package com.branchbase.ui.profile

import androidx.annotation.StringRes
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import java.io.File
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
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.branchbase.ui.navigation.PageBackHandler
import com.branchbase.ui.repository.RepoRelation
import com.branchbase.ui.theme.selectionColor
import com.branchbase.BuildConfig
import com.branchbase.R
import com.branchbase.core.AccountStatus
import com.branchbase.core.AuthKind
import com.branchbase.core.RepoCredentialStore
import com.branchbase.ui.settings.currentAppLanguageTag
import com.branchbase.ui.settings.frameWatchEnabled
import com.branchbase.ui.settings.gitProxy
import com.branchbase.ui.settings.languagePickerAvailable
import com.branchbase.ui.settings.languageRowValue
import com.branchbase.ui.settings.setFrameWatchEnabled
import com.branchbase.ui.settings.supportedAppLanguages
import com.branchbase.translate.TranslateSettings
import com.branchbase.core.AccountStore
import com.branchbase.ui.log.FrameWatch
import com.branchbase.ui.log.LogLevel
import com.branchbase.ui.log.LogManager
import com.branchbase.ui.settings.AccountRow
import com.branchbase.ui.settings.ChoiceRow
import com.branchbase.ui.settings.DangerRow
import com.branchbase.ui.settings.DisabledNavRow
import com.branchbase.ui.settings.NavRow
import com.branchbase.ui.settings.SettingsProse
import com.branchbase.ui.settings.SettingsSection
import com.branchbase.ui.settings.StatusChip
import com.branchbase.ui.settings.StatusTone
import com.branchbase.ui.settings.SwitchRow
import com.branchbase.ui.settings.displayGitProxy
import com.branchbase.ui.theme.ThemeMode
import com.branchbase.ui.theme.ThemeRuntime
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Commit
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Tune
import com.branchbase.core.LocalRepos
import com.branchbase.core.RustBridge
import com.branchbase.ui.log.Logger
import com.branchbase.ui.notification.NotifLayout
import com.branchbase.ui.notification.NotifLayoutRuntime
import com.branchbase.ui.notification.SystemNotificationState
import com.branchbase.ui.notification.rememberSystemNotificationState
import com.branchbase.ui.theme.iconTap
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
import com.branchbase.ui.decision.RepoStats
import com.branchbase.ui.decision.parseGitStatus
import com.branchbase.ui.decision.parseRepoStats
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
    Translate("沉浸式翻译"),
    Tasks("任务"),
    Accounts("账号"),
    CommitMode("提交模式"),
    GitProxy("Git 代理"),
    RepoCredentials("仓库凭据"),
    Language("语言"),
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
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back), tint = Primer.IconPrimary, modifier = Modifier.size(24.dp).iconTap { onBack() })
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
        contentDescription = stringResource(R.string.action_refresh),
        tint = Primer.Blue500,
        modifier = Modifier.size(20.dp).iconTap { onRefresh() },
    )
}

// ───────────────────────── 星标页 ─────────────────────────

@Composable
fun StarsScreen(sessionJson: String, onBack: () -> Unit, onOpenRepo: (String) -> Unit) {
    LaunchedEffect(Unit) { Logger.ui("进入星标页", "Compose") }
    val context = LocalContext.current
    val token = runCatching { JSONObject(sessionJson).getJSONObject("token").optString("access_token") }.getOrNull() ?: ""
    val host = runCatching { JSONObject(sessionJson).optString("host", "github.com") }.getOrDefault("github.com")
    // 关系判定要当前账号：星标里既有自己的仓库也有别人的，用于区分「我的 / 协作 / 他人」
    val me = remember(sessionJson, context) {
        runCatching { JSONObject(sessionJson).getJSONObject("user").optString("login") }
            .getOrNull()?.takeIf { it.isNotBlank() && it != "null" }
            ?: com.branchbase.core.AccountStore.currentLogin(context)
    }
    var repos by remember { mutableStateOf<List<RepoItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
        val cacheKey = "stars"
        // 快速预加载渲染：首次进入先读缓存
        if (refreshKey == 0) {
            val cached = ProfileCache.get(cacheKey, ProfileTtl.STARS)
            if (cached != null) {
                repos = parseRepos(cached, me = me)
                loading = false
                return@LaunchedEffect
            }
        }
        loading = true
        val json = withContext(Dispatchers.IO) { RustBridge.getStarredRepos(host, token) }
        Logger.net("GET /user/starred → ${if (json != null && !json.startsWith("ERROR:")) "200" else "失败"}", "GitHubAPI")
        if (json != null && !json.startsWith("ERROR:")) {
            repos = parseRepos(json, me = me)
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
        SubPageHeader(stringResource(R.string.nav_starred), onBack) {
            Text("${repos.size}", fontSize = 13.sp, color = Primer.TextTertiary)
            Spacer(Modifier.width(12.dp))
            RefreshButton { refreshKey++ }
        }
        // 原先这里是一个不可点的「搜索星标」占位框，已随占位清理移除；
        // 需要搜索时走首页的搜索入口（全局搜索页支持按仓库/代码等类型检索）。
        if (loading) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.state_loading), color = Primer.TextTertiary) }
        } else if (repos.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.state_no_starred), fontSize = 13.sp, color = Primer.TextTertiary) }
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
            Modifier.clip(RoundedCornerShape(6.dp)).background(Primer.WarningSurface).border(1.dp, Primer.Border, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.state_starred), fontSize = 12.sp, color = Primer.WarningTextStrong)
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
        SubPageHeader(stringResource(R.string.nav_projects), onBack) {
            RefreshButton { refreshKey++ }
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier.clip(RoundedCornerShape(6.dp)).background(Primer.Green500).padding(horizontal = 12.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(2.dp))
                    Text(stringResource(R.string.action_new), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White)
                }
            }
        }
        if (loading) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.state_loading), color = Primer.TextTertiary) }
        } else if (projects.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.state_no_projects), fontSize = 13.sp, color = Primer.TextTertiary) }
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

