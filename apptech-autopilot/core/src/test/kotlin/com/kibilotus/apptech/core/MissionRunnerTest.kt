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

    override fun back() = true
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

    private val home = { screen(button("홈"), button("혜택"), button("전체")) }
    private val benefitTop = { screen(card("만보기"), card("행운퀴즈")) }
    private val benefitScrolled = { screen(card("매일 출석체크하고 포인트 받기"), card("버튼 누르고 포인트 받기")) }

    @Test
    fun `attendance succeeds and reads points`() {
        val driver = FakeDriver(
            screens = mapOf(
                "home" to home,
                "benefit" to benefitTop,
                "benefit2" to benefitScrolled,
                "attendance" to { screen(button("출석체크하기")) },
                "done" to { screen(UiNode(text = "10원 받았어요", bounds = Bounds(0, 0, 500, 100))) },
            ),
            transitions = mapOf(
                ("home" to "혜택") to "benefit",
                ("benefit2" to "매일 출석체크하고 포인트 받기") to "attendance",
                ("attendance" to "출석체크하기") to "done",
            ),
            scrollTransitions = mapOf("benefit" to "benefit2"),
        )

        val result = runner(driver).run(TossMissions.attendance)

        assertEquals(Status.SUCCESS, result.status, result.log.joinToString("\n"))
        assertEquals(10, result.points)
        assertTrue(result.isDoneForToday)
    }

    @Test
    fun `attendance already done is skipped`() {
        val driver = FakeDriver(
            screens = mapOf(
                "home" to home,
                "benefit" to benefitScrolled,
                "attendance" to { screen(UiNode(text = "오늘 출석 완료! 내일 또 만나요", bounds = Bounds(0, 0, 500, 100))) },
            ),
            transitions = mapOf(
                ("home" to "혜택") to "benefit",
                ("benefit" to "매일 출석체크하고 포인트 받기") to "attendance",
            ),
        )

        val result = runner(driver).run(TossMissions.attendance)

        assertEquals(Status.ALREADY_DONE, result.status)
        assertTrue(result.isDoneForToday)
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
            screens = mapOf("home" to home, "benefit" to benefitTop),
            transitions = mapOf(("home" to "혜택") to "benefit"),
        )

        val result = runner(driver).run(TossMissions.attendance)

        assertEquals(Status.FAILED, result.status)
        assertEquals(FailureCode.ELEMENT_NOT_FOUND, result.code)
        assertTrue(result.failedStep!!.startsWith("scrollTo"))
        assertTrue(result.screenDump!!.contains("만보기"))
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
