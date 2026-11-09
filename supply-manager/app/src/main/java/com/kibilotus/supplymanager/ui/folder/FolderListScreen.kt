package com.kibilotus.supplymanager.ui.folder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kibilotus.supplymanager.R
import com.kibilotus.supplymanager.data.local.FolderSummary
import com.kibilotus.supplymanager.domain.MainCategory
import com.kibilotus.supplymanager.ui.common.ConfirmDialog
import com.kibilotus.supplymanager.ui.common.EmptyState
import com.kibilotus.supplymanager.ui.common.ErrorMessages
import com.kibilotus.supplymanager.ui.common.Format
import com.kibilotus.supplymanager.ui.common.NameInputDialog
import com.kibilotus.supplymanager.ui.common.SimpleViewModelFactory
import com.kibilotus.supplymanager.ui.common.rememberAppContainer

/** 대분류 탭의 첫 화면: 중분류 폴더 목록. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderListScreen(
    category: MainCategory,
    onFolderClick: (folderId: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = rememberAppContainer()
    val viewModel: FolderListViewModel = viewModel(
        key = "folders-${category.key}",
        factory = SimpleViewModelFactory {
            FolderListViewModel(container.repository, category)
        },
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    var showAddDialog by remember { mutableStateOf(false) }
    var folderToRename by remember { mutableStateOf<FolderSummary?>(null) }
    var folderToDelete by remember { mutableStateOf<FolderSummary?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.errors.collect { result ->
            ErrorMessages.forFolder(result)?.let { resId ->
                snackbarHostState.showSnackbar(context.getString(resId))
            }
        }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(category.labelRes)) },
                actions = {
                    if (uiState.folders.isNotEmpty()) {
                        Text(
                            text = Format.money(uiState.totalCost),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 16.dp),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.folder_list_fab)) },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                uiState.isLoading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )

                uiState.folders.isEmpty() -> EmptyState(
                    icon = Icons.Filled.CreateNewFolder,
                    title = stringResource(R.string.folder_empty_title),
                    description = stringResource(R.string.folder_empty_desc),
                )

                else -> LazyColumn(
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = 96.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(uiState.folders, key = { it.id }) { folder ->
                        FolderCard(
                            folder = folder,
                            onClick = { onFolderClick(folder.id) },
                            onRename = { folderToRename = folder },
                            onDelete = { folderToDelete = folder },
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        NameInputDialog(
            title = stringResource(R.string.folder_add_title),
            nameLabel = stringResource(R.string.folder_name_label),
            confirmLabel = stringResource(R.string.action_add),
            onConfirm = { name, _ ->
                viewModel.addFolder(name)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }

    folderToRename?.let { folder ->
        NameInputDialog(
            title = stringResource(R.string.folder_rename_title),
            nameLabel = stringResource(R.string.folder_name_label),
            initialName = folder.name,
            onConfirm = { name, _ ->
                viewModel.renameFolder(folder.id, name)
                folderToRename = null
            },
            onDismiss = { folderToRename = null },
        )
    }

    folderToDelete?.let { folder ->
        ConfirmDialog(
            title = stringResource(R.string.folder_delete_title),
            message = stringResource(R.string.folder_delete_message, folder.name),
            onConfirm = {
                viewModel.deleteFolder(folder.id)
                folderToDelete = null
            },
            onDismiss = { folderToDelete = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderCard(
    folder: FolderSummary,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = folder.name, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.folder_meta,
                        folder.itemCount,
                        Format.count(folder.totalQuantity),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = Format.money(folder.totalCost),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.action_more),
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_rename)) },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete)) },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}
