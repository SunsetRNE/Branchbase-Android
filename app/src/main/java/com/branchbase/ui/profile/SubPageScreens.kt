package com.branchbase.ui.profile

import android.content.Context
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.branchbase.BuildConfig
import com.branchbase.core.AccountStore
import com.branchbase.core.LocalRepos
import com.branchbase.core.RustBridge
import com.branchbase.ui.log.Logger
import com.branchbase.ui.notification.NotifLayout
import com.branchbase.ui.notification.readNotifLayout
import com.branchbase.ui.notification.writeNotifLayout
import com.branchbase.ui.theme.LanguageColors
import com.branchbase.ui.theme.AppIcon
import com.branchbase.ui.theme.Primer
import com.branchbase.ui.task.TaskKind
import com.branchbase.ui.task.TaskStore
import com.branchbase.ui.decision.AuthorIdentityScreen
import com.branchbase.ui.decision.DeleteRepoWarningScreen
import com.branchbase.ui.decision.ForkDecisionScreen
import com.branchbase.ui.decision.GitifyRollbackScreen
import com.branchbase.ui.decision.StageCommitScreen
import com.branchbase.ui.decision.StageFile
import com.branchbase.ui.decision.UpstreamSetupScreen
import com.branchbase.ui.decision.UndoCommitScreen
import com.branchbase.ui.decision.parseGitStatus
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
    Projects("项目"),
    EditProfile("编辑资料"),
    Settings("设置"),
    LocalRepo("本地仓库"),
    About("关于"),
    Log("日志"),
    NotificationSettings("通知设置"),
    Tasks("任务"),
    Accounts("账号"),
    CommitMode("提交模式"),
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
internal fun SubPageHeader(title: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
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
    val context = LocalContext.current
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
        // 预加载首屏几个仓库的详情（点进去直接命中缓存；计费网络自动跳过）
        com.branchbase.cache.RepoPrefetcher.warmList(
            context = context,
            host = host,
            token = token,
            entries = repos.map {
                it.fullName.substringBefore('/') to it.fullName.substringAfter('/', "")
            },
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Primer.BackgroundPrimary).statusBarsPadding().navigationBarsPadding(),
    ) {
        SubPageHeader("星标", onBack) {
            Text("${repos.size}", fontSize = 13.sp, color = Primer.TextTertiary)
            Spacer(Modifier.width(12.dp))
            RefreshButton { refreshKey++ }
        }
        // 原先这里是一个不可点的「搜索星标」占位框，已随占位清理移除；
        // 需要搜索时走首页的搜索入口（全局搜索页支持按仓库/代码等类型检索）。
        if (loading) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("加载中", color = Primer.TextTertiary) }
        } else if (repos.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("暂无星标", fontSize = 13.sp, color = Primer.TextTertiary) }
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
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("加载中", color = Primer.TextTertiary) }
        } else if (projects.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("暂无项目", fontSize = 13.sp, color = Primer.TextTertiary) }
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
fun SettingsScreen(onBack: () -> Unit, onOpenLocalRepo: () -> Unit, onOpenAbout: () -> Unit, onOpenLog: () -> Unit, onOpenNotificationSettings: () -> Unit, onOpenAccounts: () -> Unit, onOpenCommitMode: () -> Unit) {
    LaunchedEffect(Unit) { Logger.ui("进入设置页", "Compose") }
    val context = LocalContext.current
    var mode by remember { mutableStateOf(commitMode(context)) } // CommitMode?，null = 未配置
    var showProxyDialog by remember { mutableStateOf(false) }
    var proxyInput by remember { mutableStateOf(gitProxy(context)) }
    var proxyFeedback by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().background(Primer.BackgroundPrimary).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        SubPageHeader("设置", onBack)

        SettingsSectionTitle("提交")
        SettingsItem(
            Icons.Filled.Check,
            "提交模式",
            value = mode?.label ?: "未配置",
            onClick = onOpenCommitMode,
        )

        SettingsSectionTitle("本地仓库")
        LocalRepoEntry(enabled = mode == CommitMode.LOCAL_REPO, onClick = onOpenLocalRepo)

        SettingsSectionTitle("网络")
        SettingsItem(
            Icons.Filled.Build,
            if (gitProxy(context).isBlank()) "Git 代理（未设置）" else "Git 代理：${gitProxy(context)}",
            onClick = { showProxyDialog = true },
        )

        SettingsSectionTitle("账号")
        SettingsItem(Icons.Filled.AccountCircle, "账号管理", onClick = onOpenAccounts)

        SettingsSectionTitle("其他")
        SettingsItem(Icons.Filled.Notifications, "通知", onClick = onOpenNotificationSettings)
        SettingsItem(Icons.Filled.Info, "关于", onClick = onOpenAbout)
        SettingsItem(Icons.Filled.Build, "日志", onClick = onOpenLog)

        proxyFeedback?.let {
            Text(it, fontSize = 12.sp, color = if (it.startsWith("已")) Primer.Green500 else Primer.Red500, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }
    }

    if (showProxyDialog) {
        AlertDialog(
            onDismissRequest = { showProxyDialog = false },
            title = { Text("Git 代理") },
            text = {
                Column {
                    Text(
                        "libgit2（本地仓库 clone/pull/push）的 HTTP 代理。留空表示不使用代理。",
                        fontSize = 12.sp,
                        color = Primer.TextTertiary,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = proxyInput,
                        onValueChange = { proxyInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("http://127.0.0.1:7890 或 socks5://127.0.0.1:1080", fontSize = 12.sp, color = Primer.TextTertiary) },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val ok = RustBridge.setGitProxy(context.cacheDir.absolutePath, proxyInput.trim())
                    if (ok) {
                        context.getSharedPreferences("branchbase", Context.MODE_PRIVATE)
                            .edit().putString(KEY_GIT_PROXY, proxyInput.trim()).apply()
                        proxyFeedback = if (proxyInput.isBlank()) "已清除 Git 代理" else "已设置 Git 代理"
                    } else {
                        proxyFeedback = "设置失败（引擎不可用）"
                    }
                    showProxyDialog = false
                }) { Text("保存", color = Primer.Blue500) }
            },
            dismissButton = { TextButton(onClick = { showProxyDialog = false }) { Text("取消") } },
        )
    }
}

