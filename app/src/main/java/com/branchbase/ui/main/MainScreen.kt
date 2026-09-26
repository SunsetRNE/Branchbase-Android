package com.branchbase.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.branchbase.ui.home.FrequentReposScreen
import com.branchbase.ui.home.HomeScreen
import com.branchbase.ui.navigation.BranchbaseNavigationBar
import com.branchbase.ui.navigation.BackDisposition
import com.branchbase.ui.navigation.backDisposition
import com.branchbase.ui.navigation.NavDestination
import com.branchbase.ui.navigation.NavigationShell
import com.branchbase.ui.navigation.PageBackHandler
import com.branchbase.ui.navigation.PageLevel
import com.branchbase.ui.navigation.PageSwitcher
import com.branchbase.ui.navigation.TabSwitcher
import com.branchbase.ui.navigation.rememberTopLevelBackAction
import com.branchbase.ui.notification.NotificationScreen
import com.branchbase.ui.notification.NotifSnapshot
import com.branchbase.ui.notification.NotifTarget
import com.branchbase.ui.notification.SecurityAlertScreen
import com.branchbase.ui.profile.ProfileScreen
import com.branchbase.ui.profile.SubPage
import com.branchbase.ui.repository.RepoDeepLink
import com.branchbase.ui.repository.RepoPage
import com.branchbase.ui.repository.RepositoryScreen
import com.branchbase.ui.search.SearchScreen
import com.branchbase.ui.log.Logger
import androidx.compose.ui.platform.LocalContext

/**
 * 主界面骨架：底部导航（2 Tab：首页 / 消息）+ 内容区。
 *
 * 个人页不作为 Tab，改为点击首页头像进入（返回键回首页）；
 * 原先的「探索」Tab 只有占位页，已随占位页一并移除。
 *
 * ## 页面切换为什么改成「路由 + PageSwitcher」
 *
 * 原来是 5 个「提前 return」的 if 依次判断（仓库详情 → 安全警报 → 个人页 → 搜索 → Tab 骨架）：
 * 状态一变整棵内容树直接换掉，**没有任何过渡**，观感是硬切。
 * 现在先算出唯一路由 [MainRoute]，交给 [PageSwitcher] 按层级做位移动画：
 * 进子页从右滑入、返回向右滑出、Tab 之间淡入淡出。
 *
 * 路由 `when` 的顺序 = 原来的 return 顺序（前者优先），语义完全等价；
 * 返回键也从「每个页面各挂一个 BackHandler」收敛成**按当前路由分派的一个**，
 * 避免动画期间新旧两个页面的 BackHandler 同时存在、抢同一个返回事件。
 *
 * ## 底部导航为什么在 [NavigationShell] 里、而不在 [PageSwitcher] 里
 *
 * 骨架原来是 `PageSwitcher { ... Scaffold(bottomBar = 导航栏) ... }`：栏长在切换器**里面**，
 * 于是切 Tab（`MainRoute.Tabs` 带上 selected 时）会被同级动效连栏一起播位移 + 交叉淡入 ——
 * 用户看到的就是「切页面时导航栏上下跳」。现在栏归 [NavigationShell]（在切换器外面），
 * Tab 也不进外层路由（Tab 是内容区自己的维度，由内层 [TabSwitcher] 渲染）：
 * **页面切换只动页面，外壳一动不动**。
 */
