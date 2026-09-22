package com.branchbase.ui.repository

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.branchbase.cache.PreloadStore
import com.branchbase.cache.SearchCacheDatabase
import com.branchbase.cache.SearchCacheManager
import com.branchbase.cache.defaultBranchOf
import com.branchbase.core.RustBridge
import com.branchbase.ui.theme.LanguageColors
import com.branchbase.ui.theme.Primer
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
) {
    val session = remember(sessionJson) { runCatching { JSONObject(sessionJson) }.getOrNull() }
    val host = session?.optString("host", "github.com") ?: "github.com"
    val token = session?.optJSONObject("token")?.optString("access_token").orEmpty()
    val login = session?.optJSONObject("user")?.optString("login").orEmpty()
    val context = LocalContext.current

    var repoInfo by remember { mutableStateOf<RepoInfo?>(sharedInfo) }
    var readmeHtml by remember { mutableStateOf<String?>(null) }
    var effectiveBranch by remember { mutableStateOf("main") }
    var languages by remember { mutableStateOf<List<LanguageStat>>(emptyList()) }
    var contributors by remember { mutableStateOf<List<Contributor>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    // 分区加载态：缓存直出后仍可能有一两块在回源，避免显示成「暂无…」
    var infoLoading by remember { mutableStateOf(sharedInfo == null) }
    var readmeLoading by remember { mutableStateOf(true) }
    var langLoading by remember { mutableStateOf(true) }
    var contribLoading by remember { mutableStateOf(true) }

    // 外部（RepositoryScreen）拿到仓库信息后补进来 —— 它同时解决了默认分支的判定
    LaunchedEffect(sharedInfo) {
        if (sharedInfo != null) {
            repoInfo = sharedInfo
            infoLoading = false
            if (branch == null && effectiveBranch == "main") effectiveBranch = sharedInfo.defaultBranch
        }
    }

    /**
     * 加载仓库页数据。
     *
     * 三段式：
     * 1. **缓存直出**：先读（可过期的）整页缓存 —— 有就立刻渲染，不转圈；
     * 2. **并行回源**：语言 / 贡献者两个请求并发；README 依赖默认分支，
     *    在拿到分支后立刻与它们并行；
     * 3. **按块收敛**：每块数据到达即单独落地，先到的先显示。
     *
     * 仓库信息不在这里回源（见上面的类注释），只吃缓存直出 + 外部传入。
     *
     * ⚠️ 键里必须带 `repoInfo?.defaultBranch`：默认分支到手时要**重跑一次**，
     * 否则 README 会一直停在「用猜的 main 取回来的那一份」（下面 [DEFAULT_BRANCH_GUESS] 的注释）。
     */
    LaunchedEffect(owner, repo, branch, refreshTick, repoInfo?.defaultBranch) {
        val cacheManager = SearchCacheManager(SearchCacheDatabase.getInstance(context).searchCacheDao())
        val force = refreshTick > 0
        error = null
        infoLoading = repoInfo == null
        readmeLoading = true
        langLoading = true
        contribLoading = true

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
                bundle.info?.let { parseRepoInfo(it) }?.let { if (repoInfo == null) repoInfo = it }
                effectiveBranch = staleBranch
                bundle.readme?.let { readmeHtml = it; readmeLoading = false }
                bundle.languages?.let { languages = parseLanguages(it); langLoading = false }
                bundle.contributors?.let { contributors = parseContributors(it); contribLoading = false }
                loading = false
            }
        }

        // ── ② 并行回源（仓库信息由外部提供，这里只发剩下两个） ──
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
            if (repoInfo == null && !loading) error = "仓库不存在或无权访问"

            // README 依赖默认分支 → 拿到分支后立刻与上面两个请求并行。
            // 分支未知时**跳过**（保持加载态）：等 repoInfo 到了本 effect 会重跑
            //（键里含 `repoInfo?.defaultBranch`），那时只取一次、且取的是对的那份。
            val readmeJob = async {
                if (knownBranch == null) return@async null
                val key = PreloadStore.readmeKey(owner, repo, knownBranch)
                val cached = if (force) null else cacheManager.get(key, PreloadStore.TYPE_README)
                cached ?: RustBridge.readmeHtml(host, token, owner, repo, knownBranch)
                    ?.takeIf { !it.startsWith("ERROR:") }
                    ?.also { cacheManager.put(key, PreloadStore.TYPE_README, it) }
            }
            readmeJob.await()?.let { readmeHtml = it }
            // 分支未知 ≠ 加载完成：保持骨架，别让「还没取」显示成「没有 README」
            if (knownBranch != null) readmeLoading = false
            langJob.await()?.let { languages = it }
            langLoading = false
            contribJob.await()?.let { contributors = it }
            contribLoading = false
        }

        loading = false
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
            else -> LazyColumn(Modifier.fillMaxSize()) {
                item { RepoHeader(repoInfo) }
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

                item { SectionTitle("自述文件 README") }
                when {
                    readmeLoading -> item { SectionLoading("正在加载自述文件…") }
                    readmeHtml == null -> item { EmptyHint("暂无自述文件") }
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
                        )
                    }
                }

                item { SectionTitle("许可证 License") }
                item { LicenseRow(repoInfo?.license) }

                item { SectionTitle("贡献者 Contributors") }
                when {
                    contribLoading -> item { SectionLoading("正在加载贡献者…") }
                    contributors.isEmpty() -> item { EmptyHint("暂无贡献者") }
                    else -> items(contributors, key = { it.login }) { ContributorRow(it) }
                }

                item { SectionTitle("项目语言 Languages") }
                if (langLoading) {
                    item { SectionLoading("正在加载语言构成…") }
                } else {
                    item { LanguageSection(languages) }
                }
            }
        }
    }
}