/**
 * 提交模式（三选项，对齐 commit-mode-decision-tree.md）。
 *
 * ## 为什么 `label` 必须短、长描述单独放 [title]
 *
 * 原来只有 `label` 一个字段，于是「本地仓库」模式把整句描述
 * （`文件拉取到本地仓库，由本地 git 管理提交推送`）当成了状态文案：设置页那一行、
 * 决策页副标题、编辑器底栏都直接用它 —— 这些位置是**单行状态位**，
 * 长文案挤掉同排的内容（设置页把「提交模式」这个名字压到换行、被 48dp 行高裁掉）。
 *
 * 现在按用途拆开：
 * - [labelRes]：短名，用于**状态位**（设置项右侧值 / 当前模式 / 编辑页底栏）；
 * - [titleRes]：完整说明，只用于**整行卡片**（提交模式页、首次引导页、提交时选择弹窗）；
 * - [descRes]：一句话补充，与 [titleRes] 搭配在卡片里显示。
 *
 * ## 三个字段都是 `@StringRes`，不是写死的中文
 *
 * 界面名走三个 `@StringRes`，日志名单独一个 [logLabel]（日志按约定固定中文，见 i18n 规范 §7）。
 * 原先这里是 `SINGLE_FILE("单个文件", …)`。枚举不是 `@Composable`，写死中文的后果是
 * **界面切英文后只有这三行还是中文**（2026-09 真机：英文模式下设置页「提交模式」右侧
 * 仍显示「单个文件」，提交模式页三张卡片也全是中文）。解析一律留给调用方（§5.1 路径 A′）。
 */
