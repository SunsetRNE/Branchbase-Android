package com.branchbase.ui.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.platform.LocalContext
import com.branchbase.core.AccountStore
import com.branchbase.core.RustBridge
import com.branchbase.ui.log.LogScreen
import com.branchbase.ui.log.Logger
import com.branchbase.ui.theme.LanguageColors
import com.branchbase.ui.theme.Primer
import com.branchbase.ui.theme.ProfileColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 个人主页 Profile（对标 GitHub Profile 信息架构）。
 *
 * 概览 / 仓库 / 动态 三个主页面，由底部气泡导航栏（基础形态 ④）切换；
 * More 菜单（星标/软件包/项目/设置 + 登出）收纳到手柄弹出的气泡中。
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
    LaunchedEffect(Unit) {
        val json = withContext(Dispatchers.IO) { RustBridge.getMyRepos(host, token) }
        Logger.net("GET /user/repos → ${if (json != null) "200" else "失败"}", "GitHubAPI")
        repos = json?.let { parseRepos(it) } ?: emptyList()
        reposLoading = false
    }
    val user = runCatching { JSONObject(sessionJson).getJSONObject("user") }.getOrNull()
    // login 兜底顺序：session.user.login → 当前账号（多账号表）→ 空
    // （OAuth 交换的 session 原本只有 token，user 由 LoginViewModel 登录后补全）
    val accountLogin = remember { AccountStore.currentLogin(context) }
    val login = user?.optString("login")?.takeIf { it.isNotBlank() && it != "null" }
        ?: accountLogin.takeIf { it.isNotBlank() }
        ?: ""
    val name = user?.optString("name")?.takeIf { it.isNotBlank() && it != "null" }
    val avatarUrl = user?.optString("avatar_url")?.takeIf { it.isNotBlank() && it != "null" }
    val bio = user?.optString("bio")?.takeIf { it.isNotBlank() && it != "null" }
    val followers = user?.optLong("followers") ?: 0L
    val following = user?.optLong("following") ?: 0L
    val publicRepos = user?.optLong("public_repos") ?: 0L

    var tab by remember { mutableStateOf(ProfileTab.Overview) }
    var subPage by remember { mutableStateOf<SubPage?>(null) }

    // 子页面跳转时拦截系统返回，返回个人主页
    BackHandler(subPage != null) { subPage = null }

    // 气泡弹窗选项直接跳转子页面（不留存导航栏层级）
    val currentSubPage = subPage
    if (currentSubPage != null) {
        when (currentSubPage) {
            SubPage.Stars -> StarsScreen(sessionJson, onBack = { subPage = null }, onOpenRepo = onOpenRepo)
            SubPage.Projects -> ProjectsScreen(sessionJson, onBack = { subPage = null })
            SubPage.Settings -> SettingsScreen(onBack = { subPage = null }, onOpenLocalRepo = { subPage = SubPage.LocalRepo }, onOpenAbout = { subPage = SubPage.About }, onOpenLog = { subPage = SubPage.Log }, onOpenNotificationSettings = { subPage = SubPage.NotificationSettings }, onOpenAccounts = { subPage = SubPage.Accounts }, onOpenCommitMode = { subPage = SubPage.CommitMode })
            SubPage.LocalRepo -> LocalRepoScreen(sessionJson, onBack = { subPage = SubPage.Settings })
            SubPage.About -> AboutScreen(onBack = { subPage = SubPage.Settings })
            SubPage.Log -> LogScreen(onBack = { subPage = SubPage.Settings })
            SubPage.NotificationSettings -> NotificationSettingsScreen(onBack = { subPage = SubPage.Settings })
            SubPage.Tasks -> com.branchbase.ui.task.TaskScreen(onBack = { subPage = null })
            SubPage.Accounts -> AccountsScreen(onBack = { subPage = SubPage.Settings }, onAdd = onLogout)
            SubPage.CommitMode -> CommitModeScreen(onBack = { subPage = SubPage.Settings })
            SubPage.EditProfile -> ProfileEditScreen(sessionJson, onBack = { subPage = null }, onSaved = { subPage = null })
        }
        return
    }

    Column(
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
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Primer.IconPrimary, modifier = Modifier.size(24.dp).clickable { onBack() })
            Spacer(Modifier.width(4.dp))
            Text(login, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
        }

        // 内容（随底部气泡导航栏切换，weight 占据剩余空间）
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (tab) {
                ProfileTab.Overview -> ProfileOverview(login, name, avatarUrl, bio, followers, following, publicRepos, repos, reposLoading, onOpenRepo, onEdit = { subPage = SubPage.EditProfile })
                ProfileTab.Repositories -> ProfileRepositories(repos, reposLoading, onOpenRepo)
                ProfileTab.Activity -> ProfileActivity(host, token, login)
            }
        }

        // 气泡导航栏（基础形态 ④）：3 主项 + 右侧手柄弹出 More 菜单
        ProfileBubbleNavigationBar(
            selected = tab,
            onSelect = { tab = it; Logger.ui("切换到「${it.label}」", "Compose") },
            onLogout = onLogout,
            onNavigate = { subPage = it; Logger.ui("打开「${it.label}」", "Compose") },
        )
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

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        // 用户信息
        item {
            Column(Modifier.fillMaxWidth().padding(16.dp, 16.dp, 16.dp, 12.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.size(64.dp).clip(CircleShape).background(Primer.Blue500), contentAlignment = Alignment.Center) {
                        if (avatarUrl != null) {
                            AsyncImage(model = avatarUrl, contentDescription = login, modifier = Modifier.size(64.dp).clip(CircleShape))
                        } else {
                            Text(login.take(1).uppercase(), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        }
                    }
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
            .background(if (selected) Primer.Blue500 else Primer.Gray150)
            .border(1.dp, if (selected) Primer.Blue500 else Primer.Border, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(label, fontSize = 12.sp, color = if (selected) Color.White else Primer.TextSecondary)
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
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val type = o.optString("type")
                val repo = o.optJSONObject("repo")?.optString("name").orEmpty()
                val payload = o.optJSONObject("payload")
                val detail = when (type) {
                    "PushEvent" -> "推送了 ${payload?.optInt("size", 0) ?: 0} 个提交"
                    "CreateEvent" -> "创建了 ${payload?.optString("ref_type").orEmpty().ifBlank { "内容" }}"
                    "DeleteEvent" -> "删除了 ${payload?.optString("ref_type").orEmpty()}"
                    "WatchEvent" -> "星标了仓库"
                    "ForkEvent" -> "复刻了仓库"
                    "IssueCommentEvent" -> "评论了 issue #${payload?.optJSONObject("issue")?.optInt("number") ?: 0}"
                    "PullRequestEvent" -> "拉取请求 ${payload?.optString("action").orEmpty()} #${payload?.optJSONObject("pull_request")?.optInt("number") ?: 0}"
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
    var events by remember { mutableStateOf<List<ActivityEvent>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // 贡献墙（GraphQL 52 周；失败降级为事件近似并标注范围）
    var calendar by remember { mutableStateOf<ContributionCalendar?>(null) }
    var calLoading by remember { mutableStateOf(true) }
    var calDegraded by remember { mutableStateOf<String?>(null) }
    var selectedDay by remember { mutableStateOf<ContributionDay?>(null) }

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
        var json = withContext(Dispatchers.IO) { RustBridge.getJson(host, token, "$source?per_page=100") }
        var parsed = parseEvents(json)
        if (parsed.isEmpty()) {
            // 当前用户端点没数据时回退到公开事件端点
            val fallback = if (isSelf) "/users/$login/events" else "/user/events"
            val retry = withContext(Dispatchers.IO) { RustBridge.getJson(host, token, "$fallback?per_page=100") }
            if (parseEvents(retry).isNotEmpty()) {
                source = fallback
                json = retry
                parsed = parseEvents(retry)
            }
        }
        if (json == null) {
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
        val json = withContext(Dispatchers.IO) {
            RustBridge.contributionCalendar(host, token, login, range.first, range.second)
        }
        val parsed = parseContributionCalendar(json)
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
            // 统计
            val now = System.currentTimeMillis()
            val week = events.count { now - it.createdAt < 7L * 24 * 60 * 60 * 1000 }
            val month = events.count { now - it.createdAt < 30L * 24 * 60 * 60 * 1000 }
            SectionTitle("动态概览")
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("最近 7 天", "$week", Modifier.weight(1f))
                StatCard("最近 30 天", "$month", Modifier.weight(1f))
                StatCard("总记录", "${events.size}", Modifier.weight(1f))
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

            // 活动十字坐标轴（类型 × 时间；与下面的活动热力并存）
            ActivityAxis(events)

            // 贡献热力（按天聚合，过去 12 周）
            SectionTitle("活动热力", "过去 12 周")
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

/** 活动热力：按天聚合过去 12 周（84 天）。 */
@Composable
private fun ActivityHeatmap(events: List<ActivityEvent>) {
    val levels = listOf(
        ProfileColors.ContributionL0, ProfileColors.ContributionL1,
        ProfileColors.ContributionL2, ProfileColors.ContributionL3, ProfileColors.ContributionL4,
    )
    val dayCounts = remember(events) {
        val dayMs = 24L * 60 * 60 * 1000
        val today = System.currentTimeMillis() / dayMs * dayMs
        val counts = IntArray(84)
        events.forEach { e ->
            if (e.createdAt > 0) {
                val idx = ((today - (e.createdAt / dayMs * dayMs)) / dayMs).toInt()
                if (idx in 0 until 84) counts[83 - idx]++
            }
        }
        counts
    }
    val max = (dayCounts.maxOrNull() ?: 0).coerceAtLeast(1)
    Column {
        Canvas(Modifier.fillMaxWidth().height(74.dp)) {
            val cell = 9.dp.toPx()
            val gap = 2.dp.toPx()
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
                    topLeft = androidx.compose.ui.geometry.Offset(col * (cell + gap), row * (cell + gap)),
                    size = androidx.compose.ui.geometry.Size(cell, cell),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "共 ${dayCounts.sum()} 次活动 · 最深 ${max} 次/天",
            fontSize = 11.5.sp,
            color = Primer.TextTertiary,
        )
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
 * Profile 页切换导航：侧边隐藏 + 弹出气泡（对齐 docs/navbar-wireframe.md ④ 与 EdgeNavigationBar）。
 *
 * 3 个主项（Overview/Repositories/Activity）+ 右侧圆形手柄，点击手柄弹出 More 菜单气泡。
 * 手柄 40dp 圆，气泡白底圆角 16dp，距底 68dp、右侧对齐。
 */
@Composable
private fun ProfileBubbleNavigationBar(
    selected: ProfileTab,
    onSelect: (ProfileTab) -> Unit,
    onLogout: () -> Unit,
    onNavigate: (SubPage) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

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
            // 圆形手柄（三点）
            Box(
                modifier = Modifier
                    .padding(end = 10.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (expanded) Primer.Blue500 else Primer.Border)
                    .clickable { expanded = !expanded; Logger.ui(if (expanded) "展开 More 菜单" else "关闭 More 菜单", "Compose") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.MoreHoriz,
                    contentDescription = "更多",
                    tint = if (expanded) Color.White else Primer.IconPrimary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        // 弹出气泡（More 菜单）：用 DropdownMenu（Material3）独立窗口渲染，浮在导航栏上方，不参与导航栏高度计算
        Box(Modifier.align(Alignment.BottomEnd)) {
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.width(180.dp),
            ) {
                DropdownMenuItem(
                    text = { Text("星标") },
                    leadingIcon = { Icon(Icons.Filled.Star, null, tint = Primer.IconSecondary, modifier = Modifier.size(20.dp)) },
                    trailingIcon = { Text("8", fontSize = 12.sp, color = Primer.TextTertiary) },
                    onClick = {
                        expanded = false
                        onNavigate(SubPage.Stars)
                    },
                )
                DropdownMenuItem(
                    text = { Text("项目") },
                    leadingIcon = { Icon(Icons.Filled.Dashboard, null, tint = Primer.IconSecondary, modifier = Modifier.size(20.dp)) },
                    onClick = {
                        expanded = false
                        onNavigate(SubPage.Projects)
                    },
                )
                DropdownMenuItem(
                    text = { Text("任务") },
                    leadingIcon = { Icon(Icons.Filled.Timeline, null, tint = Primer.IconSecondary, modifier = Modifier.size(20.dp)) },
                    onClick = {
                        expanded = false
                        onNavigate(SubPage.Tasks)
                    },
                )
                DropdownMenuItem(
                    text = { Text("设置") },
                    leadingIcon = { Icon(Icons.Filled.Settings, null, tint = Primer.IconSecondary, modifier = Modifier.size(20.dp)) },
                    onClick = {
                        expanded = false
                        onNavigate(SubPage.Settings)
                    },
                )
                DropdownMenuItem(
                    text = { Text("登出") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, null, tint = Primer.IconSecondary, modifier = Modifier.size(20.dp)) },
                    onClick = { Logger.ui("登出", "Compose"); onLogout() },
                )
            }
        }
    }
}

/** 气泡导航主项（图标 + 文字，选中主蓝） */
@Composable
private fun ProfileNavItem(
    tab: ProfileTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = if (selected) Primer.Blue500 else Primer.IconPrimary
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(tab.icon, contentDescription = tab.label, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(3.dp))
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
