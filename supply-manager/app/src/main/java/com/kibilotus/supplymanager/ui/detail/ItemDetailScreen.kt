package com.kibilotus.supplymanager.ui.detail

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
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
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
import com.kibilotus.supplymanager.data.PurchaseDraft
import com.kibilotus.supplymanager.data.local.ItemSummary
import com.kibilotus.supplymanager.data.local.PurchaseEntity
import com.kibilotus.supplymanager.ui.common.ConfirmDialog
import com.kibilotus.supplymanager.ui.common.EmptyState
import com.kibilotus.supplymanager.ui.common.ErrorMessages
import com.kibilotus.supplymanager.ui.common.Format
import com.kibilotus.supplymanager.ui.common.SimpleViewModelFactory
import com.kibilotus.supplymanager.ui.common.SummaryCell
import com.kibilotus.supplymanager.ui.common.rememberAppContainer

/**
 * 소분류 상세. 구매 당시 일자 / 갯수 / 비용을 추가·수정·삭제한다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemDetailScreen(
    itemId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = rememberAppContainer()
    val viewModel: ItemDetailViewModel = viewModel(
        key = "item-detail-$itemId",
        factory = SimpleViewModelFactory {
            ItemDetailViewModel(container.repository, itemId)
        },
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    var showAddDialog by remember { mutableStateOf(false) }
    var purchaseToEdit by remember { mutableStateOf<PurchaseEntity?>(null) }
    var purchaseToDelete by remember { mutableStateOf<PurchaseEntity?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.errors.collect { result ->
            ErrorMessages.forPurchase(result)?.let { resId ->
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
                    Text(
                        text = uiState.item?.name.orEmpty(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
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
                text = { Text(stringResource(R.string.purchase_fab)) },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val summary = uiState.item
            when {
                uiState.isLoading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )

                summary == null -> EmptyState(
                    icon = Icons.Filled.Receipt,
                    title = stringResource(R.string.error_not_found),
                    description = stringResource(R.string.item_empty_desc),
                )

                else -> LazyColumn(
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 12.dp,
                        bottom = 96.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "summary") {
                        ItemSummaryCard(summary)
                    }

                    if (uiState.purchases.isEmpty()) {
                        item(key = "empty") {
                            Spacer(Modifier.height(24.dp))
                            EmptyState(
                                icon = Icons.Filled.Receipt,
                                title = stringResource(R.string.purchase_empty_title),
                                description = stringResource(R.string.purchase_empty_desc),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    } else {
                        items(uiState.purchases, key = { it.id }) { purchase ->
                            PurchaseRow(
                                purchase = purchase,
                                onClick = { purchaseToEdit = purchase },
                                onDelete = { purchaseToDelete = purchase },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        PurchaseFormDialog(
            title = stringResource(R.string.purchase_add_title),
            initialDraft = PurchaseDraft.empty(Format.todayEpochDay()),
            onConfirm = { draft ->
                viewModel.addPurchase(draft)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }

    purchaseToEdit?.let { purchase ->
        PurchaseFormDialog(
            title = stringResource(R.string.purchase_edit_title),
            initialDraft = PurchaseDraft(
                purchasedOn = purchase.purchasedOn,
                quantityText = purchase.quantity.toString(),
                costText = purchase.cost.toString(),
                memo = purchase.memo,
            ),
            onConfirm = { draft ->
                viewModel.updatePurchase(purchase.id, draft)
                purchaseToEdit = null
            },
            onDismiss = { purchaseToEdit = null },
        )
    }

    purchaseToDelete?.let { purchase ->
        ConfirmDialog(
            title = stringResource(R.string.purchase_delete_title),
            message = stringResource(
                R.string.purchase_delete_message,
                Format.date(purchase.purchasedOn),
                Format.count(purchase.quantity),
                Format.money(purchase.cost),
            ),
            onConfirm = {
                viewModel.deletePurchase(purchase.id)
                purchaseToDelete = null
            },
            onDismiss = { purchaseToDelete = null },
        )
    }
}

@Composable
private fun ItemSummaryCard(item: ItemSummary) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (item.memo.isNotBlank()) {
                Text(
                    text = item.memo,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                SummaryCell(
                    label = stringResource(R.string.summary_total_quantity),
                    value = Format.count(item.totalQuantity),
                    modifier = Modifier.weight(1f),
                )
                SummaryCell(
                    label = stringResource(R.string.summary_total_cost),
                    value = Format.money(item.totalCost),
                    modifier = Modifier.weight(1.3f),
                )
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                SummaryCell(
                    label = stringResource(R.string.summary_purchase_count),
                    value = stringResource(
                        R.string.summary_purchase_count_value,
                        item.purchaseCount,
                    ),
                    modifier = Modifier.weight(1f),
                )
                SummaryCell(
                    label = stringResource(R.string.summary_avg_unit_price),
                    value = item.averageUnitPrice?.let { Format.money(it) } ?: "-",
                    modifier = Modifier.weight(1.3f),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PurchaseRow(
    purchase: PurchaseEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 10.dp, end = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = Format.date(purchase.purchasedOn),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = Format.count(purchase.quantity),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = Format.money(purchase.cost),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(
                            R.string.purchase_unit_price,
                            Format.money(purchase.cost / purchase.quantity.coerceAtLeast(1)),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (purchase.memo.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = purchase.memo,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.DeleteOutline,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
