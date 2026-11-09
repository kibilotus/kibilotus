package com.kibilotus.supplymanager.data

/**
 * 구매 내역 입력값(문자열) 집합. 화면에서 그대로 들고 있다가 저장 시점에 검증한다.
 * 안드로이드 의존성이 없어 순수 JVM 테스트로 검증할 수 있다.
 */
data class PurchaseDraft(
    /** [java.time.LocalDate.toEpochDay] 값. */
    val purchasedOn: Long,
    val quantityText: String,
    val costText: String,
    val memo: String = "",
) {
    /** 검증을 통과한 값. */
    data class Validated(
        val purchasedOn: Long,
        val quantity: Int,
        val cost: Long,
        val memo: String,
    )

    private val quantity: Int? get() = quantityText.toDigits()?.toIntOrNull()

    private val cost: Long? get() = costText.toDigits()?.toLongOrNull()

    fun validate(): Validated? {
        val quantity = quantity?.takeIf { it in 1..SupplyRepository.MAX_QUANTITY } ?: return null
        val cost = cost?.takeIf { it in 0..SupplyRepository.MAX_COST } ?: return null
        return Validated(
            purchasedOn = purchasedOn,
            quantity = quantity,
            cost = cost,
            memo = memo.trim().take(SupplyRepository.MAX_MEMO_LENGTH),
        )
    }

    /** [validate] 가 null 을 돌려준 이유. */
    fun failureReason(): SaveResult {
        val quantity = quantity
        val quantityValid = quantity != null &&
            quantity >= 1 &&
            quantity <= SupplyRepository.MAX_QUANTITY
        return if (quantityValid) SaveResult.INVALID_COST else SaveResult.INVALID_QUANTITY
    }

    private fun String.toDigits(): String? {
        // 사용자가 "1,200원" 처럼 입력하는 경우를 흔하게 겪으므로 숫자만 추려 쓴다.
        val digits = filter { it.isDigit() }
        return digits.ifEmpty { null }
    }

    companion object {
        fun empty(purchasedOn: Long) = PurchaseDraft(
            purchasedOn = purchasedOn,
            quantityText = "1",
            costText = "",
        )
    }
}
