package com.dreammryang.onelaptogiant.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.dreammryang.onelaptogiant.data.db.RecordStatus
import com.dreammryang.onelaptogiant.data.db.SessionStatus
import com.dreammryang.onelaptogiant.data.db.TriggerType

fun SessionStatus.label(): String = when (this) {
    SessionStatus.RUNNING -> "进行中"
    SessionStatus.SUCCESS -> "成功"
    SessionStatus.PARTIAL -> "部分成功"
    SessionStatus.FAILED -> "失败"
    SessionStatus.NO_NEW -> "无新记录"
}

fun RecordStatus.label(): String = when (this) {
    RecordStatus.DOWNLOADED -> "已下载"
    RecordStatus.DOWNLOAD_FAILED -> "下载失败"
    RecordStatus.SYNCED -> "已同步"
    RecordStatus.UPLOAD_FAILED -> "上传失败"
    RecordStatus.PROCESS_FAILED -> "处理失败"
}

fun TriggerType.label(): String = when (this) {
    TriggerType.AUTO -> "自动"
    TriggerType.MANUAL -> "手动"
}

@Composable
fun RecordStatus.color(): Color = when (this) {
    RecordStatus.SYNCED -> MaterialTheme.colorScheme.primary
    RecordStatus.DOWNLOADED -> MaterialTheme.colorScheme.onSurfaceVariant
    RecordStatus.DOWNLOAD_FAILED,
    RecordStatus.UPLOAD_FAILED,
    RecordStatus.PROCESS_FAILED,
    -> MaterialTheme.colorScheme.error
}
