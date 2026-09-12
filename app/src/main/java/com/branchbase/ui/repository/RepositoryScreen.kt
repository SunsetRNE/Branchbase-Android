package com.branchbase.ui.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.ui.theme.selectionColor
import com.branchbase.cache.PreloadStore
import com.branchbase.cache.PrefetchReason
import com.branchbase.cache.RepoPrefetcher
import com.branchbase.cache.SearchCacheDatabase
import com.branchbase.cache.SearchCacheManager
import com.branchbase.core.RustBridge
import com.branchbase.ui.log.Logger
import com.branchbase.ui.navigation.PageBackHandler
import com.branchbase.ui.navigation.PageLevel
import com.branchbase.ui.navigation.PageSwitcher
import com.branchbase.ui.profile.CommitModePickerDialog
import com.branchbase.ui.profile.commitMode
import com.branchbase.ui.profile.saveCommitMode
import com.branchbase.ui.theme.iconTap
import com.branchbase.ui.theme.Primer
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 仓库详情页容器：顶部返回 + 内容区（分页切换）+ 底部导航（5 项 + ⋮ 气泡）。
 *
 * 底部导航：
 * 底部 5 项（项目页/代码/issue/工作流/发布）+ ⋮ 气泡（拉取请求/提交/设置）。
 * README 链接与星标/复刻/关注按钮的回调在此统一路由。
 */
enum class RepoPage(val label: String) {
    Overview("项目页"), Code("代码"), Issues("issue"), Workflows("工作流"), Releases("发布"),
    PullRequests("拉取请求"), Commits("提交"), Settings("设置"),
}

/**
 * 仓库深链接目标：从通知等外部入口直达仓库的某个子页/详情页。
 * 仅用于初始化 `RepositoryScreen` 的状态（后续用户操作覆盖）。
 */
data class RepoDeepLink(
    val owner: String,
    val repo: String,
    val page: RepoPage? = null,       // 初始 tab（默认 Overview）
    val issueNumber: Long? = null,    // 直达 issue 详情
    val pullNumber: Long? = null,     // 直达 PR 详情
    val commitSha: String? = null,    // 直达提交详情
    val runId: Long? = null,          // 直达 Run 详情
    val path: String? = null,         // 直达文件查看页（代码搜索结果用）
)

