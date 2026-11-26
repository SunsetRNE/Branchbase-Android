package com.branchbase.ui.home

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import com.branchbase.ui.navigation.BranchbaseLazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.stringResource
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import com.branchbase.R
import com.branchbase.core.AccountStore
import com.branchbase.core.RustBridge
import com.branchbase.ui.LocalizedText
import com.branchbase.ui.TintChip
import com.branchbase.ui.theme.TintRole
import com.branchbase.ui.theme.iconTap
import com.branchbase.ui.theme.Avatar
import com.branchbase.cache.PageCache
import com.branchbase.cache.SearchCacheDatabase
import com.branchbase.cache.SearchCacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.branchbase.ui.theme.Primer
import com.branchbase.cache.RepoPrefetcher
import com.branchbase.ui.notification.NotificationPrefetcher
import com.branchbase.ui.navigation.rememberPageResumeTick
import com.branchbase.ui.log.Logger
import org.json.JSONObject

/**
 * 计数未知时的占位（部分失败）：不猜数字，也不让整块变成空态。
 *
 * 只在一路成功、另一路失败时出现；三路全失败是 [TodoState.Failed]，走重试行。
 */
private const val COUNT_UNKNOWN = "\u2014"

/**
 * 首页卡片的统一容器语言：**中性白面 + 4dp 阴影、圆角 12dp**。
 *
 * 底色用 `Primer.BackgroundSecondary`（= `canvasSubtle`）：浅色板里它等于 canvas（纯白），
 * 靠阴影与页面分层；深色板里它是 `#161B22`，靠明度差分层。同一份代码两套主题都成立，
 * 这也是为什么不再用 `Gray100` —— 灰底在浅色下是"一块被染灰的白"，比白面 + 阴影重，
 * 在深色下又和 `canvasSubtle` 是同一个面（等于没换）。
 */
private val HomeCardShape = RoundedCornerShape(12.dp)
private val HomeCardElevation = 4.dp

/**
 * 首页 Dashboard。
 *
 * 结构：搜索栏+头像 / 待处理 / 进行中 / 常用仓库 / 最近活动。
 *
 * 「常用仓库」有两种形态：**没自定义过** → 接口顺序取前 5（与 1.1.7 一致）；
 * **自定义过** → 按用户在管理页点选的先后排（长按标题行或点右侧管理图标进入）。
 * 判定与排序都在 [FrequentRepoRules] 里（纯函数、有单测），这里只负责把状态接上。
 */
