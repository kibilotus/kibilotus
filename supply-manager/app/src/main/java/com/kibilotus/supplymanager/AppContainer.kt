package com.kibilotus.supplymanager

import android.content.Context
import com.kibilotus.supplymanager.data.SupplyRepository
import com.kibilotus.supplymanager.data.local.SupplyDatabase
import com.kibilotus.supplymanager.data.settings.SettingsStore

/**
 * 앱 전역 의존성. 규모가 작아 DI 프레임워크 없이 수동으로 조립한다.
 */
class AppContainer(context: Context) {

    private val database: SupplyDatabase by lazy { SupplyDatabase.create(context) }

    val repository: SupplyRepository by lazy { SupplyRepository(database.supplyDao()) }

    val settingsStore: SettingsStore by lazy { SettingsStore(context) }
}
