package com.branchbase.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.branchbase.ui.home.HomeScreen
import com.branchbase.ui.navigation.PageBackHandler
import com.branchbase.ui.navigation.BranchbaseNavigationBar
import com.branchbase.ui.navigation.BackDisposition
import com.branchbase.ui.navigation.backDisposition
import com.branchbase.ui.navigation.NavDestination
import com.branchbase.ui.navigation.PageLevel
import com.branchbase.ui.navigation.PageSwitcher
import com.branchbase.ui.navigation.TabSwitcher
import com.branchbase.ui.navigation.rememberTopLevelBackAction
import com.branchbase.ui.notification.NotificationScreen
import com.branchbase.ui.notification.NotifSnapshot
import com.branchbase.ui.notification.NotifTarget
import com.branchbase.ui.notification.SecurityAlertScreen
import com.branchbase.ui.profile.ProfileScreen
import com.branchbase.ui.repository.RepoDeepLink
import com.branchbase.ui.repository.RepoPage
import com.branchbase.ui.repository.RepositoryScreen
import com.branchbase.ui.search.SearchScreen
import com.branchbase.ui.theme.Primer

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
 */
@Composable
fun MainScreen(
    sessionJson: String,
    onLogout: () -> Unit,
) {
    var selected by remember { mutableStateOf(NavDestination.Home) }
    var showProfile by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
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
        else -> MainRoute.Tabs(selected)
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
    PageBackHandler {
        when {
            backDisposition(route.depth) == BackDisposition.ExitApp -> confirmExit()
            route is MainRoute.Repo -> showRepo = null
            route is MainRoute.Security -> showSecurity = null
            route is MainRoute.Profile -> showProfile = false
            route is MainRoute.Search -> showSearch = false
        }
    }

    PageSwitcher(state = route, modifier = Modifier.fillMaxSize(), label = "main-page") { r ->
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
                onBack = { showProfile = false },
                onLogout = onLogout,
                onOpenRepo = { fullName ->
                    val parts = fullName.split("/")
                    if (parts.size >= 2) showRepo = RepoDeepLink(parts[0], parts[1])
                },
            )

            // 搜索页（搜索框进入）
            MainRoute.Search -> SearchScreen(
                sessionJson = sessionJson,
                onBack = { showSearch = false },
                // 结果点进仓库/issue/PR/提交/文件：与通知深链接同一条路由，
                // 返回时回到搜索页（搜索词与结果由 SearchViewModel + 缓存保留）
                onOpenInApp = { showRepo = it },
            )

            // Tab 骨架（首页 / 消息：同级切换做淡入淡出）
            is MainRoute.Tabs -> Scaffold(
                containerColor = Primer.BackgroundPrimary,
                bottomBar = {
                    BranchbaseNavigationBar(
                        selected = r.destination,
                        onSelect = { selected = it },
                        badgeCounts = mapOf(NavDestination.Notifications to notifUnread),
                    )
                },
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    TabSwitcher(
                        state = r.destination,
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

/**
 * 主界面顶层路由。
 *
 * 层级（[PageLevel.depth]）决定切换方向：Tab 骨架在最外层（0），个人页 / 搜索是第二层（1），
 * 仓库详情与安全警报可能从任意一层打开、也可能互相跳转，因此放第三层（2）——
 * 这样「首页 → 仓库详情」是推进（从右进），「仓库详情 → 个人页」是返回（向右出）。
 */
private sealed interface MainRoute : PageLevel {

    data class Tabs(val destination: NavDestination) : MainRoute {
        override val depth: Int get() = 0
    }

    data object Profile : MainRoute {
        override val depth: Int get() = 1
    }

    data object Search : MainRoute {
        override val depth: Int get() = 1
    }

    data class Repo(val link: RepoDeepLink) : MainRoute {
        override val depth: Int get() = 2
    }

    data class Security(val target: NotifTarget.Security) : MainRoute {
        override val depth: Int get() = 2
    }
}

/** 通知跳转目标 → 仓库深链接（MVP：CheckSuite/CheckRun 因 id 语义差异暂落到工作流 tab；WorkflowRun 已可直达 Run 详情） */
private fun toDeepLink(t: NotifTarget): RepoDeepLink = when (t) {
    is NotifTarget.Issue -> RepoDeepLink(t.owner, t.repo, issueNumber = t.number)
    is NotifTarget.Pull -> RepoDeepLink(t.owner, t.repo, pullNumber = t.number)
    is NotifTarget.Commit -> RepoDeepLink(t.owner, t.repo, commitSha = t.sha)
    is NotifTarget.Run -> if (t.runId > 0) RepoDeepLink(t.owner, t.repo, runId = t.runId) else RepoDeepLink(t.owner, t.repo, page = RepoPage.Workflows)
    is NotifTarget.Security -> RepoDeepLink(t.owner, t.repo)
    is NotifTarget.Repo -> RepoDeepLink(t.owner, t.repo)
}