@Composable
fun HomeScreen(
    sessionJson: String,
    onProfileClick: () -> Unit,
    onSearchClick: () -> Unit,
    onRepoClick: (String) -> Unit = {},
    onOpenNotifications: () -> Unit = {},
    onEditFrequent: () -> Unit = {},
) {
    val context = LocalContext.current
    val user = runCatching { JSONObject(sessionJson).getJSONObject("user") }.getOrNull()
    // login 兜底：session.user → 当前账号（多账号表）→ 占位
    val login = user?.optString("login")?.takeIf { it.isNotBlank() && it != "null" }
        ?: AccountStore.currentLogin(context).takeIf { it.isNotBlank() }
        ?: stringResource(R.string.label_user)
    val avatarUrl = user?.optString("avatar_url")?.takeIf { it.isNotBlank() }

    // 解析 token 与 host，用于拉取数据
    val token = runCatching { JSONObject(sessionJson).getJSONObject("token").optString("access_token") }.getOrNull() ?: ""
    val host = runCatching { JSONObject(sessionJson).optString("host", "github.com") }.getOrDefault("github.com")
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("branchbase", Context.MODE_PRIVATE) }

    var repos by remember { mutableStateOf<List<RepoSummary>>(emptyList()) }
    var events by remember { mutableStateOf<List<Activity>>(emptyList()) }

    // 置顶选择（本地、按账号）：`null` = 从没自定义过 → 走接口顺序。
    // 初始值在组合期读一次盘（prefs 已加载过，代价可忽略），effect 里每次回前台再重读。
    val bucket = remember(sessionJson, context) { frequentRepoBucket(context, sessionJson) }
    var pinned by remember(bucket) { mutableStateOf(FrequentRepoStore.selection(context, bucket)) }

    // 加载首页「常用仓库」这一栏的数据（先读缓存，再网络刷新）
    //
    // **两个数据源，看有没有置顶**（规则集中在 [FrequentRepoSource]，这里只是照它取数）：
    // - 没置顶过（或把置顶全取消）→ 默认态：星标（收藏）仓库，服务端按最近星标倒序，取前 5
    //   （[STARRED_RECENT_PATH]，与 1.1.8 及以前一致）；
    // - 置顶过 → 只显示常用仓库：候选集是「我能用的仓库」= 自己持有的 + 有权限/被协作的 +
    //   所属组织与团队里的（[MY_REPOS_PATH]），服务端按最近推送排序。
    // 两个源各有一份缓存键：共用键会让切源后第一帧拿错数据渲染（见 [FrequentRepoSource.cacheKeyFor]）。
    //
    // 这里没有 `refresh` 参数了：管理页出现之前，首页「常用仓库」右上角有个刷新按钮，
    // 走的是 `refresh = true`（跳过缓存直接联网）。现在那个位置换成了管理入口，
    // 而**回前台本来就会重新联网**（这个 effect 的键是 resumeTick），
    // 于是「跳缓存」这条路径再没有调用方 —— 留着它就是一段没人走的死代码。
    // 真要强制刷新：进管理页，那里右上角有刷新按钮。
    suspend fun loadRepos(): List<RepoSummary> {
        val cacheKey = FrequentRepoSource.cacheKeyFor(pinned)
        prefs.getString(cacheKey, null)?.let { repos = parseRepoList(it) }
        RustBridge.getJson(host, token, FrequentRepoSource.pathFor(pinned))?.let { json ->
            if (!json.startsWith("ERROR:")) {
                repos = parseRepoList(json)
                prefs.edit().putString(cacheKey, json).apply()
            }
        }
        return repos
    }

    // 加载最近活动（先读缓存，再网络刷新）
    suspend fun loadEvents(refresh: Boolean) {
        if (!refresh) {
            prefs.getString("recent_events", null)?.let { events = parseActivities(it) }
        }
        // 首页展示「我自己的活动」→ /user/events（含私有仓库）。
        // 不要用 received_events：那是「我关注的人的活动」feed，通常为空。
        RustBridge.getJson(host, token, "/user/events?per_page=50")?.let { json ->
            if (!json.startsWith("ERROR:")) {
                events = parseActivities(json)
                prefs.edit().putString("recent_events", json).apply()
            }
        }
    }

    // 首页仪表盘数据（未读通知 / 待审 PR / 进行中的运行）
    //
    // 三个计数是 `Int?`：`null` = 这一路**没取到**，不是 0。改动前失败被静默成 0，
    // 于是「断网」与「确实没有」在界面上完全同帧，首页会断言一句假话（见 TodoState.kt）。
    var unreadNotifs by remember { mutableStateOf<Int?>(null) }
    var reviewRequests by remember { mutableStateOf<Int?>(null) }
    var assignedIssues by remember { mutableStateOf<Int?>(null) }

    // 只在**首轮**取数期间为 true。之后每次回前台重算都不再置 true ——
    // 否则切一次 Tab 就闪一屏骨架，比「数字晚 100ms 更新」难受得多。
    var todoLoading by remember { mutableStateOf(true) }
    var runningTasks by remember { mutableStateOf<List<com.branchbase.ui.task.TaskRecord>>(emptyList()) }

    // 仪表盘计数缓存（TTL 5 分钟）。星标/活动仍走各自的 prefs 缓存，不受影响。
    val cacheManager = remember(context) {
        SearchCacheManager(SearchCacheDatabase.getInstance(context).searchCacheDao())
    }

    /**
     * 计数类请求的「缓存直出 + 回源写回」。
     *
     * **失败返回 `null`，不再静默成 0**：0 是合法值（真的没有），拿它当哨兵会让
     * 「断网」与「确实没有」在界面上完全同帧，首页因此会断言一句假话。
     * 其余语义不变：回源前先读缓存，未过期（5 分钟）时 [PageCache.refresh] 直接返回缓存、
     * 不再发请求；每次回到前台仍会走一遍（本函数无 force 入口），命中未过期缓存即免流量。
     *
     * 解析与改动前逐一对应：
     * - 通知计数 = `/notifications` 返回数组的长度；
     * - 评审/指派计数 = 搜索接口返回对象的 `total_count`。
     */
    suspend fun loadCachedCount(name: String, fetch: suspend () -> String?): Int? {
        val json = PageCache.refresh(cacheManager, PageCache.homeKey(name), PageCache.TYPE_HOME) {
            withContext(Dispatchers.IO) { fetch() }
        } ?: return null
        return runCatching {
            if (json.trimStart().startsWith("[")) {
                org.json.JSONArray(json).length()
            } else {
                JSONObject(json).optInt("total_count", 0)
            }
        }.getOrNull()
    }

    /**
     * 待处理三件套：三路并行取数（失败各自为 `null`，互不影响）。
     *
     * 消息首屏预取（需求 ③）：原来这里请求 `/notifications?per_page=100` **只为了数长度**，
     * 而消息页进去还要再请求一次首屏 —— 同一份数据两条腿各拉一遍。
     * 现在这一步把首屏做完整：写 PageCache（落盘）+ 填 NotifSnapshot（已解析），
     * 未读数也从同一份数据算出，于是「进消息页」= 0 次网络 + 0 帧骨架。
     * 被策略跳过（计费网络 / 关闭了预加载开关）时返回 null，回退到计数路径。
     *
     * 单独抽成函数，是为了让「失败态的重试」能只重跑这一段，不必连带重拉星标与活动。
     */
    suspend fun loadTodoCounts() {
        coroutineScope {
            launch {
                unreadNotifs = NotificationPrefetcher.warmUp(context, host, token)
                    ?: loadCachedCount("unread") {
                        RustBridge.getJson(host, token, "/notifications?per_page=100")
                    }
            }
            launch {
                reviewRequests = loadCachedCount("review") {
                    RustBridge.searchIssues(host, token, "review-requested:@me state:open type:pr")
                }
            }
            launch {
                assignedIssues = loadCachedCount("assigned") {
                    RustBridge.searchIssues(host, token, "assignee:@me state:open")
                }
            }
        }
        todoLoading = false
    }

    // Tab 保活（`TabSwitcher` 不再销毁切走的页）⇒ `LaunchedEffect(Unit)` 一辈子只跑一次，
    // 计数会静默变旧。挂上「重新可见」的 tick：切回首页时重新校验一遍
    // （每个计数各自走 PageCache：TTL 内直接命中，不联网）。
    val resumeTick = rememberPageResumeTick()
    LaunchedEffect(resumeTick) {
        // 阶段标记：首帧取数这一段（L2 缓存冷开 + 星标 13.8KB / 通知归档 12.3KB 的 JSON 解析）
        // 与启动慢帧的「等待」段同刻发生，但此前在日志里完全看不见（见 frame-perf-design.md §9）。
        //
        // ⚠️ **每个进程只打一次**。这里踩过两次：
        // 1.0.65 无条件打 —— 这个 effect 的键是 `resumeTick`，切回首页就会重跑，日志里
        // 它在 +20s / +22s / +27s 各出现一次；1.0.67 改成 `resumeTick == 1`，
        // 但 `resumeTick` 是 remember 出来的、页面一被重建就从 1 重来
        // （1.0.67 真机：+116s 又打了一次，那时用户在仓库页）。两次的后果一样：
        // 之后几帧的慢帧注脚变成「启动 ▸ …」，`^启动` 场景桶把它们算成启动帧。
        // 进程级的闸门不受页面生命周期影响，见 [Logger.startupOnce]。
        Logger.startupOnce("home-first-paint", "启动 ▸ 首页首帧取数（L2 缓存 + 星标/通知解析）")
        // 5 个互不依赖的请求并行（原来是串行：星标 → 活动 → 通知 → 评审 → 指派）
        //
        // `loaded` 是「这一轮真正拿到的仓库」：`repos` 是 state，effect 闭包读到的是**启动那一刻**
        // 的旧值，直接拿它做预加载会预热上一轮的那几个仓库。
        var loaded: List<RepoSummary> = emptyList()
        coroutineScope {
            launch { loaded = loadRepos() }
            launch { loadEvents(false) }
            // 待处理三件套：并行取数（细节见 loadTodoCounts；失败各自为 null）
            launch { loadTodoCounts() }
        }
        // 进行中任务：本地 Room，零网络请求
        runningTasks = com.branchbase.ui.task.TaskStore.list(context)
            .filter { it.status == com.branchbase.ui.task.TaskStatus.RUNNING }
        // 置顶选择每次回前台重读：管理页是点一下就写盘（没有保存按钮），
        // 而 Tab 保活时首页**不会被重建**（切回首页不重跑 remember），只在组合期读一次会读到旧值。
        // 键无变化时 `pinned` 的写回是同一个 List 实例，Compose 跳过重组，代价为零。
        pinned = FrequentRepoStore.selection(context, bucket)
        // 预加载：首页「常用仓库」首屏那几个仓库的详情，点进去直接命中缓存（计费网络下自动跳过）
        RepoPrefetcher.warmList(
            context = context,
            host = host,
            token = token,
            entries = FrequentRepoRules.visibleOnHome(loaded, pinned, key = { it.fullName }).map {
                it.fullName.substringBefore('/') to it.fullName.substringAfter('/', "")
            },
        )
    }

    // 首页实际要渲染的「常用仓库」（顺序 = 用户点选先后；没自定义过时 = 接口顺序前 5）
    val shownRepos = FrequentRepoRules.visibleOnHome(repos, pinned, key = { it.fullName })

    // 待处理：四态 + 折叠（判定都在 TodoState.kt 的纯函数里，这里只接状态）
    val todoState = todoStateOf(todoLoading, unreadNotifs, reviewRequests, assignedIssues)
    // 折叠记忆按账号（键 home_todo_collapsed，见 TodoFoldStore）；`null` = 没手动切过 → 走状态默认
    var todoRemembered by remember(bucket) { mutableStateOf(TodoFoldStore.collapsed(context, bucket)) }
    val todoFolded = todoCollapsed(todoState, todoRemembered)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Primer.BackgroundPrimary),
    ) {
        // 顶部：搜索栏 + 头像
        SearchBarRow(login = login, avatarUrl = avatarUrl, onProfileClick = onProfileClick, onSearchClick = onSearchClick)

        // 内容：仪表盘式分区（待处理 → 进行中 → 常用 → 浏览）
        BranchbaseLazyColumn(modifier = Modifier.fillMaxSize()) {
            item { GreetingRow(login) }

            // ① 待处理：需要你动手的（折叠头 + 展开体在同一个容器里，头就是原来的区块标题）
            item {
                TodoGroup(
                    state = todoState,
                    collapsed = todoFolded,
                    unread = unreadNotifs,
                    reviews = reviewRequests,
                    assigned = assignedIssues,
                    onToggle = {
                        val next = !todoFolded
                        todoRemembered = next
                        TodoFoldStore.save(context, bucket, next)
                    },
                    onRetry = {
                        // 只重跑计数（不连带重拉星标与活动）；先置 loading 让展开体给骨架。
                        // 同时把本轮记忆钉成「展开」：加载中默认是折叠的，不这么做会把用户
                        // 刚点开的「重试」顺手收起来。只影响本轮会话，不改盘上的记忆。
                        todoRemembered = false
                        todoLoading = true
                        scope.launch { loadTodoCounts() }
                    },
                    onOpenNotifications = onOpenNotifications,
                    onOpenSearch = onSearchClick,
                )
            }

            // ② 进行中：本地任务（零网络）。与「待处理」共用无框分组容器，头不再单独占一行；
            // 两块之间留 10dp，避免两个浅底块贴在一起。
            if (runningTasks.isNotEmpty()) {
                item { Spacer(Modifier.height(10.dp)) }
                item { RunningGroup(runningTasks) }
            }

            // ③ 常用仓库（置顶过 = 只放用户选的常用仓库；没置顶过 = 收藏仓库最近 5 个）
            //
            // 图标：1.1.8 及以前这里是 Star。这一栏现在有**两种来源**（默认态是收藏仓库、
            // 置顶态是「我能用的仓库」），Star 只在默认态说得通、置顶态就是错的，
            // 所以用被整个 App 当作「仓库」的中性图标 Folder（个人页「仓库」tab 与仓库列表页都是它）。
            //
            // 右侧图标从「刷新」换成「管理」（Tune）：刷新在这个位置是冗余的 ——
            // 每次回到前台这个 effect 都会联网重拉仓库，刷新按钮只是让它早 0.1 秒；
            // 而「自定义置顶」没有别的入口。
            item {
                SectionHeader(
                    title = stringResource(R.string.label_frequent_repos),
                    icon = Icons.Filled.Folder,
                    tint = TintRole.DONE,
                    onAction = onEditFrequent,
                    actionIcon = Icons.Filled.Tune,
                    actionLabel = stringResource(R.string.label_manage_frequent_repos),
                    onLongClick = onEditFrequent,
                )
            }
            if (repos.isEmpty()) {
                // 是哪个源空了就说哪个源的话：默认态（收藏仓库为空）和「置顶了但候选集为空」不是一回事
                item {
                    EmptyState(
                        stringResource(
                            if (pinned.isNullOrEmpty()) R.string.state_no_starred_repos
                            else R.string.state_no_my_repos,
                        ),
                    )
                }
            } else if (shownRepos.isEmpty()) {
                // 勾过、但勾的那些都不在候选集里了（被移出协作者、组织权限变更、仓库转移）：空态 + 说明书。
                // **不要**在这里回落收藏仓库 —— 用户确实置顶了，静默换成另一份列表等于选择被无声覆盖；
                // 回落只发生在「一个都没置顶」（含把置顶全取消）时，规则见 [FrequentRepoSource]。
                item { EmptyState(stringResource(R.string.state_no_pinned_repos)) }
                item { EmptyState(stringResource(R.string.note_frequent_repos_empty_hint)) }
            } else {
                items(shownRepos) { repo -> RepoCard(repo, onClick = { onRepoClick(repo.fullName) }) }
            }

            // ④ 最近活动（只留 3 条，完整列表在个人主页的动态页）
            item {
                SectionHeader(
                    title = stringResource(R.string.label_recent_activity),
                    icon = Icons.Filled.History,
                    tint = TintRole.ACCENT,
                    onAction = { scope.launch { loadEvents(true) } },
                )
            }
            if (events.isEmpty()) {
                item { EmptyState(stringResource(R.string.state_no_recent_activity)) }
            } else {
                items(events.take(3)) { act -> ActivityItem(act) }
            }
        }
    }
}