internal const val KEY_GIT_PROXY = "git_proxy"

/** 读取已保存的 Git 代理。 */
internal fun gitProxy(context: Context): String =
    context.getSharedPreferences("branchbase", Context.MODE_PRIVATE).getString(KEY_GIT_PROXY, "") ?: ""

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
internal fun ModeOptionRow(label: String, desc: String, selected: Boolean, onClick: () -> Unit) {
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
internal fun SettingsItem(icon: ImageVector, name: String, value: String? = null, onClick: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp).clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = name, tint = Primer.IconSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(name, fontSize = 14.sp, color = Primer.TextPrimary, modifier = Modifier.weight(1f))
        if (!value.isNullOrBlank()) {
            Text(value, fontSize = 12.sp, color = Primer.TextTertiary)
            Spacer(Modifier.width(6.dp))
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Primer.TextTertiary, modifier = Modifier.size(20.dp))
    }
}

// ───────────────────────── 本地仓库列表页 ─────────────────────────

/** 本地仓库页内页面状态机（列表 / 各决策页）。 */
private sealed interface LocalPage {
    data object List : LocalPage
    data class Fork(val name: String) : LocalPage
    data class Undo(val name: String) : LocalPage
    data class Upstream(val name: String) : LocalPage
    data class Rollback(val name: String) : LocalPage
    data class DeleteWarn(val name: String, val unpushed: kotlin.collections.List<com.branchbase.ui.decision.UnpushedCommit>) : LocalPage
    data class Stage(val name: String) : LocalPage
    data class Identity(val name: String, val message: String) : LocalPage
    /** 本地分支管理（列表 / 切换 / 新建 / 删除） */
    data class Branches(val name: String) : LocalPage
    /** 本地 ↔ 远端分支同步（fetch + 按分支推送/拉取/建跟踪） */
    data class Sync(val name: String) : LocalPage
}

/**
 * 本地仓库列表页。每个仓库独立 Git（更新/删除），「＋拉取仓库」列出我的仓库并浅 clone。
 * clone 通过 `RustBridge.gitClone`（libgit2）。
 * 决策收口：pull/push 分叉 → Fork 页；删除升级警告；提交/撤销/上游/回退入口。
 */