@Composable
fun RepositoryScreen(
    sessionJson: String,
    owner: String,
    repo: String,
    onBack: () -> Unit,
    onOpenRepo: (owner: String, repo: String) -> Unit,
    initial: RepoDeepLink? = null,
) {
    val loggedRepo = remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!loggedRepo.value) {
            loggedRepo.value = true
            Logger.ui("进入仓库详情页 $owner/$repo", "Compose")
        }
    }
    var page by remember { mutableStateOf(initial?.page ?: RepoPage.Overview) }
    var peoplePage by remember { mutableStateOf<String?>(null) } // "star"/"fork"/"watch"
    // (文件路径, 高亮行号)；代码搜索结果会带 path 直达文件页
    var filePage by remember { mutableStateOf<Pair<String, String?>?>(initial?.path?.let { it to null }) }
    var issuePage by remember { mutableStateOf(initial?.issueNumber) }
    var pullPage by remember { mutableStateOf(initial?.pullNumber) }
    var commitPage by remember { mutableStateOf(initial?.commitSha) }
    var workflowRunsPage by remember { mutableStateOf<Pair<Long, String>?>(null) } // (workflowId, name)
    // 工作流：当前运行历史所属的完整条目（操作抽屉需要 path/htmlUrl）+ 抽屉/执行页目标
    var runsWorkflow by remember { mutableStateOf<WorkflowItem?>(null) }
    var workflowAction by remember { mutableStateOf<WorkflowItem?>(null) }
    var dispatchTarget by remember { mutableStateOf<WorkflowItem?>(null) }
    var runDetailPage by remember { mutableStateOf(initial?.runId) }
    var jobDetailPage by remember { mutableStateOf<Long?>(null) }
    var showBranchSync by remember { mutableStateOf(false) }
    var bubbleExpanded by remember { mutableStateOf(false) }
    // 分支切换弹窗（原先是一条占满整行的分支横条，现收进顶部栏胶囊）
    var showBranchDialog by remember { mutableStateOf(false) }
    // 改分支：null = 默认分支；分支列表懒加载；refreshTick 触发强制刷新
    var branch by remember { mutableStateOf<String?>(null) }
    var branches by remember { mutableStateOf<List<BranchItem>>(emptyList()) }
    var branchCached by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }
    // 发布（Releases）：详情 / 编辑（null 目标 = 新建）/ 当前用户是否有写权限
    var releaseDetail by remember { mutableStateOf<ReleaseItem?>(null) }
    var releaseEditTarget by remember { mutableStateOf<ReleaseItem?>(null) }
    var showReleaseEdit by remember { mutableStateOf(false) }
    var repoCanPush by remember { mutableStateOf(false) }
    // 分支管理 / 分支对比 / 本地分支同步（全屏页）
    var showBranchManage by remember { mutableStateOf(false) }
    var comparePair by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showLocalSync by remember { mutableStateOf(false) }
    // 提交模式（代码页气泡面板直接切换，不必再进「设置」）
    var showCommitMode by remember { mutableStateOf(false) }
    var modeLabel by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 本地 git 相关动作（分支同步页）需要 token
    val sessionToken = remember(sessionJson) { sessionInfo(sessionJson).second }

    // 提交模式标签随弹窗切换实时刷新
    LaunchedEffect(showCommitMode) {
        modeLabel = commitMode(context)?.label
    }

    /**
     * 进入仓库页：分支列表 + 仓库信息**并行**加载（原来是两个串行请求），
     * 分支列表先直出缓存（含过期）再回源，最后触发项目页/其他 tab 的预加载。
     */
    LaunchedEffect(owner, repo) {
        val (h, t, _) = sessionInfo(sessionJson)
        val cacheManager = SearchCacheManager(SearchCacheDatabase.getInstance(context).searchCacheDao())
        val branchKey = PreloadStore.branchKey(owner, repo)
        val branchType = PreloadStore.TYPE_BRANCH

        // ① 分支列表缓存直出（含过期）——切 tab/返回时分支选择器不再空一下
        cacheManager.getStale(branchKey, branchType)
            ?.let { parseBranches(it) }
            ?.takeIf { it.isNotEmpty() }
            ?.let { branches = it; branchCached = true }

        // ② 并行回源
        coroutineScope {
            val branchJob = async {
                val fresh = cacheManager.get(branchKey, branchType)
                if (fresh != null) {
                    parseBranches(fresh) to true
                } else {
                    RustBridge.listBranches(h, t, owner, repo)
                        ?.takeIf { !it.startsWith("ERROR:") }
                        ?.also { cacheManager.put(branchKey, branchType, it) }
                        ?.let { parseBranches(it) to false }
                }
            }
            val infoJob = async {
                val infoKey = PreloadStore.infoKey(owner, repo)
                val cached = cacheManager.get(infoKey, PreloadStore.TYPE_INFO)
                val json = cached ?: RustBridge.getRepoInfo(h, t, owner, repo)
                    ?.takeIf { !it.startsWith("ERROR:") }
                    ?.also { cacheManager.put(infoKey, PreloadStore.TYPE_INFO, it) }
                json?.let { parseRepoInfo(it) }
            }

            branchJob.await()?.let { (list, fromCache) ->
                if (list.isNotEmpty()) {
                    branches = list
                    branchCached = fromCache
                }
            }
            // 默认分支 + 当前用户写权限（发布页的编辑/删除判定依赖 canPush）
            infoJob.await()?.let { info ->
                if (branch == null) branch = info.defaultBranch
                repoCanPush = info.canPush
            }
        }

        // ③ 预加载：项目页四件套（进入页面必然要看）+ 其他 tab（策略决定是否投机）
        RepoPrefetcher.prefetch(
            context = context,
            reason = PrefetchReason.EnterRepo,
            host = h,
            token = t,
            owner = owner,
            repo = repo,
            branch = branch,
        )
    }

    // 工作流操作抽屉（长按工作流 / 运行历史右上角按钮召唤）。
    // 放在所有全屏页分支之前：运行历史/执行页都是「提前 return」的，放尾部会渲染不到。
    val acting = workflowAction
    if (acting != null) {
        WorkflowActionSheet(
            sessionJson = sessionJson,
            owner = owner,
            repo = repo,
            workflow = acting,
            onDismiss = { workflowAction = null },
            onDispatch = {
                workflowAction = null
                dispatchTarget = acting
            },
            onOpenFile = {
                workflowAction = null
                if (acting.path.isNotBlank()) filePage = acting.path to null
            },
            onOpenBrowser = {
                workflowAction = null
                acting.htmlUrl.takeIf { it.isNotBlank() }?.let { url ->
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                }
            },
        )
    }

    // ── 唯一路由 ──
    // 条件顺序与原先的「提前 return」完全一致（前者优先），语义等价。
    // 路由**把页面数据也带上**（而不是让页面现读状态变量）：退场动画期间状态可能已被清空，
    // AnimatedContent 会把旧路由原样交回，页面才不会在退场途中变成空白或换内容。
    val route: RepoRoute = when {
        showReleaseEdit -> RepoRoute.ReleaseEdit(releaseEditTarget)
        releaseDetail != null -> RepoRoute.ReleaseDetail(releaseDetail!!)
        showBranchSync -> RepoRoute.BranchSync
        showBranchManage -> RepoRoute.BranchManage
        comparePair != null -> RepoRoute.BranchCompare(comparePair!!)
        showLocalSync -> RepoRoute.LocalSync
        peoplePage != null -> RepoRoute.People(peoplePage!!)
        filePage != null -> RepoRoute.File(filePage!!)
        issuePage != null -> RepoRoute.Issue(issuePage!!)
        pullPage != null -> RepoRoute.Pull(pullPage!!)
        commitPage != null -> RepoRoute.Commit(commitPage!!)
        jobDetailPage != null -> RepoRoute.JobDetail(jobDetailPage!!)
        runDetailPage != null -> RepoRoute.RunDetail(runDetailPage!!)
        dispatchTarget != null -> RepoRoute.Dispatch(dispatchTarget!!)
        workflowRunsPage != null -> RepoRoute.WorkflowRuns(workflowRunsPage!!)
        else -> RepoRoute.Tab(page)
    }

    PageSwitcher(state = route, modifier = Modifier.fillMaxSize(), label = "repo-page") { r ->
        when (r) {
            // 发布编辑页（全屏；target == null 表示新建）
            is RepoRoute.ReleaseEdit -> {
                val releaseTarget = r.target
                PageBackHandler { showReleaseEdit = false }
                ReleaseEditScreen(
                    sessionJson = sessionJson,
                    owner = owner,
                    repo = repo,
                    existing = releaseTarget,
                    defaultBranch = branch ?: "main",
                    onBack = { showReleaseEdit = false },
                    onSaved = {
                        showReleaseEdit = false
                        releaseEditTarget = null
                        refreshTick++
                    },
                )
            }

            // 发布详情页（全屏）
            is RepoRoute.ReleaseDetail -> {
                val currentRelease = r.release
                PageBackHandler { releaseDetail = null }
                ReleaseDetailScreen(
                    sessionJson = sessionJson,
                    owner = owner,
                    repo = repo,
                    release = currentRelease,
                    canEdit = repoCanPush,
                    onBack = { releaseDetail = null },
                    onEdit = { releaseEditTarget = currentRelease; showReleaseEdit = true },
                    onDeleted = { releaseDetail = null; refreshTick++ },
                )
            }

            // 分支同步页（全屏）
            RepoRoute.BranchSync -> {
                PageBackHandler { showBranchSync = false }
                BranchSyncScreen(
                    sessionJson = sessionJson,
                    owner = owner,
                    repo = repo,
                    onBack = { showBranchSync = false },
                )
            }

            // 分支管理页（全屏）
            RepoRoute.BranchManage -> {
                PageBackHandler { showBranchManage = false }
                BranchManageScreen(
                    sessionJson = sessionJson,
                    owner = owner,
                    repo = repo,
                    defaultBranch = branch ?: "main",
                    canPush = repoCanPush,
                    onBack = { showBranchManage = false },
                    onOpenCompare = { b, h ->
                        showBranchManage = false
                        comparePair = b to h
                    },
                )
            }

            // 分支对比页（全屏）：显示两个分支的代码片段差异
            is RepoRoute.BranchCompare -> {
                val comparing = r.pair
                PageBackHandler { comparePair = null }
                BranchCompareScreen(
                    sessionJson = sessionJson,
                    owner = owner,
                    repo = repo,
                    initialBase = comparing.first,
                    initialHead = comparing.second,
                    onBack = { comparePair = null },
                    onOpenFile = { p ->
                        comparePair = null
                        filePage = p to null
                    },
                )
            }

            // 本地仓库分支同步页（全屏）
            RepoRoute.LocalSync -> {
                PageBackHandler { showLocalSync = false }
                LocalBranchSyncScreen(
                    dir = localRepoDir(context, repo),
                    repoName = repo,
                    token = sessionToken,
                    onBack = { showLocalSync = false },
                    onChanged = { refreshTick++ },
                )
            }

            // 星标/复刻/关注列表页（全屏，覆盖底部导航）
            is RepoRoute.People -> {
                val people = r.type
                PageBackHandler { peoplePage = null }
                PeopleListScreen(
                    sessionJson = sessionJson,
                    owner = owner,
                    repo = repo,
                    type = people,
                    onBack = { peoplePage = null },
                )
            }

            // 文件查看页（全屏）
            is RepoRoute.File -> {
                val file = r.page
                PageBackHandler { filePage = null }
                FileViewerScreen(
                    sessionJson = sessionJson,
                    owner = owner,
                    repo = repo,
                    path = file.first,
                    highlightLines = file.second,
                    defaultBranch = branch ?: "main",
                    branches = branches.map { it.name },
                    onOpenBranchManage = { showBranchManage = true },
                    onOpenLocalSync = { showLocalSync = true },
                    onBack = { filePage = null },
                )
            }

            // Issue 详情页
            is RepoRoute.Issue -> {
                val issue = r.number
                PageBackHandler { issuePage = null }
                IssueDetailScreen(
                    sessionJson = sessionJson,
                    owner = owner,
                    repo = repo,
                    number = issue,
                    onBack = { issuePage = null },
                )
            }

            // PR 详情页
            is RepoRoute.Pull -> {
                val pull = r.number
                PageBackHandler { pullPage = null }
                PullDetailScreen(
                    sessionJson = sessionJson,
                    owner = owner,
                    repo = repo,
                    number = pull,
                    onBack = { pullPage = null },
                )
            }

            // 提交详情页
            is RepoRoute.Commit -> {
                val commit = r.sha
                PageBackHandler { commitPage = null }
                CommitDetailScreen(
                    sessionJson = sessionJson,
                    owner = owner,
                    repo = repo,
                    sha = commit,
                    onBack = { commitPage = null },
                )
            }

            // Job 详情（最深）
            is RepoRoute.JobDetail -> {
                val job = r.id
                PageBackHandler { jobDetailPage = null }
                JobDetailContent(
                    sessionJson = sessionJson,
                    owner = owner,
                    repo = repo,
                    jobId = job,
                    onBack = { jobDetailPage = null },
                )
            }

            // Run 详情（jobs）
            is RepoRoute.RunDetail -> {
                val run = r.id
                PageBackHandler { runDetailPage = null }
                RunDetailContent(
                    sessionJson = sessionJson,
                    owner = owner,
                    repo = repo,
                    runId = run,
                    onBack = { runDetailPage = null },
                    onOpenJob = { jobDetailPage = it },
                )
            }

            // 手动触发工作流（全屏）
            is RepoRoute.Dispatch -> {
                val dispatching = r.workflow
                PageBackHandler { dispatchTarget = null }
                WorkflowDispatchScreen(
                    sessionJson = sessionJson,
                    owner = owner,
                    repo = repo,
                    workflow = dispatching,
                    defaultRef = branch ?: "main",
                    onBack = { dispatchTarget = null },
                    onDispatched = {
                        dispatchTarget = null
                        // 回到运行历史并刷新，让新触发的记录尽快出现
                        refreshTick++
                    },
                )
            }

            // 工作流运行历史
            is RepoRoute.WorkflowRuns -> {
                val runs = r.pair
                PageBackHandler { workflowRunsPage = null }
                WorkflowRunsContent(
                    sessionJson = sessionJson,
                    owner = owner,
                    repo = repo,
                    workflowId = runs.first,
                    workflowName = runs.second,
                    branch = branch,
                    refreshTick = refreshTick,
                    onBack = { workflowRunsPage = null },
                    onOpenRun = { runDetailPage = it },
                    onOpenActions = { runsWorkflow?.let { workflowAction = it } },
                )
            }

            // ── 基础内容：仓库页骨架（Tab） ──
            is RepoRoute.Tab -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Primer.BackgroundPrimary)
                        .statusBarsPadding()
                        .navigationBarsPadding(),
                ) {
                    RepoHeaderRow(
                        title = "$owner/$repo",
                        branch = branch,
                        showBranch = page in branchPages,
                        onBack = onBack,
                        onOpenBranches = { showBranchDialog = true },
                        onRefresh = {
                            // 强制刷新（bypass cache）：清除分支缓存 + README 缓存，再触发重载
                            scope.launch {
                                val cacheManager = SearchCacheManager(SearchCacheDatabase.getInstance(context).searchCacheDao())
                                cacheManager.delete("$owner/$repo")                 // 分支缓存
                                cacheManager.delete("$owner/$repo@${branch ?: ""}") // README 缓存
                            }
                            refreshTick++
                        },
                    )

                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        Column(Modifier.fillMaxSize()) {
                            // 分支选择器已移到顶部栏（刷新按钮左侧），不再占用一整行
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                when (page) {
                                    RepoPage.Overview -> RepositoryOverviewContent(
                                        sessionJson = sessionJson, owner = owner, repo = repo, branch = branch, refreshTick = refreshTick,
                                        onLinkClick = { dest -> handleLink(dest, context, onOpenRepo, { path, lines -> filePage = path to lines }) { page = it } },
                                        onActionClick = { action -> peoplePage = action },
                                        onOpenBranchSync = { showBranchSync = true },
                                    )
                                    RepoPage.Code -> RepositoryCodeContent(sessionJson, owner, repo, branch, refreshTick, onOpenFile = { filePage = it to null })
                                    RepoPage.Issues -> IssueListContent(sessionJson, owner, repo, refreshTick, onItemClick = { issuePage = it.number })
                                    RepoPage.Workflows -> WorkflowListContent(
                                        sessionJson, owner, repo, branch, refreshTick,
                                        onItemClick = { runsWorkflow = it; workflowRunsPage = it.id to it.name },
                                        onLongPress = { workflowAction = it },
                                    )
                                    RepoPage.Releases -> ReleaseListContent(
                                        sessionJson = sessionJson, owner = owner, repo = repo, refreshTick = refreshTick,
                                        onOpenDetail = { releaseDetail = it },
                                        onCreate = { releaseEditTarget = null; showReleaseEdit = true },
                                    )
                                    RepoPage.PullRequests -> PullListContent(sessionJson, owner, repo, branch, refreshTick, onItemClick = { pullPage = it.number })
                                    RepoPage.Commits -> CommitListContent(sessionJson, owner, repo, branch, refreshTick, onItemClick = { commitPage = it.sha })
                                    RepoPage.Settings -> RepositorySettingsContent(
                                        sessionJson = sessionJson,
                                        owner = owner,
                                        repo = repo,
                                        branches = branches.map { it.name },
                                        defaultBranch = branch ?: "main",
                                    )
                                }
                            }
                        }

                        // 代码页 Git 气泡面板（覆盖层）：分支管理 / 对比 / 提交模式 / 本地同步 / 刷新
                        if (page == RepoPage.Code) {
                            CodePageGitPanel(
                                repo = repo,
                                branches = branches.map { it.name },
                                defaultBranch = branch ?: branches.firstOrNull()?.name ?: "main",
                                refreshTick = refreshTick,
                                modeLabel = modeLabel,
                                onPickMode = { showCommitMode = true },
                                onOpenBranchManage = { showBranchManage = true },
                                onOpenCompare = { b, h -> comparePair = b to h },
                                onOpenLocalSync = { showLocalSync = true },
                                onRefresh = { refreshTick++ },
                            )
                        }
                    }

                    RepoBottomBar(
                        selected = page,
                        onSelect = {
                            page = it
                            Logger.ui("切换到「${it.label}」", "Compose")
                        },
                        bubbleExpanded = bubbleExpanded,
                        onBubbleToggle = {
                            bubbleExpanded = it
                            Logger.ui(if (it) "展开 ⋮ 气泡菜单" else "关闭 ⋮ 气泡菜单", "Compose")
                        },
                        onBubbleItem = {
                            bubbleExpanded = false
                            page = it
                            Logger.ui("打开「${it.label}」", "Compose")
                        },
                    )
                }

            }
        }
    }

    // 分支切换弹窗（顶部栏分支胶囊触发）
    if (showBranchDialog) {
        BranchSwitchDialog(
            branches = branches,
            current = branch,
            cached = branchCached,
            onDismiss = { showBranchDialog = false },
            onSelect = { name ->
                branch = name
                showBranchDialog = false
                Logger.ui("切换分支为 $name", "Compose")
            },
        )
    }

    // 提交模式选择（代码页气泡面板直接切换，无需再进「设置」）
    if (showCommitMode) {
        CommitModePickerDialog(
            onDismiss = { showCommitMode = false },
            onConfirm = { mode ->
                saveCommitMode(context, mode)
                modeLabel = mode.label
                showCommitMode = false
                Logger.ui("提交模式改为 ${mode.label}", "Compose")
            },
        )
    }
}