/** 解析 `/user/repos` 或 `/user/starred` 返回的 JSON 数组（首页与管理页共用，故 internal） */
internal fun parseRepoList(json: String): List<RepoSummary> {
    return runCatching {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            RepoSummary(
                fullName = obj.optString("full_name"),
                // optString 对 JSON null 会返回字符串 "null"（GitHub 的 description / language
                // 恰好大量为 null），不判 isNull 就会在卡片上原样印出「null」——
                // 与 login 的处理同一套判据（见 HomeScreen 顶部 login 的兜底）。
                desc = obj.stringOrEmpty("description"),
                language = obj.stringOrNull("language")?.takeIf { it.isNotBlank() },
                stars = obj.optLong("stargazers_count").let { formatCount(it) },
                forks = obj.optLong("forks_count").let { formatCount(it) },
                // 这两个字段两份响应（/user/repos 与 /user/starred）都有；缺字段时是 false，
                // 与「没读到」在界面上没法区分 —— 但徽标只是提示，不参与任何判断，代价可接受
                isPrivate = obj.optBoolean("private", false),
                isFork = obj.optBoolean("fork", false),
            )
        }
    }.getOrDefault(emptyList())
}

/** 取字符串字段：JSON `null` / 缺字段 / 空白都归一到 `""`，不会退化成字面量 "null"。 */
private fun JSONObject.stringOrEmpty(key: String): String = stringOrNull(key).orEmpty()

