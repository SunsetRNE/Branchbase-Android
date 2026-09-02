package com.branchbase.ui.notification

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.core.RustBridge
import com.branchbase.ui.log.Logger
import com.branchbase.ui.theme.Primer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 未读/全部筛选 */
enum class NotifFilter { UNREAD, ALL }

/**
 * 通知收件箱（替换 `MainScreen` 的 Placeholder）。
 *
 * 数据读取复用 `RustBridge.getJson(host, token, "/notifications")`；
 * 点击通知 → 本地标记已读 + 通过 `onOpenTarget` 上报跳转目标（深链接直达详情子页）。
 */
@Composable
fun NotificationScreen(
    sessionJson: String,
    onOpenTarget: (NotifTarget) -> Unit,
    onUnreadCountChange: (Int) -> Unit,
) {
    val logged = remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!logged.value) {
            logged.value = true
            Logger.ui("进入通知页", "Compose")
        }
    }

    val host = remember(sessionJson) {
        runCatching { JSONObject(sessionJson).optString("host", "github.com") }.getOrDefault("github.com")
    }
    val token = remember(sessionJson) {
        runCatching { JSONObject(sessionJson).getJSONObject("token").optString("access_token") }.getOrNull() ?: ""
    }

    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("branchbase", Context.MODE_PRIVATE) }

    // 本地「已读 thread id」持久化：远端 PATCH 未生效（.so 未重编译）或异步未完成时，
    // 页面切换返回后仍能保持已读状态（覆盖远端 unread=true）。
    fun readIds(): Set<String> = prefs.getStringSet("notif_read_ids", emptySet()) ?: emptySet()
    fun writeIds(ids: Set<String>) { prefs.edit().putStringSet("notif_read_ids", ids).apply() }

    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<Notification>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf(NotifFilter.UNREAD) }

    fun load() {
        scope.launch {
            loading = true
            error = null
            val json = withContext(Dispatchers.IO) {
                RustBridge.getJson(host, token, "/notifications?per_page=50")
            }
            if (json == null || json.startsWith("ERROR:")) {
                items = emptyList()
                error = "通知加载失败"
            } else {
                val ids = readIds()
                items = parseNotifications(json).map { n -> if (n.id in ids) n.copy(unread = false) else n }
                Logger.net("GET /notifications → 200（${items.size} 条）", "GitHubAPI")
            }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }

    // 未读数上报（驱动底部导航 badge）
    val unread = items.count { it.unread }
    LaunchedEffect(unread) { onUnreadCountChange(unread) }

    Column(Modifier.fillMaxSize().background(Primer.BackgroundPrimary)) {
        // 顶部栏：标题 + 刷新 + 全部已读
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("通知", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary, modifier = Modifier.weight(1f))
            IconButton(onClick = { load() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新", tint = Primer.IconPrimary)
            }
            TextButton(onClick = {
                writeIds(readIds() + items.map { it.id })
                items = items.map { it.copy(unread = false) }
                scope.launch { RustBridge.markAllNotificationsRead(host, token) }
            }, enabled = unread > 0) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = if (unread > 0) Primer.Blue500 else Primer.Gray300, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("已读", color = if (unread > 0) Primer.Blue500 else Primer.Gray300)
            }
        }

        // 筛选栏：未读 / 全部
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterTab("未读", items.count { it.unread }, filter == NotifFilter.UNREAD) { filter = NotifFilter.UNREAD }
            FilterTab("全部", items.size, filter == NotifFilter.ALL) { filter = NotifFilter.ALL }
        }

        // 列表
        when {
            loading -> LoadingList()
            error != null -> ErrorState(error!!) { load() }
            else -> {
                val visible = if (filter == NotifFilter.UNREAD) items.filter { it.unread } else items
                if (visible.isEmpty()) {
                    EmptyState(filter)
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                        items(visible, key = { it.id }) { n ->
                            NotificationRow(n) {
                                writeIds(readIds() + n.id)
                                items = items.map { if (it.id == n.id) it.copy(unread = false) else it }
                                scope.launch { RustBridge.markNotificationRead(host, token, n.id) }
                                onOpenTarget(resolveTarget(n))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterTab(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Primer.Blue500 else Primer.Gray150)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = if (selected) androidx.compose.ui.graphics.Color.White else Primer.TextSecondary)
        Spacer(Modifier.width(4.dp))
        Text("($count)", fontSize = 11.sp, color = if (selected) androidx.compose.ui.graphics.Color.White else Primer.TextTertiary)
    }
}

@Composable
private fun NotificationRow(n: Notification, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (n.unread) Primer.Blue500.copy(alpha = 0.06f) else Primer.BackgroundSecondary)
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // 类型图标块
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(n.tint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(n.icon, contentDescription = n.subjectType, tint = n.tint, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(n.title, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary, maxLines = 2)
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(n.repoFullName + (n.targetNumber?.let { " #$it" } ?: ""), fontSize = 12.sp, color = Primer.TextSecondary, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp))
                // reason 胶囊
                Text(
                    n.reasonLabel,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = n.reasonColor,
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(n.reasonColor.copy(alpha = 0.12f))
                        .padding(horizontal = 7.dp, vertical = 1.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(n.relativeTime, fontSize = 11.sp, color = Primer.TextTertiary)
            }
        }
        // 未读蓝点
        if (n.unread) {
            Spacer(Modifier.width(8.dp))
            Box(Modifier.padding(top = 4.dp).size(8.dp).clip(CircleShape).background(Primer.Blue500))
        }
    }
}

@Composable
private fun LoadingList() {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        repeat(6) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Primer.Gray150)
                    .padding(vertical = 6.dp),
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, fontSize = 13.sp, color = Primer.TextTertiary)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRetry) { Text("重试", color = Primer.Blue500) }
        }
    }
}

@Composable
private fun EmptyState(filter: NotifFilter) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Primer.Gray300, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(12.dp))
            Text(
                if (filter == NotifFilter.UNREAD) "没有未读通知" else "暂无通知",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Primer.TextSecondary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (filter == NotifFilter.UNREAD) "你已看完所有通知 🎉" else "当有人提及、评论或请求审查时，会在这里收到通知。",
                fontSize = 12.5.sp,
                color = Primer.TextTertiary,
            )
        }
    }
}