internal enum class CommitMode(
    @StringRes val labelRes: Int,
    @StringRes val titleRes: Int,
    @StringRes val descRes: Int,
    /** **日志**专用的中文名（与 `ProfileTab.logLabel` / `RepoPage.logLabel` 同一个约定）。 */
    val logLabel: String,
) {
    SINGLE_FILE(R.string.commit_mode_single_file_label, R.string.commit_mode_single_file_title, R.string.commit_mode_single_file_desc, "单个文件"),
    MULTI_FILE(R.string.commit_mode_multi_file_label, R.string.commit_mode_multi_file_title, R.string.commit_mode_multi_file_desc, "多文件合并"),
    LOCAL_REPO(R.string.commit_mode_local_repo_label, R.string.commit_mode_local_repo_title, R.string.commit_mode_local_repo_desc, "本地仓库（Git）"),
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenLocalRepo: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenLog: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenTranslate: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenCommitMode: () -> Unit,
    onOpenGitProxy: () -> Unit,
    onOpenRepoCredentials: () -> Unit,
    onOpenLanguage: () -> Unit,
    onLogout: () -> Unit,
) {
    LaunchedEffect(Unit) { Logger.ui("进入设置页", "Compose") }
    val context = LocalContext.current

    // 提交模式决定「本地仓库」那一行是导航行还是禁用态（规范 §6.3）
    // 进 remember：`commitMode` 是读 SharedPreferences 的，裸调会在**每次重组**都读一遍
    // （设置页首帧那一帧里整棵树要重组好几次，见 2026-09-22 的设置页首帧绘制归因）。
    // 本页不进保活（PageSwitcher 每次进入重建），所以 remember 的时效性等价于「每次进入读一次」。
    val mode = remember { commitMode(context) }
    val themeMode by ThemeRuntime.mode.collectAsState()
    val notificationPermission = rememberSystemNotificationState()
    val account = remember { AccountStore.current(context) }

    // 仓库级凭据只在**令牌登录模式**（PAT）下登记 —— 产品口径：入口的显示条件是
    // `AccountStore.current(context)?.auth == AuthKind.PAT`，OAuth 账号下**整行不出现**（不是置灰）。
    // 之所以不置灰：禁用行必须给「怎么才能开」的出路（规范 §6.3），而这里的出路是重新登录，
    // 不属于设置页能代办的事 —— 一行点不动的死行只会变成噪音。
    val patMode = account?.auth == AuthKind.PAT
    // 条数是读 prefs，进 remember：设置页组合期读盘一律不裸调（每次重组都会再读一遍）
    val repoCredentialCount = remember { if (patMode) RepoCredentialStore.all(context).size else 0 }

    var translateEnabled by remember { mutableStateOf(TranslateSettings.read(context).enabled) }
    var confirmLogout by remember { mutableStateOf(false) }
    // 慢帧日志开关：只在这里读一次盘（默认值来自编译通道），之后由用户点击驱动
    var frameWatch by remember { mutableStateOf(frameWatchEnabled(context)) }

    // 错误数只取一次快照：设置页不做高频重组，没必要给「日志」行挂订阅
    val logErrors = remember { LogManager.all().count { it.level == LogLevel.ERROR } }
    // 网络代理的显示值：同理进 remember —— 它原来写在 item 的组合体里，
    // 每次这一项被滚回来组合一次就要读一次 prefs
    val proxyValue = remember { displayGitProxy(gitProxy(context)).ifEmpty { context.getString(R.string.state_not_set) } }

    // 语言行的可见性与取值。三条都是系统状态（LocaleConfig / LocaleManager），
    // 同上进 remember —— 设置页组合期读系统服务一律不裸调。
    // ⚠️ 取键必须是 uiLocale：语言切换后值列要跟着变，用无参 remember 会停在切换前的语言上。
    val uiLocale = LocalConfiguration.current.locales[0]
    val languageAvailable = remember(uiLocale) { languagePickerAvailable(context) }
    val languageTag = remember(uiLocale) { currentAppLanguageTag(context) }
    val languageSupported = remember(uiLocale) { supportedAppLanguages(context) }

    LazyColumn(
        // 惰性化：设置页有 8 组卡片、二十多行，`Column + verticalScroll` 会在**首帧**
        // 把整棵树组合出来（真机实测「进入设置页」首帧 43~89ms，其中重组≈绘制）；
        // 换成 LazyColumn 后首帧只组合可见的那几组，剩下的滚到才建。
        modifier = Modifier.fillMaxSize().background(Primer.BackgroundPrimary).statusBarsPadding().navigationBarsPadding(),
    ) {

        item {
            SubPageHeader(stringResource(R.string.nav_settings), onBack)
        }

        item {
            Spacer(Modifier.height(6.dp))
        }

        // ── ① 账户：身份是第一信息（规范 §3.2） ──
        item {
            SettingsSection(stringResource(R.string.label_account_group)) {
                AccountRow(
                    login = account?.login,
                    host = account?.host,
                    // 账户卡的头像必须传下去：Avatar 内部按「本地缓存 → avatar_url → 首字母」
                    // 依次回落并做圆形裁切；漏传这一项，圈选处就只剩写死的首字母。
                    // 用 avatarUrl（快照缺失时回落会话 user.avatar_url）而不是裸 avatar。
                    avatar = account?.avatarUrl,
                    statusLabel = account?.status?.let { stringResource(it.labelRes) },
                    statusTone = account?.status?.let { accountStatusTone(it) } ?: StatusTone.MUTE,
                    onClick = onOpenAccounts,
                )
                // 仓库级凭据：账户组内、紧挨账号卡之后（不新建分组，规范 §3.2）。
                // 显示条件见上面的 `patMode`：OAuth 账号下整行不出现。
                if (patMode) {
                    NavRow(
                        icon = Icons.Filled.Key,
                        name = stringResource(R.string.nav_repo_credentials),
                        // 值列只报条数：令牌与本机 host 一律不进值列（规范 §4.3 / §6.5）。
                        // 数量用「N 条」，未配置用「未设置」（规范 §6.1：禁止「无」「空」「——」）
                        value = if (repoCredentialCount > 0) stringResource(R.string.label_credential_count, repoCredentialCount) else stringResource(R.string.state_not_set),
                        sub = stringResource(R.string.note_repo_credentials_scope),
                        onClick = onOpenRepoCredentials,
                    )
                }
            }
        }

        // ── ② 外观：三档分段控件，取代「点一下循环」（规范 §5.2） ──
        item {
            SettingsSection(stringResource(R.string.label_appearance_group)) {
                ChoiceRow(
                    icon = Icons.Filled.Palette,
                    name = stringResource(R.string.label_theme),
                    sub = stringResource(R.string.note_theme_follow_system),
                    // 三档名字走资源（`ThemeMode.labelRes`）：枚举不认识语言，解析只能在 composable 里做，
                    // 否则界面切英文后按钮仍是「跟随系统 / 浅色 / 深色」（`map` 是 inline，可以调 stringResource）
                    options = ThemeMode.entries.map { it to stringResource(it.labelRes) },
                    selected = themeMode,
                    onSelect = { ThemeRuntime.set(context, it) },
                    divider = false,
                )
                // 语言是**条件行**（同 §3.2.1 的「仓库凭据」）：API 33 以下没有 LocaleManager，
                // 清单里只有一种语言时也没有可选项 —— 两种情况下整行不出现，而不是置灰。
                // 判定收在 languagePickerAvailable 里（它同时覆盖这两个条件）。
                if (languageAvailable) {
                    NavRow(
                        icon = Icons.Filled.Language,
                        name = stringResource(R.string.settings_language),
                        // 值列与语言页里的名称同源（都是母语自称），别一处写 English、一处写「英语」
                        value = languageRowValue(
                            languageTag,
                            languageSupported,
                            stringResource(R.string.language_follow_system),
                        ),
                        onClick = onOpenLanguage,
                    )
                }
            }
        }

        // ── ③ 通知 ──
        item {
            SettingsSection(stringResource(R.string.nav_notifications)) {
                // 这一行**不是**开关：系统通知权限不是 App 的布尔值，App 只能申请或跳系统设置。
                // 用导航行 + 状态胶囊，才不会让「开了但系统没授权」变成一个骗人的开关（规范 §4.3）。
                // 状态与说明都是**资源**（`labelRes` / `hintRes`）：它们是系统状态，不跟语言走就永远是中文。
                NavRow(
                    icon = Icons.Filled.Notifications,
                    name = stringResource(R.string.nav_notifications),
                    sub = stringResource(notificationPermission.hintRes),
                    statusChip = {
                        StatusChip(
                            stringResource(notificationPermission.labelRes),
                            notificationPermissionTone(notificationPermission),
                        )
                    },
                    onClick = onOpenNotificationSettings,
                    divider = false,
                )
            }
        }

        // ── ④ 翻译 ──
        item {
            SettingsSection(stringResource(R.string.label_translation_group)) {
                SwitchRow(
                    icon = Icons.Filled.Translate,
                    name = stringResource(R.string.translate_auto_toggle),
                    sub = if (translateEnabled) {
                        stringResource(R.string.note_translate_enabled)
                    } else {
                        stringResource(R.string.note_translate_disabled)
                    },
                    checked = translateEnabled,
                    onCheckedChange = {
                        translateEnabled = it
                        TranslateSettings.setEnabled(context, it)
                    },
                    divider = false,
                )
                NavRow(
                    icon = Icons.Filled.Tune,
                    name = stringResource(R.string.translate_title),
                    onClick = onOpenTranslate,
                )
            }
        }

        // ── ⑤ 代码与提交 ──
        item {
            SettingsSection(stringResource(R.string.label_code_commit_group)) {
                NavRow(
                    icon = Icons.Filled.Commit,
                    name = stringResource(R.string.nav_commit_mode),
                    value = mode?.let { stringResource(it.labelRes) } ?: stringResource(R.string.state_not_set),
                    onClick = onOpenCommitMode,
                    divider = false,
                )
                if (mode == CommitMode.LOCAL_REPO) {
                    NavRow(
                        icon = Icons.Filled.Folder,
                        name = stringResource(R.string.nav_local_repo),
                        onClick = onOpenLocalRepo,
                    )
                } else {
                    // 禁用态**不能只调 alpha**：说明原因 + 给出去开启的路（规范 §6.3）
                    DisabledNavRow(
                        icon = Icons.Filled.Folder,
                        name = stringResource(R.string.nav_local_repo),
                        reason = stringResource(R.string.note_local_repo_requires_git_mode),
                        onFix = onOpenCommitMode,
                    )
                }
            }
        }

        // ── ⑥ 网络 ──
        item {
            SettingsSection(stringResource(R.string.label_network_group)) {
                NavRow(
                    icon = Icons.Filled.Language,
                    name = stringResource(R.string.nav_git_proxy),
                    // 值列只显示 host:port，凭据不进列表（规范 §6.5）
                    value = proxyValue,
                    sub = stringResource(R.string.note_proxy_scope),
                    onClick = onOpenGitProxy,
                    divider = false,
                )
            }
        }

        // ── ⑦ 关于与诊断：兜底组，位置固定（规范 §3.3） ──
        item {
            SettingsSection(stringResource(R.string.label_about_group)) {
                // 慢帧日志：诊断仪表。默认值随编译通道（Beta 开 / 正式版关，见 build.gradle.kts 的
                // FRAME_WATCH_DEFAULT），这里可以随时覆盖 —— 正式版用户要抓一次卡顿就靠它。
                SwitchRow(
                    icon = Icons.Filled.Speed,
                    name = stringResource(R.string.label_frame_watch),
                    sub = if (frameWatch) {
                        stringResource(R.string.note_frame_watch_desc)
                    } else {
                        stringResource(R.string.note_frame_watch_disabled)
                    },
                    checked = frameWatch,
                    onCheckedChange = {
                        frameWatch = it
                        setFrameWatchEnabled(context, it)
                        FrameWatch.setEnabled(it)
                    },
                    divider = false,
                )
                NavRow(
                    icon = Icons.AutoMirrored.Filled.Article,
                    name = stringResource(R.string.nav_logs),
                    value = if (logErrors > 0) pluralStringResource(R.plurals.label_error_count, logErrors, logErrors) else null,
                    onClick = onOpenLog,
                )
                NavRow(
                    icon = Icons.Filled.Info,
                    name = stringResource(R.string.nav_about),
                    value = BuildConfig.STANDARD_VERSION,
                    onClick = onOpenAbout,
                )
            }
        }

        // ── 危险动作：单独一组、放最末（规范 §7.2） ──
        item {
            SettingsSection {
                DangerRow(
                    icon = Icons.AutoMirrored.Filled.Logout,
                    name = if (account != null) stringResource(R.string.confirm_sign_out_title, account.login) else stringResource(R.string.action_sign_out),
                    hint = stringResource(R.string.label_irreversible),
                    onClick = { confirmLogout = true },
                    divider = false,
                )
            }
        }

        item {
            Spacer(Modifier.height(12.dp))
        }
    }

    // 二次确认：标题 = 动词 + 对象名；正文三条（会发生什么 / 影响范围 / 能否撤销）；
    // 确认按钮写**动词**，不写「确定」（规范 §7.2 / §7.3）
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = {
                Text(
                    if (account != null) stringResource(R.string.confirm_sign_out_title, account.login) else stringResource(R.string.action_sign_out),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Primer.TextPrimary,
                )
            },
            text = {
                Text(
                    stringResource(R.string.confirm_sign_out_body),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = Primer.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    onLogout()
                }) { Text(stringResource(R.string.action_sign_out), color = Primer.DangerText) }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}



