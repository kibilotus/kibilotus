package com.kibilotus.supplymanager.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kibilotus.supplymanager.data.PurchaseDraft
import com.kibilotus.supplymanager.data.SaveResult
import com.kibilotus.supplymanager.data.SupplyRepository
import com.kibilotus.supplymanager.data.local.ItemSummary
import com.kibilotus.supplymanager.data.local.PurchaseEntity
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ItemDetailUiState(
    val item: ItemSummary? = null,
    val purchases: List<PurchaseEntity> = emptyList(),
    val isLoading: Boolean = true,
)

class ItemDetailViewModel(
    private val repository: SupplyRepository,
    private val itemId: Long,
) : ViewModel() {

    val uiState: StateFlow<ItemDetailUiState> = combine(
        repository.observeItem(itemId),
        repository.observePurchases(itemId),
    ) { item, purchases ->
        ItemDetailUiState(item = item, purchases = purchases, isLoading = false)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = ItemDetailUiState(),
    )

    private val errorChannel = Channel<SaveResult>(Channel.BUFFERED)
    val errors: Flow<SaveResult> = errorChannel.receiveAsFlow()

    fun addPurchase(draft: PurchaseDraft) = viewModelScope.launch {
        repository.addPurchase(itemId, draft).reportIfFailed()
    }

    fun updatePurchase(purchaseId: Long, draft: PurchaseDraft) = viewModelScope.launch {
        repository.updatePurchase(purchaseId, draft).reportIfFailed()
    }

    fun deletePurchase(purchaseId: Long) = viewModelScope.launch {
        repository.deletePurchase(purchaseId)
    }

    private suspend fun SaveResult.reportIfFailed() {
        if (this != SaveResult.SUCCESS) errorChannel.send(this)
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
