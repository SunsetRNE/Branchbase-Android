package com.branchbase.ui.notification

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.branchbase.R
import com.branchbase.ui.navigation.PageBackHandler
import com.branchbase.ui.navigation.rememberPageResumeTick
import com.branchbase.ui.theme.color
import com.branchbase.ui.theme.textColor
import com.branchbase.ui.theme.rememberPressFeedback
import com.branchbase.ui.theme.ProvideShimmer
import com.branchbase.ui.theme.skeletonBlock
import com.branchbase.ui.theme.revealExit
import com.branchbase.ui.theme.revealEnter
import com.branchbase.cache.PageCache
import com.branchbase.cache.SearchCacheDatabase
import com.branchbase.cache.SearchCacheManager
import com.branchbase.core.RustBridge
import com.branchbase.ui.log.Logger
import com.branchbase.ui.resolve
import com.branchbase.ui.theme.Primer
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 消息（通知收件箱）页。
 *
 * ## 这一版改了什么
 *
 * **① 分类设计被舍弃，改为右下角 FAB + 弹窗面板**
 * 原来列表上方常驻一条四等分筛选行（未读 / 全部 / 参与 / 类型，46dp），
 * 一次只能表达「一个维度」，而显示模式（[NotifLayout] 4 种）在手机上根本没有入口。
 * 现在整行删除、列表高度 +46dp，全部维度收进右下角面板（见 [NotifPanel]）：
 * 分类 / 类型（多选芯片）/ 视图模式 / 时间范围 / 排序 / **过往 Issue**。
 * 面板改动**即时生效**，底部只留「重置 / 完成」。
 *
 * **② 卡片长按补全为完整状态机**
 * | 手势 | 普通态 | 多选态 |
 * |---|---|---|
 * | 轻点 | 本地标已读 → 远端 PATCH → 深链接跳转 | 切换选中 |
 * | 右滑 | 标记已读、原地复位、不跳转 | 禁用（避免批量时误触） |
 * | 长按 | 触觉 + **快捷动作面板**（含「多选」入口） | **锚点 → 当前行区间选中** |
 * | 长按后拖动 | —— | **刷选**：划过即选中 |
 * | 点分组头 | 展开 / 收起 | 整组选中 / 取消（半选显示横杠） |
 *
 * **③ 预渲染 / 预加载提前到首页阶段**
 * 首帧同步读取 [NotifSnapshot]（首页渲染时已由 [NotificationPrefetcher] 填好），
 * 因此进入本页**没有骨架屏这一帧**；网络回源在后台静默进行。
 *
 * **④ 列表重绘（1.0.76）：CI 通知折叠 + 卡片盒改行形态**
 * 真机上连着 8 条 `Build workflow run failed for main branch`（同仓库 / 同分支 / 同一天，
 * 标题逐字相同），逐条渲染就是一屏一模一样的行 —— 与动态页「30 条里 28 条是 PushEvent」
 * 同一个问题，因此口径照抄那边的 `collapsePushes`，纯函数在 [collapseCiRuns]。
 * 同时把「卡片盒」改成「行 + 1dp 分隔线」（浅色板里 canvas 与 canvasSubtle 都是纯白，
 * 卡片只靠灰边撑着），原因标签只在 [Notification.reasonHighSignal] 时渲染。
 * 设计依据：`docs/specs/screens-design.md` §3、`docs/specs/VERSION-NOTES.md` 1.0.76。
 *
 * 数据读取复用 `RustBridge.getJson(host, token, "/notifications")`，未新增读接口。
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
        runCatching { org.json.JSONObject(sessionJson).optString("host", "github.com") }.getOrDefault("github.com")
    }
    val token = remember(sessionJson) {
        runCatching { org.json.JSONObject(sessionJson).getJSONObject("token").optString("access_token") }.getOrNull() ?: ""
    }

    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    // 系统通知状态（权限 + 总开关）：没开时在列表上方给一条可关闭的横幅。
    // 注意：这只影响「能不能弹系统通知」，与站内通知列表无关 —— 列表永远照常工作。
    val systemNotification = rememberSystemNotificationState()
    var notifBannerDismissed by remember { mutableStateOf(isNotificationBannerDismissed(context)) }
    // 通知列表缓存（TTL 2 分钟，兼顾「未读」时效性）：返回上一层再进来先直出，再后台回源
    val cacheManager = remember(context) {
        SearchCacheManager(SearchCacheDatabase.getInstance(context).searchCacheDao())
    }

    // ── 首帧：同步吃快照（③ 的落点）──
    // 有快照 = 直接进 Content，不经过 Loading，因此不会闪一帧骨架
    val fromSnapshot = remember { NotifSnapshot.isFresh() && NotifSnapshot.value.isNotEmpty() }
    var items by remember { mutableStateOf(if (fromSnapshot) NotifSnapshot.value else emptyList()) }
    var loadState by remember { mutableStateOf(if (fromSnapshot) LoadState.Content else LoadState.Loading) }
    var refreshing by remember { mutableStateOf(false) }
    var before by remember { mutableStateOf(if (fromSnapshot) NotifSnapshot.value.lastOrNull()?.updatedAt else null) }
    var loadingMore by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(fromSnapshot && NotifSnapshot.value.size >= 50) }

    // ── 筛选 / 视图维度（全部由右下角面板驱动）──
    var category by remember { mutableStateOf(NotifCategory.UNREAD) }
    var types by remember { mutableStateOf<Set<String>>(emptySet()) }
    val layout = remember { mutableStateOf(readNotifLayout(context)) }
    var range by remember { mutableStateOf(NotifRange.ALL) }
    var sort by remember { mutableStateOf(NotifSort.NEWEST) }
    var panelOpen by remember { mutableStateOf(false) }
    var historyQuery by remember { mutableStateOf("") }
    /**
     * 服务端口径的「我参与的」列表（`/notifications?participating=true`）。
     *
     * 它和首屏列表是**两份不同查询**（键不同、服务端过滤条件不同），因此单独存一份，
     * 切回其它分类时不重新请求；为 null 表示还没拉过（此时先用客户端近似口径兜底，避免白屏）。
     */
    var participatingItems by remember { mutableStateOf<List<Notification>?>(null) }
    var participatingLoading by remember { mutableStateOf(false) }
    val expandedGroups = remember { mutableStateOf(setOf<String>()) }

    // ── 本地归档（已完成 / 过往 Issue）──
    var archiveVersion by remember { mutableStateOf(0) }
    val archive = remember(archiveVersion) { NotifArchive.entries(context) }
    val doneIds = remember(archive) { archive.filter { it.isDone }.map { it.id }.toSet() }

    // ── 多选状态机 ──
    // 只以「选中集合」为状态本身，进入/退出都由它推导，避免两个状态不同步
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var anchorId by remember { mutableStateOf<String?>(null) }
    var bulkRunning by remember { mutableStateOf(false) }
    // 批次代次：只增不减。批量是「远端逐条写」的长任务，用户完全可能在它跑完前退出多选、
    // 甚至重新选一批再点一次；收尾逻辑靠它分辨「我是不是最新一批」，见 [runBulk]。
    var bulkSeq by remember { mutableStateOf(0) }
    var sheetTarget by remember { mutableStateOf<Notification?>(null) }
    var undo by remember { mutableStateOf<UndoState?>(null) }

    // issue/PR 内容预览：threadId → 最新评论（作者 + 正文），见 NotificationPreviewLoader.kt。
    // 首页阶段的预取已经把首屏预览填进快照，这里只补增量。
    var previews by remember { mutableStateOf(NotifSnapshot.previewMap) }

    val previewRequested = remember { mutableSetOf<String>() }

    // ── 可见数据 ──
    // ⚠️ 从这里到 [categoryCounts] 这一整段必须在 [markReadRemote] / [runBulk] 等函数**之前**：
    //    Kotlin 的局部变量不能被声明在它上面的函数前向引用，而那些函数要读 [rows]
    //    （折叠行代表 N 条，写远端必须打散）。
    // 计算顺序固定为「分类基准 → 类型/时间维度 → 排序」，三段各自独立、可分别解释；
    // 之前是「先按类型/时间过滤再各分类各写一遍」，加一个分类就要复制一遍过滤逻辑。
    val now = System.currentTimeMillis()
    val liveItems = items.filterNot { it.id in doneIds }
    val doneItems = remember(archive) { archive.filter { it.isDone }.map { it.toNotification() } }

    val categoryBase: List<Notification> = when (category) {
        NotifCategory.UNREAD -> liveItems.filter { it.unread }
        NotifCategory.ALL -> liveItems
        // 服务端口径优先；尚未拉到（或请求失败）时退回 reason 近似口径：
        // 近似口径会漏掉「我在该 thread 里评论过但没被 @」的会话，但绝不会漏掉 @我 / 指派给我 / 我发起的。
        NotifCategory.PARTICIPATING -> participatingItems?.filterNot { it.id in doneIds }
            ?: liveItems.filter { isParticipating(it.reason) }
        NotifCategory.DONE -> doneItems
    }
    val maxAge = range.maxAgeMs
    val dimensioned = categoryBase
        .filter { types.isEmpty() || it.subjectType in types }
        .filter { maxAge == null || now - it.updatedAtMs <= maxAge }
    val visible = sortedNotifications(dimensioned, sort)

    /**
     * 渲染用的行：把「同一仓库 + 同一工作流 + 同一分支 + 同一天」的**相邻** CI 通知折成一行
     * （见 [collapseCiRuns]）。
     *
     * ⚠️ 位置有要求：必须在 [markReadRemote] / [runBulk] 这些函数**之前**声明 ——
     * 它们要读 [rows]（折叠行代表 N 条，写远端必须打散），而局部变量不能被前向引用。
     * 值由排序后的可见列表派生 —— 紧跟在 [visible] 之后。
     */
    val rows = collapseCiRuns(visible)

    /**
     * 已展开的折叠行 key（取 [Notification.allIds] 的首个 id）。
     *
     * 默认收起：这一屏的噪点正是「8 条一模一样的行」，默认铺开等于没折。
     * 展开态是**一次浏览动作**，不是用户设置，因此不进 [NotifReadStore] 那类持久层。
     */
    var foldExpanded by remember { mutableStateOf(setOf<String>()) }

    /**
     * 当前**可见**的 id 集合（渲染口径），以及收敛后的选中集合。
     *
     * 为什么要收敛：`selectedIds` 是「用户点过的 id」，而列表会因为换分类 / 类型 / 时间范围 /
     * 下拉刷新 / 「完成」归档而换一批条目。若直接用 `selectedIds`：
     * - 「全选」判断 `selectedIds.size >= visible.size` 会失真（集合里混着看不见的 id）；
     * - 批量操作（已读 / 完成 / 静音 / 复制链接）会作用到**屏幕上根本看不到**的条目。
     * 所以对外一律用 [selected]（= 选中集合 ∩ 可见集合），`selectedIds` 只作为原始记录保留。
     *
     * ⚠️ 这里的口径是**行**：折叠行只贡献一个 id（代表那条），因为用户看到的就是一行。
     * 但「写远端」必须打散成组内全部 id —— 见 [Notification.allIds] 与 [markReadRemote]。
     */
    val visibleIds = rows.map { it.id }.toSet()
    val selected = remember(selectedIds, visibleIds) { effectiveSelection(selectedIds, visibleIds) }
    val allVisibleSelected = isAllVisibleSelected(selectedIds, visibleIds)

    // 渲染顺序（区间 / 刷选依赖它）：与 [NotificationList] 的分组顺序保持一致
    val renderOrder = remember(rows, layout.value) { renderOrderIds(rows, layout.value) }

    // 未读数上报（驱动底部导航 badge）：始终基于「全部」列表，不受当前筛选与折叠影响。
    // 按**行**数而不是条数：折叠行在屏幕上就是一个未读点，报 8 会让底部徽标与眼睛看到的对不上。
    //
    // ⚠️ 语义见 [unreadRowCount]：折叠行只要**组内有任意一条未读**就算一个未读点。
    //    先筛未读再折叠是错的 —— 组里已读的那几条被抽走后组不再成立，于是「8 条折成 1 行」
    //    在这里退回 3 行，屏幕上明明只有一个点、徽标却报 3。
    val unread = unreadRowCount(liveItems)
    LaunchedEffect(unread) { onUnreadCountChange(unread) }

    val allTypes = remember(items.map { it.subjectType }) {
        items.map { it.subjectType }.distinct().filter { it.isNotBlank() }
    }
    val typeCounts = remember(dimensioned, types) { dimensioned.groupingBy { it.subjectType }.eachCount() }

    /** 分类计数：套用当前类型/时间维度，与列表里看到的条数一致 */
    fun countOf(list: List<Notification>): List<Notification> = list
        .filter { types.isEmpty() || it.subjectType in types }
        .filter { maxAge == null || now - it.updatedAtMs <= maxAge }

    val categoryCounts = remember(items, participatingItems, doneItems, types, range) {
        mapOf(
            NotifCategory.UNREAD to countOf(liveItems.filter { it.unread }).size,
            NotifCategory.ALL to countOf(liveItems).size,
            NotifCategory.PARTICIPATING to countOf(
                participatingItems?.filterNot { it.id in doneIds }
                    ?: liveItems.filter { isParticipating(it.reason) },
            ).size,
            NotifCategory.DONE to countOf(doneItems).size,
        )
    }


    val listState = rememberLazyListState()

    /** 远端结果 + 本地已读覆盖（唯一入口，保证「已读」口径只有一份） */
    fun applyRemote(list: List<Notification>): List<Notification> = NotifReadStore.apply(context, list)

    /**
     * 解析 + 合入本地已读：**放到后台线程**。
     *
     * [parseNotifications] 会把整份列表 JSON（首屏 50~200 条）解析成全量对象，而调用点原先
     * 直接写在 `scope.launch { }` 里 —— 那是主线程。于是「进入消息页」那一帧上压着一坨 JSON 解析；
     * 又因为 `TabSwitcher` 每次切 Tab 都会销毁并重建这一页，这笔开销是**每次进都付**，
     * 不是只有首次（首次还额外叠了 DB/预取/类加载）。
     */
    suspend fun parseAndApply(json: String): List<Notification> =
        withContext(Dispatchers.Default) { applyRemote(parseNotifications(json)) }

    /** 远端缓存失效（全部已读 / 单条已读共用） */
    suspend fun invalidateOnRead() {
        cacheManager.delete(PageCache.notificationKey(notifListPath()))
        // 首页「未读通知」计数是另一个键，一并失效，避免回首页看到旧数字
        cacheManager.delete(PageCache.homeKey("unread"))
        NotifSnapshot.invalidate()
    }

    fun load(force: Boolean = false) {
        scope.launch {
            // 下拉刷新时骨架屏让位给顶部指示器，避免「骨架 + 转圈」双加载态叠加
            if (force) refreshing = true else if (items.isEmpty()) loadState = LoadState.Loading
            before = null
            hasMore = true
            val path = notifListPath()
            val key = PageCache.notificationKey(path)
            // 先直出缓存（含过期数据）：返回上一层再进来不再空转一圈转圈。
            // 首帧已有快照时这里通常命中同一份数据，等于零成本。
            val cached = PageCache.cachedFirst(cacheManager, key, PageCache.TYPE_NOTIFICATION, force)
            if (cached != null) {
                items = parseAndApply(cached)
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
                // 首次进入（无缓存、无快照）仍按原语义落到错误态。
                if (cached == null && items.isEmpty()) loadState = LoadState.Failed(context.getString(R.string.error_messages_load_failed))
            } else {
                items = parseAndApply(json)
                before = items.lastOrNull()?.updatedAt
                hasMore = items.size >= 50
                loadState = LoadState.Content
                // 写快照时剔除本地已「完成」的会话：它们已经从收件箱移出，
                // 若照原样写进去，首页徽标会把它们重新算成未读（远端仍是 unread）。
                NotifSnapshot.update(items.filterNot { it.id in doneIds })
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
            val path = notifListPath(before = b)
            val key = PageCache.notificationKey(path)
            // 分页（`before` 游标）也走缓存：同一游标 == 同一页，
            // force = true：分页只在滚动触达时调用一次，这里必须真的能拿到下一页。
            val json = PageCache.refresh(cacheManager, key, PageCache.TYPE_NOTIFICATION, force = true) {
                withContext(Dispatchers.IO) {
                    RustBridge.getJson(host, token, path)
                }
            }
            if (json == null || json.startsWith("ERROR:")) {
                hasMore = false
            } else {
                val newItems = parseAndApply(json)
                if (newItems.isEmpty()) {
                    hasMore = false
                } else {
                    val existing = items.map { it.id }.toSet()
                    val dedup = newItems.filter { it.id !in existing }
                    items = items + dedup
                    before = newItems.last().updatedAt
                    hasMore = newItems.size >= 50 && dedup.isNotEmpty()
                    NotifSnapshot.update(items.filterNot { it.id in doneIds })
                }
            }
            loadingMore = false
        }
    }

    /** 拉取服务端口径的「我参与的」：与首屏同构（先直出缓存再回源），失败时保留近似口径 */
    fun loadParticipating() {
        if (participatingLoading) return
        participatingLoading = true
        scope.launch {
            val path = notifListPath(participating = true)
            val key = PageCache.notificationKey(path)
            PageCache.cachedFirst(cacheManager, key, PageCache.TYPE_NOTIFICATION)?.let { cached ->
                withContext(Dispatchers.Default) {
                    runCatching { parseNotifications(cached) }.getOrNull()?.let { applyRemote(it) }
                }?.let { participatingItems = it }
            }
            val json = PageCache.refresh(cacheManager, key, PageCache.TYPE_NOTIFICATION) {
                withContext(Dispatchers.IO) { RustBridge.getJson(host, token, path) }
            }
            if (json != null && !json.startsWith("ERROR:")) {
                participatingItems = parseAndApply(json)
            }
            participatingLoading = false
        }
    }

    // Tab 保活 ⇒ `LaunchedEffect(Unit)` 只跑一次；挂上「重新可见」的 tick，
    // 切回消息页时重新校验（`load()` 内部是 cachedFirst → refresh：TTL 2 分钟内不联网）。
    val resumeTick = rememberPageResumeTick()
    LaunchedEffect(resumeTick) { load() }

    // 首次切到「参与」时补一次服务端口径的查询（切回来不重复请求）
    LaunchedEffect(category) {
        if (category == NotifCategory.PARTICIPATING && participatingItems == null) loadParticipating()
    }

    // ── issue/PR 内容预览（补齐快照里还没有的那些）──
    // 三条约束（预览是纯增强，绝不能反过来拖累主列表）：
    // ① 只取**未读**的 Issue/PullRequest，且最多前 [NOTIF_PREVIEW_MAX] 条 —— 每条预览都是一次额外
    //    HTTP，全量预取会撞 GitHub 二级速率限制（被限流时整个通知列表一起拉不到）；
    // ② 并发上限 2（在 prefetchPreviews 内部用 Semaphore 控制），并用 TYPE_NOTIFICATION 缓存兜住重复请求；
    // ③ 任何一条失败都静默跳过（loader 内部 runCatching），不 Toast、不打断、不影响列表加载态。
    val previewTargets = items.filter {
        it.unread && it.issueLike && !it.latestCommentUrl.isNullOrBlank() && it.id !in previews
    }.take(NOTIF_PREVIEW_MAX)
    val previewUrlById = previewTargets.associate { it.id to it.latestCommentUrl }
    val previewCandidates = previewTargets.map { it.id }
    // ⚠️ 两个「必须这样写」的点，写错了功能只在第一条上生效：
    // ① key 只取「候选集合」，**不能**包含「已取到的预览」—— 否则第一条结果回流就改变 key、
    //    触发 LaunchedEffect 重启并取消整批预取，剩下 11 条永远发不出去（表现为只出 1 条预览）；
    // ② 真正的请求放进页面级 scope 而不是 LaunchedEffect 自己的协程：候选集合因刷新 / 标记已读
    //    变化时 effect 会被重启，但在跑的这批请求不该跟着被掐断（去重交给 previewRequested）。
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

    fun exitSelection() {
        selectedIds = emptySet()
        anchorId = null
        // 这里**不能**重置 [bulkRunning]：批量是仍在跑的远端长任务，用户中途退出多选
        // 只是收起选择 UI，不等于那批请求停了。旧实现把它置 false 有两个后果 ——
        // ① 退出后能再触发一批，两批并发打远端（每条间隔 [NOTIF_BULK_GAP_MS] 的限流保护失效）；
        // ② 旧批次收尾时调用本函数，会把用户**新选的一批**一起清掉。
        // 批次是否在跑只由 [runBulk] 自己管，收尾是否清选择由 [bulkSeq] + 选择是否被改过共同决定。
    }

    fun enterSelection(n: Notification) {
        panelOpen = false
        sheetTarget = null
        selectedIds = setOf(n.id)
        anchorId = n.id
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun toggleSelection(id: String) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
        anchorId = id
    }

    // ── 已读 / 未读 / 完成（本地覆盖 + 远端写）──

    /** 本地已读：持久化集合 + 列表状态 + 快照（首页徽标同源）+ issue 留档 */
    fun markReadLocal(list: List<Notification>) {
        val ids = list.map { it.id }
        NotifReadStore.add(context, ids)
        items = items.map { if (it.id in ids) it.copy(unread = false) else it }
        NotifSnapshot.mutate { snap -> snap.map { if (it.id in ids) it.copy(unread = false) else it } }
        // issue/PR 读过后留档，供面板「过往 Issue」回看
        NotifArchive.put(
            context,
            NotifArchive.of(list.filter { it.issueLike && it.targetNumber != null }, ArchivedThread.STATE_READ),
        )
        archiveVersion++
    }

    fun markUnreadLocal(list: List<Notification>) {
        val ids = list.map { it.id }
        NotifReadStore.remove(context, ids)
        NotifArchive.remove(context, ids)
        archiveVersion++
        items = items.map { if (it.id in ids) it.copy(unread = true) else it }
        NotifSnapshot.mutate { snap -> snap.map { if (it.id in ids) it.copy(unread = true) else it } }
    }

    fun markDoneLocal(list: List<Notification>) {
        if (list.isEmpty()) return
        val ids = list.map { it.id }
        NotifArchive.put(context, NotifArchive.of(list, ArchivedThread.STATE_DONE))
        NotifReadStore.add(context, ids)
        archiveVersion++
        items = items.map { if (it.id in ids) it.copy(unread = false) else it }
        NotifSnapshot.mutate { snap -> snap.filterNot { it.id in ids } }
    }

    /** 单条已读：乐观更新 → 远端 PATCH → 失败回滚。不跳转。 */
    /**
     * 把「行 id」展开成「要写远端的全部 thread id」——折叠行代表 N 条，写的时候必须逐条打散。
     *
     * 只认折叠行自己的 id：不能靠 `id in n.allIds` 之类反查，那样 `["a","b"]` 碰巧覆盖到
     * 另一折的 id 时会连带把无关条目也标掉。
     */
    fun expandFoldIds(ids: Collection<String>): List<String> =
        rows.filter { it.id in ids }.flatMap { it.allIds }.distinct()

    fun markReadRemote(n: Notification) {
        val ids = n.allIds
        scope.launch {
            val ok = ids.all { RustBridge.markNotificationRead(host, token, it) }
            if (ok) {
                invalidateOnRead()
            } else if (n.unread) {
                // 同一 thread 的其它行不受影响：按 id 精确回滚。折叠行整体回滚 ——
                // 半读的折叠行会让「连续失败 N 次」这句话与视觉状态自相矛盾。
                NotifReadStore.remove(context, ids)
                items = items.map { if (it.id in ids) it.copy(unread = true) else it }
                NotifSnapshot.mutate { snap -> snap.map { if (it.id in ids) it.copy(unread = true) else it } }
                toast(context, context.getString(R.string.error_mark_read_failed))
            }
        }
    }

    /**
     * 工作流通知 → 具体 run id；拿不到返回 null（调用方退回工作流列表）。
     *
     * GitHub **不在通知里给 run id**（CheckSuite 的 `subject.url` 常常就是 null，
     * 给了也只是 check 域的编号，见 [CheckSuiteHint] 的注释），只能拿标题里的
     * 「工作流名 + 分支」去 `GET /repos/{o}/{r}/actions/runs?branch=…` 配对。
     * 一次请求；配对不上就返回 null —— 绝不猜一个编号去开一个无关的 run
     * （1.0.29 修过「把 check id 当 run id」的那次就是这么错的）。
     */
    suspend fun resolveWorkflowRunId(n: Notification, hint: CheckSuiteHint): Long? {
        val branch = runCatching { URLEncoder.encode(hint.branch, "UTF-8") }.getOrNull() ?: return null
        val path = "/repos/${n.owner}/${n.repo}/actions/runs?branch=$branch&per_page=50"
        val json = RustBridge.getJson(host, token, path) ?: return null
        return pickRunId(parseRunCandidates(json), hint, n.updatedAtMs)
    }

    /** 点击通知：本地标记已读 + 跳转；工作流通知要先解析出具体这次 run。 */
    fun onNotifClick(n: Notification) {
        markReadLocal(listOf(n))
        // 已读的条目再点不该再发一次写请求：GitHub 对同秒内的写请求有二级限流，
        // 而「点开一条早已读过的消息」是很常见的动作。与 DioHub 一致：只在未读时才标记。
        if (n.unread) markReadRemote(n)

        val target = resolveTarget(n)
        if (target !is NotifTarget.Workflows) {
            onOpenTarget(target)
            return
        }
        // 工作流通知：先解析出具体这次 run 再跳（一次 /actions/runs 请求）。
        // 解析不出来就按原样落到工作流列表 —— 并说明原因，而不是让用户以为自己点错了。
        scope.launch {
            val hint = parseCheckSuiteTitle(n.title)
            val runId = hint?.let { resolveWorkflowRunId(n, it) }
            if (runId != null) {
                onOpenTarget(NotifTarget.Run(n.owner, n.repo, runId))
            } else {
                if (hint != null) toast(context, context.getString(R.string.note_run_not_located))
                onOpenTarget(target)
            }
        }
    }

    /** 归档条目回填为列表行（恢复未读用） */
    fun unsendToInbox(entry: ArchivedThread) {
        // 「恢复未读」的撤销：必须把 entry **原样**放回归档（read 就回 read）。
        // 硬编码 done 会把只读过的条目从「过往 Issue」升级进「已完成」——
        // 状态被永久改写，而且这不是任何用户操作的结果。
        NotifArchive.put(context, listOf(entry))
        NotifReadStore.add(context, listOf(entry.id))
        archiveVersion++
        items = items.map { if (it.id == entry.id) it.copy(unread = false) else it }
        NotifSnapshot.mutate { snap -> snap.filterNot { it.id == entry.id } }
    }

    /** 恢复未读：从归档取回、插回列表顶部（远端没有「标记未读」接口，本地覆盖层负责） */
    fun restoreFromArchive(entry: ArchivedThread) {
        NotifArchive.remove(context, listOf(entry.id))
        NotifReadStore.remove(context, listOf(entry.id))
        archiveVersion++
        val restored = entry.toNotification().copy(unread = true)
        items = if (items.none { it.id == entry.id }) listOf(restored) + items
        else items.map { if (it.id == entry.id) it.copy(unread = true) else it }
        NotifSnapshot.mutate { snap -> listOf(restored) + snap.filterNot { it.id == entry.id } }
        category = NotifCategory.UNREAD
        undo = UndoState(context.getString(R.string.toast_restored_unread, entry.title)) { unsendToInbox(entry) }
    }

    /**
     * 锚点 → 目标：按当前渲染顺序整段选中。
     * 长按（含按住拖动）刷选与「长按另一条」共用这一个原语。
     */
    fun selectRangeTo(id: String, order: List<String>) {
        val from = anchorId ?: id
        val i = order.indexOf(from)
        val j = order.indexOf(id)
        if (i < 0 || j < 0) {
            selectedIds = selectedIds + id
            return
        }
        selectedIds = selectedIds + order.subList(minOf(i, j), maxOf(i, j) + 1)
    }

    /** 整组选中 / 取消（多选态点分组头）：全选中则整组取消，否则补齐 */
    fun toggleGroupSelection(ids: List<String>) {
        if (ids.isEmpty()) return
        selectedIds = if (ids.all { it in selectedIds }) selectedIds - ids.toSet() else selectedIds + ids.toSet()
    }

    /**
     * 批量操作失败 / 撤销时的**本地回滚**。
     *
     * 只回滚本次操作涉及的那些 id，**不整体替换 `items` / 快照**：
     * 批量操作要串行打远端（每条之间还有 [NOTIF_BULK_GAP_MS] 间隔），这段时间里用户完全可能
     * 下拉刷新或翻页成功 —— 整体替换会把刚落地的**新数据一起扔掉**，
     * 回首页看到的还是被回滚过的旧未读数。
     *
     * 远端不可逆的部分（GitHub 没有「标记未读」接口）不在承诺范围内，见 [runBulk] 的注释。
     */
    fun rollbackLocal(
        targets: List<Notification>,
        unreadBefore: Map<String, Boolean>,
        archiveBefore: List<ArchivedThread>,
        readBefore: Set<String>,
    ) {
        if (targets.isEmpty()) return
        val ids = targets.map { it.id }.toSet()

        // ① 列表：只改这些 id 的未读标志
        items = items.map { n -> unreadBefore[n.id]?.let { unread -> n.copy(unread = unread) } ?: n }

        // ② 快照：同样只动这些 id；被 markDoneLocal 移出快照的条目补回来（否则徽标会少算）
        NotifSnapshot.mutate { snapshot ->
            val known = snapshot.map { n -> unreadBefore[n.id]?.let { unread -> n.copy(unread = unread) } ?: n }
            val missing = targets.filter { t -> snapshot.none { it.id == t.id } }
                .map { t -> t.copy(unread = unreadBefore[t.id] ?: false) }
            known + missing
        }

        // ③ 已读集合：按 id 恢复成员关系（而不是整表替换 —— 期间的其它已读不该被抹掉）
        val wasRead = targets.filter { it.id in readBefore }.map { it.id }
        val wasUnread = targets.filterNot { it.id in readBefore }.map { it.id }
        if (wasRead.isNotEmpty()) NotifReadStore.add(context, wasRead)
        if (wasUnread.isNotEmpty()) NotifReadStore.remove(context, wasUnread)

        // ④ 归档：以当前表为底，把这些 id 强制写回操作前的状态（put 的「done 不可降级」会挡住撤销）
        val beforeById = archiveBefore.associateBy { it.id }
        NotifArchive.replace(
            context,
            NotifArchive.entries(context).filterNot { it.id in ids } + targets.mapNotNull { beforeById[it.id] },
        )
        archiveVersion++
    }

    /**
     * 单条完成：先本地归档（乐观），再远端 `DELETE /notifications/threads/{id}`，失败按 id 回滚。
     *
     * 为什么必须打远端：完成在本地只是「加进 [NotifArchive] + 从快照移出」，服务端完全不知情。
     * 不打远端的话，下拉刷新（或下次进页面）时这条又会原样回来 —— 用户会认为「完成没生效」。
     * 这里曾经只在 [runBulk] 里调用过 [RustBridge.markNotificationDone]：
     * 长按面板的单条「标记完成」是纯本地的空操作（且因为长按接线错，那个按钮当时还点不到）。
     */
    fun markDoneRemote(n: Notification) {
        val ids = n.allIds
        val unreadBefore = n.allIds.associateWith { n.unread }
        val archiveBefore = NotifArchive.entries(context)
        val readBefore = NotifReadStore.ids(context)
        markDoneLocal(listOf(n))
        scope.launch {
            // 折叠行整体写：远端逐条 DELETE（GitHub 的 done 是 per-thread 的）
            val ok = ids.all { RustBridge.markNotificationDone(host, token, it) }
            if (ok) {
                invalidateOnRead()
            } else {
                rollbackLocal(listOf(n), unreadBefore, archiveBefore, readBefore)
                toast(context, context.getString(R.string.error_mark_done_failed))
            }
        }
    }

    /**
     * 批量操作：先本地乐观更新，再**按顺序**逐条调用远端（每条之间 [NOTIF_BULK_GAP_MS] 间隔，不并发，
     * 避免触发二级速率限制）；失败逐条回滚并在结束时 Toast 汇总；全部结束退出多选模式。
     *
     * 撤销：本地状态按 id 回到操作前（[rollbackLocal]）。远端不可逆 —— GitHub 没有「标记未读」接口，
     * 已读 / 已 done 的线程无法在服务端回滚，因此这里只承诺本地可见状态可撤销。
     */
    fun runBulk(op: BulkOp, targetIds: Set<String>) {
        if (bulkRunning || targetIds.isEmpty()) return
        // 「行 id」→ 实际要写的**条目**：折叠行代表 N 条，全部都要写。
        // 不展开的话，把一折标为已读后有 7 条留在服务端未读，刷新回来又组成一个新的折叠行
        // （表现为「标了已读没生效」）。
        val targetIdList = expandFoldIds(targetIds)
        val targets = items.filter { it.id in targetIdList }
        if (targets.isEmpty()) return

        val unreadBefore = targets.associate { it.id to it.unread }
        val archiveBefore = NotifArchive.entries(context)
        val readBefore = NotifReadStore.ids(context)
        val seq = ++bulkSeq

        bulkRunning = true
        // ① 本地乐观更新：
        // 列表按 id 逐条改未读标志 **且不以「当前是否未读」为条件** ——
        // 折叠行在界面上是一个已读点，但组内可能还有几条已经在别处被标过，这里统一改到位；
        // 「归档」走 thread 粒度，只喂**代表行**（组内 8 条是 8 次运行，留 8 条归档记录没有意义）。
        val reps = rows.filter { it.id in targetIds }
        when (op) {
            BulkOp.READ -> {
                NotifReadStore.add(context, targets.map { it.id })
                items = items.map { if (it.id in targetIdList) it.copy(unread = false) else it }
                markReadLocal(reps)
            }
            BulkOp.DONE -> markDoneLocal(reps)
            BulkOp.MUTE -> Unit // 静音只动远端，本地不隐藏（用户可能还想看）
        }

        scope.launch {
            // ② 远端顺序执行：串行 for + delay，单条失败不影响后续
            val failed = mutableListOf<Notification>()
            targets.forEachIndexed { index, n ->
                val ok = when (op) {
                    BulkOp.READ -> RustBridge.markNotificationRead(host, token, n.id)
                    BulkOp.DONE -> RustBridge.markNotificationDone(host, token, n.id)
                    BulkOp.MUTE -> RustBridge.unsubscribeThread(host, token, n.id)
                }
                if (!ok) failed += n
                if (index != targets.lastIndex) delay(NOTIF_BULK_GAP_MS)
            }
            // ③ 终态：**只回滚失败的那些**（规则见 [bulkRollbackTargets]，纯函数、有单测）；
            // 折叠行按**整行**回滚（[rollingBackRows]）—— 一折里 8 条只失败 2 条时，
            // 把折叠行显示成「已读」而其中两条还挂着未读，行状态与「连续失败 N 次」的表述自相矛盾。
            val failedIds = failed.map { it.id }.toSet()
            // 要回滚的**行**：折叠行只要有一条失败就整行回滚（判据见 [rollingBackRows]）
            val rowsDone = rollingBackRows(rows, if (op == BulkOp.MUTE) emptySet() else failedIds)
            // 要回滚的**条目**：折叠行整行回滚，普通行只有自己
            val rollbackItems = targets.filter { n -> rowsDone.any { n.id in it.allIds } }
            if (rollbackItems.isNotEmpty()) {
                rollbackLocal(rollbackItems, unreadBefore, archiveBefore, readBefore)
            }
            val what = when (op) {
                BulkOp.READ -> context.getString(R.string.action_mark_read)
                BulkOp.DONE -> context.getString(R.string.action_mark_done)
                BulkOp.MUTE -> context.getString(R.string.action_mute)
            }
            // 计数一律按**行**（折叠行算一条）：界面上一折就是一行，
            // 报「成功 8 条」而屏幕上只动了一行，会让人以为误伤了别的消息
            if (failed.isNotEmpty()) {
                toast(context, context.getString(R.string.toast_batch_result, what, reps.size - rowsDone.size, rowsDone.size))
            } else {
                invalidateOnRead()
                // 静音在本地没有任何可见状态可回退（远端也没有 subscribe 接口），
                // 因此不给撤销 —— 给一个按下去什么都不变的「撤销」比不给更糟。
                if (op != BulkOp.MUTE) {
                    undo = UndoState(context.getString(R.string.toast_batch_summary, reps.size, what)) {
                        rollbackLocal(targets, unreadBefore, archiveBefore, readBefore)
                    }
                }
            }
            bulkRunning = false

            // ④ 收尾：只在「本批仍是最新一批」且「用户没动过选择」时才收拾多选态。
            // 否则旧批次收尾时调用 exitSelection() 会把用户中途重新选的一批一起清掉。
            if (seq != bulkSeq || selectedIds != targetIds) return@launch
            if (failed.isEmpty()) {
                exitSelection()
            } else {
                // 失败的保留选中：用户可以直接再点一次重试，不用重新一条条勾。
                // 值取**行 id**：折叠行失败时选中的是那一行，而不是组内某一条看不见的 id。
                selectedIds = rowsDone.map { it.id }.toSet()
                anchorId = selectedIds.firstOrNull()
            }
        }
    }



    /** 生效中的筛选维度数（FAB 徽标） */
    val activeDims = (if (category != NotifCategory.UNREAD) 1 else 0) +
        (if (types.isNotEmpty()) 1 else 0) +
        (if (layout.value != NotifLayout.FLAT) 1 else 0) +
        (if (range != NotifRange.ALL) 1 else 0) +
        (if (sort != NotifSort.NEWEST) 1 else 0)

    // 多选态只看「可见的选中项」：筛选把最后一条也滤掉时自动退出（不会留下一个空的多选条）
    val inSelection = selected.isNotEmpty()

    // 多选态下返回键 = 退出多选（而不是退出页面）：多选时不看内容，返回键留给「取消选择」更符合预期。
    PageBackHandler(enabled = inSelection || panelOpen || sheetTarget != null) {
        when {
            sheetTarget != null -> sheetTarget = null
            panelOpen -> panelOpen = false
            else -> exitSelection()
        }
    }

    // 撤销条的 5 秒倒计时
    LaunchedEffect(undo) {
        if (undo != null) {
            delay(UNDO_WINDOW_MS)
            undo = null
        }
    }

    Box(Modifier.fillMaxSize().background(Primer.BackgroundPrimary)) {
        Column(Modifier.fillMaxSize()) {
            // ── 顶部：普通态 / 多选态 ──
            if (inSelection) {
                NotifSelectionTopBar(
                    selectedCount = selected.size,
                    visibleCount = visibleIds.size,
                    allSelected = allVisibleSelected,
                    onExit = { exitSelection() },
                    onToggleAll = {
                        // 「全选 / 全不选」的判定与产出都在 [toggledAllSelection]（纯函数、有单测）：
                        // 用 size 比较判断「是否已全选」在选中集合混进过看不见的 id 时会误判。
                        selectedIds = toggledAllSelection(selectedIds, visibleIds)
                        anchorId = if (allVisibleSelected) null else visibleIds.firstOrNull() ?: anchorId
                    },
                )
            } else {
                TopBar(
                    unread = unread,
                    onRefresh = { load(force = true) },
                    onMarkAllRead = {
                        // 走 mark-all-read 端点（服务端一次清空），因此这里只要能**报准数**即可；
                        // 但本地已读集合必须按 id 逐条写全（折叠行代表组内 N 条），
                        // 否则刷新后剩下那几条会重新组一折冒出来。
                        // markReadLocal 只覆盖代表行，组内其余 id 要在这里补齐。
                        val targets = rows.filter { it.unread }
                        val ids = targets.flatMap { it.allIds }
                        val extraIds = ids.filterNot { id -> targets.any { it.id == id } }
                        // 这些条目操作前都是未读；回滚要按 id 精确恢复，不能整表替换
                        val unreadBefore = ids.associateWith { true }
                        val readBefore = NotifReadStore.ids(context)
                        val archiveBefore = NotifArchive.entries(context)
                        NotifReadStore.add(context, extraIds)
                        markReadLocal(targets)
                        scope.launch {
                            val ok = RustBridge.markAllNotificationsRead(host, token)
                            if (ok) {
                                invalidateOnRead()
                                undo = UndoState(context.getString(R.string.toast_marked_read_count, targets.size)) {
                                    rollbackLocal(targets, unreadBefore, archiveBefore, readBefore)
                                }
                            } else {
                                rollbackLocal(targets, unreadBefore, archiveBefore, readBefore)
                                toast(context, context.getString(R.string.error_mark_all_read_failed))
                            }
                        }
                    },
                )
            }

            // ── 预加载提示（③ 的可见证据）：仅在首帧确实吃了快照时出现，4 秒后自动收起 ──
            if (fromSnapshot) PrefetchHint()

            // ── 系统通知权限横幅：只在真的收不到通知、且用户没点过「不再提示」时出现 ──
            if (!systemNotification.granted && !notifBannerDismissed && !inSelection) {
                NotificationPermissionBanner(
                    state = systemNotification,
                    onDismiss = {
                        notifBannerDismissed = true
                        dismissNotificationBanner(context)
                    },
                )
            }

            // 整页下拉刷新：只包住列表。
            // ⚠️ 必须用 weight(1f)：在 Column 里用 fillMaxSize() 会让它去占满「父容器整高」，
            //    与上面的 TopBar / 筛选条叠加后总高超出父容器，而 Compose 默认不裁剪子项，
            //    超出部分会画到容器边界之外（压住底部导航栏），表现为内容下沉 / UI 挤占。
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = { load(force = true) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                NotificationList(
                    state = loadState,
                    rows = rows,
                    category = category,
                    layout = layout.value,
                    expandedGroups = expandedGroups.value,
                    onToggleGroup = { key ->
                        expandedGroups.value = if (key in expandedGroups.value) {
                            expandedGroups.value - key
                        } else {
                            expandedGroups.value + key
                        }
                    },
                    typeFiltered = types.isNotEmpty() || range != NotifRange.ALL,
                    selectionEnabled = inSelection,
                    forceExpandGroups = inSelection,
                    previews = previews,
                    selectedIds = selected,
                    listState = listState,
                    foldExpanded = foldExpanded,
                    onToggleFold = { key ->
                        foldExpanded = if (key in foldExpanded) foldExpanded - key else foldExpanded + key
                    },
                    onClick = { onNotifClick(it) },
                    // 长按 → 快捷动作面板（**不是**直接进多选）：想「只把这一条标成已读」时，
                    // 先长按进多选再点「已读」多一步、且列表结构已经变了。多选是面板里的一个显式选项。
                    // 这里曾经是 `enterSelection(it)`，于是面板的非多选分支从引入起就没被显示过（死代码），
                    // 长按退化成「进多选」—— 与文档和 design/messages-redesign 原型描述的状态机不一致。
                    onLongClick = { sheetTarget = it },
                    onToggleSelection = { id -> toggleSelection(id) },
                    onToggleGroupSelection = { toggleGroupSelection(it) },
                    onSwipeRead = { n -> markReadLocal(listOf(n)); markReadRemote(n) },
                    hasMore = hasMore && category != NotifCategory.DONE,
                    loadingMore = loadingMore,
                    onLoadMore = { loadMore() },
                    onRetry = { load() },
                    onRangeSelect = { id -> selectRangeTo(id, renderOrder) },
                    renderOrder = renderOrder,
                )
            }
        }

        // ── 遮罩 + 面板（右下角，锚在 FAB 正上方）──
        NotifScrim(visible = panelOpen) { panelOpen = false }
        NotifPanelAnimated(
            visible = panelOpen,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = 78.dp)
                .width(panelWidthFor(LocalConfiguration.current.screenWidthDp)),
        ) {
            NotifPanel(
                category = category,
                categoryCounts = categoryCounts,
                onCategory = { category = it },
                types = allTypes,
                typeCounts = typeCounts,
                selectedTypes = types,
                onToggleType = { t -> types = if (t in types) types - t else types + t },
                onClearTypes = { types = emptySet() },
                layout = layout.value,
                onLayout = {
                    layout.value = it
                    writeNotifLayout(context, it)
                },
                range = range,
                onRange = { range = it },
                sort = sort,
                onSort = { sort = it },
                history = archive
                    .filter { it.isIssueLike }
                    .filter { h ->
                        historyQuery.isBlank() ||
                            (h.title + h.repoFullName + " #" + (h.number ?: 0))
                                .contains(historyQuery, ignoreCase = true)
                    },
                historyQuery = historyQuery,
                onHistoryQuery = { historyQuery = it },
                onOpenHistory = { h -> onOpenTarget(issueTargetOf(h)) },
                onRestoreHistory = { h -> restoreFromArchive(h) },
                onReset = {
                    category = NotifCategory.UNREAD
                    types = emptySet()
                    layout.value = NotifLayout.FLAT
                    writeNotifLayout(context, NotifLayout.FLAT)
                    range = NotifRange.ALL
                    sort = NotifSort.NEWEST
                    historyQuery = ""
                    expandedGroups.value = emptySet()
                },
                onDone = { panelOpen = false },
            )
        }

        // ── FAB ──
        NotifFilterFab(
            activeDims = activeDims,
            open = panelOpen,
            onClick = { panelOpen = !panelOpen },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 14.dp, bottom = 18.dp),
        )

        // ── 多选底部操作条（进入/退出多选时列表原地不动）──
        AnimatedVisibility(
            visible = inSelection,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            NotifSelectionBar(
                selectedCount = selected.size,
                visibleCount = visibleIds.size,
                running = bulkRunning,
                // 批量动作只作用于「可见的选中项」：不会误伤被筛选掉 / 已归档的条目
                onRead = { runBulk(BulkOp.READ, selected) },
                onDone = { runBulk(BulkOp.DONE, selected) },
                onMute = { runBulk(BulkOp.MUTE, selected) },
                onMore = { sheetTarget = items.firstOrNull { it.id in selected } },
                onExit = { exitSelection() },
            )
        }

        // ── 撤销条 ──
        UndoBar(
            state = undo,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 12.dp, end = 12.dp, bottom = if (inSelection) 66.dp else 12.dp),
            onDismiss = { undo = null },
        )
    }

    // ── 长按快捷动作面板 ──
    sheetTarget?.let { target ->
        val bulkMode = inSelection
        NotifActionSheet(
            target = target,
            bulkMode = bulkMode,
            selectionCount = selected.size,
            onDismiss = { sheetTarget = null },
            onMarkRead = { markReadLocal(listOf(target)); markReadRemote(target); sheetTarget = null },
            onMarkUnread = {
                // 折叠行整行恢复未读：组内 N 条都要动，只动代表那条会让折叠行「半读」
                if (bulkMode) {
                    val ids = expandFoldIds(selected).toSet()
                    markUnreadLocal(items.filter { it.id in ids })
                } else {
                    markUnreadLocal(items.filter { it.id in target.allIds.toSet() })
                }
                sheetTarget = null
            },
            onMarkDone = {
                if (bulkMode) runBulk(BulkOp.DONE, selected) else markDoneRemote(target)
                sheetTarget = null
            },
            onMute = {
                // 折叠行代表 N 条，逐条静音 —— 只静音代表那条，其余几条下次仍会推送
                val ids = expandFoldIds(if (bulkMode) selected else setOf(target.id))
                scope.launch {
                    val ok = ids.all { RustBridge.unsubscribeThread(host, token, it) }
                    if (ok) toast(context, context.getString(R.string.toast_conversation_muted)) else toast(context, context.getString(R.string.error_mute_failed))
                }
                sheetTarget = null
            },
            onCopyLink = {
                if (bulkMode) copyLinks(context, items.filter { it.id in expandFoldIds(selected).toSet() })
                else copyLinks(context, items.filter { it.id in target.allIds.toSet() })
                sheetTarget = null
            },
            onOpenBrowser = { openInBrowser(context, target); sheetTarget = null },
            onShare = { shareThread(context, target); sheetTarget = null },
            onMultiSelect = { enterSelection(target) },
            onExitSelection = { exitSelection(); sheetTarget = null },
        )
    }
}

