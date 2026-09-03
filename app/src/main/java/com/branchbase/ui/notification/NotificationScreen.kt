package com.branchbase.ui.notification

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.core.RustBridge
import com.branchbase.ui.log.Logger
import com.branchbase.ui.notification.NotifLayout
import com.branchbase.ui.notification.readNotifLayout
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
    var typeFilter by remember { mutableStateOf<String?>(null) } // 类型筛选（null = 全部类型）
    var typeMenu by remember { mutableStateOf(false) }
    var before by remember { mutableStateOf<String?>(null) } // 分页游标（最后一条的 updated_at）
    var loadingMore by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(true) }
    var participating by remember { mutableStateOf(false) } // 「我参与的」筛选（服务端过滤）
    var layout by remember { mutableStateOf(readNotifLayout(context)) } // 通知显示模式（默认平铺）
    val expandedGroups = remember { mutableStateOf(setOf<String>()) } // 已展开的分组 key 集合

    fun load() {
        scope.launch {
            loading = true
            error = null
            before = null
            hasMore = true
            val path = buildString {
                append("/notifications?per_page=50&all=true")
                if (participating) append("&participating=true")
            }
            val json = withContext(Dispatchers.IO) {
                RustBridge.getJson(host, token, path)
            }
            if (json == null || json.startsWith("ERROR:")) {
                items = emptyList()
                error = "通知加载失败"
            } else {
                val ids = readIds()
                items = parseNotifications(json).map { n -> if (n.id in ids) n.copy(unread = false) else n }
                before = items.lastOrNull()?.updatedAt
                hasMore = items.size >= 50
                Logger.net("GET /notifications → 200（${items.size} 条）", "GitHubAPI")
            }
            loading = false
        }
    }

    // 加载更早的通知（`before` 游标分页）
    fun loadMore() {
        val b = before
        if (b == null || loadingMore || !hasMore) return
        scope.launch {
            loadingMore = true
            val encoded = java.net.URLEncoder.encode(b, "UTF-8")
            val path = buildString {
                append("/notifications?per_page=50&all=true")
                if (participating) append("&participating=true")
                append("&before=$encoded")
            }
            val json = withContext(Dispatchers.IO) {
                RustBridge.getJson(host, token, path)
            }
            if (json == null || json.startsWith("ERROR:")) {
                hasMore = false
            } else {
                val ids = readIds()
                val newItems = parseNotifications(json).map { n -> if (n.id in ids) n.copy(unread = false) else n }
                if (newItems.isEmpty()) {
                    hasMore = false
                } else {
                    val existing = items.map { it.id }.toSet()
                    val dedup = newItems.filter { it.id !in existing }
                    items = items + dedup
                    before = newItems.last().updatedAt
                    hasMore = newItems.size >= 50 && dedup.isNotEmpty()
                }
            }
            loadingMore = false
        }
    }
    LaunchedEffect(participating) { load() }

    // 点击通知：本地标记已读 + 跳转（4 种布局复用）
    fun onNotifClick(n: Notification) {
        val wasUnread = n.unread
        writeIds(readIds() + n.id)
        items = items.map { if (it.id == n.id) it.copy(unread = false) else it }
        scope.launch {
            val ok = RustBridge.markNotificationRead(host, token, n.id)
            if (!ok && wasUnread) {
                val ids = readIds().toMutableSet().apply { remove(n.id) }
                writeIds(ids)
                items = items.map { if (it.id == n.id) it.copy(unread = true) else it }
                Toast.makeText(context, "标记已读失败，请重试", Toast.LENGTH_SHORT).show()
            }
        }
        onOpenTarget(resolveTarget(n))
    }

    // 切换分组展开/收起
    fun toggleGroup(key: String) {
        expandedGroups.value = if (key in expandedGroups.value) expandedGroups.value - key else expandedGroups.value + key
    }

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
                val idsBefore = readIds()
                val unreadBefore = items.filter { it.unread }.map { it.id }.toSet()
                writeIds(idsBefore + items.map { it.id })
                items = items.map { it.copy(unread = false) }
                scope.launch {
                    val ok = RustBridge.markAllNotificationsRead(host, token)
                    if (!ok) {
                        writeIds(idsBefore)
                        items = items.map { if (it.id in unreadBefore) it.copy(unread = true) else it }
                        Toast.makeText(context, "全部已读失败，请重试", Toast.LENGTH_SHORT).show()
                    }
                }
            }, enabled = unread > 0) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = if (unread > 0) Primer.Blue500 else Primer.Gray300, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("已读", color = if (unread > 0) Primer.Blue500 else Primer.Gray300)
            }
        }

        // 筛选栏：未读 / 全部 + 类型下拉
        val allTypes = remember(items) { items.map { it.subjectType }.distinct() }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterTab("未读", items.count { it.unread }, filter == NotifFilter.UNREAD) { filter = NotifFilter.UNREAD }
            FilterTab("全部", items.size, filter == NotifFilter.ALL) { filter = NotifFilter.ALL }
            // 我参与的（服务端过滤，切换触发重新加载）
            Text(
                "参与",
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (participating) Color.White else Primer.TextSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (participating) Primer.Blue500 else Primer.Gray150)
                    .clickable { participating = !participating }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            Spacer(Modifier.weight(1f))
            // 类型筛选下拉
            Box {
                Row(
                    Modifier.clip(RoundedCornerShape(8.dp)).background(Primer.Gray150).clickable { typeMenu = true }.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(typeFilter ?: "类型", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextSecondary)
                    Spacer(Modifier.width(2.dp))
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = Primer.IconSecondary, modifier = Modifier.size(16.dp))
                }
                DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                    DropdownMenuItem(text = { Text("全部类型", fontSize = 13.sp) }, onClick = { typeFilter = null; typeMenu = false })
                    allTypes.forEach { t ->
                        DropdownMenuItem(text = { Text(t, fontSize = 13.sp) }, onClick = { typeFilter = t; typeMenu = false })
                    }
                }
            }
        }

        // 列表
        when {
            loading -> LoadingList()
            error != null -> ErrorState(error!!) { load() }
            else -> {
                val visible = items
                    .let { if (filter == NotifFilter.UNREAD) it.filter { n -> n.unread } else it }
                    .let { if (typeFilter == null) it else it.filter { n -> n.subjectType == typeFilter } }
                if (visible.isEmpty()) {
                    EmptyState(filter)
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                        when (layout) {
                            NotifLayout.FLAT -> {
                                items(visible, key = { it.id }) { n ->
                                    NotificationRow(n) { onNotifClick(n) }
                                }
                            }
                            NotifLayout.GROUP_BY_REPO -> {
                                visible.groupBy { it.repoFullName }.forEach { (repoName, list) ->
                                    item(key = "repo:$repoName") {
                                        CollapsibleGroup(
                                            title = repoName,
                                            unreadCount = list.count { it.unread },
                                            expanded = repoName in expandedGroups.value,
                                            onToggle = { toggleGroup(repoName) },
                                        ) {
                                            list.forEach { n -> NotificationRow(n) { onNotifClick(n) } }
                                        }
                                    }
                                }
                            }
                            NotifLayout.MERGE_BY_THREAD -> {
                                visible.groupBy { it.url.ifBlank { it.id } }.forEach { (key, list) ->
                                    item(key = "thread:$key") {
                                        CollapsibleGroup(
                                            title = list.first().title,
                                            unreadCount = list.count { it.unread },
                                            expanded = key in expandedGroups.value,
                                            onToggle = { toggleGroup(key) },
                                        ) {
                                            list.forEach { n -> NotificationRow(n) { onNotifClick(n) } }
                                        }
                                    }
                                }
                            }
                            NotifLayout.TWO_LEVEL -> {
                                visible.groupBy { it.repoFullName }.forEach { (repoName, list) ->
                                    item(key = "l2repo:$repoName") {
                                        CollapsibleGroup(
                                            title = repoName,
                                            unreadCount = list.count { it.unread },
                                            expanded = repoName in expandedGroups.value,
                                            onToggle = { toggleGroup(repoName) },
                                        ) {
                                            list.groupBy { it.url.ifBlank { it.id } }.forEach { (tKey, threadList) ->
                                                if (threadList.size > 1) {
                                                    CollapsibleGroup(
                                                        title = threadList.first().title,
                                                        unreadCount = threadList.count { it.unread },
                                                        expanded = tKey in expandedGroups.value,
                                                        onToggle = { toggleGroup(tKey) },
                                                    ) {
                                                        threadList.forEach { n -> NotificationRow(n) { onNotifClick(n) } }
                                                    }
                                                } else {
                                                    NotificationRow(threadList.first()) { onNotifClick(threadList.first()) }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        // 分页加载 footer（滚动到可见时自动触发 loadMore）
                        if (hasMore) {
                            item(key = "__load_more__") {
                                LaunchedEffect(Unit) { loadMore() }
                                Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                                    if (loadingMore) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Primer.Blue500, strokeWidth = 2.dp)
                                    } else {
                                        Text("加载更多", fontSize = 12.sp, color = Primer.TextTertiary)
                                    }
                                }
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

/** 可折叠分组：标题行（标题 + 未读数 + 箭头）+ 可展开的内容（带高度过渡动画） */
@Composable
private fun CollapsibleGroup(
    title: String,
    unreadCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(Primer.BackgroundSecondary),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { onToggle() }.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = Primer.TextPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (unreadCount > 0) {
                Box(Modifier.clip(CircleShape).background(Primer.Blue500).padding(horizontal = 6.dp, vertical = 1.dp)) {
                    Text("$unreadCount", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Spacer(Modifier.width(6.dp))
            }
            val rotation by animateFloatAsState(if (expanded) 90f else 0f, label = "arrow")
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (expanded) "收起" else "展开",
                tint = Primer.IconSecondary,
                modifier = Modifier.size(18.dp).rotate(rotation),
            )
        }
        AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) { content() }
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