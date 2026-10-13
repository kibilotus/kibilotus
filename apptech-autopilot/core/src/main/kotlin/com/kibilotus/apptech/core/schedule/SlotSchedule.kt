package com.kibilotus.apptech.core.schedule

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

/**
 * 하루를 [startHour]시부터 [intervalHours]시간 단위 슬롯으로 나눈다. 기본값은 00:00, 02:00, … 22:00.
 * 슬롯은 시작 시각의 epoch millis로 식별한다.
 */
class SlotSchedule(
    val zone: ZoneId = ZoneId.of("Asia/Seoul"),
    val intervalHours: Int = 2,
    val startHour: Int = 0,
) {
    init {
        require(intervalHours in 1..24 && 24 % intervalHours == 0) { "intervalHours must divide 24" }
        require(startHour in 0 until intervalHours) { "startHour must be < intervalHours" }
    }

    /** [now]가 속한 슬롯의 시작 시각 */
    fun slotStart(now: Instant): ZonedDateTime {
        val t = now.atZone(zone)
        val offset = Math.floorMod(t.hour - startHour, intervalHours)
        val slotHour = Math.floorMod(t.hour - offset, 24)
        val date = if (slotHour > t.hour) t.toLocalDate().minusDays(1) else t.toLocalDate()
        return date.atTime(slotHour, 0).atZone(zone)
    }

    /** [now] 이후 처음 시작되는 슬롯 */
    fun nextSlotStart(now: Instant): ZonedDateTime =
        // DST가 없는 지역(KST) 기준. 시간 더하기는 ZonedDateTime이 처리한다.
        slotStart(now).plusHours(intervalHours.toLong())

    /** 미션 완료 여부를 판단하는 '오늘' (현지 날짜) */
    fun dayOf(now: Instant): LocalDate = now.atZone(zone).toLocalDate()
}

/** 봇 탐지 회피용 실행 지연. 기본 1~5분 */
class Jitter(
    private val min: Duration = Duration.ofMinutes(1),
    private val max: Duration = Duration.ofMinutes(5),
    private val random: Random = Random.Default,
) {
    fun next(): Duration = Duration.ofMillis(random.nextLong(min.toMillis(), max.toMillis() + 1))
}
