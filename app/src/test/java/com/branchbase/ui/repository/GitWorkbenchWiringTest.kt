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
 *    挡不住「只在一个入口打了」）；
 * 4. **长任务不许被面板吞掉**：加深（1.0.98）是分钟级的全史下载，必须走任务中心 + 进度弹窗，
 *    而且两个宿主都得接上出口 —— 写错的样子是「点了没反应」或「任务中心里什么都没有」，
 *    两种都只有真机上点一次才看得出来。
 */
class GitWorkbenchWiringTest {

    private fun source(path: String): String {
        val file = File(path)
        assertTrue("找不到源文件：${file.absolutePath}（单测工作目录应为 app 模块根）", file.exists())
        return file.readText()
    }

    /** 面板这一族的源码（含状态模型与两个数据档）。**只给出口、不自己取数**。 */
    private val panelSources = listOf(
        "src/main/java/com/branchbase/ui/repository/GitBubblePanel.kt",
        "src/main/java/com/branchbase/ui/repository/GitPanelStage.kt",
        "src/main/java/com/branchbase/ui/repository/GitWorkspacePanel.kt",
        "src/main/java/com/branchbase/ui/repository/GitRefsPanel.kt",
        "src/main/java/com/branchbase/ui/repository/GitRefsModels.kt",
        "src/main/java/com/branchbase/ui/repository/CommitGraphPanel.kt",
        "src/main/java/com/branchbase/ui/repository/GitFileHistoryPanel.kt",
        "src/main/java/com/branchbase/ui/repository/FileHistoryModels.kt",
    )

    /**
     * 与面板同族、但**允许自己取数**的只读页面/模型（本地 diff）。
     *
     * 它们和面板一起接受「零 git 写操作」的扫描（都是只读界面），但不接受
     * 「不许自己取 diff」那条 —— 取 diff 正是那个页面存在的理由。
     */
    private val readOnlyPageSources = listOf(
        "src/main/java/com/branchbase/ui/repository/LocalDiffModels.kt",
        "src/main/java/com/branchbase/ui/repository/LocalDiffScreen.kt",
    )

