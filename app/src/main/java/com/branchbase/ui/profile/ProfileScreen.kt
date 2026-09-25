package com.branchbase.ui.profile

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
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Commit
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import com.branchbase.R
import com.branchbase.ui.LocalizedText
import com.branchbase.ui.settings.GitProxyScreen
import com.branchbase.ui.settings.LanguageScreen
import com.branchbase.ui.settings.RepoCredentialsScreen
import com.branchbase.ui.repository.repoRelationOf
import com.branchbase.ui.repository.RepoRelation
import com.branchbase.ui.repository.RepoDeepLink
import com.branchbase.ui.theme.color
import com.branchbase.ui.theme.TintRole
import com.branchbase.ui.theme.selectionColor
import com.branchbase.cache.PageCache
import com.branchbase.cache.SearchCacheDatabase
import com.branchbase.cache.SearchCacheManager
import com.branchbase.core.AvatarCache
import com.branchbase.ui.navigation.NavigationShell
import com.branchbase.ui.navigation.PageLevel
import com.branchbase.ui.navigation.PageSwitcher
import com.branchbase.ui.navigation.TabSwitcher
import com.branchbase.ui.navigation.rememberPageResumeTick
import kotlinx.coroutines.launch
import com.branchbase.core.AccountStore
import com.branchbase.core.RustBridge
import com.branchbase.ui.log.LogCategory
import com.branchbase.ui.log.LogScreen
import com.branchbase.ui.log.Logger
import com.branchbase.ui.theme.iconTap
import com.branchbase.ui.theme.LanguageColors
import com.branchbase.ui.theme.Avatar
import com.branchbase.ui.theme.Primer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
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
import com.branchbase.ui.theme.PlaceholderSwap
import com.branchbase.ui.theme.ProvideShimmer
import com.branchbase.ui.theme.bubbleEnter
import com.branchbase.ui.theme.bubbleExit
import com.branchbase.ui.theme.rememberPressFeedback
import com.branchbase.ui.theme.skeletonBlock
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
    /**
     * 深链接进仓库页（个人页里的子页用）。
     *
     * 与 [onOpenRepo] 分开：那个只说得清「哪个仓库」（`owner/repo`），落点是仓库首页；
     * 「设置 → 本地仓库」的进入还要说清「去干嘛」—— 打开代码页并把 Git 面板展开到工作台
     * （[RepoDeepLink.openGitPanel]）。合成一个回调就得在这里塞一个「要不要开面板」的布尔参数，
     * 而那个意图属于深链接自己。
     */
    onOpenRepoDeepLink: (RepoDeepLink) -> Unit = {},
    /**
     * 从寄存点恢复的初始子页（例如「设置 → 账号管理」）。
     *
     * 场景：「添加账号」的登录页整屏接管时本页会被销毁，返回时重建 —— 没有它就会落在个人页主页，
     * 而用户的直觉是「回到我离开的那一页」。
     */
    initialSubPage: SubPage? = null,
    /** 消费掉初始子页后回调，宿主据此清空寄存点（否则下次进个人页会被拽回旧子页）。 */
    initialSubPageConsumed: () -> Unit = {},
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
    //
    // sessionJson 只解析**一次**：原先是三处各自 `JSONObject(sessionJson)`，而且都没进 remember
    // —— 这个页面每重组一次就要把整份 session 解析三遍，而它在 Main ↔ 子页切换时会反复重组。
    val session = remember(sessionJson) { runCatching { JSONObject(sessionJson) }.getOrNull() }
    val token = remember(session) {
        runCatching { session?.getJSONObject("token")?.optString("access_token") }.getOrNull() ?: ""
    }
    val host = remember(session) { session?.optString("host", "github.com") ?: "github.com" }
    var repos by remember { mutableStateOf<List<RepoItem>>(emptyList()) }
    var reposLoading by remember { mutableStateOf(true) }
    val user = remember(session) { runCatching { session?.getJSONObject("user") }.getOrNull() }
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
     *
     * 回源走 [PageCache.refreshDetached]（不是 [PageCache.refresh]）：这一页最常见的动作是
     * 「进来一眼就点进某个仓库」，而那样会在结果回来之前就离开、取消掉整个 effect ——
     * 旧写法下这一趟**既没写缓存也没打日志**，于是下一次进主页还是冷启动。
     */
    suspend fun loadRepos() {
        val key = if (login.isBlank()) null else PageCache.profileKey(login, "repos")

        // ① 先直出缓存（含过期数据）
        if (key != null) {
            PageCache.cachedFirst(cacheManager, key, PageCache.TYPE_PROFILE)?.let { cached ->
                parseRepos(cached, me = login).takeIf { it.isNotEmpty() }?.let {
                    repos = it
                    reposLoading = false
                }
            }
        }

        // ② 回源并写回（页面离开也照样落缓存，见 refreshDetached 的注释）
        val json = if (key != null) {
            PageCache.refreshDetached(cacheManager, key, PageCache.TYPE_PROFILE) {
                RustBridge.getMyRepos(host, token)
            }
        } else {
            RustBridge.getMyRepos(host, token)?.takeIf { !it.startsWith("ERROR:") }
        }
        json?.let { repos = parseRepos(it, me = login) }
        Logger.net("GET /user/repos → ${if (json == null) "失败/空" else "200（${repos.size} 个仓库）"}", "GitHubAPI")
    }

    // Tab 保活 ⇒ `LaunchedEffect(Unit)` 只跑一次；挂上「重新可见」的 tick，
    // 切回个人页时重新校验仓库列表（`loadRepos` 内部 cachedFirst → refresh，TTL 10 分钟内不联网）。
    val resumeTick = rememberPageResumeTick()
    LaunchedEffect(resumeTick) {
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
    // 初始值取自寄存点（一次性）：消费后回调宿主清空，避免下次又被拽回来
    var subPage by remember { mutableStateOf(initialSubPage) }
    LaunchedEffect(Unit) {
        if (initialSubPage != null) {
            Logger.ui("个人页恢复子页：${initialSubPage.label}", "Compose")
            initialSubPageConsumed()
        }
    }

    // 唯一路由：子页栈（depth>0）或主页三 Tab（depth=0）。
    // 原来是 `if (currentSubPage != null) { when(...); return }` —— 状态一变整棵树换掉、无过渡；
    // 现在交给 PageSwitcher：进子页从右滑入、返回向右滑出，主页三个 Tab 之间淡入淡出。
    val route: ProfileRoute = subPage?.let { ProfileRoute.Sub(it) } ?: ProfileRoute.Main

    // 子页面跳转时拦截系统返回，**逐层退回**（不是一律回主页）：
    // 设置的下级页（本地仓库 / 关于 / 日志 / 通知设置 / 翻译 / 账号 / 提交模式 / 仓库凭据，depth=2）
    // 先回设置页，一级子页（星标 / 项目 / 任务 / 编辑资料 / 设置，depth=1）才回个人主页。
    // 曾经这里写死 `subPage = null` —— 页面左上角返回是回设置、系统返回键却直接跳回个人页，
    // 同一个返回意图给出两个结果（返回键跳层）。
    // 子页返回交给下面 PageSwitcher 的 `onBack` 兜底（同一条规则：[profileBackTarget] 是
    // 穷尽 `when`）。这里不再单独挂 handler —— 两处各写一遍迟早会分家。

    // 底部气泡导航栏由 [NavigationShell] 持有（**不在**下面的 PageSwitcher 里）：
    // 放进切换器里的话，切 Tab 会被同级动效连着整条栏一起播（2026-09 之前那 2% 垂直位移就是这样
    // 把「切页面时导航栏上下跳」带出来的：旧栏上移、新栏上浮、两栏错位叠着）。
    NavigationShell(
        bar = {
            // 气泡导航栏（基础形态 ④）：3 主项 + 右侧手柄弹出 More 菜单
            ProfileBubbleNavigationBar(
                selected = tab,
                onSelect = { tab = it; Logger.ui("切换到「${it.logLabel}」", "Compose") },
                onLogout = onLogout,
                onNavigate = { subPage = it; Logger.ui("打开「${it.label}」", "Compose") },
            )
        },
        // 只有个人主页有底部导航；星标 / 设置 / 任务等子页都是全屏页
        barVisible = route is ProfileRoute.Main,
        modifier = Modifier.fillMaxSize(),
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            PageSwitcher(
                state = route,
                onBack = { subPage = profileBackTarget(subPage) },
                modifier = Modifier.fillMaxSize(),
                label = "profile-page",
            ) { r ->
                when (r) {
                    is ProfileRoute.Sub -> when (r.page) {
                        SubPage.Stars -> StarsScreen(sessionJson, onBack = { subPage = null }, onOpenRepo = onOpenRepo)
                        SubPage.Projects -> ProjectsScreen(sessionJson, onBack = { subPage = null })
                        SubPage.Settings -> SettingsScreen(onBack = { subPage = null }, onOpenLocalRepo = { subPage = SubPage.LocalRepo }, onOpenAbout = { subPage = SubPage.About }, onOpenLog = { subPage = SubPage.Log }, onOpenNotificationSettings = { subPage = SubPage.NotificationSettings }, onOpenTranslate = { subPage = SubPage.Translate }, onOpenAccounts = { subPage = SubPage.Accounts }, onOpenCommitMode = { subPage = SubPage.CommitMode }, onOpenGitProxy = { subPage = SubPage.GitProxy }, onOpenRepoCredentials = { subPage = SubPage.RepoCredentials }, onOpenLanguage = { subPage = SubPage.Language }, onLogout = onLogout)
                        // 返回目标必须与其它二级页一致（`SettingsSpecTest` 逐行钉着这一条）——
                        // 所以这句调用保持单行：拆行会让那条源码级钉子假红
                        SubPage.LocalRepo -> LocalRepoScreen(sessionJson, onBack = { subPage = SubPage.Settings }, onOpenInApp = onOpenRepoDeepLink)
                        SubPage.About -> AboutScreen(onBack = { subPage = SubPage.Settings })
                        SubPage.Log -> LogScreen(onBack = { subPage = SubPage.Settings })
                        SubPage.NotificationSettings -> NotificationSettingsScreen(onBack = { subPage = SubPage.Settings })
                        SubPage.Translate -> TranslateSettingsScreen(onBack = { subPage = SubPage.Settings })
                        SubPage.Tasks -> com.branchbase.ui.task.TaskScreen(onBack = { subPage = null })
                        // onAdd 不再接 onLogout：那等于「点添加账号就把自己登出」。新增流程由
                        // 账号页自己用 LoginViewModel.addAccount() 触发（它持有同一实例的 viewModel），
                        // 这里的 onAdd 只留给宿主做别的钩子。
                        // ⚠️ 必须写成**单行**：SettingsSpecTest 会断言「二级页的返回目标与路由同一行」，
                        //    那是防止返回目标与路由走散的既有约定，别为了排版拆行。
                        SubPage.Accounts -> AccountsScreen(onBack = { subPage = SubPage.Settings }, onAdd = {})
                        SubPage.CommitMode -> CommitModeScreen(onBack = { subPage = SubPage.Settings })
                        SubPage.GitProxy -> GitProxyScreen(onBack = { subPage = SubPage.Settings })
                        SubPage.Language -> LanguageScreen(onBack = { subPage = SubPage.Settings })
                        // 返回目标不写死：走 [profileBackTarget]（本页 depth = 2 → SubPage.Settings），
                        // 与系统返回键同一条规则（规范 §3.1）
                        SubPage.RepoCredentials -> RepoCredentialsScreen(onBack = { subPage = profileBackTarget(subPage) })
                        SubPage.EditProfile -> ProfileEditScreen(sessionJson, onBack = { subPage = null }, onSaved = { subPage = null })
                    }

                    ProfileRoute.Main -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Primer.BackgroundPrimary)
                            // 底部「手势条」内边距由栏自己负责（见 ProfileBubbleNavigationBar），
                            // 这里只管状态栏 —— 两处都取会叠成两层
                            .statusBarsPadding(),
                    ) {
                        // 顶部导航
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back), tint = Primer.IconPrimary, modifier = Modifier.size(24.dp).iconTap { onBack() })
                            Spacer(Modifier.width(4.dp))
                            Text(login, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
                        }

                        // 内容（随底部气泡导航栏切换，weight 占据剩余空间）。
                        // 切 Tab 的淡入淡出只作用在这里：顶部导航与底部导航栏都不参与
                        Box(Modifier.fillMaxWidth().weight(1f)) {
                            TabSwitcher(
                                state = tab,
                                modifier = Modifier.fillMaxSize(),
                                label = "profile-tab",
                            ) { t ->
                                when (t) {
                                    ProfileTab.Overview -> ProfileOverview(login, name, avatarUrl, bio, followers, following, publicRepos, repos, reposLoading, onOpenRepo, onEdit = { subPage = SubPage.EditProfile })
                                    ProfileTab.Repositories -> ProfileRepositories(repos, reposLoading, onOpenRepo)
                                    ProfileTab.Activity -> ProfileActivity(host, token, login, onOpenRepo)
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
 * 个人页路由。
 *
 * 层级：主页三 Tab（0）→ 一级子页（1：星标 / 项目 / 任务 / 编辑资料 / 设置）→
 * 设置的下级页（2：本地仓库 / 关于 / 日志 / 通知设置 / 沉浸式翻译 / 账号 / 提交模式 / 仓库凭据）。
 * 这样「设置 → 关于」是推进，「关于 → 设置」是返回，方向都对得上用户的操作。
 */
private sealed interface ProfileRoute : PageLevel {

    /**
     * 个人主页（三 Tab）。
     *
     * **刻意不带「当前 Tab」**：Tab 是内容区自己的维度（内容区那层 [TabSwitcher] 渲染），
     * 不是换页。塞进路由的话切 Tab 就等于换路由，外层 [PageSwitcher] 会把整块内容连
     * 导航栏一起播位移 + 交叉淡入（内层还会再播一次）—— 那是「导航栏上下跳」的来源。
     */
    data object Main : ProfileRoute {
        override val depth: Int get() = 0
    }

    data class Sub(val page: SubPage) : ProfileRoute {
        override val depth: Int get() = subPageDepth(page)

        /**
         * 首帧重页：设置页一轮里出了 **7 条慢帧**（最慢 118.2ms，主段**绘制**）——
         * 八组卡片 + 二十多行；LazyColumn 已经压过一轮首帧，剩下的靠过渡降级省
         * （不给位移、只做短淡化）。判据与台账见 [PageLevel.heavyFirstFrame]；
         * 其余子页先不标 —— 基线上都不到 3 条。
         */
        override val heavyFirstFrame: Boolean get() = page == SubPage.Settings
    }
}

/**
 * 子页层级（唯一的「谁在谁下面」真源）。
 *
 * 动效方向（[PageSwitcher] 按层级差决定推进 / 返回）与返回键目标
 * （[profileBackTarget]）都从这里取，避免两处各写一份 `when` 而慢慢分家。
 */
internal fun subPageDepth(page: SubPage): Int = when (page) {
    SubPage.LocalRepo, SubPage.About, SubPage.Log, SubPage.NotificationSettings,
    SubPage.Translate, SubPage.Accounts, SubPage.CommitMode, SubPage.GitProxy,
    SubPage.RepoCredentials, SubPage.Language,
    -> 2

    else -> 1
}

/**
 * 个人页按返回键该去哪个子页（纯函数，便于单测）。
 *
 * - 一级子页（`depth == 1`）→ `null`（回个人主页）；
 * - 设置的下级页（`depth == 2`）→ [SubPage.Settings]（先回设置，**不是**直接跳回主页）。
 *
 * 「按返回跳层」这类问题只有在真机上连按才试得出来，回归时最难发现，所以钉成纯函数：
 * 页面左上角的返回箭头与系统返回键**必须走同一个目标**。
 */
internal fun profileBackTarget(page: SubPage?): SubPage? = when {
    page == null -> null
    subPageDepth(page) >= 2 -> SubPage.Settings
    else -> null
}

/**
 * 底部导航的三个主项。
 *
 * [labelRes] 是**显示**文案，跟随界面语言；[logLabel] 是**日志**专用的中文名。
 * 两者不能合并：日志按约定固定中文（`tools/perf/frame-baseline.py` 的段落分类
 * 直接匹配这些中文词），界面语言一换，日志侧的分析脚本就会失配。
 */
private enum class ProfileTab(@StringRes val labelRes: Int, val logLabel: String, val icon: ImageVector) {
    Overview(R.string.profile_tab_overview, "概览", Icons.Filled.Person),
    Repositories(R.string.profile_tab_repositories, "仓库", Icons.Filled.Folder),
    Activity(R.string.profile_tab_activity, "动态", Icons.Filled.Timeline),
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
                        // 长按头像 → 强制重新拉取（网页端换头像后手动刷新）。
                        // 用 iconTap（而非 combinedClickable）：clip 必须在点击节点之前，否则反馈是方的
                        modifier = Modifier.iconTap(
                            onClick = {},
                            onLongClick = {
                                scope.launch {
                                    val ok = AvatarCache.refresh(context, login, avatarUrl)
                                    if (ok) {
                                        avatarTick++
                                        Toast.makeText(context, context.getString(R.string.toast_avatar_refreshed), Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, context.getString(R.string.error_refresh_failed_network), Toast.LENGTH_SHORT).show()
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
                        .border(1.dp, Primer.BorderEmphasis, RoundedCornerShape(6.dp)).clickable { onEdit() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.action_edit_profile), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Primer.TextPrimary)
                }
                Spacer(Modifier.height(10.dp))
                Row {
                    Text(stringResource(R.string.label_followers, followers), fontSize = 14.sp, color = Primer.TextSecondary)
                    Text(" · ", fontSize = 14.sp, color = Primer.TextSecondary)
                    Text(stringResource(R.string.label_following, following), fontSize = 14.sp, color = Primer.TextSecondary)
                    Text(" · ", fontSize = 14.sp, color = Primer.TextSecondary)
                    Text(stringResource(R.string.label_repo_count_public, publicRepos), fontSize = 14.sp, color = Primer.TextSecondary)
                }
            }
        }
        // 热门仓库
        item {
            SectionTitle(stringResource(R.string.label_popular_repos), stringResource(R.string.label_custom_pins))
            Column(Modifier.padding(horizontal = 16.dp)) {
                if (loading) {
                    // 骨架屏：结构和尺寸与 [RepoCard] 一一对应（同 6dp 圆角 / 同边框 / 同 12dp 内边距）。
                    // 原先是「加载中…」一行灰字：卡片的真实高度要等数据回来才知道，内容到达时整段往下跳一次。
                    // ProvideShimmer 只包一层 —— 三张卡共用一条微光动画（见 ui/theme/Motion.kt）。
                    ProvideShimmer {
                        repeat(PROFILE_REPO_SKELETON_COUNT) { RepoCardSkeleton() }
                    }
                } else if (pinnedRepos.isEmpty()) {
                    Text(stringResource(R.string.state_no_pinned_repos), fontSize = 13.sp, color = Primer.TextTertiary)
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
    // null = 不筛选。拿哨兵字符串「全部」当"不筛选"的话，展示文案一旦抽成资源并翻译，
    // `filter == "全部"` 在英文界面下永不成立 —— 筛选会直接失效（不崩溃，只是点了没反应）。
    var filter by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // 搜索框
        Box(Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(6.dp)).background(Primer.Gray150).border(1.dp, Primer.BorderEmphasis, RoundedCornerShape(6.dp)).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
            Text(stringResource(R.string.hint_find_repo), fontSize = 13.sp, color = Primer.TextTertiary)
        }
        Spacer(Modifier.height(10.dp))
        // 语言筛选
        val langs = listOf<String?>(null, "Kotlin", "Shell", "Python", "C++")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            langs.forEach { lang ->
                FilterChip(lang ?: stringResource(R.string.filter_all), selected = filter == lang) { filter = lang }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (loading) {
            // 骨架屏：搜索框与语言筛选是**不依赖数据**的静态结构，照常渲染（用户能先看清这页有什么）；
            // 只把下面的列表换成与 [RepoCard] 同构的占位卡，数据到达时列表在原位长出来。
            ProvideShimmer {
                LazyColumn {
                    items(PROFILE_REPO_SKELETON_COUNT) { RepoCardSkeleton() }
                }
            }
        } else {
            LazyColumn {
                items(repos.filter { filter == null || it.language == filter }) { repo ->
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
                selectionColor(selected, on = Primer.Blue500, off = Primer.BorderEmphasis),
                RoundedCornerShape(14.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(label, fontSize = 12.sp, color = selectionColor(selected, on = Color.White, off = Primer.TextSecondary))
    }
}

// ───────────────────────── Activity 页（真实事件数据） ─────────────────────────

/**
 * 动态事件（由 `/user/events`、`/users/{login}/events` 解析）。
 *
 * ## 为什么是这些字段（而不是「四个字段拼一句话」）
 *
 * 原先只有 type / repo / detail / createdAt，`detail` 是**解析期就拼好的一句中文**。
 * 后果是行渲染里没有任何**真实对象**可用：头像、PR / issue / 发布的标题、
 * 折叠后的次数与最新 sha 全在解析层就被丢掉了。
 * 于是真机上的观感是「不像真实数据」——最近 30 条里 28 条是 PushEvent，
 * 而 events 接口把 PushEvent 的 payload 裁剪到只剩 `ref` / `head` / `before`
 * （没有提交数、没有提交信息），逐条渲染必然是一屏几乎一样的「推送到 main · <sha>」。
 *
 * 现在把显示真正需要的东西从 payload 里取出来：谁做的（[actor] / [actorAvatar]）、
 * 真实对象标题（[title]）、推送折叠用的分支与短 sha（[branch] / [head]）。
 */
internal data class ActivityEvent(
    val type: String,
    val repo: String,
    /** 行动描述；[LocalizedText] 而不是 String —— 解析期不认识 Context，见其 KDoc。 */
    val detail: LocalizedText,
    val createdAt: Long,
    /** 触发者登录名（`actor.login`）——「哪个仓库」之外，活动流还要能读出「谁做的」。 */
    val actor: String = "",
    /** 触发者头像（`actor.avatar_url`），直接交给统一头像组件。 */
    val actorAvatar: String? = null,
    /** 真实对象标题：PR / issue / 评论所属 issue / 发布 / 复刻目标 / 协作者 / wiki 页面。 */
    val title: String? = null,
    /** 推送分支（`payload.ref` 去掉 `refs/heads/`），折叠判定与文案都用它。 */
    val branch: String? = null,
    /** 推送的短 sha（`payload.head` 前 7 位）。 */
    val head: String? = null,
    /** 连续同类推送被 [collapsePushes] 折叠后的条数（1 = 未折叠）。 */
    val pushCount: Int = 1,
)

/**
 * 解析事件流。
 *
 * `internal` 是为了单测能钉住 payload → 字段的映射：payload 是**外部契约**
 * （字段被 GitHub 裁剪过，见 [ActivityEvent] 的说明），映射错一处就是整块显示错。
 */
internal fun parseEvents(json: String?): List<ActivityEvent> {
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
                val actor = o.optJSONObject("actor")
                // events API 的 PushEvent payload 被裁剪：只有 ref/head/before，
                // 没有 size / commits（旧实现读 size 恒为 0，显示「推送了 0 个提交」）
                val branch = payload?.optString("ref").orEmpty()
                    .removePrefix("refs/heads/")
                    .removePrefix("refs/tags/")
                    .takeIf { it.isNotBlank() }
                val head = payload?.optString("head").orEmpty().take(7).takeIf { it.isNotBlank() }
                val detail = when (type) {
                    "PushEvent" -> pushDetail(branch, 1, head)
                    "CreateEvent" -> createdOrDeleted(
                        res = R.string.activity_created_named,
                        plainRes = R.string.activity_created,
                        kindRes = when (payload?.optString("ref_type").orEmpty()) {
                            "branch" -> R.string.activity_kind_branch
                            "tag" -> R.string.activity_kind_tag
                            "repository" -> R.string.activity_kind_repository
                            else -> R.string.activity_kind_content
                        },
                        name = payload?.optString("ref").orEmpty(),
                    )
                    "DeleteEvent" -> createdOrDeleted(
                        res = R.string.activity_deleted_named,
                        plainRes = R.string.activity_deleted,
                        kindRes = when (payload?.optString("ref_type").orEmpty()) {
                            "branch" -> R.string.activity_kind_branch
                            "tag" -> R.string.activity_kind_tag
                            else -> R.string.activity_kind_ref
                        },
                        name = payload?.optString("ref").orEmpty(),
                    )
                    "WatchEvent" -> LocalizedText(R.string.activity_starred_repo)
                    "ForkEvent" -> LocalizedText(R.string.activity_forked_repo)
                    "IssueCommentEvent" -> LocalizedText(
                        R.string.activity_commented_issue,
                        listOf(payload?.optJSONObject("issue")?.optInt("number") ?: 0),
                    )
                    // 编号在 payload 顶层（payload.pull_request.number 未必存在）
                    "PullRequestEvent" -> {
                        val n = payload?.optInt("number") ?: payload?.optJSONObject("pull_request")?.optInt("number") ?: 0
                        LocalizedText(
                            R.string.activity_pull_request_action,
                            listOf(eventActionText(payload?.optString("action").orEmpty()), n),
                        )
                    }
                    "PullRequestReviewEvent" -> {
                        val n = payload?.optJSONObject("pull_request")?.optInt("number") ?: 0
                        if (n > 0) {
                            LocalizedText(R.string.activity_reviewed_pr, listOf(n))
                        } else {
                            LocalizedText(R.string.activity_reviewed_pr_plain)
                        }
                    }
                    "ReleaseEvent" -> LocalizedText(
                        R.string.activity_published_release,
                        listOf(payload?.optJSONObject("release")?.optString("tag_name").orEmpty()),
                    )
                    "PublicEvent" -> LocalizedText(R.string.activity_made_public)
                    "IssuesEvent" -> {
                        val n = payload?.optJSONObject("issue")?.optInt("number") ?: 0
                        val action = eventActionText(payload?.optString("action").orEmpty())
                        if (n > 0) {
                            LocalizedText(R.string.activity_issue_action, listOf(action, n))
                        } else {
                            LocalizedText(R.string.activity_issue_action_plain, listOf(action))
                        }
                    }
                    "MemberEvent" -> LocalizedText(R.string.activity_added_collaborator)
                    "GollumEvent" -> LocalizedText(R.string.activity_updated_wiki)
                    // 认不出的事件类型原样透出（后端新增时不是把整行吞掉）
                    else -> LocalizedText(raw = type.removeSuffix("Event"))
                }
                add(
                    ActivityEvent(
                        type = type,
                        repo = repo,
                        detail = detail,
                        createdAt = parseIsoTime(o.optString("created_at")),
                        actor = actor?.optString("login").orEmpty(),
                        actorAvatar = actor?.optString("avatar_url")?.takeIf { it.isNotBlank() },
                        title = eventTitle(type, payload),
                        branch = branch,
                        head = head,
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())
        // 接口顺序**不可信**：`created_at` 才是时间真值，而 events 接口返回的是「按事件 id 倒序」——
        // 实测同一页里出现 06:38 / 06:31 / 06:43 这样的顺序（真机截图的前三行就是
        // 77d563e / e00b6a6 / 85dc7f9，与实测一致）。乱序的后果都在显示层：
        //   ① 相对时间忽大忽小（「4 小时前」下面跟着「1 天前」，再跟回「4 小时前」）——
        //      看起来就不像真实数据；
        //   ② [collapsePushes] 按**相邻**条目折叠，乱序时会把同一天的推送拆成好几段。
        // 所以在解析出口统一按时间倒序排一次（稳定排序：同一时间保持接口原序）；
        // 调用方拿到的一定是有序列表，不需要再排。
        .sortedByDescending { it.createdAt }
}

/**
 * 「创建 / 删除」共用一份拼法（只差动作词），参数是 `(动作, 类型, 名字)`。
 *
 * `name` 为空时退到不带名字的短句 —— 两种情况各占一条资源，因为英文里
 * 「Created branch main」与「Created a branch」不是同一个句子。
 */
private fun createdOrDeleted(
    @StringRes res: Int,
    @StringRes plainRes: Int,
    @StringRes kindRes: Int,
    name: String,
): LocalizedText {
    val kind = LocalizedText(kindRes)
    return if (name.isNotBlank()) {
        LocalizedText(res, listOf(kind, name))
    } else {
        LocalizedText(plainRes, listOf(kind))
    }
}

/**
 * 事件动作的文案（原先直接把 `opened` / `closed` 原样拼进句子）。
 *
 * 认不出来的动作**原样透出**（[LocalizedText.raw]）：GitHub 以后新增动作时
 * 至少还看得到接口给的原词，而不是整句变成空白。
 */
private fun eventActionText(action: String): LocalizedText = when (action) {
    "opened" -> LocalizedText(R.string.event_action_opened)
    "closed" -> LocalizedText(R.string.event_action_closed)
    "reopened" -> LocalizedText(R.string.event_action_reopened)
    "merged" -> LocalizedText(R.string.event_action_merged)
    "labeled" -> LocalizedText(R.string.event_action_labeled)
    "assigned" -> LocalizedText(R.string.event_action_assigned)
    "" -> LocalizedText(raw = "")
    else -> LocalizedText(raw = action)
}

/**
 * 真实对象标题（拿不到就返回 null —— 行里不空占一行）。
 *
 * 这些字段都在 payload 里真实存在（见 `ActivityEvent` 的说明），
 * 原先被解析层丢掉了，所以行里只剩一句通用文案。
 */
private fun eventTitle(type: String, payload: JSONObject?): String? {
    val raw = when (type) {
        "IssueCommentEvent", "IssuesEvent" -> payload?.optJSONObject("issue")?.optString("title")
        "PullRequestEvent", "PullRequestReviewEvent" ->
            payload?.optJSONObject("pull_request")?.optString("title")
        "ReleaseEvent" -> payload?.optJSONObject("release")?.optString("name")
            ?.takeIf { it.isNotBlank() }
            ?: payload?.optJSONObject("release")?.optString("tag_name")
        "ForkEvent" -> payload?.optJSONObject("forkee")?.optString("full_name")
        "MemberEvent" -> payload?.optJSONObject("member")?.optString("login")
        "GollumEvent" -> payload?.optJSONArray("pages")?.optJSONObject(0)?.optString("page_name")
        else -> null
    }
    return raw?.trim()?.takeIf { it.isNotBlank() && it != "null" }
}

/**
 * 折叠连续推送：**同仓库 + 同分支 + 同一天（本地时区）**的相邻 `PushEvent` 合并成一行。
 *
 * 为什么必须折叠：events 接口按时间倒序返回，而日常几乎全是推送 ——
 * 真机上最近 30 条里 28 条是 `PushEvent`，且同仓库同分支（`refs/heads/main`）。
 * 逐条渲染就是一屏「推送到 main · <sha>」的重复行：读不出「今天做了多少事」，
 * 也不像活动流（GitHub 自己的 feed 同样合并：「pushed 3 commits to main」）。
 *
 * 只在**相邻**条目之间折叠，不跨其它事件、不跨天：跨天合并会把
 * 「今天 3 次 + 昨天 5 次」写成「8 次」，那是在编造事实。组内保留**最新一次**的 sha ——
 * 它是这一组里唯一有定位价值的东西（按时间倒序，所以是组内首条）。
 *
 * 纯函数，钉子见 `ActivityFeedTest`。
 */
internal fun collapsePushes(events: List<ActivityEvent>): List<ActivityEvent> {
    val out = ArrayList<ActivityEvent>(events.size)
    events.forEach { e ->
        val last = out.lastOrNull()
        val sameRun = last != null &&
            last.type == "PushEvent" && e.type == "PushEvent" &&
            last.repo == e.repo && last.branch == e.branch &&
            localDay(last.createdAt) == localDay(e.createdAt)
        if (sameRun) {
            val count = last.pushCount + 1
            out[out.lastIndex] = last.copy(
                pushCount = count,
                detail = pushDetail(last.branch, count, last.head),
            )
        } else {
            out += e
        }
    }
    return out
}

/** 推送行文案（单条与折叠后共用一处，避免两处拼法分家）。 */
private fun pushDetail(branch: String?, count: Int, head: String?): LocalizedText = when {
    branch.isNullOrBlank() ->
        if (count > 1) {
            LocalizedText(R.string.activity_push_times, listOf(count))
        } else {
            LocalizedText(R.string.activity_push_plain)
        }
    count > 1 && head != null ->
        LocalizedText(R.string.activity_push_branch_count_head, listOf(branch, count, head))
    count > 1 -> LocalizedText(R.string.activity_push_branch_count, listOf(branch, count))
    head != null -> LocalizedText(R.string.activity_push_branch_head, listOf(branch, head))
    else -> LocalizedText(R.string.activity_push_branch, listOf(branch))
}

/** 本地时区的「日」键（判定「同一天的连续推送」用；跨时区不会把两天误判成一天）。 */
private fun localDay(ms: Long): Long {
    val c = java.util.Calendar.getInstance()
    c.timeInMillis = ms
    return c.get(java.util.Calendar.YEAR) * 1000L + c.get(java.util.Calendar.DAY_OF_YEAR)
}

private fun parseIsoTime(s: String): Long = runCatching {
    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }.parse(s)?.time ?: 0L
}.getOrDefault(0L)

/**
 * 一次事件源抓取的结论。
 *
 * **为什么要把「失败」与「成功但空」分开**：调用方据此决定要不要把这条腿记进
 * [EventSourceMemory]（记了就 5 分钟不再试）。旧实现只看 `isEmpty()` ——
 * 一次网络抖动、或用户这段时间真的没有公开活动，都会被记成「这个源坏了」；
 * 而「坏了」这个结论在日志里又没有依据（失败原因当时是静默的）。
 */
internal data class EventFetchResult(val events: List<ActivityEvent>, val failed: Boolean)

/**
 * 事件源抓取失败时写进日志的正文（纯函数，便于单测）。
 *
 * 三种失败在日志里必须能分开，否则「这条腿为什么总是不走」还是只能靠猜：
 * - 没拿到任何响应（[RustBridge.getJson] 返回 null：断网、被代理挡下、请求没发出去）；
 * - 拿到空响应（`refresh` 会把它也归成 null）；
 * - 拿到 `ERROR:` 串（HTTP 4xx/5xx —— **401/404 的区别就在这里**）。
 */
internal fun eventFetchFailure(raw: String?): String = when {
    raw == null -> "无响应（未联网 / 请求未发出）"
    raw.isBlank() -> "空响应"
    else -> raw.take(140)
}

/**
 * 相对时间（「刚刚」/「5 分钟前」…）。
 *
 * 是 `@Composable` 因为量词要按语言选形：中文只有一种写法，英文得区分
 * `1 minute ago` / `2 minutes ago`，而倍数词只有 `pluralStringResource` 拿得到。
 * `ms <= 0` 返回空串（时间解析失败时行里不占位）。
 */
@Composable
private fun relativeTime(ms: Long): String {
    if (ms <= 0) return ""
    val min = (System.currentTimeMillis() - ms) / 60000
    return when {
        min < 1 -> stringResource(R.string.relative_just_now)
        min < 60 -> pluralStringResource(R.plurals.relative_minutes, min.toInt(), min)
        min < 1440 -> pluralStringResource(R.plurals.relative_hours, (min / 60).toInt(), min / 60)
        min < 43200 -> pluralStringResource(R.plurals.relative_days, (min / 1440).toInt(), min / 1440)
        else -> pluralStringResource(R.plurals.relative_months, (min / 43200).toInt(), min / 43200)
    }
}

@Composable
private fun ProfileActivity(
    host: String,
    token: String,
    login: String,
    // 「最近活动」整行可点 → 进对应仓库（这一页此前只读不跳，「看得到去不了」）
    onOpenRepo: (String) -> Unit,
) {
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
    suspend fun fetchEventPages(path: String): EventFetchResult {
        val all = mutableListOf<ActivityEvent>()
        for (page in 1..3) {
            val key = PageCache.profileKey(login, "events:$path:$page")
            // 失败原因要留痕。**别指望 refresh 把 `ERROR:` 串交回来**：它按契约把错误响应
            // 吞成 null（见 [PageCache.refresh]），所以「`?: break` + 事后判 `ERROR:` 前缀」
            // 是一段**永远到不了的死代码**，失败全程静默 —— 真机日志里 3 次
            // 「跳过刚失败过的事件源 /user/events」、0 次失败原因，就是这么来的。
            // 现在把原始响应截下来，null 时补记一行（2026-09-22 修）。
            var raw: String? = null
            val pageJson = PageCache.refresh(cacheManager, key, PageCache.TYPE_PROFILE) {
                RustBridge.getJson(host, token, "$path?per_page=100&page=$page").also { raw = it }
            }
            if (pageJson == null) {
                Logger.net("事件源 $path 第 $page 页失败：${eventFetchFailure(raw)}", "GitHubAPI")
                return EventFetchResult(all.sortedByDescending { it.createdAt }, failed = true)
            }
            val batch = parseEvents(pageJson)
            if (batch.isEmpty()) break
            all += batch
            if (batch.size < 100) break
        }
        // 分页拼接后**整体再排一次**：单页排序盖不住分页边界上的乱序
        // （接口本身不按时间返回，见 [parseEvents] 的说明），
        // 而 [collapsePushes] 的口径是「相邻条目」——顺序错了，折叠就会把同一天切成好几段。
        return EventFetchResult(all.sortedByDescending { it.createdAt }, failed = false)
    }

    // Tab 保活 ⇒ 这两个 effect 不会因为「切回动态页」重跑；挂上「重新可见」的 tick 做重新校验。
    // 注意两个 loading 标志都加了「已有数据就别再置加载态」的条件：重新校验时数据就在手上
    // （L1 同帧命中），再示意一次加载会让分区骨架闪一下。
    val resumeTick = rememberPageResumeTick()

    LaunchedEffect(login, resumeTick) {
        if (events.isEmpty()) loading = true
        error = null
        if (login.isBlank()) {
            error = context.getString(R.string.error_login_name_missing)
            loading = false
            return@LaunchedEffect
        }
        // 数据源**只有一条腿**：`/users/{login}/events`。
        //
        // 这里原来先试 `/user/events`（注释写着「认证用户自己的活动（含私有仓库）」），空了再回退，
        // 而回退方向在「看别人的主页」时还写反了（会去取**你自己**的活动）。
        // 1.0.65 把失败留痕修好之后（`?: break` 静默 → 记原始响应），真机日志立刻给出了答案：
        //
        //     23:21:43.053 事件源 /user/events 第 1 页失败：ERROR:未知错误: HTTP 404 Not Found
        //
        // **GitHub 没有这个端点** —— 文档里「List events for the authenticated user」指的
        // 就是 `/users/{username}/events`（认证成该用户时返回里才含私有活动，
        // 见 https://docs.github.com/en/rest/activity/events ）。所以那条腿不但注定失败，
        // 每次进动态页都要先白等一次往返；而正确答案本来就在回退的那条腿上。
        // 注意不要用 received_events —— 那是「你关注的人的活动」feed，通常为空。
        val source = "/users/$login/events"
        // 失败的源 5 分钟内不再重试（见 [EventSourceMemory]）：网络抖动时别每次都先白等一次。
        // **判据是「一条都没拿到 + 抓取失败」**，不是单独的 `isEmpty()`：
        // 源是好的、只是这段时间没数据，不该被拉黑 5 分钟；翻到第 2 页才断也一样
        // （拿到部分数据说明源是通的）。旧实现只看 `isEmpty()`，把这两种都算成了「源坏了」。
        val sourceKey = "$login|$source"
        val fetched = if (eventSourceMemory.isDead(sourceKey)) {
            Logger.debug(LogCategory.NETWORK, "动态", "跳过刚失败过的事件源 $source")
            EventFetchResult(emptyList(), failed = false)
        } else {
            fetchEventPages(source)
        }
        if (fetched.failed && fetched.events.isEmpty()) eventSourceMemory.markDead(sourceKey)
        val parsed = fetched.events
        if (parsed.isNotEmpty()) eventSourceMemory.clear(sourceKey)
        if (parsed.isEmpty()) {
            error = context.getString(R.string.error_activity_unavailable)
        } else {
            events = parsed
            Logger.net("GET $source → ${events.size} 条", "GitHubAPI")
        }
        loading = false
    }

    // 贡献日历：GraphQL contributionsCollection（REST 拿不到 52 周）
    LaunchedEffect(login, resumeTick) {
        if (calendar == null) calLoading = true
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
            calDegraded = context.getString(R.string.label_activity_90_days)
        }
    }

    // ── 渲染：**没有整页 loading 门** ──
    //
    // 原先 `loading -> ProfileActivitySkeleton()` 是「整页骨架 →（事件到齐）→ 整页内容」。
    // 问题是两块数据不是一起到的：贡献日历走 GraphQL，比事件流慢，于是整页骨架刚让位，
    // ContributionWall 又渲染一次「加载中…」——用户看到的是
    // 「骨架 → 还有一层加载文字 → 内容」，两层加载态叠着，这就是「闪」。
    // 现在每一区按**自己的**数据就绪度在原地由骨架淡入内容，全屏只有一层加载态。
    //
    // ProvideShimmer 仍然只包一层：整页所有骨架区块共用一条微光动画
    // （每个区块各挂一条无限动画是 Motion.kt 点名的坑）。
    ProvideShimmer {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            // ── ① 概览统计（数据源：贡献日历 GraphQL）──
            // 未就绪时给骨架值条，**不给 0**：0 也是「内容」，用户会先读到它，
            // 再从 0 跳到真实值 —— 这是这一屏第三处小闪。失败（stats == null）时给「—」而不是 0。
            val stats = remember(calendar) { contributionStats(calendar) }
            SectionTitle(stringResource(R.string.label_activity_overview), if (stats != null) stringResource(R.string.label_by_contribution_calendar) else null)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val labels = listOf(stringResource(R.string.label_last_7_days), stringResource(R.string.label_last_30_days), stringResource(R.string.label_last_year))
                val values = listOf(stats?.week, stats?.month, stats?.year)
                labels.forEachIndexed { i, label ->
                    PlaceholderSwap(
                        loading = calLoading,
                        modifier = Modifier.weight(1f),
                        // 卡片必须自己 fillMaxWidth：Crossfade 的内容装在一层 **wrap-content** 的
                        // 内层 Box 里，weight 只加在外层容器上 —— 卡片不声明撑满的话，
                        // 三张卡会各自缩到「文字宽」并左对齐（此前 weight 直接加在 StatCard 上，
                        // 不存在这个问题；包进 Crossfade 后必须显式声明，否则是静默的版式回退）。
                        skeleton = { StatCardSkeleton(Modifier.fillMaxWidth()) },
                        content = { StatCard(label, values[i]?.toString() ?: "—", Modifier.fillMaxWidth()) },
                    )
                }
            }

            // ── ② 贡献墙（自带 loading：骨架网格 → 网格，见 ContributionWall.kt）──
            ContributionWall(
                calendar = calendar,
                loading = calLoading,
                error = if (calendar == null && !calLoading) stringResource(R.string.error_contributions_unavailable) else null,
                degradedNote = calDegraded,
                selectedDate = selectedDay?.date,
                onDaySelected = { selectedDay = it },
            )
            selectedDay?.let { day ->
                ContributionDayDetail(day)
            }

            // ── ③ 活动区（数据源：事件流）──
            // 两层过渡，各管各的：
            //   外层 Crossfade —— 「有活动区」↔「空 / 失败说明」是整块换，淡入淡出；
            //   内层 PlaceholderSwap —— 加载中的三块（类型分布 / 热力 / 时间线）各自就地填成内容。
            // 只有一层 loading 门会退回原来的问题（事件到了、日历没到就又冒一层加载态），
            // 没有内层就地填充则会整块溶解、版式跟着跳。
            val hasActivity = loading || events.isNotEmpty()
            Crossfade(
                targetState = hasActivity,
                animationSpec = tween(ElementMotion.REVEAL_MS),
                label = "activity-area",
            ) { show ->
                // ⚠️ Crossfade 的内容在 Box 里：**多子元素会互相叠加**，必须自己套一层 Column
                // （这一条是真机截图抓到的：六段内容全叠在同一位置，整页看起来像错版）。
                // 套在**最外层**（而不是只套 else 分支）也是刻意的：lambda 体保持「就是一个 Column」，
                // CrossfadeLayoutTest 这条源码级钉子才能用一条规则覆盖所有调用点。
                Column(Modifier.fillMaxWidth()) {
                    if (!show) {
                        ActivityEmptyState(error)
                    } else {
                        val collapsed = remember(events) { collapsePushes(events) }
                        // 类型分布（Top 5）
                        SectionTitle(stringResource(R.string.label_activity_by_type))
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            PlaceholderSwap(
                                loading = loading,
                                skeleton = { repeat(3) { TypeBarSkeleton() } },
                                content = {
                                    // 有事件就一定有分布（byType 从 events 派生），空分支不可达，只为类型完整
                                    val byType = events.groupingBy { it.type.removeSuffix("Event") }.eachCount()
                                        .entries.sortedByDescending { it.value }.take(5)
                                    val max = (byType.firstOrNull()?.value ?: 1).coerceAtLeast(1)
                                    byType.forEach { (label, count) ->
                                        TypeBar(label, count, (count * 100 / max).coerceIn(4, 100))
                                    }
                                },
                            )
                        }

                        // 活动热力（按天聚合，13 周 = events API 的 90 天上限）
                        SectionTitle(stringResource(R.string.label_activity_heatmap), stringResource(R.string.label_past_90_days))
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            PlaceholderSwap(
                                loading = loading,
                                skeleton = { HeatmapSkeleton() },
                                content = { ActivityHeatmap(events) },
                            )
                        }

                        // 时间线：连续推送先折叠（同仓库 + 同分支 + 同一天），再截前 30 条
                        SectionTitle(stringResource(R.string.label_recent_activity))
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            PlaceholderSwap(
                                loading = loading,
                                skeleton = { repeat(4) { EventRowSkeleton() } },
                                content = {
                                    collapsed.take(30).forEach { e ->
                                        EventRow(e, onClick = { if (e.repo.isNotBlank()) onOpenRepo(e.repo) })
                                    }
                                },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * 「最近活动」空态 / 失败态。
 *
 * 原先是**整页**居中提示（`loading` 之外的两个分支），但那时概览与贡献墙也一起让位了 ——
 * 日历明明已经拿到，却因为事件流为空而整屏报错。现在它只占活动区那三块的位置。
 */
@Composable
private fun ActivityEmptyState(error: String?) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (error != null) stringResource(R.string.error_activity_load_failed) else stringResource(R.string.state_no_public_activity),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Primer.TextSecondary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            error ?: stringResource(R.string.note_activity_hint),
            fontSize = 12.sp,
            color = Primer.TextTertiary,
        )
    }
}

// ───────────────────────── 骨架屏 ─────────────────────────
//
// 「骨架 → 内容」的分区替换统一走元素级的 [PlaceholderSwap]（`ui/theme/Motion.kt`）：
// 延迟现身（缓存秒回时不闪灰块）+ 骨架先退 / 内容再进（中段不糊）+ 尺寸动画（下方不被顶下去）。
// 这里曾经有一份本地实现 `RegionSwap`（`Crossfade(220ms)`），它有两个固有毛病 ——
// 容器尺寸当帧取两态最大值、两态同时半透明 —— 真机上就是「内容落地那一刻跳一下 + 糊一下」。

/**
 * 三张概览统计卡之一的骨架，尺寸对齐 [StatCard]
 * （8dp 圆角 + 1dp 边框 + 上下 12dp 内边距；数值 20sp、标签 11sp）。
 */
@Composable
private fun StatCardSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, Primer.BorderEmphasis, RoundedCornerShape(8.dp))
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.width(44.dp).height(24.dp).skeletonBlock())
        Spacer(Modifier.height(2.dp))
        Box(Modifier.width(56.dp).height(14.dp).skeletonBlock())
    }
}

/**
 * 骨架网格：7 行 × [cols] 列的小方块（贡献墙 10dp 格 / 活动热力 14dp 行高）。
 *
 * 真实网格的列宽是「按可用宽度自适应」的，这里同样用 `weight(1f)` 均分，
 * 所以换屏宽 / 换字体缩放时骨架与内容的列数、行高都对得上。
 *
 * `internal` 而非 `private`：贡献墙骨架（`ContributionWall.kt`）用的是同一份网格 ——
 * 两处各写一份的话，改格子尺寸只会改到其中一处。
 */
@Composable
internal fun SkeletonGrid(cols: Int, cellHeight: Dp) {
    Column(Modifier.fillMaxWidth()) {
        repeat(7) { row ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = if (row < 6) 2.dp else 0.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                repeat(cols) {
                    Box(Modifier.weight(1f).height(cellHeight).skeletonBlock(cornerRadius = 2.dp))
                }
            }
        }
    }
}

/** 给骨架网格套一层与 [ActivityHeatmap] 相同的面板（10dp 圆角 + 1dp 边框 + 10dp 内边距）。 */
@Composable
private fun SkeletonPanel(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, Primer.BorderEmphasis, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) { content() }
}

/** 活动热力骨架：与 [ActivityHeatmap] 同构（带边框面板 + 7×13 网格 + 汇总条）。 */
@Composable
private fun HeatmapSkeleton() {
    SkeletonPanel {
        SkeletonGrid(cols = 13, cellHeight = 14.dp)
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth(0.6f).height(12.dp).skeletonBlock())
    }
}

/** 类型分布骨架：对齐 [TypeBar]（标签行 12.5sp + 4dp 间隔 + 7dp 进度条 + 10dp 底距）。 */
@Composable
private fun TypeBarSkeleton() {
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.width(64.dp).height(14.dp).skeletonBlock())
            Box(Modifier.width(32.dp).height(13.dp).skeletonBlock())
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth(0.6f).height(7.dp).skeletonBlock(cornerRadius = 4.dp))
    }
}

