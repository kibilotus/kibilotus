package com.kibilotus.apptech.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 앱 내부 전용: 슬롯 알람, 카운트다운 취소, 실행 중지 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ALARM -> {
                AlarmScheduler.scheduleNext(context)
                Autopilot.onTrigger(context, Autopilot.Source.ALARM)
            }
            ACTION_CANCEL -> Autopilot.cancelCountdown(context)
            ACTION_STOP -> Autopilot.stop()
        }
    }

    companion object {
        const val ACTION_ALARM = "com.kibilotus.apptech.ALARM"
        const val ACTION_CANCEL = "com.kibilotus.apptech.CANCEL_COUNTDOWN"
        const val ACTION_STOP = "com.kibilotus.apptech.STOP"
    }
}

/** 재부팅/앱 업데이트/시간 변경 시 알람 재등록 (알람은 재부팅하면 사라진다) */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmScheduler.scheduleNext(context)
    }
}
