package com.kibilotus.apptech.app

import android.app.KeyguardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.kibilotus.apptech.core.Mission
import com.kibilotus.apptech.core.MissionResult
import com.kibilotus.apptech.core.MissionRunner
import com.kibilotus.apptech.core.schedule.BatchReport
import com.kibilotus.apptech.core.schedule.BatchRunner
import com.kibilotus.apptech.core.schedule.SlotSchedule
import com.kibilotus.apptech.core.schedule.TriggerPolicy
import com.kibilotus.apptech.core.toss.TossMissions
import java.io.File
import java.time.Instant
import java.time.ZonedDateTime
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.random.Random

/**
 * 스케줄 실행 컨트롤러 (프로세스 싱글턴).
 *
 * 트리거(슬롯 알람 / 잠금 해제 / 서비스 연결) → [TriggerPolicy] 판단
 *   → 잠김: 다음 잠금 해제까지 대기
 *   → 실행 가능: 10초 취소 가능 카운트다운 → 배치 실행 (미완료 미션만)
 *
 * 프로세스는 시스템이 바인딩한 접근성 서비스 덕분에 계속 살아 있으므로 별도 포그라운드 서비스가 필요 없다.
 * 모든 public 메서드는 메인 스레드에서 호출한다.
 */
object Autopilot {
    enum class Source { ALARM, UNLOCK, SERVICE_CONNECTED }

    private const val COUNTDOWN_SECONDS = 10

    val schedule = SlotSchedule()
    val missions: List<Mission> = TossMissions.all

    private val policy = TriggerPolicy(schedule)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var countdown: Runnable? = null
    private var running: Future<*>? = null

    /** MainActivity가 화면 로그를 받기 위해 등록 (메인 스레드에서 호출됨) */
    var listener: ((String) -> Unit)? = null

    val isBusy: Boolean get() = countdown != null || running?.isDone == false

    fun onTrigger(context: Context, source: Source) {
        val app = context.applicationContext
        if (isBusy) return
        val prefs = AutopilotPrefs(app)
        val now = Instant.now()
        val pending = pendingMissions(prefs, now)
        val ready = deviceReady(app)
        val decision = policy.decide(now, ready, pending.size, prefs.lastAttemptedSlotMs)
        log("trigger $source → $decision (미완료 ${pending.size}개, ready=$ready)")
        if (decision != TriggerPolicy.Decision.START_COUNTDOWN) return

        if (AutomationService.instance == null) {
            markAttempted(prefs, now)
            Notifier.warning(app, "접근성 서비스가 꺼져 있어 토스 미션을 실행하지 못했습니다. 앱에서 다시 켜 주세요.")
            return
        }
        // 알림이 꺼져 있으면 취소 기회 없이 화면을 빼앗게 되므로 자동 실행하지 않는다
        if (!Notifier.enabled(app)) {
            log("알림 권한이 없어 자동 실행을 건너뜀")
            return
        }
        startCountdown(app, pending.size)
    }

    fun cancelCountdown(context: Context) {
        val app = context.applicationContext
        countdown?.let(main::removeCallbacks)
        countdown = null
        Notifier.cancelCountdown(app)
        markAttempted(AutopilotPrefs(app), Instant.now())
        log("카운트다운 취소 → 이번 슬롯은 건너뜀")
    }

    /** 앱 화면의 '지금 실행' (슬롯 규칙 무시, 미완료 미션만) */
    fun runNow(context: Context) {
        if (isBusy) return log("이미 실행 중입니다")
        startBatch(context.applicationContext)
    }

    fun stop() {
        running?.cancel(true)
    }

    fun pendingMissions(prefs: AutopilotPrefs, now: Instant): List<Mission> =
        BatchRunner(missions, prefs, { error("not used") }).pending(now.atZone(schedule.zone))

    fun deviceReady(context: Context): Boolean {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        return power.isInteractive && !keyguard.isKeyguardLocked
    }

    private fun startCountdown(app: Context, pendingCount: Int) {
        Notifier.countdown(app, pendingCount, COUNTDOWN_SECONDS)
        val task = Runnable {
            countdown = null
            Notifier.cancelCountdown(app)
            // 카운트다운 중에 화면을 끄거나 잠갔으면 다음 잠금 해제 때 다시 시도
            if (!deviceReady(app)) {
                log("카운트다운 중 기기가 잠김 → 잠금 해제 대기")
                return@Runnable
            }
            startBatch(app)
        }
        countdown = task
        main.postDelayed(task, COUNTDOWN_SECONDS * 1000L)
    }

    private fun startBatch(app: Context) {
        val service = AutomationService.instance ?: return log("접근성 서비스가 꺼져 있습니다")
        if (!deviceReady(app)) return log("기기가 잠겨 있어 실행할 수 없습니다")
        val prefs = AutopilotPrefs(app)
        val startedAt = Instant.now()
        markAttempted(prefs, startedAt)
        Notifier.running(app)
        log("── 배치 시작 ──")
        running = worker.submit {
            val driver = AccessibilityDriver(service)
            val runner = MissionRunner(driver, logger = ::log)
            val batch = BatchRunner(
                missions,
                prefs,
                { mission -> runner.run(mission).also { saveFailure(app, it) } },
                pauseBetween = { Thread.sleep(Random.nextLong(3_000, 10_000)) },
            )
            try {
                val report = batch.run(ZonedDateTime.ofInstant(startedAt, schedule.zone))
                driver.home()
                val summary = summarize(report)
                prefs.lastReport = summary
                log("── 결과 ──\n$summary")
                main.post { Notifier.report(app, report, summary) }
            } finally {
                main.post { Notifier.cancelRunning(app) }
            }
        }
    }

    private fun markAttempted(prefs: AutopilotPrefs, now: Instant) {
        prefs.lastAttemptedSlotMs = schedule.slotStart(now).toInstant().toEpochMilli()
    }

    private fun summarize(report: BatchReport): String = buildString {
        report.results.forEach { r ->
            append(r.missionId).append(": ").append(r.status)
            r.points?.let { append(" +").append(it).append("원") }
            r.code?.let { append(" [").append(it).append(']') }
            r.failedStep?.let { append(" @ ").append(it) }
            append('\n')
        }
        if (report.skipped.isNotEmpty()) append("스킵(이미 완료): ").append(report.skipped.joinToString())
    }.trim()

    /** 실패 시 로그 + 화면 트리를 파일로 남긴다 (최근 50개 유지) */
    private fun saveFailure(app: Context, result: MissionResult) {
        val dump = result.screenDump ?: return
        val dir = File(app.getExternalFilesDir(null), "dumps").apply { mkdirs() }
        File(dir, "fail_${result.missionId}_${result.startedAtMs}.txt")
            .writeText(UiDumper.mask(result.log.joinToString("\n") + "\n\n" + dump))
        dir.listFiles { f -> f.name.startsWith("fail_") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(50)
            ?.forEach { it.delete() }
    }

    private fun log(msg: String) {
        android.util.Log.i("Autopilot", msg)
        main.post { listener?.invoke(msg) }
    }
}
