package com.kibilotus.apptech.core

/**
 * 미션을 구성하는 단계. 화면 문구/구조가 바뀌면 코드 로직이 아니라 이 정의만 고치면 되도록
 * 선언형으로 설계했다.
 */
sealed interface Step {
    val description: String

    /** 앱 실행 후 해당 패키지가 포그라운드가 될 때까지 대기 */
    data class Launch(val packageName: String, val deepLink: String? = null, val timeoutMs: Long = 15_000) : Step {
        override val description get() = "launch $packageName" + (deepLink?.let { " ($it)" } ?: "")
    }

    data class WaitFor(val selector: Selector, val timeoutMs: Long = 10_000) : Step {
        override val description get() = "waitFor $selector"
    }

    /** 요소를 찾아 탭. [optional]이면 못 찾아도 실패하지 않음 */
    data class Tap(val selector: Selector, val timeoutMs: Long = 10_000, val optional: Boolean = false) : Step {
        override val description get() = "tap $selector" + if (optional) " (optional)" else ""
    }

    /** 요소가 보일 때까지 스크롤 */
    data class ScrollTo(val selector: Selector, val maxScrolls: Int = 8) : Step {
        override val description get() = "scrollTo $selector"
    }

    data class Back(val times: Int = 1) : Step {
        override val description get() = "back x$times"
    }

    /** 사람처럼 보이도록 랜덤 대기 */
    data class Pause(val minMs: Long, val maxMs: Long = minMs) : Step {
        override val description get() = "pause $minMs..${maxMs}ms"
    }

    /** 요소가 있으면 [status]로 미션을 종료 (예: '출석 완료' 문구 → ALREADY_DONE) */
    data class FinishIfPresent(
        val selector: Selector,
        val status: Status = Status.ALREADY_DONE,
        val timeoutMs: Long = 2_000,
    ) : Step {
        override val description get() = "finishIfPresent($status) $selector"
    }

    /** 요소 존재 여부에 따라 분기 (팝업, 광고 등 선택적 화면 처리) */
    data class If(
        val selector: Selector,
        val then: List<Step>,
        val otherwise: List<Step> = emptyList(),
        val timeoutMs: Long = 2_000,
    ) : Step {
        override val description get() = "if $selector"
    }

    /** 요소가 사라질 때까지 대기 (광고 재생 등) */
    data class WaitUntilGone(val selector: Selector, val timeoutMs: Long = 60_000) : Step {
        override val description get() = "waitUntilGone $selector"
    }

    /** 화면 문구에서 획득 포인트를 읽는다. 못 읽어도 실패하지 않음 */
    data class ReadPoints(
        val selector: Selector,
        val pattern: Regex = Regex("""([0-9][0-9,]*)\s*(원|포인트|P)"""),
        val timeoutMs: Long = 3_000,
    ) : Step {
        override val description get() = "readPoints $selector"
    }
}
