package com.branchbase.ui.repository

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import com.branchbase.ui.navigation.BranchbaseLazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Balance
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.R
import com.branchbase.cache.PreloadStore
import com.branchbase.cache.SearchCacheDatabase
import com.branchbase.cache.SearchCacheManager
import com.branchbase.cache.defaultBranchOf
import com.branchbase.core.RustBridge
import com.branchbase.ui.FloatingCardButton
import com.branchbase.ui.TintChip
import com.branchbase.ui.cardSurface
import com.branchbase.ui.theme.Avatar
import com.branchbase.ui.theme.LanguageColors
import com.branchbase.ui.theme.Primer
import com.branchbase.ui.theme.TintRole
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

/**
 * 项目页（仓库首页）。
 *
 * 结构：
 * 仓库头（owner/名/描述）→ 星标/复刻/关注 → README → 许可证 → 贡献者 → 语言比例条。
 * 数据源：getRepoInfo / readmeHtml+parseHtml / getRepoLanguages / getRepoContributors。
 *
 * ## 仓库信息为什么由外部传入
 *
 * 这一页与 [RepositoryScreen] 都要 `GET /repos/{o}/{r}`（前者画头部与三个计数，
 * 后者取默认分支与 `permissions.push`）。原先两处各发一次，首次进入必然重复 ——
 * 两个同内容的请求互相竞争，按钮上的计数就卡在这轮往返上。现在统一由
 * [RepositoryScreen] 取一次，这里只消费 [sharedInfo]。
 *
 * @param relation 当前用户与仓库的关系（星标双向态 / 关注档位 / 复刻能力的判定输入）
 * @param starCount 星标数的显示值（已经算进乐观更新的增量）
 * @param forkDecision 复刻按钮的形态（见 [RepoRelationRules.forkDecision]）
 */
