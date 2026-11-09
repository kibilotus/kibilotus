package com.kibilotus.supplymanager.ui.common

import java.text.DecimalFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** 화면 표기용 포맷 모음. 모두 순수 함수라 테스트로 고정할 수 있다. */
object Format {

    private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

    private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd")
    private val numberFormat = DecimalFormat("#,##0")

    /** epochDay → "2026.11.09" */
    fun date(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).format(dateFormatter)

    /** 1200 → "1,200원" */
    fun money(won: Long): String = "${numberFormat.format(won)}원"

    /** 12 → "12개" */
    fun count(value: Int): String = "${numberFormat.format(value)}개"

    fun number(value: Long): String = numberFormat.format(value)

    /** 오늘 날짜의 epochDay. 기기 시간대 기준. */
    fun todayEpochDay(): Long = LocalDate.now().toEpochDay()

    /**
     * Material3 DatePicker 는 UTC 자정 기준 밀리초를 쓰므로 epochDay 와 상호 변환한다.
     */
    fun epochDayToUtcMillis(epochDay: Long): Long = epochDay * MILLIS_PER_DAY

    fun utcMillisToEpochDay(utcMillis: Long): Long =
        Instant.ofEpochMilli(utcMillis).atOffset(ZoneOffset.UTC).toLocalDate().toEpochDay()
}