@Composable
fun LocalRepoScreen(sessionJson: String, onBack: () -> Unit) {
    LaunchedEffect(Unit) { Logger.ui("进入本地仓库页", "Compose") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accountLogin = remember { AccountStore.currentLogin(context) }
    val repoRoot = remember(accountLogin) { LocalRepos.rootFor(context, accountLogin) }
    var repos by remember { mutableStateOf(listLocalRepos(repoRoot)) }
    val host = remember(sessionJson) { runCatching { JSONObject(sessionJson).optString("host", "github.com") }.getOrDefault("github.com") }
    val token = remember(sessionJson) {
        runCatching { JSONObject(sessionJson).optJSONObject("token")?.optString("access_token").orEmpty() }.getOrDefault("")
    }
    val login = remember(sessionJson) {
        runCatching { JSONObject(sessionJson).optJSONObject("user")?.optString("login").orEmpty() }.getOrDefault("")
    }

    var myRepos by remember { mutableStateOf<List<RepoItem>>(emptyList()) }
    var showPicker by remember { mutableStateOf(false) }
    var loadingRepos by remember { mutableStateOf(false) }
    var cloning by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    // 决策页状态机
    var page by remember { mutableStateOf<LocalPage>(LocalPage.List) }

    // 提示统一走 Snackbar：浮在内容之上，不占用列表布局、不挤动页面
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(feedback) {
        val msg = feedback ?: return@LaunchedEffect
        feedback = null
        snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
    }

    fun authorName() = context.getSharedPreferences("branchbase", Context.MODE_PRIVATE)
        .getString("commit.author.name", "") ?: "Branchbase"

    fun authorEmail() = context.getSharedPreferences("branchbase", Context.MODE_PRIVATE)
        .getString("commit.author.email", "") ?: "branchbase@users.noreply.github.com"

    fun dirOf(name: String) = File(repoRoot, name).absolutePath

    // 各仓库当前分支（用于行内显示；随仓库列表变化刷新）
    var branchMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(repos) {
        val m = mutableMapOf<String, String>()
        repos.forEach { r ->
            val st = withContext(Dispatchers.IO) {
                RustBridge.gitStatus(dirOf(r))?.let { parseGitStatus(it) }
            }
            st?.branch?.takeIf { it.isNotBlank() }?.let { m[r] = it }
        }
        branchMap = m
    }
    fun branchOf(name: String) = branchMap[name] ?: "—"

    /** pull 三态：成功 / 分叉（→ Fork 决策页）/ 失败。 */
    fun doPull(name: String) {
        scope.launch {
            feedback = null
            val taskId = TaskStore.start(context, TaskKind.PULL, "更新仓库 $name")
            val pullResult = withContext(Dispatchers.IO) { RustBridge.gitPullDetailed(dirOf(name), token) }
            // 错误同时落日志：UI 的 feedback 一闪而过无法回看，日志才能事后定位
            Logger.net("git pull ($name) → ${pullResult ?: "成功"}", "LocalGit")
            when (pullResult) {
                null -> { TaskStore.success(context, taskId, "已更新"); feedback = "已更新 $name" }
                "nff" -> { TaskStore.fail(context, taskId, "本地与远端分叉（需决策）"); page = LocalPage.Fork(name) }
                else -> { TaskStore.fail(context, taskId, pullResult); feedback = "更新失败：$pullResult" }
            }
        }
    }

    /** push 三态：无 upstream 先引导设置（P2-2），被拒 → Fork 决策页。 */
    fun doPush(name: String) {
        scope.launch {
            feedback = null
            val st = withContext(Dispatchers.IO) { RustBridge.gitStatus(dirOf(name))?.let { parseGitStatus(it) } }
            if (st == null) { feedback = "无法读取仓库状态（引擎不可用）"; return@launch }
            if (!st.hasUpstream) { page = LocalPage.Upstream(name); return@launch }
            val taskId = TaskStore.start(context, TaskKind.PUSH, "推送仓库 $name")
            val pushResult = withContext(Dispatchers.IO) { RustBridge.gitPushDetailed(dirOf(name), token, st.branch) }
            Logger.net("git push ${st.branch} ($name) → ${pushResult ?: "成功"}", "LocalGit")
            when (pushResult) {
                null -> { TaskStore.success(context, taskId, "已推送 ${st.branch}"); feedback = "已推送 $name" }
                "nff" -> { TaskStore.fail(context, taskId, "推送被拒（远端领先）"); page = LocalPage.Fork(name) }
                else -> { TaskStore.fail(context, taskId, pushResult); feedback = "推送失败：$pushResult" }
            }
        }
    }

    /** 删除请求：ahead>0 时升级为独立确认页（P1-3），否则普通确认框。 */
    fun doDeleteRequest(name: String) {
        scope.launch {
            val st = withContext(Dispatchers.IO) { RustBridge.gitStatus(dirOf(name))?.let { parseGitStatus(it) } }
            if (st != null && st.ahead > 0) {
                page = LocalPage.DeleteWarn(name, st.unpushed)
            } else {
                deleteTarget = name
            }
        }
    }

    /** 本地提交入口：工作区有改动才进暂存页（P0-2）。 */
    fun doStageCommit(name: String) {
        scope.launch {
            val st = withContext(Dispatchers.IO) { RustBridge.gitStatus(dirOf(name))?.let { parseGitStatus(it) } }
            if (st == null || st.dirty.isEmpty()) { feedback = "工作区没有改动可提交"; return@launch }
            page = LocalPage.Stage(name)
        }
    }

    /** 执行本地 git commit（identity 已就绪）。 */
    fun doGitCommit(repoName: String, message: String) {
        scope.launch {
            val taskId = TaskStore.start(context, TaskKind.COMMIT, "本地提交 $repoName")
            val sha = withContext(Dispatchers.IO) {
                RustBridge.gitCommit(dirOf(repoName), message, authorName(), authorEmail())
            }
            Logger.net("git commit ($repoName) → ${sha?.take(7) ?: "失败"}", "LocalGit")
            if (sha != null) TaskStore.success(context, taskId, "已提交 $sha")
            else TaskStore.fail(context, taskId, "提交失败（引擎不可用）")
            feedback = if (sha != null) "已提交（本地 git）" else "提交失败（引擎不可用）"
            page = LocalPage.List
        }
    }

    /** 提交前身份检查（P0-3：首次无签名时先配置）。 */
    fun commitOrIdentity(name: String, message: String) {
        val prefs = context.getSharedPreferences("branchbase", Context.MODE_PRIVATE)
        if (prefs.getString("commit.author.name", null) == null || prefs.getString("commit.author.email", null) == null) {
            page = LocalPage.Identity(name, message)
        } else {
            doGitCommit(name, message)
        }
    }

    // ── 决策页分发 ──
    when (val p = page) {
        is LocalPage.Fork -> {
            ForkDecisionScreen(
                repoName = p.name,
                repoDir = dirOf(p.name),
                token = token,
                onBack = { page = LocalPage.List },
                onResolved = { msg -> feedback = msg; page = LocalPage.List },
            )
            return
        }
        is LocalPage.Undo -> {
            UndoCommitScreen(
                repoName = p.name,
                repoDir = dirOf(p.name),
                onBack = { page = LocalPage.List },
                onResolved = { msg -> feedback = msg; page = LocalPage.List },
            )
            return
        }
        is LocalPage.Upstream -> {
            UpstreamSetupScreen(
                repoName = p.name,
                repoDir = dirOf(p.name),
                token = token,
                onBack = { page = LocalPage.List },
                onResolved = { msg, fork ->
                    if (fork) page = LocalPage.Fork(p.name)
                    else { feedback = msg; page = LocalPage.List }
                },
            )
            return
        }
        is LocalPage.Rollback -> {
            GitifyRollbackScreen(
                repoName = p.name,
                repoDir = dirOf(p.name),
                onBack = { page = LocalPage.List },
                onDeletedRepo = {
                    scope.launch {
                        val st = withContext(Dispatchers.IO) { RustBridge.gitStatus(dirOf(p.name))?.let { parseGitStatus(it) } }
                        page = LocalPage.DeleteWarn(p.name, st?.unpushed ?: emptyList())
                    }
                },
                onResolved = { msg -> feedback = msg; page = LocalPage.List },
            )
            return
        }
        is LocalPage.DeleteWarn -> {
            DeleteRepoWarningScreen(
                repoName = p.name,
                unpushed = p.unpushed,
                onBack = { page = LocalPage.List },
                onPushFirst = {
                    // 先推送再删：直接走 push（无 upstream 引导设置；被拒转分叉）
                    page = LocalPage.List
                    doPush(p.name)
                },
                onDelete = {
                    scope.launch {
                        File(repoRoot, p.name).deleteRecursively()
                        repos = listLocalRepos(repoRoot)
                        feedback = "已删除 ${p.name}"
                        page = LocalPage.List
                    }
                },
            )
            return
        }
        is LocalPage.Stage -> {
            var dirtyFiles by remember(p.name) { mutableStateOf<List<StageFile>>(emptyList()) }
            LaunchedEffect(p.name) {
                val st = withContext(Dispatchers.IO) { RustBridge.gitStatus(dirOf(p.name))?.let { parseGitStatus(it) } }
                dirtyFiles = st?.dirty?.map { StageFile(it.path, it.status, checked = true) } ?: emptyList()
            }
            StageCommitScreen(
                repoName = p.name,
                files = dirtyFiles,
                mode = CommitMode.LOCAL_REPO,
                onBack = { page = LocalPage.List },
                onPickMode = { /* 已由弹窗固化 */ },
                onCommit = { message, _ -> commitOrIdentity(p.name, message) },
            )
            return
        }
        is LocalPage.Identity -> {
            AuthorIdentityScreen(
                suggestedName = login.ifBlank { "Branchbase" },
                suggestedEmail = "${login.ifBlank { "branchbase" }}@users.noreply.github.com",
                onBack = { page = LocalPage.List },
                onConfirm = { name, email, save ->
                    if (save) {
                        context.getSharedPreferences("branchbase", Context.MODE_PRIVATE)
                            .edit().putString("commit.author.name", name).putString("commit.author.email", email).apply()
                    }
                    doGitCommit(p.name, p.message)
                },
            )
            return
        }
        is LocalPage.Branches -> {
            var branchList by remember(p.name) { mutableStateOf<List<LocalBranch>>(emptyList()) }
            var branchDirty by remember(p.name) { mutableIntStateOf(0) }
            var branchBusy by remember(p.name) { mutableStateOf(false) }
            var branchError by remember(p.name) { mutableStateOf<String?>(null) }
            var reloadKey by remember(p.name) { mutableIntStateOf(0) }

            LaunchedEffect(p.name, reloadKey) {
                branchList = withContext(Dispatchers.IO) { parseLocalBranches(RustBridge.localBranches(dirOf(p.name))) }
                branchDirty = withContext(Dispatchers.IO) {
                    RustBridge.gitStatus(dirOf(p.name))?.let { parseGitStatus(it) }?.dirty?.size ?: 0
                }
            }

            BranchesScreen(
                repoName = p.name,
                branches = branchList,
                dirtyCount = branchDirty,
                busy = branchBusy,
                error = branchError,
                onBack = { page = LocalPage.List },
                onRefresh = { reloadKey++ },
                onCheckout = { target ->
                    scope.launch {
                        branchBusy = true
                        branchError = null
                        // 脏工作区：用户已在对话框确认「撤销并切换」
                        if (branchDirty > 0) {
                            val discardErr = withContext(Dispatchers.IO) { RustBridge.discardAllChanges(dirOf(p.name)) }
                            if (discardErr != null) {
                                branchError = discardErr
                                branchBusy = false
                                return@launch
                            }
                        }
                        val err = withContext(Dispatchers.IO) { RustBridge.checkoutBranch(dirOf(p.name), target) }
                        Logger.net("git checkout $target (${p.name}) → ${err ?: "成功"}", "LocalGit")
                        branchBusy = false
                        if (err == null) {
                            feedback = "已切换到 $target"
                            page = LocalPage.List
                        } else {
                            branchError = err
                        }
                    }
                },
                onDelete = { target ->
                    scope.launch {
                        branchBusy = true
                        branchError = null
                        val err = withContext(Dispatchers.IO) { RustBridge.deleteBranchLocal(dirOf(p.name), target) }
                        Logger.net("git branch -d $target (${p.name}) → ${err ?: "成功"}", "LocalGit")
                        branchBusy = false
                        if (err == null) {
                            feedback = "已删除分支 $target"
                            reloadKey++
                        } else {
                            branchError = err
                        }
                    }
                },
                onCreate = { newBranch ->
                    scope.launch {
                        branchBusy = true
                        branchError = null
                        val err = withContext(Dispatchers.IO) {
                            RustBridge.createBranchLocal(dirOf(p.name), newBranch, "")
                        }
                        Logger.net("git switch -c $newBranch (${p.name}) → ${err ?: "成功"}", "LocalGit")
                        branchBusy = false
                        if (err == null) {
                            feedback = "已创建并切换到 $newBranch"
                            page = LocalPage.List
                        } else {
                            branchError = err
                        }
                    }
                },
            )
            return
        }
        is LocalPage.Sync -> {
            com.branchbase.ui.repository.LocalBranchSyncScreen(
                dir = dirOf(p.name),
                repoName = p.name,
                token = token,
                onBack = { page = LocalPage.List },
                onChanged = { repos = listLocalRepos(repoRoot) },
            )
            return
        }
        is LocalPage.List -> Unit
    }

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
        // repos 根目录必须存在（libgit2 clone 不会自动创建父目录）
        if (!repoRoot.exists() && !repoRoot.mkdirs()) {
            feedback = "无法创建本地仓库目录"
            return
        }
        val target = File(repoRoot, name)
        if (target.exists()) {
            feedback = "「$name」已存在"
            return
        }
        scope.launch {
            cloning = true
            feedback = null
            val taskId = TaskStore.start(context, TaskKind.CLONE, "拉取仓库 $fullName")
            val error = RustBridge.gitCloneDetailed("https://github.com/$fullName", target.absolutePath, "", token)
            if (error == null) TaskStore.success(context, taskId, "已拉取到 ${target.name}")
            else TaskStore.fail(context, taskId, error)
            Logger.remote(if (error == null) "git clone $fullName 完成" else "git clone $fullName 失败：$error", "libgit2")
            cloning = false
            feedback = if (error == null) "已拉取 $name" else "拉取失败：$error"
            repos = listLocalRepos(repoRoot)
        }
    }

    // Box 包裹：Snackbar 浮在内容之上，不占用列表布局、不会挤动页面
    Box(Modifier.fillMaxSize()) {
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

        if (repos.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Folder, null, tint = Primer.IconSecondary, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("暂无本地仓库", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
                    Spacer(Modifier.height(4.dp))
                    Text("点击「拉取仓库」将仓库克隆到本地", fontSize = 12.sp, color = Primer.TextTertiary)
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(repos) { name ->
                    LocalRepoRow(
                        name = name,
                        branch = branchOf(name),
                        onBranches = { page = LocalPage.Branches(name) },
                        onSync = { page = LocalPage.Sync(name) },
                        onPull = { doPull(name) },
                        onPush = { doPush(name) },
                        onCommit = { doStageCommit(name) },
                        onUndo = { page = LocalPage.Undo(name) },
                        onUpstream = { page = LocalPage.Upstream(name) },
                        onRollback = { page = LocalPage.Rollback(name) },
                        onDelete = { doDeleteRequest(name) },
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
        SnackbarHost(
            snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
        )
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
private fun LocalRepoRow(
    name: String,
    branch: String,
    onBranches: () -> Unit,
    onSync: () -> Unit,
    onPull: () -> Unit,
    onPush: () -> Unit,
    onCommit: () -> Unit,
    onUndo: () -> Unit,
    onUpstream: () -> Unit,
    onRollback: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, Primer.Border, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Primer.Blue500)
            Spacer(Modifier.weight(1f))
            // 当前分支胶囊 → 分支管理页
            Row(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Primer.Gray150)
                    .clickable { onBranches() }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("⑂ ${branch.ifBlank { "—" }}", fontSize = 11.sp, color = Primer.TextSecondary)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("更新", fontSize = 12.sp, color = Primer.Blue500, modifier = Modifier.clickable { onPull() })
            Text("推送", fontSize = 12.sp, color = Primer.Blue500, modifier = Modifier.clickable { onPush() })
            Text("分支同步", fontSize = 12.sp, color = Primer.Blue500, modifier = Modifier.clickable { onSync() })
            Text("提交", fontSize = 12.sp, color = Primer.Green500, modifier = Modifier.clickable { onCommit() })
            Text("撤销", fontSize = 12.sp, color = Primer.TextSecondary, modifier = Modifier.clickable { onUndo() })
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("上游", fontSize = 12.sp, color = Primer.TextSecondary, modifier = Modifier.clickable { onUpstream() })
            Text("回退 Git 化", fontSize = 12.sp, color = Primer.TextSecondary, modifier = Modifier.clickable { onRollback() })
            Text("删除", fontSize = 12.sp, color = Primer.Red500, modifier = Modifier.clickable { onDelete() })
        }
    }
}

// ───────────────────────── 本地分支管理 ─────────────────────────

/** 本地分支（nativeLocalBranches 的 JSON 解析结果）。 */
internal data class LocalBranch(
    val name: String,
    val isHead: Boolean,
    val upstream: String,
    val ahead: Int,
    val behind: Int,
)

private fun parseLocalBranches(json: String?): List<LocalBranch> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            LocalBranch(
                name = o.optString("name"),
                isHead = o.optBoolean("is_head", false),
                upstream = o.optString("upstream"),
                ahead = o.optInt("ahead", 0),
                behind = o.optInt("behind", 0),
            )
        }
    }.getOrDefault(emptyList())
}