@Composable
fun RepositoryOverviewContent(
    sessionJson: String,
    owner: String,
    repo: String,
    branch: String? = null,
    refreshTick: Int = 0,
    sharedInfo: RepoInfo? = null,
    relation: RepoViewerRelation? = null,
    starCount: Long? = null,
    forkDecision: ForkDecision = ForkDecision(ForkMode.DIALOG),
    starBusy: Boolean = false,
    onLinkClick: (Destination) -> Unit,
    onStarClick: () -> Unit = {},
    onStarLongClick: () -> Unit = {},
    onWatchClick: () -> Unit = {},
    onWatchLongClick: () -> Unit = {},
    onForkClick: () -> Unit = {},
    /** 贡献者卡的「查看全部」：卡里只放前 8 位，完整列表在独立全屏页。 */
    onOpenContributors: () -> Unit = {},
) {
    val session = remember(sessionJson) { runCatching { JSONObject(sessionJson) }.getOrNull() }
    val host = session?.optString("host", "github.com") ?: "github.com"
    val token = session?.optJSONObject("token")?.optString("access_token").orEmpty()
    val login = session?.optJSONObject("user")?.optString("login").orEmpty()
    val context = LocalContext.current

    // 首帧快照：这个仓库上一轮已经渲染过就**当帧**把内容摆上（见 [RepoOverviewMemory]）。
    // 下面每一个初始值都从它来 —— 数据本来就全在 L1 里命中，缺的只是「首帧有没有内容」。
    val snapshot = remember(owner, repo) { repoOverviewMemory.get(owner, repo) }
    var repoInfo by remember { mutableStateOf<RepoInfo?>(sharedInfo ?: snapshot?.info) }
    var readmeHtml by remember { mutableStateOf(snapshot?.readmeHtml) }
    var effectiveBranch by remember { mutableStateOf("main") }
    var languages by remember { mutableStateOf(snapshot?.languages ?: emptyList()) }
    var contributors by remember { mutableStateOf(snapshot?.contributors ?: emptyList()) }
    var loading by remember { mutableStateOf(sharedInfo == null && snapshot == null) }
    var error by remember { mutableStateOf<String?>(null) }
    // 分区加载态：缓存直出后仍可能有一两块在回源，避免显示成「暂无…」；
    // 快照里已经有内容的那几块**直接不置加载态** —— 这是「重进不闪」的另一半
    var infoLoading by remember { mutableStateOf(sharedInfo == null && snapshot?.info == null) }
    var readmeLoading by remember { mutableStateOf(snapshot?.readmeHtml == null) }
    var langLoading by remember { mutableStateOf(snapshot?.languages.isNullOrEmpty()) }
    var contribLoading by remember { mutableStateOf(snapshot?.contributors.isNullOrEmpty()) }

    /**
     * 自述文件的 WebView 持有者：**作用域在这一页，不在 LazyColumn 的 item 里**。
     *
     * 自述文件是列表里一项 4~6 万 dp 高的 item，滚到页面底部就会被 LazyColumn 回收；
     * item 一没，`remember` 出来的 WebView 与测量高度就一起没了 —— 往回滚时重新组合，
     * 用户看到的是「自述文件重新加载，然后跳回整篇描述的最顶部」（2026-09-22 真机反馈）。
     * 放到页面级之后，回收只是把它摘下来，挂回去还是同一个 WebView、同一份文档、同一个高度。
     * 机制见 [ReadmeViewHolder]。
     */
    // 初始高度来自快照：没有它，这一项挂回去时是 1dp，要等 JS 量完才撑开 —— 那就是「跳」
    val readmeHolder = remember {
        ReadmeViewHolder(initialHeight = snapshot?.readmeHeight ?: 1.dp) { h ->
            repoOverviewMemory.putReadmeHeight(owner, repo, h)
        }
    }
    DisposableEffect(readmeHolder) {
        onDispose { readmeHolder.release() }
    }

    // 外部（RepositoryScreen）拿到仓库信息后补进来 —— 它同时解决了默认分支的判定
    LaunchedEffect(sharedInfo) {
        if (sharedInfo != null) {
            repoInfo = sharedInfo
            infoLoading = false
            if (branch == null && effectiveBranch == "main") effectiveBranch = sharedInfo.defaultBranch
            repoOverviewMemory.putInfo(owner, repo, sharedInfo)
        }
    }

    /**
     * 加载仓库页数据（**与分支无关的那一半**）。
     *
     * 三段式：
     * 1. **缓存直出**：先读（可过期的）整页缓存 —— 有就立刻渲染，不转圈；
     * 2. **并行回源**：语言 / 贡献者两个请求并发（仓库信息由外部提供，见上面的类注释）；
     * 3. **按块收敛**：每块数据到达即单独落地，先到的先显示。
     *
     * ## 键里为什么**不再**带 `branch` / `repoInfo?.defaultBranch`（2026-09-22 修）
     *
     * 1.0.62 为了让 README 在真实默认分支到手后重取一次，把 `repoInfo?.defaultBranch`
     * 塞进了这个 effect 的键。代价是**整段加载都跟着重跑**：进一次仓库页，外部 `sharedInfo`
     * 与缓存直出会先后把分支补上 ⇒ 这个 effect 一共跑 **3 遍**。每遍都会
     * (a) 把语言 / 贡献者重新打回加载态、(b) 用新的 `coroutineScope` 重启在途请求 ——
     * 上一遍的请求被取消，于是同一份数据要发 3 次、骨架要闪 3 次。
     * 真机日志（2026-09-22，v1.0.65）里的形状就是同一批
     * `直出 repo-info ×2 / @main / repo-lang / repo-contrib` 连着出现**三轮**。
     *
     * 现在 README 拆成独立的 [LaunchedEffect]（它才是真正依赖分支的那一块，见下），
     * 这个 effect 只跟 `(owner, repo, refreshTick)` 走。
     */
    LaunchedEffect(owner, repo, refreshTick) {
        val cacheManager = SearchCacheManager(SearchCacheDatabase.getInstance(context).searchCacheDao())
        val force = refreshTick > 0
        error = null
        // 「已经有数据就别再打回加载态」（手动刷新除外 —— 那时用户要的就是「重来一遍」的反馈）。
        // 上面那段注释里的「骨架闪 3 次」正是这里每跑一遍无条件置 true 造成的。
        if (force || repoInfo == null) infoLoading = true
        if (force || readmeHtml == null) readmeLoading = true
        if (force || languages.isEmpty()) langLoading = true
        if (force || contributors.isEmpty()) contribLoading = true
        // （上面这几行的初始值已由首帧快照给足：快照命中时它们本来就是 false）

        val staleInfo = if (force) null else cacheManager.getStale(PreloadStore.infoKey(owner, repo), PreloadStore.TYPE_INFO)
        /**
         * 默认分支的**可信来源**（按优先级）：用户显式选择 → 仓库信息（外部传入，或刚从缓存读出）。
         * 两者都没有 = 「还没拿到」，此时**绝不猜 `main` 去回源** —— 猜错会先取一份 `@main` 的
         * README，等真实分支（如 `master`）到了再取一遍，用户看到的是「仓库页闪现性重建」
         * （真机日志：`@main` 与 `@master` 相隔 1 秒各未命中一次）。
         */
        val knownBranch = branch ?: repoInfo?.defaultBranch ?: defaultBranchOf(staleInfo)

        // ── ① 缓存直出（含过期数据）：命中即先渲染 ──
        if (!force) {
            // 读缓存时可以用猜的分支拼键（猜错就是个 miss，没有任何副作用），
            // 但**回源**不许用（见上）
            val staleBranch = knownBranch ?: DEFAULT_BRANCH_GUESS
            val bundle = PreloadStore.readStaleBundle(cacheManager, owner, repo, staleBranch)
            if (bundle.usable) {
                // 外部传入的值更新（它来自同一个请求，但可能比缓存新）
                bundle.info?.let { parseRepoInfo(it) }?.let {
                    if (repoInfo == null) repoInfo = it
                    repoOverviewMemory.putInfo(owner, repo, it)
                }
                effectiveBranch = staleBranch
                bundle.readme?.let { readmeHtml = it; readmeLoading = false; repoOverviewMemory.putReadme(owner, repo, it) }
                bundle.languages?.let {
                    languages = parseLanguages(it)
                    langLoading = false
                    repoOverviewMemory.putLanguages(owner, repo, languages)
                }
                bundle.contributors?.let {
                    contributors = parseContributors(it)
                    contribLoading = false
                    repoOverviewMemory.putContributors(owner, repo, contributors)
                }
                loading = false
            }
        }

        // ── ② 并行回源 ──
        coroutineScope {
            val langJob = async {
                val key = PreloadStore.langKey(owner, repo)
                val cached = if (force) null else cacheManager.get(key, PreloadStore.TYPE_LANG)
                val json = cached ?: RustBridge.getRepoLanguages(host, token, owner, repo)
                    ?.takeIf { !it.startsWith("ERROR:") }
                    ?.also { cacheManager.put(key, PreloadStore.TYPE_LANG, it) }
                json?.let { parseLanguages(it) }
            }
            val contribJob = async {
                val key = PreloadStore.contribKey(owner, repo)
                val cached = if (force) null else cacheManager.get(key, PreloadStore.TYPE_CONTRIB)
                val json = cached ?: RustBridge.getRepoContributors(host, token, owner, repo)
                    ?.takeIf { !it.startsWith("ERROR:") }
                    ?.also { cacheManager.put(key, PreloadStore.TYPE_CONTRIB, it) }
                json?.let { parseContributors(it) }
            }

            // 实际分支：用户选择 ?: 仓库默认分支 ?: 上一次的值（**不用猜的兜底**）
            if (knownBranch != null) effectiveBranch = knownBranch
            infoLoading = repoInfo == null
            if (repoInfo == null && !loading) error = context.getString(R.string.error_repo_not_found_or_forbidden)

            langJob.await()?.let { languages = it; repoOverviewMemory.putLanguages(owner, repo, it) }
            langLoading = false
            contribJob.await()?.let { contributors = it; repoOverviewMemory.putContributors(owner, repo, it) }
            contribLoading = false
        }

        loading = false
    }

    /**
     * README：**唯一依赖默认分支的那一块**，所以单独一个 effect。
     *
     * 分支是异步到的（外部 `sharedInfo`，或上面那条 effect 的缓存直出），这个 effect 的键
     * 跟着它走：分支未知时**直接返回、什么都不做**（保持骨架，绝不拿猜的分支去取 ——
     * 那正是 1.0.62 修掉的「仓库页闪现性重建」）；分支到齐就取一次，缓存新鲜时一次网络都不发。
     *
     * 拆出来的意义：分支变化只重跑这一块。此前它连着语言 / 贡献者一起重启，
     * 那两块与分支毫无关系，却要跟着取消在途请求、重新打回加载态。
     */
    val readmeBranch = branch ?: repoInfo?.defaultBranch
    LaunchedEffect(owner, repo, readmeBranch, refreshTick) {
        val target = readmeBranch ?: return@LaunchedEffect
        val cacheManager = SearchCacheManager(SearchCacheDatabase.getInstance(context).searchCacheDao())
        val force = refreshTick > 0
        effectiveBranch = target
        val key = PreloadStore.readmeKey(owner, repo, target)
        val cached = if (force) null else cacheManager.get(key, PreloadStore.TYPE_README)
        val json = cached ?: RustBridge.readmeHtml(host, token, owner, repo, target)
            ?.takeIf { !it.startsWith("ERROR:") }
            ?.also { cacheManager.put(key, PreloadStore.TYPE_README, it) }
        // 取到了就换，取不到就保留缓存直出的那一份（别把已有内容清成「暂无自述文件」）
        if (json != null) {
            readmeHtml = json
            repoOverviewMemory.putReadme(owner, repo, json)
        }
        // 分支已知 ⇒ 这一块该有结论了（有内容 / 真的没有），骨架到此为止
        readmeLoading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Primer.BackgroundPrimary),
    ) {
        when {
            // 只在「什么都还没有」时占满屏转圈；有缓存直出后立即渲染内容
            loading && repoInfo == null && readmeHtml == null ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Primer.Blue500)
                }
            error != null && repoInfo == null -> ErrorState(error!!)
            else -> BranchbaseLazyColumn(Modifier.fillMaxSize()) {
                item { RepoIdentityCard(repoInfo) }
                item {
                    ActionRow(
                        stars = starCount ?: repoInfo?.stars,
                        forks = repoInfo?.forks,
                        watchers = repoInfo?.watchers,
                        relation = relation,
                        forkDecision = forkDecision,
                        busy = starBusy,
                        onStarClick = onStarClick,
                        onStarLongClick = onStarLongClick,
                        onWatchClick = onWatchClick,
                        onWatchLongClick = onWatchLongClick,
                        onForkClick = onForkClick,
                    )
                }
                // 分支同步入口不在这里：它是写操作、又占掉首屏一整行，已收进底部栏 ⋮ 气泡
                // （见 RepositoryScreen 的 bubbleEntries，按 canPush 门控）。

                // README **刻意不上卡**：它是 LazyColumn 里一项 4~6 万 dp 高的文档。
                // 卡边的 4dp 阴影会沿整页拉出两条长竖边（读成「页面被夹在两条带子里」），
                // 而卡内边距会让正文可用宽度少 28dp —— 每一段换行都会重排。
                // 所以它只要一个区块标题，正文留在画布上（文档面 ≠ 元数据卡面）。
                item { SectionHeader(Icons.AutoMirrored.Filled.Article, stringResource(R.string.label_readme)) }
                when {
                    readmeLoading -> item { SectionLoading(stringResource(R.string.state_loading_readme)) }
                    readmeHtml == null -> item { EmptyHint(stringResource(R.string.state_no_readme)) }
                    else -> item {
                        ReadmeWebView(
                            html = readmeHtml!!,
                            host = host,
                            owner = owner,
                            repo = repo,
                            branch = effectiveBranch,
                            login = login,
                            token = token,
                            onLinkClick = onLinkClick,
                            // 页面级持有：滚到底再往回滚不重建、不跳回顶部（见 [ReadmeViewHolder]）
                            holder = readmeHolder,
                        )
                    }
                }

                item { LicenseCard(repoInfo?.license) }

                item {
                    ContributorsCard(
                        contributors = contributors,
                        loading = contribLoading,
                        onOpenAll = onOpenContributors,
                    )
                }

                item { LanguagesCard(languages, langLoading) }
            }
        }
    }
}

