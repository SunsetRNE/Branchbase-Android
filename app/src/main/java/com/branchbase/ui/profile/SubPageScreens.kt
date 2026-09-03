package com.branchbase.ui.profile

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import java.io.File
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.branchbase.BuildConfig
import com.branchbase.R
import com.branchbase.core.RustBridge
import com.branchbase.ui.log.Logger
import com.branchbase.ui.notification.NotifLayout
import com.branchbase.ui.notification.readNotifLayout
import com.branchbase.ui.notification.writeNotifLayout
import com.branchbase.ui.theme.LanguageColors
import com.branchbase.ui.theme.Primer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * More 菜单子页面（星标 / 软件包 / 项目 / 设置）。
 *
 * 由气泡弹窗选项直接跳转进入，不留存导航栏层级；返回回到「个人主页」。
 * 三个数据页（星标/软件包/项目）统一接入「缓存 + 快速预加载渲染 + 数据过期 + 强制刷新」机制，
 * 避免重复请求服务器导致限流/拉黑。
 */
enum class SubPage(val label: String) {
    Stars("星标"),
    Packages("软件包"),
    Projects("项目"),
    Settings("设置"),
    LocalRepo("本地仓库"),
    About("关于"),
    Log("日志"),
    NotificationSettings("通知设置"),
}

// ───────────────────────── 缓存机制（内存缓存 + TTL 过期） ─────────────────────────

/** 内存级数据缓存：key -> (原始 JSON, 写入时间戳) */
internal object ProfileCache {
    private class Entry(val data: String, val time: Long)
    private val map = mutableMapOf<String, Entry>()

    /** 命中未过期的缓存返回原始 JSON，否则返回 null */
    fun get(key: String, ttlMs: Long): String? {
        val e = map[key] ?: return null
        if (System.currentTimeMillis() - e.time > ttlMs) {
            map.remove(key)
            return null
        }
        return e.data
    }

    fun put(key: String, data: String) {
        map[key] = Entry(data, System.currentTimeMillis())
    }
}

/** 各数据类型过期时长（毫秒），避免频繁请求 */
internal object ProfileTtl {
    const val STARS = 15L * 60 * 1000      // 15 分钟
    const val PACKAGES = 15L * 60 * 1000   // 15 分钟
    const val PROJECTS = 15L * 60 * 1000   // 15 分钟
}

// ───────────────────────── 数据模型 & 解析 ─────────────────────────

internal data class PackageItem(
    val name: String,
    val packageType: String,
    val visibility: String? = null,
    val versionCount: Long = 0,
)

internal data class ProjectItem(
    val name: String,
    val description: String? = null,
)

internal fun parsePackages(json: String): List<PackageItem> {
    return runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val it = arr.getJSONObject(i)
            PackageItem(
                name = it.optString("name"),
                packageType = it.optString("package_type"),
                visibility = it.optString("visibility").takeIf { v -> v.isNotBlank() && v != "null" },
                versionCount = it.optLong("version_count"),
            )
        }
    }.getOrDefault(emptyList())
}

internal fun parseProjects(json: String): List<ProjectItem> {
    return runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val it = arr.getJSONObject(i)
            ProjectItem(
                name = it.optString("name"),
                description = it.optString("body").takeIf { d -> d.isNotBlank() && d != "null" },
            )
        }
    }.getOrDefault(emptyList())
}

// ───────────────────────── 公共组件 ─────────────────────────

/** 子页面顶部返回导航（返回箭头 + 标题 + 可选右侧操作） */
@Composable
private fun SubPageHeader(title: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Primer.IconPrimary, modifier = Modifier.size(24.dp).clickable { onBack() })
        Spacer(Modifier.width(8.dp))
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

/** 强制刷新按钮（Material Refresh 图标，无 emoji） */
@Composable
private fun RefreshButton(onRefresh: () -> Unit) {
    Icon(
        Icons.Filled.Refresh,
        contentDescription = "刷新",
        tint = Primer.Blue500,
        modifier = Modifier.size(20.dp).clickable { onRefresh() },
    )
}

// ───────────────────────── 星标页 ─────────────────────────