/** main / master 视为受保护分支：删除入口置灰不可点。 */
private fun isProtectedBranch(name: String) = name == "main" || name == "master"

/**
 * 本地分支管理页。
 *
 * 交互约定（对齐本轮确定的规则）：
 * - 脏工作区切换：先弹确认，确认后**撤销所有改动再切换**（不做隐式 stash）
 * - 删除：当前分支不显示删除入口；main/master 置灰
 * - 新建：基于当前分支，创建后自动切换
 */
@Composable
private fun BranchesScreen(
    repoName: String,
    branches: List<LocalBranch>,
    dirtyCount: Int,
    busy: Boolean,
    error: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onCheckout: (String) -> Unit,
    onDelete: (String) -> Unit,
    onCreate: (String) -> Unit,
) {
    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var confirmSwitch by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().background(Primer.BackgroundPrimary)
            .statusBarsPadding().navigationBarsPadding(),
    ) {
        SubPageHeader("分支 · $repoName", onBack) { RefreshButton { onRefresh() } }

        if (dirtyCount > 0) {
            Text(
                "工作区有 $dirtyCount 个改动；切换分支若会覆盖它们将被拒绝",
                fontSize = 11.5.sp,
                color = Color(0xFF7A5B00),
                lineHeight = 16.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(8.dp)).background(Color(0xFFFFF8E5))
                    .padding(10.dp),
            )
        }
        error?.let {
            Text(
                it,
                fontSize = 12.sp,
                color = Primer.Red500,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        LazyColumn(Modifier.weight(1f)) {
            items(branches) { b ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(enabled = !b.isHead && !busy) {
                            if (dirtyCount > 0) confirmSwitch = b.name else onCheckout(b.name)
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (b.isHead) "●" else "○",
                        fontSize = 11.sp,
                        color = if (b.isHead) Primer.Green500 else Primer.TextTertiary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                b.name,
                                fontSize = 13.5.sp,
                                fontWeight = if (b.isHead) FontWeight.SemiBold else FontWeight.Normal,
                                color = Primer.TextPrimary,
                            )
                            if (b.isHead) {
                                Spacer(Modifier.width(6.dp))
                                Text("当前", fontSize = 10.sp, color = Primer.Green500)
                            }
                        }
                        val meta = buildString {
                            if (b.upstream.isNotBlank()) append(b.upstream)
                            if (b.ahead > 0) { if (isNotEmpty()) append(" · "); append("↑${b.ahead}") }
                            if (b.behind > 0) { if (isNotEmpty()) append(" · "); append("↓${b.behind}") }
                        }
                        if (meta.isNotBlank()) {
                            Text(meta, fontSize = 10.5.sp, color = Primer.TextTertiary, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                    if (!b.isHead) {
                        val protected = isProtectedBranch(b.name)
                        Text(
                            "删除",
                            fontSize = 12.sp,
                            color = if (protected) Primer.TextTertiary else Primer.Red500,
                            modifier = Modifier.clickable(enabled = !protected && !busy) { confirmDelete = b.name },
                        )
                    }
                }
            }
            item {
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, Primer.Border, RoundedCornerShape(10.dp))
                        .clickable { showCreate = true }
                        .padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("＋ 新建分支", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Primer.Blue500)
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("新建分支", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary) },
            text = {
                Column {
                    Text(
                        "基于当前分支创建，创建后自动切换过去。",
                        fontSize = 12.sp, color = Primer.TextTertiary, lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        placeholder = { Text("分支名，如 feature/login", fontSize = 12.sp, color = Primer.TextTertiary) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newName.isNotBlank() && !busy,
                    onClick = { val n = newName.trim(); showCreate = false; newName = ""; onCreate(n) },
                ) { Text("创建并切换", color = Primer.Blue500) }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("取消") } },
        )
    }

    confirmDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除分支 $target？", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary) },
            text = {
                Text(
                    "只删除本地分支，不影响远端；未合并的提交会丢失。",
                    fontSize = 12.sp, color = Primer.TextTertiary, lineHeight = 18.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = null; onDelete(target) }) { Text("删除", color = Primer.Red500) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消") } },
        )
    }

    confirmSwitch?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmSwitch = null },
            title = { Text("先撤销改动？", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary) },
            text = {
                Text(
                    "工作区有 $dirtyCount 个改动，切换分支可能失败或覆盖它们。\n" +
                        "确认后会先撤销所有改动再切换（此操作不可撤销）。",
                    fontSize = 12.sp, color = Primer.TextTertiary, lineHeight = 18.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmSwitch = null; onCheckout(target) }) {
                    Text("撤销并切换", color = Primer.Red500)
                }
            },
            dismissButton = { TextButton(onClick = { confirmSwitch = null }) { Text("取消") } },
        )
    }
}

