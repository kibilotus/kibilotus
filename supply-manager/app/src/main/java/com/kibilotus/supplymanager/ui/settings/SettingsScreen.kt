package com.kibilotus.supplymanager.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kibilotus.supplymanager.BuildConfig
import com.kibilotus.supplymanager.R
import com.kibilotus.supplymanager.data.settings.ThemeMode
import com.kibilotus.supplymanager.ui.common.ConfirmDialog
import com.kibilotus.supplymanager.ui.common.Format
import com.kibilotus.supplymanager.ui.common.LabeledRow
import com.kibilotus.supplymanager.ui.common.SimpleViewModelFactory
import com.kibilotus.supplymanager.ui.common.rememberAppContainer
import androidx.compose.foundation.selection.selectable

/** 세 번째 탭: 테마, 전체 요약, 데이터 초기화. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val container = rememberAppContainer()
    val viewModel: SettingsViewModel = viewModel(
        key = "settings",
        factory = SimpleViewModelFactory {
            SettingsViewModel(container.repository, container.settingsStore)
        },
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.dataDeleted.collect {
            snackbarHostState.showSnackbar(context.getString(R.string.settings_deleted))
        }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsSection(title = stringResource(R.string.settings_section_theme)) {
                Column(modifier = Modifier.selectableGroup()) {
                    ThemeMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = uiState.themeMode == mode,
                                    role = Role.RadioButton,
                                    onClick = { viewModel.setThemeMode(mode) },
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = uiState.themeMode == mode,
                                onClick = null,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = stringResource(mode.labelRes),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            }

            SettingsSection(title = stringResource(R.string.settings_section_summary)) {
                uiState.statsByCategory.forEach { (category, stats) ->
                    Column(modifier = Modifier.padding(vertical = 6.dp)) {
                        Text(
                            text = stringResource(category.labelRes),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.height(4.dp))
                        LabeledRow(
                            label = stringResource(
                                R.string.settings_summary_row,
                                stats.folderCount,
                                stats.itemCount,
                            ),
                            value = Format.money(stats.totalCost),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_summary_total),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(4.dp))
                LabeledRow(
                    label = stringResource(
                        R.string.settings_summary_row,
                        uiState.totalFolderCount,
                        uiState.totalItemCount,
                    ),
                    value = Format.money(uiState.totalCost),
                )
                LabeledRow(
                    label = stringResource(R.string.summary_total_quantity),
                    value = Format.count(uiState.totalQuantity),
                )
            }

            SettingsSection(title = stringResource(R.string.settings_section_data)) {
                Text(
                    text = stringResource(R.string.settings_delete_all_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { showDeleteDialog = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Icon(Icons.Filled.DeleteForever, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_delete_all))
                }
            }

            SettingsSection(title = stringResource(R.string.settings_section_about)) {
                LabeledRow(
                    label = stringResource(R.string.app_name),
                    value = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDeleteDialog) {
        ConfirmDialog(
            title = stringResource(R.string.settings_delete_all_title),
            message = stringResource(R.string.settings_delete_all_message),
            onConfirm = {
                viewModel.deleteAllData()
                showDeleteDialog = false
            },
            onDismiss = { showDeleteDialog = false },
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                content()
            }
        }
    }
}
