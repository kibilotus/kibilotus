package com.kibilotus.apptech.app

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.view.accessibility.AccessibilityEvent

/**
 * 시스템이 바인딩하는 접근성 서비스.
 * - [AccessibilityDriver]가 화면 조회/제스처에 이 인스턴스를 사용한다
 * - 시스템이 계속 바인딩하므로 프로세스가 상주한다. 그래서 잠금 해제(USER_PRESENT) 수신을 여기에 등록한다
 *   (Android 8+에서는 USER_PRESENT를 manifest 수신자로 받을 수 없다)
 */
class AutomationService : AccessibilityService() {

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Autopilot.onTrigger(context, Autopilot.Source.UNLOCK)
        }
    }
    private var receiverRegistered = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(unlockReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(unlockReceiver, filter)
        }
        receiverRegistered = true
        AlarmScheduler.scheduleNext(this)
        Autopilot.onTrigger(this, Autopilot.Source.SERVICE_CONNECTED)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        release()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun release() {
        instance = null
        if (receiverRegistered) {
            unregisterReceiver(unlockReceiver)
            receiverRegistered = false
        }
    }

    companion object {
        /** 사용자가 설정에서 서비스를 켜 두었을 때만 non-null */
        @Volatile
        var instance: AutomationService? = null
            private set
    }
}
