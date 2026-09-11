package com.branchbase.ui.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.cache.PreloadStore
import com.branchbase.cache.PrefetchReason
import com.branchbase.cache.RepoPrefetcher
import com.branchbase.cache.SearchCacheDatabase
import com.branchbase.cache.SearchCacheManager
import com.branchbase.core.RustBridge
import com.branchbase.ui.log.Logger
import com.branchbase.ui.profile.CommitModePickerDialog
import com.branchbase.ui.profile.commitMode
import com.branchbase.ui.profile.saveCommitMode
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
    var filePage by remember { mutableStateOf<Pair<String, String?>?>(null) } // (文件路径, 高亮行号)
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

    // 发布编辑页（全屏；releaseEditTarget == null 表示新建）
    if (showReleaseEdit) {
        BackHandler { showReleaseEdit = false }
        ReleaseEditScreen(
            sessionJson = sessionJson,
            owner = owner,
            repo = repo,
            existing = releaseEditTarget,
            defaultBranch = branch ?: "main",
            onBack = { showReleaseEdit = false },
            onSaved = {
                showReleaseEdit = false
                releaseEditTarget = null
                refreshTick++
            },
        )
        return
    }

    // 发布详情页（全屏）
    val currentRelease = releaseDetail
    if (currentRelease != null) {
        BackHandler { releaseDetail = null }
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
        return
    }

    // 分支同步页（全屏）
    if (showBranchSync) {
        BackHandler { showBranchSync = false }
        BranchSyncScreen(
            sessionJson = sessionJson,
            owner = owner,
            repo = repo,
            onBack = { showBranchSync = false },
        )
        return
    }

    // 分支管理页（全屏）
    if (showBranchManage) {
        BackHandler { showBranchManage = false }
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
        return
    }

    // 分支对比页（全屏）：显示两个分支的代码片段差异
    val comparing = comparePair
    if (comparing != null) {
        BackHandler { comparePair = null }
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
        return
    }

    // 本地仓库分支同步页（全屏）
    if (showLocalSync) {
        BackHandler { showLocalSync = false }
        LocalBranchSyncScreen(
            dir = localRepoDir(context, repo),
            repoName = repo,
            token = sessionToken,
            onBack = { showLocalSync = false },
            onChanged = { refreshTick++ },
        )
        return
    }

    // 星标/复刻/关注列表页（全屏，覆盖底部导航）
    if (peoplePage != null) {
        BackHandler { peoplePage = null }
        PeopleListScreen(
            sessionJson = sessionJson,
            owner = owner,
            repo = repo,
            type = peoplePage!!,
            onBack = { peoplePage = null },
        )
        return
    }

    // 文件查看页（全屏）
    if (filePage != null) {
        BackHandler { filePage = null }
        FileViewerScreen(
            sessionJson = sessionJson,
            owner = owner,
            repo = repo,
            path = filePage!!.first,
            highlightLines = filePage!!.second,
            defaultBranch = branch ?: "main",
            branches = branches.map { it.name },
            onOpenBranchManage = { showBranchManage = true },
            onOpenLocalSync = { showLocalSync = true },
            onBack = { filePage = null },
        )
        return
    }

    // Issue 详情页
    if (issuePage != null) {
        BackHandler { issuePage = null }
        IssueDetailScreen(
            sessionJson = sessionJson,
            owner = owner,
            repo = repo,
            number = issuePage!!,
            onBack = { issuePage = null },
        )
        return
    }

    // PR 详情页
    if (pullPage != null) {
        BackHandler { pullPage = null }
        PullDetailScreen(
            sessionJson = sessionJson,
            owner = owner,
            repo = repo,
            number = pullPage!!,
            onBack = { pullPage = null },
        )
        return
    }

    // 提交详情页
    if (commitPage != null) {
        BackHandler { commitPage = null }
        CommitDetailScreen(
            sessionJson = sessionJson,
            owner = owner,
            repo = repo,
            sha = commitPage!!,
            onBack = { commitPage = null },
        )
        return
    }

    // Job 详情（最深）
    if (jobDetailPage != null) {
        BackHandler { jobDetailPage = null }
        JobDetailContent(
            sessionJson = sessionJson,
            owner = owner,
            repo = repo,
            jobId = jobDetailPage!!,
            onBack = { jobDetailPage = null },
        )
        return
    }

    // Run 详情（jobs）
    if (runDetailPage != null) {
        BackHandler { runDetailPage = null }
        RunDetailContent(
            sessionJson = sessionJson,
            owner = owner,
            repo = repo,
            runId = runDetailPage!!,
            onBack = { runDetailPage = null },
            onOpenJob = { jobDetailPage = it },
        )
        return
    }

    // 手动触发工作流（全屏）
    val dispatching = dispatchTarget
    if (dispatching != null) {
        BackHandler { dispatchTarget = null }
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
        return
    }

    // 工作流运行历史
    if (workflowRunsPage != null) {
        BackHandler { workflowRunsPage = null }
        WorkflowRunsContent(
            sessionJson = sessionJson,
            owner = owner,
            repo = repo,
            workflowId = workflowRunsPage!!.first,
            workflowName = workflowRunsPage!!.second,
            branch = branch,
            refreshTick = refreshTick,
            onBack = { workflowRunsPage = null },
            onOpenRun = { runDetailPage = it },
            onOpenActions = { runsWorkflow?.let { workflowAction = it } },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Primer.BackgroundPrimary)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        RepoHeaderRow(
            title = "$owner/$repo",
            onBack = onBack,
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
                if (page in branchPages) {
                    BranchSelector(branch = branch, branches = branches, cached = branchCached, onSelect = { branch = it })
                }
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
private fun RepoHeaderRow(title: String, onBack: () -> Unit, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "返回",
            tint = Primer.IconPrimary,
            modifier = Modifier.size(24.dp).clickable { onBack() },
        )
        Spacer(Modifier.width(8.dp))
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary, maxLines = 1, modifier = Modifier.weight(1f))
        Icon(
            Icons.Filled.Refresh,
            contentDescription = "刷新",
            tint = Primer.IconPrimary,
            modifier = Modifier.size(22.dp).clickable { onRefresh() },
        )
    }
}

