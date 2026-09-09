package com.branchbase.ui.repository

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Visibility
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
 * 对齐 `docs/repository-overview-wireframe.md`：
 * 仓库头（owner/名/描述）→ 星标/复刻/关注 → README → 许可证 → 贡献者 → 语言比例条。
 * 数据源：getRepoInfo / readmeHtml+parseHtml / getRepoLanguages / getRepoContributors。
 */
@Composable
fun RepositoryOverviewContent(
    sessionJson: String,
    owner: String,
    repo: String,
    branch: String? = null,
    refreshTick: Int = 0,
    onLinkClick: (Destination) -> Unit,
    onActionClick: (String) -> Unit,
    onOpenBranchSync: () -> Unit = {},
) {
    val session = remember(sessionJson) { runCatching { JSONObject(sessionJson) }.getOrNull() }
    val host = session?.optString("host", "github.com") ?: "github.com"
    val token = session?.optJSONObject("token")?.optString("access_token").orEmpty()
    val login = session?.optJSONObject("user")?.optString("login").orEmpty()
    val context = LocalContext.current

    var repoInfo by remember { mutableStateOf<RepoInfo?>(null) }
    var readmeHtml by remember { mutableStateOf<String?>(null) }
    var effectiveBranch by remember { mutableStateOf("main") }
    var languages by remember { mutableStateOf<List<LanguageStat>>(emptyList()) }
    var contributors by remember { mutableStateOf<List<Contributor>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    // 分区加载态：缓存直出后仍可能有一两块在回源，避免显示成「暂无…」
    var infoLoading by remember { mutableStateOf(true) }
    var readmeLoading by remember { mutableStateOf(true) }
    var langLoading by remember { mutableStateOf(true) }
    var contribLoading by remember { mutableStateOf(true) }

    /**
     * 加载仓库页数据。
     *
     * 三段式（本轮优化）：
     * 1. **缓存直出**：先读（可过期的）整页缓存 —— 有就立刻渲染，不转圈；
     * 2. **并行回源**：仓库信息 / 语言 / 贡献者三个请求并发；README 依赖默认分支，
     *    在拿到分支后立刻与它们并行（原来是 4 个请求串行，一次冷连接握手就要 390ms）；
     * 3. **按块收敛**：每块数据到达即单独落地，先到的先显示。
     */
    LaunchedEffect(owner, repo, branch, refreshTick) {
        val cacheManager = SearchCacheManager(SearchCacheDatabase.getInstance(context).searchCacheDao())
        val force = refreshTick > 0
        error = null
        infoLoading = true
        readmeLoading = true
        langLoading = true
        contribLoading = true

        // ── ① 缓存直出（含过期数据）：命中即先渲染 ──
        if (!force) {
            val staleInfo = cacheManager.getStale(PreloadStore.infoKey(owner, repo), PreloadStore.TYPE_INFO)
            val staleBranch = branch ?: defaultBranchOf(staleInfo) ?: "main"
            val bundle = PreloadStore.readStaleBundle(cacheManager, owner, repo, staleBranch)
            if (bundle.usable) {
                bundle.info?.let { parseRepoInfo(it) }?.let { repoInfo = it }
                effectiveBranch = staleBranch
                bundle.readme?.let { readmeHtml = it; readmeLoading = false }
                bundle.languages?.let { languages = parseLanguages(it); langLoading = false }
                bundle.contributors?.let { contributors = parseContributors(it); contribLoading = false }
                loading = false
            }
        }

        // ── ② 并行回源 ──
        coroutineScope {
            val infoJob = async {
                val key = PreloadStore.infoKey(owner, repo)
                val cached = if (force) null else cacheManager.get(key, PreloadStore.TYPE_INFO)
                val json = cached ?: RustBridge.getRepoInfo(host, token, owner, repo)
                    ?.takeIf { !it.startsWith("ERROR:") }
                    ?.also { cacheManager.put(key, PreloadStore.TYPE_INFO, it) }
                json?.let { parseRepoInfo(it) }
            }
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

            val info = infoJob.await()
            if (info != null) repoInfo = info
            // 实际分支：用户选择 ?: 仓库默认分支 ?: 上一次的值 ?: main
            effectiveBranch = branch ?: info?.defaultBranch ?: effectiveBranch
            infoLoading = false
            if (info == null && repoInfo == null) error = "仓库不存在或无权访问"

            // README 依赖默认分支 → 拿到分支后立刻与上面两个请求并行
            val readmeJob = async {
                val key = PreloadStore.readmeKey(owner, repo, effectiveBranch)
                val cached = if (force) null else cacheManager.get(key, PreloadStore.TYPE_README)
                cached ?: RustBridge.readmeHtml(host, token, owner, repo, effectiveBranch)
                    ?.takeIf { !it.startsWith("ERROR:") }
                    ?.also { cacheManager.put(key, PreloadStore.TYPE_README, it) }
            }
            readmeJob.await()?.let { readmeHtml = it }
            readmeLoading = false
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
                item { ActionRow(repoInfo, onActionClick) }
                item {
                    // 分支同步入口（服务端合并，不需要本地 clone）
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, Primer.Border, RoundedCornerShape(8.dp))
                            .clickable { onOpenBranchSync() }
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("分支同步", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "把某个分支的内容同步到另一个分支",
                            fontSize = 11.sp,
                            color = Primer.TextTertiary,
                            modifier = Modifier.weight(1f),
                        )
                        Text("›", fontSize = 15.sp, color = Primer.TextTertiary)
                    }
                }

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

@Composable
private fun ActionRow(info: RepoInfo?, onActionClick: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ActionButton(Icons.Filled.Star, "Star", info?.stars) { onActionClick("star") }
        ActionButton(Icons.AutoMirrored.Filled.CallSplit, "Fork", info?.forks) { onActionClick("fork") }
        ActionButton(Icons.Filled.Visibility, "Watch", info?.watchers) { onActionClick("watch") }
    }
}

@Composable
private fun RowScope.ActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, count: Long?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(Primer.Gray150)
            .clickable { onClick() }
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = Primer.IconPrimary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, fontSize = 12.sp, color = Primer.TextSecondary)
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
        Text("${c.commits} commits", fontSize = 12.sp, color = Primer.TextTertiary)
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