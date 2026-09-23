package com.branchbase.ui.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.runtime.CompositionLocalProvider
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
import org.json.JSONObject
import androidx.compose.ui.unit.sp
import com.branchbase.ui.theme.selectionColor
import com.branchbase.cache.PreloadStore
import com.branchbase.cache.PrefetchReason
import com.branchbase.cache.RepoPrefetcher
import com.branchbase.cache.SearchCacheDatabase
import com.branchbase.cache.SearchCacheManager
import com.branchbase.core.GithubWebSession
import com.branchbase.core.RepoCredentialStore
import com.branchbase.core.RustBridge
import com.branchbase.ui.auth.keyTokenCreateUrl
import com.branchbase.ui.decision.PatInputScreen
import com.branchbase.ui.log.Logger
import com.branchbase.ui.navigation.NavigationShell
import com.branchbase.ui.navigation.PageBackHandler
import com.branchbase.ui.navigation.PageLevel
import com.branchbase.ui.navigation.PageSwitcher
import com.branchbase.ui.navigation.TabSwitcher
import com.branchbase.ui.profile.CommitMode
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
    // 从运行详情点某一步进来时带上步骤号，日志页据此落在对应分段
    var jobDetailStep by remember { mutableStateOf<Long?>(null) }
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
    // 仓库信息的完整对象：项目页头部与三个计数都用它，**不再让项目页自己再取一次**
    var repoInfo by remember { mutableStateOf<RepoInfo?>(null) }
    // ── 星标 / 关注 / 复刻（三个按钮的判定与交互状态） ──
    // relation = 当前用户与仓库的关系（星标双向态 / Watch 档位 / 复刻能力）
    var relation by remember { mutableStateOf<RepoViewerRelation?>(null) }
    // 网页会话变化后要重新判定（登录成功 / 会话失效都会走这里）
    var webSessionTick by remember { mutableStateOf(0) }
    var starBusy by remember { mutableStateOf(false) }
    // 乐观更新：请求发出前先改界面，失败回滚。这里记的是相对服务端计数的增量
    var starDelta by remember { mutableStateOf(0L) }
    var showWatchPanel by remember { mutableStateOf(false) }
    var showForkDialog by remember { mutableStateOf(false) }
    var showWebLogin by remember { mutableStateOf(false) }
    // 分支管理 / 分支对比 / 本地分支同步（全屏页）
    var showBranchManage by remember { mutableStateOf(false) }
    var comparePair by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showLocalSync by remember { mutableStateOf(false) }
    // 提交模式（代码页气泡面板直接切换，不必再进「设置」）
    var showCommitMode by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()


    // ── 仓库凭据（D）：账号优先，账号打不开这个仓库时才回退 ──────────────────
    // 规则（产品口径）：① 账号能访问就一律用账号；② 回退后**读与写都用**这条令牌 ——
    // 因此这个仓库里的动作身份可能与当前账号不同，页面上必须有可见提示（横幅见下）；
    // ③ 凭据的增删在「设置 → 仓库凭据」（只在令牌登录模式显示）。
    val accountHost = remember(sessionJson) { sessionInfo(sessionJson).first }
    var credentialTick by remember { mutableStateOf(0) }
    val repoCredential = remember(owner, repo, accountHost, credentialTick) {
        RepoCredentialStore.find(context, accountHost, owner, repo)
    }
    /** 账号被拒后是否已回退到仓库凭据。 */
    var credentialFallback by remember(owner, repo) { mutableStateOf(false) }
    /** 用户显式点了「改用账号」：本次不再自动回退（否则会来回打架）。 */
    var accountOnly by remember(owner, repo) { mutableStateOf(false) }
    /** 「用访问令牌打开」本次会话的覆盖（不落盘；勾了「记住」才会进仓库凭据）。 */
    var tokenOverride by remember { mutableStateOf<String?>(null) }
    var overrideLogin by remember { mutableStateOf<String?>(null) }
    var showPatInput by remember { mutableStateOf(false) }
    /** 本次会话真正生效的令牌：手动输入 > 仓库凭据回退 >（null = 用账号）。 */
    val activeToken = tokenOverride ?: repoCredential?.takeIf { credentialFallback }?.token
    val activeCredentialLogin = overrideLogin ?: repoCredential?.takeIf { credentialFallback }?.login
    /** 传给子页面的会话：有覆盖就用覆盖令牌重建的那份（子页面零改动）。 */
    val session = remember(sessionJson, activeToken) {
        activeToken?.let { sessionWithToken(sessionJson, it) } ?: sessionJson
    }
    // 本地 git 相关动作（分支同步页）需要 token
    val sessionToken = remember(session) { sessionInfo(session).second }
    // 作业日志：在仓库页这一层建**一个**，Run 详情与 Job 详情共用 ——
    // 两个页面切来切去不会重复下载、也不会重复切段（取数逻辑在 :joblogs 模块）
    val jobLogStore = rememberJobLogStore(session, owner, repo)
    // 当前提交模式：**它同时是「Git 悬浮球是否出现」的判据**（只有本地仓库模式才显示），
    // 所以必须是随切换更新的状态；只存 label 字符串就没法参与这个判断了。
    var mode by remember { mutableStateOf<CommitMode?>(commitMode(context)) }

    // 打开弹窗时重读一次（模式也可能是在「设置 → 提交模式」里改的）
    LaunchedEffect(showCommitMode) {
        if (showCommitMode) mode = commitMode(context)
    }

    /**
     * 进入仓库页：分支列表 + 仓库信息**并行**加载（原来是两个串行请求），
     * 分支列表先直出缓存（含过期）再回源，最后触发项目页/其他 tab 的预加载。
     */
    LaunchedEffect(owner, repo) {
        val (h, t, _) = sessionInfo(session)
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
            // 默认分支 + 当前用户写权限（发布页的编辑/删除、⋮ 气泡里的分支同步都依赖 canPush）
            infoJob.await()?.let { info ->
                if (branch == null) branch = info.defaultBranch
                repoCanPush = info.canPush
                repoInfo = info
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

    // ── 星标 / 关注 / 复刻：判定与动作 ──
    //
    // 判定规则全部收在 [RepoRelationRules]（纯函数），这里只做「取数 → 落地状态 → 反馈」。
    // token 复用上面已解好的 sessionToken（本地 git 动作也要它）
    val sessionHost = remember(session) { sessionInfo(session).first }
    val sessionLogin = remember(session) { sessionInfo(session).third }
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    /**
     * 关系态与仓库信息**并行**取。
     *
     * 判定输入越早到，按钮越早显示正确形态；它同时提供复刻的 `forkabilityError`
     * 与 Custom 的当前勾选（两样都是 API 拿不到的，见 [RepoActions.loadRelation]）。
     */
    LaunchedEffect(owner, repo, webSessionTick) {
        // ① 先直出（含过期）：星标 / Watch 的形态当帧就位。判定本身要走
        //    「网页会话 → GraphQL」两条腿，冷的一次实测 ~800ms（真机日志 22:52:52.519 → 53.320），
        //    这段时间按钮此前一直是空的 —— 页面先渲染一遍、结论到了再重画一遍。
        RepoActions.cachedRelation(context, owner, repo, sessionLogin)?.let { relation = it }
        // ② 再回源复核：拿到新值覆盖；拿不到就保留旧值（总比空着强）
        RepoActions.loadRelation(context, sessionHost, sessionToken, owner, repo, sessionLogin)
            ?.let { relation = it }
    }
    // 刷新后服务端计数会重来一遍，乐观增量必须归零，否则数字会越刷越离谱
    LaunchedEffect(owner, repo, refreshTick) { starDelta = 0L }

    val forkDecision = RepoRelationRules.forkDecision(relation, repoInfo, owner, sessionLogin)

    /** 星标：收藏 ↔ 取消收藏（双向态）。乐观更新 + 失败回滚。 */
    fun toggleStar() {
        if (sessionToken.isBlank()) {
            toast("请先登录")
            return
        }
        if (starBusy) return
        val target = RepoRelationRules.starredAfterToggle(relation?.starred == true)
        relation = (relation ?: RepoViewerRelation()).copy(starred = target)
        starDelta += RepoRelationRules.starDelta(target)
        starBusy = true
        scope.launch {
            val error = RepoActions.setStar(sessionHost, sessionToken, owner, repo, target)
            starBusy = false
            if (error != null) {
                relation = (relation ?: RepoViewerRelation()).copy(starred = !target)
                starDelta -= RepoRelationRules.starDelta(target)
                toast(error)
            } else {
                // 写回缓存：否则 5 分钟内再进这个仓库，按钮又变回切换前的样子
                relation?.let { RepoActions.cacheRelation(context, owner, repo, sessionLogin, it) }
            }
        }
    }

    /** 关注：写入档位。有网页会话时四档都走网页端点（Custom 只有它有）。 */
    fun applyWatch(level: WatchLevel, threads: List<String>) {
        showWatchPanel = false
        scope.launch {
            val error = RepoActions.setWatch(
                context = context,
                host = sessionHost,
                token = sessionToken,
                owner = owner,
                repo = repo,
                relation = relation,
                level = level,
                threadTypes = threads,
            )
            if (error == null) {
                relation = (relation ?: RepoViewerRelation()).copy(subscription = level)
                relation?.let { RepoActions.cacheRelation(context, owner, repo, sessionLogin, it) }
            } else {
                toast(error)
            }
        }
    }

    /** 复刻：按持有者分流 —— 自己的仓库进列表，他人走网页版流程，被禁用则明说。 */
    fun onForkClick() {
        when (forkDecision.mode) {
            ForkMode.LIST_ONLY -> peoplePage = "fork"
            ForkMode.DISABLED -> toast(forkDecision.reason ?: "该仓库已关闭复刻")
            ForkMode.DIALOG -> showForkDialog = true
        }
    }

    // 工作流操作抽屉（长按工作流 / 运行历史右上角按钮召唤）。
    // 放在所有全屏页分支之前：运行历史/执行页都是「提前 return」的，放尾部会渲染不到。
    val acting = workflowAction
    if (acting != null) {
        WorkflowActionSheet(
            sessionJson = session,
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
        showPatInput -> RepoRoute.PatInput
        showReleaseEdit -> RepoRoute.ReleaseEdit(releaseEditTarget)
        releaseDetail != null -> RepoRoute.ReleaseDetail(releaseDetail!!)
        showWebLogin -> RepoRoute.WebLogin
        showBranchSync -> RepoRoute.BranchSync
        showBranchManage -> RepoRoute.BranchManage
        comparePair != null -> RepoRoute.BranchCompare(comparePair!!)
        showLocalSync -> RepoRoute.LocalSync
        peoplePage != null -> RepoRoute.People(peoplePage!!)
        filePage != null -> RepoRoute.File(filePage!!)
        issuePage != null -> RepoRoute.Issue(issuePage!!)
        pullPage != null -> RepoRoute.Pull(pullPage!!)
        commitPage != null -> RepoRoute.Commit(commitPage!!)
        jobDetailPage != null -> RepoRoute.JobDetail(jobDetailPage!!, jobDetailStep)
        runDetailPage != null -> RepoRoute.RunDetail(runDetailPage!!)
        dispatchTarget != null -> RepoRoute.Dispatch(dispatchTarget!!)
        workflowRunsPage != null -> RepoRoute.WorkflowRuns(workflowRunsPage!!)
        else -> RepoRoute.Tab
    }

    // 底部 ⋮ 气泡展开时同样铺了一层全屏遮罩：返回键先收起它，而不是关掉整个仓库页。
    // 注册顺序：在按路由分派的 handler 之后、各子页的 handler 之前 ——
    // 子页打开时气泡是收起的，两者不会同时启用。
    PageBackHandler(bubbleExpanded) { bubbleExpanded = false }

    // 打不开仓库时的「出路」：失败卡（ListError）从这里取 —— 见 LocalRepoAccessActions 的说明
    val repoAccessActions = remember(owner, repo, session) {
        RepoAccessActions(
            useToken = { showPatInput = true },
            reauth = { openInBrowser(context, keyTokenCreateUrl()) },
            openInBrowser = { openInBrowser(context, "https://${sessionInfo(session).first}/$owner/$repo") },
            probeScopes = { RustBridge.oauthScopes(sessionInfo(session).first, sessionInfo(session).second) },
            onAccessDenied = { msg ->
                val cred = repoCredential
                when {
                    // 用户明确要求「只用账号」时不自动回退
                    accountOnly -> Logger.local("账号打不开 $owner/$repo（用户已选「改用账号」）：${msg.take(80)}", "私有仓库")
                    cred != null && !credentialFallback && tokenOverride == null -> {
                        credentialFallback = true
                        refreshTick++
                        Logger.local(
                            "账号打不开 $owner/$repo，回退到仓库凭据 @${cred.login}（读与写都用它）：${msg.take(80)}",
                            "私有仓库",
                        )
                    }
                    // 覆盖令牌也打不开：要说出来（否则用户只看到「又失败了」，不知道用的是哪条令牌）
                    tokenOverride != null ->
                        Logger.warn(
                            com.branchbase.ui.log.LogCategory.NETWORK,
                            "私有仓库",
                            "本次输入的令牌也打不开 $owner/$repo（身份 @${overrideLogin ?: "未知"}）：${msg.take(80)}",
                        )
                    cred == null -> Logger.local("账号打不开 $owner/$repo，且未配仓库凭据：${msg.take(80)}", "私有仓库")
                }
            },
        )
    }

    // 底部导航栏由 [NavigationShell] 持有（**不在**下面的 PageSwitcher 里）。
    // 放进切换器里的话，切 Tab 会被同级动效连着整条栏一起播（2026-09 之前那 2% 垂直位移就是这样
    // 把「切页面时导航栏上下跳」带出来的：旧栏上移、新栏上浮、两栏错位叠着；顶部栏也被同一个动效带着动）。
    // 现在栏只在该出现时进出（进子页自己收起），页面切换只动页面内容。
    NavigationShell(
        bar = {
            RepoBottomBar(
                selected = page,
                canPush = repoCanPush,
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
                onBubbleAction = { key ->
                    bubbleExpanded = false
                    when (key) {
                        BUBBLE_ACTION_BRANCH_SYNC -> {
                            showBranchSync = true
                            Logger.ui("打开「分支同步」", "Compose")
                        }
                    }
                },
            )
        },
        // 只有 Tab 骨架有底部导航；详情 / 文件 / 决策页都是全屏页
        barVisible = route is RepoRoute.Tab,
        modifier = Modifier.fillMaxSize(),
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            /**
             * 子页的**默认返回**：一条规则，不再每个分支各挂一个 `PageBackHandler`。
             *
             * `when` 是穷尽的（`RepoRoute` 是 sealed）⇒ **新增路由时编译器会强制在这里表态** ——
             * 旧的写法漏挂一个分支只会静默「返回时跳掉一层」（网页登录页就这么漏过一版：
             * 在那个页面按系统返回会直接退出整个仓库页）。
             */
            fun leavePage() {
                when (route) {
                    RepoRoute.Tab -> Unit
                    RepoRoute.BranchSync -> showBranchSync = false
                    RepoRoute.BranchManage -> showBranchManage = false
                    RepoRoute.LocalSync -> showLocalSync = false
                    RepoRoute.WebLogin -> showWebLogin = false
                    RepoRoute.PatInput -> showPatInput = false
                    is RepoRoute.BranchCompare -> comparePair = null
                    is RepoRoute.ReleaseDetail -> releaseDetail = null
                    is RepoRoute.ReleaseEdit -> showReleaseEdit = false
                    is RepoRoute.People -> peoplePage = null
                    is RepoRoute.File -> filePage = null
                    is RepoRoute.Issue -> issuePage = null
                    is RepoRoute.Pull -> pullPage = null
                    is RepoRoute.Commit -> commitPage = null
                    is RepoRoute.JobDetail -> { jobDetailPage = null; jobDetailStep = null }
                    is RepoRoute.RunDetail -> runDetailPage = null
                    is RepoRoute.Dispatch -> dispatchTarget = null
                    is RepoRoute.WorkflowRuns -> workflowRunsPage = null
                }
            }

            PageSwitcher(
                state = route,
                onBack = ::leavePage,
                modifier = Modifier.fillMaxSize(),
                label = "repo-page",
            ) { r ->
                when (r) {
                    // 发布编辑页（全屏；target == null 表示新建）
                    is RepoRoute.ReleaseEdit -> {
                        val releaseTarget = r.target
                        ReleaseEditScreen(
                            sessionJson = session,
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
                        ReleaseDetailScreen(
                            sessionJson = session,
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
                        BranchSyncScreen(
                            sessionJson = session,
                            owner = owner,
                            repo = repo,
                            onBack = { showBranchSync = false },
                        )
                    }

                    // 分支管理页（全屏）
                    RepoRoute.BranchManage -> {
                        BranchManageScreen(
                            sessionJson = session,
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
                        BranchCompareScreen(
                            sessionJson = session,
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
                        PeopleListScreen(
                            sessionJson = session,
                            owner = owner,
                            repo = repo,
                            type = people,
                            onBack = { peoplePage = null },
                        )
                    }

                    // 私有仓库「用访问令牌打开」：输入的 token 只在本次会话内覆盖，不落盘、不进日志
                    RepoRoute.PatInput -> {
                        PatInputScreen(
                            onBack = { showPatInput = false },
                            // 先校验：输错当场可见（顺带拿到 @login，用于凭据清单）
                            validate = { t ->
                                RustBridge.getCurrentUser(accountHost, t)
                                    ?.takeIf { !it.startsWith("ERROR:") }
                                    ?.let { json -> runCatching { JSONObject(json).optString("login") }.getOrNull() }
                                    ?.takeIf { it.isNotBlank() && it != "null" }
                            },
                            rememberLabel = "记住这个仓库的凭据（设置 → 仓库凭据 可删除）",
                            onConfirm = { token, login, remember ->
                                tokenOverride = token
                                overrideLogin = login
                                credentialFallback = false
                                showPatInput = false
                                if (remember) {
                                    RepoCredentialStore.save(context, accountHost, owner, repo, token, login)
                                    credentialTick++
                                }
                                refreshTick++
                                // 锚点：`私有仓库` —— 只记「谁在哪用了令牌」，绝不记 token 本身
                                Logger.local("已用访问令牌打开 $owner/$repo（身份 @$login · 记住=$remember）", "私有仓库")
                            },
                        )
                    }

                    // 网页会话登录（只有自定义通知需要）。
                    // 返回键由登录页自己处理（先在网页里后退，退不动才离开本页），
                    // 这里不再注册第二个 BackHandler，免得两处抢同一次返回。
                    RepoRoute.WebLogin -> {
                        GithubWebLoginScreen(
                            host = sessionHost,
                            repoPath = "/$owner/$repo",
                            onBack = { showWebLogin = false },
                            onLoggedIn = { login ->
                                showWebLogin = false
                                webSessionTick++ // 触发关系态重判：网页版能给出最准的判定
                                toast("已登录网页会话：$login")
                            },
                        )
                    }

                    // 文件查看页（全屏）
                    is RepoRoute.File -> {
                        val file = r.page
                        FileViewerScreen(
                            sessionJson = session,
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
                        IssueDetailScreen(
                            sessionJson = session,
                            owner = owner,
                            repo = repo,
                            number = issue,
                            onBack = { issuePage = null },
                        )
                    }

                    // PR 详情页
                    is RepoRoute.Pull -> {
                        val pull = r.number
                        PullDetailScreen(
                            sessionJson = session,
                            owner = owner,
                            repo = repo,
                            number = pull,
                            onBack = { pullPage = null },
                        )
                    }

                    // 提交详情页
                    is RepoRoute.Commit -> {
                        val commit = r.sha
                        CommitDetailScreen(
                            sessionJson = session,
                            owner = owner,
                            repo = repo,
                            sha = commit,
                            onBack = { commitPage = null },
                        )
                    }

                    // 日志页（最深；原「Job 详情页」演进而来）
                    is RepoRoute.JobDetail -> {
                        val job = r.id
                        JobLogScreen(
                            sessionJson = session,
                            owner = owner,
                            repo = repo,
                            jobId = job,
                            initialStepNumber = r.step,
                            logStore = jobLogStore,
                            onBack = { jobDetailPage = null; jobDetailStep = null },
                        )
                    }

                    // Run 详情（jobs）
                    is RepoRoute.RunDetail -> {
                        val run = r.id
                        RunDetailContent(
                            sessionJson = session,
                            owner = owner,
                            repo = repo,
                            runId = run,
                            logStore = jobLogStore,
                            onBack = { runDetailPage = null },
                            onOpenLog = { jobId, step -> jobDetailPage = jobId; jobDetailStep = step },
                            onReRun = { dispatchTarget = it },
                        )
                    }

                    // 手动触发工作流（全屏）
                    is RepoRoute.Dispatch -> {
                        val dispatching = r.workflow
                        WorkflowDispatchScreen(
                            sessionJson = session,
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
                        WorkflowRunsContent(
                            sessionJson = session,
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
                    RepoRoute.Tab -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Primer.BackgroundPrimary)
                                // 底部「手势条」内边距由导航栏自己负责（见 RepoBottomBar），
                                // 这里只管状态栏 —— 两处都取会叠成两层
                                .statusBarsPadding(),
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
                                // 切 Tab 的淡入淡出放在**内容区**自己身上：顶部栏与底部导航栏
                                // 都不参与这个动效 —— 之前把 page 放进外层路由时，整个骨架（含栏）
                                // 被同级动效一起播，栏就在切页面时上下跳
                                if (activeToken != null) {
                                    // 身份可见性：回退/覆盖后，这个仓库里的**写操作**不再以当前账号执行
                                    RepoCredentialBanner(
                                        login = activeCredentialLogin,
                                        onUseAccount = {
                                            accountOnly = true
                                            credentialFallback = false
                                            tokenOverride = null
                                            overrideLogin = null
                                            refreshTick++
                                            Logger.local("用户选择改用账号：$owner/$repo", "私有仓库")
                                        },
                                    )
                                }
                                TabSwitcher(
                                    state = page,
                                    modifier = Modifier.fillMaxSize(),
                                    label = "repo-tab",
                                ) { p ->
                                    // 分支选择器已移到顶部栏（刷新按钮左侧），不再占用一整行
                                    CompositionLocalProvider(LocalRepoAccessActions provides repoAccessActions) {
                                        when (p) {
                                            RepoPage.Overview -> RepositoryOverviewContent(
                                                sessionJson = session, owner = owner, repo = repo, branch = branch, refreshTick = refreshTick,
                                                sharedInfo = repoInfo,
                                                relation = relation,
                                                starCount = repoInfo?.stars?.plus(starDelta),
                                                forkDecision = forkDecision,
                                                starBusy = starBusy,
                                                onLinkClick = { dest -> handleLink(dest, context, onOpenRepo, { path, lines -> filePage = path to lines }) { page = it } },
                                                // 点击做动作、长按看列表（与网页版的两层交互一致）
                                                onStarClick = { toggleStar() },
                                                onStarLongClick = { peoplePage = "star" },
                                                onWatchClick = { showWatchPanel = true },
                                                onWatchLongClick = { peoplePage = "watch" },
                                                onForkClick = { onForkClick() },
                                                // 分支同步入口在底部栏 ⋮ 气泡里（见 bubbleEntries）
                                            )
                                            RepoPage.Code -> RepositoryCodeContent(session, owner, repo, branch, refreshTick, onOpenFile = { filePage = it to null })
                                            RepoPage.Issues -> IssueListContent(session, owner, repo, refreshTick, onItemClick = { issuePage = it.number })
                                            RepoPage.Workflows -> WorkflowListContent(
                                                session, owner, repo, branch, refreshTick,
                                                onItemClick = { runsWorkflow = it; workflowRunsPage = it.id to it.name },
                                                onLongPress = { workflowAction = it },
                                            )
                                            RepoPage.Releases -> ReleaseListContent(
                                                sessionJson = session, owner = owner, repo = repo, refreshTick = refreshTick,
                                                onOpenDetail = { releaseDetail = it },
                                                onCreate = { releaseEditTarget = null; showReleaseEdit = true },
                                            )
                                            RepoPage.PullRequests -> PullListContent(session, owner, repo, branch, refreshTick, onItemClick = { pullPage = it.number })
                                            RepoPage.Commits -> CommitListContent(session, owner, repo, branch, refreshTick, onItemClick = { commitPage = it.sha })
                                            RepoPage.Settings -> RepositorySettingsContent(
                                                sessionJson = session,
                                                owner = owner,
                                                repo = repo,
                                                branches = branches.map { it.name },
                                                defaultBranch = branch ?: "main",
                                            )
                                        }
                                    }
                                }

                                // Git 悬浮球（代码页覆盖层）：**绑定「本地仓库（Git）」模式**，
                                // 另外两种模式（单文件 / 多文件）不显示（判定见 showGitBubble）。
                                if (page == RepoPage.Code && showGitBubble(mode)) {
                                    CodePageGitPanel(
                                        repo = repo,
                                        branches = branches.map { it.name },
                                        defaultBranch = branch ?: branches.firstOrNull()?.name ?: "main",
                                        refreshTick = refreshTick,
                                        modeLabel = mode?.label,
                                        onPickMode = { showCommitMode = true },
                                        onOpenBranchManage = { showBranchManage = true },
                                        onOpenCompare = { b, h -> comparePair = b to h },
                                        onOpenLocalSync = { showLocalSync = true },
                                        onRefresh = { refreshTick++ },
                                    )
                                }
                            }
                        }

                    }
                }
            }
        }
    }

    // Watch 控制面板（点击「关注」）——四档 + Watch settings，与网页版下拉一一对应
    if (showWatchPanel) {
        WatchPanelSheet(
            current = relation?.subscription ?: WatchLevel.PARTICIPATING,
            watchersCount = relation?.watchersCount ?: repoInfo?.watchers,
            hasWebSession = GithubWebSession.has(context, sessionHost),
            threadTypes = relation?.threadTypes.orEmpty(),
            onLoadThreadTypes = { RepoActions.loadWatchThreadTypes(context, sessionHost, owner, repo) },
            onSelect = { level, threads -> applyWatch(level, threads) },
            onOpenSettings = {
                showWatchPanel = false
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RepoActions.watchSettingsUrl(sessionHost))))
                }
            },
            onLoginWeb = {
                showWatchPanel = false
                showWebLogin = true
            },
            onDismiss = { showWatchPanel = false },
        )
    }

    // 复刻对话框（他人仓库才进这里；自己的仓库走的是「复刻列表」）
    if (showForkDialog) {
        ForkSheet(
            sourceOwner = owner,
            repoName = repo,
            login = sessionLogin,
            // 分支数直接用已经加载好的分支列表，不再为弹窗多发一个请求
            branchCount = branches.size.takeIf { it > 0 },
            defaultBranch = branch ?: repoInfo?.defaultBranch ?: "main",
            onLoadTargets = { RepoActions.forkTargets(sessionHost, sessionToken, sessionLogin) },
            onCheckExists = { target, name -> RepoActions.repoExists(sessionHost, sessionToken, target, name) },
            onCreate = { organization, name, defaultBranchOnly ->
                RepoActions.createFork(
                    host = sessionHost,
                    token = sessionToken,
                    owner = owner,
                    repo = repo,
                    organization = organization,
                    name = name,
                    defaultBranchOnly = defaultBranchOnly,
                )
            },
            onCreated = { full ->
                showForkDialog = false
                if (full.isBlank()) {
                    toast("复刻已提交（GitHub 异步创建，稍后可用）")
                } else {
                    toast("已复刻到 $full")
                    val (newOwner, newRepo) = full.split("/", limit = 2).let { it.first() to it.getOrElse(1) { repo } }
                    onOpenRepo(newOwner, newRepo)
                }
            },
            onDismiss = { showForkDialog = false },
        )
    }

    // 分支切换弹窗（顶部栏分支胶囊触发）
    if (showBranchDialog) {        BranchSwitchDialog(
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

    // 提交模式选择（代码页 Git 悬浮球里直接切换，无需再进「设置」）
    if (showCommitMode) {
        CommitModePickerDialog(
            onDismiss = { showCommitMode = false },
            onConfirm = { picked ->
                saveCommitMode(context, picked)
                mode = picked
                showCommitMode = false
                Logger.ui("提交模式改为 ${picked.label}", "Compose")
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

    /** Tab 骨架（基础内容）。**刻意不带「当前页」**：切 Tab 是内容区自己的维度（见 `repo-tab` 那层 [TabSwitcher]），不是换页。 */
    data object Tab : RepoRoute {
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

    /**
     * GitHub 网页会话登录页。
     *
     * 只有「自定义通知」需要它 —— 这类能力只有网页端有，而网页端只认浏览器 Cookie
     * （OAuth token 会被 302 到登录页）。其余能力一律走官方 API，不打扰用户。
     */
    data object WebLogin : RepoRoute {
        override val depth: Int get() = 1
    }

    /** 私有仓库打不开时的「用访问令牌打开」（P0-5）。 */
    data object PatInput : RepoRoute {
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
    data class JobDetail(val id: Long, val step: Long? = null) : RepoRoute {
        override val depth: Int get() = 3
    }
}

/**
 * 代码页 Git 悬浮球（**仅本地仓库模式**，调用点已按提交模式门控）。
 *
 * 把原本只在「设置 → 本地仓库」里才有的入口挂到代码页：
 * 提交模式（就地切换，可切到别的模式后球自动收起）、分支管理、分支对比、
 * 本地分支同步、刷新。
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

    // 展开态铺了一层全屏透明遮罩（点空白收起）：返回键要消费的是「收起面板」，
    // 而不是把整个仓库页关掉（遮罩挡着正文时，用户按返回的意图一定是不看了）。
    PageBackHandler(expanded) { expanded = false }

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

/**
 * ⋮ 气泡里的一项：要么切到某个 Tab 页，要么触发一个动作。
 *
 * 为什么不用 `List<Pair<RepoPage, Icon>>`：分支同步**不是** `RepoPage` —— 它不进 Tab 骨架
 * （`when (page)` 里渲染不到），而是像其它全屏页一样盖上来（`RepoRoute.BranchSync`）。
 * 硬塞进 `RepoPage` 会让那个 `when` 多出一条永远走不到的支路。
 */
private sealed interface BubbleEntry {
    val label: String
    val icon: ImageVector

    data class Page(val page: RepoPage, override val icon: ImageVector) : BubbleEntry {
        override val label: String get() = page.label
    }

    /** [key] 由 [RepoBottomBar] 的 `onBubbleAction` 分派。 */
    data class Action(override val label: String, override val icon: ImageVector, val key: String) : BubbleEntry
}

/** 气泡动作 key：分支同步（服务端合并，需要写权限）。 */
private const val BUBBLE_ACTION_BRANCH_SYNC = "branchSync"

/**
 * 气泡项（顺序即菜单顺序）。分支同步放最后 —— 它是动作，不是页面。
 *
 * 它以前是概览页里一整行描边入口，压在 README 之上、视觉重量和星标/复刻同级；
 * 收进 ⋮ 之后首屏不再被它占掉一行，且天然落在「次要入口」的语境里。
 *
 * `canPush=false`（别人的仓库）时**不显示**而不是点进去再失败 —— 与
 * [BranchManageScreen] 对新建/删除的处理一致（那里也是隐藏，只留只读的对比）。
 */
private fun bubbleEntries(canPush: Boolean): List<BubbleEntry> = listOfNotNull(
    BubbleEntry.Page(RepoPage.PullRequests, Icons.AutoMirrored.Filled.CallSplit),
    BubbleEntry.Page(RepoPage.Commits, Icons.Filled.History),
    BubbleEntry.Page(RepoPage.Settings, Icons.Filled.Settings),
    BubbleEntry.Action("分支同步", Icons.Filled.Sync, BUBBLE_ACTION_BRANCH_SYNC).takeIf { canPush },
)

@Composable
private fun RepoBottomBar(
    selected: RepoPage,
    canPush: Boolean,
    onSelect: (RepoPage) -> Unit,
    bubbleExpanded: Boolean,
    onBubbleToggle: (Boolean) -> Unit,
    onBubbleItem: (RepoPage) -> Unit,
    onBubbleAction: (String) -> Unit,
) {
    val entries = remember(canPush) { bubbleEntries(canPush) }
    // `.navigationBarsPadding()` 放在 `.height()` **之前**：内边距算在 56dp 之外，
    // 系统手势条那块留白沿用壳子的底色（原来由外层 Column 的 navigationBarsPadding 提供）。
    // 栏自己负责这条内边距（NavigationShell 不会再加一层）。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
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
                tint = if (entries.any { it is BubbleEntry.Page && it.page == selected }) Primer.Blue500 else Primer.IconPrimary,
                modifier = Modifier
                    .size(46.dp)
                    .clickable { onBubbleToggle(!bubbleExpanded) }
                    .padding(10.dp),
            )
            DropdownMenu(expanded = bubbleExpanded, onDismissRequest = { onBubbleToggle(false) }) {
                entries.forEach { entry ->
                    DropdownMenuItem(
                        text = { Text(entry.label) },
                        leadingIcon = { Icon(entry.icon, null, tint = Primer.IconSecondary, modifier = Modifier.size(18.dp)) },
                        onClick = {
                            when (entry) {
                                is BubbleEntry.Page -> onBubbleItem(entry.page)
                                is BubbleEntry.Action -> onBubbleAction(entry.key)
                            }
                        },
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

/**
 * 「这个仓库正在用独立凭据」横幅。
 *
 * 为什么必须有：回退到仓库凭据后，这个仓库里的**提交 / 开 PR / 合并**都是以那条令牌的身份执行的，
 * 而导航栏上的账号仍是当前账号 —— 不提示就是**静默换身份**。右侧给一条「改用账号」的退路。
 */
@Composable
private fun RepoCredentialBanner(login: String?, onUseAccount: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Primer.WarningSurface)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "此仓库使用独立凭据" + (login?.takeIf { it.isNotBlank() }?.let { "（@$it）" } ?: "") +
                "：读与写都用它，操作身份与当前账号不同。",
            fontSize = 11.5.sp,
            color = Primer.WarningText,
            modifier = Modifier.weight(1f),
        )
        Text(
            "改用账号",
            fontSize = 11.5.sp,
            color = Primer.Blue500,
            modifier = Modifier
                .padding(start = 10.dp)
                .clickable { onUseAccount() },
        )
    }
}
