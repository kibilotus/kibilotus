package com.kibilotus.apptech.core.toss

import com.kibilotus.apptech.core.Blocker
import com.kibilotus.apptech.core.FailureCode
import com.kibilotus.apptech.core.Mission
import com.kibilotus.apptech.core.Selector.Companion.contains
import com.kibilotus.apptech.core.Selector.Companion.regex
import com.kibilotus.apptech.core.Selector.Companion.text
import com.kibilotus.apptech.core.Step

/**
 * 토스 미션 정의.
 *
 * ⚠️ 아래 셀렉터(문구)는 **실기기에서 아직 검증되지 않은 초기값**이다.
 * 앱의 [UI 덤프] 기능으로 실제 화면 트리를 뽑아 이 파일을 보정해야 한다. (docs/STAGE1.md 참고)
 * 문구 변경에 대비해 가능한 한 `or`로 후보를 여러 개 둔다.
 */
object TossMissions {
    const val PACKAGE = "viva.republica.toss"

    /** 어떤 미션이든 이 화면이 보이면 즉시 중단 (재시도해도 해결 안 되는 상태) */
    val blockers = listOf(
        Blocker(regex("비밀번호를? ?입력|암호를? ?입력"), FailureCode.APP_LOCKED),
        Blocker(regex("원격 ?제어|원격 ?지원 앱|접근성 (권한|서비스).*(감지|위험|종료)|보안 위협"), FailureCode.AUTOMATION_DETECTED),
    )

    private val benefitTab = text("혜택")

    /** 보상 안내 문구 ("10원 받았어요" 등). 잔액 같은 다른 금액을 읽지 않도록 '받았/적립'이 붙은 문구만 */
    private val rewardMessage = regex("[0-9][0-9,]*\\s*(원|포인트).{0,12}(받았|적립)")

    /** 토스 실행 → 하단 '혜택' 탭 진입 */
    private val openBenefitTab = listOf(
        Step.Launch(PACKAGE),
        // 토스 첫 화면의 이벤트/공지 팝업 닫기
        Step.Tap(text("닫기") or text("다음에 하기") or text("오늘 하루 보지 않기"), timeoutMs = 2_000, optional = true),
        Step.Tap(benefitTab, timeoutMs = 15_000),
        Step.Pause(800, 2_000),
    )

    /** 매일 출석체크 */
    val attendance = Mission(
        id = "toss.attendance",
        title = "토스 출석체크",
        packageName = PACKAGE,
        blockers = blockers,
        steps = openBenefitTab + listOf(
            Step.ScrollTo(contains("출석")),
            Step.Tap(contains("출석")),
            Step.Pause(1_000, 2_000),
            // 이미 출석한 상태
            Step.FinishIfPresent(regex("출석 ?완료|오늘(은)? (이미 )?출석|내일 (다시 )?(만나요|와)|이미 받")),
            Step.Tap(regex("출석 ?(체크)?하기|출석하고|포인트 받기")),
            Step.WaitFor(regex("출석 ?완료|받았어요|적립")),
            Step.ReadPoints(rewardMessage),
        ),
    )

    /** 버튼 누르고 포인트 받기 (광고 시청이 끼어 있을 수 있음) */
    val pressButton = Mission(
        id = "toss.press_button",
        title = "토스 버튼 누르고 포인트 받기",
        packageName = PACKAGE,
        blockers = blockers,
        steps = openBenefitTab + listOf(
            Step.ScrollTo(contains("버튼 누르") or contains("버튼누르")),
            Step.Tap(contains("버튼 누르") or contains("버튼누르")),
            Step.Pause(1_000, 2_000),
            Step.FinishIfPresent(regex("내일 (다시|또)|이미 받|모두 받|오늘(의)? .*끝")),
            Step.Tap(regex("버튼 누르기|포인트 받기|받기$")),
            // 광고가 뜨는 경우: 광고가 끝날 때까지 기다렸다가 닫기
            Step.If(
                regex("광고|AD$"),
                then = listOf(
                    Step.WaitFor(text("닫기") or text("광고 닫기") or text("건너뛰기") or text("X"), timeoutMs = 60_000),
                    Step.Tap(text("닫기") or text("광고 닫기") or text("건너뛰기") or text("X")),
                ),
                timeoutMs = 3_000,
            ),
            Step.WaitFor(regex("받았어요|적립")),
            Step.ReadPoints(rewardMessage),
        ),
    )

    val all = listOf(attendance, pressButton)
}
