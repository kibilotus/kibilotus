package com.kibilotus.apptech.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.kibilotus.apptech.core.schedule.BatchReport

/** 카운트다운/실행 중/결과 알림. 3단계에서 텔레그램 등 외부 알림은 별도 인터페이스로 추가 */
object Notifier {
    private const val CH_ACTION = "autopilot_action"
    private const val CH_STATUS = "autopilot_status"
    private const val ID_COUNTDOWN = 1
    private const val ID_RUNNING = 2
    private const val ID_REPORT = 3
    private const val ID_WARNING = 4

    fun enabled(context: Context) = nm(context).areNotificationsEnabled()

    fun countdown(context: Context, pendingCount: Int, seconds: Int) {
        val n = builder(context, CH_ACTION)
            .setContentTitle("${seconds}초 후 토스 자동 실행")
            .setContentText("미션 ${pendingCount}개 · 실행 중에는 화면을 만지지 마세요")
            .setWhen(System.currentTimeMillis() + seconds * 1000L)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setTimeoutAfter(seconds * 1000L + 1_000)
            .setCategory(Notification.CATEGORY_REMINDER)
            .addAction(action(context, "취소", AlarmReceiver.ACTION_CANCEL))
            .build()
        nm(context).notify(ID_COUNTDOWN, n)
    }

    fun cancelCountdown(context: Context) = nm(context).cancel(ID_COUNTDOWN)

    fun running(context: Context) {
        val n = builder(context, CH_STATUS)
            .setContentTitle("토스 자동화 실행 중")
            .setContentText("화면을 만지지 마세요. 중지하면 다음 회차에 다시 시도합니다.")
            .setOngoing(true)
            .addAction(action(context, "중지", AlarmReceiver.ACTION_STOP))
            .build()
        nm(context).notify(ID_RUNNING, n)
    }

    fun cancelRunning(context: Context) = nm(context).cancel(ID_RUNNING)

    fun report(context: Context, report: BatchReport, summary: String) {
        val title = when {
            report.results.isEmpty() -> "토스: 실행할 미션 없음"
            report.allDone -> "토스: 미션 완료"
            else -> "토스: 일부 미션 실패"
        }
        val n = builder(context, CH_STATUS)
            .setContentTitle(title)
            .setContentText(summary.lineSequence().firstOrNull() ?: "")
            .setStyle(Notification.BigTextStyle().bigText(summary))
            .setAutoCancel(true)
            .build()
        nm(context).notify(ID_REPORT, n)
    }

    fun warning(context: Context, text: String) {
        val n = builder(context, CH_ACTION)
            .setContentTitle("앱테크 오토파일럿")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        nm(context).notify(ID_WARNING, n)
    }

    private fun builder(context: Context, channel: String): Notification.Builder {
        ensureChannels(context)
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentIntent(open)
    }

    private fun action(context: Context, title: String, broadcast: String): Notification.Action {
        val pi = PendingIntent.getBroadcast(
            context,
            broadcast.hashCode(),
            Intent(context, AlarmReceiver::class.java).setAction(broadcast),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(null, title, pi).build()
    }

    private fun ensureChannels(context: Context) {
        val nm = nm(context)
        if (nm.getNotificationChannel(CH_ACTION) != null) return
        // 카운트다운은 헤드업으로 보여야 취소할 수 있으므로 HIGH
        nm.createNotificationChannel(NotificationChannel(CH_ACTION, "실행 예고/경고", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, "실행 상태/결과", NotificationManager.IMPORTANCE_LOW))
    }

    private fun nm(context: Context) = context.getSystemService(NotificationManager::class.java)
}