@Composable
fun StarsScreen(sessionJson: String, onBack: () -> Unit, onOpenRepo: (String) -> Unit) {
    LaunchedEffect(Unit) { Logger.ui("进入星标页", "Compose") }
    val token = runCatching { JSONObject(sessionJson).getJSONObject("token").optString("access_token") }.getOrNull() ?: ""
    val host = runCatching { JSONObject(sessionJson).optString("host", "github.com") }.getOrDefault("github.com")
    var repos by remember { mutableStateOf<List<RepoItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
        val cacheKey = "stars"
        // 快速预加载渲染：首次进入先读缓存
        if (refreshKey == 0) {
            val cached = ProfileCache.get(cacheKey, ProfileTtl.STARS)
            if (cached != null) {
                repos = parseRepos(cached)
                loading = false
                return@LaunchedEffect
            }
        }
        loading = true
        val json = withContext(Dispatchers.IO) { RustBridge.getStarredRepos(host, token) }
        Logger.net("GET /user/starred → ${if (json != null && !json.startsWith("ERROR:")) "200" else "失败"}", "GitHubAPI")
        if (json != null && !json.startsWith("ERROR:")) {
            repos = parseRepos(json)
            ProfileCache.put(cacheKey, json)
        } else {
            repos = emptyList()
        }
        loading = false
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Primer.BackgroundPrimary).statusBarsPadding().navigationBarsPadding(),
    ) {
        SubPageHeader("星标", onBack) {
            Text("${repos.size}", fontSize = 13.sp, color = Primer.TextTertiary)
            Spacer(Modifier.width(12.dp))
            RefreshButton { refreshKey++ }
        }
        // 搜索框（占位）
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(36.dp).clip(RoundedCornerShape(6.dp)).background(Primer.Gray150).border(1.dp, Primer.Border, RoundedCornerShape(6.dp)).padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = Primer.TextTertiary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("搜索星标", fontSize = 13.sp, color = Primer.TextTertiary)
            }
        }
        Spacer(Modifier.height(8.dp))
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("加载中", color = Primer.TextTertiary) }
        } else if (repos.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("暂无星标", fontSize = 13.sp, color = Primer.TextTertiary) }
        } else {
            LazyColumn {
                items(repos) { repo -> StarredRepoCard(repo, onClick = { onOpenRepo(repo.fullName) }) }
            }
        }
    }
}

@Composable
private fun StarredRepoCard(repo: RepoItem, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(Primer.Blue500), contentAlignment = Alignment.Center) {
            Text(repo.name.take(1).uppercase(), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(repo.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Primer.Blue500)
            if (repo.description != null) {
                Spacer(Modifier.height(2.dp))
                Text(repo.description, fontSize = 12.5.sp, color = Primer.TextSecondary, lineHeight = 18.sp)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (repo.language != null) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(LanguageColors.of(repo.language)))
                    Spacer(Modifier.width(4.dp))
                    Text(repo.language, fontSize = 12.sp, color = Primer.TextTertiary)
                    Spacer(Modifier.width(8.dp))
                }
                Icon(Icons.Filled.Star, contentDescription = null, tint = Primer.TextTertiary, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(2.dp))
                Text("${repo.stars}", fontSize = 12.sp, color = Primer.TextTertiary)
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFFFFF8C5)).border(1.dp, Color(0xFFD4A72C), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("已星标", fontSize = 12.sp, color = Color(0xFF9A6700))
        }
    }
}

// ───────────────────────── 软件包页 ─────────────────────────

@Composable
fun PackagesScreen(sessionJson: String, onBack: () -> Unit) {
    LaunchedEffect(Unit) { Logger.ui("进入软件包页", "Compose") }
    val token = runCatching { JSONObject(sessionJson).getJSONObject("token").optString("access_token") }.getOrNull() ?: ""
    val host = runCatching { JSONObject(sessionJson).optString("host", "github.com") }.getOrDefault("github.com")
    var packages by remember { mutableStateOf<List<PackageItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
        val cacheKey = "packages"
        if (refreshKey == 0) {
            val cached = ProfileCache.get(cacheKey, ProfileTtl.PACKAGES)
            if (cached != null) {
                packages = parsePackages(cached)
                loading = false
                return@LaunchedEffect
            }
        }
        loading = true
        val json = withContext(Dispatchers.IO) { RustBridge.getMyPackages(host, token) }
        Logger.net("GET /user/packages → ${if (json != null && !json.startsWith("ERROR:")) "200" else "失败"}", "GitHubAPI")
        if (json != null && !json.startsWith("ERROR:")) {
            packages = parsePackages(json)
            ProfileCache.put(cacheKey, json)
        } else {
            packages = emptyList()
        }
        loading = false
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Primer.BackgroundPrimary).statusBarsPadding().navigationBarsPadding(),
    ) {
        SubPageHeader("软件包", onBack) {
            Text("${packages.size}", fontSize = 13.sp, color = Primer.TextTertiary)
            Spacer(Modifier.width(12.dp))
            RefreshButton { refreshKey++ }
        }
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("加载中", color = Primer.TextTertiary) }
        } else if (packages.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("暂无软件包", fontSize = 13.sp, color = Primer.TextTertiary) }
        } else {
            LazyColumn {
                items(packages) { pkg -> PackageCard(pkg) }
            }
        }
    }
}