// ── 组件 ──

/**
 * 仓库身份卡：**浮层卡 + owner 头像 + 名称 + 描述**。
 *
 * 改造前这段是裸文字（owner 小字 / 蓝色大字 / 描述），靠字号与颜色分层；1.4.95 起收进
 * `cardSurface()`，与搜索页的仓库行卡同一形状：头像 26dp（[Avatar] 首帧同步直出）、
 * owner 11sp 弱化、名称 20sp、描述 12.5sp。
 *
 * 顶栏仍然保留 `owner/repo` 标题 —— 滚到页尾之后，那是唯一的定位信息。
 */
@Composable
private fun RepoIdentityCard(info: RepoInfo?) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp)
            .cardSurface()
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(
                url = info?.ownerAvatarUrl,
                login = info?.ownerLogin.orEmpty(),
                size = 26.dp,
            )
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                info?.ownerLogin?.takeIf { it.isNotBlank() }?.let {
                    Text(it, fontSize = 11.sp, color = Primer.TextTertiary, maxLines = 1)
                }
                Text(
                    text = info?.name ?: "",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Primer.Blue500,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val description = info?.description
        if (!description.isNullOrBlank()) {
            Spacer(Modifier.height(7.dp))
            Text(
                description,
                fontSize = 12.5.sp,
                color = Primer.TextSecondary,
                lineHeight = 18.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 星标 / 复刻 / 关注三连：**三张并排的浮层小卡，每张一枚语义图标**（「三语义图标」）。
 *
 * 语义色沿用动态流的 `EVENT_VISUALS`（`ui/profile/ProfileScreen.kt`）：
 * 星标 `WARNING`（= WatchEvent）、复刻 `DONE`（= ForkEvent）、关注 `SUCCESS`。
 * 于是在这页点的星，与首页「某人 star 了这个仓库」是同一种橙 —— 不另立一套色板。
 *
 * 状态只靠**图标变体 + 文案**表达（实心/空心星、眼睛/眼睛划掉），牌色不随状态换：
 * 颜色一旦同时表「类别」与「状态」，两种语义会互相污染。
 * 唯一的例外是「已忽略」与「复刻被禁用」：这两枚的牌降成 `NEUTRAL_SUBTLE`，
 * 免得「已忽略」顶着一块绿色。
 *
 * 长按与点击分开（与网页版的两层交互一致）：长按看列表，点击做动作。
 */
@Composable
private fun ActionRow(
    stars: Long?,
    forks: Long?,
    watchers: Long?,
    relation: RepoViewerRelation?,
    forkDecision: ForkDecision,
    busy: Boolean,
    onStarClick: () -> Unit,
    onStarLongClick: () -> Unit,
    onWatchClick: () -> Unit,
    onWatchLongClick: () -> Unit,
    onForkClick: () -> Unit,
) {
    val starred = relation?.starred == true
    val ignoring = relation?.subscription == WatchLevel.IGNORE
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp),
        // 卡距 6dp：相邻两卡共 6dp 空白，与首页/搜索列表的卡距同一个数
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ActionCard(
            icon = if (starred) Icons.Filled.Star else Icons.Filled.StarBorder,
            role = TintRole.WARNING,
            label = if (starred) stringResource(R.string.state_starred) else stringResource(R.string.action_star),
            count = stars,
            enabled = !busy,
            onClick = onStarClick,
            onLongClick = onStarLongClick,
        )
        ActionCard(
            icon = Icons.AutoMirrored.Filled.CallSplit,
            role = TintRole.DONE,
            label = stringResource(R.string.action_fork),
            count = forks,
            enabled = forkDecision.mode != ForkMode.DISABLED,
            onClick = onForkClick,
        )
        ActionCard(
            icon = if (ignoring) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
            role = if (ignoring) TintRole.NEUTRAL_SUBTLE else TintRole.SUCCESS,
            label = if (ignoring) stringResource(R.string.state_ignored) else stringResource(R.string.action_watch),
            count = watchers,
            onClick = onWatchClick,
            onLongClick = onWatchLongClick,
        )
    }
}

/**
 * 一枚动作卡：`cardSurface()` + 语义图标牌 + 文案 + 计数，整张卡是点击目标。
 *
 * 36dp 高、内边距只留左右 8dp：卡片高度由「一枚 18dp 的牌 + 一行 10~12sp 的字」决定，
 * 不需要上下留白（与个人页统计卡同一结论）。窄屏（320dp）下三张卡各约 92dp、
 * 卡内可用 76dp，牌 18 + 间距 5 + 3 字 10sp + 计数 12sp 正好放得下。
 */
@Composable
private fun RowScope.ActionCard(
    icon: ImageVector,
    role: TintRole,
    label: String,
    count: Long?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    FloatingCardButton(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.weight(1f).height(36.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TintChip(
                icon = icon,
                role = if (enabled) role else TintRole.NEUTRAL_SUBTLE,
                size = 18.dp,
                corner = 6.dp,
                iconSize = 11.dp,
            )
            Spacer(Modifier.width(5.dp))
            Text(
                label,
                fontSize = 10.sp,
                color = if (enabled) Primer.TextSecondary else Primer.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            count?.let {
                Spacer(Modifier.width(3.dp))
                Text(
                    formatCount(it),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (enabled) Primer.TextPrimary else Primer.TextTertiary,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 区块标题（**不上卡**的那一块用）：语义图标牌 + 13sp 粗体。
 *
 * 目前只有 README 用它 —— 文档区只要一个标题，正文留在画布上（理由见 item 列表处的注释）。
 * 图标牌取 `NEUTRAL_SUBTLE`：一屏的彩色块只留给动作区那三枚。
 */
@Composable
private fun SectionHeader(icon: ImageVector, title: String) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TintChip(icon, TintRole.NEUTRAL_SUBTLE)
        Spacer(Modifier.width(8.dp))
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary)
    }
}

/**
 * 一张区块卡：**卡面 + 语义图标 + 标题**，内容由调用方给。
 *
 * 与 README 的分工就是「元数据 vs 文档」：许可证 / 贡献者 / 语言是一眼扫完的几行，
 * 上卡之后整页的层次从「标题 + 灰底行」变成「一叠白卡」，与首页、搜索页同一套。
 */
@Composable
private fun SectionCard(
    icon: ImageVector,
    title: String,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp)
            .cardSurface()
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TintChip(icon, TintRole.NEUTRAL_SUBTLE)
            Spacer(Modifier.width(8.dp))
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary)
            Spacer(Modifier.weight(1f))
            trailing?.invoke()
        }
        Spacer(Modifier.height(8.dp))
        content()
    }
}

