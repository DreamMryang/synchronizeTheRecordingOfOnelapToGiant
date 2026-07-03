package com.dreammryang.onelaptogiant.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dreammryang.onelaptogiant.ui.common.formatTime
import com.dreammryang.onelaptogiant.ui.common.label

@Composable
fun HistoryScreen(viewModel: HistoryViewModel, onOpenSession: (Long) -> Unit) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()

    if (sessions.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("还没有同步记录", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(sessions, key = { it.id }) { session ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenSession(session.id) },
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