/** 账号状态 → 胶囊语气（规范 §4.3：状态要能一眼分辨「好 / 警告 / 坏」）。 */
private fun accountStatusTone(status: AccountStatus): StatusTone = when (status) {
    AccountStatus.OK -> StatusTone.OK
    AccountStatus.UNKNOWN -> StatusTone.MUTE
    AccountStatus.LIMITED, AccountStatus.UNREACHABLE -> StatusTone.WARN
    AccountStatus.INVALID, AccountStatus.SUSPENDED -> StatusTone.BAD
}

/**
 * 系统通知状态 → 胶囊语气。
 *
 * 三档都要能一眼分开：开着 = OK；还能弹系统授权框 = WARN（差一步）；被系统关掉 = BAD
 * （App 里怎么点都没用，只能去系统设置）。全是 MUTE 的话，用户分不出「差一步」和「没救了」。
 */
private fun notificationPermissionTone(state: SystemNotificationState): StatusTone = when {
    state.granted -> StatusTone.OK
    state.canRequest -> StatusTone.WARN
    else -> StatusTone.BAD
}

/** 通知设置子页面：系统通知权限入口 + 通知列表显示模式。 */
@Composable
fun NotificationSettingsScreen(onBack: () -> Unit) {
    LaunchedEffect(Unit) { Logger.ui("进入通知设置页", "Compose") }
    val context = LocalContext.current
    // 显示模式读**单一真源**（`NotifLayoutRuntime`），不是本地 `remember { readNotifLayout() }`：
    // 这里选的模式要能立刻反映到消息页，vice versa —— 两个入口各持一份时，
    // 「选了平铺还是按仓库分组」就是这么来的（详见 `NotifLayoutRuntime` 的注释）。
    val layout by NotifLayoutRuntime.layout.collectAsState()
    // 系统通知（权限 + 总开关）：与下载通知、通知页横幅读同一份状态
    val permission = rememberSystemNotificationState()

    Column(
        modifier = Modifier.fillMaxSize().background(Primer.BackgroundPrimary).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        SubPageHeader(stringResource(R.string.nav_notifications), onBack)
        Spacer(Modifier.height(6.dp))

        SettingsSection(stringResource(R.string.label_system_notifications)) {
            // 已开启 → 进系统设置（可关掉 / 改渠道）；未开启 → 能弹授权框就弹，否则去设置页。
            // 两种形态**行型不同**（对齐「本地仓库」那一行）：
            // - 已开启：导航行 + 状态胶囊，状态是「好」；
            // - 未开启：禁用态（§6.3）—— 写清原因 + 给一条出路按钮，按钮的**动词随状态变**
            //   （还能弹授权框＝「开启」，被系统关掉＝「去设置」）。写成固定「开启」的话，
            //   系统里已关通知的用户点下去不会有任何反应。
            if (permission.granted) {
                NavRow(
                    icon = Icons.Filled.Notifications,
                    name = stringResource(R.string.label_allow_notifications),
                    sub = stringResource(permission.hintRes),
                    statusChip = {
                        StatusChip(
                            stringResource(permission.labelRes),
                            notificationPermissionTone(permission),
                        )
                    },
                    onClick = { permission.openSettings() },
                    divider = false,
                )
            } else {
                DisabledNavRow(
                    icon = Icons.Filled.Notifications,
                    name = stringResource(R.string.label_allow_notifications),
                    reason = stringResource(permission.hintRes),
                    fixLabel = stringResource(permission.actionRes),
                    onFix = { permission.request() },
                    divider = false,
                )
            }
        }

        SettingsSection(stringResource(R.string.label_notification_display_mode)) {
            NotifLayout.entries.forEachIndexed { i, l ->
                ModeOptionRow(
                    label = stringResource(l.labelRes),
                    desc = stringResource(l.descRes),
                    selected = layout == l,
                    divider = i != 0,
                ) {
                    NotifLayoutRuntime.set(context, l)
                }
            }
        }

        SettingsSection(stringResource(R.string.translate_notes_section)) {
            SettingsProse(
                stringResource(R.string.note_notification_settings_desc),
                divider = false,
            )
        }

        Spacer(Modifier.height(12.dp))
    }
}


/**
 * 二级页的单选行：18dp 圆点 + 名称 + 说明（规范 §5.4：「4–7 档枚举走 L2 单选列表」）。
 *
 * 每行**必须**有说明 —— 单选列表的选择质量完全取决于说明文案。
 *
 * [desc] 可空，但那是**例外**而不是退路：只有「名称本身已经用了读者自己的语言、
 * 再写一遍就是用说明复述名称」时才允许省略（规范 §6.2 禁止复述），
 * 语言页每种语言的**母语自称**就是这种情况。凡是能提供增量信息的，都必须写。
 *
 * 两处配色是**按对比度审计定的**，不是随手挑的：
 * - 未选中描边走 [Primer.BorderControl]（可交互控件边界，WCAG 1.4.11 要 ≥3:1）；
 *   `Primer.Border` 是装饰性描边，只有 1.80:1。
 * - 选中圆点走 [Primer.SuccessTextStrong]，不用 `Primer.Green500`：
 *   后者压在 `SuccessSurface` 上只有 2.79:1，而圆点是**唯一**表达「选了哪个」的图形。
 */