/**
 * 仓库页路由（唯一页面状态的真源）。
 *
 * 原来是 15 个「`if (状态 != null) { 页面(); return }`」依次判断：谁先满足谁显示，
 * 状态一变整棵树直接换掉、**没有任何过渡**。现在压成一条 `when` 得到唯一路由，
 * 交给 [PageSwitcher] 按 [depth] 做位移动画（进子页从右滑入、返回向右滑出）。
 *
 * 层级决定方向：
 * - `0` Tab 骨架；`1` 从任一 Tab 直接打开的详情（Issue / PR / 提交 / 文件 / 列表…）；
 * - `2` 由 1 再深入一层（发布详情 → 发布编辑、运行历史 → Run 详情/手动触发）；
 * - `3` 最深一层（Run 详情 → Job 详情）。
 *
 * 路由**携带页面数据**：退场动画期间旧状态可能已被清空（例如 `releaseDetail = null`），
 * 由 `AnimatedContent` 把旧路由原样交回，页面才不会在退场途中变成空白或换了内容。
 */
private sealed interface RepoRoute : PageLevel {

    /** Tab 骨架（基础内容）。 */
    data class Tab(val page: RepoPage) : RepoRoute {
        override val depth: Int get() = 0
    }

    // ── 第 1 层：Tab 直接打开的全屏页 ──
    data object BranchSync : RepoRoute {
        override val depth: Int get() = 1
    }