/**
 * 动态事件行骨架：对齐 [EventRow]（26dp 圆形头像 + 10dp 间隔 + 两行：仓库名 17dp / 描述 17dp）。
 *
 * 刻意**不给真实对象标题留第三行**：标题只有 PR / issue / 发布等类型才有，
 * 而常见的是推送（没有标题）—— 留了就是「骨架比内容高一截」。
 */
@Composable
private fun EventRowSkeleton() {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(26.dp).skeletonBlock(cornerRadius = 13.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.fillMaxWidth(0.55f).height(17.dp).skeletonBlock())
                Spacer(Modifier.weight(1f))
                Box(Modifier.width(40.dp).height(14.dp).skeletonBlock())
            }
            Spacer(Modifier.height(3.dp))
            Box(Modifier.fillMaxWidth(0.7f).height(17.dp).skeletonBlock())
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

/**
 * 贡献日历查询区间（近一年，ISO8601 UTC）。
 *
 * ## 必须按 **UTC 天**取整（1.0.58 修的真实 bug）
 *
 * 原实现是 `[now - 364 天, now]`，`now` 精确到秒 —— 而这个区间**同时是缓存键的一部分**
 * （`profileKey(login, "calendar:$from:$to")`），于是键每次都不同、缓存**永远不可能命中**：
 * 每进一次动态页都要打一遍 GraphQL，贡献墙只能等网络，回来再画一遍（真机 77ms 的绘制帧）。
 * 真机日志里三次进页面拿到的键分别是 `…07:04:22Z` / `…07:04:24Z` / `…07:04:29Z`。
 *
 * 取整到天之后：同一天内键稳定 → L1/L2 都能命中（TTL 10 分钟）→ 过期才回源；
 * 跨 UTC 零点换上一天的键，自然滚动。**结束点取「明天 00:00Z」**（不是今天 00:00Z）——
 * 否则今天那一格会被排除在区间外，墙上的「今天」永远是空的。
 *
 * 区间同时是缓存键与查询参数：查询本身能接受任意时刻，取整只是为了键稳定，
 * 所以这里不需要动 GraphQL 那边。
 */
