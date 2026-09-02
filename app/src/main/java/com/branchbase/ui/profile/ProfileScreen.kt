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
import androidx.compose.material.icons.filled.Inventory2
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
    val login = user?.optString("login")?.takeIf { it.isNotBlank() && it != "null" } ?: "用户"
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
            SubPage.Packages -> PackagesScreen(sessionJson, onBack = { subPage = null })
            SubPage.Projects -> ProjectsScreen(sessionJson, onBack = { subPage = null })
            SubPage.Settings -> SettingsScreen(onBack = { subPage = null }, onOpenLocalRepo = { subPage = SubPage.LocalRepo }, onOpenAbout = { subPage = SubPage.About }, onOpenLog = { subPage = SubPage.Log })
            SubPage.LocalRepo -> LocalRepoScreen(sessionJson, onBack = { subPage = SubPage.Settings })
            SubPage.About -> AboutScreen(onBack = { subPage = SubPage.Settings })
            SubPage.Log -> LogScreen(onBack = { subPage = SubPage.Settings })
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
                ProfileTab.Overview -> ProfileOverview(login, name, avatarUrl, bio, followers, following, publicRepos, repos, reposLoading, onOpenRepo)
                ProfileTab.Repositories -> ProfileRepositories(repos, reposLoading, onOpenRepo)
                ProfileTab.Activity -> ProfileActivity()
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
                Box(Modifier.fillMaxWidth().height(32.dp).clip(RoundedCornerShape(6.dp)).background(Primer.Gray150).border(1.dp, Primer.Border, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
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
        // 成就
        item {
            SectionTitle("成就")
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AchievementBadge("⭐", "拉取鲨鱼")
                AchievementBadge("🚀", "银河大脑")
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

@Composable
private fun AchievementBadge(emoji: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(Color(0xFF1B1F24)), contentAlignment = Alignment.Center) {
            Text(emoji, fontSize = 24.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 12.sp, color = Primer.TextSecondary)
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

// ───────────────────────── Activity 页（🔧 本地渲染解析） ─────────────────────────

@Composable
private fun ProfileActivity() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // 贡献图（本地渲染）
        SectionTitle("贡献图", "🔧 本地渲染")
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("过去一年 179 次贡献", fontSize = 14.sp, color = Primer.TextTertiary)
            Spacer(Modifier.height(10.dp))
            ContributionGraph()
        }
        // 雷达（本地渲染）
        SectionTitle("动态概览", "🔧 本地渲染")
        ActivityRadar(Modifier.padding(horizontal = 16.dp))
        // 时间线
        SectionTitle("贡献动态")
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("2026 年 8 月", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
            Spacer(Modifier.height(8.dp))
            ContributionBar("SunsetRNE/extractions-data", 37, 78)
            ContributionBar("SunsetRNE/Sundown", 24, 52)
            ContributionBar("创建了 21 个仓库", 21, 30)
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(6.dp)).background(Primer.Gray150).border(1.dp, Primer.Border, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                Text("查看更多动态", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Primer.Blue500)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** 🔧 本地渲染：贡献图绿阶网格 */
@Composable
private fun ContributionGraph() {
    val levels = listOf(ProfileColors.ContributionL0, ProfileColors.ContributionL1, ProfileColors.ContributionL2, ProfileColors.ContributionL3, ProfileColors.ContributionL4)
    val data = remember { List(12 * 7) { (0..4).random() } } // 模拟贡献数据
    Canvas(Modifier.fillMaxWidth().height(70.dp)) {
        val cell = 9.dp.toPx()
        val gap = 2.dp.toPx()
        data.forEachIndexed { idx, lv ->
            val col = idx / 7
            val row = idx % 7
            val x = col * (cell + gap)
            val y = row * (cell + gap)
            drawRoundRect(color = levels[lv], topLeft = androidx.compose.ui.geometry.Offset(x, y), size = androidx.compose.ui.geometry.Size(cell, cell), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
        }
    }
}

/** 🔧 本地渲染：活动雷达图 */
@Composable
private fun ActivityRadar(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text("向 SunsetRNE/Janno、SunsetRNE/extractions-data 等 55 个仓库做出贡献", fontSize = 14.sp, color = Primer.TextTertiary)
        Spacer(Modifier.height(8.dp))
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val w = size.width
            val h = size.height
            drawLine(color = Primer.Border, start = androidx.compose.ui.geometry.Offset(0f, h / 2), end = androidx.compose.ui.geometry.Offset(w, h / 2))
            drawLine(color = Primer.Border, start = androidx.compose.ui.geometry.Offset(w / 2, 0f), end = androidx.compose.ui.geometry.Offset(w / 2, h))
            drawRect(color = ProfileColors.ContributionL1, topLeft = androidx.compose.ui.geometry.Offset(w * 0.46f, h * 0.14f), size = androidx.compose.ui.geometry.Size(14.dp.toPx(), 14.dp.toPx()))
        }
    }
}

@Composable
private fun ContributionBar(label: String, count: Int, percent: Int) {
    Column(Modifier.padding(bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Primer.Blue500)
            Text("$count 次提交", fontSize = 12.sp, color = Primer.TextTertiary)
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth(percent / 100f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(ProfileColors.ContributionL1))
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
                    text = { Text("软件包") },
                    leadingIcon = { Icon(Icons.Filled.Inventory2, null, tint = Primer.IconSecondary, modifier = Modifier.size(20.dp)) },
                    onClick = {
                        expanded = false
                        onNavigate(SubPage.Packages)
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