@Composable
private fun PackageCard(pkg: PackageItem) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(Primer.Blue500), contentAlignment = Alignment.Center) {
            Text(pkg.name.take(1).uppercase(), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(pkg.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Primer.Blue500)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(Primer.Gray150).border(1.dp, Primer.Border, RoundedCornerShape(10.dp)).padding(horizontal = 7.dp, vertical = 1.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(pkg.packageType, fontSize = 11.sp, color = Primer.TextSecondary)
                }
                if (pkg.visibility != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(if (pkg.visibility == "private") "私有" else "公开", fontSize = 11.sp, color = Primer.TextTertiary)
                }
            }
        }
        if (pkg.versionCount > 0) {
            Text("${pkg.versionCount} 版本", fontSize = 12.sp, color = Primer.TextTertiary)
        }
    }
}

// ───────────────────────── 项目页 ─────────────────────────

@Composable
fun ProjectsScreen(sessionJson: String, onBack: () -> Unit) {
    LaunchedEffect(Unit) { Logger.ui("进入项目页", "Compose") }
    val token = runCatching { JSONObject(sessionJson).getJSONObject("token").optString("access_token") }.getOrNull() ?: ""
    val host = runCatching { JSONObject(sessionJson).optString("host", "github.com") }.getOrDefault("github.com")
    var projects by remember { mutableStateOf<List<ProjectItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
        val cacheKey = "projects"
        if (refreshKey == 0) {
            val cached = ProfileCache.get(cacheKey, ProfileTtl.PROJECTS)
            if (cached != null) {
                projects = parseProjects(cached)
                loading = false
                return@LaunchedEffect
            }
        }
        loading = true
        val json = withContext(Dispatchers.IO) { RustBridge.getMyProjects(host, token) }
        Logger.net("GET /user/projects → ${if (json != null && !json.startsWith("ERROR:")) "200" else "失败"}", "GitHubAPI")
        if (json != null && !json.startsWith("ERROR:")) {
            projects = parseProjects(json)
            ProfileCache.put(cacheKey, json)
        } else {
            projects = emptyList()
        }
        loading = false
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Primer.BackgroundPrimary).statusBarsPadding().navigationBarsPadding(),
    ) {
        SubPageHeader("项目", onBack) {
            RefreshButton { refreshKey++ }
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier.clip(RoundedCornerShape(6.dp)).background(Primer.Green500).padding(horizontal = 12.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("新建", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White)
                }
            }
        }
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("加载中", color = Primer.TextTertiary) }
        } else if (projects.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("暂无项目", fontSize = 13.sp, color = Primer.TextTertiary) }
        } else {
            LazyColumn {
                items(projects) { project -> ProjectCard(project) }
            }
        }
    }
}

@Composable
private fun ProjectCard(project: ProjectItem) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(project.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
        if (project.description != null) {
            Spacer(Modifier.height(2.dp))
            Text(project.description, fontSize = 12.5.sp, color = Primer.TextSecondary, lineHeight = 18.sp)
        }
    }
}

// ───────────────────────── 设置页 ─────────────────────────

/** 提交模式（三选项，对齐 commit-mode-decision-tree.md） */
internal enum class CommitMode(val label: String, val desc: String) {
    SINGLE_FILE("单个文件更改，单个提交", "官方客户端行为 · 编辑即提交"),
    MULTI_FILE("多个文件更改，合并一次提交", "网页端行为 · 暂存区统一提交"),
    LOCAL_REPO("文件拉取到本地仓库，由本地 git 管理提交推送", "Git 命令行习惯"),
}

internal const val KEY_COMMIT_MODE = "commit_mode"

