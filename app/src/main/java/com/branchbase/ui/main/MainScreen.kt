package com.branchbase.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.branchbase.ui.home.HomeScreen
import com.branchbase.ui.navigation.BranchbaseNavigationBar
import com.branchbase.ui.navigation.NavDestination
import com.branchbase.ui.notification.NotificationScreen
import com.branchbase.ui.notification.NotifTarget
import com.branchbase.ui.notification.SecurityAlertScreen
import com.branchbase.ui.profile.ProfileScreen
import com.branchbase.ui.repository.RepoDeepLink
import com.branchbase.ui.repository.RepoPage
import com.branchbase.ui.repository.RepositoryScreen
import com.branchbase.ui.search.SearchScreen
import com.branchbase.ui.theme.Primer

/**
 * 主界面骨架：底部导航（3 Tab）+ 内容区。
 *
 * 个人页不再作为 Tab，改为点击首页头像进入（返回键回首页）。
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
    var notifUnread by remember { mutableStateOf(0) }

    // 仓库详情页（点击仓库进入；通知深链接可直达详情子页）
    val currentRepo = showRepo
    if (currentRepo != null) {
        BackHandler { showRepo = null }
        RepositoryScreen(
            sessionJson = sessionJson,
            owner = currentRepo.owner,
            repo = currentRepo.repo,
            onBack = { showRepo = null },
            onOpenRepo = { o, r -> showRepo = RepoDeepLink(o, r) },
            initial = currentRepo,
        )
        return
    }

    // 安全警报落地页（通知 Security 类型直达；提供「查看仓库」入口）
    val currentSecurity = showSecurity
    if (currentSecurity != null) {
        BackHandler { showSecurity = null }
        SecurityAlertScreen(
            sessionJson = sessionJson,
            owner = currentSecurity.owner,
            repo = currentSecurity.repo,
            title = currentSecurity.title,
            subjectUrl = currentSecurity.subjectUrl,
            onBack = { showSecurity = null },
            onOpenRepo = {
                showSecurity = null
                showRepo = RepoDeepLink(currentSecurity.owner, currentSecurity.repo)
            },
        )
        return
    }

    // 个人页（头像进入）
    if (showProfile) {
        BackHandler { showProfile = false }
        ProfileScreen(
            sessionJson = sessionJson,
            onBack = { showProfile = false },
            onLogout = onLogout,
            onOpenRepo = { fullName ->
                val parts = fullName.split("/")
                if (parts.size >= 2) showRepo = RepoDeepLink(parts[0], parts[1])
            },
        )
        return
    }

    // 搜索页（搜索框进入）
    if (showSearch) {
        BackHandler { showSearch = false }
        SearchScreen(
            sessionJson = sessionJson,
            onBack = { showSearch = false },
        )
        return
    }

    Scaffold(
        containerColor = Primer.BackgroundPrimary,
        bottomBar = {
            BranchbaseNavigationBar(
                selected = selected,
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
            when (selected) {
                NavDestination.Home -> HomeScreen(
                    sessionJson = sessionJson,
                    onProfileClick = { showProfile = true },
                    onSearchClick = { showSearch = true },
                    onRepoClick = { fullName ->
                        val parts = fullName.split("/")
                        if (parts.size >= 2) showRepo = RepoDeepLink(parts[0], parts[1])
                    },
                )
                NavDestination.Explore -> Placeholder("探索（待接入）")
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

@Composable
private fun Placeholder(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = Primer.TextTertiary)
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