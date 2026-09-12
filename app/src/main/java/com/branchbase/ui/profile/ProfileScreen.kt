package com.branchbase.ui.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import com.branchbase.ui.theme.selectionColor
import com.branchbase.cache.PageCache
import com.branchbase.cache.SearchCacheDatabase
import com.branchbase.cache.SearchCacheManager
import com.branchbase.core.AvatarCache
import com.branchbase.ui.navigation.PageLevel
import com.branchbase.ui.navigation.PageSwitcher
import com.branchbase.ui.navigation.TabSwitcher
import kotlinx.coroutines.launch
import com.branchbase.core.AccountStore
import com.branchbase.core.RustBridge
import com.branchbase.ui.log.LogScreen
import com.branchbase.ui.log.Logger
import com.branchbase.ui.theme.iconTap
import com.branchbase.ui.theme.LanguageColors
import com.branchbase.ui.theme.Avatar
import com.branchbase.ui.theme.Primer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.branchbase.ui.theme.ElementMotion
import com.branchbase.ui.theme.bubbleEnter
import com.branchbase.ui.theme.bubbleExit
import com.branchbase.ui.theme.rememberPressFeedback
import com.branchbase.ui.theme.ProfileColors
import org.json.JSONArray
import org.json.JSONObject

/**
 * 个人主页 Profile（对标 GitHub Profile 信息架构）。
 *
 * 概览 / 仓库 / 动态 三个主页面，由底部气泡导航栏（基础形态 ④）切换；
 * More 菜单（星标/项目/任务/设置 + 登出）收纳到手柄弹出的气泡中。
 * 动态页的贡献图与雷达图为 🔧 本地渲染解析。
 */
@Composable
fun ProfileScreen(
    sessionJson: String,
    onBack: () -> Unit,
    onLogout: () -> Unit,
    onOpenRepo: (String) -> Unit,
) {
    val loggedProfile = remember { mutableStateOf(false) }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        if (!loggedProfile.value) {
            loggedProfile.value = true
            Logger.ui("进入个人主页", "Compose")
        }
    }
    // 共享仓库数据：个人页加载一次，Overview/Repositories 复用（避免重复请求）
    val token = runCatching { JSONObject(sessionJson).getJSONObject("token").optString("access_token") }.getOrNull() ?: ""
    val host = runCatching { JSONObject(sessionJson).optString("host", "github.com") }.getOrDefault("github.com")
    var repos by remember { mutableStateOf<List<RepoItem>>(emptyList()) }
    var reposLoading by remember { mutableStateOf(true) }
    val user = runCatching { JSONObject(sessionJson).getJSONObject("user") }.getOrNull()
    // login 兜底顺序：session.user.login → 当前账号（多账号表）→ 空
    // （OAuth 交换的 session 原本只有 token，user 由 LoginViewModel 登录后补全）
    val accountLogin = remember { AccountStore.currentLogin(context) }
    val login = user?.optString("login")?.takeIf { it.isNotBlank() && it != "null" }
        ?: accountLogin.takeIf { it.isNotBlank() }
        ?: ""
    // 个人主页缓存（仓库列表 / 贡献日历 / 活动，TTL 10 分钟）
    val cacheManager = remember(context) {
        SearchCacheManager(SearchCacheDatabase.getInstance(context).searchCacheDao())
    }

    /**
     * 我的仓库列表（`/user/repos`）。
     *
     * 保持原来的**单次请求**（不翻页）：翻页会让首次进入从 1 次请求变成最多 5 次，
     * 与「加缓存是为了更快」相悖。这里只做「先直出缓存 → 再回源」。
     */
    suspend fun loadRepos() {
        val key = if (login.isBlank()) null else PageCache.profileKey(login, "repos")

        // ① 先直出缓存（含过期数据）
        if (key != null) {
            PageCache.cachedFirst(cacheManager, key, PageCache.TYPE_PROFILE)?.let { cached ->
                parseRepos(cached).takeIf { it.isNotEmpty() }?.let {
                    repos = it
                    reposLoading = false
                }
            }
        }

        // ② 回源并写回
        val json = if (key != null) {
            PageCache.refresh(cacheManager, key, PageCache.TYPE_PROFILE) {
                RustBridge.getMyRepos(host, token)
            }
        } else {
            RustBridge.getMyRepos(host, token)?.takeIf { !it.startsWith("ERROR:") }
        }
        json?.let { repos = parseRepos(it) }
        Logger.net("GET /user/repos → ${if (json == null) "失败/空" else "200（${repos.size} 个仓库）"}", "GitHubAPI")
    }

    LaunchedEffect(Unit) {
        loadRepos()
        reposLoading = false
    }
    val name = user?.optString("name")?.takeIf { it.isNotBlank() && it != "null" }
    val avatarUrl = user?.optString("avatar_url")?.takeIf { it.isNotBlank() && it != "null" }
    val bio = user?.optString("bio")?.takeIf { it.isNotBlank() && it != "null" }
    val followers = user?.optLong("followers") ?: 0L
    val following = user?.optLong("following") ?: 0L
    val publicRepos = user?.optLong("public_repos") ?: 0L

    var tab by remember { mutableStateOf(ProfileTab.Overview) }
    var subPage by remember { mutableStateOf<SubPage?>(null) }

    // 唯一路由：子页栈（depth>0）或主页三 Tab（depth=0）。
    // 原来是 `if (currentSubPage != null) { when(...); return }` —— 状态一变整棵树换掉、无过渡；
    // 现在交给 PageSwitcher：进子页从右滑入、返回向右滑出，主页三个 Tab 之间淡入淡出。
    val route: ProfileRoute = subPage?.let { ProfileRoute.Sub(it) } ?: ProfileRoute.Main(tab)

    // 子页面跳转时拦截系统返回，返回个人主页（按路由启用，动画期间不会重复响应）
    BackHandler(subPage != null) { subPage = null }

    PageSwitcher(state = route, modifier = Modifier.fillMaxSize(), label = "profile-page") { r ->
        when (r) {
            is ProfileRoute.Sub -> when (r.page) {
                SubPage.Stars -> StarsScreen(sessionJson, onBack = { subPage = null }, onOpenRepo = onOpenRepo)
                SubPage.Projects -> ProjectsScreen(sessionJson, onBack = { subPage = null })
                SubPage.Settings -> SettingsScreen(onBack = { subPage = null }, onOpenLocalRepo = { subPage = SubPage.LocalRepo }, onOpenAbout = { subPage = SubPage.About }, onOpenLog = { subPage = SubPage.Log }, onOpenNotificationSettings = { subPage = SubPage.NotificationSettings }, onOpenTranslate = { subPage = SubPage.Translate }, onOpenAccounts = { subPage = SubPage.Accounts }, onOpenCommitMode = { subPage = SubPage.CommitMode })
                SubPage.LocalRepo -> LocalRepoScreen(sessionJson, onBack = { subPage = SubPage.Settings })
                SubPage.About -> AboutScreen(onBack = { subPage = SubPage.Settings })
                SubPage.Log -> LogScreen(onBack = { subPage = SubPage.Settings })
                SubPage.NotificationSettings -> NotificationSettingsScreen(onBack = { subPage = SubPage.Settings })
                SubPage.Translate -> TranslateSettingsScreen(onBack = { subPage = SubPage.Settings })
                SubPage.Tasks -> com.branchbase.ui.task.TaskScreen(onBack = { subPage = null })
                SubPage.Accounts -> AccountsScreen(onBack = { subPage = SubPage.Settings }, onAdd = onLogout)
                SubPage.CommitMode -> CommitModeScreen(onBack = { subPage = SubPage.Settings })
                SubPage.EditProfile -> ProfileEditScreen(sessionJson, onBack = { subPage = null }, onSaved = { subPage = null })
            }

            is ProfileRoute.Main -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Primer.BackgroundPrimary)
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            ) {
                // 顶部导航
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Primer.IconPrimary, modifier = Modifier.size(24.dp).iconTap { onBack() })
                    Spacer(Modifier.width(4.dp))
                    Text(login, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
                }

                // 内容（随底部气泡导航栏切换，weight 占据剩余空间）
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    TabSwitcher(
                        state = r.tab,
                        modifier = Modifier.fillMaxSize(),
                        label = "profile-tab",
                    ) { t ->
                        when (t) {
                            ProfileTab.Overview -> ProfileOverview(login, name, avatarUrl, bio, followers, following, publicRepos, repos, reposLoading, onOpenRepo, onEdit = { subPage = SubPage.EditProfile })
                            ProfileTab.Repositories -> ProfileRepositories(repos, reposLoading, onOpenRepo)
                            ProfileTab.Activity -> ProfileActivity(host, token, login)
                        }
                    }
                }

                // 气泡导航栏（基础形态 ④）：3 主项 + 右侧手柄弹出 More 菜单
                ProfileBubbleNavigationBar(
                    selected = r.tab,
                    onSelect = { tab = it; Logger.ui("切换到「${it.label}」", "Compose") },
                    onLogout = onLogout,
                    onNavigate = { subPage = it; Logger.ui("打开「${it.label}」", "Compose") },
                )
            }
        }
    }
}

