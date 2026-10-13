package com.kibilotus.apptech.core

import com.kibilotus.apptech.core.toss.TossMissions
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 화면 이름 → UI 트리, (화면, 탭한 라벨) → 다음 화면 으로 동작하는 가짜 기기 */
private class FakeDriver(
    private val screens: Map<String, () -> UiNode>,
    private val transitions: Map<Pair<String, String>, String>,
    var current: String = "launcher",
    private val pkgOf: (String) -> String = { if (it == "launcher") "launcher" else TossMissions.PACKAGE },
    private val scrollTransitions: Map<String, String> = emptyMap(),
    private val backTransitions: Map<String, String> = emptyMap(),
) : Driver {
    val taps = mutableListOf<Triple<String, Int, Int>>()

    override fun snapshot() = screens.getValue(current)()
    override fun foregroundPackage() = pkgOf(current)
    override fun launch(packageName: String, deepLink: String?): Boolean {
        current = "home"; return true
    }

    override fun tap(node: UiNode, x: Int, y: Int): Boolean {
        val label = node.walk().map { it.label }.firstOrNull { it.isNotEmpty() } ?: ""
        taps += Triple(label, x, y)
        transitions[current to label]?.let { current = it }
        return true
    }

    override fun scrollForward(): Boolean {
        current = scrollTransitions[current] ?: return false; return true
    }

    override fun back(): Boolean {
        backTransitions[current]?.let { current = it }
        return true
    }
    override fun home() = true
}

private class FakeClock : Clock {
    var now = 0L
    override fun nowMs() = now
    override fun sleep(ms: Long) {
        now += ms
    }
}

private var y = 0
private fun button(label: String, clickable: Boolean = true) =
    UiNode(text = label, clickable = clickable, bounds = Bounds(0, y, 400, y + 100).also { y += 100 })

/** 텍스트 노드는 클릭 불가, 부모가 클릭 가능한 전형적인 WebView/Compose 구조 */
private fun card(label: String) = UiNode(
    clickable = true,
    bounds = Bounds(0, y, 1000, y + 200),
    children = listOf(UiNode(text = label, bounds = Bounds(20, y + 20, 300, y + 80))),
).also { y += 200 }

private fun screen(vararg nodes: UiNode) = UiNode(className = "FrameLayout", bounds = Bounds(0, 0, 1080, 2400), children = nodes.toList())

class MissionRunnerTest {
    private val clock = FakeClock()
    private fun runner(driver: Driver) = MissionRunner(driver, clock, Random(42), logger = ::println)

    private val home = { screen(button("홈"), button("혜택"), button("쇼핑"), button("증권"), button("전체")) }

    // 증권 탭: 맞춤형 서비스 목록이 순환 (1/2 페이지에는 출석체크 없음)
    private val stockPage1 = { screen(card("내 숨겨진 투자본능 테스트"), card("다른 서비스 더 보기 1 / 2")) }
    private val stockPage2 = { screen(card("출석체크하고 적립금 받기"), card("다른 서비스 더 보기 2 / 2")) }

    private fun attendanceDriver(attendanceScreen: () -> UiNode) = FakeDriver(
        screens = mapOf(
            "home" to home,
            "stock1" to stockPage1,
            "stock2" to stockPage2,
            "attendance" to attendanceScreen,
            "done" to { screen(UiNode(text = "10원 받았어요", bounds = Bounds(0, 0, 500, 100))) },
        ),
        transitions = mapOf(
            ("home" to "증권") to "stock1",
            ("stock1" to "다른 서비스 더 보기 1 / 2") to "stock2",
            ("stock2" to "출석체크하고 적립금 받기") to "attendance",
            ("attendance" to "출석체크하기") to "done",
        ),
    )

    @Test
    fun `attendance rotates service list, succeeds and reads points`() {
        val result = runner(attendanceDriver { screen(button("출석체크하기")) }).run(TossMissions.attendance)

        assertEquals(Status.SUCCESS, result.status, result.log.joinToString("\n"))
        assertEquals(10, result.points)
        assertTrue(result.isDoneForToday)
    }

    @Test
    fun `attendance already done is skipped`() {
        val driver = attendanceDriver { screen(UiNode(text = "오늘 출석 완료! 내일 또 만나요", bounds = Bounds(0, 0, 500, 100))) }

        val result = runner(driver).run(TossMissions.attendance)

        assertEquals(Status.ALREADY_DONE, result.status)
        assertNull(result.points)
    }

    private val benefit = { screen(card("행운복권"), card("버튼 누르기"), card("만보기")) }
    private val buttonTitle = UiNode(text = "매일 버튼 누르고\n8번 포인트를 받아요", bounds = Bounds(0, 0, 800, 200))

