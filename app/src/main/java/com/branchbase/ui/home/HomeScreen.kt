package com.branchbase.ui.home

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import com.branchbase.R
import com.branchbase.core.AccountStore
import com.branchbase.core.RustBridge
import com.branchbase.ui.LocalizedText
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
    var unreadNotifs by remember { mutableStateOf(0) }
    var reviewRequests by remember { mutableStateOf(0) }
    var assignedIssues by remember { mutableStateOf(0) }
    var runningTasks by remember { mutableStateOf<List<com.branchbase.ui.task.TaskRecord>>(emptyList()) }

    // 仪表盘计数缓存（TTL 5 分钟）。星标/活动仍走各自的 prefs 缓存，不受影响。
    val cacheManager = remember(context) {
        SearchCacheManager(SearchCacheDatabase.getInstance(context).searchCacheDao())
    }

    /**
     * 计数类请求的「缓存直出 + 回源写回」。
     *
     * 语义与改动前一致：失败静默为 0、不打扰首页；区别只在**回源前先读缓存**，
     * 未过期（5 分钟）时 [PageCache.refresh] 直接返回缓存、不再发请求。
     * 每次回到前台仍会走一遍这里（本函数无 force 入口），命中未过期缓存即免流量。
     *
     * 解析与改动前逐一对应：
     * - 通知计数 = `/notifications` 返回数组的长度；
     * - 评审/指派计数 = 搜索接口返回对象的 `total_count`。
     */
    suspend fun loadCachedCount(name: String, fetch: suspend () -> String?): Int {
        val json = PageCache.refresh(cacheManager, PageCache.homeKey(name), PageCache.TYPE_HOME) {
            withContext(Dispatchers.IO) { fetch() }
        } ?: return 0
        return runCatching {
            if (json.trimStart().startsWith("[")) {
                org.json.JSONArray(json).length()
            } else {
                JSONObject(json).optInt("total_count", 0)
            }
        }.getOrDefault(0)
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
            // 待处理三件套（都是轻量请求，失败静默为 0，不打扰首页）
            // 每个计数各自 key（home:unread / home:review / home:assigned），类型 TYPE_HOME（TTL 5 分钟）
            // 消息首屏预取（需求 ③）：原来这里请求 `/notifications?per_page=100` **只为了数长度**，
            // 而消息页进去还要再请求一次首屏 —— 同一份数据两条腿各拉一遍。
            // 现在这一步把首屏做完整：写 PageCache（落盘）+ 填 NotifSnapshot（已解析），
            // 未读数也从同一份数据算出，于是「进消息页」= 0 次网络 + 0 帧骨架。
            // 被策略跳过（计费网络 / 关闭了预加载开关）时返回 null，回退到原来的计数路径。
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Primer.BackgroundPrimary),
    ) {
        // 顶部：搜索栏 + 头像
        SearchBarRow(login = login, avatarUrl = avatarUrl, onProfileClick = onProfileClick, onSearchClick = onSearchClick)

        // 内容：仪表盘式分区（待处理 → 进行中 → 常用 → 浏览）
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item { GreetingRow(login) }

            // ① 待处理：需要你动手的
            item { SectionHeader(stringResource(R.string.label_todo), Icons.Filled.Notifications) }
            item {
                TodoCard(
                    unread = unreadNotifs,
                    reviews = reviewRequests,
                    assigned = assignedIssues,
                    onOpenNotifications = onOpenNotifications,
                    onOpenSearch = onSearchClick,
                )
            }

            // ② 进行中：本地任务（零网络）
            if (runningTasks.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.label_in_progress), Icons.Filled.Timeline) }
                item { RunningTasksCard(runningTasks) }
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
                desc = obj.optString("description").orEmpty(),
                language = obj.optString("language").takeIf { it.isNotBlank() },
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
 * 待处理卡片：未读通知 / 待我审查 / 分配给我。
 *
 * 三行都是「需要你动手的」，全为 0 时显示空态而不是隐藏整块 —— 保持首屏结构稳定。
 */
@Composable
private fun TodoCard(
    unread: Int,
    reviews: Int,
    assigned: Int,
    onOpenNotifications: () -> Unit,
    onOpenSearch: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, Primer.BorderEmphasis, RoundedCornerShape(10.dp)),
    ) {
        if (unread + reviews + assigned == 0) {
            Box(Modifier.fillMaxWidth().padding(vertical = 22.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.state_no_todo), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextSecondary)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.note_todo_hint), fontSize = 11.5.sp, color = Primer.TextTertiary)
                }
            }
            return@Column
        }
        TodoRow(stringResource(R.string.label_unread_notifications), stringResource(R.string.label_unread_count, unread), Primer.InfoSurfaceSoft, Primer.Blue500, unread > 0, onOpenNotifications)
        TodoRow(stringResource(R.string.label_awaiting_review), stringResource(R.string.label_review_pr_count, reviews), Primer.SuccessSurface, Primer.Green500, reviews > 0, onOpenSearch)
        TodoRow(
            stringResource(R.string.label_assigned_to_me), stringResource(R.string.label_assigned_issue_count, assigned), Primer.WarningSurface, Primer.WarningTextStrong,
            assigned > 0, onOpenSearch, last = true,
        )
    }
}

@Composable
private fun TodoRow(
    title: String,
    value: String,
    iconBg: Color,
    iconFg: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    last: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(iconBg),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(iconFg))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) Primer.TextPrimary else Primer.TextTertiary,
            )
            Text(value, fontSize = 11.sp, color = Primer.TextTertiary, modifier = Modifier.padding(top = 1.dp))
        }
        Text("›", fontSize = 15.sp, color = Primer.TextTertiary)
    }
    if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(Primer.BorderEmphasis.copy(alpha = 0.4f)))
}

/** 进行中任务卡片（数据来自本地 TaskStore，零网络）。 */
@Composable
private fun RunningTasksCard(tasks: List<com.branchbase.ui.task.TaskRecord>) {
    val shown = tasks.take(3)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, Primer.BorderEmphasis, RoundedCornerShape(10.dp)),
    ) {
        shown.forEachIndexed { index, t ->
            Column(Modifier.padding(horizontal = 12.dp, vertical = 11.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        t.title,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Primer.TextPrimary,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (t.progress in 0..100) "${t.progress}%" else stringResource(R.string.label_running),
                        fontSize = 11.sp,
                        color = Primer.Blue500,
                    )
                }
                if (t.progress in 0..100) {
                    Spacer(Modifier.height(7.dp))
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
                        modifier = Modifier.padding(top = 5.dp),
                    )
                }
            }
            if (index < shown.lastIndex) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Primer.BorderEmphasis.copy(alpha = 0.4f)))
            }
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
    onAction: (() -> Unit)? = null,
    actionIcon: ImageVector = Icons.Filled.Refresh,
    actionLabel: String = stringResource(R.string.action_refresh),
    onLongClick: (() -> Unit)? = null,
) {
    val press = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)
            .combinedClickable(
                interactionSource = press,
                indication = null,
                onClick = {},
                onLongClick = onLongClick,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Primer.IconPrimary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
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
            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Primer.BackgroundSecondary)
            .clickable { onClick() }
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                repo.fullName,
                fontSize = 15.sp,
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
            Spacer(Modifier.height(4.dp))
            Text(repo.desc, fontSize = 13.sp, color = Primer.TextSecondary)
        }
        Spacer(Modifier.height(10.dp))
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