internal fun contributionRange(nowMs: Long = System.currentTimeMillis()): Pair<String, String> {
    val day = 24L * 60 * 60 * 1000
    val endMs = (nowMs / day + 1) * day
    val startMs = endMs - 365 * day
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
    fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
    return fmt.format(java.util.Date(startMs)) to fmt.format(java.util.Date(endMs))
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
                if (day.count > 0) pluralStringResource(R.plurals.label_contribution_count, day.count, day.count) else stringResource(R.string.state_no_contributions),
                fontSize = 11.5.sp,
                color = if (day.count > 0) Primer.Green500 else Primer.TextTertiary,
            )
        }
        if (day.count == 0) {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.note_no_contributions_day), fontSize = 11.5.sp, color = Primer.TextTertiary)
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, Primer.BorderEmphasis, RoundedCornerShape(8.dp))
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
            Text(stringResource(R.string.label_times_count, count), fontSize = 11.5.sp, color = Primer.TextTertiary)
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
            .border(1.dp, Primer.BorderEmphasis, RoundedCornerShape(10.dp))
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
                stringResource(R.string.label_activity_totals, dayCounts.sum(), max),
                fontSize = 11.5.sp,
                color = Primer.TextTertiary,
            )
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.label_less), fontSize = 9.5.sp, color = Primer.TextTertiary)
            levels.forEach { c ->
                Spacer(Modifier.width(3.dp))
                Box(Modifier.width(10.dp).height(10.dp).clip(RoundedCornerShape(2.dp)).background(c))
            }
            Spacer(Modifier.width(3.dp))
            Text(stringResource(R.string.label_more), fontSize = 9.5.sp, color = Primer.TextTertiary)
        }
    }
}