/** 需要改分支的页面（显示分支选择器） */
private val branchPages = setOf(
    RepoPage.Overview, RepoPage.Code, RepoPage.Workflows,
    RepoPage.Commits, RepoPage.PullRequests,
)

/** 分支选择器：下拉切换分支，切换后触发对应页面重载（branch 变化会进入各页 LaunchedEffect key） */
@Composable
private fun BranchSelector(
    branch: String?,
    branches: List<BranchItem>,
    cached: Boolean = false,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = branch?.takeIf { it.isNotBlank() } ?: "默认分支"
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().background(Primer.Gray150).clickable { expanded = true }.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.CallSplit, null, tint = Primer.IconSecondary, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary, modifier = Modifier.weight(1f), maxLines = 1)
            if (cached) {
                Text("缓存", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Primer.Green500)
                Spacer(Modifier.width(6.dp))
            }
            Text("⌄", fontSize = 13.sp, color = Primer.TextTertiary)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.fillMaxWidth(0.88f)) {
            if (branches.isEmpty()) {
                DropdownMenuItem(text = { Text("暂无分支", color = Primer.TextTertiary) }, onClick = { expanded = false })
            } else {
                branches.forEach { b ->
                    DropdownMenuItem(
                        text = { Text(b.name, color = if (b.name == branch) Primer.Blue500 else Primer.TextPrimary) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.CallSplit, null, tint = if (b.name == branch) Primer.Blue500 else Primer.IconSecondary, modifier = Modifier.size(16.dp)) },
                        onClick = { expanded = false; onSelect(b.name) },
                    )
                }
            }
        }
    }
}

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
        Icon(icon, null, tint = if (selected) Primer.Blue500 else Primer.IconPrimary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(2.dp))
        Text(
            page.label,
            fontSize = 9.5.sp,
            color = if (selected) Primer.Blue500 else Primer.TextTertiary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}