/**
 * 个人页路由。
 *
 * 层级：主页三 Tab（0）→ 一级子页（1：星标 / 项目 / 任务 / 编辑资料 / 设置）→
 * 设置的下级页（2：本地仓库 / 关于 / 日志 / 通知设置 / 沉浸式翻译 / 账号 / 提交模式）。
 * 这样「设置 → 关于」是推进，「关于 → 设置」是返回，方向都对得上用户的操作。
 */
private sealed interface ProfileRoute : PageLevel {

    data class Main(val tab: ProfileTab) : ProfileRoute {
        override val depth: Int get() = 0
    }

    data class Sub(val page: SubPage) : ProfileRoute {
        override val depth: Int get() = when (page) {
            SubPage.LocalRepo, SubPage.About, SubPage.Log, SubPage.NotificationSettings,
            SubPage.Translate, SubPage.Accounts, SubPage.CommitMode,
            -> 2

            else -> 1
        }
    }
}

private enum class ProfileTab(val label: String, val icon: ImageVector) {
    Overview("概览", Icons.Filled.Person),
    Repositories("仓库", Icons.Filled.Folder),
    Activity("动态", Icons.Filled.Timeline),
}

// ───────────────────────── Overview 页 ─────────────────────────

@Composable
private fun ProfileOverview(
    login: String,
    name: String?,
    avatarUrl: String?,
    bio: String?,
    followers: Long,
    following: Long,
    publicRepos: Long,
    repos: List<RepoItem>,
    loading: Boolean,
    onOpenRepo: (String) -> Unit,
    onEdit: () -> Unit,
) {
    val pinnedRepos = repos.sortedByDescending { it.stars }.take(4)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 长按头像强制刷新后 +1，驱动 Avatar 重读本地缓存
    var avatarTick by remember { mutableIntStateOf(0) }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        // 用户信息
        item {
            Column(Modifier.fillMaxWidth().padding(16.dp, 16.dp, 16.dp, 12.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Avatar(
                        url = avatarUrl,
                        login = login,
                        size = 64.dp,
                        version = avatarTick,
                        // 长按头像 → 强制重新拉取（网页端换头像后手动刷新）
                        modifier = Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = {
                                scope.launch {
                                    val ok = AvatarCache.refresh(context, login, avatarUrl)
                                    if (ok) {
                                        avatarTick++
                                        Toast.makeText(context, "头像已刷新", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "刷新失败（检查网络）", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                        ),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(name ?: login, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary)
                        Spacer(Modifier.height(2.dp))
                        Text("@$login", fontSize = 16.sp, fontWeight = FontWeight.Light, color = Primer.TextSecondary)
                    }
                }
                if (bio != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(bio, fontSize = 14.sp, color = Primer.TextSecondary, lineHeight = 20.sp)
                }
                Spacer(Modifier.height(14.dp))
                Box(
                    Modifier.fillMaxWidth().height(32.dp).clip(RoundedCornerShape(6.dp)).background(Primer.Gray150)
                        .border(1.dp, Primer.Border, RoundedCornerShape(6.dp)).clickable { onEdit() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("编辑资料", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Primer.TextPrimary)
                }
                Spacer(Modifier.height(10.dp))
                Row {
                    Text("${followers} 关注者", fontSize = 14.sp, color = Primer.TextSecondary)
                    Text(" · ", fontSize = 14.sp, color = Primer.TextSecondary)
                    Text("${following} 正在关注", fontSize = 14.sp, color = Primer.TextSecondary)
                    Text(" · ", fontSize = 14.sp, color = Primer.TextSecondary)
                    Text("${publicRepos} 仓库", fontSize = 14.sp, color = Primer.TextSecondary)
                }
            }
        }
        // 热门仓库
        item {
            SectionTitle("热门仓库", "自定义置顶")
            Column(Modifier.padding(horizontal = 16.dp)) {
                if (loading) {
                    Text("加载中…", fontSize = 13.sp, color = Primer.TextTertiary)
                } else if (pinnedRepos.isEmpty()) {
                    Text("暂无置顶仓库", fontSize = 13.sp, color = Primer.TextTertiary)
                } else {
                    pinnedRepos.forEach { repo -> RepoCard(repo, onClick = { onOpenRepo(repo.fullName) }) }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, sub: String? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
        Spacer(Modifier.weight(1f))
        if (sub != null) {
            Text(sub, fontSize = 12.sp, color = Primer.Blue500)
        }
    }
}

// ───────────────────────── Repositories 页 ─────────────────────────

@Composable
private fun ProfileRepositories(repos: List<RepoItem>, loading: Boolean, onOpenRepo: (String) -> Unit) {
    var filter by remember { mutableStateOf("全部") }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // 搜索框
        Box(Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(6.dp)).background(Primer.Gray150).border(1.dp, Primer.Border, RoundedCornerShape(6.dp)).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
            Text("🔍 查找仓库…", fontSize = 13.sp, color = Primer.TextTertiary)
        }
        Spacer(Modifier.height(10.dp))
        // 语言筛选
        val langs = listOf("全部", "Kotlin", "Shell", "Python", "C++")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            langs.forEach { lang ->
                FilterChip(lang, selected = filter == lang) { filter = lang }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("加载中…", color = Primer.TextTertiary)
            }
        } else {
            LazyColumn {
                items(repos.filter { filter == "全部" || it.language == filter }) { repo ->
                    RepoCard(repo, onClick = { onOpenRepo(repo.fullName) })
                }
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(14.dp))
            .background(selectionColor(selected, on = Primer.Blue500, off = Primer.Gray150))
            .border(
                1.dp,
                selectionColor(selected, on = Primer.Blue500, off = Primer.Border),
                RoundedCornerShape(14.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(label, fontSize = 12.sp, color = selectionColor(selected, on = Color.White, off = Primer.TextSecondary))
    }
}

// ───────────────────────── Activity 页（真实事件数据） ─────────────────────────

/** 动态事件（由 /users/{login}/received_events 解析）。 */
internal data class ActivityEvent(
    val type: String,
    val repo: String,
    val detail: String,
    val createdAt: Long,
)

private fun parseEvents(json: String?): List<ActivityEvent> {
    if (json.isNullOrBlank() || json.startsWith("ERROR:")) return emptyList()
    return runCatching {
        val arr = JSONArray(json)
        val seen = HashSet<String>()
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                // 分页拉取可能重叠，按事件 id 去重
                val id = o.optString("id")
                if (id.isNotBlank() && !seen.add(id)) continue
                val type = o.optString("type")
                val repo = o.optJSONObject("repo")?.optString("name").orEmpty()
                val payload = o.optJSONObject("payload")
                val detail = when (type) {
                    // events API 的 PushEvent payload 被裁剪，只有 ref/head/before，
                    // 没有 size / commits（旧实现读 size 恒为 0，显示「推送了 0 个提交」）
                    "PushEvent" -> {
                        val ref = payload?.optString("ref").orEmpty()
                            .removePrefix("refs/heads/")
                            .removePrefix("refs/tags/")
                        val head = payload?.optString("head").orEmpty().take(7)
                        when {
                            ref.isNotBlank() && head.isNotBlank() -> "推送到 $ref · $head"
                            ref.isNotBlank() -> "推送到 $ref"
                            else -> "推送了代码"
                        }
                    }
                    "CreateEvent" -> {
                        val kind = when (payload?.optString("ref_type").orEmpty()) {
                            "branch" -> "分支"
                            "tag" -> "标签"
                            "repository" -> "仓库"
                            else -> "内容"
                        }
                        val name = payload?.optString("ref").orEmpty()
                        if (name.isNotBlank()) "创建了$kind $name" else "创建了$kind"
                    }
                    "DeleteEvent" -> {
                        val kind = when (payload?.optString("ref_type").orEmpty()) {
                            "branch" -> "分支"
                            "tag" -> "标签"
                            else -> "引用"
                        }
                        val name = payload?.optString("ref").orEmpty()
                        if (name.isNotBlank()) "删除了$kind $name" else "删除了$kind"
                    }
                    "WatchEvent" -> "星标了仓库"
                    "ForkEvent" -> "复刻了仓库"
                    "IssueCommentEvent" -> "评论了 issue #${payload?.optJSONObject("issue")?.optInt("number") ?: 0}"
                    // 编号在 payload 顶层（payload.pull_request.number 未必存在）
                    "PullRequestEvent" -> {
                        val n = payload?.optInt("number") ?: payload?.optJSONObject("pull_request")?.optInt("number") ?: 0
                        val action = when (payload?.optString("action").orEmpty()) {
                            "opened" -> "打开"
                            "closed" -> "关闭"
                            "reopened" -> "重新打开"
                            else -> payload?.optString("action").orEmpty()
                        }
                        "拉取请求 $action #$n"
                    }
                    "PullRequestReviewEvent" -> "审查了拉取请求"
                    "ReleaseEvent" -> "发布了 ${payload?.optJSONObject("release")?.optString("tag_name").orEmpty()}"
                    "PublicEvent" -> "公开了仓库"
                    "IssuesEvent" -> "issue ${payload?.optString("action").orEmpty()}"
                    "MemberEvent" -> "添加了协作者"
                    "GollumEvent" -> "更新了 wiki"
                    else -> type.removeSuffix("Event")
                }
                add(ActivityEvent(type, repo, detail, parseIsoTime(o.optString("created_at"))))
            }
        }
    }.getOrDefault(emptyList())
}

private fun parseIsoTime(s: String): Long = runCatching {
    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }.parse(s)?.time ?: 0L
}.getOrDefault(0L)

private fun eventIcon(type: String) = when (type) {
    "PushEvent" -> "⇧"
    "CreateEvent" -> "＋"
    "DeleteEvent" -> "−"
    "WatchEvent" -> "★"
    "ForkEvent" -> "⑂"
    "IssueCommentEvent", "IssuesEvent" -> "◉"
    "PullRequestEvent", "PullRequestReviewEvent" -> "⇄"
    "ReleaseEvent" -> "◆"
    else -> "•"
}

private fun relativeTime(ms: Long): String {
    if (ms <= 0) return ""
    val diff = System.currentTimeMillis() - ms
    val min = diff / 60000
    return when {
        min < 1 -> "刚刚"
        min < 60 -> "${min} 分钟前"
        min < 1440 -> "${min / 60} 小时前"
        min < 43200 -> "${min / 1440} 天前"
        else -> "${min / 43200} 个月前"
    }
}

@Composable
private fun ProfileActivity(host: String, token: String, login: String) {
    val context = LocalContext.current
    // 动态页专属缓存管理器（活动的每页 + 贡献日历，TTL 10 分钟）
    val cacheManager = remember(context) {
        SearchCacheManager(SearchCacheDatabase.getInstance(context).searchCacheDao())
    }
    var events by remember { mutableStateOf<List<ActivityEvent>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // 贡献墙（GraphQL 52 周；失败降级为事件近似并标注范围）
    var calendar by remember { mutableStateOf<ContributionCalendar?>(null) }
    var calLoading by remember { mutableStateOf(true) }
    var calDegraded by remember { mutableStateOf<String?>(null) }
    var selectedDay by remember { mutableStateOf<ContributionDay?>(null) }

    /**
     * 拉取事件流（最多 3 页 = 300 条，GitHub events 的硬上限）。
     *
     * 缓存按页各存一份，key 里带上事件源（`/user/events` 与 `/users/{login}/events`
     * 权限不同、结果不同，不能共用一份缓存），类型 TYPE_PROFILE（TTL 10 分钟）；
     * 未加载过的页没有缓存 → 照旧回源，不做预取。
     */
    suspend fun fetchEventPages(path: String): List<ActivityEvent> {
        val all = mutableListOf<ActivityEvent>()
        for (page in 1..3) {
            val key = PageCache.profileKey(login, "events:$path:$page")
            val pageJson = PageCache.refresh(cacheManager, key, PageCache.TYPE_PROFILE) {
                RustBridge.getJson(host, token, "$path?per_page=100&page=$page")
            } ?: break
            if (pageJson.startsWith("ERROR:")) break
            val batch = parseEvents(pageJson)
            if (batch.isEmpty()) break
            all += batch
            if (batch.size < 100) break
        }
        return all
    }

    LaunchedEffect(login) {
        loading = true
        error = null
        if (login.isBlank()) {
            error = "未获取到登录名，请重新登录"
            loading = false
            return@LaunchedEffect
        }
        // 数据源：`/user/events` 是「认证用户自己的活动」（含私有仓库），
        // `/users/{login}/events` 是「该用户的公开活动」。
        // 注意不要用 received_events —— 那是「你关注的人的活动」feed，通常为空。
        val isSelf = login == AccountStore.currentLogin(context)
        var source = if (isSelf) "/user/events" else "/users/$login/events"
        var parsed = fetchEventPages(source)
        if (parsed.isEmpty()) {
            // 当前用户端点没数据时回退到公开事件端点
            val fallback = if (isSelf) "/users/$login/events" else "/user/events"
            val retry = fetchEventPages(fallback)
            if (retry.isNotEmpty()) {
                source = fallback
                parsed = retry
            }
        }
        if (parsed.isEmpty()) {
            error = "无法加载动态（网络或权限受限）"
        } else {
            events = parsed
            Logger.net("GET $source → ${events.size} 条", "GitHubAPI")
        }
        loading = false
    }

    // 贡献日历：GraphQL contributionsCollection（REST 拿不到 52 周）
    LaunchedEffect(login) {
        calLoading = true
        val range = contributionRange()
        // key 按查询区间分段（profileKey(login, "calendar:$from:$to")），类型 TYPE_PROFILE
        val key = PageCache.profileKey(login, "calendar:${range.first}:${range.second}")
        // ① 先直出缓存（含过期数据）
        val cachedJson = PageCache.cachedFirst(cacheManager, key, PageCache.TYPE_PROFILE)
        if (cachedJson != null) {
            parseContributionCalendar(cachedJson)?.let { calendar = it }
        }
        // ② 回源：TTL（10 分钟）内 refresh 直接返回缓存、不发请求；GraphQL 失败时 Rust 侧返回 `ERROR:` 串，
        // refresh 会挡掉且不覆盖旧缓存（返回 null）→ 此时回退到刚才直出的内容。
        val freshJson = PageCache.refresh(cacheManager, key, PageCache.TYPE_PROFILE) {
            RustBridge.contributionCalendar(host, token, login, range.first, range.second)
        }
        val parsed = parseContributionCalendar(freshJson ?: cachedJson)
        if (parsed != null) {
            calendar = parsed
            Logger.net("POST /graphql contributionsCollection($login) → ${parsed.total} 次贡献", "GraphQL")
        } else {
            Logger.net("POST /graphql 不可用，降级为 received_events 近似", "GraphQL")
        }
        calLoading = false
    }

    // 降级：GraphQL 无权限/失败时，用已加载的公开事件近似（仅近 13 周）
    LaunchedEffect(calendar, events, loading, calLoading) {
        if (calendar == null && !calLoading && !loading && events.isNotEmpty()) {
            calendar = fallbackCalendarFromEvents(events, 13)
            calDegraded = "近 90 天公开活动"
        }
    }

    when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("加载中…", fontSize = 13.sp, color = Primer.TextTertiary)
        }
        error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(error!!, fontSize = 13.sp, color = Primer.TextTertiary)
        }
        events.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("暂无公开动态", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextSecondary)
                Spacer(Modifier.height(4.dp))
                Text("推送、星标、开 PR 等活动会显示在这里", fontSize = 12.sp, color = Primer.TextTertiary)
            }
        }
        else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            // 概览统计：用 GraphQL 贡献日历（精确到天）。
            // 事件流有 100/300 条上限，用它统计「近 7 天 / 30 天」会明显偏小。
            val stats = remember(calendar) { contributionStats(calendar) }
            SectionTitle("动态概览", if (stats != null) "按贡献日历" else null)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("近 7 天", "${stats?.week ?: 0}", Modifier.weight(1f))
                StatCard("近 30 天", "${stats?.month ?: 0}", Modifier.weight(1f))
                StatCard("近一年", "${stats?.year ?: 0}", Modifier.weight(1f))
            }

            // 贡献墙（52 周；GraphQL 优先，失败降级为事件近似）
            ContributionWall(
                calendar = calendar,
                loading = calLoading,
                error = if (calendar == null && !calLoading) "贡献数据不可用（可能是令牌缺少 read:user 权限）" else null,
                degradedNote = calDegraded,
                selectedDate = selectedDay?.date,
                onDaySelected = { selectedDay = it },
            )
            selectedDay?.let { day ->
                ContributionDayDetail(day)
            }

            // 类型分布（Top 5）
            val byType = events.groupingBy { it.type.removeSuffix("Event") }.eachCount()
                .entries.sortedByDescending { it.value }.take(5)
            if (byType.isNotEmpty()) {
                SectionTitle("活动类型分布")
                Column(Modifier.padding(horizontal = 16.dp)) {
                    val max = byType.first().value.coerceAtLeast(1)
                    byType.forEach { (label, count) ->
                        TypeBar(label, count, (count * 100 / max).coerceIn(4, 100))
                    }
                }
            }

            // 活动热力（按天聚合，13 周 = events API 的 90 天上限）
            SectionTitle("活动热力", "过去 90 天")
            Column(Modifier.padding(horizontal = 16.dp)) {
                ActivityHeatmap(events)
            }

            // 时间线
            SectionTitle("最近活动")
            Column(Modifier.padding(horizontal = 16.dp)) {
                events.take(30).forEach { e -> EventRow(e) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 贡献日历统计（近 7 天 / 30 天 / 全年），数据来自 GraphQL，精确到天。 */
private data class ContributionStats(val week: Int, val month: Int, val year: Int)

private fun contributionStats(calendar: ContributionCalendar?): ContributionStats? {
    val days = calendar?.days ?: return null
    if (days.isEmpty()) return null
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
    val dayMs = 24L * 60 * 60 * 1000
    val today = System.currentTimeMillis()
    var week = 0
    var month = 0
    days.forEach { d ->
        val t = runCatching { fmt.parse(d.date)?.time ?: 0L }.getOrDefault(0L)
        if (t <= 0L) return@forEach
        val ageDays = (today - t) / dayMs
        if (ageDays < 7) week += d.count
        if (ageDays < 30) month += d.count
    }
    return ContributionStats(week, month, calendar.total)
}

/** 贡献日历查询区间（近一年，ISO8601 UTC）。 */
private fun contributionRange(): Pair<String, String> {
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
    fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
    val now = System.currentTimeMillis()
    return fmt.format(java.util.Date(now - 364L * 24 * 60 * 60 * 1000)) to fmt.format(java.util.Date(now))
}

/** 贡献墙点选后的当天明细卡。 */
@Composable
private fun ContributionDayDetail(day: ContributionDay) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp)).background(Primer.Gray150)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(day.date, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
            Spacer(Modifier.weight(1f))
            Text(
                if (day.count > 0) "${day.count} 次贡献" else "无贡献",
                fontSize = 11.5.sp,
                color = if (day.count > 0) Primer.Green500 else Primer.TextTertiary,
            )
        }
        if (day.count == 0) {
            Spacer(Modifier.height(4.dp))
            Text("这一天没有公开贡献记录", fontSize = 11.5.sp, color = Primer.TextTertiary)
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, Primer.Border, RoundedCornerShape(8.dp))
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary)
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 11.sp, color = Primer.TextTertiary)
    }
}