    data object BranchManage : RepoRoute {
        override val depth: Int get() = 1
    }

    data object LocalSync : RepoRoute {
        override val depth: Int get() = 1
    }

    data class BranchCompare(val pair: Pair<String, String>) : RepoRoute {
        override val depth: Int get() = 1
    }

    data class ReleaseDetail(val release: ReleaseItem) : RepoRoute {
        override val depth: Int get() = 1
    }

    data class People(val type: String) : RepoRoute {
        override val depth: Int get() = 1
    }

    data class File(val page: Pair<String, String?>) : RepoRoute {
        override val depth: Int get() = 1
    }

    data class Issue(val number: Long) : RepoRoute {
        override val depth: Int get() = 1
    }

    data class Pull(val number: Long) : RepoRoute {
        override val depth: Int get() = 1
    }

    data class Commit(val sha: String) : RepoRoute {
        override val depth: Int get() = 1
    }

    data class WorkflowRuns(val pair: Pair<Long, String>) : RepoRoute {
        override val depth: Int get() = 1
    }

    // ── 第 2 层：由上一层再深入 ──
    data class ReleaseEdit(val target: ReleaseItem?) : RepoRoute {
        override val depth: Int get() = 2
    }

    data class Dispatch(val workflow: WorkflowItem) : RepoRoute {
        override val depth: Int get() = 2
    }

