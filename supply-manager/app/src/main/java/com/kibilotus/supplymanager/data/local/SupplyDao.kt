package com.kibilotus.supplymanager.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SupplyDao {

    // ---------------------------------------------------------------- 중분류(폴더)

    @Query(
        """
        SELECT f.id            AS id,
               f.categoryKey   AS categoryKey,
               f.name          AS name,
               f.createdAt     AS createdAt,
               (SELECT COUNT(*) FROM items i WHERE i.folderId = f.id) AS itemCount,
               IFNULL((SELECT SUM(p.quantity) FROM purchases p
                        JOIN items i2 ON p.itemId = i2.id
                       WHERE i2.folderId = f.id), 0) AS totalQuantity,
               IFNULL((SELECT SUM(p.cost) FROM purchases p
                        JOIN items i3 ON p.itemId = i3.id
                       WHERE i3.folderId = f.id), 0) AS totalCost
          FROM folders f
         WHERE f.categoryKey = :categoryKey
         ORDER BY f.name COLLATE NOCASE ASC
        """,
    )
    fun observeFolders(categoryKey: String): Flow<List<FolderSummary>>

    @Query("SELECT * FROM folders WHERE id = :folderId LIMIT 1")
    fun observeFolder(folderId: Long): Flow<FolderEntity?>

    @Query(
        "SELECT COUNT(*) FROM folders " +
            "WHERE categoryKey = :categoryKey AND name = :name COLLATE NOCASE AND id != :excludeId",
    )
    suspend fun countFolderNamed(categoryKey: String, name: String, excludeId: Long): Int

    /** 유니크 제약 충돌 시 -1 을 돌려준다. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFolder(folder: FolderEntity): Long

    @Query("UPDATE folders SET name = :name WHERE id = :folderId")
    suspend fun updateFolderName(folderId: Long, name: String)

    @Query("DELETE FROM folders WHERE id = :folderId")
    suspend fun deleteFolder(folderId: Long)

    // ---------------------------------------------------------------- 소분류(품목)

    @Query(
        """
        SELECT i.id        AS id,
               i.folderId  AS folderId,
               i.name      AS name,
               i.memo      AS memo,
               i.createdAt AS createdAt,
               IFNULL(SUM(p.quantity), 0) AS totalQuantity,
               IFNULL(SUM(p.cost), 0)     AS totalCost,
               COUNT(p.id)                AS purchaseCount,
               MAX(p.purchasedOn)         AS lastPurchasedOn
          FROM items i
          LEFT JOIN purchases p ON p.itemId = i.id
         WHERE i.folderId = :folderId
         GROUP BY i.id
         ORDER BY i.name COLLATE NOCASE ASC
        """,
    )
    fun observeItems(folderId: Long): Flow<List<ItemSummary>>

    @Query(
        """
        SELECT i.id        AS id,
               i.folderId  AS folderId,
               i.name      AS name,
               i.memo      AS memo,
               i.createdAt AS createdAt,
               IFNULL(SUM(p.quantity), 0) AS totalQuantity,
               IFNULL(SUM(p.cost), 0)     AS totalCost,
               COUNT(p.id)                AS purchaseCount,
               MAX(p.purchasedOn)         AS lastPurchasedOn
          FROM items i
          LEFT JOIN purchases p ON p.itemId = i.id
         WHERE i.id = :itemId
         GROUP BY i.id
        """,
    )
    fun observeItem(itemId: Long): Flow<ItemSummary?>

    @Query(
        "SELECT COUNT(*) FROM items " +
            "WHERE folderId = :folderId AND name = :name COLLATE NOCASE AND id != :excludeId",
    )
    suspend fun countItemNamed(folderId: Long, name: String, excludeId: Long): Int

    /** 유니크 제약 충돌 시 -1 을 돌려준다. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItem(item: ItemEntity): Long

    @Query("UPDATE items SET name = :name, memo = :memo WHERE id = :itemId")
    suspend fun updateItem(itemId: Long, name: String, memo: String)

    @Query("DELETE FROM items WHERE id = :itemId")
    suspend fun deleteItem(itemId: Long)

    // ---------------------------------------------------------------- 구매 내역

    @Query(
        "SELECT * FROM purchases WHERE itemId = :itemId " +
            "ORDER BY purchasedOn DESC, id DESC",
    )
    fun observePurchases(itemId: Long): Flow<List<PurchaseEntity>>

    @Insert
    suspend fun insertPurchase(purchase: PurchaseEntity): Long

    @Query(
        "UPDATE purchases SET purchasedOn = :purchasedOn, quantity = :quantity, " +
            "cost = :cost, memo = :memo WHERE id = :purchaseId",
    )
    suspend fun updatePurchase(
        purchaseId: Long,
        purchasedOn: Long,
        quantity: Int,
        cost: Long,
        memo: String,
    )

    @Query("DELETE FROM purchases WHERE id = :purchaseId")
    suspend fun deletePurchase(purchaseId: Long)

    // ---------------------------------------------------------------- 전체 집계

    @Query(
        """
        SELECT f.categoryKey           AS categoryKey,
               COUNT(DISTINCT f.id)    AS folderCount,
               COUNT(DISTINCT i.id)    AS itemCount,
               IFNULL(SUM(p.quantity), 0) AS totalQuantity,
               IFNULL(SUM(p.cost), 0)     AS totalCost
          FROM folders f
          LEFT JOIN items i     ON i.folderId = f.id
          LEFT JOIN purchases p ON p.itemId = i.id
         GROUP BY f.categoryKey
        """,
    )
    fun observeCategoryStats(): Flow<List<CategoryStats>>

    /** folders 를 비우면 items / purchases 는 ON DELETE CASCADE 로 함께 삭제된다. */
    @Query("DELETE FROM folders")
    suspend fun deleteAllFolders()
}