/**
 * 事件类型 → 图标 + 语义色。
 *
 * 常量表存**角色**而不是 `Color`（主题色只能在 composable 里读，表要能留在顶层常量区），
 * 颜色在渲染点 `.color()` 解析 —— 这套约定见 `ui/theme/TintRole.kt`。
 * 换掉原先的 emoji 字形（`⇧ ＋ − ★ ⑂ ◉ ⇄ ◆`）：那些字形在不同字体下粗细、基线都不一致，
 * 且全是灰的 —— 一屏「推送到 main」看不出类型差别，「谁做了什么」只能靠读文字。
 */
private data class EventVisual(val icon: ImageVector, val tint: TintRole)

private val EVENT_VISUALS: Map<String, EventVisual> = mapOf(
    "PushEvent" to EventVisual(Icons.Filled.Commit, TintRole.ACCENT),
    "CreateEvent" to EventVisual(Icons.Filled.Add, TintRole.SUCCESS),
    "DeleteEvent" to EventVisual(Icons.Filled.Delete, TintRole.DANGER),
    "WatchEvent" to EventVisual(Icons.Filled.Star, TintRole.WARNING),
    "ForkEvent" to EventVisual(Icons.AutoMirrored.Filled.CallSplit, TintRole.DONE),
    "IssueCommentEvent" to EventVisual(Icons.AutoMirrored.Filled.Comment, TintRole.NEUTRAL_SUBTLE),
    "IssuesEvent" to EventVisual(Icons.Filled.ErrorOutline, TintRole.SUCCESS),
    "PullRequestEvent" to EventVisual(Icons.AutoMirrored.Filled.CallMerge, TintRole.DONE),
    "PullRequestReviewEvent" to EventVisual(Icons.Filled.RateReview, TintRole.NEUTRAL_SUBTLE),
    "ReleaseEvent" to EventVisual(Icons.Filled.LocalOffer, TintRole.WARNING),
    "PublicEvent" to EventVisual(Icons.Filled.Public, TintRole.ACCENT),
    "MemberEvent" to EventVisual(Icons.Filled.PersonAdd, TintRole.SUCCESS),
    "GollumEvent" to EventVisual(Icons.AutoMirrored.Filled.Article, TintRole.NEUTRAL_SUBTLE),
)