    data class RunDetail(val id: Long) : RepoRoute {
        override val depth: Int get() = 2
    }

    // ── 第 3 层：Run 详情 → Job 详情 ──
    data class JobDetail(val id: Long) : RepoRoute {
        override val depth: Int get() = 3
    }
}

/**
 * 代码页 Git 气泡面板。
 *
 * 把原本只在「设置 → 本地仓库」里才有的入口挂到代码页：
 * 提交模式（就地切换）、分支管理、分支对比、本地分支同步、刷新。
 * 徽标显示当前本地仓库的待推送/待拉取/改动数，一眼看出是否需要同步。
 */
@Composable
private fun CodePageGitPanel(
    repo: String,
    branches: List<String>,
    defaultBranch: String,
    refreshTick: Int,
    modeLabel: String?,
    onPickMode: () -> Unit,
    onOpenBranchManage: () -> Unit,
    onOpenCompare: (String, String) -> Unit,
    onOpenLocalSync: () -> Unit,
    onRefresh: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val localGit = rememberLocalRepoGitState(repo, refreshTick)
    val otherBranch = branches.firstOrNull { it != defaultBranch }

    val actions = listOf(
        GitBubbleAction(
            key = "mode",
            label = "提交模式：${modeLabel ?: "未设置"}",
            icon = Icons.Filled.Settings,
            onClick = onPickMode,
        ),
        GitBubbleAction(
            key = "branch",
            label = "分支管理",
            icon = Icons.Filled.AccountTree,
            badge = branches.size.takeIf { it > 0 }?.toString(),
            onClick = onOpenBranchManage,
        ),
        GitBubbleAction(
            key = "compare",
            label = "对比分支",
            icon = Icons.Filled.CompareArrows,
            enabled = otherBranch != null,
            onClick = { otherBranch?.let { onOpenCompare(defaultBranch, it) } },
        ),
        GitBubbleAction(
            key = "sync",
            label = when {
                !localGit.exists -> "本地仓库未拉取"
                localGit.needsPush -> "推送本地分支"
                localGit.needsPull -> "拉取远端分支"
                else -> "本地分支同步"
            },
            icon = Icons.Filled.Sync,
            badge = when {
                localGit.diverged -> "分叉"
                localGit.ahead > 0 -> "↑${localGit.ahead}"
                localGit.behind > 0 -> "↓${localGit.behind}"
                else -> null
            },
            enabled = localGit.exists,
            onClick = onOpenLocalSync,
        ),
        GitBubbleAction(
            key = "refresh",
            label = "刷新",
            icon = Icons.Filled.Refresh,
            onClick = onRefresh,
        ),
    )

    GitBubblePanel(
        actions = actions,
        expanded = expanded,
        onExpandedChange = { expanded = it },
        title = localGit.summary(),
        handleBadge = when {
            localGit.diverged -> "!"
            localGit.dirtyCount > 0 -> localGit.dirtyCount.toString()
            localGit.ahead > 0 -> localGit.ahead.toString()
            else -> null
        },
    )
}

