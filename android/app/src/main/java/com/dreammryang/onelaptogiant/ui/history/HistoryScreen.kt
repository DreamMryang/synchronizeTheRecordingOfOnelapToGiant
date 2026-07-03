package com.dreammryang.onelaptogiant.ui.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dreammryang.onelaptogiant.data.db.SyncSessionEntity
import com.dreammryang.onelaptogiant.ui.common.formatTime
import com.dreammryang.onelaptogiant.ui.common.label

@OptIn(ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    onOpenSession: (Long) -> Unit,
    onGoSettings: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    var sessionPendingDelete by remember { mutableStateOf<SyncSessionEntity?>(null) }

    LaunchedEffect(Unit) {
        viewModel.message.collect { message ->
            // 带动作的 Snackbar 默认 Indefinite（不点不消失），显式改为短时自动消失
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = "回到顶部",
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                listState.animateScrollToItem(0)
            }
        }
    }

    sessionPendingDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { sessionPendingDelete = null },
            title = { Text("删除该会话及其本地记录？") },
            text = { Text("本地删除不影响捷安特服务端数据与去重。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.onDeleteSession(session.id)
                    sessionPendingDelete = null
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionPendingDelete = null }) { Text("取消") }
            },
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("同步历史") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // 按钮放 bottomBar：SnackbarHost 自动显示在其上方，不再遮挡
        bottomBar = {
            Button(
                onClick = viewModel::onSyncClick,
                enabled = state.configured && !state.syncing,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text(if (state.syncing) "同步中…" else "立即同步")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 横幅区无内容时整体不渲染，避免空容器的 16dp 内边距在顶部留白
            val hasBanner = !state.configured || state.processFailedCount > 0 || state.progress != null
            if (hasBanner) Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!state.configured) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("尚未配置账号", style = MaterialTheme.typography.titleMedium)
                            Text("请先在设置中填写顽鹿与捷安特的账号密码", style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = onGoSettings) { Text("去设置") }
                        }
                    }
                }

                if (state.processFailedCount > 0) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    ) {
                        Text(
                            "有 ${state.processFailedCount} 个文件已上传但服务端处理失败，请在历史中查看详情并人工处理",
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }

                state.progress?.let { p ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text("同步中：${p.step.label}")
                                if (p.total > 0) Text("${p.done}/${p.total}")
                            }
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            if (state.sessions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("还没有同步记录", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.sessions, key = { it.id }) { session ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { onOpenSession(session.id) },
                                    onLongClick = { sessionPendingDelete = session },
                                ),
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(formatTime(session.startedAt), style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "${session.triggerType.label()} · ${session.status.label()}",
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                }
                                Text(
                                    "发现 ${session.foundCount} · 下载 ${session.downloadedCount} · " +
                                        "同步 ${session.syncedCount} · 失败 ${session.failedCount}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                session.errorMsg?.let {
                                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