/** 表里没有的类型（GitHub 会新增事件类型）用中性图标兜底，不显示成「未知」。 */
private val EVENT_VISUAL_FALLBACK = EventVisual(Icons.Filled.History, TintRole.NEUTRAL_SUBTLE)

/**
 * 动态事件行（真实对象 + 类型 + 触发者）。
 *
 * 布局：
 * ```
 * [触发者头像 26dp，右下角事件类型角标] 仓库名（粗体）        相对时间
 *                                      事件描述（含折叠后的次数与最新 sha）
 *                                      真实对象标题（PR / issue / 发布…，有才显示）
 * ```
 *
 * 与旧版的差别（旧版是「灰底 emoji 字形 + `仓库 · 描述` 一行 + 时间一行」）：
 * ① 头像接的是 `actor.avatar_url`，能看出「谁做的」（星标别人的仓库时不再是灰圈）；
 * ② 事件类型用类型色图标角标，一眼分得出推送 / 星标 / PR；
 * ③ PR / issue / 发布的**真实标题**排第三行；
 * ④ 整行可点 → 进对应仓库（原先点不动，「最近活动」看得到去不了）。
 */
@Composable
private fun EventRow(e: ActivityEvent, onClick: () -> Unit) {
    val context = LocalContext.current
    val visual = EVENT_VISUALS[e.type] ?: EVENT_VISUAL_FALLBACK
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = e.repo.isNotBlank()) { onClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(26.dp)) {
            Avatar(
                url = e.actorAvatar,
                login = e.actor.ifBlank { e.repo.substringBefore('/') },
                size = 26.dp,
            )
            // 角标：先铺一块底色「挖空」头像边缘，再画类型图标 —— 否则两色叠在一起发糊
            Box(
                Modifier.align(Alignment.BottomEnd).size(14.dp).clip(CircleShape).background(Primer.BackgroundPrimary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    visual.icon,
                    contentDescription = null,
                    tint = visual.tint.color(),
                    modifier = Modifier.size(11.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    e.repo.ifBlank { stringResource(R.string.label_unknown_repo) },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Primer.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(relativeTime(e.createdAt), fontSize = 11.5.sp, color = Primer.TextTertiary)
            }
            Spacer(Modifier.height(2.dp))
            Text(e.detail.resolve(context), fontSize = 12.5.sp, color = Primer.TextSecondary, lineHeight = 17.sp)
            if (e.title != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    e.title,
                    fontSize = 12.sp,
                    color = Primer.TextTertiary,
                    lineHeight = 17.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val tint: TintRole,
    val page: SubPage? = null,
    val danger: Boolean = false,
)

private val moreBubbleItems = listOf(
    MoreBubbleItem(R.string.profile_bubble_stars, Icons.Filled.Star, TintRole.WARNING, SubPage.Stars),
    MoreBubbleItem(R.string.nav_projects, Icons.Filled.Dashboard, TintRole.DONE, SubPage.Projects),
    MoreBubbleItem(R.string.nav_tasks, Icons.Filled.Timeline, TintRole.ACCENT, SubPage.Tasks),
    MoreBubbleItem(R.string.nav_settings, Icons.Filled.Settings, TintRole.NEUTRAL, SubPage.Settings),
)

/**
 * Profile 页切换导航：3 个主项 + 右侧圆形手柄，点手柄从**手柄上方**弹出 More 气泡。
 *
 * ## 与「当前设计」对齐的三处修正（原本不一致）
 *
 * 1. **手柄填充色**：原来用 `Primer.Border`（描边色，0xFFBFC1C9）当**填充**用，
 *    与设计里「中性面用 Gray150」的规则冲突；现在收起态是 `Gray150` + `BorderEmphasis` 描边，
 *    展开态是 `Blue500` 实心 + 白图标；
 * 2. **弹层不再是 Material 默认色**：原来用 `DropdownMenu`，容器色 / 文字色 / 图标色
 *    全走 Material 主题（既不是 Primer 池，也与 App 其它弹层不一致）。现在是
 *    `Popup` + `Primer.BackgroundPrimary` 白底 + `Primer.BorderEmphasis` 描边 + 16dp 圆角 + 阴影，
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

    // 外层 Box 固定 60dp 高：气泡作为悬浮层向上溢出，不参与导航栏高度计算，避免点击后抬高导航栏。
    // `.navigationBarsPadding()` 放在 `.height()` 之前：系统手势条那块留白算在 60dp 之外、
    // 沿用壳子底色（原来由外层 Column 的 navigationBarsPadding 提供）—— 栏自己负责这条内边距。
    Box(Modifier.fillMaxWidth().navigationBarsPadding().height(60.dp)) {
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
                        selectionColor(expanded, on = Primer.Blue500, off = Primer.BorderEmphasis),
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
                    contentDescription = if (expanded) stringResource(R.string.action_collapse_more_menu) else stringResource(R.string.action_more),
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
                        border = BorderStroke(1.dp, Primer.BorderEmphasis.copy(alpha = 0.6f)),
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
                                        label = stringResource(item.labelRes),
                                        icon = item.icon,
                                        tint = item.tint.color(),
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
                                    label = stringResource(R.string.action_sign_out_alt),
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
    val label = stringResource(tab.labelRes)
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
                contentDescription = label,
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
            label,
            fontSize = 11.sp,
            color = tint,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

// ───────────────────────── 通用仓库卡片 & 数据模型 ─────────────────────────

/**
 * 骨架屏占位卡数量。
 *
 * 取 3 而不是「按屏高铺满」：热门仓库区最多 4 张卡，仓库页首屏也远不到 5 张 ——
 * 占位数量多于内容会出现「骨架比内容还长，数据回来页面缩短」的反向跳动。
 */
private const val PROFILE_REPO_SKELETON_COUNT = 3

/**
 * 仓库卡骨架：**结构与尺寸都与 [RepoCard] 一一对应**
 * （同 6dp 圆角 / 同 1dp 边框 / 同 12dp 内边距 / 同 10dp 底距；名称行 16dp + 描述行 12dp + 语言行 12dp）。
 *
 * 热门仓库区与「仓库」页共用这一份：两处的真实卡片本来就是同一个 [RepoCard]，
 * 骨架各写一份的话，改卡片尺寸时只会改到其中一处，另一处就开始「数据到达时跳一下」。
 */
@Composable
private fun RepoCardSkeleton() {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(6.dp))
            .border(1.dp, Primer.BorderEmphasis, RoundedCornerShape(6.dp)).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 仓库名（左，可伸缩）与星数（右，固定宽度）—— 对齐 RepoCard 的 weight(1f) + 尾部计数
            Box(Modifier.fillMaxWidth(0.45f).height(16.dp).skeletonBlock())
            Spacer(Modifier.weight(1f))
            Box(Modifier.width(34.dp).height(14.dp).skeletonBlock())
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth(0.85f).height(12.dp).skeletonBlock())
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).skeletonBlock(cornerRadius = 5.dp))
            Spacer(Modifier.width(4.dp))
            Box(Modifier.width(48.dp).height(12.dp).skeletonBlock())
        }
    }
}