/** README 链接路由（blob 直接打开文件 + 行号高亮；tree 进代码页；issue/pull/commit 映射到列表页） */
private fun handleLink(
    dest: Destination,
    context: Context,
    onOpenRepo: (String, String) -> Unit,
    onOpenFile: (String, String?) -> Unit,
    onNavigate: (RepoPage) -> Unit,
) {
    when (dest.type) {
        "repo" -> dest.owner?.let { o -> dest.repo?.let { r -> onOpenRepo(o, r) } }
        // blob → 打开对应文件（带行号高亮）；无 path 则回退到代码页
        "blob" -> dest.path?.let { onOpenFile(it, dest.lines) } ?: onNavigate(RepoPage.Code)
        "tree" -> onNavigate(RepoPage.Code)
        "issue" -> onNavigate(RepoPage.Issues)
        "pull" -> onNavigate(RepoPage.PullRequests)
        "commit" -> onNavigate(RepoPage.Commits)
        "external" -> dest.url.takeIf { it.isNotBlank() }?.let { url ->
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }
        else -> { /* anchor/user/raw 待扩展 */ }
    }
}

// ── 顶部导航 ──

@Composable
private fun RepoHeaderRow(
    title: String,
    branch: String?,
    showBranch: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenBranches: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "返回",
            tint = Primer.IconPrimary,
            modifier = Modifier.size(24.dp).iconTap { onBack() },
        )
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = Primer.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (showBranch) {
            BranchChip(branch = branch, onClick = onOpenBranches)
            Spacer(Modifier.width(2.dp))
        }
        Icon(
            Icons.Filled.Refresh,
            contentDescription = "刷新",
            tint = Primer.IconPrimary,
            modifier = Modifier.size(22.dp).iconTap { onRefresh() },
        )
    }
}

