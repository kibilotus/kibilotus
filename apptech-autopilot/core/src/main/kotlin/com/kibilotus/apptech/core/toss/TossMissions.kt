package com.kibilotus.apptech.core.toss

import com.kibilotus.apptech.core.Blocker
import com.kibilotus.apptech.core.FailureCode
import com.kibilotus.apptech.core.Mission
import com.kibilotus.apptech.core.Period
import com.kibilotus.apptech.core.Selector.Companion.contains
import com.kibilotus.apptech.core.Selector.Companion.regex
import com.kibilotus.apptech.core.Selector.Companion.text
import com.kibilotus.apptech.core.Step

/**
 * 토스 미션 정의.
 *
 * 화면 문구 근거: 2026-10-13 사용자 스크린샷
 * - 하단 탭: 홈 / 혜택 / 쇼핑 / 증권 / 전체
 * - 출석체크: 증권 탭 → '맞춤형 서비스' 목록의 "출석체크하고 적립금 받기" (목록이 "다른 서비스 더 보기 1/2"로 순환)
 * - 버튼 누르기: 혜택 탭 → "버튼 누르기" → "매일 버튼 누르고 8번 포인트를 받아요", 버튼 "쿠팡에서 5원 받기",
 *   회차 표시 "5회차 진행중 / 2시 59분까지" → 3시간 x 8회차로 판단
 *
 * ⚠️ 아직 확인 못 한 부분 (TODO 표시): 출석체크 화면 내부, 쿠팡 이동 후 복귀/지급 확인 화면.
 *    접근성 트리상 text/desc 구분과 클릭 가능 노드 구조도 앱의 [UI 덤프]로 확인 필요.
 */
object TossMissions {
    const val PACKAGE = "viva.republica.toss"

    /** 어떤 미션이든 이 화면이 보이면 즉시 중단 (재시도해도 해결 안 되는 상태) */
    val blockers = listOf(
        Blocker(regex("비밀번호를? ?입력|암호를? ?입력"), FailureCode.APP_LOCKED),
        Blocker(regex("원격 ?제어|원격 ?지원 앱|접근성 (권한|서비스).*(감지|위험|종료)|보안 위협"), FailureCode.AUTOMATION_DETECTED),
    )

    /** 보상 안내 문구 ("10원 받았어요" 등). 잔액 같은 다른 금액을 읽지 않도록 '받았/적립'이 붙은 문구만 */
    private val rewardMessage = regex("[0-9][0-9,]*\\s*(원|포인트).{0,12}(받았|적립)")

    /** 토스 실행 → 첫 화면 팝업 정리 → 하단 탭 이동 */
    private fun openTab(tab: String) = listOf(
        Step.Launch(PACKAGE),
        Step.Tap(text("닫기") or text("다음에 하기") or text("오늘 하루 보지 않기"), timeoutMs = 2_000, optional = true),
        Step.Tap(text(tab), timeoutMs = 15_000),
        Step.Pause(800, 2_000),
    )

    private val attendanceEntry = contains("출석체크하고")
    private val moreServices = contains("다른 서비스 더 보기")

    /** 매일 출석체크 (증권 탭) */
    val attendance = Mission(
        id = "toss.attendance",
        title = "토스 출석체크",
        packageName = PACKAGE,
        blockers = blockers,
        period = Period.Daily,
        steps = openTab("증권") + listOf(
            Step.ScrollTo(attendanceEntry or moreServices, maxScrolls = 10),
            // 맞춤형 서비스 목록이 순환하므로, 이번 페이지에 없으면 '다른 서비스 더 보기'로 넘긴다
            Step.If(attendanceEntry, then = emptyList(), otherwise = listOf(Step.Tap(moreServices), Step.Pause(800, 1_500))),
            Step.Tap(attendanceEntry),
            Step.Pause(1_500, 2_500),
            // TODO(덤프 필요): 출석체크 화면 내부 문구
            Step.FinishIfPresent(regex("출석 ?완료|이미 출석|오늘(은)? 출석(을)? (했|완료)|내일 (다시|또) (만나|와)")),
            Step.Tap(regex("^출석 ?(체크)?하기|^출석하고|적립금 받기$|^받기$")),
            Step.WaitFor(regex("출석 ?완료|받았어요|적립(됐|되었|완료)")),
            Step.ReadPoints(rewardMessage),
        ),
    )

    private val rewardButton = regex("[0-9][0-9,]*원 받기$")

    /** 버튼 누르고 포인트 받기 (혜택 탭, 3시간 회차 x 하루 8번) */
    val pressButton = Mission(
        id = "toss.press_button",
        title = "토스 버튼 누르고 포인트 받기",
        packageName = PACKAGE,
        blockers = blockers,
        period = Period.EveryHours(3),
        steps = openTab("혜택") + listOf(
            Step.ScrollTo(text("버튼 누르기")),
            Step.Tap(text("버튼 누르기")),
            Step.WaitFor(contains("버튼 누르고")),
            Step.FinishIfPresent(regex("오늘 기회를 다 썼|8번 (모두|다) 받")),
            // 이번 회차 보상 버튼("쿠팡에서 5원 받기")이 없으면 이미 받은 것으로 본다
            Step.FinishIfAbsent(rewardButton),
            Step.ReadPoints(rewardButton, pattern = Regex("""([0-9][0-9,]*)원 받기""")),
            Step.Tap(rewardButton),
            // 쿠팡 페이지에 사람처럼 잠시 머문 뒤 토스로 복귀
            Step.Pause(6_000, 12_000),
            Step.ReturnToApp(PACKAGE),
            // TODO(덤프 필요): 복귀 후 지급 안내 팝업 문구
            Step.Tap(text("확인") or text("닫기"), timeoutMs = 3_000, optional = true),
            // 보상 버튼이 사라져야(또는 다음 회차 안내로 바뀌어야) 지급 완료로 판단
            Step.WaitUntilGone(rewardButton, timeoutMs = 10_000),
        ),
    )

    val all = listOf(attendance, pressButton)
}