/** 取可空字符串字段：JSON `null` / 缺字段 / 空白 → `null`。 */
private fun JSONObject.stringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

private fun formatCount(n: Long): String = when {
    n >= 1000 -> "%.1fk".format(n / 1000.0)
    else -> n.toString()
}

/** 解析 received_events 返回的 JSON 数组 */
private fun parseActivities(json: String): List<Activity> {
    return runCatching {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val obj = arr.getJSONObject(i)
            val type = obj.optString("type")
            val actor = obj.optJSONObject("actor")?.optString("login") ?: return@mapNotNull null
            val repoName = obj.optJSONObject("repo")?.optString("name") ?: ""
            val createdAt = obj.optString("created_at")
            // 整句资源：原来拼 "$actor $verb $repoName"，只有中文语序拼得对
            val (icon, text) = when (type) {
                "WatchEvent" -> Icons.Filled.Star to LocalizedText(R.string.home_activity_starred, listOf(actor, repoName))
                "ForkEvent" -> Icons.Filled.CallSplit to LocalizedText(R.string.home_activity_forked, listOf(actor, repoName))
                "IssuesEvent" -> Icons.Filled.ErrorOutline to LocalizedText(R.string.home_activity_issue, listOf(actor, repoName))
                "PullRequestEvent" -> Icons.Filled.CallSplit to LocalizedText(R.string.home_activity_pr, listOf(actor, repoName))
                "PushEvent" -> Icons.Filled.Code to LocalizedText(R.string.home_activity_push, listOf(actor, repoName))
                "CreateEvent" -> Icons.Filled.Add to LocalizedText(R.string.home_activity_created, listOf(actor, repoName))
                else -> return@mapNotNull null
            }
            Activity(
                icon = icon,
                text = text,
                createdAt = createdAt,
            )
        }
    }.getOrDefault(emptyList())
}