    private fun buttonDriver(button: () -> UiNode, afterReturn: () -> UiNode) = FakeDriver(
        screens = mapOf(
            "home" to home,
            "benefit" to benefit,
            "button" to button,
            "coupang" to { screen(button("쿠팡 상품")) },
            "returned" to afterReturn,
        ),
        transitions = mapOf(
            ("home" to "혜택") to "benefit",
            ("benefit" to "버튼 누르기") to "button",
            ("button" to "쿠팡에서 5원 받기") to "coupang",
        ),
        pkgOf = { if (it == "coupang") "com.coupang.mobile" else TossMissions.PACKAGE },
        backTransitions = mapOf("coupang" to "returned"),
    )

    @Test
    fun `press button visits coupang, returns and confirms reward`() {
        val driver = buttonDriver(
            button = { screen(buttonTitle, button("쿠팡에서 5원 받기")) },
            afterReturn = { screen(buttonTitle, UiNode(text = "다음 회차 오후 3시", bounds = Bounds(0, 300, 500, 400))) },
        )

        val result = runner(driver).run(TossMissions.pressButton)

        assertEquals(Status.SUCCESS, result.status, result.log.joinToString("\n"))
        assertEquals(5, result.points)
    }

    @Test
    fun `press button without reward button means this window is done`() {
        val driver = buttonDriver(
            button = { screen(buttonTitle, UiNode(text = "6회차 오후 3시", bounds = Bounds(0, 300, 500, 400))) },
            afterReturn = { screen() },
        )

        val result = runner(driver).run(TossMissions.pressButton)

        assertEquals(Status.ALREADY_DONE, result.status)
    }

    @Test
    fun `press button fails when reward is not confirmed`() {
        val withButton = { screen(buttonTitle, button("쿠팡에서 5원 받기")) }
        val result = runner(buttonDriver(button = withButton, afterReturn = withButton)).run(TossMissions.pressButton)

        assertEquals(Status.FAILED, result.status)
        assertEquals(FailureCode.NOT_CONFIRMED, result.code)
        assertNull(result.points)
    }

    @Test
    fun `app lock screen blocks the mission`() {
        val driver = FakeDriver(
            screens = mapOf("home" to { screen(UiNode(text = "비밀번호를 입력해주세요", bounds = Bounds(0, 0, 500, 100))) }),
            transitions = emptyMap(),
        )

        val result = runner(driver).run(TossMissions.attendance)

        assertEquals(Status.BLOCKED, result.status)
        assertEquals(FailureCode.APP_LOCKED, result.code)
        assertNotNull(result.screenDump)
    }

    @Test
    fun `missing element fails with step and screen dump`() {
        val driver = FakeDriver(
            screens = mapOf("home" to home, "stock" to { screen(card("실시간 거래대금 차트")) }),
            transitions = mapOf(("home" to "증권") to "stock"),
        )

        val result = runner(driver).run(TossMissions.attendance)

        assertEquals(Status.FAILED, result.status)
        assertEquals(FailureCode.ELEMENT_NOT_FOUND, result.code)
        assertTrue(result.failedStep!!.startsWith("scrollTo"))
        assertTrue(result.screenDump!!.contains("거래대금"))
    }

    @Test
    fun `app that never reaches foreground fails`() {
        val driver = FakeDriver(screens = mapOf("home" to home), transitions = emptyMap(), pkgOf = { "launcher" })

        val result = runner(driver).run(TossMissions.attendance)

        assertEquals(FailureCode.APP_NOT_FOREGROUND, result.code)
    }

    @Test
    fun `tap targets the clickable ancestor at a randomized point`() {
        val c = card("버튼 누르고 포인트 받기")
        val driver = FakeDriver(screens = mapOf("home" to { screen(c) }), transitions = emptyMap())
        val mission = Mission("t", "t", TossMissions.PACKAGE, listOf(Step.Launch(TossMissions.PACKAGE), Step.Tap(Selector.contains("버튼"))))

        runner(driver).run(mission)

        val (_, x, y) = driver.taps.single()
        val b = c.bounds
        assertTrue(x in b.left..b.right && y in b.top..b.bottom, "tap ($x,$y) outside $b")
        // 텍스트 노드(20..300) 바깥이어도 카드 영역이면 OK — 즉 부모 카드를 탭했는지 확인
        assertTrue(x >= b.left + b.width * 0.2 && x <= b.left + b.width * 0.8)
    }

    @Test
    fun `reward selector ignores balances`() {
        val node = UiNode(text = "내 포인트 12,345원", bounds = Bounds(0, 0, 10, 10))
        assertNull(Selector.regex("[0-9][0-9,]*\\s*(원|포인트).{0,12}(받았|적립)").findIn(node))
    }
}