// ───────────────────────────────── 顶部栏 ─────────────────────────────────

@Composable
private fun TopBar(unread: Int, onRefresh: () -> Unit, onMarkAllRead: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.nav_messages), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary)
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
            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh_messages), tint = Primer.IconPrimary)
        }
        TextButton(onClick = onMarkAllRead, enabled = unread > 0) {
            Icon(
                Icons.Filled.DoneAll,
                contentDescription = null,
                tint = if (unread > 0) Primer.Blue500 else Primer.Gray300,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.state_read), color = if (unread > 0) Primer.Blue500 else Primer.Gray300)
        }
    }
}

/** 预加载提示条：首帧由首页阶段的快照直出时短暂出现，4 秒后自动收起。 */
@Composable
private fun PrefetchHint() {
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(4000)
        visible = false
    }
    AnimatedVisibility(visible = visible, enter = revealEnter(), exit = revealExit()) {
        Row(
            Modifier
                .padding(start = 12.dp, bottom = 8.dp)
                .clip(CircleShape)
                .background(Primer.Blue500.copy(alpha = 0.08f))
                .border(1.dp, Primer.Blue500.copy(alpha = 0.18f), CircleShape)
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(Primer.Blue500))
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.note_prefetched_first_frame),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Primer.Blue600,
            )
        }
    }
}

