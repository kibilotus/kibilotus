package com.kibilotus.supplymanager

import com.kibilotus.supplymanager.ui.common.Format
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test
    fun `epochDay 를 연월일로 표기한다`() {
        val epochDay = LocalDate.of(2026, 11, 9).toEpochDay()

        assertEquals("2026.11.09", Format.date(epochDay))
    }

    @Test
    fun `금액은 세 자리마다 쉼표를 넣는다`() {
        assertEquals("0원", Format.money(0))
        assertEquals("1,200원", Format.money(1_200))
        assertEquals("1,234,567원", Format.money(1_234_567))
    }

    @Test
    fun `갯수 표기에 단위를 붙인다`() {
        assertEquals("12개", Format.count(12))
        assertEquals("1,000개", Format.count(1_000))
    }

    @Test
    fun `DatePicker 밀리초와 epochDay 는 왕복 변환된다`() {
        val epochDay = LocalDate.of(2025, 1, 31).toEpochDay()
        val millis = Format.epochDayToUtcMillis(epochDay)

        assertEquals(epochDay, Format.utcMillisToEpochDay(millis))
    }
}
