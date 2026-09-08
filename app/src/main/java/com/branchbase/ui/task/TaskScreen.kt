package com.branchbase.ui.task

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.ui.theme.Primer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 任务中心：本地持久化任务记录（对齐 design/task-prototype.html）。
 *
 * - 筛选（全部/运行中/已完成/失败）
 * - 单条删除、清理已完成、全部清除（二次确认，运行中任务保留于"清理已完成"）
 * - 运行中任务每 2 秒刷新（短时更新），不确定进度显示滑动条
 * - 点击任务进入详情（类型/状态/起止/耗时/日志）
 */
@Composable
fun TaskScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tasks by remember { mutableStateOf<List<TaskRecord>>(emptyList()) }
    var filter by remember { mutableStateOf(TaskFilter.ALL) }
    var detail by remember { mutableStateOf<TaskRecord?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }
    var clearAllMode by remember { mutableStateOf(false) }
    var confirmed by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }

    fun reload() {
        scope.launch { tasks = TaskStore.list(context) }
    }

    LaunchedEffect(Unit) {
        tasks = TaskStore.list(context)
        // 运行中任务短时更新（每 2 秒）
        while (true) {
            delay(2000)
            tasks = TaskStore.list(context)
        }
    }

    val current = detail
    if (current != null) {
        TaskDetailScreen(
            task = current,
            onBack = { detail = null },
            onDelete = {
                scope.launch {
                    TaskStore.delete(context, current.id)
                    detail = null
                    reload()
                    feedback = "已删除记录 #${current.id}"
                }
            },
        )
        return
    }

    val shown = tasks.filter {
        when (filter) {
            TaskFilter.ALL -> true
            TaskFilter.RUNNING -> it.status == TaskStatus.RUNNING
            TaskFilter.SUCCESS -> it.status == TaskStatus.SUCCESS
            TaskFilter.FAILED -> it.status == TaskStatus.FAILED
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Primer.BackgroundPrimary)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // 头部
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack, "返回",
                tint = Primer.IconPrimary,
                modifier = Modifier.size(24.dp).clickable { onBack() },
            )
            Spacer(Modifier.width(8.dp))
            Text("任务", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
            Spacer(Modifier.weight(1f))
            Text("${tasks.size} 条记录", fontSize = 12.sp, color = Primer.TextTertiary)
        }

        // 筛选 chips
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TaskFilter.entries.forEach { f ->
                val n = when (f) {
                    TaskFilter.ALL -> tasks.size
                    TaskFilter.RUNNING -> tasks.count { it.status == TaskStatus.RUNNING }
                    TaskFilter.SUCCESS -> tasks.count { it.status == TaskStatus.SUCCESS }
                    TaskFilter.FAILED -> tasks.count { it.status == TaskStatus.FAILED }
                }
                val on = filter == f
                Text(
                    "${f.label} $n",
                    fontSize = 12.5.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) Color.White else Primer.TextSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (on) Primer.Blue500 else Color.White)
                        .border(1.dp, if (on) Primer.Blue500 else Primer.Border, RoundedCornerShape(16.dp))
                        .clickable { filter = f }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }

        feedback?.let {
            Text(it, fontSize = 12.sp, color = Primer.Green500, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }

        // 列表
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("暂无任务记录", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextSecondary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "拉取仓库、提交文件、创建 PR 等操作会在这里留下记录",
                        fontSize = 12.sp,
                        color = Primer.TextTertiary,
                    )
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(shown, key = { it.id }) { t ->
                    TaskCard(
                        task = t,
                        onOpen = { detail = t },
                        onDelete = {
                            scope.launch {
                                TaskStore.delete(context, t.id)
                                reload()
                                feedback = "已删除记录 #${t.id}"
                            }
                        },
                    )
                }
            }
        }

        // 底部操作条
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BottomBtn("清理已完成", Primer.TextSecondary, Modifier.weight(1f)) {
                clearAllMode = false
                confirmed = false
                showClearDialog = true
            }
            BottomBtn("全部清除", Primer.Red500, Modifier.weight(1f)) {
                clearAllMode = true
                confirmed = false
                showClearDialog = true
            }
        }
    }

    if (showClearDialog) {
        val finished = tasks.count { it.status != TaskStatus.RUNNING }
        val running = tasks.count { it.status == TaskStatus.RUNNING }
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(if (clearAllMode) "清除全部任务记录" else "清理已完成/失败记录") },
            text = {
                Column {
                    Text(
                        if (clearAllMode) "将清除全部 $finished 条已结束记录与 $running 个运行中记录（运行中任务会被中断记录）。"
                        else "将清理 $finished 条已结束记录，保留 $running 个运行中任务。",
                        fontSize = 13.sp,
                        color = Primer.TextSecondary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("任务记录仅存本地，删除后不可恢复。", fontSize = 12.sp, color = Primer.TextTertiary)
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.clickable { confirmed = !confirmed },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(18.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .border(2.dp, if (confirmed) Primer.Red500 else Primer.Border, RoundedCornerShape(4.dp))
                                .then(if (confirmed) Modifier.background(Primer.Red500) else Modifier),
                            contentAlignment = Alignment.Center,
                        ) { if (confirmed) Text("✓", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.width(8.dp))
                        Text("我确认清理这些记录", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = confirmed,
                    onClick = {
                        scope.launch {
                            val n = if (clearAllMode) TaskStore.clearAll(context) else TaskStore.clearFinished(context)
                            showClearDialog = false
                            reload()
                            feedback = "已清理 $n 条记录"
                        }
                    },
                ) { Text("清理", color = if (confirmed) Primer.Red500 else Primer.TextTertiary) }
            },
            dismissButton = { TextButton(onClick = { showClearDialog = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun BottomBtn(label: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, color, RoundedCornerShape(8.dp))
            .background(Color.White)
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

@Composable
private fun TaskCard(task: TaskRecord, onOpen: () -> Unit, onDelete: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, Primer.Border, RoundedCornerShape(10.dp))
            .background(Color.White)
            .clickable { onOpen() },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(
                        when (task.status) {
                            TaskStatus.RUNNING -> Primer.Blue500
                            TaskStatus.SUCCESS -> Primer.Green500
                            TaskStatus.FAILED -> Primer.Red500
                            TaskStatus.CANCELED -> Primer.Gray500
                        },
                    ),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                task.title,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = Primer.TextPrimary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(
                task.kind.label,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Primer.TextSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Primer.Gray150)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "删除",
                fontSize = 11.sp,
                color = Primer.Red500,
                modifier = Modifier.clickable { onDelete() },
            )
        }
        Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 11.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("开始 ${formatTime(task.createdAt)}", fontSize = 11.5.sp, color = Primer.TextTertiary)
                Text(
                    if (task.status == TaskStatus.RUNNING) "已运行 ${formatDuration(System.currentTimeMillis() - task.createdAt)}"
                    else "耗时 ${formatDuration((task.finishedAt ?: task.updatedAt) - task.createdAt)}",
                    fontSize = 11.5.sp,
                    color = Primer.TextTertiary,
                )
                Text(task.status.label, fontSize = 11.5.sp, color = Primer.TextTertiary)
            }
            if (task.detail.isNotBlank()) {
                Spacer(Modifier.height(5.dp))
                Text(
                    task.detail,
                    fontSize = 11.5.sp,
                    color = if (task.status == TaskStatus.FAILED) Primer.Red500 else Primer.TextSecondary,
                    maxLines = 2,
                )
            }
            if (task.status == TaskStatus.RUNNING) {
                Spacer(Modifier.height(9.dp))
                if (task.progress in 0..100) {
                    LinearProgressIndicator(
                        progress = { task.progress / 100f },
                        modifier = Modifier.fillMaxWidth().height(5.dp),
                        color = Primer.Blue500,
                        trackColor = Primer.Gray200,
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(5.dp),
                        color = Primer.Blue500,
                        trackColor = Primer.Gray200,
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskDetailScreen(task: TaskRecord, onBack: () -> Unit, onDelete: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Primer.BackgroundPrimary)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack, "返回",
                tint = Primer.IconPrimary,
                modifier = Modifier.size(24.dp).clickable { onBack() },
            )
            Spacer(Modifier.width(8.dp))
            Text("任务详情", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
            Spacer(Modifier.weight(1f))
            Text("#${task.id}", fontSize = 12.sp, color = Primer.TextTertiary)
        }

        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            DetailCard("基本信息") {
                DetailRow("任务", task.title)
                DetailRow("类型", "${task.kind.label} · ${if (task.durable) "长任务" else "短任务"}", mono = true)
                DetailRow(
                    "状态", task.status.label,
                    valueColor = when (task.status) {
                        TaskStatus.RUNNING -> Primer.Blue500
                        TaskStatus.SUCCESS -> Primer.Green500
                        TaskStatus.FAILED -> Primer.Red500
                        TaskStatus.CANCELED -> Primer.Gray500
                    },
                )
                DetailRow("开始", formatDateTime(task.createdAt), mono = true)
                task.finishedAt?.let { DetailRow("结束", formatDateTime(it), mono = true) }
                DetailRow(
                    "耗时",
                    formatDuration((task.finishedAt ?: task.updatedAt) - task.createdAt),
                )
            }
            Spacer(Modifier.height(10.dp))
            DetailCard("详情") {
                Text(
                    task.detail.ifBlank { "（无）" },
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (task.status == TaskStatus.FAILED) Primer.Red500 else Primer.TextSecondary,
                    modifier = Modifier.padding(12.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            DetailCard("存储") {
                DetailRow("位置", "Room · tasks.db（仅本地）", mono = true)
                DetailRow("更新", formatDateTime(task.updatedAt), mono = true)
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BottomBtn("返回", Primer.TextSecondary, Modifier.weight(1f)) { onBack() }
            BottomBtn("删除记录", Primer.Red500, Modifier.weight(1f)) { onDelete() }
        }
    }
}

@Composable
private fun DetailCard(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, Primer.Border, RoundedCornerShape(10.dp)),
    ) {
        Text(
            title,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Primer.TextSecondary,
            modifier = Modifier.fillMaxWidth().background(Primer.Gray150).padding(horizontal = 12.dp, vertical = 9.dp),
        )
        content()
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    mono: Boolean = false,
    valueColor: Color = Primer.TextPrimary,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
        Text(label, fontSize = 12.5.sp, color = Primer.TextTertiary, modifier = Modifier.width(74.dp))
        Text(
            value,
            fontSize = 12.5.sp,
            color = valueColor,
            fontFamily = if (mono) FontFamily.Monospace else null,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun formatTime(ms: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))

private fun formatDateTime(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))

private fun formatDuration(ms: Long): String {
    if (ms < 1000) return "${ms}ms"
    val s = ms / 1000
    if (s < 60) return "${s} 秒"
    val m = s / 60
    if (m < 60) return "${m}m ${s % 60}s"
    return "${m / 60}h ${m % 60}m"
}
