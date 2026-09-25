package com.branchbase.ui.repository

import com.branchbase.ui.log.LOG_ANCHORS
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Git 工作台的**接线与插桩**钉子（源码级，与 `GitPanelStageTest` / `LogAnchorsTest` 同一套路）。
 *
 * 为什么这些必须在源码上钉：它们全都**只表现成真机上的行为**，JVM 单测里既没有 Compose 运行时、
 * 也跑不起 libgit2 ——
 *
 * 1. **面板内不许执行任何写操作**：`git-mode-design.md` §6.1 把动作分成三档
 *    （面板内直接执行 = 安全 / 走决策页 = 有后果 / 明确不做）。面板里能做的是「读 + 给出口」，
 *    一旦有人图省事在这里直接 `gitCommit` / `discardAllChanges`，分档就静默失效了
 *    —— 而它失效的样子是「浮层里误触一下就丢了改动」，不是编译错误；
 * 2. **两个宿主的出口不能只接一边**：代码页与文件页共用 `GitPanelViewHost`，但出口回调是各传各的。
 *    只接一边的表现是「代码页的面板有分支管理、文件页的没有」—— 用户报过同类不一致（Git 球门控那次）；
 * 3. **日志锚点是契约**：`LOG_ANCHORS` 那张表会被打进导出的 `report.md`，收到日志的人照着 grep。
 *    表里有、代码里没打（或反过来）都等于「照表搜不到东西」，所以这里把新锚点「Git工作台」
 *    的**四个关键入口**逐个钉住（`LogAnchorsTest` 只保证「表里的 tag 被用过至少一次」，
 *    挡不住「只在一个入口打了」）。
 */
class GitWorkbenchWiringTest {

    private fun source(path: String): String {
        val file = File(path)
        assertTrue("找不到源文件：${file.absolutePath}（单测工作目录应为 app 模块根）", file.exists())
        return file.readText()
    }

    /** 面板这一族的源码（含状态模型与两个数据档）。 */
    private val panelSources = listOf(
        "src/main/java/com/branchbase/ui/repository/GitBubblePanel.kt",
        "src/main/java/com/branchbase/ui/repository/GitPanelStage.kt",
        "src/main/java/com/branchbase/ui/repository/GitWorkspacePanel.kt",
        "src/main/java/com/branchbase/ui/repository/GitRefsPanel.kt",
        "src/main/java/com/branchbase/ui/repository/GitRefsModels.kt",
        "src/main/java/com/branchbase/ui/repository/CommitGraphPanel.kt",
    )

    @Test
    fun `面板里不许出现任何 git 写操作`() {
        // 写操作全表（与 RustBridge 的公开方法同名）：出现任何一个都说明有人把「有后果的动作」
        // 搬进了浮层，而不是让它走决策页 / 既有管理页
        val writes = listOf(
            "gitCommit", "gitPushDetailed", "gitPushSetUpstream", "gitPullDetailed", "gitCloneDetailed",
            "gitResetSoft", "gitResetHardRemote", "gitAmend", "gitRevert", "gitFetchRemote",
            "checkoutBranch", "createBranchLocal", "deleteBranchLocal", "discardAllChanges",
        )
        val offenders = panelSources.flatMap { path ->
            val text = source(path)
            writes.filter { text.contains("RustBridge.$it(") }.map { "$path → $it" }
        }
        assertEquals(
            "Git 面板只能读 + 给出口：写操作必须走决策页 / 分支管理页（git-mode-design.md §6.1）",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `两个宿主都必须把出口接上面板`() {
        // onOpenBranches = 面板里的「分支管理」出口。少了它，那一档就退化成只读死胡同
        // （D-j 要求动作落既有页面，但路得指出来）
        val hosts = listOf(
            "src/main/java/com/branchbase/ui/repository/RepositoryScreen.kt" to "代码页",
            "src/main/java/com/branchbase/ui/repository/RepositoryFileViewer.kt" to "文件页",
        )
        val missing = hosts.filter { (path, _) -> !source(path).contains("onOpenBranches =") }
        assertEquals("这两个宿主都必须给面板接上分支管理出口：$missing", emptyList<Pair<String, String>>(), missing)
    }

    @Test
    fun `设置列表的进入必须带 openGitPanel`() {
        // 「进入」= 代码页 + 面板开到视图档。漏了 openGitPanel 的表现是
        // 「点了进仓库了，但面板关着」—— 入口还在，指示的目标却没了
        val text = source("src/main/java/com/branchbase/ui/profile/SubPageScreens.kt")
        val at = text.indexOf("fun enterRepo(")
        assertTrue("LocalRepoScreen 里找不到 enterRepo（设置列表的『进入』入口）", at >= 0)
        val body = text.substring(at, text.indexOf("\n    }", at))
        assertTrue(
            "『进入』必须构造带 openGitPanel = true 的深链接，当前：$body",
            body.contains("RepoDeepLink(") && body.contains("openGitPanel = true"),
        )
    }

    @Test
    fun `新锚点 Git工作台 的四个入口都真的打了日志`() {
        assertTrue(
            "锚点「Git工作台」要登记进 LOG_ANCHORS，否则导出包的 report.md 里没有它",
            LOG_ANCHORS.any { it.first == GIT_WORKBENCH_LOG_TAG },
        )
        assertTrue(
            "tag 常量本身要带着字面量（LogAnchorsTest 按字面量到源码里搜，常量写歪了表就烂了）",
            source("src/main/java/com/branchbase/ui/repository/GitPanelStage.kt")
                .contains("\"$GIT_WORKBENCH_LOG_TAG\""),
        )
        // 四处入口：面板动作 / 深链接进入与返回退档（代码页）/ 设置列表进入 / 引用树取数。
        // 这里查的是**常量引用**（各处都该用 GIT_WORKBENCH_LOG_TAG，不写字面量 ——
        // 那样才能保证 tag 只有一处真源）
        val entries = mapOf(
            "src/main/java/com/branchbase/ui/repository/GitBubblePanel.kt" to "面板动作与档位",
            "src/main/java/com/branchbase/ui/repository/RepositoryScreen.kt" to "深链接进入与返回退档",
            "src/main/java/com/branchbase/ui/profile/SubPageScreens.kt" to "设置列表进入",
            "src/main/java/com/branchbase/ui/repository/GitRefsPanel.kt" to "引用树取数",
        )
        val silent = entries.filter { (path, _) -> !source(path).contains("GIT_WORKBENCH_LOG_TAG") }
        assertEquals("这些入口必须打「Git工作台」锚点，否则日志里只剩半边链路：$silent", emptyMap<String, String>(), silent)
    }

    @Test
    fun `图形档的取数结果与失败都要留痕`() {
        // 提交图是 REST 取数，面板上只显示「N 条」或一句「加载失败」——
        // 事后定位「是限额、是网络、还是分支名不对」只能靠日志
        val text = source("src/main/java/com/branchbase/ui/repository/CommitGraphPanel.kt")
        assertTrue("提交图取数成功要记一条（带条数）", text.contains("提交图 ▸") && text.contains("Logger.net("))
        assertTrue("提交图取数失败要记 warn", text.contains("Logger.warn("))
    }
}
