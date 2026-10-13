package com.kibilotus.apptech.core

/**
 * 기기 제어 추상화. Android에서는 AccessibilityService가 구현하고, 테스트에서는 가짜 구현을 쓴다.
 * 모든 메서드는 블로킹이며 백그라운드 스레드에서 호출된다.
 */
interface Driver {
    /** 현재 활성 창의 UI 트리. 창이 없거나 접근할 수 없으면 null */
    fun snapshot(): UiNode?

    /** 현재 포그라운드 앱의 패키지명 */
    fun foregroundPackage(): String?

    /** 앱 실행 (deepLink가 있으면 딥링크로 진입) */
    fun launch(packageName: String, deepLink: String? = null): Boolean

    /** [node] 영역 안의 (x, y)를 탭한다 */
    fun tap(node: UiNode, x: Int, y: Int): Boolean

    /** 화면을 한 페이지 아래로 스크롤 */
    fun scrollForward(): Boolean

    fun back(): Boolean

    fun home(): Boolean
}

interface Clock {
    fun nowMs(): Long
    fun sleep(ms: Long)

    object System : Clock {
        override fun nowMs() = java.lang.System.currentTimeMillis()
        override fun sleep(ms: Long) = Thread.sleep(ms)
    }
}