/**
 * ISO 时间转相对时间（「刚刚」/「5 分钟前」…）。
 *
 * `@Composable` 因为量词要按语言选形：中文只有一种写法，英文得区分
 * `1 minute ago` / `2 minutes ago`，而倍数词只有 `pluralStringResource` 拿得到。
 * 解析失败原样返回 `iso` —— 宁可显示原始时间，也不要吞掉整个时间列。
 */
@Composable
private fun relativeTime(iso: String): String {
    val minutes = runCatching {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val epoch = fmt.parse(iso)?.time ?: return@runCatching null
        (System.currentTimeMillis() - epoch) / 60000
    }.getOrNull() ?: return iso
    return when {
        minutes < 1 -> stringResource(R.string.relative_just_now)
        minutes < 60 -> pluralStringResource(R.plurals.relative_minutes, minutes.toInt(), minutes)
        minutes < 1440 -> pluralStringResource(R.plurals.relative_hours, (minutes / 60).toInt(), minutes / 60)
        else -> pluralStringResource(R.plurals.relative_days, (minutes / 1440).toInt(), minutes / 1440)
    }
}

/** 搜索栏 + 头像（头像点击进个人页） */
@Composable
private fun SearchBarRow(login: String, avatarUrl: String?, onProfileClick: () -> Unit, onSearchClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 搜索框（点击进搜索页）
        Row(
            modifier = Modifier
                .weight(1f)
                .height(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Primer.BackgroundSecondary)
                .clickable { onSearchClick() }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.action_search), tint = Primer.IconSecondary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.hint_search_github), fontSize = 14.sp, color = Primer.TextTertiary)
        }
        Spacer(Modifier.width(10.dp))
        // 头像（40dp 圆）—— 统一组件：本地缓存优先 + 按尺寸取图 + 首字母占位
        Avatar(
            url = avatarUrl,
            login = login,
            size = 40.dp,
            // 反馈必须是圆形：Avatar 内部的 clip 在调用方 modifier **之后**，
            // 直接传 clickable 会让水波纹溢成正方形（iconTap 把 clip 提到点击之前）
            modifier = Modifier.iconTap { onProfileClick() },
        )
    }
}

/** 问候语（首页顶部）。 */
@Composable
private fun GreetingRow(login: String) {
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    val greet = when {
        hour < 6 -> stringResource(R.string.greet_late_night)
        hour < 12 -> stringResource(R.string.greet_morning)
        hour < 18 -> stringResource(R.string.greet_afternoon)
        else -> stringResource(R.string.greet_evening)
    }
    Text(
        stringResource(R.string.greet_with_login, greet, login),
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        color = Primer.TextPrimary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
    )
}

/**
 * 首页分区容器：**中性白面 + 4dp 阴影、圆角 12dp**（与 [RepoCard] 同一套，见 [HomeCardShape]）。
 *
 * 「待处理」与「进行中」共用同一套容器语言 —— 两块并排时不能一个是无框分组、
 * 一个是 1dp 纯黑描边卡片（浅色板里 `BorderEmphasis` 就是纯黑，是页面上最重的线）。
 * 头部与展开体之间、行与行之间都用同一条 `TodoDivider()`。
 *
 * 2026-11-27：`Gray100` 灰底换成白面 + 阴影。灰底在浅色下是「一块被染灰的白」，
 * 分组全靠明度硬造，比同层级的内容还重；白面 + 阴影才是更轻的分组方式。
 * 深色下阴影不可见，分组由 `canvasSubtle`（#161B22）与 canvas（#0D1117）的明度差承担。
 */
@Composable
private fun HomeGroupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth()
            .padding(horizontal = 16.dp)
            .shadow(HomeCardElevation, HomeCardShape)
            .clip(HomeCardShape)
            .background(Primer.BackgroundSecondary),
        content = content,
    )
}

/**
 * 待处理分组：**折叠头 + 展开体在同一个容器里**，头就是原来的区块标题。
 *
 * 三行都是「需要你动手的」，但整块的信息量只有「一句话 + 一个数」：
 * 所以行压到 44dp（与折叠头同高），全 0 时默认折叠成一句「都处理完了」，
 * 不再为「没事了」画一张 100dp 的卡片。
 *
 * 无描边（原先是 `BorderEmphasis`，浅色板里是纯黑）：这一块可以没有内容，
 * 用最重的线去框它，层级正好反了。改浅底 + 圆角，靠 44dp 高度与留白识别。
 *
 * 展开/收起走 [AnimatedVisibility] + `expandVertically(spring)`：弹簧带过冲才有「弹出来」的手感；
 * tween 的等高展开就是「生硬」那一版。`clip = true`（默认）保证过冲期内容不外溢。
 */