/** 许可证卡：一行 SPDX 标识（`MIT`）；没有许可证时显示「无」。 */
@Composable
private fun LicenseCard(license: String?) {
    SectionCard(Icons.Outlined.Balance, stringResource(R.string.label_license)) {
        Text(
            text = license ?: stringResource(R.string.label_none_value),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = if (license != null) Primer.Blue500 else Primer.TextTertiary,
        )
    }
}

/**
 * 贡献者卡里**最多列几个**。
 *
 * 卡是一次性组合的（一个 LazyColumn item），不像改造前那样一行一个懒 item ——
 * 100 个贡献者会在一帧里排完。取 8 是「一屏能看见的数量」，多出来的走卡底的「查看全部」，
 * 那个入口开的是独立全屏页（[ContributorsScreen]，同样是浮层卡列表）。
 */
private const val CONTRIBUTORS_PREVIEW = 8

/** 贡献者卡：前 8 位 + 卡底「共 N 位 · 查看全部」，整行可点。 */
@Composable
private fun ContributorsCard(
    contributors: List<Contributor>,
    loading: Boolean,
    onOpenAll: () -> Unit,
) {
    SectionCard(Icons.Outlined.People, stringResource(R.string.label_contributors)) {
        when {
            loading -> SectionLoading(stringResource(R.string.state_loading_contributors))
            contributors.isEmpty() -> EmptyHint(stringResource(R.string.state_no_contributors))
            else -> {
                contributors.take(CONTRIBUTORS_PREVIEW).forEach { ContributorRow(it) }
                if (contributors.size > CONTRIBUTORS_PREVIEW) {
                    Spacer(Modifier.height(2.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onOpenAll)
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.label_contributors_total, contributors.size.toString()),
                            fontSize = 11.5.sp,
                            color = Primer.TextTertiary,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            stringResource(R.string.action_view_all),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Primer.Blue500,
                        )
                        Text("›", fontSize = 13.sp, color = Primer.Blue500)
                    }
                }
            }
        }
    }
}

