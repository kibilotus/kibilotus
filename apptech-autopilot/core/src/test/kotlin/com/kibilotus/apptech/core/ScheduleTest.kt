package com.kibilotus.apptech.core

import com.kibilotus.apptech.core.schedule.BatchRunner
import com.kibilotus.apptech.core.schedule.InMemoryStateStore
import com.kibilotus.apptech.core.schedule.Jitter
import com.kibilotus.apptech.core.schedule.SlotSchedule
import com.kibilotus.apptech.core.schedule.TriggerPolicy
import com.kibilotus.apptech.core.schedule.TriggerPolicy.Decision
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScheduleTest {
    private val kst = ZoneId.of("Asia/Seoul")
    private val schedule = SlotSchedule(kst)
    private fun at(h: Int, m: Int, day: Int = 13) = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, kst).toInstant()

    @Test
    fun `slots start every two hours from midnight`() {
        assertEquals(at(0, 0), schedule.slotStart(at(0, 0)).toInstant())
        assertEquals(at(0, 0), schedule.slotStart(at(1, 59)).toInstant())
        assertEquals(at(2, 0), schedule.slotStart(at(2, 1)).toInstant())
        assertEquals(at(22, 0), schedule.slotStart(at(23, 30)).toInstant())
        assertEquals(at(0, 0, day = 14), schedule.nextSlotStart(at(23, 30)).toInstant())
        assertEquals(at(14, 0), schedule.nextSlotStart(at(12, 0)).toInstant())
    }

    @Test
    fun `odd start hour wraps to previous day`() {
        val s = SlotSchedule(kst, intervalHours = 2, startHour = 1)
        assertEquals(at(23, 0, day = 12), s.slotStart(at(0, 30)).toInstant())
        assertEquals(at(1, 0), s.slotStart(at(1, 0)).toInstant())
    }

    @Test
    fun `day uses local KST date`() {
        // 2026-10-13 00:30 KST == 2026-10-12 15:30 UTC
        assertEquals(LocalDate.of(2026, 10, 13), schedule.dayOf(at(0, 30)))
    }

    @Test
    fun `jitter stays between one and five minutes`() {
        val j = Jitter(random = Random(1))
        repeat(1000) {
            val d = j.next()
            assertTrue(d >= Duration.ofMinutes(1) && d <= Duration.ofMinutes(5), "$d")
        }
    }

    @Test
    fun `trigger policy attempts each slot once and waits for unlock`() {
        val p = TriggerPolicy(schedule)
        val slot0 = at(0, 0).toEpochMilli()

        assertEquals(Decision.NOTHING, p.decide(at(0, 3), deviceReady = true, pendingCount = 0, lastAttemptedSlotMs = null))
        assertEquals(Decision.WAIT_FOR_UNLOCK, p.decide(at(0, 3), deviceReady = false, pendingCount = 2, lastAttemptedSlotMs = null))
        // 01:10에 잠금 해제 → 00시 슬롯 아직 시도 안 함 → 카운트다운
        assertEquals(Decision.START_COUNTDOWN, p.decide(at(1, 10), deviceReady = true, pendingCount = 2, lastAttemptedSlotMs = null))
        // 같은 슬롯 안에서 다시 잠금 해제 → 아무것도 안 함
        assertEquals(Decision.NOTHING, p.decide(at(1, 50), deviceReady = true, pendingCount = 1, lastAttemptedSlotMs = slot0))
        // 다음 슬롯(02시)에는 다시 시도
        assertEquals(Decision.START_COUNTDOWN, p.decide(at(2, 4), deviceReady = true, pendingCount = 1, lastAttemptedSlotMs = slot0))
    }

    private fun mission(id: String, period: Period = Period.Daily) = Mission(id, id, "pkg", emptyList(), period = period)
    private fun result(id: String, s: Status) = MissionResult(id, s, startedAtMs = 0, durationMs = 0)
    private fun zat(h: Int, m: Int, day: Int = 13) = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, kst)

    @Test
    fun `batch skips missions done in this period and retries failures`() {
        val store = InMemoryStateStore()
        val outcomes = mutableMapOf("a" to Status.SUCCESS, "b" to Status.FAILED, "c" to Status.ALREADY_DONE)
        val calls = mutableListOf<String>()
        val batch = BatchRunner(listOf(mission("a"), mission("b"), mission("c")), store, { m ->
            calls += m.id
            result(m.id, outcomes.getValue(m.id))
        })

        val first = batch.run(zat(0, 3))
        assertEquals(listOf("a", "b", "c"), calls)
        assertEquals(false, first.allDone)

        calls.clear()
        outcomes["b"] = Status.SUCCESS
        val second = batch.run(zat(2, 3))
        assertEquals(listOf("b"), calls)
        assertEquals(listOf("a", "c"), second.skipped)
        assertTrue(second.allDone)

        // 다음 날에는 전부 다시 대상
        assertEquals(3, batch.pending(zat(0, 3, day = 14)).size)
    }

    @Test
    fun `three hour missions reset every window`() {
        val store = InMemoryStateStore()
        val batch = BatchRunner(listOf(mission("daily"), mission("button", Period.EveryHours(3))), store, { m ->
            result(m.id, Status.SUCCESS)
        })

        batch.run(zat(0, 5))
        assertEquals(emptyList(), batch.pending(zat(2, 5)).map { it.id }) // 같은 0~3시 회차
        assertEquals(listOf("button"), batch.pending(zat(4, 5)).map { it.id }) // 3~6시 회차
        assertEquals(listOf("button"), batch.pending(zat(23, 0)).map { it.id })
    }

    @Test
    fun `two hour slots cover all eight three hour windows`() {
        val windows = (0 until 24 step 2).map { Period.EveryHours(3).key(zat(it, 3)) }.toSet()
        assertEquals(8, windows.size)
    }

    @Test
    fun `batch stops after a blocked mission`() {
        val calls = mutableListOf<String>()
        val batch = BatchRunner(listOf(mission("a"), mission("b")), InMemoryStateStore(), { m ->
            calls += m.id
            result(m.id, Status.BLOCKED)
        })

        batch.run(zat(0, 3))

        assertEquals(listOf("a"), calls)
    }
}