// ───────────────────────── 关于页 ─────────────────────────

/**
 * 关于页：展示应用图标 + 版本号信息（工程/标准/Git 包/构建时间/七位哈希）+ 最新构建校验提示。
 * 版本口径：工程版本（semver）+ 标准版本（版本-时间-哈希），并做本地/远端签名指纹对照。
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
            // weight(1f)：与顶部 SubPageHeader 同级；fillMaxSize() 会超出容器，底部内容被压住
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            // 应用图标：与桌面完全一致的那枚（PackageManager 合成自适应图标两个图层）；
            // 曾经的手搓近似（只画 foreground + 硬编码 #0d1117）与真实图标并不一致，已移除。
            AppIcon(size = 80.dp, shape = RoundedCornerShape(22.dp))
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
                // 第三方代码编辑器痕迹：独立模块 :editor 封装，移除时删这行 + 该模块
                AboutInfoRow(
                    "代码编辑器",
                    "${com.branchbase.editor.EditorModuleInfo.NAME} " +
                        "${com.branchbase.editor.EditorModuleInfo.VERSION}（${com.branchbase.editor.EditorModuleInfo.MODULE}）",
                )
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
            // 构建校验横幅：由真实校验数据推导的**五种状态**（原先是写死的绿底文案，
            // 校验失败/进行中/取不到远端文件都显示同一句「✓ 最新构建」）
            BuildVerifyBanner(
                state = buildVerifyState(
                    variant = variant,
                    localFingerprint = localFingerprint,
                    remoteFingerprint = remoteFingerprint,
                    checking = remoteChecking,
                ),
                variant = variant,
                localFingerprint = localFingerprint,
                remoteFingerprint = remoteFingerprint,
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

/** 校验横幅的配色与文案（五态各自独立，不再共用一句写死的结论）。 */
@Composable
private fun BuildVerifyBanner(
    state: BuildVerifyState,
    variant: ReleaseVariant,
    localFingerprint: String,
    remoteFingerprint: String?,
) {
    // 文案与配色都按状态取：绿=通过、红=不一致、蓝=进行中、琥珀=取不到远端、灰=本地编译
    val (title, detail, fg, bg, border) = when (state) {
        BuildVerifyState.Checking -> BannerStyle(
            "正在校验…",
            "读取本地签名指纹，并拉取远端校验文件",
            Primer.Blue500,
            Color(0xFFF0F7FF),
            Color(0xFFCFE3F7),
        )
        BuildVerifyState.LocalBuild -> BannerStyle(
            "本地编译版本",
            "签名不在正式版 / 测试版之列，不参与远端校验（远端只登记官方发布产物）",
            Primer.TextSecondary,
            Primer.Gray100,
            Primer.Gray200,
        )
        BuildVerifyState.RemoteUnavailable -> BannerStyle(
            "无法完成远端校验",
            "取不到远端校验文件：网络不可达，或该分支尚未发布校验文件",
            Color(0xFF9A6700),
            Color(0xFFFFF8E5),
            Color(0xFFF2D08A),
        )
        BuildVerifyState.Matched -> BannerStyle(
            "✓ 签名与远端一致",
            "本地 APK 签名 = 远端${variant.label}校验文件（${fingerprintShort(localFingerprint)}）",
            Color(0xFF176F2C),
            Color(0xFFF0FFF4),
            Color(0xFFD4E9D6),
        )
        BuildVerifyState.Mismatched -> BannerStyle(
            "✗ 签名与远端不一致",
            "本地 ${fingerprintShort(localFingerprint)} / 远端 ${fingerprintShort(remoteFingerprint.orEmpty())}，" +
                "该 APK 可能被重新打包，建议立即卸载",
            Primer.Red500,
            Color(0xFFFFEBE9),
            Color(0xFFF5C2C0),
        )
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column {
            Text(title, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = fg)
            Spacer(Modifier.height(3.dp))
            Text(detail, fontSize = 11.5.sp, color = fg.copy(alpha = 0.85f), lineHeight = 18.sp)
        }
    }
}

/** 横幅的（标题 / 说明 / 前景色 / 底色 / 描边色）。 */
private data class BannerStyle(
    val title: String,
    val detail: String,
    val fg: Color,
    val bg: Color,
    val border: Color,
)

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