@Composable
private fun TodoGroup(
    state: TodoState,
    collapsed: Boolean,
    unread: Int?,
    reviews: Int?,
    assigned: Int?,
    onToggle: () -> Unit,
    onRetry: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenSearch: () -> Unit,
) {
    HomeGroupCard {
        TodoHead(
            state = state,
            collapsed = collapsed,
            unread = unread,
            reviews = reviews,
            assigned = assigned,
            onToggle = onToggle,
        )
        AnimatedVisibility(
            visible = !collapsed,
            enter = expandVertically(
                animationSpec = spring(dampingRatio = 0.55f, stiffness = 420f),
                expandFrom = Alignment.Top,
            ) + fadeIn(animationSpec = tween(120)),
            exit = shrinkVertically(animationSpec = tween(260)) + fadeOut(animationSpec = tween(150)),
        ) {
            Column(Modifier.fillMaxWidth()) {
                // 头与展开体之间也要一条：否则「标题 + 三行」读起来像一段连续文本，
                // 而不是「一个可折叠的块」。原型里这条是 .fold-inner 的 border-top。
                TodoDivider()
                when (state) {
                    TodoState.Loading -> TodoSkeleton()
                    TodoState.Failed -> TodoFailRow(onRetry)
                    TodoState.Empty, TodoState.Ready -> {
                        TodoRow(
                            title = stringResource(R.string.label_unread_notifications),
                            value = unread?.let { stringResource(R.string.label_unread_count, it.toString()) } ?: COUNT_UNKNOWN,
                            iconBg = Primer.InfoSurfaceSoft,
                            iconFg = Primer.Blue500,
                            enabled = (unread ?: 0) > 0,
                            onClick = onOpenNotifications,
                        )
                        TodoDivider()
                        TodoRow(
                            title = stringResource(R.string.label_awaiting_review),
                            value = reviews?.let { stringResource(R.string.label_review_pr_count, it.toString()) } ?: COUNT_UNKNOWN,
                            iconBg = Primer.SuccessSurface,
                            iconFg = Primer.Green500,
                            enabled = (reviews ?: 0) > 0,
                            onClick = onOpenSearch,
                        )
                        TodoDivider()
                        TodoRow(
                            title = stringResource(R.string.label_assigned_to_me),
                            value = assigned?.let { stringResource(R.string.label_assigned_issue_count, it.toString()) } ?: COUNT_UNKNOWN,
                            iconBg = Primer.WarningSurface,
                            iconFg = Primer.WarningTextStrong,
                            enabled = (assigned ?: 0) > 0,
                            onClick = onOpenSearch,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 折叠头：铃铛 + 「待处理」+ 摘要 + 箭头。
 *
 * 摘要承担状态（N 项待办 / 都处理完了 / 读取中… / 暂时取不到），折叠时它是唯一信息，
 * 所以数字用 `TextPrimary` 加粗、短语用 `TextTertiary`——两者都能读出来，但轻重不同。
 *
 * 读屏：整行是一个 `Role.Button` 节点，带 `expanded` 语义与「展开/收起」动作标签，
 * TalkBack 会念「待处理，1 项待办，已折叠，双击展开」。
 */
@Composable
private fun TodoHead(
    state: TodoState,
    collapsed: Boolean,
    unread: Int?,
    reviews: Int?,
    assigned: Int?,
    onToggle: () -> Unit,
) {
    val actionLabel = stringResource(if (collapsed) R.string.home_todo_expand else R.string.home_todo_collapse)
    // Compose 1.10.3 的 SemanticsProperties 里**没有** `expanded`，可用的等价物是
    // `stateDescription`（读屏念「已折叠/已展开」）+ `expand`/`collapse` 动作（读屏菜单里可触发）。
    val stateText = stringResource(
        if (collapsed) R.string.home_todo_state_collapsed else R.string.home_todo_state_expanded,
    )
    val chevron by animateFloatAsState(
        targetValue = if (collapsed) -90f else 0f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 500f),
        label = "todo-chevron",
    )
    val summary = when (state) {
        TodoState.Loading -> stringResource(R.string.home_todo_loading)
        TodoState.Empty -> stringResource(R.string.home_todo_empty)
        TodoState.Failed -> stringResource(R.string.home_todo_failed)
        TodoState.Ready -> {
            val n = todoSummaryCount(unread, reviews, assigned)
            pluralStringResource(R.plurals.home_todo_pending, n, n)
        }
    }
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(onClickLabel = actionLabel) { onToggle() }
            .semantics {
                stateDescription = stateText
                if (collapsed) {
                    expand(actionLabel) { onToggle(); true }
                } else {
                    collapse(actionLabel) { onToggle(); true }
                }
            }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TintChip(Icons.Filled.Notifications, TintRole.WARNING)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.label_todo), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary)
        Spacer(Modifier.weight(1f))
        Text(
            summary,
            fontSize = 13.sp,
            color = Primer.TextTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(2.dp))
        Icon(
            Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = Primer.IconSecondary,
            modifier = Modifier.size(16.dp).rotate(chevron),
        )
    }
}

/**
 * 单行型：标题 + 计数同行，44dp。
 *
 * 计数靠右而不是另起一行：这一块的信息就是「一句话 + 一个数」，
 * 两行排版等于用两倍高度讲一件事（见 `docs/specs/prototypes/home-todo-empty.md` §5.2）。
 * `null` 计数（部分失败）显示「—」并不可点 —— 不猜数字。
 */
@Composable
private fun TodoRow(
    title: String,
    value: String,
    iconBg: Color,
    iconFg: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(22.dp).clip(RoundedCornerShape(7.dp)).background(iconBg),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(iconFg))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            title,
            fontSize = 13.sp,
            lineHeight = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) Primer.TextPrimary else Primer.TextTertiary,
            modifier = Modifier.weight(1f),
        )
        Text(value, fontSize = 12.sp, color = Primer.TextTertiary)
        if (enabled) {
            Spacer(Modifier.width(6.dp))
            Text("›", fontSize = 15.sp, color = Primer.TextTertiary)
        }
    }
}