/**
 * 当前分支胶囊（顶部栏，刷新按钮左侧）。
 *
 * 取代了原先占满整行的灰色横条：横条把内容整体下压一行、下拉菜单与横条同宽，
 * 分支名长了会截断、分支多了只能滚。现在入口是一枚可点胶囊，点开走 [BranchSwitchDialog]。
 * 名称限宽 96dp（超出省略），避免长分支名把刷新按钮挤出去。
 */
@Composable
private fun BranchChip(branch: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Primer.Gray150)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.CallSplit,
            contentDescription = null,
            tint = Primer.IconSecondary,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            branch?.takeIf { it.isNotBlank() } ?: "默认分支",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Primer.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 96.dp),
        )
        Spacer(Modifier.width(3.dp))
        Text("\u2304", fontSize = 11.sp, color = Primer.TextTertiary)
    }
}

/**
 * 分支切换弹窗。
 *
 * 三个此前缺失的能力：
 * 1. **可搜索** —— 分支动辄上百个，长列表靠滚不现实；
 * 2. **可滚动** —— 弹窗内固定最大高度，不吃满整屏；
 * 3. **状态可见** —— 当前分支打勾、受保护分支有标记、列表来自缓存时在标题里说明。
 */
@Composable
private fun BranchSwitchDialog(
    branches: List<BranchItem>,
    current: String?,
    cached: Boolean,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val keyword = query.trim()
    val filtered = remember(branches, keyword) {
        if (keyword.isEmpty()) branches else branches.filter { it.name.contains(keyword, ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("切换分支", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary)
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        append("共 ${branches.size} 个分支")
                        if (cached) append(" · 列表来自本地缓存")
                    },
                    fontSize = 11.5.sp,
                    color = Primer.TextTertiary,
                )
            }
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Primer.Gray100)
                        .border(1.dp, Primer.Gray200, RoundedCornerShape(8.dp))
                        .padding(horizontal = 9.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = Primer.TextTertiary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(7.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            Text("搜索分支", fontSize = 12.5.sp, color = Primer.TextTertiary)
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 12.5.sp, color = Primer.TextPrimary),
                            cursorBrush = SolidColor(Primer.Blue500),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                when {
                    branches.isEmpty() -> Text(
                        "分支列表尚未加载完成：稍等片刻，或关闭弹窗后在顶部栏点刷新。",
                        fontSize = 12.5.sp,
                        color = Primer.TextTertiary,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                    filtered.isEmpty() -> Text(
                        "没有匹配「$keyword」的分支",
                        fontSize = 12.5.sp,
                        color = Primer.TextTertiary,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                        items(filtered, key = { it.name }) { b ->
                            BranchRow(
                                item = b,
                                selected = b.name == current,
                                onClick = { onSelect(b.name) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭", color = Primer.Blue500) }
        },
    )
}

/** 弹窗里的一行分支：图标 + 名称 +（受保护标记）+ 当前分支勾选 */
@Composable
private fun BranchRow(item: BranchItem, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.CallSplit,
            contentDescription = null,
            tint = if (selected) Primer.Blue500 else Primer.IconSecondary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            item.name,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) Primer.Blue500 else Primer.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (item.protected) {
            Text(
                "受保护",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = Primer.TextSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Primer.Gray150)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = "当前分支", tint = Primer.Blue500, modifier = Modifier.size(16.dp))
        }
    }
}

private val branchPages = setOf(
    RepoPage.Overview, RepoPage.Code, RepoPage.Workflows,
    RepoPage.Commits, RepoPage.PullRequests,
)

// ── 底部导航（5 项 + ⋮ 气泡） ──

private val bottomTabs = listOf(
    RepoPage.Overview to Icons.Filled.Info,
    RepoPage.Code to Icons.Filled.Code,
    RepoPage.Issues to Icons.Filled.ErrorOutline,
    RepoPage.Workflows to Icons.Filled.PlayArrow,
    RepoPage.Releases to Icons.Filled.Sell,
)

private val bubbleItems = listOf(
    RepoPage.PullRequests to Icons.AutoMirrored.Filled.CallSplit,
    RepoPage.Commits to Icons.Filled.History,
    RepoPage.Settings to Icons.Filled.Settings,
)

@Composable
private fun RepoBottomBar(
    selected: RepoPage,
    onSelect: (RepoPage) -> Unit,
    bubbleExpanded: Boolean,
    onBubbleToggle: (Boolean) -> Unit,
    onBubbleItem: (RepoPage) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(Primer.BackgroundSecondary),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        bottomTabs.forEach { (p, icon) ->
            BottomTab(p, icon, p == selected, onSelect)
        }
        // ⋮ 手柄 + 气泡
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = "更多",
                tint = if (bubbleItems.any { it.first == selected }) Primer.Blue500 else Primer.IconPrimary,
                modifier = Modifier
                    .size(46.dp)
                    .clickable { onBubbleToggle(!bubbleExpanded) }
                    .padding(10.dp),
            )
            DropdownMenu(expanded = bubbleExpanded, onDismissRequest = { onBubbleToggle(false) }) {
                bubbleItems.forEach { (p, icon) ->
                    DropdownMenuItem(
                        text = { Text(p.label) },
                        leadingIcon = { Icon(icon, null, tint = Primer.IconSecondary, modifier = Modifier.size(18.dp)) },
                        onClick = { onBubbleItem(p) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.BottomTab(page: RepoPage, icon: ImageVector, selected: Boolean, onSelect: (RepoPage) -> Unit) {
    Column(
        modifier = Modifier
            .weight(1f)
            .clickable { onSelect(page) }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 选中态用 180ms 渐变而不是硬切：底部 Tab 是最高频的点击目标
        val tint = selectionColor(selected, on = Primer.Blue500, off = Primer.IconPrimary)
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(2.dp))
        Text(
            page.label,
            fontSize = 9.5.sp,
            color = selectionColor(selected, on = Primer.Blue500, off = Primer.TextTertiary),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}