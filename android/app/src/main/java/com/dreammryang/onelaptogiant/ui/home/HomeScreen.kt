package com.dreammryang.onelaptogiant.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dreammryang.onelaptogiant.data.db.SyncSessionEntity
import com.dreammryang.onelaptogiant.ui.common.formatTime
import com.dreammryang.onelaptogiant.ui.common.label

@Composable
fun HomeScreen(viewModel: HomeViewModel, onGoSettings: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
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

        state.lastSession?.let { LastSessionCard(it) }

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

        Button(
            onClick = viewModel::onSyncClick,
            enabled = state.configured && !state.syncing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.syncing) "同步中…" else "立即同步")
        }
    }
}

@Composable
private fun LastSessionCard(session: SyncSessionEntity) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("上次同步", style = MaterialTheme.typography.titleMedium)
                Text(session.status.label(), style = MaterialTheme.typography.titleMedium)
            }
            Text("时间：${formatTime(session.startedAt)}（${session.triggerType.label()}触发）")
            Text("发现 ${session.foundCount} · 下载 ${session.downloadedCount} · 同步 ${session.syncedCount} · 失败 ${session.failedCount}")
            session.errorMsg?.let {
                Text("错误：$it", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
