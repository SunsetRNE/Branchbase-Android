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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Settings
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
import com.branchbase.cache.SearchCacheDatabase
import com.branchbase.cache.SearchCacheManager
import com.branchbase.core.RustBridge
import com.branchbase.ui.log.Logger
import com.branchbase.ui.theme.Primer
import kotlinx.coroutines.launch

/**
 * 仓库详情页容器：顶部返回 + 内容区（分页切换）+ 底部导航（5 项 + ⋮ 气泡）。
 *
 * 对齐 `docs/repository-navigation-wireframe.md`：
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
    var filePage by remember { mutableStateOf<String?>(null) } // 文件查看路径
    var issuePage by remember { mutableStateOf(initial?.issueNumber) }
    var pullPage by remember { mutableStateOf(initial?.pullNumber) }
    var commitPage by remember { mutableStateOf(initial?.commitSha) }
    var workflowRunsPage by remember { mutableStateOf<Pair<Long, String>?>(null) } // (workflowId, name)
    var runDetailPage by remember { mutableStateOf(initial?.runId) }
    var jobDetailPage by remember { mutableStateOf<Long?>(null) }
    var bubbleExpanded by remember { mutableStateOf(false) }
    // 改分支：null = 默认分支；分支列表懒加载；refreshTick 触发强制刷新
    var branch by remember { mutableStateOf<String?>(null) }
    var branches by remember { mutableStateOf<List<BranchItem>>(emptyList()) }
    var branchCached by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 加载分支列表 + 默认分支（进入详情页时）；分支列表走 Room 缓存（命中则跳过远端）
    LaunchedEffect(owner, repo) {
        val (h, t, _) = sessionInfo(sessionJson)
        val cacheManager = SearchCacheManager(SearchCacheDatabase.getInstance(context).searchCacheDao())
        val key = "$owner/$repo"
        // 1. 分支列表：先查缓存，命中则直接展示（缓存命中态），否则拉远端并写缓存
        val cached = cacheManager.get(key, "分支")
        if (cached != null) {
            branchCached = true
            branches = parseBranches(cached)
        } else {
            branchCached = false
            RustBridge.listBranches(h, t, owner, repo)
                ?.takeIf { !it.startsWith("ERROR:") }
                ?.let { json ->
                    branches = parseBranches(json)
                    cacheManager.put(key, "分支", json)
                }
        }
        // 2. 默认分支
        RustBridge.getRepoInfo(h, t, owner, repo)
            ?.takeIf { !it.startsWith("ERROR:") }
            ?.let { parseRepoInfo(it) }
            ?.let { info -> if (branch == null) branch = info.defaultBranch }
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
            path = filePage!!,
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
            onBack = { workflowRunsPage = null },
            onOpenRun = { runDetailPage = it },
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
                            onLinkClick = { dest -> handleLink(dest, context, onOpenRepo) { page = it } },
                            onActionClick = { action -> peoplePage = action },
                        )
                        RepoPage.Code -> RepositoryCodeContent(sessionJson, owner, repo, branch, refreshTick, onOpenFile = { filePage = it })
                        RepoPage.Issues -> IssueListContent(sessionJson, owner, repo, refreshTick, onItemClick = { issuePage = it.number })
                        RepoPage.Workflows -> WorkflowListContent(sessionJson, owner, repo, branch, refreshTick, onItemClick = { workflowRunsPage = it.id to it.name })
                        RepoPage.Releases -> ReleaseListContent(sessionJson, owner, repo, refreshTick)
                        RepoPage.PullRequests -> PullListContent(sessionJson, owner, repo, branch, refreshTick, onItemClick = { pullPage = it.number })
                        RepoPage.Commits -> CommitListContent(sessionJson, owner, repo, branch, refreshTick, onItemClick = { commitPage = it.sha })
                        RepoPage.Settings -> RepositorySettingsContent()
                    }
                }
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

/** README 链接路由（blob/tree/issue/pull/commit 暂映射到列表页，详情页待后续） */
private fun handleLink(dest: Destination, context: Context, onOpenRepo: (String, String) -> Unit, onNavigate: (RepoPage) -> Unit) {
    when (dest.type) {
        "repo" -> dest.owner?.let { o -> dest.repo?.let { r -> onOpenRepo(o, r) } }
        "blob", "tree" -> onNavigate(RepoPage.Code)
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