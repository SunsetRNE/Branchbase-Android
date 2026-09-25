package com.branchbase.ui.navigation

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「系统栏内边距」的结构性钉子（源码级，套路同 `SettingsSpecTest` / `FileEditorWiringTest`）。
 *
 * ## 为什么这件事要钉在源码上
 *
 * 应用从 Android 15 起就是 **edge-to-edge 强制**的：窗口铺满整屏，状态栏与系统导航栏
 * （手势条 / 三键虚拟导航）都浮在内容之上。谁不消费内边距，**没有报错、没有崩溃、测试也不会红** ——
 * 只是「返回箭头压在状态栏底下」「列表最后一行滚不出手势条」，而且只在真机上看得见。
 *
 * 仓库页那条路由最容易漏：`RepositoryScreen` 的 `NavigationShell(barVisible = route is RepoRoute.Tab)`
 * 在进详情页时把底部导航栏整个收起，于是**子页必须自己取底部内边距**。
 * 2026-09-22 的现场就是 `JobLogScreen`（唯一一个没走 `DetailScaffold` 的详情页）：
 * 它直接用 `DetailTopBar` 搭根，而 `DetailTopBar` 自己不取任何内边距 —— 顶栏与日志列表
 * 两头都压进系统栏。
 *
 * ## 判据
 *
 * 每个全屏页的**根**要么自己消费（`statusBarsPadding` / `navigationBarsPadding` /
 * `windowInsetsPadding`），要么交给一个**已经消费过**的壳（下面 [shells] 列出的那几个）。
 * 两者都没有 = 这一页压系统栏。
 *
 * ⚠️ 新增页面时**必须在这里登记**：这个清单是显式的（不是扫全目录），
 * 因为「哪些是全屏页」只有人知道 —— 而漏登记的后果正是这条钉子想防的。
 */
class SystemBarInsetsTest {

    /** 消费系统栏内边距的写法（任取其一是合格）。 */
    private val consumes = listOf("statusBarsPadding", "navigationBarsPadding", "windowInsetsPadding")

    /**
     * **代管壳**：它们自己消费系统栏，用它们的页面不用再取（再取就是两层）。
     *
     * 每个壳都要在下一条用例里被验证「真的取了」—— 否则这里就成了一个空头承诺。
     */
    private val shells = listOf("DetailScaffold(", "FullScreen(", "DecisionScreenShell(", "NavigationShell(")

    /**
     * 全屏页（铺满窗口、不依赖任何外层内边距的那些）。
     *
     * 含「主骨架的 Tab 内容」之外的全部页面；Tab 内容不在此列 —— 它们跑在
     * `MainScreen` / `RepositoryScreen` 的 Tab 骨架里，顶部内边距由骨架给、底部由导航栏给。
     */
    private val fullScreenPages = listOf(
        // 仓库树：详情 / 列表 / 流程 / 决策
        "src/main/java/com/branchbase/ui/repository/JobLogScreen.kt",
        "src/main/java/com/branchbase/ui/repository/IssueDetailScreen.kt",
        "src/main/java/com/branchbase/ui/repository/RepositoryDetailScreens.kt",
        "src/main/java/com/branchbase/ui/repository/RepositoryFileViewer.kt",
        "src/main/java/com/branchbase/ui/repository/RepositoryListScreens.kt",
        "src/main/java/com/branchbase/ui/repository/RepositoryWorkflowScreens.kt",
        "src/main/java/com/branchbase/ui/repository/WorkflowDispatchScreen.kt",
        "src/main/java/com/branchbase/ui/repository/ReleaseScreens.kt",
        "src/main/java/com/branchbase/ui/repository/BranchCompareScreen.kt",
        "src/main/java/com/branchbase/ui/repository/LocalDiffScreen.kt",
        "src/main/java/com/branchbase/ui/repository/BranchManageScreen.kt",
        "src/main/java/com/branchbase/ui/repository/BranchSyncScreen.kt",
        "src/main/java/com/branchbase/ui/repository/LocalBranchSyncScreen.kt",
        "src/main/java/com/branchbase/ui/repository/GithubWebLoginScreen.kt",
        "src/main/java/com/branchbase/ui/decision/CollabScreens.kt",
        "src/main/java/com/branchbase/ui/decision/CommitPrepScreens.kt",
        "src/main/java/com/branchbase/ui/decision/SyncDecisionScreens.kt",
        // 个人页的子页与独立页
        "src/main/java/com/branchbase/ui/profile/SubPageScreens.kt",
        "src/main/java/com/branchbase/ui/profile/AccountsScreen.kt",
        "src/main/java/com/branchbase/ui/profile/CommitModeScreen.kt",
        "src/main/java/com/branchbase/ui/profile/ProfileEditScreen.kt",
        "src/main/java/com/branchbase/ui/profile/TranslateSettingsScreen.kt",
        "src/main/java/com/branchbase/ui/log/LogScreen.kt",
        "src/main/java/com/branchbase/ui/settings/GitProxyScreen.kt",
        "src/main/java/com/branchbase/ui/task/TaskScreen.kt",
        "src/main/java/com/branchbase/ui/search/SearchScreen.kt",
        "src/main/java/com/branchbase/ui/notification/SecurityAlertScreen.kt",
        // 登录 / 引导
        "src/main/java/com/branchbase/ui/auth/LoginScreens.kt",
        "src/main/java/com/branchbase/ui/auth/CommitModeGuide.kt",
    )

