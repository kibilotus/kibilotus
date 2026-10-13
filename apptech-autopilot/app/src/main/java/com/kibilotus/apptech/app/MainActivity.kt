package com.kibilotus.apptech.app

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.kibilotus.apptech.core.FailureCode
import com.kibilotus.apptech.core.MissionResult
import com.kibilotus.apptech.core.MissionRunner
import com.kibilotus.apptech.core.Status
import com.kibilotus.apptech.core.toss.TossMissions
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 1단계(메커니즘 검증)용 수동 실행 화면.
 * - 화면 덤프: 셀렉터 보정용으로 토스 화면 트리를 저장/공유
 * - 지금 실행: 토스 미션을 순서대로 실행하고 결과를 표시
 * 스케줄러(2단계), DB/알림(3단계)은 아직 없다.
 */
class MainActivity : Activity() {

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private var lastDump: String? = null

    private val dumpDir by lazy { File(getExternalFilesDir(null), "dumps") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        status = TextView(this).apply { textSize = 15f }
        root.addView(status)
        root.addView(button("① 접근성 서비스 설정 열기") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(button("② 10초 후 화면 덤프 (그 사이 토스 화면으로 이동)") { dumpLater() })
        root.addView(button("③ 덤프 공유") { shareLastDump() })
        root.addView(button("④ 토스 미션 지금 실행") { runMissions() })
        logView = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
        }
        root.addView(ScrollView(this).apply { addView(logView) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        val connected = AutomationService.instance != null
        status.text = if (connected) "접근성 서비스: 연결됨 ✅" else "접근성 서비스: 꺼짐 ❌ (① 에서 켜 주세요)"
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun dumpLater() {
        val service = AutomationService.instance ?: return log("접근성 서비스가 꺼져 있습니다")
        log("10초 후 현재 화면을 덤프합니다. 토스에서 덤프할 화면을 띄워 두세요.")
        worker.execute {
            Thread.sleep(10_000)
            val dump = UiDumper(service, dumpDir).dump("screen")
            if (dump == null) {
                post("덤프 실패: 활성 창을 읽을 수 없음 (토스가 접근성 조회를 막았을 수 있음)")
                return@execute
            }
            lastDump = dump.text
            post(
                "덤프 저장: ${dump.textFile.name} (${dump.text.lines().size}줄)\n" +
                    "스크린샷: ${dump.screenshot?.name ?: "실패 - ${dump.screenshotError}"}",
            )
            bringToFront()
        }
    }

    private fun shareLastDump() {
        val text = lastDump ?: dumpDir.listFiles { f -> f.extension == "txt" }
            ?.maxByOrNull { it.lastModified() }?.readText()
            ?: return log("공유할 덤프가 없습니다")
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            // 바인더 한도(~1MB) 회피
            putExtra(Intent.EXTRA_TEXT, text.take(200_000))
        }
        startActivity(Intent.createChooser(send, "화면 덤프 공유"))
    }

    private fun runMissions() {
        val service = AutomationService.instance ?: return log("접근성 서비스가 꺼져 있습니다")
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            return log("기기가 잠겨 있어 실행할 수 없습니다 (${FailureCode.DEVICE_LOCKED})")
        }
        log("── 실행 시작 ${time()} ──")
        worker.execute {
            val driver = AccessibilityDriver(service)
            val runner = MissionRunner(driver, logger = { post(it) })
            val results = mutableListOf<MissionResult>()
            for (mission in TossMissions.all) {
                val result = runner.run(mission)
                results += result
                if (result.screenDump != null) {
                    dumpDir.mkdirs()
                    File(dumpDir, "fail_${mission.id}_${System.currentTimeMillis()}.txt")
                        .writeText(UiDumper.mask(result.log.joinToString("\n") + "\n\n" + result.screenDump))
                }
                // 앱 잠금/자동화 차단은 다음 미션도 똑같이 막히므로 중단
                if (result.status == Status.BLOCKED) break
            }
            driver.home()
            post("── 결과 ──\n" + results.joinToString("\n") { it.summary() })
            bringToFront()
        }
    }

    private fun MissionResult.summary() = buildString {
        append(missionId).append(": ").append(status)
        points?.let { append(" +").append(it) }
        code?.let { append(" [").append(it).append(']') }
        message?.let { append(" ").append(it) }
        failedStep?.let { append(" @ ").append(it) }
        append(" (").append(durationMs / 1000).append("s)")
    }

    private fun bringToFront() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    private fun post(msg: String) = main.post { log(msg) }

    private fun log(msg: String) {
        logView.append("$msg\n")
    }

    private fun time() = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
}