@Composable
private fun RepoCard(repo: RepoItem, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(6.dp)).border(1.dp, Primer.BorderEmphasis, RoundedCornerShape(6.dp)).clickable { onClick() }.padding(12.dp),
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
    /** 与当前账号的关系（账号仓库 / 协作仓库 / 非账号仓库 / 非协作仓库）。 */
    val relation: RepoRelation? = null,
)

/**
 * 解析仓库列表（`/user/repos` 等）。
 *
 * [me] 是当前登录账号：列表接口会返回 `permissions` 与 `private`，据此把每个仓库判成
 * 账号仓库 / 协作仓库 / 非账号仓库 / 非协作仓库（判定规则见 [repoRelationOf]）——
 * 「我的仓库」列表里其实混着协作仓库，不区分的话用户会以为都是自己的。
 */
internal fun parseRepos(json: String, me: String = ""): List<RepoItem> {
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
                relation = repoRelationOf(
                    ownerLogin = it.optJSONObject("owner")?.optString("login"),
                    me = me,
                    canPush = it.optJSONObject("permissions")?.optBoolean("push", false) ?: false,
                    canPull = it.optJSONObject("permissions")?.optBoolean("pull", true) ?: true,
                    isPrivate = it.optBoolean("private", false),
                ),
            )
        }
    }.getOrDefault(emptyList())
}
