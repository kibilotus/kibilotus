package com.kibilotus.supplymanager.ui.folder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kibilotus.supplymanager.data.SaveResult
import com.kibilotus.supplymanager.data.SupplyRepository
import com.kibilotus.supplymanager.data.local.FolderSummary
import com.kibilotus.supplymanager.domain.MainCategory
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FolderListUiState(
    val folders: List<FolderSummary> = emptyList(),
    val isLoading: Boolean = true,
) {
    val totalCost: Long get() = folders.sumOf { it.totalCost }
}

class FolderListViewModel(
    private val repository: SupplyRepository,
    private val category: MainCategory,
) : ViewModel() {

    val uiState: StateFlow<FolderListUiState> = repository.observeFolders(category)
        .map { FolderListUiState(folders = it, isLoading = false) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = FolderListUiState(),
        )

    private val errorChannel = Channel<SaveResult>(Channel.BUFFERED)
    val errors: Flow<SaveResult> = errorChannel.receiveAsFlow()

    fun addFolder(name: String) = viewModelScope.launch {
        repository.addFolder(category, name).reportIfFailed()
    }

    fun renameFolder(folderId: Long, name: String) = viewModelScope.launch {
        repository.renameFolder(category, folderId, name).reportIfFailed()
    }

    fun deleteFolder(folderId: Long) = viewModelScope.launch {
        repository.deleteFolder(folderId)
    }

    private suspend fun SaveResult.reportIfFailed() {
        if (this != SaveResult.SUCCESS) errorChannel.send(this)
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