@Composable
private fun TypeBar(label: String, count: Int, percent: Int) {
    Column(Modifier.padding(bottom = 10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextSecondary)
            Text("$count 次", fontSize = 11.5.sp, color = Primer.TextTertiary)
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth(percent / 100f).height(7.dp).clip(RoundedCornerShape(4.dp)).background(Primer.Blue500))
    }
}

/** 活动热力：按天聚合过去 13 周（91 天，events API 上限约 90 天）。 */
@Composable
private fun ActivityHeatmap(events: List<ActivityEvent>) {
    val weeks = 13
    val days = weeks * 7
    val levels = listOf(
        ProfileColors.ContributionL0, ProfileColors.ContributionL1,
        ProfileColors.ContributionL2, ProfileColors.ContributionL3, ProfileColors.ContributionL4,
    )
    val dayCounts = remember(events) {
        val dayMs = 24L * 60 * 60 * 1000
        val today = System.currentTimeMillis() / dayMs * dayMs
        val counts = IntArray(days)
        events.forEach { e ->
            if (e.createdAt > 0) {
                val idx = ((today - (e.createdAt / dayMs * dayMs)) / dayMs).toInt()
                if (idx in 0 until days) counts[days - 1 - idx]++
            }
        }
        counts
    }
    val max = (dayCounts.maxOrNull() ?: 0).coerceAtLeast(1)

    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, Primer.Border, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = 2.dp
            // 格子宽度自适应：13 周正好铺满一行（此前固定 9dp，只占约 40% 宽度）
            val cellW = (maxWidth - gap * (weeks - 1)) / weeks
            val cellH = 14.dp
            Canvas(Modifier.fillMaxWidth().height(cellH * 7 + gap * 6)) {
                val w = cellW.toPx()
                val h = cellH.toPx()
                val g = gap.toPx()
                val radius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
                dayCounts.forEachIndexed { idx, c ->
                    val col = idx / 7
                    val row = idx % 7
                    val lv = when {
                        c == 0 -> 0
                        c * 4 < max -> 1
                        c * 2 < max -> 2
                        c * 4 < max * 3 -> 3
                        else -> 4
                    }
                    drawRoundRect(
                        color = levels[lv],
                        topLeft = androidx.compose.ui.geometry.Offset(col * (w + g), row * (h + g)),
                        size = androidx.compose.ui.geometry.Size(w, h),
                        cornerRadius = radius,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "共 ${dayCounts.sum()} 次活动 · 最深 $max 次/天",
                fontSize = 11.5.sp,
                color = Primer.TextTertiary,
            )
            Spacer(Modifier.weight(1f))
            Text("少", fontSize = 9.5.sp, color = Primer.TextTertiary)
            levels.forEach { c ->
                Spacer(Modifier.width(3.dp))
                Box(Modifier.width(10.dp).height(10.dp).clip(RoundedCornerShape(2.dp)).background(c))
            }
            Spacer(Modifier.width(3.dp))
            Text("多", fontSize = 9.5.sp, color = Primer.TextTertiary)
        }
    }
}