@Composable
private fun ContributorRow(c: Contributor) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 用统一的 [Avatar] 而不是 Coil 的 AsyncImage：前者**首帧同步直出**进程内已解码的位图
        // （24 条 LRU），后者每次重建都要重放一遍「蓝底 → 真图」。贡献者一屏十来个，
        // 那串 pop-in 正是「参与者列表渲染有点慢」的观感来源；顺带 Avatar 的落盘还有 200 文件上限
        // （见 core/AvatarCache 的 evictionVictims）。
        Avatar(url = c.avatarUrl, login = c.login, size = 24.dp)
        Spacer(Modifier.width(9.dp))
        Text(
            c.login,
            fontSize = 13.sp,
            color = Primer.Blue500,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            pluralStringResource(R.plurals.label_commit_count, c.commits.toInt(), c.commits),
            fontSize = 11.5.sp,
            color = Primer.TextTertiary,
        )
    }
}

/** 语言卡：比例条 + 图例（数据到位之前显示分区加载态）。 */
@Composable
private fun LanguagesCard(langs: List<LanguageStat>, loading: Boolean) {
    SectionCard(Icons.Outlined.Code, stringResource(R.string.label_languages)) {
        when {
            loading -> SectionLoading(stringResource(R.string.state_loading_languages))
            else -> LanguageSection(langs)
        }
    }
}

