package com.branchbase.ui.notification

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.branchbase.cache.PageCache
import com.branchbase.cache.SearchCacheDatabase
import com.branchbase.cache.SearchCacheManager
import com.branchbase.core.RustBridge
import com.branchbase.ui.log.Logger
import com.branchbase.ui.notification.NotifLayout
import com.branchbase.ui.notification.readNotifLayout
import com.branchbase.ui.theme.Primer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 未读/全部筛选 */
enum class NotifFilter { UNREAD, ALL }

/** 多选批量操作 */
private enum class BulkOp { READ, DONE, MUTE }

/** 一级页状态：加载中 / 错误 / 有数据（错误与数据互斥，用密封类避免二者同时为真） */
private sealed interface LoadState {
    data object Loading : LoadState
    data class Failed(val message: String) : LoadState
    data object Content : LoadState
}

/** 批量操作顺序执行时每条之间的间隔（毫秒）：避免同一秒内连发多次写请求触发二级速率限制 */
private const val NOTIF_BULK_GAP_MS = 120L

/** 一次最多预取多少条 issue/PR 内容预览（每条一次额外请求，宁少勿多，避免二级速率限制） */
private const val NOTIF_PREVIEW_MAX = 12

/**
 * 消息（通知收件箱）页。
 *
 * 数据读取复用 `RustBridge.getJson(host, token, "/notifications")`；
 * 交互分层：
 * - 点击行 → 本地标记已读 + 深链接跳转（`onOpenTarget(resolveTarget(n))`）；
 * - 右滑行 → 只标记已读、不跳转（`SwipeToDismissBox`，仅 StartToEnd）；
 * - 长按行 → 进入多选模式（行内不再放 ⋮，单条「完成 / 静音」走多选）；
 * - 整页下拉 → `load(force = true)`。
 */