@Composable
private fun EventRow(e: ActivityEvent) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(24.dp).clip(CircleShape).background(Primer.Gray150),
            contentAlignment = Alignment.Center,
        ) { Text(eventIcon(e.type), fontSize = 12.sp, color = Primer.TextSecondary) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${e.repo.ifBlank { "（未知仓库）" }} · ${e.detail}",
                fontSize = 13.sp,
                color = Primer.TextPrimary,
                lineHeight = 18.sp,
            )
            Text(relativeTime(e.createdAt), fontSize = 11.5.sp, color = Primer.TextTertiary)
        }
    }
}

// ───────────────────────── 气泡导航栏（基础形态 ④） ─────────────────────────

/**
 * More 气泡里的一个条目。
 *
 * ## 色彩（原来这里的图标清一色中性灰，是「不在设计色池里」的主要来源）
 *
 * 每一项按**用途**取 `Primer` 池内的语义色，并各配一枚同色 12% 的图标底：
 * 星标 = `Orange500`、项目 = `Purple500`、任务 = `Blue500`、设置 = `IconPrimary`、
 * 登出 = `Red500`（危险色，文字也同步用红）。这样一眼能区分「去哪」而不是「一排灰图标」。
 */
private data class MoreBubbleItem(
    val label: String,
    val icon: ImageVector,
    val tint: Color,
    val page: SubPage? = null,
    val danger: Boolean = false,
)

