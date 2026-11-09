package com.kibilotus.supplymanager.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [FolderEntity::class, ItemEntity::class, PurchaseEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class SupplyDatabase : RoomDatabase() {

    abstract fun supplyDao(): SupplyDao

    companion object {
        private const val NAME = "supply_manager.db"

        fun create(context: Context): SupplyDatabase =
            Room.databaseBuilder(context, SupplyDatabase::class.java, NAME).build()
    }
}
