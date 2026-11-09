package com.kibilotus.supplymanager.ui.item

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Inventory2
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kibilotus.supplymanager.R
import com.kibilotus.supplymanager.data.local.ItemSummary
import com.kibilotus.supplymanager.domain.MainCategory
import com.kibilotus.supplymanager.ui.common.ConfirmDialog
import com.kibilotus.supplymanager.ui.common.EmptyState
import com.kibilotus.supplymanager.ui.common.ErrorMessages
import com.kibilotus.supplymanager.ui.common.Format
import com.kibilotus.supplymanager.ui.common.NameInputDialog
import com.kibilotus.supplymanager.ui.common.SimpleViewModelFactory
import com.kibilotus.supplymanager.ui.common.rememberAppContainer

/** 중분류 폴더 안의 소분류(품목) 목록. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemListScreen(
    category: MainCategory,
    folderId: Long,
    onItemClick: (itemId: Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = rememberAppContainer()
    val viewModel: ItemListViewModel = viewModel(
        key = "items-$folderId",
        factory = SimpleViewModelFactory {
            ItemListViewModel(container.repository, folderId)
        },
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    var showAddDialog by remember { mutableStateOf(false) }
    var itemToEdit by remember { mutableStateOf<ItemSummary?>(null) }
    var itemToDelete by remember { mutableStateOf<ItemSummary?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.errors.collect { result ->
            ErrorMessages.forItem(result)?.let { resId ->
                snackbarHostState.showSnackbar(context.getString(resId))
            }
        }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = uiState.folderName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(category.labelRes),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    if (uiState.items.isNotEmpty()) {
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
                text = { Text(stringResource(R.string.item_list_fab)) },
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

                uiState.items.isEmpty() -> EmptyState(
                    icon = Icons.Filled.Inventory2,
                    title = stringResource(R.string.item_empty_title),
                    description = stringResource(R.string.item_empty_desc),
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
                    items(uiState.items, key = { it.id }) { item ->
                        ItemCard(
                            item = item,
                            onClick = { onItemClick(item.id) },
                            onEdit = { itemToEdit = item },
                            onDelete = { itemToDelete = item },
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        NameInputDialog(
            title = stringResource(R.string.item_add_title),
            nameLabel = stringResource(R.string.item_name_label),
            initialMemo = "",
            confirmLabel = stringResource(R.string.action_add),
            onConfirm = { name, memo ->
                viewModel.addItem(name, memo)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }

    itemToEdit?.let { item ->
        NameInputDialog(
            title = stringResource(R.string.item_rename_title),
            nameLabel = stringResource(R.string.item_name_label),
            initialName = item.name,
            initialMemo = item.memo,
            onConfirm = { name, memo ->
                viewModel.updateItem(item.id, name, memo)
                itemToEdit = null
            },
            onDismiss = { itemToEdit = null },
        )
    }

    itemToDelete?.let { item ->
        ConfirmDialog(
            title = stringResource(R.string.item_delete_title),
            message = stringResource(R.string.item_delete_message, item.name),
            onConfirm = {
                viewModel.deleteItem(item.id)
                itemToDelete = null
            },
            onDismiss = { itemToDelete = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemCard(
    item: ItemSummary,
    onClick: () -> Unit,
    onEdit: () -> Unit,
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.memo.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = item.memo,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = Format.count(item.totalQuantity),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = Format.money(item.totalCost),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = item.lastPurchasedOn?.let {
                        stringResource(R.string.item_meta_last, Format.date(it))
                    } ?: stringResource(R.string.item_meta_never),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
                        text = { Text(stringResource(R.string.action_edit)) },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onEdit()
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