/**
 * 卡内分隔线（折叠头与展开体之间、展开体各行之间）。
 *
 * 2026-11-27：`BorderEmphasis × 40%` 换成 `Primer.Border`。
 * `BorderEmphasis` 在浅色板里是**纯黑**，压在原本的灰底上 40% 刚好；卡片换成白面之后，
 * 同样一条线成了整页对比度最高的东西 —— 用户第一眼看到的就是它，而不是「待处理」这几个字。
 * 描边色（#BFC1C9 / 深色 #30363D）才是"分组"该有的重量：看得见，但不抢读。
 */
@Composable
private fun TodoDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Primer.Border))
}

/** 取数中：3 行骨架，行高照实（44dp），取数完成前后不跳。 */
@Composable
private fun TodoSkeleton() {
    repeat(3) { index ->
        Row(
            Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(22.dp).clip(RoundedCornerShape(7.dp)).background(Primer.Gray150))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.width(92.dp).height(11.dp).clip(RoundedCornerShape(3.dp)).background(Primer.Gray150))
        }
        if (index < 2) TodoDivider()
    }
}

/**
 * 取数失败：**不冒充空态**。
 *
 * 摘要已经说了「暂时取不到」，这里给原因与出口（重试只重跑计数，不连带重拉星标与活动）。
 * 这一态会被 [todoCollapsed] 强制展开 —— 把唯一的恢复入口藏在折叠里，等于让用户没有出路。
 */
@Composable
private fun TodoFailRow(onRetry: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = Primer.Red500, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.home_todo_failed),
                fontSize = 13.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = Primer.TextSecondary,
            )
            Text(
                stringResource(R.string.home_todo_failed_hint),
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = Primer.TextTertiary,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.action_retry),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Primer.Link,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, Primer.Border, RoundedCornerShape(6.dp))
                .clickable { onRetry() }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/**
 * 进行中分组（数据来自本地 TaskStore，零网络）。
 *
 * 与「待处理」同一套容器：浅底、无描边、44dp 头 + 分隔线。区别只有两点 ——
 * 头不可点（最多 3 条，收起没有收益，所以不给箭头），行高由内容决定（有进度条与详情）。
 */
@Composable
private fun RunningGroup(tasks: List<com.branchbase.ui.task.TaskRecord>) {
    val shown = tasks.take(3)
    HomeGroupCard {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Timeline, contentDescription = null, tint = Primer.IconPrimary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.label_in_progress),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Primer.TextPrimary,
            )
        }
        TodoDivider()
        shown.forEachIndexed { index, t ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        t.title,
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Primer.TextPrimary,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (t.progress in 0..100) "${t.progress}%" else stringResource(R.string.label_running),
                        fontSize = 12.sp,
                        color = Primer.Blue500,
                    )
                }
                if (t.progress in 0..100) {
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(Primer.Gray150),
                    ) {
                        Box(
                            Modifier.fillMaxWidth(t.progress / 100f).height(5.dp)
                                .clip(RoundedCornerShape(3.dp)).background(Primer.Blue500),
                        )
                    }
                }
                if (t.detail.isNotBlank()) {
                    Text(
                        t.detail,
                        fontSize = 11.sp,
                        color = Primer.TextTertiary,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            if (index < shown.lastIndex) TodoDivider()
        }
    }
}

/**
 * 区块标题（图标 + 文字 + 可选右侧操作 + 可选长按）。
 *
 * `onLongClick` 目前只有「常用仓库」用（长按 = 进自定义置顶页），并且**只挂在标题行**：
 * 需求明确说了「只让标题行长按」，卡片区要留给将来的「长按快捷操作」（打开仓库 /
 * 复制链接那类），现在把长按占掉的话以后就是两者的手势打架。
 *
 * 长按不配点击反馈：`indication = null` —— 点这一行本来什么都不做，
 * 给一个「有反馈但没反应」的水波纹比没有反馈更让人困惑。
 */
@Composable
private fun SectionHeader(
    title: String,
    icon: ImageVector,
    /** 区块图标的语义色：牌底由它派生（同色 12%），调用处不传色值。 */
    tint: TintRole,
    onAction: (() -> Unit)? = null,
    actionIcon: ImageVector = Icons.Filled.Refresh,
    actionLabel: String = stringResource(R.string.action_refresh),
    onLongClick: (() -> Unit)? = null,
) {
    val press = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 6.dp)
            .combinedClickable(
                interactionSource = press,
                indication = null,
                onClick = {},
                onLongClick = onLongClick,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TintChip(icon, tint)
        Spacer(Modifier.width(8.dp))
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary)
        Spacer(Modifier.weight(1f))
        if (onAction != null) {
            Icon(
                actionIcon,
                contentDescription = actionLabel,
                tint = Primer.IconSecondary,
                modifier = Modifier.size(18.dp).iconTap { onAction() },
            )
        }
    }
}