    @Test
    fun `面板里不许出现任何 git 写操作`() {
        // 写操作全表（与 RustBridge 的公开方法同名）：出现任何一个都说明有人把「有后果的动作」
        // 搬进了浮层，而不是让它走决策页 / 既有管理页
        val writes = listOf(
            "gitCommit", "gitPushDetailed", "gitPushSetUpstream", "gitPullDetailed", "gitCloneDetailed",
            "gitResetSoft", "gitResetHardRemote", "gitAmend", "gitRevert", "gitFetchRemote",
            "checkoutBranch", "createBranchLocal", "deleteBranchLocal", "discardAllChanges",
            // 加深也是「落到仓库上的一次网络写」：面板只给出口（onDeepen），跑它的是宿主
            "gitFetchDeepen",
        )
        val offenders = (panelSources + readOnlyPageSources).flatMap { path ->
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
        // 提交图是双来源取数（本地 / REST），面板上只显示「N 条」或一句「加载失败」——
        // 事后定位「是限额、是网络、是分支名不对，还是本地库读不出来」只能靠日志。
        // 所以这里连**来源**一起钉：只记条数的话，两条来源在日志里长得一模一样
        val text = source("src/main/java/com/branchbase/ui/repository/CommitGraphPanel.kt")
        assertTrue("提交图取数成功要记一条（带条数与来源）", text.contains("提交图 ▸") && text.contains("Logger.net("))
        assertTrue("提交图取数失败要记 warn", text.contains("Logger.warn("))
        assertTrue("本地读不出来要留一条（否则事后只看到『来源=本地』）", text.contains("退回 REST"))
    }

    @Test
    fun `加深历史的出口两个宿主都要接，且面板自己不许调引擎`() {
        // 与 onOpenBranches 同一条口径：出口回调是各宿主各传各的，只接一边的表现是
        // 「代码页的面板能加深、文件页的不能」——用户报过同类不一致（Git 球门控那次）。
        // 真正的长任务落在宿主（LocalRepoDeepen.kt 的运行器），面板这一族源码里
        // 一个 git 写方法都不许出现（上面那条测试扫的就是它）
        val hosts = listOf(
            "src/main/java/com/branchbase/ui/repository/RepositoryScreen.kt" to "代码页",
            "src/main/java/com/branchbase/ui/repository/RepositoryFileViewer.kt" to "文件页",
        )
        val missing = hosts.filter { (path, _) -> !source(path).contains("onDeepen =") }
        assertEquals("这两个宿主都必须给面板接上「加深历史」出口：$missing", emptyList<Pair<String, String>>(), missing)

        val offenders = panelSources.filter { source(it).contains("gitFetchDeepen(") }
        assertEquals(
            "面板这一族只给出口、不自己跑加深（长任务要落任务中心 + 进度弹窗，见 §10）：$offenders",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `diff 出口两个宿主都要接`() {
        // 与 onOpenBranches / onDeepen 同一条口径：出口回调各宿主各传各的，只接一边的表现是
        // 「代码页的改动清单点得开、文件页点不开」（用户报过同类不一致：Git 球门控那次）。
        // 两个入口都要接：工作区档的一行（diff_worktree）与提交图的一行（diff_commit）
        val hosts = listOf(
            "src/main/java/com/branchbase/ui/repository/RepositoryScreen.kt" to "代码页",
            "src/main/java/com/branchbase/ui/repository/RepositoryFileViewer.kt" to "文件页",
        )
        val missingWorktree = hosts.filter { (path, _) -> !source(path).contains("onOpenDiff =") }
        assertEquals(
            "这两个宿主都必须给面板接上「看改动文件的本地 diff」出口：$missingWorktree",
            emptyList<Pair<String, String>>(),
            missingWorktree,
        )
        val missingCommit = hosts.filter { (path, _) -> !source(path).contains("onOpenCommitDiff =") }
        assertEquals(
            "这两个宿主都必须给面板接上「看某条提交的本地 diff」出口：$missingCommit",
            emptyList<Pair<String, String>>(),
            missingCommit,
        )

        // 面板这一族仍然不许自己调引擎：diff 是**只读**动作，但它同样属于宿主的出口体系
        val offenders = panelSources.filter {
            val text = source(it)
            text.contains("gitDiffWorktree(") || text.contains("gitDiffCommit(")
        }
        assertEquals("面板只给出口，不自己取 diff（两个宿主各接一次）：$offenders", emptyList<String>(), offenders)

        // 反过来：取数的**就是**那个页面 —— 别哪天把这一句「优化」掉了，页面会变成一张空表
        val screen = source("src/main/java/com/branchbase/ui/repository/LocalDiffScreen.kt")
        assertTrue(
            "本地 diff 页必须自己按入口取数（工作区 / 某个提交）",
            screen.contains("RustBridge.gitDiffWorktree(") && screen.contains("RustBridge.gitDiffCommit("),
        )
    }

    @Test
    fun `文件历史档：文件页必须把当前文件传进去`() {
        // 这一档要一个文件路径。文件页有（正在看的那个），代码页没有（如实说明去哪看）。
        // 漏传的表现是「在文件页打开文件历史，它却说请去文件里看」——而这一页就是那个文件页，
        // 只有真机上点一次才看得出来，所以钉在源码上
        val viewer = source("src/main/java/com/branchbase/ui/repository/RepositoryFileViewer.kt")
        assertTrue(
            "文件页必须把正在看的文件路径传给面板（否则文件历史档退化成一句「去文件里看」）",
            viewer.contains("filePath = path,"),
        )
        val host = source("src/main/java/com/branchbase/ui/repository/GitWorkspacePanel.kt")
        assertTrue(
            "宿主必须真的渲染文件历史档（available=true 与渲染是同一件事，见 GitPanelStageTest）",
            host.contains("GitPanelKind.FileHistory -> GitFileHistoryPanel("),
        )
        val panel = source("src/main/java/com/branchbase/ui/repository/GitFileHistoryPanel.kt")
        assertTrue("文件历史取数成功要留痕（带来源）", panel.contains("文件历史 ▸") && panel.contains("Logger.net("))
        assertTrue("取数失败要留一条 warn", panel.contains("Logger.warn("))
        assertTrue("本地失败要退回 REST 并留痕", panel.contains("退回 REST"))
    }

    @Test
    fun `加深跑的是任务中心那一套进度`() {
        // §10：长任务（加深 / 合并 / push）一律走 TaskStore + 进度弹窗，不许出现第二种「转圈」。
        // 写错的表现是「加深在跑，任务中心里什么都没有」——事后完全无从判断它跑过没有
        val runner = source("src/main/java/com/branchbase/ui/repository/LocalRepoDeepen.kt")
        assertTrue("加深要落一条任务记录", runner.contains("TaskStore.start("))
        assertTrue("加深要单列一种 kind（借 PULL 的话事后说不清那条记录是什么）", runner.contains("TaskKind.DEEPEN"))
        assertTrue("进度从引擎快照里轮询", runner.contains("RustBridge.gitCloneProgress()"))
        assertTrue("取消走同一条引擎取消", runner.contains("RustBridge.gitCloneCancel()"))
        assertTrue("失败要停在弹窗里给原因（不许只闪一条反馈）", runner.contains("CloneProgressDialog("))
    }
}