private val moreBubbleItems = listOf(
    MoreBubbleItem("星标", Icons.Filled.Star, Primer.Orange500, SubPage.Stars),
    MoreBubbleItem("项目", Icons.Filled.Dashboard, Primer.Purple500, SubPage.Projects),
    MoreBubbleItem("任务", Icons.Filled.Timeline, Primer.Blue500, SubPage.Tasks),
    MoreBubbleItem("设置", Icons.Filled.Settings, Primer.IconPrimary, SubPage.Settings),
)

/**
 * Profile 页切换导航：3 个主项 + 右侧圆形手柄，点手柄从**手柄上方**弹出 More 气泡。
 *
 * ## 与「当前设计」对齐的三处修正（原本不一致）
 *
 * 1. **手柄填充色**：原来用 `Primer.Border`（描边色，0xFFBFC1C9）当**填充**用，
 *    与设计里「中性面用 Gray150」的规则冲突；现在收起态是 `Gray150` + `Border` 描边，
 *    展开态是 `Blue500` 实心 + 白图标；
 * 2. **弹层不再是 Material 默认色**：原来用 `DropdownMenu`，容器色 / 文字色 / 图标色
 *    全走 Material 主题（既不是 Primer 池，也与 App 其它弹层不一致）。现在是
 *    `Popup` + `Primer.BackgroundPrimary` 白底 + `Primer.Border` 描边 + 16dp 圆角 + 阴影，
 *    与 `EdgeNavigationBar` 的气泡规格同源（文件头的「同源」注释这才成立）；
 * 3. **缩放原点**：Material 菜单是「从上边缘往下长」，而气泡是从手柄**向上**弹出，
 *    现在用 [bubbleEnter] 从右下角锚点缩放，观感上像是从手柄里冒出来。
 *
 * ## 动效
 *
 * - 手柄：按下缩到 0.94（120ms，跟手）+ 展开时三点图标旋转 90°（横三点 → 竖三点，暗示状态切换）；
 * - 主项：按下缩放 + 选中胶囊淡入 + 选中图标轻微放大 6%；
 * - 气泡：容器的锚点缩放淡入（180ms）+ 条目**逐条错峰**入场（每条 +28ms），
 *   收起时不延迟（一次性淡出，避免"关得比开得慢"的拖沓感）。
 */
