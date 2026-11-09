package com.kibilotus.supplymanager.data.local

/** 폴더 목록 행: 폴더 정보 + 하위 집계. */
data class FolderSummary(
    val id: Long,
    val categoryKey: String,
    val name: String,
    val createdAt: Long,
    val itemCount: Int,
    val totalQuantity: Int,
    val totalCost: Long,
)

/** 품목 목록/상세 행: 품목 정보 + 구매 내역 집계. */
data class ItemSummary(
    val id: Long,
    val folderId: Long,
    val name: String,
    val memo: String,
    val createdAt: Long,
    val totalQuantity: Int,
    val totalCost: Long,
    val purchaseCount: Int,
    /** 구매 내역이 없으면 null. */
    val lastPurchasedOn: Long?,
) {
    /** 1개당 평균 비용. 수량이 0이면 null. */
    val averageUnitPrice: Long?
        get() = if (totalQuantity > 0) totalCost / totalQuantity else null
}

/** 설정 탭의 대분류별 집계. */
data class CategoryStats(
    val categoryKey: String,
    val folderCount: Int,
    val itemCount: Int,
    val totalQuantity: Int,
    val totalCost: Long,
)