/** 仓库卡片 */
@Composable
private fun RepoCard(repo: RepoSummary, onClick: () -> Unit = {}) {
    Column(
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, bottom = 6.dp)
            .fillMaxWidth()
            // 与 HomeGroupCard 同一套容器：白面 + 阴影。浅色下同色不同层靠阴影，
            // 深色下 BackgroundSecondary 就是 #161B22，阴影不可见也不影响分层。
            .shadow(HomeCardElevation, HomeCardShape)
            .clip(HomeCardShape)
            .background(Primer.BackgroundSecondary)
            .clickable { onClick() }
            // 竖向比横向收得更狠：留白是这张卡最高的成本项，横向再收会让文字贴边
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                repo.fullName,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Primer.Blue500,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // fill = false：名字短的时候徽标紧跟着名字，而不是被 weight 推到屏幕最右
                modifier = Modifier.weight(1f, fill = false),
            )
            RepoBadges(repo)
        }
        if (repo.desc.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                repo.desc,
                fontSize = 12.sp,
                // 显式写行高：12sp 走默认（≈16.2sp）会让两行描述多出 2dp；15sp 是 1.25 倍
                lineHeight = 15.sp,
                color = Primer.TextSecondary,
                // 描述只做提示，不值得为第 3 行撑高整张卡；点进去看全
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            // 没有描述也是信息：留一行居中的灰字占位，卡片节奏不断档。
            // 真实数据里 GitHub 的 description 大量是 null（见 parseRepoList 的 isNull 判据），
            // 这一分支不是理论情况 —— 首页第一屏就会碰到。
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.state_no_repo_description),
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = Primer.TextTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (repo.language != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(langColor(repo.language)))
                    Spacer(Modifier.width(4.dp))
                    Text(repo.language, fontSize = 12.sp, color = Primer.TextSecondary)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Star, contentDescription = stringResource(R.string.label_stars), tint = Primer.IconSecondary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(2.dp))
                Text(repo.stars, fontSize = 12.sp, color = Primer.TextSecondary)
            }
            if (repo.forks != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CallSplit, contentDescription = stringResource(R.string.action_fork), tint = Primer.IconSecondary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(2.dp))
                    Text(repo.forks, fontSize = 12.sp, color = Primer.TextSecondary)
                }
            }
        }
    }
}

/**
 * 仓库徽标（私有 / 复刻）。两个都不是时不占位：连那 6dp 间隔都不加。
 *
 * 用「描边 + 次级文字」而不是彩底：私有 / 复刻是仓库的**属性**，不是状态。
 * 彩底会和真正表示状态的色块（成功 / 警告 / 危险 / 选中蓝）抢读法，
 * 而这里一屏最多出现两次，安静地把信息给到就够了。
 *
 * 首页卡片与管理页列表行共用（同包 internal）。
 */
@Composable
internal fun RepoBadges(repo: RepoSummary) {
    if (!repo.isPrivate && !repo.isFork) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (repo.isPrivate) RepoBadge(stringResource(R.string.label_repo_private))
            // 复刻沿用仓库页动作按钮的文案（同一条资源，两种语言下都念得通）
            if (repo.isFork) RepoBadge(stringResource(R.string.action_fork))
        }
    }
}

@Composable
private fun RepoBadge(text: String) {
    val shape = RoundedCornerShape(4.dp)
    Text(
        text = text,
        fontSize = 10.sp,
        color = Primer.TextSecondary,
        modifier = Modifier
            .clip(shape)
            .border(1.dp, Primer.Border, shape)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** 活动项 */
@Composable
private fun ActivityItem(act: Activity) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Primer.BackgroundSecondary)
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(Primer.BackgroundPrimary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(act.icon, contentDescription = null, tint = Primer.IconPrimary, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(act.text.resolve(context), fontSize = 13.sp, color = Primer.TextSecondary)
            Spacer(Modifier.height(3.dp))
            Text(relativeTime(act.createdAt), fontSize = 11.sp, color = Primer.TextTertiary)
        }
    }
}

/** 空态 */
@Composable
private fun EmptyState(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Primer.TextTertiary, fontSize = 13.sp)
    }
}

/** 仓库摘要（来自 `/user/repos` 或 `/user/starred`；首页与管理页共用，故 internal） */
internal data class RepoSummary(
    val fullName: String,
    val desc: String,
    val language: String?,
    val stars: String,
    val forks: String? = null,
    /** 私有仓库（`private`）：同一列里混着组织仓库与别人的仓库，用户需要一眼看出「这条谁能打开」。 */
    val isPrivate: Boolean = false,
    /** 复刻（`fork`）：复刻的「最近推送」常来自上游，和自有仓库是两回事。 */
    val isFork: Boolean = false,
)

/** 活动（来自 received_events） */
private data class Activity(
    val icon: ImageVector,
    /** 模型只带「资源 ID + 参数」，渲染时才按当前语言解析（见 [LocalizedText]）。 */
    val text: LocalizedText,
    /** 事件的 ISO 时间。相对时间在**渲染时**算，否则语言切换后旧文案会停在内存里。 */
    val createdAt: String,
)

/** GitHub 语言色映射（首页与管理页共用，故 internal） */
internal fun langColor(lang: String?): Color = when (lang) {
    "Kotlin" -> Color(0xFFA97BFF)
    "Rust" -> Color(0xFFDEA584)
    "Java" -> Color(0xFFB07219)
    "Python" -> Color(0xFF3572A5)
    "JavaScript" -> Color(0xFFF1E05A)
    "TypeScript" -> Color(0xFF3178C6)
    "Go" -> Color(0xFF00ADD8)
    "Swift" -> Color(0xFFF05138)
    "C++" -> Color(0xFFF34B7D)
    "C" -> Color(0xFF555555)
    "Dart" -> Color(0xFF00B4AB)
    else -> Color(0xFF8B949E)
}