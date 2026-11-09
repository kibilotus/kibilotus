package com.kibilotus.supplymanager.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.kibilotus.supplymanager.R
import com.kibilotus.supplymanager.data.SupplyRepository

/**
 * 폴더/품목 이름(+메모)을 입력받는 공용 다이얼로그. 추가와 이름 변경에 모두 쓴다.
 * [initialMemo] 가 null 이면 메모 입력란을 숨긴다.
 */
@Composable
fun NameInputDialog(
    title: String,
    nameLabel: String,
    initialName: String = "",
    initialMemo: String? = null,
    memoLabel: String = stringResource(R.string.item_memo_label),
    confirmLabel: String = stringResource(R.string.action_save),
    onConfirm: (name: String, memo: String) -> Unit,
    onDismiss: () -> Unit,
) {
    // 대상이 바뀌면(다른 폴더/품목 수정) 입력값도 새 대상 기준으로 다시 시작한다.
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    var memo by rememberSaveable(initialMemo) { mutableStateOf(initialMemo.orEmpty()) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { input ->
                        if (input.length <= SupplyRepository.MAX_NAME_LENGTH) name = input
                    },
                    label = { Text(nameLabel) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
                if (initialMemo != null) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = memo,
                        onValueChange = { input ->
                            if (input.length <= SupplyRepository.MAX_MEMO_LENGTH) memo = input
                        },
                        label = { Text(memoLabel) },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    keyboard?.hide()
                    onConfirm(name, memo)
                },
                enabled = name.isNotBlank(),
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