// ── 组件 ──

@Composable
private fun RepoHeader(info: RepoInfo?) {
    Column(Modifier.fillMaxWidth().padding(14.dp, 16.dp)) {
        info?.ownerLogin?.let {
            Text(it, fontSize = 12.sp, color = Primer.TextTertiary)
        }
        Text(
            text = info?.name ?: "",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Primer.Blue500,
        )
        if (!info?.description.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(info.description, fontSize = 13.sp, color = Primer.TextSecondary, lineHeight = 20.sp)
        }
    }
}

/**
 * 星标 / 复刻 / 关注三连按钮。
 *
 * 三个按钮的**形态**都由判定结果决定，而不是固定文案：
 * - 星标：`relation.starred` 决定实心/空心与文案（收藏 ↔ 取消收藏的双向态）；
 * - 关注：`relation.subscription == IGNORE` 时换成「已忽略」的图标，避免看着像在关注；
 * - 复刻：`forkDecision.mode == DISABLED` 才置灰 —— 自己的仓库不是禁用，是「只能看复刻列表」。
 *
 * 长按与点击分开（网页版也有这两层）：长按看列表，点击做动作。
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
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ActionButton(
            icon = if (starred) Icons.Filled.Star else Icons.Filled.StarBorder,
            label = if (starred) "已星标" else "星标",
            count = stars,
            selected = starred,
            enabled = !busy,
            onClick = onStarClick,
            onLongClick = onStarLongClick,
        )
        ActionButton(
            icon = Icons.AutoMirrored.Filled.CallSplit,
            label = "复刻",
            count = forks,
            enabled = forkDecision.mode != ForkMode.DISABLED,
            onClick = onForkClick,
        )
        ActionButton(
            icon = if (ignoring) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
            label = if (ignoring) "已忽略" else "关注",
            count = watchers,
            selected = ignoring,
            onClick = onWatchClick,
            onLongClick = onWatchLongClick,
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun RowScope.ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    count: Long?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Primer.SelectedRow else Primer.Gray150)
            // clip 必须在点击节点之前：否则水波纹是方的（与 iconTap 同一约定）
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = when {
                !enabled -> Primer.TextTertiary
                selected -> Primer.Blue500
                else -> Primer.IconPrimary
            },
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            fontSize = 12.sp,
            color = if (selected) Primer.Blue500 else Primer.TextSecondary,
        )
        count?.let {
            Spacer(Modifier.width(4.dp))
            Text(formatCount(it), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary)
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = Primer.TextPrimary,
        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp),
    )
}

@Composable
private fun LicenseRow(license: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Primer.Gray150)
            .padding(10.dp, 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = license ?: "无",
            fontSize = 13.sp,
            color = if (license != null) Primer.Blue500 else Primer.TextTertiary,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ContributorRow(c: Contributor) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(Primer.Blue500), contentAlignment = Alignment.Center) {
            if (c.avatarUrl != null) {
                AsyncImage(model = c.avatarUrl, contentDescription = c.login, modifier = Modifier.size(26.dp).clip(CircleShape))
            } else {
                Text(c.login.take(1).uppercase(), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(c.login, fontSize = 13.sp, color = Primer.Blue500, modifier = Modifier.weight(1f))
        Text("${c.commits} 次提交", fontSize = 12.sp, color = Primer.TextTertiary)
    }
}

@Composable
private fun LanguageSection(langs: List<LanguageStat>) {
    if (langs.isEmpty()) {
        EmptyHint("暂无语言数据")
        return
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
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
    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 13.sp, color = Primer.TextTertiary)
    }
}

/** 分区加载态：该块数据仍在回源，避免误显示「暂无…」。 */
@Composable
private fun SectionLoading(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
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
        Text("加载失败", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
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