    private fun source(path: String): String {
        val f = File(path)
        assertTrue("找不到源文件：${f.absolutePath}（单测工作目录应为 app 模块根）", f.exists())
        return f.readText()
    }

    @Test
    fun `每个全屏页的根都要消费系统栏内边距`() {
        fullScreenPages.forEach { path ->
            val src = source(path)
            val ok = consumes.any { src.contains(it) } || shells.any { src.contains(it) }
            assertTrue(
                "$path 既不自己取系统栏内边距，也没有用带内边距的壳（${shells.joinToString(" / ")}）——" +
                    "edge-to-edge 下这一页会压状态栏与手势条",
                ok,
            )
        }
    }

    /** 上一条把「用壳」当成合格，所以壳本身必须**真的**取了内边距，否则那是空头承诺。 */
    @Test
    fun `代管壳自己必须消费系统栏内边距`() {
        val detailScaffold = source("src/main/java/com/branchbase/ui/repository/DetailScaffold.kt")
        assertTrue(
            "DetailScaffold 是详情页的公共壳，必须自己取状态栏 + 导航栏内边距",
            detailScaffold.contains("statusBarsPadding()") && detailScaffold.contains("navigationBarsPadding()"),
        )

        val workflow = source("src/main/java/com/branchbase/ui/repository/RepositoryWorkflowScreens.kt")
        val fullScreen = workflow.substringAfter("private fun FullScreen(")
        assertTrue(
            "FullScreen（工作流页的壳）必须自己取状态栏 + 导航栏内边距",
            fullScreen.contains("statusBarsPadding()") && fullScreen.contains("navigationBarsPadding()"),
        )

        val decision = source("src/main/java/com/branchbase/ui/decision/DecisionComponents.kt")
        val shell = decision.substringAfter("fun DecisionScreenShell(")
        assertTrue(
            "DecisionScreenShell 必须自己取状态栏 + 导航栏内边距",
            shell.contains("statusBarsPadding()") && shell.contains("navigationBarsPadding()"),
        )
    }

    /**
     * 仓库页那条路由最容易漏：进详情页时底部导航栏被收起（`barVisible = route is RepoRoute.Tab`），
     * 于是**没有人为子页兜底底部内边距**。这条用例把「谁来兜底」写死在这里。
     */
    @Test
    fun `仓库树收起底部导航栏时子页必须自己兜底`() {
        val repo = source("src/main/java/com/branchbase/ui/repository/RepositoryScreen.kt")
        assertTrue(
            "仓库页只有 Tab 骨架带底部导航栏（进详情页时它被收起）",
            repo.contains("barVisible = route is RepoRoute.Tab"),
        )
        assertTrue(
            "Tab 骨架自己只取状态栏（底部交给 RepoBottomBar），这一点要保持",
            repo.contains(".statusBarsPadding(),"),
        )
        val bar = source("src/main/java/com/branchbase/ui/repository/RepositoryScreen.kt")
        assertTrue(
            "RepoBottomBar 必须自己取导航栏内边距（NavigationShell 不会再加一层）",
            bar.contains("RepoBottomBar") && bar.contains(".navigationBarsPadding()"),
        )
    }
}
