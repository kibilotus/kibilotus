package com.kibilotus.supplymanager.ui.item

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kibilotus.supplymanager.data.SaveResult
import com.kibilotus.supplymanager.data.SupplyRepository
import com.kibilotus.supplymanager.data.local.ItemSummary
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ItemListUiState(
    val folderName: String = "",
    val items: List<ItemSummary> = emptyList(),
    val isLoading: Boolean = true,
) {
    val totalCost: Long get() = items.sumOf { it.totalCost }
}

class ItemListViewModel(
    private val repository: SupplyRepository,
    private val folderId: Long,
) : ViewModel() {

    val uiState: StateFlow<ItemListUiState> = combine(
        repository.observeFolder(folderId),
        repository.observeItems(folderId),
    ) { folder, items ->
        ItemListUiState(
            folderName = folder?.name.orEmpty(),
            items = items,
            isLoading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = ItemListUiState(),
    )

    private val errorChannel = Channel<SaveResult>(Channel.BUFFERED)
    val errors: Flow<SaveResult> = errorChannel.receiveAsFlow()

    fun addItem(name: String, memo: String) = viewModelScope.launch {
        repository.addItem(folderId, name, memo).reportIfFailed()
    }

    fun updateItem(itemId: Long, name: String, memo: String) = viewModelScope.launch {
        repository.updateItem(folderId, itemId, name, memo).reportIfFailed()
    }

    fun deleteItem(itemId: Long) = viewModelScope.launch {
        repository.deleteItem(itemId)
    }

    private suspend fun SaveResult.reportIfFailed() {
        if (this != SaveResult.SUCCESS) errorChannel.send(this)
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