// ───────────────────────────────── 撤销条 ─────────────────────────────────

/** 可撤销操作：本地状态整体回滚（远端不可逆，见 [NotificationScreen] 的 runBulk 注释）。 */
private data class UndoState(val message: String, val restore: () -> Unit)

private const val UNDO_WINDOW_MS = 5000L

@Composable
private fun UndoBar(state: UndoState?, modifier: Modifier = Modifier, onDismiss: () -> Unit) {
    AnimatedVisibility(
        visible = state != null,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
        modifier = modifier,
    ) {
        Surface(
            color = Primer.Gray900,
            shape = RoundedCornerShape(10.dp),
            shadowElevation = 8.dp,
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    state?.message.orEmpty(),
                    fontSize = 13.sp,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.action_undo),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Primer.Blue400,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            state?.restore?.invoke()
                            onDismiss()
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

// ───────────────────────────────── 列表 ─────────────────────────────────

@Composable
private fun NotificationList(
    state: LoadState,
    rows: List<Notification>,
    category: NotifCategory,
    layout: NotifLayout,
    expandedGroups: Set<String>,
    onToggleGroup: (String) -> Unit,
    typeFiltered: Boolean,
    selectionEnabled: Boolean,
    forceExpandGroups: Boolean,
    previews: Map<String, NotificationPreview>,
    selectedIds: Set<String>,
    listState: LazyListState,
    foldExpanded: Set<String>,
    onToggleFold: (String) -> Unit,
    onClick: (Notification) -> Unit,
    onLongClick: (Notification) -> Unit,
    onToggleSelection: (String) -> Unit,
    onToggleGroupSelection: (List<String>) -> Unit,
    onSwipeRead: (Notification) -> Unit,
    hasMore: Boolean,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onRangeSelect: (String) -> Unit,
    renderOrder: List<String>,
) {
    // 多选态的「长按 → 区间 / 按住划过刷选」放在列表容器上统一处理，而不是每行各写一份 ——
    // 手指跨行时只有容器能看到完整位移。普通态直接 return（不挂手势检测器），
    // 行自己的 combinedClickable 负责长按菜单。
    //
    // 分组布局下 LazyColumn 的每个 item 是**一整个分组容器**（key = `repo:x` / `thread:x` / `l2repo:x`），
    // 通知行嵌在容器内部：指针只能映射到容器 key，因此必须把「容器 key → 它包含的 id」查出来，
    // 否则刷选在三种分组布局下永远匹配不上（划过即选中失效）。
    val keyToIds = remember(rows, layout) { renderKeyToIds(rows, layout) }
    val haptics = LocalHapticFeedback.current
    val dragSelectModifier = Modifier.pointerInput(selectionEnabled, renderOrder, keyToIds) {
        if (!selectionEnabled) return@pointerInput
        // 一行可能对应多条（分组）：区间端点取这一行的**首尾 id**，
        // 两次合并即可把整行纳入区间（同一分组在渲染序里必然是连续的一段）。
        fun selectRowAt(y: Float) {
            val key = idAtOffset(y, listState.layoutInfo) ?: return
            val ids = keyToIds[key] ?: return
            ids.firstOrNull()?.let(onRangeSelect)
            ids.lastOrNull()?.let(onRangeSelect)
        }
        detectDragGesturesAfterLongPress(
            onDragStart = { offset ->
                // 长按进入「区间 / 刷选」时补一次触觉确认：多选态下每行不再有自己的长按菜单，
                // 没有反馈就分不清「长按没生效」还是「这一行本来就没反应」
                // （Compose 手势文档建议在长按 / 拖动开始时给 haptic 反馈）。
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                selectRowAt(offset.y)
            },
            onDrag = { change, _ -> selectRowAt(change.position.y) },
        )
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .then(dragSelectModifier)
            // 多选态把整个列表标记为「可选择集合」：读屏会把每个 selectable 行播报成
            // 「第 x 项，共 y 项」，否则每行都是孤立控件，用户不知道自己在列表里的位置。
            .then(if (selectionEnabled) Modifier.selectableGroup() else Modifier),
        // 列表用**行 + 分隔线**，不给每行套卡片盒：浅色主题下卡片底与页面底都是纯白，
        // 卡片其实只靠那圈灰边撑着，一屏十几个盒子就是「空格子」观感的来源。
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        when (state) {
            LoadState.Loading -> {
                // 一整格装 6 行骨架，而不是 `items(6) { NotificationSkeleton() }`：
                // ProvideShimmer 只在这一格里存在 —— 一条动画驱动 6 行。
                // 旧写法是每行各自 `rememberInfiniteTransition`（6 条动画 + 每帧 6 次重组）。
                item(key = "__skeleton__") {
                    ProvideShimmer {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            repeat(6) { NotificationSkeleton() }
                        }
                    }
                }
            }
            is LoadState.Failed -> {
                item(key = "__error__") { ErrorState(state.message, onRetry) }
            }
            LoadState.Content -> {
                if (rows.isEmpty()) {
                    item(key = "__empty__") { EmptyState(category, typeFiltered) }
                } else {
                    // 末行 id：分隔线只在行与行之间画，列表底不该多一条线
                    val lastRowId = rows.lastOrNull()?.id
                    val rowContent: @Composable (Notification) -> Unit = { n ->
                        NotificationRow(
                            n = n,
                            preview = previews[n.id],
                            selectionEnabled = selectionEnabled,
                            selected = n.id in selectedIds,
                            foldOpen = n.id in foldExpanded,
                            onToggleFold = { onToggleFold(n.id) },
                            onClick = { onClick(n) },
                            // 多选态下长按交给容器做区间/刷选，行不再触发「进入多选」
                            onLongClick = if (selectionEnabled) null else ({ onLongClick(n) }),
                            onToggleSelection = { onToggleSelection(n.id) },
                            onSwipeRead = { onSwipeRead(n) },
                        )
                    }
                    when (layout) {
                        NotifLayout.FLAT -> {
                            items(rows, key = { it.id }) { n ->
                                // 增删动画：拉到新通知时滑入、已读移除时收起（列表带 key，位置动画才有意义）
                                Box(Modifier.animateItem()) {
                                    Column {
                                        rowContent(n)
                                        // 末行不画分隔线（下面就是列表底）
                                        if (n.id != lastRowId) RowDivider()
                                    }
                                }
                            }
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
                                        selectionMode = forceExpandGroups,
                                        selectedState = groupSelectedState(list.map { it.id }, selectedIds),
                                        onToggleSelection = { onToggleGroupSelection(list.map { it.id }) },
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
                                        selectionMode = forceExpandGroups,
                                        selectedState = groupSelectedState(list.map { it.id }, selectedIds),
                                        onToggleSelection = { onToggleGroupSelection(list.map { it.id }) },
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
                                        selectionMode = forceExpandGroups,
                                        selectedState = groupSelectedState(list.map { it.id }, selectedIds),
                                        onToggleSelection = { onToggleGroupSelection(list.map { it.id }) },
                                        onToggle = { onToggleGroup(repoName) },
                                    ) {
                                        for ((tKey, threadList) in list.groupBy { it.url.ifBlank { it.id } }) {
                                            if (threadList.size > 1) {
                                                CollapsibleGroup(
                                                    title = threadList.first().title,
                                                    unreadCount = threadList.count { it.unread },
                                                    expanded = forceExpandGroups || tKey in expandedGroups,
                                                    selectionMode = forceExpandGroups,
                                                    selectedState = groupSelectedState(threadList.map { it.id }, selectedIds),
                                                    onToggleSelection = { onToggleGroupSelection(threadList.map { it.id }) },
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
                                    Text(stringResource(R.string.action_load_more), fontSize = 12.sp, color = Primer.TextTertiary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 「全部」列表的未读**行**数（驱动底部导航的徽标；纯函数，见 `NotificationCiFoldTest`）。
 *
 * 为什么按行而不按条：折叠行在屏幕上就是一个未读点，报 8 会让徽标与眼睛看到的对不上。
 *
 * ⚠️ 顺序要求：必须**先折叠再筛未读**。反过来先把未读挑出来的话，组里已读的那几条被抽走、
 * 折叠组不再成立 —— 「8 条折成 1 行」在这里会退回 3 行，徽标报 3 而屏幕上只有一个点。
 *
 * 输入需要已按时间倒序（与其他折叠调用同一前提）；顺序不对只会让折叠被切成几段、
 * 结果偏大，不会漏报未读。
 */
internal fun unreadRowCount(list: List<Notification>): Int {
    val unreadIds = list.filterTo(mutableSetOf()) { it.unread }.mapTo(mutableSetOf()) { it.id }
    if (unreadIds.isEmpty()) return 0
    return collapseCiRuns(list).count { row -> row.allIds.any { it in unreadIds } }
}

/**
 * 把手指位置映射到当前可见**渲染行**的 key（刷选用）。
 *
 * 分组布局下渲染行就是分组容器（`repo:x` / `thread:x` / `l2repo:x`），
 * 需要再用 [renderKeyToIds] 换成它包含的通知 id。落在 footer / 骨架屏上返回其 key（查不到映射即忽略）。
 */
private fun idAtOffset(y: Float, info: LazyListLayoutInfo): String? {
    val hit = info.visibleItemsInfo.firstOrNull { y >= it.offset && y < it.offset + it.size } ?: return null
    return hit.key as? String
}

/**
 * 多选集合的纯函数（`internal` 是为了可单测，见 `NotificationSelectionTest`）。
 *
 * 抽出来的理由：选中集合与可见集合会**各自变化** —— 换分类 / 类型 / 时间范围、下拉刷新、
 * 「完成」归档都会换掉一批可见条目。两者不收敛时，出问题的地方全在边界上：
 * 「全选」判断用 `size >=` 会失真、批量操作会打到看不见的条目、分组头半选态算错。
 * 这些都不该靠真机点出来。
 */

/** 有效选中集合 = 用户点过的 ∩ 当前可见（看不见的 id 一律不参与计数与批量操作）。 */
internal fun effectiveSelection(selectedIds: Set<String>, visibleIds: Set<String>): Set<String> =
    if (selectedIds.isEmpty() || visibleIds.isEmpty()) emptySet() else selectedIds intersect visibleIds

/** 是否「已全选」：可见集合非空，且其中每一条都在选中集合里。 */
internal fun isAllVisibleSelected(selectedIds: Set<String>, visibleIds: Set<String>): Boolean =
    visibleIds.isNotEmpty() && visibleIds.all { it in selectedIds }

/** 「全选 / 全不选」的下一状态：已全选 → 清空，否则 → 选中当前可见的全部。 */
internal fun toggledAllSelection(selectedIds: Set<String>, visibleIds: Set<String>): Set<String> =
    if (isAllVisibleSelected(selectedIds, visibleIds)) emptySet() else visibleIds

/** 分组头的三种选中态（半选必须有独立信号，否则用户无法判断「点了会不会全取消」）。 */
internal fun groupSelectedState(ids: List<String>, selected: Set<String>): GroupSelectState {
    val on = ids.count { it in selected }
    return when {
        on == 0 -> GroupSelectState.NONE
        on == ids.size -> GroupSelectState.ALL
        else -> GroupSelectState.MIXED
    }
}

internal enum class GroupSelectState { NONE, ALL, MIXED }

/** 行分隔线：行形态下用它代替「每张卡一圈边框」，只做分组提示，刻意画得极轻。 */
@Composable
private fun RowDivider() {
    // 在组合里取色：drawBehind 的 lambda 不是 @Composable
    val color = Primer.Gray150
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .drawBehind { drawRect(color = color) },
    )
}

/**
 * 单条消息行。
 *
 * 形态是**行 + 分隔线**，不是卡片盒：浅色主题下卡片底与页面底都是纯白（`canvas = canvasSubtle`），
 * 卡片原本只靠那圈灰边撑着，一屏十几个盒子就是「空格子」观感的来源。
 *
 * 识别特征保留「类型图标块」；未读额外有左侧 3dp 蓝色竖条 + 极浅蓝底 + 加粗标题 + 尾点
 * （多重视觉冗余，不依赖单一信号，色弱 / 灰度屏也能区分）。
 *
 * ## 布局结构（固定「识别槽」，多选方框不再与标题重叠）
 *
 * 正文顺序对齐 DioHub - Dev：元信息行（仓库 #号 · 原因 · 时间）在**最上**、标题居中、
 * 评论预览在**最下**。理由见 README「卡片的约束」表 —— 「哪来的、什么时候」是定位坐标，
 * 先给坐标再读标题；预览仍是「要不要点进去」的依据，但标题必须是第一眼看到的那一行。
 *
 * ```
 *  ▍┌──────┐  仓库 #号 · [原因]              2 天前
 *  ▍│ 识别 │  标题（最多 2 行）
 *  ▍│ 槽位 │  评论预览（作者：正文）
 *  ▍└──────┘  ⌄ 展开其余 7 次        ← 折叠行才有（见 FoldExpandRow）
 * ─────────────────────────────────────────────────  ← 1dp 分隔线
 *  ▍ = 未读竖条（overlay 绘制，不占布局宽度）
 * ```
 *
 * 三条硬约束（上一版正是这里出的问题）：
 * 1. **行首槽位固定 32dp**：普通态是类型图标块、多选态是 20dp 复选方框，两者都锚在同一个
 *    32dp 槽里。上一版多选态把 32dp 图标直接换成 24dp 的 M3 `Checkbox`，标题左边界会跳 8dp；
 *    而 M3 Checkbox 按「独立控件」设计（内部 `wrapContentSize` + `requiredSize` + 最小触摸目标），
 *    在 `size(...)`/`padding(...)` 组合下会按自身约束重新落位，方框被画到槽位之外压住标题。
 * 2. **未读竖条 overlay 绘制**（`matchParentSize` + [drawBehind]）：作为 flex 子项时，
 *    未读行比已读行少 3dp 正文宽度，同样的标题会换行到不同位置。
 * 3. **方框只表达状态，点击归整行**：多选态整行是触摸目标（Material 列表选择规范），
 *    方框自身不带点击与最小触摸目标，选择语义由整行的 `selectable` 统一提供。
 *
 * 手势分工（[selectionEnabled] = 当前是否处于多选态）：
 * - 非多选态：点击 → 打开（未读时顺带标已读并打远端）；长按 → 快捷动作面板（单条动作，
 *   多选是面板里的一个显式选项）；
 * - 多选态：点击 → 切换选中（不跳转）；长按由列表容器接管做区间 / 刷选；
 * - **左滑 / 右滑两个方向同效** → 标记已读、复位、不跳转，**多选态下一律禁用**：
 *   旧实现用「是否平铺布局」同时控制多选和滑动，导致多选态下未选中的未读行仍可被滑动，
 *   批量选择过程中很容易误触把行标成已读。现在改为「多选态一律禁用滑动」，
 *   并且滑动在 4 种布局下都可用（此前只在平铺布局可用属于顺带的耦合，并非设计）。
 */
@Composable
private fun NotificationRow(
    n: Notification,
    preview: NotificationPreview? = null,
    selectionEnabled: Boolean,
    selected: Boolean,
    /** 折叠行是否已展开（见 [NotifFold]；普通行恒为 false） */
    foldOpen: Boolean,
    onToggleFold: () -> Unit,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onToggleSelection: () -> Unit,
    onSwipeRead: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    // 按下反馈：缩放值在 graphicsLayer 里读，只在绘制阶段消费（不触发每帧重组）。
    // ⚠️ graphicsLayer 必须排在 clip/background **之前** —— 否则只缩内容不缩底。
    val press = rememberPressFeedback(pressedScale = 0.985f)
    val rowShape = RoundedCornerShape(8.dp)
    SwipeToReadRow(enabled = !selectionEnabled && n.unread, onRead = onSwipeRead) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = press.scale.value
                    scaleY = press.scale.value
                }
                .clip(rowShape)
                // ⚠️ **未读不铺底**（真机复看后的修正）。
                //
                // 原型里给未读行铺了一层 4% 蓝底、推荐给用户时也说了「未读靠竖条+粗标题+圆点」，
                // 但真机上这一层底是**净负收益**：一屏里未读常常是多数，于是每一行都带底色、
                // 整片都「被高亮」，读过的和没读过的反而更难分 —— 高亮失去对比对象就不再是高亮。
                // 现在未读只由三个冗余信号表达（左侧竖条 + 加粗标题 + 尾部圆点），
                // 已读行保持干净白底，一屏里有没有未读一眼可数。
                //
                // ⚠️ 但「不铺底」**不等于背景可以透明**：`SwipeToReadRow` 的滑动提示
                // （「✓ 已读」）画在内容**之下**，内容背景一透明它就常驻透出来 ——
                // 看着就像「已读」两个字和标题、图标叠在一起（真机复看发现）。
                // 所以先铺一层不透明底：与页面同色，视觉上等于没有，
                // 作用只是把滑动提示挡回内容下面去。选中色再叠在它上面。
                .background(Primer.BackgroundPrimary)
                .background(if (selected) Primer.Blue500.copy(alpha = 0.06f) else Color.Transparent)
                // 选中态另加一圈描边：多选时底色只差 6%，只有底没有边的话，
                // 在浅色屏 / 强光下几乎看不出哪几行被选中了
                .then(
                    if (selected) {
                        Modifier.border(1.5.dp, Primer.BorderControl, rowShape)
                    } else {
                        Modifier
                    },
                )
                // 多选态用 selectable + Role.Checkbox：读屏会播报「已选中 / 未选中，复选框」；
                // 只画一个方框（不带语义）时，无障碍用户完全不知道行处于什么选择状态。
                .then(
                    if (selectionEnabled) {
                        Modifier.selectable(
                            selected = selected,
                            role = Role.Checkbox,
                            interactionSource = press.interaction,
                            indication = null,
                            onClick = onToggleSelection,
                        )
                    } else {
                        Modifier.combinedClickable(
                            interactionSource = press.interaction,
                            indication = null,
                            // 长按 → 快捷动作面板，补一次触觉确认：
                            // 没有反馈就分不清「长按没生效」还是「这一行本来就没反应」
                            onLongClick = onLongClick?.let { action ->
                                {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    action()
                                }
                            },
                            onClick = onClick,
                        )
                    },
                ),
        ) {
            val unreadBar = Primer.AccentText
            Box(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    NotificationLead(
                        selectionEnabled = selectionEnabled,
                        selected = selected,
                        n = n,
                        foldCount = n.fold?.count ?: 1,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        // 元信息行（仓库 #号 · 原因 · 时间）提到标题**上方** —— 排布对齐 DioHub - Dev：
                        // 「哪来的、什么时候」是定位坐标，先给坐标再读标题，扫一眼就能决定要不要点进去。
                        // 仓库名仍用 weight(fill = false)：短名字时原因胶囊与时间贴右，长名字先省略号。
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                n.repoFullName + (n.targetNumber?.let { " #$it" } ?: ""),
                                fontSize = 12.sp,
                                color = Primer.TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            // 原因标签只在「这条通知要我动手」时出现（见 Notification.reasonHighSignal）：
                            // 同屏 8 个「CI 运行结果」只是重复占位，结论已经在标题里。
                            // 文案没丢 —— 长按动作面板仍会读 reasonLabel。
                            if (n.reasonHighSignal) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    stringResource(n.reasonLabelRes),
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    // 用**文字色**角色而不是填充色：填充色压在自己的 12% 浅底上
                                    // 过不了 WCAG AA（WARNING 只有 2.6、DANGER 3.9、ACCENT 4.4）
                                    color = n.reasonColor.textColor(),
                                    maxLines = 1,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(9.dp))
                                        .background(n.reasonColor.textColor().copy(alpha = 0.12f))
                                        .padding(horizontal = 7.dp, vertical = 1.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            n.title,
                            fontSize = 13.5.sp,
                            fontWeight = if (n.unread) FontWeight.Bold else FontWeight.SemiBold,
                            color = Primer.TextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // 折叠行：展开其余运行的入口。没有这个入口，用户会以为消息被吞了 ——
                        // 收起时也必须让人看出「这里其实是 N 条」。
                        // isFolded ⟺ fold != null（见 Notification.isFolded），这里一次收口，
                        // 免得在行渲染里堆 !! 与 ?. 两种写法
                        val fold = n.fold
                        if (n.isFolded && fold != null) {
                            Spacer(Modifier.height(6.dp))
                            FoldExpandRow(
                                fold = fold,
                                open = foldOpen,
                                onToggle = {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onToggleFold()
                                },
                            )
                        }
                        // 动态预览（最新评论「作者：正文」）下移到标题**下方** —— 对齐 DioHub - Dev，
                        // 它把「最新动态」放在卡片最底。预览仍是「要不要点进去」的关键依据，
                        // 但标题永远是第一眼看到的那一行。未取到（未预取 / 取失败 / 正文为空）时整行不占位。
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
                                    if (preview.author.isBlank()) preview.body else stringResource(R.string.label_preview_author_body, preview.author, preview.body),
                                    fontSize = 12.sp,
                                    color = Primer.TextSecondary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        // 相对时间在**渲染期**由原始时间戳算出：快照 / 缓存里的时间不会失真
                        Text(relativeTimeOf(n.updatedAtMs).resolve(), fontSize = 11.sp, color = Primer.TextTertiary)
                        if (n.unread && !selectionEnabled) {
                            Spacer(Modifier.height(5.dp))
                            Box(Modifier.size(8.dp).clip(CircleShape).background(unreadBar))
                        }
                    }
                }
                // 未读竖条：overlay 画在最上层，不参与测量（正文宽度与已读行完全一致）。
                // 宽度 3 → **4dp**：去掉未读底之后它是主要的未读信号，3dp 在真机上偏细
                // （已读行是干净白底，4dp 的蓝条边界清楚，不需要靠底色再衬一层）。
                if (n.unread) {
                    Spacer(
                        Modifier.matchParentSize().drawBehind {
                            drawRect(color = unreadBar, size = Size(width = 4.dp.toPx(), height = size.height))
                        },
                    )
                }
            }
        }
    }
}

/**
 * 折叠行的「展开其余 N 次 / 收起」控件 + 展开后的逐条运行明细。
 *
 * 明细里保留每一次运行的**原始标题与时间**，所以折叠不丢信息：收起来是「今天挂了几次」，
 * 展开是「哪几次、什么时候」。
 *
 * ## 真机复看后的两处修正
 *
 * 1. **去掉胶囊边框**：原先是带描边的小胶囊，真机上它压在标题下方、每折都出现一次，
 *    读起来像「第二个标题」，把本已收起来的重复又加回来一层。现在只是一行安静的蓝色文字 +
 *    箭头 —— 它是动作，不是内容。
 * 2. **明细从属于父行**：明细用三级文字色 + 左侧导轨，且**父行不再铺未读底**（见
 *    [NotificationRow]），于是「1 条汇总 + N 条明细」的层次在视觉上真正成立；此前父行带底色时，
 *    明细跟着继承同一片底色，整块连成一片、层次被抹平。
 */
@Composable
private fun FoldExpandRow(fold: NotifFold, open: Boolean, onToggle: () -> Unit) {
    // 在组合里取色（drawBehind 的 lambda 不是 @Composable，里面读不到 Primer）
    val railColor = Primer.Gray200
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onToggle)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (open) stringResource(R.string.action_collapse) else stringResource(R.string.action_expand_remaining_runs, fold.count - 1),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = Primer.AccentText,
            )
            Spacer(Modifier.width(3.dp))
            val rotation by animateFloatAsState(if (open) 180f else 0f, label = "fold-chevron")
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = Primer.AccentText,
                modifier = Modifier.size(13.dp).rotate(rotation),
            )
        }
        AnimatedVisibility(visible = open, enter = revealEnter(), exit = revealExit()) {
            Column(
                Modifier
                    .padding(top = 5.dp, start = 1.dp)
                    .drawBehind {
                        // 左侧 2dp 导轨：把明细「挂在」折叠行下面，而不是看起来像新的几条消息
                        drawRect(
                            color = railColor,
                            size = Size(width = 2.dp.toPx(), height = size.height),
                        )
                    }
                    .padding(start = 9.dp),
            ) {
                fold.runs.forEach { run ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            run.title,
                            fontSize = 11.5.sp,
                            color = Primer.TextTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(relativeTimeOf(run.updatedAtMs).resolve(), fontSize = 11.sp, color = Primer.TextTertiary)
                    }
                }
            }
        }
    }
}

/**
 * 行首「识别槽」——固定 32dp，**任何模式下都占位**。
 *
 * 固定宽度的意义有两条：
 * 1. 普通态 ↔ 多选态切换时**标题左边界不动**（旧版 32dp 图标 → 24dp 复选框，标题会左右跳）；
 * 2. 让「方框压到标题」在结构上不可能发生 —— 方框最大 20dp，槽位 32dp，正文从槽位右侧 10dp 才开始。
 *
 * [foldCount] > 1 时（折叠行）图标块右下角挂一个「×N」计数徽标，并补一层错位的圆角描边 ——
 * 一眼看出「这里其实是 N 条」。**不加底色**：图标用文字色后不需要同色底来衬，
 * 而深色主题下那层 12% 同色底几乎看不见，白占一层。
 */
@Composable
private fun NotificationLead(selectionEnabled: Boolean, selected: Boolean, n: Notification, foldCount: Int) {
    Box(Modifier.size(32.dp), contentAlignment = Alignment.TopStart) {
        if (selectionEnabled) {
            SelectionCheckbox(checked = selected, modifier = Modifier.padding(top = 2.dp))
        } else {
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                if (foldCount > 1) {
                    // 只做「还有几条」的暗示：不参与语义（读屏从行文案就能知道条数）
                    Box(
                        Modifier
                            .matchParentSize()
                            .offset(x = 2.5.dp, y = 2.5.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, Primer.Gray200, RoundedCornerShape(8.dp)),
                    )
                }
                Icon(
                    n.icon,
                    contentDescription = n.subjectType,
                    tint = n.tint.textColor(),
                    modifier = Modifier.size(18.dp),
                )
                if (foldCount > 1) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = 5.dp, y = 4.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(Primer.Blue500)
                            .padding(horizontal = 4.5.dp, vertical = 2.5.dp),
                    ) {
                        Text(
                            "×$foldCount",
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            lineHeight = 9.5.sp,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 自绘复选方框（20dp，支持「半选」横杠）。
 *
 * 为什么不用 M3 `Checkbox`：
 * 1. 它按**独立控件**设计（内部 `wrapContentSize` + `requiredSize(20dp)`，可点时还会撑出最小触摸目标），
 *    塞进行首窄槽位时，`size(...)`/`padding(...)` 的组合顺序会让方框按内部约束重新落位并画出槽位；
 * 2. 它的勾选语义会和整行 `selectable` 的语义重复，读屏会念两遍；
 * 3. 这里只需要「一个能表达 未选 / 已选 / 半选 的方框」，自绘 20dp 反而完全可控。
 */
@Composable
private fun SelectionCheckbox(
    checked: Boolean,
    modifier: Modifier = Modifier,
    mixed: Boolean = false,
) {
    val active = checked || mixed
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .size(20.dp)
            .clip(shape)
            .background(if (active) Primer.Blue500 else Color.Transparent)
            .border(1.5.dp, if (active) Primer.Blue500 else Primer.Gray300, shape),
        contentAlignment = Alignment.Center,
    ) {
        when {
            // 半选：M3 没有三态 Checkbox，用一条横杠表达「组内部分选中」
            mixed -> Box(Modifier.size(width = 10.dp, height = 2.dp).background(Color.White))
            checked -> Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

/**
 * 左右滑标记已读的包装。
 *
 * **两个方向都可用**（对齐 DioHub - Dev：左右滑都是 Mark as read）。只放开单方向时，
 * 「从哪一侧滑」纯粹是用户的握持习惯 —— 左滑在单手 / 手小的场景下更顺手，没有理由拒绝。
 * `onDismiss` 里调用已读逻辑后必须 `reset()` 复位 —— 已读只是状态变化，
 * **不能真的把行从列表移除**。未读点/竖条的消失本身就是「已生效」的反馈。
 *
 * 背景提示按方向贴边：`backgroundContent` 本身不带方向参数（M3 1.4 的签名是
 * `@Composable RowScope.() -> Unit`），方向从 [SwipeToDismissBoxState.dismissDirection] 读 ——
 * 从左往右滑时内容右移、露出的是**左边缘**，提示就该靠左；反向同理。
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
        enableDismissFromEndToStart = enabled,
        backgroundContent = {
            val fromStart = dismissState.dismissDirection != SwipeToDismissBoxValue.EndToStart
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Primer.Blue500.copy(alpha = 0.12f))
                    .padding(horizontal = 16.dp),
                horizontalArrangement = if (fromStart) Arrangement.Start else Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = Primer.Blue500, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.state_read), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Primer.Blue500)
            }
        },
        onDismiss = { direction ->
            if (direction != SwipeToDismissBoxValue.Settled) currentOnRead()
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
    selectedState: GroupSelectState = GroupSelectState.NONE,
    onToggleSelection: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    // 分组头的三态（全选 / 未选 / 半选）：交给 triStateToggleable 统一表达，
    // 读屏才会把「半选」播报出来 —— 只画一条横杠的话，无障碍用户无法判断点下去是「全选」还是「全不选」。
    val triState = when (selectedState) {
        GroupSelectState.ALL -> ToggleableState.On
        GroupSelectState.NONE -> ToggleableState.Off
        GroupSelectState.MIXED -> ToggleableState.Indeterminate
    }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(8.dp)).background(Primer.BackgroundSecondary),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(
                    if (selectionMode) {
                        Modifier.triStateToggleable(
                            state = triState,
                            role = Role.Checkbox,
                            onClick = onToggleSelection,
                        )
                    } else {
                        Modifier.clickable { onToggle() }
                    },
                )
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
                // 纯视觉：点击由整个分组头统一消费（triStateToggleable），避免一次点击被消费两次
                SelectionCheckbox(
                    checked = triState == ToggleableState.On,
                    mixed = triState == ToggleableState.Indeterminate,
                )
            } else {
                val rotation by animateFloatAsState(if (expanded) 90f else 0f, label = "arrow")
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = if (expanded) stringResource(R.string.action_collapse_group) else stringResource(R.string.action_expand_group),
                    tint = Primer.IconSecondary,
                    modifier = Modifier.size(18.dp).rotate(rotation),
                )
            }
        }
        AnimatedVisibility(visible = expanded, enter = revealEnter(), exit = revealExit()) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) { content() }
        }
    }
}

@Composable
private fun EmptyState(category: NotifCategory, typeFiltered: Boolean) {
    Box(Modifier.fillMaxWidth().padding(vertical = 64.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Primer.Gray300, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(12.dp))
            Text(
                when {
                    typeFiltered && category != NotifCategory.UNREAD -> stringResource(R.string.state_no_messages_in_filter)
                    category == NotifCategory.DONE -> stringResource(R.string.state_no_completed_messages)
                    category == NotifCategory.UNREAD -> stringResource(R.string.state_no_unread_messages)
                    else -> stringResource(R.string.state_no_messages)
                },
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Primer.TextSecondary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    category == NotifCategory.DONE -> stringResource(R.string.note_archived_here)
                    category == NotifCategory.UNREAD -> stringResource(R.string.state_all_caught_up)
                    else -> stringResource(R.string.note_when_messages_arrive)
                },
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
            TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry), color = Primer.Blue500) }
        }
    }
}

/**
 * 骨架行：**结构与尺寸都与 [NotificationRow] 一一对应**
 * （同行高：内边距 12/11dp / 图标 32dp / meta 行 15dp + 间隔 5dp + 标题行 18dp）。
 *
 * 注意行形态之后**没有卡片盒了**，骨架也不该再画一圈边框 —— 否则加载完会「抖」一下：
 * 骨架看起来是一叠卡片、结果是分隔线列表。骨架屏与真实行的结构必须同步改，这是老坑。
 *
 * 现在只在一件事上会看到它：首页阶段没有预取到快照（计费网络 / 关闭了预加载开关 / 刚登录）。
 */
@Composable
private fun NotificationSkeleton() {
    // 之前是静态灰块（加载期间整页「死住」），后来接了 shimmer。
    // 占位块一律用 [skeletonBlock]：微光值在绘制期读。
    // 早先的写法是 `background(color.copy(alpha = shimmer))` —— 组合期读状态，
    // 6 行骨架各挂一条无限动画，加载时每帧把整块骨架重组一遍。
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .skeletonBlock(cornerRadius = 8.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            // 顺序与真实行一致：元信息行（仓库 · 时间）在上，标题在下
            Box(Modifier.fillMaxWidth(0.42f).height(15.dp).skeletonBlock())
            Spacer(Modifier.height(5.dp))
            Box(Modifier.fillMaxWidth(0.72f).height(18.dp).skeletonBlock())
        }
    }
}

// ───────────────────────────────── 长按动作面板 ─────────────────────────────────

/**
 * 长按卡片的快捷动作面板（需求 ② 的入口）。
 *
 * 为什么长按不直接进多选：用户想「只把这一条标成已读」时，
 * 旧实现要先长按进多选、再点「已读」，多了一步且列表结构发生了变化。
 * 现在长按先给**单条动作**，把多选作为一个显式选项放在末尾。
 *
 * 从多选条「更多」进来时（[bulkMode]）换成批量语义：动作作用于整个选中集合。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotifActionSheet(
    target: Notification,
    bulkMode: Boolean,
    selectionCount: Int,
    onDismiss: () -> Unit,
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
    onMarkDone: () -> Unit,
    onMute: () -> Unit,
    onCopyLink: () -> Unit,
    onOpenBrowser: () -> Unit,
    onShare: () -> Unit,
    onMultiSelect: () -> Unit,
    onExitSelection: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
                Box(
                    Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(target.tint.color().copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(target.icon, null, tint = target.tint.color(), modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (bulkMode) stringResource(R.string.label_selected_messages, selectionCount) else target.title,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Primer.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (bulkMode) stringResource(R.string.note_bulk_scope)
                        else target.repoFullName + (target.targetNumber?.let { " #$it" } ?: "") +
                            " · " + relativeTimeOf(target.updatedAtMs).resolve(),
                        fontSize = 11.5.sp,
                        color = Primer.TextTertiary,
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Primer.Gray150))

            if (bulkMode) {
                SheetAction(Icons.Filled.Done, stringResource(R.string.action_complete_selected, selectionCount), onMarkDone, hint = stringResource(R.string.action_move_to_done))
                SheetAction(Icons.Filled.MarkEmailUnread, stringResource(R.string.action_mark_all_unread), onMarkUnread, hint = stringResource(R.string.label_local_only))
                SheetAction(Icons.Filled.ContentCopy, stringResource(R.string.action_copy_selected_links), onCopyLink)
                SheetAction(Icons.Filled.Share, stringResource(R.string.action_share_first), onShare)
                Box(Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth().height(1.dp).background(Primer.Gray150))
                SheetAction(Icons.Filled.SelectAll, stringResource(R.string.action_exit_multiselect), onExitSelection)
            } else {
                if (target.unread) {
                    SheetAction(Icons.Filled.MarkEmailRead, stringResource(R.string.action_mark_as_read), onMarkRead, hint = stringResource(R.string.hint_swipe_right_same))
                } else {
                    SheetAction(Icons.Filled.MarkEmailUnread, stringResource(R.string.action_mark_as_unread), onMarkUnread)
                }
                SheetAction(Icons.Filled.Done, stringResource(R.string.action_mark_done), onMarkDone, hint = stringResource(R.string.action_move_to_done))
                SheetAction(Icons.Filled.VolumeOff, stringResource(R.string.action_mute_conversation), onMute, hint = stringResource(R.string.note_mute_thread))
                Box(Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth().height(1.dp).background(Primer.Gray150))
                SheetAction(Icons.Filled.ContentCopy, stringResource(R.string.action_copy_link), onCopyLink)
                SheetAction(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.action_open_in_browser), onOpenBrowser)
                SheetAction(Icons.Filled.Share, stringResource(R.string.action_share), onShare)
                Box(Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth().height(1.dp).background(Primer.Gray150))
                SheetAction(Icons.Filled.SelectAll, stringResource(R.string.label_multi_select), onMultiSelect, hint = stringResource(R.string.hint_long_press_range))
            }
            Text(
                stringResource(R.string.note_multiselect_help),
                fontSize = 11.5.sp,
                color = Primer.Blue600,
                lineHeight = 17.sp,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .background(Primer.Blue500.copy(alpha = 0.06f))
                    .padding(10.dp),
            )
        }
    }
}

@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    hint: String? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Primer.IconPrimary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 13.5.sp, color = Primer.TextPrimary)
        if (hint != null) {
            Spacer(Modifier.weight(1f))
            Text(hint, fontSize = 11.5.sp, color = Primer.TextTertiary)
        }
    }
}

// ───────────────────────────────── 状态 / 工具 ─────────────────────────────────

/** 一级页状态：加载中 / 错误 / 有数据（错误与数据互斥，用密封类避免二者同时为真） */
private sealed interface LoadState {
    data object Loading : LoadState
    data class Failed(val message: String) : LoadState
    data object Content : LoadState
}

/** 多选批量操作 */
internal enum class BulkOp { READ, DONE, MUTE }

/**
 * 批量结束后**要回滚**的条目（纯函数，有单测）。
 *
 * 只回滚**失败**的那些。旧实现一失败就整批回滚，于是远端已经改成功的条目在本地又被撤回：
 * 用户看到「批量失败」，过一会儿下拉刷新，其中一部分又自己变回已改 —— 这段窗口里
 * 本地与远端并不一致，而「回滚是为了跟远端一致」恰恰是整批回滚的理由，逻辑上是自相矛盾的。
 *
 * 静音（[BulkOp.MUTE]）不改变任何本地可见状态，没有可回滚的东西 —— 回滚它反而会
 * 顺带重写 [NotifArchive]（把期间用户从别的入口产生的归档改动覆盖掉）。
 */
internal fun bulkRollbackTargets(
    targets: List<Notification>,
    failedIds: Set<String>,
    op: BulkOp,
): List<Notification> =
    if (op == BulkOp.MUTE) emptyList() else targets.filter { it.id in failedIds }

/**
 * 批量失败后要整行回滚的**行**（纯函数，钉子在 `NotificationBulkRollbackTest`）。
 *
 * 折叠行是一个「汇总」：它显示的未读点与「连续失败 N 次」是一句话。组内 8 条只失败 2 条时，
 * 若按条回滚，界面会显示这一折仍是「已读」而其中两条在服务端还是未读 ——
 * 刷新后它们会重新组一折冒出来，与 [bulkRollbackTargets] 要解决的「本地与远端不一致」是同一个问题。
 * 所以折叠行**要么整行成功、要么整行回滚**。
 *
 * 返回的是行（[Notification]）而不是 id：调用方既要用它回滚条目，也要用它把「失败的行」留在选中态。
 */
internal fun rollingBackRows(rows: List<Notification>, failedIds: Set<String>): List<Notification> =
    if (failedIds.isEmpty()) emptyList()
    else rows.filter { row -> row.allIds.any { it in failedIds } }

/** 批量操作顺序执行时每条之间的间隔（毫秒）：避免同一秒内连发多次写请求触发二级速率限制 */
private const val NOTIF_BULK_GAP_MS = 120L

/** 一次最多预取多少条 issue/PR 内容预览（每条一次额外请求，宁少勿多，避免二级速率限制） */
private const val NOTIF_PREVIEW_MAX = 12

/**
 * 「我参与的」客户端口径。
 *
 * 服务端 `participating=true` 更准，但它要求每次切换分类都重新请求一页，
 * 而手机上「切分类 = 立刻想看结果」，等一次网络往返反而更差。
 * 这里用 reason 近似（与面板计数同源），保证分类切换零延迟、零流量。
 * 需要严格口径时把 [notifListPath] 的 `participating` 打开即可（键会随之不同，缓存不冲突）。
 */
private fun isParticipating(reason: String): Boolean =
    reason in setOf("mention", "team_mention", "review_requested", "assign", "author")

/** 排序（客户端）：分页游标场景下只作用于已加载段。 */
private fun sortedNotifications(list: List<Notification>, sort: NotifSort): List<Notification> = when (sort) {
    NotifSort.NEWEST -> list.sortedByDescending { it.updatedAtMs }
    NotifSort.OLDEST -> list.sortedBy { it.updatedAtMs }
    NotifSort.UNREAD_FIRST -> list.sortedWith(
        compareByDescending<Notification> { it.unread }.thenByDescending { it.updatedAtMs },
    )
}

/**
 * 渲染顺序 = 用户在屏幕上从上到下看到的行顺序。
 * 分组布局会改变行的先后（同一仓库的通知被拉到一起），因此区间 / 刷选必须按这个顺序算，
 * 否则「从 A 拖到 B」选出来的是另一个集合。
 */
internal fun renderOrderIds(list: List<Notification>, layout: NotifLayout): List<String> = when (layout) {
    NotifLayout.FLAT -> list.map { it.id }
    NotifLayout.GROUP_BY_REPO -> list.groupBy { it.repoFullName }.values.flatten().map { it.id }
    NotifLayout.MERGE_BY_THREAD -> list.groupBy { it.url.ifBlank { it.id } }.values.flatten().map { it.id }
    NotifLayout.TWO_LEVEL -> list.groupBy { it.repoFullName }.values
        .flatMap { repo -> repo.groupBy { it.url.ifBlank { it.id } }.values.flatten() }
        .map { it.id }
}

/**
 * 渲染行 key → 该行包含的通知 id（刷选用，见 [NotificationList] 的手势处理）。
 *
 * 三种分组布局的 LazyColumn item 都是**分组容器**，多个通知共用一个 item；
 * 平铺布局下 item key 就是通知 id。key 的拼法必须与 [NotificationList] 里 `item(key = …)` 一致，
 * 因此这里与 [renderOrderIds] 放在一起、一起被单测钉住。
 */
internal fun renderKeyToIds(list: List<Notification>, layout: NotifLayout): Map<String, List<String>> = when (layout) {
    NotifLayout.FLAT -> list.associate { it.id to listOf(it.id) }
    NotifLayout.GROUP_BY_REPO -> list.groupBy { it.repoFullName }
        .mapKeys { (repoName, _) -> "repo:$repoName" }
        .mapValues { (_, group) -> group.map { it.id } }
    NotifLayout.MERGE_BY_THREAD -> list.groupBy { it.url.ifBlank { it.id } }
        .mapKeys { (threadKey, _) -> "thread:$threadKey" }
        .mapValues { (_, group) -> group.map { it.id } }
    NotifLayout.TWO_LEVEL -> list.groupBy { it.repoFullName }
        .mapKeys { (repoName, _) -> "l2repo:$repoName" }
        .mapValues { (_, group) -> group.map { it.id } }
}

/** 归档条目是否属于「过往 Issue」区块（issue / PR）。 */
private val ArchivedThread.isIssueLike: Boolean
    get() = subjectType == "Issue" || subjectType == "PullRequest"

/**
 * 面板宽度自适应（需求注明「考虑实际分辨率」）：
 * 窄屏（<360dp）用「屏宽 - 24dp」避免贴边，常规屏固定 330dp，
 * 折叠屏/平板不让它无限拉宽（超过 380dp 就失去「固定在右下角」的体量感）。
 */
private fun panelWidthFor(screenWidthDp: Int): androidx.compose.ui.unit.Dp = when {
    screenWidthDp < 360 -> (screenWidthDp - 24).coerceAtLeast(280).dp
    screenWidthDp < 420 -> 330.dp
    else -> 380.dp
}

private fun issueTargetOf(entry: ArchivedThread): NotifTarget = when (entry.subjectType) {
    "PullRequest" -> NotifTarget.Pull(entry.owner, entry.repo, entry.number ?: 0)
    else -> NotifTarget.Issue(entry.owner, entry.repo, entry.number ?: 0)
}

/**
 * 通知对应的网页地址（复制链接 / 在浏览器打开 / 分享共用）。
 *
 * 以前一律拼 `/issues/{number}`：Release 与工作流通知会打开一个**编号巧合的 issue**。
 * 这里按 subject 类型分派；编号缺失时退到仓库页（比一个必然 404 的地址强）。
 */
private fun threadUrlOf(n: Notification): String {
    val repo = "https://github.com/${n.repoFullName}"
    val number = n.targetNumber
    return when (n.subjectType) {
        "Issue" -> number?.let { "$repo/issues/$it" } ?: repo
        "PullRequest" -> number?.let { "$repo/pull/$it" } ?: repo
        "Commit" -> n.targetSha?.let { "$repo/commit/$it" } ?: repo
        "CheckSuite", "CheckRun", "WorkflowRun" -> number?.let { "$repo/actions/runs/$it" } ?: repo
        "Discussion" -> number?.let { "$repo/discussions/$it" } ?: repo
        // Release 通知里抽到的编号是 release id（不是 tag），拼不出网页地址 → 退到发布列表
        "Release" -> "$repo/releases"
        else -> repo
    }
}

private fun copyThreadLink(context: Context, n: Notification) {
    copyToClipboard(context, threadUrlOf(n), context.getString(R.string.toast_link_copied))
}

private fun copyLinks(context: Context, list: List<Notification>) {
    if (list.isEmpty()) return
    copyToClipboard(context, list.joinToString("\n") { threadUrlOf(it) }, context.getString(R.string.toast_links_copied, list.size))
}

private fun copyToClipboard(context: Context, text: String, okMessage: String) {
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("GitHub", text))
        toast(context, okMessage)
    }.onFailure { toast(context, text) }
}

private fun openInBrowser(context: Context, n: Notification) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(threadUrlOf(n))))
    }.onFailure { toast(context, context.getString(R.string.error_no_browser)) }
}

private fun shareThread(context: Context, n: Notification) {
    runCatching {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "${n.title}\n${threadUrlOf(n)}")
        }
        context.startActivity(Intent.createChooser(send, context.getString(R.string.title_share_message)))
    }.onFailure { toast(context, context.getString(R.string.error_share_failed)) }
}

private fun toast(context: Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}