@Composable
private fun LanguageSection(langs: List<LanguageStat>) {
    if (langs.isEmpty()) {
        EmptyHint(stringResource(R.string.state_no_language_data))
        return
    }
    Column(Modifier.fillMaxWidth()) {
        // 比例条（占比 >= 0.5% 的语言才显示色块）
        Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))) {
            langs.filter { it.percent >= 0.5 }.forEach { lang ->
                Box(
                    Modifier
                        .weight((lang.percent * 1000).toInt().coerceAtLeast(1).toFloat())
                        .fillMaxSize()
                        .background(LanguageColors.of(lang.name)),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // 图例
        langs.forEach { lang ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(LanguageColors.of(lang.name)))
                Spacer(Modifier.width(8.dp))
                Text(lang.name, fontSize = 12.sp, color = Primer.TextPrimary, modifier = Modifier.weight(1f))
                Text(
                    text = String.format("%.2f%%", lang.percent),
                    fontSize = 12.sp,
                    color = Primer.TextTertiary,
                )
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 12.5.sp, color = Primer.TextTertiary)
    }
}

/** 分区加载态：该块数据仍在回源，避免误显示「暂无…」。 */
@Composable
private fun SectionLoading(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            color = Primer.Blue500,
            strokeWidth = 2.dp,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 12.sp, color = Primer.TextTertiary)
    }
}

@Composable
private fun ErrorState(message: String) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(stringResource(R.string.error_load_failed), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
        Spacer(Modifier.height(4.dp))
        Text(message, fontSize = 12.sp, color = Primer.TextTertiary)
    }
}

private fun formatCount(n: Long): String = when {
    n >= 1000 -> "%.1fk".format(n / 1000.0)
    else -> n.toString()
}
/**
 * 「默认分支未知」时**只用于读缓存**的猜测值。
 *
 * 猜错就是一次 miss，没有任何副作用；但**绝不能用它去回源** ——
 * 那会先取一份 `@main` 的 README，等真实默认分支（如 `master`）到了再取一遍，
 * 用户看到的是「仓库页闪现性重建」（真机日志里两次未命中相隔 1 秒）。
 */
private const val DEFAULT_BRANCH_GUESS = "main"
