package com.kibilotus.apptech.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 설정/상태 화면.
 * - 권한 설정: 접근성, 알림, 정확한 알람, 배터리 최적화 예외
 * - 셀렉터 보정용 화면 덤프
 * - 수동 실행/중지
 */
class MainActivity : Activity() {

    private val dumpWorker = Executors.newSingleThreadExecutor()
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
        status = TextView(this).apply { textSize = 14f }
        root.addView(status)
        root.addView(button("접근성 서비스 설정") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        root.addView(button("알림 권한 / 정확한 알람 / 배터리 최적화 예외") { requestPermissions() })
        root.addView(button("10초 후 화면 덤프 (그 사이 토스 화면으로 이동)") { dumpLater() })
        root.addView(button("덤프 공유") { shareLastDump() })
        root.addView(button("미완료 미션 지금 실행") { Autopilot.runNow(this) })
        root.addView(button("실행 중지") { Autopilot.stop() })
        logView = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
        }
        root.addView(ScrollView(this).apply { addView(logView) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        Autopilot.listener = { msg -> log(msg); refreshStatus() }
        refreshStatus()
    }

    override fun onPause() {
        Autopilot.listener = null
        super.onPause()
    }

    override fun onDestroy() {
        dumpWorker.shutdownNow()
        super.onDestroy()
    }

    private fun refreshStatus() {
        val prefs = AutopilotPrefs(this)
        val now = Instant.now()
        val pending = Autopilot.pendingMissions(prefs, now).map { it.title }
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.KOREA)
        status.text = buildString {
            appendLine("접근성 서비스: ${ok(AutomationService.instance != null)}")
            appendLine("알림: ${ok(Notifier.enabled(this@MainActivity))}   정확한 알람: ${ok(AlarmScheduler.canScheduleExact(this@MainActivity))}")
            appendLine("배터리 최적화 예외: ${ok(ignoringBatteryOptimizations())}")
            appendLine("다음 알람: ${prefs.nextAlarmAtMs.takeIf { it > 0 }?.let { fmt.format(Date(it)) } ?: "-"}")
            appendLine("이번 주기 미완료: ${pending.ifEmpty { listOf("없음") }.joinToString()}")
            prefs.lastReport?.let { append("최근 결과:\n").append(it) }
        }
    }

    private fun ok(b: Boolean) = if (b) "✅" else "❌"

    private fun ignoringBatteryOptimizations() =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun requestPermissions() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

            !AlarmScheduler.canScheduleExact(this) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))

            !ignoringBatteryOptimizations() ->
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))

            else -> log("필요한 권한이 모두 허용되어 있습니다")
        }
        AlarmScheduler.scheduleNext(this)
    }

    private fun dumpLater() {
        val service = AutomationService.instance ?: return log("접근성 서비스가 꺼져 있습니다")
        log("10초 후 현재 화면을 덤프합니다. 토스에서 덤프할 화면을 띄워 두세요.")
        dumpWorker.execute {
            Thread.sleep(10_000)
            val dump = UiDumper(service, dumpDir).dump("screen")
            main.post {
                if (dump == null) {
                    log("덤프 실패: 활성 창을 읽을 수 없음 (토스가 접근성 조회를 막았을 수 있음)")
                } else {
                    lastDump = dump.text
                    log(
                        "덤프 저장: ${dump.textFile.name} (${dump.text.lines().size}줄)\n" +
                            "스크린샷: ${dump.screenshot?.name ?: "실패 - ${dump.screenshotError}"}",
                    )
                }
            }
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
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

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    private fun log(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        logView.append("$time $msg\n")
    }
}
