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
 *    两种都只有真机上点一次才看得出来；
 * 5. **「引擎标了、解析认了、渲染没了」要能被抓住**：未推送段（1.0.101）是三段接力 ——
 *    引擎逐条标 `unpushed`、解析器认得它、面板把它画出来。前两段各有单测，第三段没有编译期约束，
 *    少了那一句 `if` 的表现是「工作区档写着待推送 3，图上一条标记都没有」（两头全绿）。
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
    fun `合并：面板只给出口，动作全在宿主的运行器里`() {
        // 合并是阶段 5 的「有后果的动作」：面板里那三枚出口（合并分支… / 继续 / 放弃）
        // 都不许自己调引擎 —— 真跑动作的是宿主（`MergeFlow.kt` 的 runMerge / runMergeAbort）。
        // 这条钉的是「面板里零 git 写操作」这条既有口径在新功能上同样成立
        val writes = listOf(
            "RustBridge.gitMerge(", "RustBridge.gitMergeAbort(", "RustBridge.gitMergeContinue(",
            "RustBridge.gitResolveConflict(", "RustBridge.gitWriteResolved(",
        )
        val offenders = panelSources.flatMap { path ->
            val text = source(path)
            writes.filter { text.contains(it) }.map { "$path → $it" }
        }
        assertEquals(
            "合并的动作只能在宿主侧的 MergeFlow / 两个全屏页里跑：$offenders",
            emptyList<String>(),
            offenders,
        )
        // 反过来：运行器必须真的调引擎 —— 否则上面那条会因为「谁都不调」而永远绿
        val runner = source("src/main/java/com/branchbase/ui/repository/MergeFlow.kt")
        assertTrue("合并运行器要调 gitMerge", runner.contains("RustBridge.gitMerge("))
        assertTrue("放弃合并要调 gitMergeAbort", runner.contains("RustBridge.gitMergeAbort("))
    }

    @Test
    fun `合并：两个宿主都要接三个出口，且弹窗渲染在外层`() {
        // 出口回调各宿主各传各的（与 onOpenBranches / onDeepen / onOpenDiff 同一条口径）：
        // 只接一边的表现是「代码页的面板能合并、文件页的不能」
        val hosts = listOf(
            "src/main/java/com/branchbase/ui/repository/RepositoryScreen.kt" to "代码页",
            "src/main/java/com/branchbase/ui/repository/RepositoryFileViewer.kt" to "文件页",
        )
        for (outlet in listOf("onMerge =", "onResumeMerge =", "onAbortMerge =")) {
            val missing = hosts.filter { (path, _) -> !source(path).contains(outlet) }
            assertEquals("这两个宿主都必须给面板接上 $outlet 出口：$missing", emptyList<Pair<String, String>>(), missing)
        }
        // 冲突弹窗必须在**外层**渲染：合并从全屏决策页发起，那一屏在的时候面板并没有被组合 ——
        // 放进面板里的话，弹窗会在最需要它的那一刻不出现。判据取「宿主自己的 mergeFlow 驱动它」：
        // 面板内部状态驱动不了它，因为那时面板根本不存在
        for ((path, name) in hosts) {
            val text = source(path)
            assertTrue("$name 必须渲染冲突弹窗", text.contains("MergeConflictDialog("))
            assertTrue("$name 的弹窗要由宿主持有的 mergeFlow 驱动", text.contains("mergeFlow.conflict?.let"))
            // 跳到详情页：代码页是 `RepoRoute.MergeConflict ->`，文件页是 `FilePage.MergeConflict` ——
            // 两边各用自己的路由类型，所以这里只钉「那个名字出现过」
            assertTrue("$name 的弹窗要能跳到冲突详情页", text.contains("MergeConflict"))
        }
    }

    @Test
    fun `合并：预解析的触发点是弹窗出现那一刻`() {
        // D-h 的契约：`MergePreparse.start` 必须在「合并返回冲突」的那一刻调（运行器里），
        // 而不是在详情页打开时调。写反的表现是「点开详情页先看一段骨架」——
        // 而那正是「页面打开后再从头算」这条被点名不许的做法
        val runner = source("src/main/java/com/branchbase/ui/repository/MergeFlow.kt")
        assertTrue("冲突那一刻要启动预解析", runner.contains("MergePreparse.start("))
        val page = source("src/main/java/com/branchbase/ui/repository/MergeConflictScreen.kt")
        assertTrue("详情页只读缓存", page.contains("MergePreparse.stateOf("))
        // 页面里唯一允许的一次 start 是「失败可重试」那条（force = true）：
        // 进入页面时从头算一遍正是 D-h 点名不许的两条之一
        val starts = Regex("""MergePreparse\.start\(""").findAll(page).count()
        assertEquals("详情页只留『重新预解析』那一次 start：$starts", 1, starts)
        assertTrue("那一次必须是 force 重试（否则会撞上『已经有结果就不重复启动』）", page.contains("force = true"))
    }

    @Test
    fun `未推送段：面板必须真的画出来，不是只解析`() {
        // 这一族最典型的静默失效：引擎标了（cargo 有钉子）、解析器认了（单测有）、
        // 中间那一句渲染没了 —— 两头都绿，表现却是「工作区档写着待推送 3，图上一条标记都没有」。
        // 所以对着面板源码钉一句：字段要真被用上，而且只有本地那份解析器认它
        val panel = source("src/main/java/com/branchbase/ui/repository/CommitGraphPanel.kt")
        assertTrue("提交图必须按 unpushed 画行尾标记", panel.contains("row.commit.unpushed"))
        assertTrue("脚注要数已加载的这一屏（全量那个数在工作区档）", panel.contains("unpushedCount("))
        assertTrue(
            "本地来源必须走 parseLocalGraphCommits —— REST 那份解析器不认 unpushed",
            panel.contains("::parseLocalGraphCommits"),
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

    @Test
    fun `阶段 5 的两条入口都要接上，且动作仍只在运行器里跑`() {
        // 入口 ①：分叉页的第三条「合并远端」（设置 → 本地仓库那条路）。
        // 它是**第三个宿主**：合并从这里发起时，冲突弹窗与冲突详情页也得在这条路上走得通 ——
        // 只接「合并」不接冲突页的表现是「点了合并、弹出冲突，然后无处可去」
        val settings = source("src/main/java/com/branchbase/ui/profile/SubPageScreens.kt")
        assertTrue("分叉页要接上「合并远端」出口", settings.contains("onMergeRemote ="))
        assertTrue("这条路的合并也要跑同一个运行器（不许自己调引擎）", settings.contains("runMerge("))
        assertTrue("冲突弹窗要在这个宿主里渲染", settings.contains("MergeConflictDialog("))
        // 弹窗必须在**子页状态机之外**：合并从分叉页发起，但用户可能在结果回来前就返回了列表 ——
        // 放进分叉页那个分支里的话，那一刻它不会被组合，而仓库已经停在合并中
        val dispatch = settings.indexOf("when (val p = page)")
        val dialog = settings.indexOf("MergeConflictDialog(")
        assertTrue("冲突弹窗要渲染在子页状态机之前（否则返回列表后它不出现）", dialog in 1..<dispatch)
        assertTrue("冲突详情页要在子页状态机里有落点", settings.contains("MergeConflict("))
        assertTrue("弹窗跳转要用发起合并时的仓库名（那一刻 page 可能已变）", settings.contains("mergeRepo"))
        assertTrue("合并中/取数后要能刷新列表与分叉页事实", settings.contains("mergeReloadKey"))

        // 入口 ②：PR 详情页的「拉到本地解决」→ 合并决策页 + **预选** PR 的 head。
        // 少了预选的表现是「打开了合并页，却要用户自己在几十个分支里找那个 PR 的分支」
        val detail = source("src/main/java/com/branchbase/ui/repository/RepositoryDetailScreens.kt")
        assertTrue("PR 详情页要露出这条入口", detail.contains("LocalResolveEntry("))
        assertTrue("入口只在不可自动合并时露出（判据是纯函数，单测钉着）", detail.contains("pullLocalResolveEntry("))
        assertTrue("这一页只给出口、不自己跑合并", !detail.contains("RustBridge.gitMerge("))
        val repo = source("src/main/java/com/branchbase/ui/repository/RepositoryScreen.kt")
        assertTrue("仓库页要把 PR 的 head 交给合并页", repo.contains("mergePreselect = head"))
        assertTrue("合并决策页要吃到预选", repo.contains("initialPick = mergePreselect"))
        assertTrue("从面板进来的那条路要清掉预选（否则会选着上一个 PR 的分支）", repo.contains("mergePreselect = null"))
        val page = source("src/main/java/com/branchbase/ui/repository/MergeDecisionScreen.kt")
        assertTrue("合并页要支持预选参数", page.contains("initialPick: String?"))
        assertTrue("预选的分支不在清单里时要补一条（引擎会自己 fetch）", page.contains("preferred"))
    }
}