@Composable
private fun ProfileBubbleNavigationBar(
    selected: ProfileTab,
    onSelect: (ProfileTab) -> Unit,
    onLogout: () -> Unit,
    onNavigate: (SubPage) -> Unit,
) {
    // 用户意图（手柄图标、日志用它）；实际渲染的 Popup 由 popupState 托管到退场动画结束
    val popupState = remember { MutableTransitionState(false) }
    val expanded = popupState.targetState

    // 外层 Box 固定 60dp 高：气泡作为悬浮层向上溢出，不参与导航栏高度计算，避免点击后抬高导航栏
    Box(Modifier.fillMaxWidth().height(60.dp)) {
        // 主项 + 手柄
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .background(Primer.BackgroundSecondary),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProfileTab.entries.forEach { t ->
                ProfileNavItem(
                    tab = t,
                    selected = t == selected,
                    onClick = { onSelect(t) },
                    modifier = Modifier.weight(1f),
                )
            }

            // 圆形手柄（三点）：按下缩放 + 展开时旋转 90°
            val press = rememberPressFeedback()
            val rotation = animateFloatAsState(
                targetValue = if (expanded) 90f else 0f,
                animationSpec = tween(ElementMotion.ICON_MS),
                label = "more-rotation",
            )
            Box(
                modifier = Modifier
                    .padding(end = 10.dp)
                    .size(40.dp)
                    .graphicsLayer {
                        scaleX = press.scale.value
                        scaleY = press.scale.value
                    }
                    .clip(CircleShape)
                    .border(
                        1.dp,
                        selectionColor(expanded, on = Primer.Blue500, off = Primer.Border),
                        CircleShape,
                    )
                    .background(selectionColor(expanded, on = Primer.Blue500, off = Primer.Gray150))
                    .clickable(
                        interactionSource = press.interaction,
                        indication = LocalIndication.current,
                    ) {
                        popupState.targetState = !expanded
                        Logger.ui(if (popupState.targetState) "展开 More 菜单" else "关闭 More 菜单", "Compose")
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.MoreHoriz,
                    contentDescription = if (expanded) "收起更多菜单" else "更多",
                    tint = selectionColor(expanded, on = Color.White, off = Primer.IconPrimary),
                    modifier = Modifier
                        .size(22.dp)
                        .graphicsLayer { rotationZ = rotation.value },
                )
            }
        }

        // 气泡：Popup 独立窗口（可点空白 / 返回键关闭），但**位置锚定在手柄上**、动画完全自控。
        // 只有「已展开或正在收起」时才挂载 —— 收起动画播完（isIdle）即卸载，不留透明窗口吃点击。
        if (popupState.currentState || popupState.targetState) {
            // 气泡与手柄的间距：先取成局部值，再同时用于 remember 的 key 与定位器
            val gapPx = with(LocalDensity.current) { 10.dp.roundToPx() }
            Popup(
                popupPositionProvider = remember(gapPx) { moreBubblePosition(gapPx) },
                onDismissRequest = { popupState.targetState = false },
                properties = PopupProperties(focusable = true),
            ) {
                AnimatedVisibility(
                    visibleState = popupState,
                    enter = bubbleEnter(),
                    exit = bubbleExit(),
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Primer.BackgroundPrimary,
                        border = BorderStroke(1.dp, Primer.Border.copy(alpha = 0.6f)),
                        shadowElevation = 12.dp,
                        modifier = Modifier.width(196.dp),
                    ) {
                        Column(Modifier.padding(vertical = 6.dp)) {
                            moreBubbleItems.forEachIndexed { index, item ->
                                // 逐条错峰入场：容器展开后，条目按顺序"冒"出来
                                AnimatedVisibility(
                                    visible = popupState.currentState,
                                    enter = fadeIn(
                                        tween(ElementMotion.BUBBLE_MS, delayMillis = index * ElementMotion.STAGGER_MS),
                                    ) + slideInVertically(
                                        tween(ElementMotion.BUBBLE_MS, delayMillis = index * ElementMotion.STAGGER_MS),
                                    ) { it / 3 },
                                    exit = fadeOut(tween(90)),
                                ) {
                                    MoreBubbleRow(
                                        label = item.label,
                                        icon = item.icon,
                                        tint = item.tint,
                                        onClick = {
                                            popupState.targetState = false
                                            onNavigate(item.page!!)
                                        },
                                    )
                                }
                            }

                            // 分隔线 + 危险项：与上面「去哪」的条目分开，避免误触
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                                    .height(1.dp)
                                    .background(Primer.Gray150),
                            )
                            AnimatedVisibility(
                                visible = popupState.currentState,
                                enter = fadeIn(
                                    tween(ElementMotion.BUBBLE_MS, delayMillis = moreBubbleItems.size * ElementMotion.STAGGER_MS),
                                ),
                                exit = fadeOut(tween(90)),
                            ) {
                                MoreBubbleRow(
                                    label = "登出",
                                    icon = Icons.AutoMirrored.Filled.Logout,
                                    tint = Primer.Red500,
                                    danger = true,
                                    onClick = {
                                        popupState.targetState = false
                                        Logger.ui("登出", "Compose")
                                        onLogout()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 气泡定位：**右对齐手柄、底边贴在手柄上方**。
 *
 * 直接写明而不是用 `DropdownMenu` 的默认锚点：菜单默认从锚点下边缘往下展开，
 * 而这个气泡必须向上弹出，否则会盖住手柄本身。
 */
private fun moreBubblePosition(gapPx: Int): PopupPositionProvider = object : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = anchorBounds.right - popupContentSize.width
        val y = anchorBounds.top - popupContentSize.height - gapPx
        return IntOffset(x, y)
    }
}

/**
 * 气泡里的一行：同色图标底 + 标签（危险项整行用红）。
 *
 * 按下时整行缩到 0.97（比手柄的 0.94 更轻）—— 行级元素幅度小一点才不会显得在抖。
 */
@Composable
private fun MoreBubbleRow(
    label: String,
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    val press = rememberPressFeedback(pressedScale = 0.97f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .graphicsLayer {
                scaleX = press.scale.value
                scaleY = press.scale.value
            }
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = press.interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(tint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
            color = if (danger) Primer.Red500 else Primer.TextPrimary,
        )
    }
}

/** 气泡导航主项（图标 + 文字，选中主蓝） */
/**
 * 气泡导航主项（图标 + 文字，选中主蓝）。
 *
 * 交互反馈三件套：按下缩放（0.94 / 120ms）、选中胶囊淡入、选中图标放大 6%。
 * 缩放值都在 `graphicsLayer` 里读，避免为了一枚图标每帧重组整行。
 */
@Composable
private fun ProfileNavItem(
    tab: ProfileTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val press = rememberPressFeedback()
    // 选中态渐变（图标/文字同色系），避免每次切 Tab 都「跳」一下
    val tint = selectionColor(selected, on = Primer.Blue500, off = Primer.IconPrimary)
    val iconScale = animateFloatAsState(
        targetValue = if (selected) 1.06f else 1f,
        animationSpec = tween(ElementMotion.COLOR_MS),
        label = "nav-icon-scale",
    )
    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = press.scale.value
                scaleY = press.scale.value
            }
            .clickable(
                interactionSource = press.interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 选中胶囊：淡入淡出。比 Material 那条横贯整栏的指示器更轻，和 60dp 栏高更搭
        Box(
            modifier = Modifier
                .size(width = 48.dp, height = 28.dp)
                .clip(CircleShape)
                .background(selectionColor(selected, on = Primer.Blue500.copy(alpha = 0.10f))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                tab.icon,
                contentDescription = tab.label,
                tint = tint,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer {
                        scaleX = iconScale.value
                        scaleY = iconScale.value
                    },
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            tab.label,
            fontSize = 11.sp,
            color = tint,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

// ───────────────────────── 通用仓库卡片 & 数据模型 ─────────────────────────

@Composable
private fun RepoCard(repo: RepoItem, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(6.dp)).border(1.dp, Primer.Border, RoundedCornerShape(6.dp)).clickable { onClick() }.padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(repo.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Primer.Blue500, modifier = Modifier.weight(1f))
            Text("★ ${repo.stars}", fontSize = 12.sp, color = Primer.TextPrimary)
        }
        if (repo.description != null) {
            Spacer(Modifier.height(4.dp))
            Text(repo.description, fontSize = 12.sp, color = Primer.TextSecondary)
        }
        if (repo.language != null) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(LanguageColors.of(repo.language)))
                Spacer(Modifier.width(4.dp))
                Text(repo.language, fontSize = 12.sp, color = Primer.TextTertiary)
            }
        }
    }
}

internal data class RepoItem(
    val name: String,
    val fullName: String,
    val description: String? = null,
    val language: String? = null,
    val stars: Long = 0,
    val forks: Long = 0,
)

internal fun parseRepos(json: String): List<RepoItem> {
    return runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val it = arr.getJSONObject(i)
            RepoItem(
                name = it.optString("name"),
                fullName = it.optString("full_name"),
                description = it.optString("description").takeIf { d -> d.isNotBlank() && d != "null" },
                language = it.optString("language").takeIf { l -> l.isNotBlank() && l != "null" },
                stars = it.optLong("stargazers_count"),
                forks = it.optLong("forks_count"),
            )
        }
    }.getOrDefault(emptyList())
}