@OptIn(ExperimentalMaterial3Api::class)
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
            Logger.ui("进入消息页", "Compose")
        }
    }

    val host = remember(sessionJson) {
        runCatching { JSONObject(sessionJson).optString("host", "github.com") }.getOrDefault("github.com")
    }
    val token = remember(sessionJson) {
        runCatching { JSONObject(sessionJson).getJSONObject("token").optString("access_token") }.getOrNull() ?: ""
    }

    val context = LocalContext.current
    // 长按进入多选时给一次震动反馈：多选是「模式切换」，没有触觉提示时用户常误以为没生效
    val haptics = LocalHapticFeedback.current
    val prefs = remember { context.getSharedPreferences("branchbase", Context.MODE_PRIVATE) }
    // 通知列表缓存（TTL 2 分钟，兼顾「未读」时效性）：返回上一层再进来先直出，再后台回源
    val cacheManager = remember(context) {
        SearchCacheManager(SearchCacheDatabase.getInstance(context).searchCacheDao())
    }

    // 本地「已读 thread id」持久化：远端 PATCH 未生效（.so 未重编译）或异步未完成时，
    // 页面切换返回后仍能保持已读状态（覆盖远端 unread=true）。
    fun readIds(): Set<String> = prefs.getStringSet("notif_read_ids", emptySet()) ?: emptySet()
    fun writeIds(ids: Set<String>) { prefs.edit().putStringSet("notif_read_ids", ids).apply() }

    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<Notification>>(emptyList()) }
    var loadState by remember { mutableStateOf<LoadState>(LoadState.Loading) }
    var refreshing by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(NotifFilter.UNREAD) }
    var typeFilter by remember { mutableStateOf<String?>(null) } // 类型筛选（null = 全部类型）
    var typeMenu by remember { mutableStateOf(false) }
    var before by remember { mutableStateOf<String?>(null) } // 分页游标（最后一条的 updated_at）
    var loadingMore by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(true) }
    var participating by remember { mutableStateOf(false) } // 「我参与的」筛选（服务端过滤）
    val layout = remember { mutableStateOf(readNotifLayout(context)) } // 通知显示模式（默认平铺）
    val expandedGroups = remember { mutableStateOf(setOf<String>()) } // 已展开的分组 key 集合

    // 多选模式：只以「选中集合」为状态本身，进入/退出都由它推导，避免两个状态不同步
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var bulkRunning by remember { mutableStateOf(false) }

    // issue/PR 内容预览：threadId → 最新评论（作者 + 正文），见 NotificationPreviewLoader.kt。
    // 只增不减：列表刷新 / 标记已读都不清空，避免「已经取回来的预览又消失」这种闪烁。
    var previews by remember { mutableStateOf<Map<String, NotificationPreview>>(emptyMap()) }

    /** 当前列表查询串（缓存键与失效都依赖它，保证两处永远一致） */
    fun listPath(beforeCursor: String? = null): String = buildString {
        append("/notifications?per_page=50&all=true")
        if (participating) append("&participating=true")
        if (beforeCursor != null) append("&before=").append(java.net.URLEncoder.encode(beforeCursor, "UTF-8"))
    }

    /** 本地已读集合 + 远端缓存失效（全部已读 / 单条已读共用，语义与改造前一致） */
    suspend fun invalidateOnRead() {
        cacheManager.delete(PageCache.notificationKey(listPath()))
        // 首页「未读通知」计数是另一个键，一并失效，避免回首页看到旧数字
        cacheManager.delete(PageCache.homeKey("unread"))
    }

    fun load(force: Boolean = false) {
        scope.launch {
            // 下拉刷新时骨架屏让位给顶部指示器，避免「骨架 + 转圈」双加载态叠加
            if (force) refreshing = true else loadState = LoadState.Loading
            before = null
            hasMore = true
            val path = listPath()
            val key = PageCache.notificationKey(path)
            // 先直出缓存（含过期数据）：返回上一层再进来不再空转一圈转圈。
            // 首次进入没有缓存 → cachedFirst 返回 null，仍走下面回源，骨架屏不变。
            val cached = PageCache.cachedFirst(cacheManager, key, PageCache.TYPE_NOTIFICATION, force)
            if (cached != null) {
                val ids = readIds()
                items = parseNotifications(cached).map { n -> if (n.id in ids) n.copy(unread = false) else n }
                before = items.lastOrNull()?.updatedAt
                hasMore = items.size >= 50
                loadState = LoadState.Content
            }
            // 回源并写回（未过期时直接返回缓存内容，失败返回 null，且不覆盖旧缓存）
            val json = PageCache.refresh(cacheManager, key, PageCache.TYPE_NOTIFICATION, force) {
                withContext(Dispatchers.IO) {
                    RustBridge.getJson(host, token, path)
                }
            }
            if (json == null || json.startsWith("ERROR:")) {
                // 回源失败：只有「本次确实直出了缓存」时才静默保留旧内容；
                // 首次进入（无缓存）仍按原语义落到错误态。
                if (cached == null && items.isEmpty()) loadState = LoadState.Failed("消息加载失败")
            } else {
                val ids = readIds()
                items = parseNotifications(json).map { n -> if (n.id in ids) n.copy(unread = false) else n }
                before = items.lastOrNull()?.updatedAt
                hasMore = items.size >= 50
                loadState = LoadState.Content
                Logger.net("GET /notifications → 200（${items.size} 条）", "GitHubAPI")
            }
            refreshing = false
        }
    }

    // 加载更早的通知（`before` 游标分页）
    fun loadMore() {
        val b = before
        if (b == null || loadingMore || !hasMore) return
        scope.launch {
            loadingMore = true
            val path = listPath(b)
            val key = PageCache.notificationKey(path)
            // 分页（`before` 游标）也走缓存：同一游标 == 同一页，
            // 未加载过的页没有缓存 → 仍然正常回源（不做任何预取）。
            // force = true：分页只在滚动触达时调用一次，这里必须真的能拿到下一页，
            // 不能因为页内已有旧缓存就停止加载（旧缓存仍会被 refresh 成功后覆盖写回）。
            val json = PageCache.refresh(cacheManager, key, PageCache.TYPE_NOTIFICATION, force = true) {
                withContext(Dispatchers.IO) {
                    RustBridge.getJson(host, token, path)
                }
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

    // ── issue/PR 内容预览 ──
    // 通知行原本只有「标题 + 仓库 #编号 + 触发动因」，「提到了你」后面到底是一句评审意见
    // 还是一个「+1」必须点进详情才知道。这里按需预取最新评论正文补在标题下面。
    // 三条约束（预览是纯增强，绝不能反过来拖累主列表）：
    // ① 只取**未读**的 Issue/PullRequest，且最多前 [NOTIF_PREVIEW_MAX] 条 —— 每条预览都是一次额外
    //    HTTP，全量预取会撞 GitHub 二级速率限制（被限流时整个通知列表一起拉不到）；
    // ② 并发上限 2（在 prefetchPreviews 内部用 Semaphore 控制），并用 TYPE_NOTIFICATION 缓存
    //    兜住滚动 / 重组 / 退出再进的重复请求；
    // ③ 任何一条失败都静默跳过（loader 内部 runCatching），不 Toast、不打断、不影响列表加载态。
    val previewTargets = items.filter {
        it.unread &&
            (it.subjectType == "Issue" || it.subjectType == "PullRequest") &&
            !it.latestCommentUrl.isNullOrBlank()
    }.take(NOTIF_PREVIEW_MAX)
    val previewUrlById = previewTargets.associate { it.id to it.latestCommentUrl }
    // 已发起过预取的 thread（含失败与空正文）：只用于去重，不作为 LaunchedEffect 的 key
    val previewRequested = remember { mutableSetOf<String>() }
    val previewCandidates = previewTargets.map { it.id }
    // ⚠️ 两个「必须这样写」的点，写错了功能只在第一条上生效：
    // ① key 只取「候选集合」，**不能**包含「已取到的预览」—— 否则第一条结果回流就改变 key、
    //    触发 LaunchedEffect 重启并取消整批预取，剩下 11 条永远发不出去（表现为只出 1 条预览）；
    // ② 真正的请求放进页面级 scope 而不是 LaunchedEffect 自己的协程：候选集合因刷新 / 标记已读
    //    变化时，effect 会被重启，但在跑的这批请求不该跟着被掐断（去重交给 previewRequested）。
    LaunchedEffect(previewCandidates) {
        val fresh = previewCandidates.filter { previewRequested.add(it) }
        if (fresh.isEmpty()) return@LaunchedEffect
        scope.launch {
            prefetchPreviews(
                context = context,
                host = host,
                token = token,
                threadIds = fresh,
                commentUrlOf = { id -> previewUrlById[id] },
            ) { p -> previews = previews + (p.threadId to p) }
        }
    }

    fun toggleGroup(key: String) {
        expandedGroups.value = if (key in expandedGroups.value) expandedGroups.value - key else expandedGroups.value + key
    }

    // ── 已读（点击 / 右滑共用；远端失败才回滚本地） ──

    /** 只做本地已读：写持久化集合 + 翻转列表状态 */
    fun markReadLocal(id: String) {
        writeIds(readIds() + id)
        items = items.map { if (it.id == id) it.copy(unread = false) else it }
    }

    /** 单条已读：乐观更新 → 远端 PATCH → 失败回滚 → 成功则作废缓存。不跳转。 */
    fun markReadRemote(n: Notification) {
        scope.launch {
            val ok = RustBridge.markNotificationRead(host, token, n.id)
            if (ok) {
                invalidateOnRead()
            } else if (n.unread) {
                // 同一 thread 的其它行不受影响：按 id 精确回滚
                writeIds(readIds().toMutableSet().apply { remove(n.id) })
                items = items.map { if (it.id == n.id) it.copy(unread = true) else it }
                Toast.makeText(context, "标记已读失败，请重试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 点击通知：本地标记已读 + 跳转（4 种布局复用） */
    fun onNotifClick(n: Notification) {
        markReadLocal(n.id)
        markReadRemote(n)
        onOpenTarget(resolveTarget(n))
    }

    // ── 多选模式 ──

    fun exitSelection() {
        selectedIds = emptySet()
        bulkRunning = false
    }

    /**
     * 长按进入多选并选中该行。
     *
     * 重做要点：**不再修改筛选条件**。旧实现进入多选时强制 `filter = ALL` + 清空类型筛选，
     * 结果是「长按的那一行可能因为筛选被换掉而跑位甚至消失」——用户长按的是眼前这一条，
     * 列表却在同一帧里换了一批数据，多选刚建立就失去了参照。现在筛选保持原样：
     * 长按谁就选谁，列表原地不动（`visible` 不变），多选的作用域始终等于**当前筛选下可见的行**。
     *
     * 由此带来的两个配套改动：
     * 1. 分组布局（按仓库 / 按会话 / 两级）下同样可以多选：进入多选时所有分组强制展开
     *    （见 `NotificationList(forceExpandGroups = ...)`），避免「已选的行被折叠在分组里看不见」；
     * 2. 「全选」只作用于当前可见的行（含筛选），语义与用户所见一致。
     */
    fun enterSelection(n: Notification) {
        typeMenu = false
        selectedIds = setOf(n.id)
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun toggleSelection(n: Notification) {
        selectedIds = if (n.id in selectedIds) selectedIds - n.id else selectedIds + n.id
    }

    /** 整组选中 / 取消整组（多选态下点分组头）：全选中则整组取消，否则补齐，避免逐条点 */
    fun toggleGroupSelection(ids: Set<String>) {
        if (ids.isEmpty()) return
        selectedIds = if (ids.all { it in selectedIds }) selectedIds - ids else selectedIds + ids
    }

    /**
     * 批量操作：先本地乐观更新，再**按顺序**逐条调用远端（每条之间 [NOTIF_BULK_GAP_MS] 间隔，不并发，
     * 避免触发二级速率限制）；失败逐条回滚并在结束时 Toast 汇总；全部结束退出多选模式。
     */
    fun runBulk(op: BulkOp, targetIds: Set<String>) {
        if (bulkRunning || targetIds.isEmpty()) return
        val original = items
        val originalReadIds = readIds()
        val targets = items.filter { it.id in targetIds }
        if (targets.isEmpty()) return

        bulkRunning = true
        // ① 本地乐观更新
        when (op) {
            BulkOp.READ -> {
                writeIds(originalReadIds + targetIds)
                items = items.map { if (it.id in targetIds) it.copy(unread = false) else it }
            }
            BulkOp.DONE, BulkOp.MUTE -> {
                items = items.filterNot { it.id in targetIds }
            }
        }

        scope.launch {
            // ② 远端顺序执行：串行 for + delay，单条失败不影响后续
            val failed = mutableListOf<String>()
            targets.forEachIndexed { index, n ->
                val ok = when (op) {
                    BulkOp.READ -> RustBridge.markNotificationRead(host, token, n.id)
                    BulkOp.DONE -> RustBridge.markNotificationDone(host, token, n.id)
                    BulkOp.MUTE -> RustBridge.unsubscribeThread(host, token, n.id)
                }
                if (!ok) failed += n.id
                if (index != targets.lastIndex) delay(NOTIF_BULK_GAP_MS)
            }
            // ③ 终态：全失败/部分失败都回滚本地，让用户看到的和远端一致
            if (failed.isNotEmpty()) {
                items = original
                if (op == BulkOp.READ) writeIds(originalReadIds)
                val msg = when (op) {
                    BulkOp.READ -> "标记已读失败：${failed.size} 条"
                    BulkOp.DONE -> "标记完成失败：${failed.size} 条"
                    BulkOp.MUTE -> "静音失败：${failed.size} 条"
                }
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            } else {
                invalidateOnRead()
            }
            bulkRunning = false
            exitSelection()
        }
    }

    // 筛选行上的计数（都在「当前类型筛选」口径下）：
    // 「未读 N」= 类型筛选下未读条数；「全部 N」= 类型筛选下全部条数。
    // （此前 unreadCount 被误传成 totalCount，两个 chip 数字相同）
    val typed = items.let { if (typeFilter == null) it else it.filter { n -> n.subjectType == typeFilter } }
    val unreadInType = typed.count { it.unread }
    val totalInType = typed.size

    // 未读数上报（驱动底部导航 badge）：始终基于「全部」列表，不受当前筛选影响
    val unread = items.count { it.unread }
    LaunchedEffect(unread) { onUnreadCountChange(unread) }

    val allTypes = remember(items.map { it.subjectType }.distinct()) { items.map { it.subjectType }.distinct() }
    val visible = items
        .let { if (filter == NotifFilter.UNREAD) it.filter { n -> n.unread } else it }
        .let { if (typeFilter == null) it else it.filter { n -> n.subjectType == typeFilter } }

    val inSelection = selectedIds.isNotEmpty()

    // 多选态下返回键 = 退出多选（而不是退出页面）：多选时不看内容，返回键留给「取消选择」更符合预期。
    // enabled = inSelection，非多选态下不拦截，返回键行为完全不变。
    BackHandler(enabled = inSelection) { exitSelection() }

    Column(Modifier.fillMaxSize().background(Primer.BackgroundPrimary)) {
        // 顶部栏（不参与滚动 / 不参与下拉）：标题 + 未读胶囊徽标 + 刷新 + 全部已读
        TopBar(
            unread = unread,
            onRefresh = { load(force = true) },
            onMarkAllRead = {
                val idsBefore = readIds()
                val unreadBefore = items.filter { it.unread }.map { it.id }.toSet()
                writeIds(idsBefore + items.map { it.id })
                items = items.map { it.copy(unread = false) }
                scope.launch {
                    val ok = RustBridge.markAllNotificationsRead(host, token)
                    if (ok) {
                        // 全部已读成功 → 作废本页缓存，避免返回后直出「未读」旧列表
                        invalidateOnRead()
                    } else {
                        writeIds(idsBefore)
                        items = items.map { if (it.id in unreadBefore) it.copy(unread = true) else it }
                        Toast.makeText(context, "全部已读失败，请重试", Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )

        // 多选操作条：替代筛选行占据同一位置（与筛选行同为一行高度，切换时列表不跳）
        if (inSelection) {
            SelectionBar(
                selectedCount = selectedIds.size,
                visibleCount = visible.size,
                allSelected = selectedIds.size >= visible.size && visible.isNotEmpty(),
                enabled = !bulkRunning,
                running = bulkRunning,
                onSelectAllToggle = {
                    selectedIds = if (selectedIds.size >= visible.size) emptySet() else visible.map { it.id }.toSet()
                },
                onRead = { runBulk(BulkOp.READ, selectedIds) },
                onDone = { runBulk(BulkOp.DONE, selectedIds) },
                onMute = { runBulk(BulkOp.MUTE, selectedIds) },
                onExit = { exitSelection() },
            )
        }

        // 筛选行固定在列表之上（不随列表滚动）：
        // ① 下拉刷新的圆形指示器画在 PullToRefreshBox 顶部，若筛选行是列表首项会被它盖住；
        // ② 固定后筛选条件随时可见，也避免「加载中筛选行跟着骨架一起动」。
        // 多选态下筛选行让位给多选条（两者同一位置、同一高度，切换时列表不跳）；
        // 筛选条件本身保持不变 —— 「已选 N / 共 M 项」里的 M 就是当前筛选下的可见条数，
        // 用户由此知道多选的作用范围，不会因为筛选行消失而失去上下文。
        if (!inSelection) {
            FilterRow(
                filter = filter,
                onFilterChange = { filter = it },
                unreadCount = unreadInType,
                totalCount = totalInType,
                participating = participating,
                onParticipatingChange = { participating = it },
                allTypes = allTypes,
                typeFilter = typeFilter,
                onTypeFilterChange = { typeFilter = it },
                typeMenu = typeMenu,
                onTypeMenuChange = { typeMenu = it },
            )
        }

        // 整页下拉刷新：只包住列表。
        // ⚠️ 必须用 weight(1f)：在 Column 里用 fillMaxSize() 会让它去占满「父容器整高」，
        //    与上面的 TopBar / 筛选行叠加后总高超出父容器，而 Compose 默认不裁剪子项，
        //    超出部分会画到容器边界之外（压住底部导航栏），表现为内容下沉 / UI 挤占。
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { load(force = true) },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            NotificationList(
                state = loadState,
                rows = visible,
                filter = filter,
                layout = layout.value,
                expandedGroups = expandedGroups.value,
                onToggleGroup = { toggleGroup(it) },
                typeFilter = typeFilter,
                // 多选在 4 种布局下都可用：以「是否处于多选态」为开关，
                // 而不是旧的「是否平铺布局」—— 旧写法让分组布局完全无法多选，
                // 且右滑已读的开关被这个标志顺带绑成了「仅平铺可用」（见 NotificationRow）
                selectionEnabled = inSelection,
                forceExpandGroups = inSelection,
                previews = previews,
                selectedIds = selectedIds,
                onClick = { onNotifClick(it) },
                onLongClick = { enterSelection(it) },
                onToggleSelection = { toggleSelection(it) },
                onToggleGroupSelection = { toggleGroupSelection(it) },
                onSwipeRead = { markReadLocal(it.id); markReadRemote(it) },
                hasMore = hasMore,
                loadingMore = loadingMore,
                onLoadMore = { loadMore() },
                onRetry = { load() },
            )
        }
    }
}

// ───────────────────────────────── 顶部栏 / 筛选行 / 多选条 ─────────────────────────────────

@Composable
private fun TopBar(unread: Int, onRefresh: () -> Unit, onMarkAllRead: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("消息", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary)
        // 未读胶囊徽标：0 时不显示
        if (unread > 0) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Primer.Blue500)
                    .padding(horizontal = 7.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (unread > 99) "99+" else "$unread",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onRefresh) {
            Icon(Icons.Filled.Refresh, contentDescription = "刷新消息", tint = Primer.IconPrimary)
        }
        TextButton(onClick = onMarkAllRead, enabled = unread > 0) {
            Icon(
                Icons.Filled.DoneAll,
                contentDescription = null,
                tint = if (unread > 0) Primer.Blue500 else Primer.Gray300,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text("已读", color = if (unread > 0) Primer.Blue500 else Primer.Gray300)
        }
    }
}

/**
 * 筛选栏：**4 等分格**（每格 `weight(1f)`）。
 *
 * 重做要点（对齐「填充比例 + 数字显示」的诉求）：
 * - 旧版是 `FlowRow` + 内容宽度自适应：四个 chip 宽度随文字长短参差不齐，数字拼在文字里
 *   （「未读 3」），既不好扫读也对不齐；
 * - 现在四格等宽（各占 1/4），标签与数字**分离**：数字用独立徽标呈现，
 *   0 不显示、>99 显示 99+、选中态徽标反色（白底蓝字）；
 * - 「类型」格显示当前类型名（超长省略）+ 下拉箭头。
 */
@Composable
private fun FilterRow(
    filter: NotifFilter,
    onFilterChange: (NotifFilter) -> Unit,
    unreadCount: Int,
    totalCount: Int,
    participating: Boolean,
    onParticipatingChange: (Boolean) -> Unit,
    allTypes: List<String>,
    typeFilter: String?,
    onTypeFilterChange: (String?) -> Unit,
    typeMenu: Boolean,
    onTypeMenuChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterCell(
            label = "未读",
            count = unreadCount,
            selected = filter == NotifFilter.UNREAD,
            modifier = Modifier.weight(1f),
        ) { onFilterChange(NotifFilter.UNREAD) }

        FilterCell(
            label = "全部",
            count = totalCount,
            selected = filter == NotifFilter.ALL,
            modifier = Modifier.weight(1f),
        ) { onFilterChange(NotifFilter.ALL) }

        FilterCell(
            label = "参与",
            count = null,
            selected = participating,
            modifier = Modifier.weight(1f),
        ) { onParticipatingChange(!participating) }

        Box(Modifier.weight(1f)) {
            FilterCell(
                label = typeFilter?.let { typeShortName(it) } ?: "类型",
                count = null,
                selected = typeFilter != null,
                showArrow = true,
                modifier = Modifier.fillMaxWidth(),
            ) { onTypeMenuChange(true) }
            DropdownMenu(expanded = typeMenu, onDismissRequest = { onTypeMenuChange(false) }) {
                DropdownMenuItem(
                    text = { Text("全部类型", fontSize = 13.sp) },
                    onClick = { onTypeFilterChange(null); onTypeMenuChange(false) },
                )
                allTypes.forEach { t ->
                    DropdownMenuItem(
                        // 与格子里的显示保持一致（同一份短名），避免「选了 PR 却显示 Pull Request」
                        text = { Text(typeShortName(t), fontSize = 13.sp) },
                        onClick = { onTypeFilterChange(t); onTypeMenuChange(false) },
                    )
                }
            }
        }
    }
}

/** 筛选格：标签 + 计数徽标（+ 可选下拉箭头）；四格等宽由调用方 `weight(1f)` 决定。 */
@Composable
private fun FilterCell(
    label: String,
    count: Int?,
    selected: Boolean,
    modifier: Modifier = Modifier,
    showArrow: Boolean = false,
    onClick: () -> Unit,
) {
    val fg = if (selected) Color.White else Primer.TextSecondary
    Row(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Primer.Blue500 else Primer.Gray150)
            .border(1.dp, if (selected) Primer.Blue500 else Primer.Gray200, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // 类型名可能很长：允许收缩并省略，避免把箭头挤出格子
            modifier = Modifier.weight(1f, fill = false),
        )
        if (count != null && count > 0) {
            Spacer(Modifier.width(4.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (selected) Color.White else Primer.Blue500.copy(alpha = 0.14f))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            ) {
                Text(
                    if (count > 99) "99+" else "$count",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) Primer.Blue500 else Primer.Blue500,
                    maxLines = 1,
                )
            }
        }
        if (showArrow) {
            Spacer(Modifier.width(2.dp))
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/**
 * 多选操作条：**单行**（高度与筛选行一致，均为 46dp）。
 *
 * 重做要点：旧版是「一行计数 + 一行操作按钮」的两行结构（≈100dp），进入多选时列表整体下移
 * 一大截，刚长按选中的那一行会直接从视野里被推走 —— 用户会以为「长按没生效」。
 * 现在压成单行：左侧计数（含可见总数），右侧 5 个 40dp 圆形图标按钮，
 * 与筛选行同高 ⇒ 进入/退出多选时列表**原地不动**。
 *
 * 按钮顺序按使用频率排：全选 → 已读 → 完成 → 静音 → 退出。
 * 每条都有 `contentDescription`（无语义按钮在无障碍下就是隐形按钮）。
 */
@Composable
private fun SelectionBar(
    selectedCount: Int,
    visibleCount: Int,
    allSelected: Boolean,
    enabled: Boolean,
    running: Boolean,
    onSelectAllToggle: () -> Unit,
    onRead: () -> Unit,
    onDone: () -> Unit,
    onMute: () -> Unit,
    onExit: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .background(Primer.Blue500.copy(alpha = 0.06f))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 计数：M 是当前筛选下的可见条数，让「全选」的范围可预期；
        // 批量执行期间换成进度文案（串行请求 + 120ms 间隔，条数多时要几秒）
        if (running) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Primer.Blue500, strokeWidth = 2.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            if (running) "处理中…" else "已选 $selectedCount / 共 $visibleCount",
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = Primer.TextPrimary,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        SelectionAction(
            icon = Icons.Filled.SelectAll,
            label = if (allSelected) "取消全选" else "全选",
            enabled = enabled,
        ) { onSelectAllToggle() }
        SelectionAction(Icons.Filled.MarkEmailRead, "标记已读", enabled) { onRead() }
        SelectionAction(Icons.Filled.Done, "标记完成", enabled) { onDone() }
        SelectionAction(Icons.Filled.VolumeOff, "静音", enabled) { onMute() }
        // 退出始终可用：即便批量操作正在跑，用户也该能收起多选态（请求会继续在后台跑完）
        SelectionAction(Icons.Filled.Close, "退出多选", true) { onExit() }
    }
}

/** 多选条上的单个图标动作（固定 40dp 触控区，图标 18dp） */
@Composable
private fun SelectionAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp)) {
        Icon(
            icon,
            contentDescription = label,
            modifier = Modifier.size(18.dp),
            tint = if (enabled) Primer.Blue500 else Primer.Gray300,
        )
    }
}

// ───────────────────────────────── 列表（含筛选行 / 分页 / 4 种布局） ─────────────────────────────────

@Composable
private fun NotificationList(
    state: LoadState,
    rows: List<Notification>,
    filter: NotifFilter,
    layout: NotifLayout,
    expandedGroups: Set<String>,
    onToggleGroup: (String) -> Unit,
    typeFilter: String?,
    selectionEnabled: Boolean,
    forceExpandGroups: Boolean,
    previews: Map<String, NotificationPreview>,
    selectedIds: Set<String>,
    onClick: (Notification) -> Unit,
    onLongClick: (Notification) -> Unit,
    onToggleSelection: (Notification) -> Unit,
    onToggleGroupSelection: (Set<String>) -> Unit,
    onSwipeRead: (Notification) -> Unit,
    hasMore: Boolean,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        when (state) {
            LoadState.Loading -> {
                items(6) { NotificationSkeleton() }
            }
            is LoadState.Failed -> {
                item(key = "__error__") { ErrorState(state.message, onRetry) }
            }
            LoadState.Content -> {
                if (rows.isEmpty()) {
                    item(key = "__empty__") { EmptyState(filter, typeFilter) }
                } else {
                    val rowContent: @Composable (Notification) -> Unit = { n ->
                        NotificationRow(
                            n = n,
                            preview = previews[n.id],
                            selectionEnabled = selectionEnabled,
                            selected = n.id in selectedIds,
                            onClick = { onClick(n) },
                            onLongClick = { onLongClick(n) },
                            onToggleSelection = { onToggleSelection(n) },
                            onSwipeRead = { onSwipeRead(n) },
                        )
                    }
                    when (layout) {
                        NotifLayout.FLAT -> {
                            items(rows, key = { it.id }) { n -> rowContent(n) }
                        }
                        NotifLayout.GROUP_BY_REPO -> {
                            // 注意：分组内容必须在 for 循环里逐条调用（不能在 forEach 的普通 lambda 内调用
                            // 可组合函数），否则协程/组合上下文不满足，编译期就会报错
                            for ((repoName, list) in rows.groupBy { it.repoFullName }) {
                                item(key = "repo:$repoName") {
                                    CollapsibleGroup(
                                        title = repoName,
                                        unreadCount = list.count { it.unread },
                                        expanded = forceExpandGroups || repoName in expandedGroups,
                                        // 多选态：分组头点击 = 整组选中/取消（逐条点太慢），并显示组内是否已全选
                                        selectionMode = forceExpandGroups,
                                        allSelected = list.all { it.id in selectedIds },
                                        onToggleSelection = { onToggleGroupSelection(list.map { it.id }.toSet()) },
                                        onToggle = { onToggleGroup(repoName) },
                                    ) {
                                        for (n in list) rowContent(n)
                                    }
                                }
                            }
                        }
                        NotifLayout.MERGE_BY_THREAD -> {
                            for ((threadKey, list) in rows.groupBy { it.url.ifBlank { it.id } }) {
                                item(key = "thread:$threadKey") {
                                    CollapsibleGroup(
                                        title = list.first().title,
                                        unreadCount = list.count { it.unread },
                                        expanded = forceExpandGroups || threadKey in expandedGroups,
                                        // 多选态：分组头点击 = 整组选中/取消（逐条点太慢），并显示组内是否已全选
                                        selectionMode = forceExpandGroups,
                                        allSelected = list.all { it.id in selectedIds },
                                        onToggleSelection = { onToggleGroupSelection(list.map { it.id }.toSet()) },
                                        onToggle = { onToggleGroup(threadKey) },
                                    ) {
                                        for (n in list) rowContent(n)
                                    }
                                }
                            }
                        }
                        NotifLayout.TWO_LEVEL -> {
                            for ((repoName, list) in rows.groupBy { it.repoFullName }) {
                                item(key = "l2repo:$repoName") {
                                    CollapsibleGroup(
                                        title = repoName,
                                        unreadCount = list.count { it.unread },
                                        expanded = forceExpandGroups || repoName in expandedGroups,
                                        // 多选态：分组头点击 = 整组选中/取消（逐条点太慢），并显示组内是否已全选
                                        selectionMode = forceExpandGroups,
                                        allSelected = list.all { it.id in selectedIds },
                                        onToggleSelection = { onToggleGroupSelection(list.map { it.id }.toSet()) },
                                        onToggle = { onToggleGroup(repoName) },
                                    ) {
                                        for ((tKey, threadList) in list.groupBy { it.url.ifBlank { it.id } }) {
                                            if (threadList.size > 1) {
                                                CollapsibleGroup(
                                                    title = threadList.first().title,
                                                    unreadCount = threadList.count { it.unread },
                                                    expanded = forceExpandGroups || tKey in expandedGroups,
                                                    // 多选态：分组头点击 = 整组选中/取消（逐条点太慢），并显示组内是否已全选
                                                    selectionMode = forceExpandGroups,
                                                    allSelected = threadList.all { it.id in selectedIds },
                                                    onToggleSelection = { onToggleGroupSelection(threadList.map { it.id }.toSet()) },
                                                    onToggle = { onToggleGroup(tKey) },
                                                ) {
                                                    for (n in threadList) rowContent(n)
                                                }
                                            } else {
                                                rowContent(threadList.first())
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
                            LaunchedEffect(Unit) { onLoadMore() }
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

// ───────────────────────────────── 列表行 ─────────────────────────────────

/**
 * 单条消息行。
 *
 * 识别特征保留「类型图标块」；未读额外有左侧 3dp 蓝色竖条 + 极浅蓝底 + 加粗标题 + 尾点（多重视觉冗余，
 * 不依赖单一信号，色弱 / 灰度屏也能区分）。
 *
 * 手势分工（[selectionEnabled] = 当前是否处于多选态）：
 * - 非多选态：点击 → 打开；长按 → 进入多选并选中该行；
 * - 多选态：点击 → 切换选中（不跳转）；长按不再重复触发进入多选；
 * - 右滑（StartToEnd）→ 标记已读、复位、不跳转，**多选态下必须关闭**：
 *   旧实现用「是否平铺布局」同时控制多选和右滑，导致多选态下未选中的未读行仍可被滑动，
 *   批量选择过程中很容易误触把行标成已读。现在改为「多选态一律禁用右滑」，
 *   并且右滑在 4 种布局下都可用（此前只在平铺布局可用属于顺带的耦合，并非设计）。
 */
@Composable
private fun NotificationRow(
    n: Notification,
    preview: NotificationPreview? = null,
    selectionEnabled: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleSelection: () -> Unit,
    onSwipeRead: () -> Unit,
) {
    SwipeToReadRow(enabled = !selectionEnabled && n.unread, onRead = onSwipeRead) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { if (selectionEnabled) onToggleSelection() else onClick() },
                    onLongClick = { if (!selectionEnabled) onLongClick() },
                ),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (n.unread) Primer.Blue500.copy(alpha = 0.04f) else Primer.BackgroundSecondary,
            ),
            // 已读行靠 1dp 边框与极浅蓝底区分，不靠投影（通知列表密度高，投影会糊成一片）
            border = BorderStroke(
                1.dp,
                if (n.unread) Primer.Blue500.copy(alpha = 0.20f) else Primer.Gray150,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                // 未读左侧竖条（3dp）
                if (n.unread) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(Primer.Blue500),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    // 多选态显示复选框（纯视觉：点击由整行的 combinedClickable 统一处理，
                    // 避免 Checkbox 自身可点导致一次点击被消费两次），否则显示类型图标块
                    if (selectionEnabled) {
                        Checkbox(
                            checked = selected,
                            onCheckedChange = null,
                            modifier = Modifier.size(24.dp).padding(top = 4.dp),
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(n.tint.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(n.icon, contentDescription = n.subjectType, tint = n.tint, modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            n.title,
                            fontSize = 13.5.sp,
                            fontWeight = if (n.unread) FontWeight.Bold else FontWeight.SemiBold,
                            color = Primer.TextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // 内容预览：最新评论「作者：正文」。放在标题与元信息之间 ——
                        // 「谁说了什么」是决定要不要点进去的关键信息，比仓库名/时间更该靠前。
                        // 未取到（未预取、取失败、正文为空）时整行不占位，行高回到原来的样子。
                        if (preview != null) {
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (preview.avatarUrl.isNotBlank()) {
                                    AsyncImage(
                                        model = preview.avatarUrl,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp).clip(CircleShape),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                }
                                Text(
                                    if (preview.author.isBlank()) preview.body else "${preview.author}：${preview.body}",
                                    fontSize = 12.sp,
                                    color = Primer.TextSecondary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Spacer(Modifier.height(5.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                n.repoFullName + (n.targetNumber?.let { " #$it" } ?: ""),
                                fontSize = 12.sp,
                                color = Primer.TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                n.reasonLabel,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = n.reasonColor,
                                maxLines = 1,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(n.reasonColor.copy(alpha = 0.12f))
                                    .padding(horizontal = 7.dp, vertical = 1.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(n.relativeTime, fontSize = 11.sp, color = Primer.TextTertiary)
                        }
                    }
                    if (n.unread) {
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(Primer.Blue500))
                    }
                }
            }
        }
    }
}

/**
 * 右滑标记已读的包装。
 *
 * 只启用 `StartToEnd`（右滑）；`onDismiss` 里调用已读逻辑后必须 `reset()` 复位 ——
 * 已读只是状态变化，**不能真的把行从列表移除**。未读点/竖条的消失本身就是"已生效"的反馈。
 */
@Composable
private fun SwipeToReadRow(enabled: Boolean, onRead: () -> Unit, content: @Composable () -> Unit) {
    val currentOnRead by rememberUpdatedState(onRead)
    val dismissState = rememberSwipeToDismissBoxState()
    LaunchedEffect(dismissState.currentValue) {
        dismissState.reset() // 兜底：任何残留在已滑出状态的情况都复位
    }
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = enabled,
        enableDismissFromEndToStart = false,
        backgroundContent = {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Primer.Blue500.copy(alpha = 0.12f))
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = Primer.Blue500, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("已读", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Primer.Blue500)
            }
        },
        onDismiss = { direction ->
            if (direction == SwipeToDismissBoxValue.StartToEnd) currentOnRead()
        },
    ) {
        content()
    }
}

// ───────────────────────────────── 分组 / 空态 / 骨架 ─────────────────────────────────

/** 可折叠分组：标题行（标题 + 未读数 + 箭头）+ 可展开的内容（带高度过渡动画） */
@Composable
private fun CollapsibleGroup(
    title: String,
    unreadCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    // 多选态：分组头点击改为「整组选中/取消」，末端用复选框体现组内是否已全选
    selectionMode: Boolean = false,
    allSelected: Boolean = false,
    onToggleSelection: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(8.dp)).background(Primer.BackgroundSecondary),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { if (selectionMode) onToggleSelection() else onToggle() }
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = Primer.TextPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (unreadCount > 0) {
                Box(Modifier.clip(CircleShape).background(Primer.Blue500).padding(horizontal = 6.dp, vertical = 1.dp)) {
                    Text("$unreadCount", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Spacer(Modifier.width(6.dp))
            }
            if (selectionMode) {
                // 纯视觉：点击由整个分组头统一消费，避免一次点击被 Checkbox 与 Row 各消费一次
                Checkbox(
                    checked = allSelected,
                    onCheckedChange = null,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                val rotation by animateFloatAsState(if (expanded) 90f else 0f, label = "arrow")
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = if (expanded) "收起分组" else "展开分组",
                    tint = Primer.IconSecondary,
                    modifier = Modifier.size(18.dp).rotate(rotation),
                )
            }
        }
        AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) { content() }
        }
    }
}

@Composable
private fun EmptyState(filter: NotifFilter, typeFilter: String?) {
    Box(Modifier.fillMaxWidth().padding(vertical = 64.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Primer.Gray300, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(12.dp))
            Text(
                when {
                    typeFilter != null -> "该类型下没有消息"
                    filter == NotifFilter.UNREAD -> "没有未读消息"
                    else -> "暂无消息"
                },
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Primer.TextSecondary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (filter == NotifFilter.UNREAD) "你已看完所有消息 🎉" else "当有人提及、评论或请求审查时，会在这里收到消息。",
                fontSize = 12.5.sp,
                color = Primer.TextTertiary,
            )
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 64.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = Primer.Gray300, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(12.dp))
            Text(message, fontSize = 13.sp, color = Primer.TextTertiary)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRetry) { Text("重试", color = Primer.Blue500) }
        }
    }
}

/**
 * 骨架行：**结构与尺寸都与 [NotificationRow] 一一对应**
 * （同 Card 圆角 8dp / 同边框 / 同内边距 10dp / 图标 32dp / 标题行 18dp + 间隔 5dp + meta 行 15dp）。
 *
 * 之前是一个固定 56dp 的灰块：与真实行（单行标题约 58dp、双行标题约 76dp）不等高，
 * 加载完成时整列会向下「沉」一次；现在高度与真实行一致，切换时不跳动。
 */
@Composable
private fun NotificationSkeleton() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Primer.BackgroundSecondary),
        border = BorderStroke(1.dp, Primer.Gray150),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(Primer.Gray150),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth(0.72f).height(18.dp).clip(RoundedCornerShape(4.dp)).background(Primer.Gray150))
                Spacer(Modifier.height(5.dp))
                Box(Modifier.fillMaxWidth(0.42f).height(15.dp).clip(RoundedCornerShape(4.dp)).background(Primer.Gray150))
            }
        }
    }
}
