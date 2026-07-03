package com.dreammryang.onelaptogiant.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.dreammryang.onelaptogiant.data.settings.INTERVAL_OPTIONS

@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.saved.collect { snackbarHostState.showSnackbar("已保存") }
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
            Text("顽鹿账号", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = state.onelapAccount,
                onValueChange = { v -> viewModel.update { it.copy(onelapAccount = v) } },
                label = { Text("账号") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.onelapPassword,
                onValueChange = { v -> viewModel.update { it.copy(onelapPassword = v) } },
                label = { Text("密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )

            Text("捷安特账号", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = state.giantUsername,
                onValueChange = { v -> viewModel.update { it.copy(giantUsername = v) } },
                label = { Text("账号") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.giantPassword,
                onValueChange = { v -> viewModel.update { it.copy(giantPassword = v) } },
                label = { Text("密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
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
            value = "$selected 小时",
            onValueChange = {},
            readOnly = true,
            label = { Text("同步间隔") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            INTERVAL_OPTIONS.forEach { hours ->
                DropdownMenuItem(
                    text = { Text("$hours 小时") },
                    onClick = {
                        onSelect(hours)
                        expanded = false
                    },
                )
            }
        }
    }
}