@Composable
fun MainScreen(
    sessionJson: String,
    onLogout: () -> Unit,
) {
    var selected by remember { mutableStateOf(NavDestination.Home) }
    var showProfile by remember { mutableStateOf(false) }

    // 「添加账号」的登录页整屏接管时，state 一变这棵子树会被销毁；返回时重建，
    // 导航状态全丢 —— 用户从「设置 → 账号管理」进去的，回来却落在别处。
    // 落点寄存在 MainNavMemory（进程内，不受销毁影响），这里**消费一次**即恢复：
    // 读走就清空，否则用户下次正常启动还会被拽回「账号管理」。
    var pendingSubPage by remember { mutableStateOf<SubPage?>(null) }
    LaunchedEffect(Unit) {
        MainNavMemory.consume()?.let { route ->
            selected = when (route.tab) {
                MainNavMemory.MainTab.MESSAGES -> NavDestination.Notifications
                MainNavMemory.MainTab.HOME -> NavDestination.Home
            }
            if (route.onProfile) {
                showProfile = true
                // 子页名认不出来就停在个人页主页，总比落错页好
                pendingSubPage = route.profileSubPage?.let { name ->
                    SubPage.entries.firstOrNull { it.name == name }
                }
            }
            Logger.ui("恢复上次落点：tab=${route.tab} 个人页=${route.onProfile} 子页=${route.profileSubPage}", "Compose")
        }
    }
    var showSearch by remember { mutableStateOf(false) }
    // 「常用仓库」置顶管理页（首页长按标题 / 点管理图标进入；见 FrequentReposScreen）
    var showFrequent by remember { mutableStateOf(false) }
    var showRepo by remember { mutableStateOf<RepoDeepLink?>(null) }
    var showSecurity by remember { mutableStateOf<NotifTarget.Security?>(null) }
    // 未读徽标与首页「待处理」卡片同源：订阅快照的未读数。
    // 原来只有「消息页组合时上报」这一个来源 —— 冷启动停在下 Tab 时徽标恒为 0，
    // 而首页卡片已经有数字（首页预取已填过快照），两处自相矛盾。
    val snapshotUnread by NotifSnapshot.unreadFlow.collectAsState()
    var notifUnread by remember { mutableStateOf(snapshotUnread) }
    LaunchedEffect(snapshotUnread) { notifUnread = snapshotUnread }

    // 唯一路由（条件顺序与原「提前 return」一致，前者优先）
    val currentRepo = showRepo
    val currentSecurity = showSecurity
    val route: MainRoute = when {
        currentRepo != null -> MainRoute.Repo(currentRepo)
        currentSecurity != null -> MainRoute.Security(currentSecurity)
        showProfile -> MainRoute.Profile
        showSearch -> MainRoute.Search
        showFrequent -> MainRoute.FrequentRepos
        else -> MainRoute.Tabs
    }

    // 返回键按路由分派：顶层 Tab → **再按一次退出应用**（不再回登录页，见 TopLevelBack.kt）；
    // 更深的路由 → 关掉当前页。
    //
    // 两个坑都踩过，一起焊死：
    // 1. 早先写的是 `enabled = route.depth > 0`（顶层整个关掉），把「顶层按返回」让给了外层
    //    LoginFlow 的兜底 handler —— 中间多一层门（如提交模式引导页）就漏成「直接退出 App」；
    // 2. 改成 `enabled = true` 之后，退场动画期间（外层 PageSwitcher 已切走）这个
    //    handler 仍然启用，会把用户紧接着的第二次返回键吃掉 —— 「再按一次退出」失灵。
    //    现在由 PageBackHandler 叠加 LocalPageActive：**只有当前页能抢返回键**。
    val confirmExit = rememberTopLevelBackAction()
    // 这一层只管**顶层**（Tab 骨架）：退回上一层由下面 PageSwitcher 的 `onBack` 兜底
    // （那条规则是穷尽 `when`，新增路由漏不掉）。两层各管一段，不再互相抄一份路由判断。
    PageBackHandler { confirmExit() }

    /**
     * 子页的**默认返回**：一条规则（穷尽 `when` ⇒ 新增路由必须在这里表态）。
     *
     * 老写法是「每个页面自己在 `onBack` 回调里清自己的状态」，主界面这四条路由还算好找，
     * 仓库页那边十几个子页就漏了一个（见 [RepositoryScreen]）。
     */
    fun leavePage() {
        when (route) {
            MainRoute.Tabs -> Unit
            MainRoute.Profile -> showProfile = false
            MainRoute.Search -> showSearch = false
            MainRoute.FrequentRepos -> showFrequent = false
            is MainRoute.Repo -> showRepo = null
            is MainRoute.Security -> showSecurity = null
        }
    }

    // 底部导航栏由 [NavigationShell] 持有（**不在**下面的 PageSwitcher 里）。
    // 放进切换器里的话，切 Tab 会被同级动效连着整条栏一起播（2026-09 之前那 2% 垂直位移就是这样
    // 把「切页面时导航栏上下跳」带出来的：旧栏上移、新栏上浮、两栏错位叠着）；
    // 现在同级已是纯淡化，这条规则仍不变 —— 页面切换只动页面。
    // 栏只在「该出现」时进出（进子页自己收起）。
    NavigationShell(
        bar = {
            BranchbaseNavigationBar(
                selected = selected,
                onSelect = { selected = it },
                badgeCounts = mapOf(NavDestination.Notifications to notifUnread),
            )
        },
        // 只有 Tab 骨架有底部导航；仓库详情 / 个人页 / 搜索都是全屏页
        barVisible = route is MainRoute.Tabs,
        modifier = Modifier.fillMaxSize(),
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            PageSwitcher(
                state = route,
                onBack = ::leavePage,
                modifier = Modifier.fillMaxSize(),
                label = "main-page",
            ) { r ->
                when (r) {
                    // 仓库详情页（点击仓库进入；通知深链接可直达详情子页）
                    is MainRoute.Repo -> RepositoryScreen(
                        sessionJson = sessionJson,
                        owner = r.link.owner,
                        repo = r.link.repo,
                        onBack = { showRepo = null },
                        onOpenRepo = { o, r2 -> showRepo = RepoDeepLink(o, r2) },
                        initial = r.link,
                    )

                    // 安全警报落地页（通知 Security 类型直达；提供「查看仓库」入口）
                    is MainRoute.Security -> SecurityAlertScreen(
                        sessionJson = sessionJson,
                        owner = r.target.owner,
                        repo = r.target.repo,
                        title = r.target.title,
                        subjectUrl = r.target.subjectUrl,
                        onBack = { showSecurity = null },
                        onOpenRepo = {
                            showSecurity = null
                            showRepo = RepoDeepLink(r.target.owner, r.target.repo)
                        },
                    )

                    // 个人页（头像进入）
                    MainRoute.Profile -> ProfileScreen(
                        sessionJson = sessionJson,
                        // 从寄存点恢复个人页里的子页（例如「设置 → 账号管理」）。
                        // 用户自己按返回时清空它，否则下次进个人页会被拽回旧子页。
                        initialSubPage = pendingSubPage,
                        initialSubPageConsumed = { pendingSubPage = null },
                        onBack = { showProfile = false },
                        onLogout = onLogout,
                        onOpenRepo = { fullName ->
                            val parts = fullName.split("/")
                            if (parts.size >= 2) showRepo = RepoDeepLink(parts[0], parts[1])
                        },
                        // 子页里的深链接（设置 → 本地仓库 的「进入」）：目标由子页决定，
                        // 主界面只负责把它挂到仓库路由上（与搜索页的 onOpenInApp 同一条路）
                        onOpenRepoDeepLink = { showRepo = it },
                    )

                    // 搜索页（搜索框进入）
                    MainRoute.Search -> SearchScreen(
                        sessionJson = sessionJson,
                        onBack = { showSearch = false },
                        // 结果点进仓库/issue/PR/提交/文件：与通知深链接同一条路由，
                        // 返回时回到搜索页（搜索词与结果由 SearchViewModel + 缓存保留）
                        onOpenInApp = { showRepo = it },
                    )

                    // 常用仓库置顶管理页（首页长按标题进入）
                    MainRoute.FrequentRepos -> FrequentReposScreen(
                        sessionJson = sessionJson,
                        onBack = { showFrequent = false },
                    )

                    // Tab 骨架（首页 / 消息：同级切换做淡入淡出）
                    //
                    // 顶部内边距原来由 Scaffold 的 innerPadding 给（HomeScreen / NotificationScreen
                    // 自己不带状态栏内边距）；骨架换成 NavigationShell 后，壳子只管底部导航栏那一块，
                    // 顶部在这里补 —— 只取「非底部」的系统栏内边距，底部由导航栏自己负责（M3 NavigationBar
                    // 自带 windowInsets），两处都取会叠成两层。
                    MainRoute.Tabs -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(
                                WindowInsets.systemBars.only(
                                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                                ),
                            ),
                    ) {
                        // Tab 是内容区自己的维度（不进外层路由）：这里淡入淡出，导航栏一动不动
                        TabSwitcher(
                            state = selected,
                            modifier = Modifier.fillMaxSize(),
                            label = "main-tab",
                        ) { dest ->
                            when (dest) {
                                NavDestination.Home -> HomeScreen(
                                    sessionJson = sessionJson,
                                    onProfileClick = { showProfile = true },
                                    onSearchClick = { showSearch = true },
                                    onRepoClick = { fullName ->
                                        val parts = fullName.split("/")
                                        if (parts.size >= 2) showRepo = RepoDeepLink(parts[0], parts[1])
                                    },
                                    onOpenNotifications = { selected = NavDestination.Notifications },
                                    onEditFrequent = { showFrequent = true },
                                )

                                NavDestination.Notifications -> NotificationScreen(
                                    sessionJson = sessionJson,
                                    onOpenTarget = { target ->
                                        when (target) {
                                            is NotifTarget.Security -> showSecurity = target
                                            else -> showRepo = toDeepLink(target)
                                        }
                                    },
                                    onUnreadCountChange = { notifUnread = it },
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
 * 主界面顶层路由。
 *
 * 层级（[PageLevel.depth]）决定切换方向：Tab 骨架在最外层（0），个人页 / 搜索是第二层（1），
 * 仓库详情与安全警报可能从任意一层打开、也可能互相跳转，因此放第三层（2）——
 * 这样「首页 → 仓库详情」是推进（从右进），「仓库详情 → 个人页」是返回（向右出）。
 */
private sealed interface MainRoute : PageLevel {

    /**
     * Tab 骨架（首页 / 消息）。
     *
     * **刻意不带「当前 Tab」**：Tab 是内容区自己的维度（由 [TabSwitcher] 渲染），不是「换页」。
     * 把 selected 塞进路由的话，切 Tab 就等于换了路由 —— 外层 [PageSwitcher] 会当成同级换页，
     * 把整块内容连导航栏一起播位移 + 交叉淡入（而且内层 TabSwitcher 还会再播一次），
     * 这正是「切页面时导航栏上下跳」的来源。外层路由只描述「在哪一层」。
     */
    data object Tabs : MainRoute {
        override val depth: Int get() = 0
    }

    data object Profile : MainRoute {
        override val depth: Int get() = 1
    }

    data object Search : MainRoute {
        override val depth: Int get() = 1
    }

    /** 「常用仓库」置顶管理页：从首页打开，与搜索页同一层（首页 → 它 = 推进） */
    data object FrequentRepos : MainRoute {
        override val depth: Int get() = 1
    }

    data class Repo(val link: RepoDeepLink) : MainRoute {
        override val depth: Int get() = 2

        /**
         * 首帧重页：进仓库详情那一帧真机测到 **244ms**，其中 181.8ms 在「动画」段（= 这一帧的重组）。
         * 标上它 = 放弃方向位移、只做短淡化（判据见 [PageLevel.heavyFirstFrame]）。
         */
        override val heavyFirstFrame: Boolean get() = true
    }

    data class Security(val target: NotifTarget.Security) : MainRoute {
        override val depth: Int get() = 2
    }
}

/** 通知跳转目标 → 仓库深链接（WorkflowRun 可直达 Run 详情；CheckSuite/CheckRun 只能落到工作流 tab） */
private fun toDeepLink(t: NotifTarget): RepoDeepLink = when (t) {
    is NotifTarget.Issue -> RepoDeepLink(t.owner, t.repo, issueNumber = t.number)
    is NotifTarget.Pull -> RepoDeepLink(t.owner, t.repo, pullNumber = t.number)
    is NotifTarget.Commit -> RepoDeepLink(t.owner, t.repo, commitSha = t.sha)
    is NotifTarget.Run -> if (t.runId > 0) RepoDeepLink(t.owner, t.repo, runId = t.runId) else RepoDeepLink(t.owner, t.repo, page = RepoPage.Workflows)
    is NotifTarget.Workflows -> RepoDeepLink(t.owner, t.repo, page = RepoPage.Workflows)
    is NotifTarget.Security -> RepoDeepLink(t.owner, t.repo)
    is NotifTarget.Repo -> RepoDeepLink(t.owner, t.repo)
}
