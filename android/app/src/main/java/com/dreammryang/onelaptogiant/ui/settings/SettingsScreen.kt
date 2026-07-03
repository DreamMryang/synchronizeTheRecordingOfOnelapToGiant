package com.dreammryang.onelaptogiant.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dreammryang.onelaptogiant.data.settings.INTERVAL_OFF
import com.dreammryang.onelaptogiant.data.settings.INTERVAL_OPTIONS

private enum class CredentialPlatform { ONELAP, GIANT }

@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var editingPlatform by remember { mutableStateOf<CredentialPlatform?>(null) }

    LaunchedEffect(Unit) {
        viewModel.saved.collect { snackbarHostState.showSnackbar("已保存") }
    }

    editingPlatform?.let { platform ->
        val (title, account, password, onConfirm) = when (platform) {
            CredentialPlatform.ONELAP -> CredentialDialogSpec(
                title = "顽鹿账号",
                account = state.onelapAccount,
                password = state.onelapPassword,
                onConfirm = viewModel::saveOnelapCredentials,
            )
            CredentialPlatform.GIANT -> CredentialDialogSpec(
                title = "捷安特账号",
                account = state.giantUsername,
                password = state.giantPassword,
                onConfirm = viewModel::saveGiantCredentials,
            )
        }
        CredentialDialog(
            title = title,
            initialAccount = account,
            initialPassword = password,
            onConfirm = { a, p ->
                onConfirm(a, p)
                editingPlatform = null
            },
            onDismiss = { editingPlatform = null },
        )
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("账号", style = MaterialTheme.typography.titleMedium)
            CredentialEntry(
                label = "顽鹿账号",
                accountName = state.onelapAccount,
                onClick = { editingPlatform = CredentialPlatform.ONELAP },
            )
            CredentialEntry(
                label = "捷安特账号",
                accountName = state.giantUsername,
                onClick = { editingPlatform = CredentialPlatform.GIANT },
            )

            Text("同步选项", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = state.recentDays,
                onValueChange = { v -> viewModel.update { it.copy(recentDays = v) } },
                label = { Text("同步最近天数") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            IntervalDropdown(
                selected = state.intervalHours,
                onSelect = { v -> viewModel.update { it.copy(intervalHours = v) } },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("仅 Wi-Fi 下同步")
                Switch(
                    checked = state.wifiOnly,
                    onCheckedChange = { v -> viewModel.update { it.copy(wifiOnly = v) } },
                )
            }

            Button(
                onClick = viewModel::save,
                enabled = state.loaded,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("保存")
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun IntervalDropdown(selected: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = intervalLabel(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text("同步间隔") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            INTERVAL_OPTIONS.forEach { hours ->
                DropdownMenuItem(
                    text = { Text(intervalLabel(hours)) },
                    onClick = {
                        onSelect(hours)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun intervalLabel(h: Int): String = if (h == INTERVAL_OFF) "关闭" else "$h 小时"

private data class CredentialDialogSpec(
    val title: String,
    val account: String,
    val password: String,
    val onConfirm: (account: String, password: String) -> Unit,
)

@Composable
private fun CredentialEntry(label: String, accountName: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = accountName.ifBlank { "未配置" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CredentialDialog(
    title: String,
    initialAccount: String,
    initialPassword: String,
    onConfirm: (account: String, password: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var account by remember { mutableStateOf(initialAccount) }
    var password by remember { mutableStateOf(initialPassword) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = account,
                    onValueChange = { account = it },
                    label = { Text("账号") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(account, password) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