@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenLocalRepo: () -> Unit, onOpenAbout: () -> Unit, onOpenLog: () -> Unit, onOpenNotificationSettings: () -> Unit) {
    LaunchedEffect(Unit) { Logger.ui("进入设置页", "Compose") }
    val context = LocalContext.current
    var mode by remember { mutableStateOf(commitMode(context)) } // CommitMode?，null = 未配置

    Column(
        modifier = Modifier.fillMaxSize().background(Primer.BackgroundPrimary).statusBarsPadding().navigationBarsPadding(),
    ) {
        SubPageHeader("设置", onBack)

        SettingsSectionTitle("提交模式")
        CommitMode.entries.forEach { m ->
            ModeOptionRow(
                label = m.label,
                desc = m.desc,
                selected = mode == m,
                onClick = {
                    mode = m
                    saveCommitMode(context, m)
                },
            )
        }

        SettingsSectionTitle("本地仓库")
        LocalRepoEntry(enabled = mode == CommitMode.LOCAL_REPO, onClick = onOpenLocalRepo)

        SettingsSectionTitle("其他")
        SettingsItem(Icons.Filled.AccountCircle, "账号")
        SettingsItem(Icons.Filled.Palette, "外观")
        SettingsItem(Icons.Filled.Notifications, "通知", onClick = onOpenNotificationSettings)
        SettingsItem(Icons.Filled.Info, "关于", onClick = onOpenAbout)
        SettingsItem(Icons.Filled.Build, "日志", onClick = onOpenLog)
    }
}

/** 通知设置子页面：选择通知列表显示模式（4 种，默认平铺），持久化到 SharedPreferences。 */
@Composable
fun NotificationSettingsScreen(onBack: () -> Unit) {
    LaunchedEffect(Unit) { Logger.ui("进入通知设置页", "Compose") }
    val context = LocalContext.current
    var layout by remember { mutableStateOf(readNotifLayout(context)) }

    Column(
        modifier = Modifier.fillMaxSize().background(Primer.BackgroundPrimary).statusBarsPadding().navigationBarsPadding(),
    ) {
        SubPageHeader("通知", onBack)

        SettingsSectionTitle("通知显示模式")
        NotifLayout.entries.forEach { l ->
            ModeOptionRow(
                label = l.label,
                desc = l.desc,
                selected = layout == l,
                onClick = {
                    layout = l
                    writeNotifLayout(context, l)
                },
            )
        }

        SettingsSectionTitle("说明")
        Text(
            "选择通知列表的展示方式。\n「平铺」为默认：每条通知独立成卡；分组/合并/两级模式可将相关通知折叠，减少列表长度。",
            fontSize = 12.sp,
            color = Primer.TextTertiary,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun SettingsSectionTitle(title: String) {
    Text(
        title,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = Primer.TextTertiary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun ModeOptionRow(label: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) Color(0xFFF0FFF4) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .border(2.dp, if (selected) Primer.Green500 else Primer.Border, CircleShape)
                .background(if (selected) Primer.Green500 else Color.Transparent),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
            Spacer(Modifier.height(2.dp))
            Text(desc, fontSize = 12.sp, color = Primer.TextTertiary)
        }
    }
}

@Composable
private fun LocalRepoEntry(enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp, vertical = 13.dp)
            .alpha(if (enabled) 1f else 0.55f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Folder,
            contentDescription = "本地仓库",
            tint = if (enabled) Primer.IconPrimary else Primer.Gray500,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            "本地仓库",
            fontSize = 14.sp,
            color = if (enabled) Primer.TextPrimary else Primer.Gray500,
            modifier = Modifier.weight(1f),
        )
        if (enabled) {
            Icon(Icons.Filled.ChevronRight, null, tint = Primer.TextTertiary, modifier = Modifier.size(20.dp))
        } else {
            Text("未开启", fontSize = 11.sp, color = Primer.Gray500)
        }
    }
}

@Composable
private fun SettingsItem(icon: ImageVector, name: String, onClick: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp).clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = name, tint = Primer.IconSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(name, fontSize = 14.sp, color = Primer.TextPrimary, modifier = Modifier.weight(1f))
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Primer.TextTertiary, modifier = Modifier.size(20.dp))
    }
}

// ───────────────────────── 本地仓库列表页 ─────────────────────────