@Composable
internal fun ModeOptionRow(
    label: String,
    desc: String?,
    selected: Boolean,
    divider: Boolean = false,
    onClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(Primer.Gray200))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(selectionColor(selected, on = Primer.SuccessSurface))
                .clickable { onClick() }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // 单选圆点：外圈与填充一起渐变（设置页里每个开关都会走这条路径）
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .border(
                        2.dp,
                        selectionColor(selected, on = Primer.SuccessTextStrong, off = Primer.BorderControl),
                        CircleShape,
                    )
                    .background(selectionColor(selected, on = Primer.SuccessTextStrong)),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
                // 没有增量信息时连 2dp 间距一起省掉：留一个空 Text 会让这一行比同级行矮不下去、
                // 也高不起来，视觉上像是「说明没加载出来」
                if (desc != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(desc, fontSize = 12.sp, color = Primer.TextTertiary)
                }
            }
        }
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
    data class DeleteWarn(
        val name: String,
        val unpushed: kotlin.collections.List<com.branchbase.ui.decision.UnpushedCommit>,
        /** 远端仓库统计（取不到就是 null —— 页面据此**不显示**这一行，而不是编数字）。 */
        val stats: com.branchbase.ui.decision.RepoStats? = null,
    ) : LocalPage
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

    // 返回键先消费「本页自己的决策页」：这一页内部有十来个子页（分叉 / 撤销 / 上游 / 回退 /
    // 删除警告 / 暂存提交 / 身份 / 分支 / 同步），它们的左上角返回都是「回本地仓库列表」，
    // 系统返回键必须走同一个目标。否则按返回会直接跳出整个「本地仓库」页，
    // 用户精心进入的决策页连同已填内容一起消失（与外层 ProfileScreen 的返回键相比，
    // 这里后注册、优先级更高，所以能抢到）。
    PageBackHandler(page != LocalPage.List) { page = LocalPage.List }

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
            val taskId = TaskStore.start(context, TaskKind.PULL, context.getString(R.string.action_update_repo, name))
            val pullResult = withContext(Dispatchers.IO) { RustBridge.gitPullDetailed(dirOf(name), token) }
            // 错误同时落日志：UI 的 feedback 一闪而过无法回看，日志才能事后定位
            Logger.net("git pull ($name) → ${pullResult ?: "成功"}", "LocalGit")
            when (pullResult) {
                null -> { TaskStore.success(context, taskId, context.getString(R.string.state_updated)); feedback = context.getString(R.string.toast_updated, name) }
                "nff" -> { TaskStore.fail(context, taskId, context.getString(R.string.state_diverged_needs_decision)); page = LocalPage.Fork(name) }
                else -> { TaskStore.fail(context, taskId, pullResult); feedback = context.getString(R.string.error_update_failed, pullResult) }
            }
        }
    }

    /** push 三态：无 upstream 先引导设置（P2-2），被拒 → Fork 决策页。 */
    fun doPush(name: String) {
        scope.launch {
            feedback = null
            val st = withContext(Dispatchers.IO) { RustBridge.gitStatus(dirOf(name))?.let { parseGitStatus(it) } }
            if (st == null) { feedback = context.getString(R.string.error_repo_status_engine_unavailable); return@launch }
            if (!st.hasUpstream) { page = LocalPage.Upstream(name); return@launch }
            val taskId = TaskStore.start(context, TaskKind.PUSH, context.getString(R.string.action_push_repo, name))
            val pushResult = withContext(Dispatchers.IO) { RustBridge.gitPushDetailed(dirOf(name), token, st.branch) }
            Logger.net("git push ${st.branch} ($name) → ${pushResult ?: "成功"}", "LocalGit")
            when (pushResult) {
                null -> { TaskStore.success(context, taskId, context.getString(R.string.toast_pushed_branch, st.branch)); feedback = context.getString(R.string.toast_pushed, name) }
                "nff" -> { TaskStore.fail(context, taskId, context.getString(R.string.state_push_rejected)); page = LocalPage.Fork(name) }
                else -> { TaskStore.fail(context, taskId, pushResult); feedback = context.getString(R.string.error_push_failed, pushResult) }
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
            if (st == null || st.dirty.isEmpty()) { feedback = context.getString(R.string.error_nothing_to_commit); return@launch }
            page = LocalPage.Stage(name)
        }
    }

    /** 执行本地 git commit（identity 已就绪）。 */
    fun doGitCommit(repoName: String, message: String) {
        scope.launch {
            val taskId = TaskStore.start(context, TaskKind.COMMIT, context.getString(R.string.action_commit_local, repoName))
            val sha = withContext(Dispatchers.IO) {
                RustBridge.gitCommit(dirOf(repoName), message, authorName(), authorEmail())
            }
            Logger.net("git commit ($repoName) → ${sha?.take(7) ?: "失败"}", "LocalGit")
            if (sha != null) TaskStore.success(context, taskId, context.getString(R.string.toast_committed, sha))
            else TaskStore.fail(context, taskId, context.getString(R.string.error_commit_failed_engine))
            feedback = if (sha != null) context.getString(R.string.state_committed_local_git) else context.getString(R.string.error_commit_failed_engine)
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
                        // 远端统计：从本地记录的 origin URL 反推 owner/repo，取不到就传 null（页面不显示那一行）
                        val stats = withContext(Dispatchers.IO) { fetchRepoStats(st?.remoteUrl, host, token) }
                        page = LocalPage.DeleteWarn(p.name, st?.unpushed ?: emptyList(), stats)
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
                stats = p.stats,
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
                        feedback = context.getString(R.string.toast_deleted, p.name)
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
                            feedback = context.getString(R.string.toast_switched, target)
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
                            feedback = context.getString(R.string.toast_deleted_branch, target)
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
                            feedback = context.getString(R.string.toast_created_and_switched, newBranch)
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
            myRepos = json?.takeIf { !it.startsWith("ERROR:") }?.let { parseRepos(it, me = accountLogin) } ?: emptyList()
            loadingRepos = false
        }
    }

    // clone 一个仓库
    fun doClone(fullName: String, name: String) {
        showPicker = false
        // repos 根目录必须存在（libgit2 clone 不会自动创建父目录）
        if (!repoRoot.exists() && !repoRoot.mkdirs()) {
            feedback = context.getString(R.string.error_cannot_create_repo_dir)
            return
        }
        val target = File(repoRoot, name)
        if (target.exists()) {
            feedback = context.getString(R.string.error_already_exists, name)
            return
        }
        scope.launch {
            cloning = true
            feedback = null
            val taskId = TaskStore.start(context, TaskKind.CLONE, context.getString(R.string.action_clone_repo, fullName))
            val error = RustBridge.gitCloneDetailed("https://github.com/$fullName", target.absolutePath, "", token)
            if (error == null) TaskStore.success(context, taskId, context.getString(R.string.toast_cloned_to, target.name))
            else TaskStore.fail(context, taskId, error)
            Logger.remote(if (error == null) "git clone $fullName 完成" else "git clone $fullName 失败：$error", "libgit2")
            cloning = false
            feedback = if (error == null) context.getString(R.string.toast_cloned, name) else context.getString(R.string.error_clone_failed, error)
            repos = listLocalRepos(repoRoot)
        }
    }

    // Box 包裹：Snackbar 浮在内容之上，不占用列表布局、不会挤动页面
    Box(Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier.fillMaxSize().background(Primer.BackgroundPrimary).statusBarsPadding().navigationBarsPadding(),
    ) {
        SubPageHeader(stringResource(R.string.nav_local_repo), onBack) {
            Text(stringResource(R.string.label_repo_count, repos.size), fontSize = 12.sp, color = Primer.TextTertiary)
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
                Text(stringResource(R.string.action_clone_repository), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White)
            }
        }

        if (cloning) {
            Text(stringResource(R.string.state_cloning), fontSize = 12.sp, color = Primer.TextTertiary, modifier = Modifier.padding(horizontal = 16.dp))
        }

        if (repos.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Folder, null, tint = Primer.IconSecondary, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.state_no_local_repos), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.note_clone_hint), fontSize = 12.sp, color = Primer.TextTertiary)
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
                Text(stringResource(R.string.label_choose_repo_to_clone), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary)
                Spacer(Modifier.height(10.dp))
                if (loadingRepos) {
                    Text(stringResource(R.string.state_loading_ellipsis), fontSize = 13.sp, color = Primer.TextTertiary)
                } else if (myRepos.isEmpty()) {
                    Text(stringResource(R.string.state_no_repos), fontSize = 13.sp, color = Primer.TextTertiary)
                } else {
                    LazyColumn(Modifier.heightIn(max = 400.dp)) {
                        items(myRepos) { repo ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { doClone(repo.fullName, repo.name) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(repo.fullName, fontSize = 14.sp, color = Primer.TextPrimary, modifier = Modifier.weight(1f))
                                // 我的 / 协作 / 他人：`/user/repos` 默认就包含协作与组织仓库，
                                // 不标出来用户会以为列表里全是自己的
                                RepoRelationBadge(repo.relation)
                            }
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
            title = { Text(stringResource(R.string.action_delete_local_repo)) },
            text = { Text(stringResource(R.string.confirm_delete_local_copy, name)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch {
                        File(repoRoot, name).deleteRecursively()
                        repos = listLocalRepos(repoRoot)
                    }
                }) { Text(stringResource(R.string.action_delete), color = Primer.Red500) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

private fun listLocalRepos(root: File): List<String> =
    root.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: emptyList()

/**
 * 删除本地仓库前的「挽留」统计（P1-3）。
 *
 * 本地仓库目录里**没有** owner/repo，只有 git 配置里的 origin URL —— 从这里反推；
 * 任何一步拿不到（没配 origin / URL 解析不出 / 接口失败）就返回 null，
 * 页面据此**不显示**统计行 —— 宁可少一行，也不摆一串写死的假数字（本轮修掉的正是后者）。
 */
private suspend fun fetchRepoStats(remoteUrl: String?, host: String, token: String): RepoStats? {
    val (owner, repo) = ownerRepoOfRemote(remoteUrl ?: return null) ?: return null
    val json = RustBridge.getRepoInfo(host, token, owner, repo) ?: return null
    return parseRepoStats(json)
}

/** 从 `https://github.com/o/r(.git)` / `git@github.com:o/r.git` 取 owner/repo；解析不出返回 null。 */
private fun ownerRepoOfRemote(url: String): Pair<String, String>? {
    val m = Regex("""[/:]([^/:\s]+)/([^/\s]+?)(?:\.git)?$""").find(url.trim()) ?: return null
    val owner = m.groupValues[1]
    val repo = m.groupValues[2]
    return if (owner.isNotBlank() && repo.isNotBlank()) owner to repo else null
}

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
            Text(stringResource(R.string.action_update), fontSize = 12.sp, color = Primer.Blue500, modifier = Modifier.clickable { onPull() })
            Text(stringResource(R.string.action_push), fontSize = 12.sp, color = Primer.Blue500, modifier = Modifier.clickable { onPush() })
            Text(stringResource(R.string.nav_branch_sync), fontSize = 12.sp, color = Primer.Blue500, modifier = Modifier.clickable { onSync() })
            Text(stringResource(R.string.action_commit), fontSize = 12.sp, color = Primer.Green500, modifier = Modifier.clickable { onCommit() })
            Text(stringResource(R.string.action_undo), fontSize = 12.sp, color = Primer.TextSecondary, modifier = Modifier.clickable { onUndo() })
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.nav_upstream), fontSize = 12.sp, color = Primer.TextSecondary, modifier = Modifier.clickable { onUpstream() })
            Text(stringResource(R.string.nav_revert_gitify), fontSize = 12.sp, color = Primer.TextSecondary, modifier = Modifier.clickable { onRollback() })
            Text(stringResource(R.string.action_delete), fontSize = 12.sp, color = Primer.Red500, modifier = Modifier.clickable { onDelete() })
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
        SubPageHeader(stringResource(R.string.label_branches_of_repo, repoName), onBack) { RefreshButton { onRefresh() } }

        if (dirtyCount > 0) {
            Text(
                stringResource(R.string.note_dirty_blocks_switch, dirtyCount),
                fontSize = 11.5.sp,
                color = Primer.WarningTextStrong,
                lineHeight = 16.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(8.dp)).background(Primer.WarningSurface)
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
                                Text(stringResource(R.string.label_current), fontSize = 10.sp, color = Primer.Green500)
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
                            stringResource(R.string.action_delete),
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
                    Text(stringResource(R.string.action_new_branch_plus), fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Primer.Blue500)
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text(stringResource(R.string.action_new_branch), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.note_new_branch_from_current),
                        fontSize = 12.sp, color = Primer.TextTertiary, lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.hint_branch_name), fontSize = 12.sp, color = Primer.TextTertiary) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newName.isNotBlank() && !busy,
                    onClick = { val n = newName.trim(); showCreate = false; newName = ""; onCreate(n) },
                ) { Text(stringResource(R.string.action_create_and_switch), color = Primer.Blue500) }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    confirmDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.confirm_delete_branch, target), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary) },
            text = {
                Text(
                    stringResource(R.string.note_delete_local_branch),
                    fontSize = 12.sp, color = Primer.TextTertiary, lineHeight = 18.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = null; onDelete(target) }) { Text(stringResource(R.string.action_delete), color = Primer.Red500) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    confirmSwitch?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmSwitch = null },
            title = { Text(stringResource(R.string.confirm_discard_first_title), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary) },
            text = {
                Text(
                    stringResource(R.string.confirm_discard_before_switch_body, dirtyCount),
                    fontSize = 12.sp, color = Primer.TextTertiary, lineHeight = 18.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmSwitch = null; onCheckout(target) }) {
                    Text(stringResource(R.string.action_discard_and_switch), color = Primer.Red500)
                }
            },
            dismissButton = { TextButton(onClick = { confirmSwitch = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

// ───────────────────────── 关于页 ─────────────────────────

/**
 * 关于页（**紧凑版**）：一屏看完「这是什么版本 / 是不是官方包 / 去哪儿反馈」。
 *
 * ## 旧版为什么显得空
 *
 * - 顶部把图标与文字**竖向堆叠**（80dp 图标 + 24/12/4/20 四段间距，约 150dp 只放了个 logo）；
 * - 信息行上下各 12dp 留白、行间没有分隔线，9 行版本信息占掉约 380dp；
 * - 校验结论在页面末尾又用一张独立横幅重复了一遍（横幅标题 + 说明 ≈ 70dp）；
 * - 段落间距 24 / 20 / 16 / 28 各来一次。
 * 加起来约 780dp —— 常见机型上要滑一屏半，而这一页真正要看的就是「版本 + 校验结论」。
 *
 * ## 紧凑版改了什么（内容一项没少）
 *
 * 1. **身份行**：图标 80 → 52dp，与名称同一行（名称 + 副标题右对齐成一列），
 *    右侧直接挂**校验结论胶囊** —— 结论从页尾横幅提前到第一屏第一眼；
 * 2. **信息行**：上下留白 12 → 8dp、行间补 1dp 分隔线（紧了也不会糊成一片），
 *    拆成「版本信息」「构建校验」两张卡片；
 * 3. **去重**：原独立横幅的「标题 + 说明」压成「胶囊短标签 + 一行说明」，说明并进校验卡片；
 * 4. 段落间距 24 / 20 / 16 / 28 → 统一 10dp（页尾 16dp）。
 *
 * 高度从约 780dp 降到约 590dp：常见机型**不用滚动**即可看全，
 * 版本 / 标准版本 / 构建时间 / 七位哈希 / Git 配置包 / 代码编辑器 /
 * 发布版本 / 签名校验 / 远程校验 / 校验说明 / 两个入口链接全部保留。
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

    val state = buildVerifyState(
        variant = variant,
        localFingerprint = localFingerprint,
        remoteFingerprint = remoteFingerprint,
        checking = remoteChecking,
    )
    val copy = verifyCopy(state, stringResource(variant.labelRes), localFingerprint, remoteFingerprint)
    // 配色按状态取（绿=通过、红=不一致、蓝=进行中、琥珀=取不到远端、灰=本地编译）；
    // 底色走 Primer 的浅色块（深浅主题各自成立），描边由前景色降透明度推得，不再写死浅色 RGB
    val (fg, bg) = when (state) {
        BuildVerifyState.Checking -> Primer.Blue500 to Primer.InfoSurface
        BuildVerifyState.LocalBuild -> Primer.TextSecondary to Primer.Gray100
        BuildVerifyState.RemoteUnavailable -> Primer.WarningText to Primer.WarningSurface
        BuildVerifyState.Matched -> Primer.SuccessTextStrong to Primer.SuccessSurface
        BuildVerifyState.Mismatched -> Primer.Red500 to Primer.DangerSurface
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Primer.BackgroundPrimary).statusBarsPadding().navigationBarsPadding(),
    ) {
        SubPageHeader(stringResource(R.string.nav_about), onBack)
        Column(
            // weight(1f)：与顶部 SubPageHeader 同级；fillMaxSize() 会超出容器，底部内容被压住
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        ) {
            // 身份行：图标 + 名称/副标题 + 校验结论胶囊（原来结论在页尾横幅，这里第一眼就能看到）
            AboutIdentityRow(copy.chip, fg, bg)

            // 版本信息
            AboutCard(topGap = 10.dp) {
                AboutInfoRow(stringResource(R.string.label_engineering_version), BuildConfig.ENGINEERING_VERSION)
                AboutRowDivider()
                AboutInfoRow(stringResource(R.string.label_standard_version), BuildConfig.STANDARD_VERSION)
                AboutRowDivider()
                AboutInfoRow(stringResource(R.string.label_build_time), BuildConfig.BUILD_TIME)
                AboutRowDivider()
                AboutInfoRow(stringResource(R.string.label_short_hash), BuildConfig.GIT_HASH)
                AboutRowDivider()
                AboutInfoRow(stringResource(R.string.label_git_bundle), "libgit2 1.7.2")
                AboutRowDivider()
                // 第三方代码编辑器痕迹：独立模块 :editor 封装，移除时删这行 + 该模块
                AboutInfoRow(
                    stringResource(R.string.label_code_editor),
                    stringResource(R.string.label_editor_module_info, com.branchbase.editor.EditorModuleInfo.NAME, com.branchbase.editor.EditorModuleInfo.VERSION, com.branchbase.editor.EditorModuleInfo.MODULE),
                )
            }

            // 构建校验：三行原始值 + 一行结论说明（原独立横幅的去重落点）
            AboutCard(topGap = 10.dp) {
                AboutInfoRow(stringResource(R.string.label_release_build), stringResource(variant.labelRes))
                AboutRowDivider()
                AboutInfoRow(stringResource(R.string.label_signature_check), if (variant != ReleaseVariant.UNKNOWN) stringResource(R.string.label_match) else stringResource(R.string.state_signature_abnormal))
                AboutRowDivider()
                AboutInfoRow(
                    stringResource(R.string.label_remote_check),
                    when {
                        remoteChecking -> stringResource(R.string.state_checking)
                        remoteFingerprint.isNullOrBlank() -> stringResource(R.string.error_checksum_unavailable)
                        remoteFingerprint.equals(localFingerprint, ignoreCase = true) -> stringResource(R.string.label_match)
                        else -> stringResource(R.string.state_mismatch)
                    },
                )
                AboutRowDivider()
                AboutVerifyNote(copy.detail, fg)
            }

            // 项目主页 + 开发交流群：关于页是用户找「去哪儿反馈」的地方，链接统一从这里出去。
            // QQ 群链接里的 authKey 会过期，所以群号也直接写在行里（过期了按号搜索即可）。
            AboutCard(topGap = 10.dp) {
                AboutLinkRow(stringResource(R.string.label_project_home), "SunsetRNE/Branchbase-Android") {
                    openExternal(context, REPO_URL)
                }
                AboutRowDivider()
                AboutLinkRow(stringResource(R.string.label_community_qq), "790735040") {
                    openExternal(context, QUN_URL)
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 身份行：52dp 图标 + 名称/副标题 + 右侧校验结论胶囊。 */
@Composable
private fun AboutIdentityRow(chip: String, fg: Color, bg: Color) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 应用图标：与桌面完全一致的那枚（PackageManager 合成自适应图标两个图层；
        // 曾经的手搓近似只画 foreground + 硬编码 #0d1117，与真实图标并不一致，已移除）。
        // 紧凑版缩到 52dp 并与文字同行 —— 关于页的图标是识别用的，不需要占掉 1/5 屏。
        AppIcon(size = 52.dp, shape = RoundedCornerShape(14.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Branchbase", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary)
            Spacer(Modifier.height(2.dp))
            Text(stringResource(R.string.app_tagline), fontSize = 11.5.sp, color = Primer.TextTertiary)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            chip,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            color = fg,
            modifier = Modifier
                .clip(RoundedCornerShape(9.dp))
                .background(bg)
                .border(1.dp, fg.copy(alpha = 0.30f), RoundedCornerShape(9.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** 关于页卡片容器（16dp 页边距 + 8dp 圆角 + 描边；与设置页列表同一套观感）。 */
@Composable
private fun AboutCard(topGap: Dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = topGap)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, Primer.Border, RoundedCornerShape(8.dp)),
        content = content,
    )
}

/** 卡片内行间分隔线（紧凑版行距只有 8dp，没有它几行会糊成一块）。 */
@Composable
private fun AboutRowDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Primer.Border))
}

/** 校验说明行（原横幅的 detail）：小字 + 状态色，跟着校验卡片走。 */
@Composable
private fun AboutVerifyNote(text: String, fg: Color) {
    Text(
        text,
        fontSize = 11.5.sp,
        color = fg.copy(alpha = 0.9f),
        lineHeight = 16.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** 项目主页（仓库地址，同时说明只做 Android）。 */
private const val REPO_URL = "https://github.com/SunsetRNE/Branchbase-Android"

/**
 * 开发交流 QQ 群的加群链接（群「Branchbase开发交流」· 790735040）。
 *
 * ⚠️ 链接里的 `authKey` / `data` 是腾讯签发的**会过期**的票据：过期后这个链接会失效，
 * 所以界面上把群号一并显示出来，过期了按群号搜索同样能进群。
 */
private const val QUN_URL =
    "https://qun.qq.com/universal-share/share?ac=1&authKey=pvJE5SaHMaTeBU%2BNqt2VJBfeAGY0eLg%2BGHUTz2TDjsxe0aeS3L32m6Cg0NEnMCmg" +
        "&busi_data=eyJncm91cENvZGUiOiI3OTA3MzUwNDAiLCJ0b2tlbiI6ImN4bGZ6WnBiUHVRZ0JOWFlmTVloV1R6S1o3NnIyV212RExzeTdtdUs4TVdqamVaNjR0V2xDRGJvNkI1N1RvV3ciLCJ1aW4iOiIxNTM5MDA3NDYwIn0%3D" +
        "&data=U0Umjl8BD3SwVhcezyilAhDNcA1MUL3HSJAq3dI4hCfZywecQmAbK4yqvgp-3FkggT-Sq4hqJivTho3p4TrhEQ&svctype=4&tempid=h5_group_info"

/** 用系统浏览器（或能处理该 scheme 的应用）打开外部链接；失败就静默忽略（不崩）。 */
private fun openExternal(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)),
        )
    }
}

