package com.kibilotus.supplymanager.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.kibilotus.supplymanager.R
import com.kibilotus.supplymanager.data.PurchaseDraft
import com.kibilotus.supplymanager.ui.common.Format

/**
 * 구매 일자 / 갯수 / 비용을 입력하는 다이얼로그. 추가와 수정에 함께 쓴다.
 */
@Composable
fun PurchaseFormDialog(
    title: String,
    initialDraft: PurchaseDraft,
    onConfirm: (PurchaseDraft) -> Unit,
    onDismiss: () -> Unit,
) {
    var purchasedOn by rememberSaveable(initialDraft) { mutableStateOf(initialDraft.purchasedOn) }
    var quantityText by rememberSaveable(initialDraft) { mutableStateOf(initialDraft.quantityText) }
    var costText by rememberSaveable(initialDraft) { mutableStateOf(initialDraft.costText) }
    var memo by rememberSaveable(initialDraft) { mutableStateOf(initialDraft.memo) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.purchase_date_label),
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.CalendarMonth, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(Format.date(purchasedOn))
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = quantityText,
                        onValueChange = { input ->
                            quantityText = input.filter { it.isDigit() }.take(7)
                        },
                        label = { Text(stringResource(R.string.purchase_quantity_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = costText,
                        onValueChange = { input ->
                            costText = input.filter { it.isDigit() }.take(13)
                        },
                        label = { Text(stringResource(R.string.purchase_cost_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1.4f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = memo,
                    onValueChange = { input -> memo = input.take(200) },
                    label = { Text(stringResource(R.string.purchase_memo_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        PurchaseDraft(
                            purchasedOn = purchasedOn,
                            quantityText = quantityText,
                            costText = costText,
                            memo = memo,
                        ),
                    )
                },
                enabled = quantityText.isNotBlank() && costText.isNotBlank(),
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )

    if (showDatePicker) {
        PurchaseDatePickerDialog(
            initialEpochDay = purchasedOn,
            onSelect = { selected ->
                purchasedOn = selected
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PurchaseDatePickerDialog(
    initialEpochDay: Long,
    onSelect: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = Format.epochDayToUtcMillis(initialEpochDay),
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val millis = state.selectedDateMillis
                    if (millis != null) {
                        onSelect(Format.utcMillisToEpochDay(millis))
                    } else {
                        onDismiss()
                    }
                },
            ) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    ) {
        DatePicker(state = state)
    }
}
