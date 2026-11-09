package com.kibilotus.supplymanager

import com.kibilotus.supplymanager.data.PurchaseDraft
import com.kibilotus.supplymanager.data.SaveResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PurchaseDraftTest {

    private val someDay = 20_400L

    @Test
    fun `정상 입력은 검증을 통과한다`() {
        val validated = PurchaseDraft(someDay, "3", "12000", " 대용량 ").validate()

        assertNotNull(validated)
        assertEquals(someDay, validated!!.purchasedOn)
        assertEquals(3, validated.quantity)
        assertEquals(12_000L, validated.cost)
        assertEquals("대용량", validated.memo)
    }

    @Test
    fun `비용에 쉼표나 단위가 섞여도 숫자만 읽는다`() {
        val validated = PurchaseDraft(someDay, "2개", "1,200원").validate()

        assertNotNull(validated)
        assertEquals(2, validated!!.quantity)
        assertEquals(1_200L, validated.cost)
    }

    @Test
    fun `비용 0원은 허용한다`() {
        assertNotNull(PurchaseDraft(someDay, "1", "0").validate())
    }

    @Test
    fun `갯수가 비었거나 0이면 갯수 오류다`() {
        listOf("", "0", "abc").forEach { quantity ->
            val draft = PurchaseDraft(someDay, quantity, "1000")
            assertNull(draft.validate())
            assertEquals(SaveResult.INVALID_QUANTITY, draft.failureReason())
        }
    }

    @Test
    fun `갯수는 정상이고 비용이 비면 비용 오류다`() {
        val draft = PurchaseDraft(someDay, "1", "")

        assertNull(draft.validate())
        assertEquals(SaveResult.INVALID_COST, draft.failureReason())
    }

    @Test
    fun `기본 입력값은 갯수 1개로 시작한다`() {
        val draft = PurchaseDraft.empty(someDay)

        assertEquals("1", draft.quantityText)
        assertEquals("", draft.costText)
        assertEquals(someDay, draft.purchasedOn)
    }
}
