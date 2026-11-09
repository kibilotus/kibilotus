package com.kibilotus.supplymanager.data

import android.database.sqlite.SQLiteConstraintException
import com.kibilotus.supplymanager.data.local.CategoryStats
import com.kibilotus.supplymanager.data.local.FolderEntity
import com.kibilotus.supplymanager.data.local.FolderSummary
import com.kibilotus.supplymanager.data.local.ItemEntity
import com.kibilotus.supplymanager.data.local.ItemSummary
import com.kibilotus.supplymanager.data.local.PurchaseEntity
import com.kibilotus.supplymanager.data.local.SupplyDao
import com.kibilotus.supplymanager.domain.MainCategory
import kotlinx.coroutines.flow.Flow

/**
 * 대분류(탭) → 중분류(폴더) → 소분류(품목) → 구매 내역 계층을 다루는 단일 창구.
 * 입력 검증은 모두 이 레이어에서 수행하고, 결과를 [SaveResult] 로 돌려준다.
 */
class SupplyRepository(private val dao: SupplyDao) {

    companion object {
        const val MAX_NAME_LENGTH = 40
        const val MAX_MEMO_LENGTH = 200
        const val MAX_QUANTITY = 1_000_000
        const val MAX_COST = 1_000_000_000_000L

        /** Room 의 IGNORE 전략이 충돌로 건너뛸 때 돌려주는 rowId. */
        private const val INSERT_IGNORED = -1L
    }

    // ---------------------------------------------------------------- 중분류(폴더)

    fun observeFolders(category: MainCategory): Flow<List<FolderSummary>> =
        dao.observeFolders(category.key)

    fun observeFolder(folderId: Long): Flow<FolderEntity?> = dao.observeFolder(folderId)

    suspend fun addFolder(category: MainCategory, rawName: String): SaveResult {
        val name = rawName.normalizedName() ?: return SaveResult.BLANK_NAME
        if (dao.countFolderNamed(category.key, name, excludeId = 0L) > 0) {
            return SaveResult.DUPLICATE_NAME
        }
        val id = dao.insertFolder(FolderEntity(categoryKey = category.key, name = name))
        return if (id == INSERT_IGNORED) SaveResult.DUPLICATE_NAME else SaveResult.SUCCESS
    }

    suspend fun renameFolder(category: MainCategory, folderId: Long, rawName: String): SaveResult {
        val name = rawName.normalizedName() ?: return SaveResult.BLANK_NAME
        if (dao.countFolderNamed(category.key, name, excludeId = folderId) > 0) {
            return SaveResult.DUPLICATE_NAME
        }
        return runCatchingConstraint { dao.updateFolderName(folderId, name) }
    }

    suspend fun deleteFolder(folderId: Long) = dao.deleteFolder(folderId)

    // ---------------------------------------------------------------- 소분류(품목)

    fun observeItems(folderId: Long): Flow<List<ItemSummary>> = dao.observeItems(folderId)

    fun observeItem(itemId: Long): Flow<ItemSummary?> = dao.observeItem(itemId)

    suspend fun addItem(folderId: Long, rawName: String, rawMemo: String): SaveResult {
        val name = rawName.normalizedName() ?: return SaveResult.BLANK_NAME
        if (dao.countItemNamed(folderId, name, excludeId = 0L) > 0) {
            return SaveResult.DUPLICATE_NAME
        }
        val id = dao.insertItem(
            ItemEntity(folderId = folderId, name = name, memo = rawMemo.normalizedMemo()),
        )
        return if (id == INSERT_IGNORED) SaveResult.DUPLICATE_NAME else SaveResult.SUCCESS
    }

    suspend fun updateItem(
        folderId: Long,
        itemId: Long,
        rawName: String,
        rawMemo: String,
    ): SaveResult {
        val name = rawName.normalizedName() ?: return SaveResult.BLANK_NAME
        if (dao.countItemNamed(folderId, name, excludeId = itemId) > 0) {
            return SaveResult.DUPLICATE_NAME
        }
        return runCatchingConstraint {
            dao.updateItem(itemId, name, rawMemo.normalizedMemo())
        }
    }

    suspend fun deleteItem(itemId: Long) = dao.deleteItem(itemId)

    // ---------------------------------------------------------------- 구매 내역

    fun observePurchases(itemId: Long): Flow<List<PurchaseEntity>> = dao.observePurchases(itemId)

    suspend fun addPurchase(itemId: Long, draft: PurchaseDraft): SaveResult {
        val validated = draft.validate() ?: return draft.failureReason()
        dao.insertPurchase(
            PurchaseEntity(
                itemId = itemId,
                purchasedOn = validated.purchasedOn,
                quantity = validated.quantity,
                cost = validated.cost,
                memo = validated.memo,
            ),
        )
        return SaveResult.SUCCESS
    }

    suspend fun updatePurchase(purchaseId: Long, draft: PurchaseDraft): SaveResult {
        val validated = draft.validate() ?: return draft.failureReason()
        dao.updatePurchase(
            purchaseId = purchaseId,
            purchasedOn = validated.purchasedOn,
            quantity = validated.quantity,
            cost = validated.cost,
            memo = validated.memo,
        )
        return SaveResult.SUCCESS
    }

    suspend fun deletePurchase(purchaseId: Long) = dao.deletePurchase(purchaseId)

    // ---------------------------------------------------------------- 전체 집계

    fun observeCategoryStats(): Flow<List<CategoryStats>> = dao.observeCategoryStats()

    suspend fun deleteAll() = dao.deleteAllFolders()

    // ---------------------------------------------------------------- 내부 헬퍼

    /**
     * 이름 유니크 제약 위반만 중복으로 바꿔 돌려준다.
     * (목록 갱신과 저장 사이의 좁은 경쟁 구간에서만 발생한다.)
     */
    private fun runCatchingConstraint(block: () -> Unit): SaveResult = try {
        block()
        SaveResult.SUCCESS
    } catch (_: SQLiteConstraintException) {
        SaveResult.DUPLICATE_NAME
    }

    private fun String.normalizedName(): String? =
        trim().replace(WHITESPACE, " ").take(MAX_NAME_LENGTH).ifBlank { null }

    private fun String.normalizedMemo(): String = trim().take(MAX_MEMO_LENGTH)
}

private val WHITESPACE = Regex("\\s+")
