package com.kibilotus.apptech.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * 시스템이 바인딩하는 접근성 서비스. 이벤트를 직접 처리하지 않고,
 * [AccessibilityDriver]가 화면 조회/제스처를 위해 이 인스턴스를 사용한다.
 */
class AutomationService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        /** 사용자가 설정에서 서비스를 켜 두었을 때만 non-null */
        @Volatile
        var instance: AutomationService? = null
            private set
    }
}
