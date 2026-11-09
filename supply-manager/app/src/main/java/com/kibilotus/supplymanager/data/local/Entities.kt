package com.kibilotus.supplymanager.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 중분류: 대분류 안의 폴더 (집-화장실, 집-거실 …) */
@Entity(
    tableName = "folders",
    indices = [Index(value = ["categoryKey", "name"], unique = true)],
)
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** [com.kibilotus.supplymanager.domain.MainCategory.key] */
    val categoryKey: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/** 소분류: 폴더 안의 개별 비품 (휴지, 수건 …) */
@Entity(
    tableName = "items",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["folderId", "name"], unique = true)],
)
data class ItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val folderId: Long,
    val name: String,
    val memo: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

/** 소분류에 쌓이는 구매 기록: 구매 일자 / 갯수 / 비용 */
@Entity(
    tableName = "purchases",
    foreignKeys = [
        ForeignKey(
            entity = ItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["itemId"])],
)
data class PurchaseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val itemId: Long,
    /** [java.time.LocalDate.toEpochDay] 값. 타임존 영향을 받지 않도록 '날짜'만 저장한다. */
    val purchasedOn: Long,
    val quantity: Int,
    /** 해당 구매 건의 총 비용(원). */
    val cost: Long,
    val memo: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)