/**
 * 本地仓库列表页。每个仓库独立 Git（更新/删除），「＋拉取仓库」列出我的仓库并浅 clone。
 * clone 通过 `RustBridge.gitClone`（libgit2）。
 */
@Composable
fun LocalRepoScreen(sessionJson: String, onBack: () -> Unit) {
    LaunchedEffect(Unit) { Logger.ui("进入本地仓库页", "Compose") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repoRoot = remember { File(context.getExternalFilesDir(null), "repos") }
    var repos by remember { mutableStateOf(listLocalRepos(repoRoot)) }
    val host = remember(sessionJson) { runCatching { JSONObject(sessionJson).optString("host", "github.com") }.getOrDefault("github.com") }
    val token = remember(sessionJson) {
        runCatching { JSONObject(sessionJson).optJSONObject("token")?.optString("access_token").orEmpty() }.getOrDefault("")
    }

    var myRepos by remember { mutableStateOf<List<RepoItem>>(emptyList()) }
    var showPicker by remember { mutableStateOf(false) }
    var loadingRepos by remember { mutableStateOf(false) }
    var cloning by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    // 拉取我的仓库
    fun loadMyRepos() {
        if (loadingRepos) return
        loadingRepos = true
        scope.launch {
            val json = withContext(Dispatchers.IO) { RustBridge.getMyRepos(host, token) }
            myRepos = json?.takeIf { !it.startsWith("ERROR:") }?.let { parseRepos(it) } ?: emptyList()
            loadingRepos = false
        }
    }

    // clone 一个仓库
    fun doClone(fullName: String, name: String) {
        showPicker = false
        val target = File(repoRoot, name)
        if (target.exists()) {
            feedback = "「$name」已存在"
            return
        }
        scope.launch {
            cloning = true
            feedback = null
            val ok = RustBridge.gitClone("https://github.com/$fullName", target.absolutePath, "", token)
            Logger.remote(if (ok) "git clone $fullName 完成" else "git clone $fullName 失败", "libgit2")
            cloning = false
            feedback = if (ok) "已拉取 $name" else "拉取失败"
            repos = listLocalRepos(repoRoot)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Primer.BackgroundPrimary).statusBarsPadding().navigationBarsPadding(),
    ) {
        SubPageHeader("本地仓库", onBack) {
            Text("${repos.size} 个", fontSize = 12.sp, color = Primer.TextTertiary)
        }

        // ＋拉取仓库
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Primer.Green500)
                .clickable { showPicker = true; if (myRepos.isEmpty()) loadMyRepos() }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Add, null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("拉取仓库", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White)
            }
        }

        if (cloning) {
            Text("正在克隆…", fontSize = 12.sp, color = Primer.TextTertiary, modifier = Modifier.padding(horizontal = 16.dp))
        }
        feedback?.let { Text(it, fontSize = 12.sp, color = if (it.startsWith("已")) Primer.Green500 else Primer.Red500, modifier = Modifier.padding(horizontal = 16.dp)) }

        if (repos.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Folder, null, tint = Primer.IconSecondary, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("暂无本地仓库", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
                    Spacer(Modifier.height(4.dp))
                    Text("点击「拉取仓库」将仓库克隆到本地", fontSize = 12.sp, color = Primer.TextTertiary)
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(repos) { name ->
                    LocalRepoRow(
                        name = name,
                        onPull = { scope.launch { RustBridge.gitPull(File(repoRoot, name).absolutePath, token) } },
                        onDelete = { deleteTarget = name },
                    )
                }
            }
        }
    }

    // 仓库选择对话框
    if (showPicker) {
        Dialog(onDismissRequest = { showPicker = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Primer.BackgroundPrimary, RoundedCornerShape(12.dp))
                    .padding(16.dp),
            ) {
                Text("选择要拉取的仓库", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary)
                Spacer(Modifier.height(10.dp))
                if (loadingRepos) {
                    Text("加载中…", fontSize = 13.sp, color = Primer.TextTertiary)
                } else if (myRepos.isEmpty()) {
                    Text("暂无仓库", fontSize = 13.sp, color = Primer.TextTertiary)
                } else {
                    LazyColumn(Modifier.heightIn(max = 400.dp)) {
                        items(myRepos) { repo ->
                            Text(
                                repo.fullName,
                                fontSize = 14.sp,
                                color = Primer.TextPrimary,
                                modifier = Modifier.fillMaxWidth().clickable { doClone(repo.fullName, repo.name) }.padding(vertical = 10.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    // 删除确认
    deleteTarget?.let { name ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除本地仓库") },
            text = { Text("确定删除「$name」吗？仅删除本地副本，不影响远端仓库。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch {
                        File(repoRoot, name).deleteRecursively()
                        repos = listLocalRepos(repoRoot)
                    }
                }) { Text("删除", color = Primer.Red500) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}

private fun listLocalRepos(root: File): List<String> =
    root.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: emptyList()

@Composable
private fun LocalRepoRow(name: String, onPull: () -> Unit, onDelete: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, Primer.Border, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Text(name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Primer.Blue500)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("更新", fontSize = 12.sp, color = Primer.Blue500, modifier = Modifier.clickable { onPull() })
            Text("删除", fontSize = 12.sp, color = Primer.Red500, modifier = Modifier.clickable { onDelete() })
        }
    }
}

// ───────────────────────── 关于页 ─────────────────────────

/**
 * 关于页：展示应用图标 + 版本号信息（工程/标准/Git 包/构建时间/七位哈希）+ 最新构建校验提示。
 * 对齐 docs/versioning.md 与 design/about-prototype.html。
 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    LaunchedEffect(Unit) { Logger.ui("AboutScreen 进入，展示版本号与校验结果", "Compose") }
    val context = LocalContext.current
    val variant = remember { resolveReleaseVariant(context) }
    val localFingerprint = remember { signatureFingerprint(context) }
    var remoteFingerprint by remember { mutableStateOf<String?>(null) }
    var remoteChecking by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        remoteChecking = true
        remoteFingerprint = withContext(Dispatchers.IO) {
val sig = when (variant) {
                    ReleaseVariant.BETA -> fetchRemoteSignature("SunsetRNE", "Branchbase-Android", "beta", "verify/signature.txt")
                    ReleaseVariant.RELEASE -> fetchLatestReleaseSignature("SunsetRNE", "Branchbase-Android")
                    else -> null
                }
            sig?.let { parseSignatureFingerprint(it) }
        }
        remoteChecking = false
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Primer.BackgroundPrimary).statusBarsPadding().navigationBarsPadding(),
    ) {
        SubPageHeader("关于", onBack)
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            // 应用图标（读取 foreground 资源，规避 adaptive-icon 的 painterResource 渲染崩溃）
            Box(
                Modifier.size(80.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xFF0d1117)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = "应用图标",
                    modifier = Modifier.size(60.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text("Branchbase", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary)
            Spacer(Modifier.height(4.dp))
            Text("GitHub 第三方客户端", fontSize = 12.sp, color = Primer.TextTertiary)

            Spacer(Modifier.height(20.dp))
            // 版本信息卡片
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, Primer.Border, RoundedCornerShape(8.dp)),
            ) {
                AboutInfoRow("工程版本号", BuildConfig.ENGINEERING_VERSION)
                AboutInfoRow("标准版本号", BuildConfig.STANDARD_VERSION)
                AboutInfoRow("Git 配置包版本", "libgit2 1.7.2")
                AboutInfoRow("构建时间", BuildConfig.BUILD_TIME)
                AboutInfoRow("七位哈希", BuildConfig.GIT_HASH)
                AboutInfoRow("发布版本", variant.label)
                AboutInfoRow("签名校验", if (variant != ReleaseVariant.UNKNOWN) "匹配" else "异常（未知签名）")
                AboutInfoRow("远程校验", when {
                    remoteChecking -> "校验中…"
                    remoteFingerprint.isNullOrBlank() -> "无法获取校验文件"
                    remoteFingerprint.equals(localFingerprint, ignoreCase = true) -> "匹配"
                    else -> "不匹配"
                })
            }

            Spacer(Modifier.height(16.dp))
            // 最新构建校验提示
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFF0FFF4))
                    .border(1.dp, Color(0xFFD4E9D6), RoundedCornerShape(6.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    "✓ 当前为标准版本号对应的最新构建\n（对比远端 release 的标准版本号一致）",
                    fontSize = 12.sp,
                    color = Color(0xFF176F2C),
                    lineHeight = 20.sp,
                )
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun AboutInfoRow(key: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(key, fontSize = 13.sp, color = Primer.TextSecondary)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = Primer.TextPrimary,
            textAlign = TextAlign.End,
        )
    }
}