/** 关于页的可点击链接行（标题 + 右侧值 + `›`）；上下 12dp 保住 ~41dp 的点击高度。 */
@Composable
private fun AboutLinkRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 13.sp, color = Primer.TextSecondary, maxLines = 1)
        Spacer(Modifier.width(10.dp))
        // 值占满剩余宽度并右对齐：窄屏上由它省略（而不是让标题被挤掉或整行溢出）
        Text(
            value,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = Primer.Blue500,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(4.dp))
        Text("›", fontSize = 14.sp, color = Primer.TextTertiary)
    }
}

@Composable
private fun AboutInfoRow(key: String, value: String) {
    Row(
        // 上下 8dp（原 12dp）：紧凑版行距收紧，靠行间 1dp 分隔线区分，
        // 又不至于把可读性压没（12.5sp 正文 + 8dp 留白 ≈ 33dp 行高）
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(key, fontSize = 12.5.sp, color = Primer.TextSecondary)
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = Primer.TextPrimary,
            textAlign = TextAlign.End,
            // 值占满剩余宽度并右对齐：标准版本号（版本-时间-哈希）较长，窄屏允许折行而不是被省略
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 仓库关系徽章（账号仓库 / 协作仓库 / 非账号仓库 / 非协作仓库）。
 *
 * 颜色按「能不能写」分档：能写的用绿色（与提交/推送入口一致），只能读的用中性灰，
 * 无权限的用红色 —— 一眼看出这个仓库我能做什么。
 */
@Composable
internal fun RepoRelationBadge(relation: RepoRelation?, modifier: Modifier = Modifier) {
    if (relation == null) return
    val (fg, bg) = when (relation) {
        RepoRelation.OWN, RepoRelation.COLLABORATOR -> Primer.SuccessText to Primer.SuccessSurface
        RepoRelation.FOREIGN -> Primer.TextTertiary to Primer.Gray150
        RepoRelation.NOT_COLLABORATOR -> Primer.Red500 to Primer.DangerSurface
    }
    Text(
        stringResource(relation.labelRes),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = fg,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}
