package com.kibilotus.supplymanager.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kibilotus.supplymanager.data.SupplyRepository
import com.kibilotus.supplymanager.data.local.CategoryStats
import com.kibilotus.supplymanager.data.settings.SettingsStore
import com.kibilotus.supplymanager.data.settings.ThemeMode
import com.kibilotus.supplymanager.domain.MainCategory
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.DEFAULT,
    /** 대분류 순서대로 채운 집계. 데이터가 없는 대분류는 0 으로 채운다. */
    val statsByCategory: List<Pair<MainCategory, CategoryStats>> = emptyList(),
) {
    val totalFolderCount: Int get() = statsByCategory.sumOf { it.second.folderCount }
    val totalItemCount: Int get() = statsByCategory.sumOf { it.second.itemCount }
    val totalQuantity: Int get() = statsByCategory.sumOf { it.second.totalQuantity }
    val totalCost: Long get() = statsByCategory.sumOf { it.second.totalCost }
}

class SettingsViewModel(
    private val repository: SupplyRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsStore.themeMode,
        repository.observeCategoryStats(),
    ) { themeMode, stats ->
        SettingsUiState(
            themeMode = themeMode,
            statsByCategory = MainCategory.entries.map { category ->
                category to (
                    stats.firstOrNull { it.categoryKey == category.key }
                        ?: CategoryStats(category.key, 0, 0, 0, 0L)
                    )
            },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = SettingsUiState(),
    )

    private val deletedChannel = Channel<Unit>(Channel.BUFFERED)
    val dataDeleted: Flow<Unit> = deletedChannel.receiveAsFlow()

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch {
        settingsStore.setThemeMode(mode)
    }

    fun deleteAllData() = viewModelScope.launch {
        repository.deleteAll()
        deletedChannel.send(Unit)
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
