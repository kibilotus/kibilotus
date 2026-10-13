package com.kibilotus.apptech.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.kibilotus.apptech.core.schedule.Jitter
import java.time.Instant

/** 다음 슬롯(00/02/04…시) + 1~5분 지터에 알람을 예약한다 */
object AlarmScheduler {
    private val jitter = Jitter()

    fun scheduleNext(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(AlarmManager::class.java)
        val at = Autopilot.schedule.nextSlotStart(Instant.now()).toInstant().plus(jitter.next()).toEpochMilli()
        val pi = PendingIntent.getBroadcast(
            app,
            0,
            Intent(app, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_ALARM),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (canScheduleExact(app)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            // 정확한 알람 권한이 없으면 Doze 중 수 분~수십 분 늦어질 수 있음
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
        AutopilotPrefs(app).nextAlarmAtMs = at
    }

    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
}
