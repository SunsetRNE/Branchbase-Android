package com.branchbase.ui.repository

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.branchbase.R
import com.branchbase.cache.ListCache
import com.branchbase.cache.SearchCacheDatabase
import com.branchbase.cache.SearchCacheManager
import com.branchbase.core.RustBridge
import com.branchbase.ui.LocalizedText
import com.branchbase.ui.log.LogCategory
import com.branchbase.ui.log.Logger
import com.branchbase.ui.decision.FeedbackLine
import com.branchbase.ui.navigation.rememberPageResumeTick
import com.branchbase.ui.resolve
import com.branchbase.ui.theme.iconTap
import com.branchbase.ui.theme.LanguageColors
import com.branchbase.ui.theme.Primer
import org.json.JSONObject

// ── 通用 ──

/**
 * 用**覆盖令牌**重建会话 JSON（私有仓库「用访问令牌打开」用）。
 *
 * 子页面各自解析自己那份 `sessionJson`，所以只要在仓库页这一层换掉传下去的那份，
 * **子页面零改动**；解析失败时原样返回（宁可不变，也不要造一份坏会话）。
 */
internal fun sessionWithToken(sessionJson: String, token: String): String = runCatching {
    val o = JSONObject(sessionJson)
    val t = o.optJSONObject("token") ?: JSONObject()
    t.put("access_token", token)
    o.put("token", t)
    o.toString()
}.getOrDefault(sessionJson)

internal fun sessionInfo(sessionJson: String): Triple<String, String, String> {
    val s = runCatching { JSONObject(sessionJson) }.getOrNull()
    return Triple(
        s?.optString("host", "github.com") ?: "github.com",
        s?.optJSONObject("token")?.optString("access_token").orEmpty(),
        s?.optJSONObject("user")?.optString("login").orEmpty(),
    )
}

@Composable
private fun ListLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Primer.Blue500)
    }
}

@Composable
private fun ListEmpty(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 13.sp, color = Primer.TextTertiary)
    }
}

/**
 * 仓库打不开时的**出路**：由仓库页提供、失败卡消费。
 *
 * 为什么用 CompositionLocal 而不是加参数：`ListError` 有 6 个调用点（代码/议题/拉取请求/提交/工作流/发布），
 * 把 3 个回调一路穿下去会改 6 个函数签名；而出路只跟「当前在哪个仓库」有关，天然是环境。
 * （`navigation` 包的 `LocalPageActive` 是同一个套路。）
 */
internal data class RepoAccessActions(
    /** 打开访问令牌输入页（PAT 重试）。 */
    val useToken: () -> Unit,
    /** 去建一个带 repo 权限的新令牌（浏览器）。 */
    val reauth: () -> Unit,
    /** 在浏览器里打开这个仓库。 */
    val openInBrowser: () -> Unit,
    /** 探测当前令牌**已授予**的 scopes（拿不到返回 null）。只在失败卡真的要用时才调用。 */
    val probeScopes: suspend () -> String?,
    /**
     * 账号在这个仓库上被拒（404/403）时回调一次（同一 message 只报一次）。
     *
     * 仓库页据此决定**要不要回退到仓库级凭据**（规则：账号优先，打不开才回退）——
     * 判定放在仓库页，失败卡只负责「如实上报」。
     */
    val onAccessDenied: (String) -> Unit,
)

internal val LocalRepoAccessActions = androidx.compose.runtime.staticCompositionLocalOf<RepoAccessActions?> { null }

/** 失败态 + 重试按钮（对应原型「失败」态，与「空态」区分） */
@Composable
internal fun ListError(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.error_load_failed), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
        Spacer(Modifier.height(4.dp))
        Text(message, fontSize = 12.sp, color = Primer.TextTertiary)

        // 打不开仓库（404/403）时给「解释 + 出路」：只给原始错误串，用户只能猜
        val actions = LocalRepoAccessActions.current
        val hint = repoAccessHint(message)
        if (hint != null && actions != null) {
            var scopeLine by remember(message) { mutableStateOf<String?>(null) }
            LaunchedEffect(message) {
                // 上报一次：账号在这个仓库上被拒 —— 仓库页可能因此回退到仓库级凭据
                actions.onAccessDenied(message)
                // 探测一次令牌权限（失败/细粒度令牌拿不到 → 不额外说明）
                scopeLine = scopeVerdictLine(scopeVerdict(actions.probeScopes()))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                hint,
                fontSize = 12.sp,
                color = Primer.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            scopeLine?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    fontSize = 12.sp,
                    color = Primer.TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AccessAction(stringResource(R.string.action_open_with_token_short), primary = true) { actions.useToken() }
                AccessAction(stringResource(R.string.action_create_repo_token)) { actions.reauth() }
                AccessAction(stringResource(R.string.action_open_in_browser)) { actions.openInBrowser() }
            }
            Spacer(Modifier.height(12.dp))
        } else {
            Spacer(Modifier.height(12.dp))
        }

        Box(
            Modifier.clip(CircleShape).background(Primer.Blue500).clickable { onRetry() }.padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(stringResource(R.string.action_retry), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        }
    }
}

/** 失败卡上的一个出路按钮（主按钮为实心蓝，其余描边）。 */
@Composable
private fun AccessAction(label: String, primary: Boolean = false, onClick: () -> Unit) {
    val bg = if (primary) Primer.Blue500 else Color.Transparent
    val fg = if (primary) Color.White else Primer.TextSecondary
    Box(
        Modifier
            .clip(CircleShape)
            .background(bg)
            .then(
                if (primary) Modifier else Modifier.border(1.dp, Primer.Border, CircleShape),
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = fg)
    }
}

/** 「刚刚」的阈值：一分钟内。提出来是为了让 [shortTime] 与调用方（如 `lastUsedLabel`）
 *  共用**同一个判据** —— 调用方需要知道"是不是刚刚"，但不该去比对 [shortTime] 的**输出文案**。 */
internal const val JUST_NOW_MS = 60_000L

/**
 * ISO-8601 UTC → 相对时间（「刚刚 / N 分钟前 / N 小时前 / N 天前」）。
 *
 * 返回 [LocalizedText]：「N 分钟前」是 `<plurals>`（英文要分 `1 minute ago` / `2 minutes ago`），
 * 「刚刚」是 `<string>`，一个 `String` 装不下两种；解析交给渲染层。
 *
 * 解析失败**原样透出 `iso`**（[LocalizedText.raw]）—— 宁可显示原始时间戳，
 * 也不要空一格或显示「1970 年」。
 */
internal fun shortTime(iso: String): LocalizedText = runCatching {
    val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }
    val e = f.parse(iso)?.time ?: return@runCatching LocalizedText(raw = iso)
    val elapsed = System.currentTimeMillis() - e
    val m = elapsed / 60000
    when {
        elapsed < JUST_NOW_MS -> LocalizedText(R.string.relative_just_now)
        m < 60 -> LocalizedText.plural(R.plurals.relative_minutes, m.toInt(), listOf(m))
        m < 1440 -> LocalizedText.plural(R.plurals.relative_hours, (m / 60).toInt(), listOf(m / 60))
        else -> LocalizedText.plural(R.plurals.relative_days, (m / 1440).toInt(), listOf(m / 1440))
    }
}.getOrElse { LocalizedText(raw = iso) }

/**
 * GitHub 的 `state` 字段 → **资源 ID**。
 *
 * 此前 Issue/PR 列表直接把原始值（`open` / `closed` / `merged`）当文案渲染，
 * 于是同一屏里「星标者 / 分支 / 提交」都是中文，唯独状态是英文。
 *
 * 返回 `null` 表示**没有对应资源**（后端新增的状态）：调用方原样透出 `state` 本身，
 * 不吞掉也不猜。用 `Int?` 而不是 `String` 是为了让它**可单测** —— 测试断言资源 ID，
 * 文案搬家（改措辞、再抽一次）不会假红，见 i18n-migration 的坑表。
 */
@StringRes
internal fun stateLabelResOrNull(state: String): Int? = when (state.lowercase()) {
    "open" -> R.string.state_open
    "closed" -> R.string.state_closed
    "merged" -> R.string.state_merged
    "draft" -> R.string.state_draft
    else -> null
}

/** 同名 @Composable 包装：解析留给渲染层，调用点一行都不用改。 */
@Composable
internal fun stateLabelOf(state: String): String =
    stateLabelResOrNull(state)?.let { stringResource(it) } ?: state

@Composable
internal fun stateColor(state: String): Color = when (state) {
    "open" -> Primer.Green500
    "closed" -> Primer.Red500
    "merged" -> Primer.Purple500
    else -> Primer.Gray500
}

/** 将 markdown 正文渲染为 HTML（POST /markdown），套 markdown-body 供 WebView 渲染。 */
internal suspend fun markdownToHtml(
    host: String,
    token: String,
    markdown: String,
): String? {
    val html = RustBridge.renderMarkdown(host, token, markdown) ?: return null
    if (html.startsWith("ERROR:")) return null
    // renderMarkdown 返回的 HTML 无 markdown-body 包装，补一层以复用 WebView 的 CSS
    return "<div class=\"markdown-body\">$html</div>"
}

// ── 代码（文件树，两级导航） ──

@Composable
fun RepositoryCodeContent(sessionJson: String, owner: String, repo: String, branch: String? = null, refreshTick: Int = 0, onOpenFile: (String) -> Unit) {
    val (host, token, _) = sessionInfo(sessionJson)
    val context = LocalContext.current
    var path by remember { mutableStateOf("") }
    // 缓存键先于状态声明：key 变化（切仓库/目录/分支）时列表自动清空，
    // 避免新请求失败时静默显示上一页内容
    val cacheKey = ListCache.key(owner, repo, ListCache.PAGE_CODE, ListCache.codeParams(path, branch))
    var items by remember(cacheKey) { mutableStateOf<List<FileTreeItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryTick by remember { mutableStateOf(0) }

    // Tab 保活 ⇒ 切走再切回不会重跑这个 effect。把「重新可见」的 tick 加进键：
    // 回来时重新校验一次（cachedFirst 命中 L1 就是同帧、TTL 内不联网）。
    // ⚠️ 已有数据时**不置加载态** —— 否则这次重新校验会把列表换成 loading 闪一下。
    val resumeTick = rememberPageResumeTick()
    LaunchedEffect(owner, repo, path, branch, refreshTick, retryTick, resumeTick) {
        if (items.isEmpty()) loading = true
        error = null
        // 只有「本次确实直出了缓存」才在回源失败时静默保留旧内容；否则照常报错
        var shownStale = false
        val manager = SearchCacheManager(SearchCacheDatabase.getInstance(context).searchCacheDao())
        // 目录与分支都会影响结果，两者都参与缓存键（codeParams 对 null 分支用空串占位）

        // ① 先直出缓存（含过期数据）：切目录/分支或重进页面立即有内容；手动刷新与重试时跳过
        if (refreshTick == 0 && retryTick == 0) {
            ListCache.readStale(manager, cacheKey)?.let { cached ->
                val parsed = parseFileTree(cached)
                if (parsed.isNotEmpty()) {
                    items = parsed
                    shownStale = true
                    loading = false
                }
            }
        }

        // ② 回源刷新
        val encoded = if (path.isEmpty()) "" else "/${encodePath(path)}"
        val ref = branch?.takeIf { it.isNotBlank() }?.let { b -> "?ref=${encodeRef(b)}" } ?: ""
        val json = RustBridge.getJson(host, token, "/repos/$owner/$repo/contents$encoded$ref")
        if (json == null || json.startsWith("ERROR:")) {
            if (!shownStale) error = loadFailure(context.getString(R.string.nav_code), json?.removePrefix("ERROR:"), owner, repo)
        } else {
            items = parseFileTree(json)
            ListCache.write(manager, cacheKey, json)
        }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        BreadcrumbBar(owner, repo, path) { newPath -> path = newPath }
        when {
            loading -> ListLoading()
            error != null -> ListError(error!!) { retryTick++ }
            items.isEmpty() -> ListEmpty(stringResource(R.string.state_empty_directory))
            else -> LazyColumn(Modifier.fillMaxSize()) {
                // 同一目录内文件名唯一（GitHub contents API 保证），name 可作稳定 key
                items(items, key = { it.name }) { f ->
                    FileTreeRow(f) {
                        if (f.type == "dir") path = joinPath(path, f.name)
                        else onOpenFile(joinPath(path, f.name))
                    }
                }
            }
        }
    }
}

@Composable
private fun BreadcrumbBar(owner: String, repo: String, path: String, onNavigate: (String) -> Unit) {
    val segs = path.split("/").filter { it.isNotBlank() }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$owner/$repo",
            fontSize = 12.5.sp,
            color = Primer.Blue500,
            modifier = Modifier.clickable { onNavigate("") },
        )
        segs.forEachIndexed { i, seg ->
            Text("/", fontSize = 12.5.sp, color = Primer.TextTertiary)
            Text(
                seg,
                fontSize = 12.5.sp,
                color = if (i == segs.lastIndex) Primer.TextPrimary else Primer.Blue500,
                fontWeight = if (i == segs.lastIndex) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.clickable { onNavigate(segs.take(i + 1).joinToString("/")) },
            )
        }
    }
}

@Composable
private fun FileTreeRow(item: FileTreeItem, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (item.type == "dir") Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
            null,
            tint = Primer.IconSecondary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(item.name, fontSize = 13.sp, color = Primer.TextPrimary, modifier = Modifier.weight(1f))
        if (item.type == "file" && item.size > 0) {
            Text(formatBytes(item.size), fontSize = 11.sp, color = Primer.TextTertiary)
        }
    }
}

private fun formatBytes(n: Long): String = when {
    n >= 1024 -> "%.1f KB".format(n / 1024.0)
    else -> "$n B"
}

// ── Issue 列表 ──

@Composable
fun IssueListContent(sessionJson: String, owner: String, repo: String, refreshTick: Int = 0, onItemClick: (IssueItem) -> Unit) {
    val (host, token, _) = sessionInfo(sessionJson)
    val context = LocalContext.current
    // 缓存键先于状态声明：key 变化（切仓库/目录/分支）时列表自动清空，
    // 避免新请求失败时静默显示上一页内容
    val cacheKey = ListCache.key(owner, repo, ListCache.PAGE_ISSUES)
    var items by remember(cacheKey) { mutableStateOf<List<IssueItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryTick by remember { mutableStateOf(0) }

    // Tab 保活 ⇒ 切走再切回不会重跑这个 effect。把「重新可见」的 tick 加进键：
    // 回来时重新校验一次（cachedFirst 命中 L1 就是同帧、TTL 内不联网）。
    // ⚠️ 已有数据时**不置加载态** —— 否则这次重新校验会把列表换成 loading 闪一下。
    val resumeTick = rememberPageResumeTick()
    LaunchedEffect(owner, repo, refreshTick, retryTick, resumeTick) {
        if (items.isEmpty()) loading = true
        error = null
        // 只有「本次确实直出了缓存」才在回源失败时静默保留旧内容；否则照常报错
        var shownStale = false
        val manager = SearchCacheManager(SearchCacheDatabase.getInstance(context).searchCacheDao())

        // ① 先直出缓存（含过期数据）
        if (refreshTick == 0 && retryTick == 0) {
            ListCache.readStale(manager, cacheKey)?.let { cached ->
                val parsed = parseIssues(cached)
                if (parsed.isNotEmpty()) {
                    items = parsed
                    shownStale = true
                    loading = false
                }
            }
        }

        // ② 回源刷新
        val json = RustBridge.getJson(host, token, "/repos/$owner/$repo/issues?state=all")
        if (json == null || json.startsWith("ERROR:")) {
            if (!shownStale) error = loadFailure(context.getString(R.string.label_issues), json?.removePrefix("ERROR:"), owner, repo)
        } else {
            items = parseIssues(json)
            ListCache.write(manager, cacheKey, json)
        }
        loading = false
    }

    when {
        loading -> ListLoading()
        error != null -> ListError(error!!) { retryTick++ }
        items.isEmpty() -> ListEmpty(stringResource(R.string.state_no_issues))
        else -> LazyColumn(Modifier.fillMaxSize()) {
            // issue number 在仓库内唯一
            items(items, key = { it.number }) { IssueRow(it) { onItemClick(it) } }
        }
    }
}

@Composable
private fun IssueRow(item: IssueItem, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(12.dp, 16.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(16.dp).clip(CircleShape).background(stateColor(item.state)),
            contentAlignment = Alignment.Center,
        ) {}
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary, lineHeight = 18.sp)
            Spacer(Modifier.height(4.dp))
            Text("#${item.number} · ${item.author} · ${shortTime(item.createdAt).resolve()}", fontSize = 11.5.sp, color = Primer.TextTertiary)
        }
    }
}

// ── PR 列表 ──

@Composable
fun PullListContent(sessionJson: String, owner: String, repo: String, branch: String? = null, refreshTick: Int = 0, onItemClick: (PullItem) -> Unit) {
    val (host, token, _) = sessionInfo(sessionJson)
    val context = LocalContext.current
    // 缓存键先于状态声明：key 变化（切仓库/目录/分支）时列表自动清空，
    // 避免新请求失败时静默显示上一页内容
    val cacheKey = ListCache.key(owner, repo, ListCache.PAGE_PULLS, branch ?: "")
    var items by remember(cacheKey) { mutableStateOf<List<PullItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryTick by remember { mutableStateOf(0) }

    // Tab 保活 ⇒ 切走再切回不会重跑这个 effect。把「重新可见」的 tick 加进键：
    // 回来时重新校验一次（cachedFirst 命中 L1 就是同帧、TTL 内不联网）。
    // ⚠️ 已有数据时**不置加载态** —— 否则这次重新校验会把列表换成 loading 闪一下。
    val resumeTick = rememberPageResumeTick()
    LaunchedEffect(owner, repo, branch, refreshTick, retryTick, resumeTick) {
        if (items.isEmpty()) loading = true
        error = null
        // 只有「本次确实直出了缓存」才在回源失败时静默保留旧内容；否则照常报错
        var shownStale = false
        val manager = SearchCacheManager(SearchCacheDatabase.getInstance(context).searchCacheDao())
        // base 分支影响结果，参与缓存键

        // ① 先直出缓存（含过期数据）
        if (refreshTick == 0 && retryTick == 0) {
            ListCache.readStale(manager, cacheKey)?.let { cached ->
                val parsed = parsePulls(cached)
                if (parsed.isNotEmpty()) {
                    items = parsed
                    shownStale = true
                    loading = false
                }
            }
        }

        // ② 回源刷新
        val base = branch?.takeIf { it.isNotBlank() }?.let { b -> "&base=${encodeRef(b)}" } ?: ""
        val json = RustBridge.getJson(host, token, "/repos/$owner/$repo/pulls?state=all$base")
        if (json == null || json.startsWith("ERROR:")) {
            if (!shownStale) error = loadFailure(context.getString(R.string.label_pull_requests), json?.removePrefix("ERROR:"), owner, repo)
        } else {
            items = parsePulls(json)
            ListCache.write(manager, cacheKey, json)
        }
        loading = false
    }

    when {
        loading -> ListLoading()
        error != null -> ListError(error!!) { retryTick++ }
        items.isEmpty() -> ListEmpty(stringResource(R.string.state_no_pull_requests))
        else -> LazyColumn(Modifier.fillMaxSize()) {
            // PR number 在仓库内唯一
            items(items, key = { it.number }) { PullRow(it) { onItemClick(it) } }
        }
    }
}

@Composable
private fun PullRow(item: PullItem, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(12.dp, 16.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(16.dp).clip(CircleShape).background(stateColor(item.state)),
            contentAlignment = Alignment.Center,
        ) {}
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary, lineHeight = 18.sp)
            Spacer(Modifier.height(4.dp))
            Text("#${item.number} · ${item.author} · ${shortTime(item.createdAt).resolve()}", fontSize = 11.5.sp, color = Primer.TextTertiary)
        }
    }
}

// ── 提交列表 ──

@Composable
fun CommitListContent(sessionJson: String, owner: String, repo: String, branch: String? = null, refreshTick: Int = 0, onItemClick: (CommitItem) -> Unit) {
    val (host, token, _) = sessionInfo(sessionJson)
    val context = LocalContext.current
    // 缓存键先于状态声明：key 变化（切仓库/目录/分支）时列表自动清空，
    // 避免新请求失败时静默显示上一页内容
    val cacheKey = ListCache.key(owner, repo, ListCache.PAGE_COMMITS, branch ?: "")
    var items by remember(cacheKey) { mutableStateOf<List<CommitItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryTick by remember { mutableStateOf(0) }

    // Tab 保活 ⇒ 切走再切回不会重跑这个 effect。把「重新可见」的 tick 加进键：
    // 回来时重新校验一次（cachedFirst 命中 L1 就是同帧、TTL 内不联网）。
    // ⚠️ 已有数据时**不置加载态** —— 否则这次重新校验会把列表换成 loading 闪一下。
    val resumeTick = rememberPageResumeTick()
    LaunchedEffect(owner, repo, branch, refreshTick, retryTick, resumeTick) {
        if (items.isEmpty()) loading = true
        error = null
        // 只有「本次确实直出了缓存」才在回源失败时静默保留旧内容；否则照常报错
        var shownStale = false
        val manager = SearchCacheManager(SearchCacheDatabase.getInstance(context).searchCacheDao())
        // 分支影响结果，参与缓存键

        // ① 先直出缓存（含过期数据）
        if (refreshTick == 0 && retryTick == 0) {
            ListCache.readStale(manager, cacheKey)?.let { cached ->
                val parsed = parseCommits(cached)
                if (parsed.isNotEmpty()) {
                    items = parsed
                    shownStale = true
                    loading = false
                }
            }
        }

        // ② 回源刷新
        val sha = branch?.takeIf { it.isNotBlank() }?.let { b -> "?sha=${encodeRef(b)}" } ?: ""
        val json = RustBridge.getJson(host, token, "/repos/$owner/$repo/commits$sha")
        if (json == null || json.startsWith("ERROR:")) {
            if (!shownStale) error = loadFailure(context.getString(R.string.action_commit), json?.removePrefix("ERROR:"), owner, repo)
        } else {
            items = parseCommits(json)
            ListCache.write(manager, cacheKey, json)
        }
        loading = false
    }

    when {
        loading -> ListLoading()
        error != null -> ListError(error!!) { retryTick++ }
        items.isEmpty() -> ListEmpty(stringResource(R.string.state_no_commits))
        else -> LazyColumn(Modifier.fillMaxSize()) {
            // commit sha 唯一
            items(items, key = { it.sha }) { CommitRow(it) { onItemClick(it) } }
        }
    }
}

@Composable
private fun CommitRow(item: CommitItem, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(12.dp, 16.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(Primer.Blue500), contentAlignment = Alignment.Center) {
            Text(item.author.take(1).uppercase(), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(item.message, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary, maxLines = 2)
            Spacer(Modifier.height(4.dp))
            Text("${item.author} · ${item.sha} · ${shortTime(item.date).resolve()}", fontSize = 11.5.sp, color = Primer.TextTertiary)
        }
    }
}

// ── 工作流列表 ──

@Composable
fun WorkflowListContent(sessionJson: String, owner: String, repo: String, branch: String? = null, refreshTick: Int = 0, onItemClick: (WorkflowItem) -> Unit, onLongPress: (WorkflowItem) -> Unit = {}) {
    val (host, token, _) = sessionInfo(sessionJson)
    val context = LocalContext.current
    // 缓存键先于状态声明：key 变化（切仓库/目录/分支）时列表自动清空，
    // 避免新请求失败时静默显示上一页内容
    val cacheKey = ListCache.key(owner, repo, ListCache.PAGE_WORKFLOWS)
    var items by remember(cacheKey) { mutableStateOf<List<WorkflowItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryTick by remember { mutableStateOf(0) }

    // Tab 保活 ⇒ 切走再切回不会重跑这个 effect。把「重新可见」的 tick 加进键：
    // 回来时重新校验一次（cachedFirst 命中 L1 就是同帧、TTL 内不联网）。
    // ⚠️ 已有数据时**不置加载态** —— 否则这次重新校验会把列表换成 loading 闪一下。
    val resumeTick = rememberPageResumeTick()
    LaunchedEffect(owner, repo, branch, refreshTick, retryTick, resumeTick) {
        if (items.isEmpty()) loading = true
        error = null
        // 只有「本次确实直出了缓存」才在回源失败时静默保留旧内容；否则照常报错
        var shownStale = false
        val manager = SearchCacheManager(SearchCacheDatabase.getInstance(context).searchCacheDao())
        // 工作流列表接口不带分支参数，无需 params

        // ① 先直出缓存（含过期数据）
        if (refreshTick == 0 && retryTick == 0) {
            ListCache.readStale(manager, cacheKey)?.let { cached ->
                val parsed = parseWorkflows(cached)
                if (parsed.isNotEmpty()) {
                    items = parsed
                    shownStale = true
                    loading = false
                }
            }
        }

        // ② 回源刷新
        val json = RustBridge.getJson(host, token, "/repos/$owner/$repo/actions/workflows")
        if (json == null || json.startsWith("ERROR:")) {
            if (!shownStale) error = loadFailure(context.getString(R.string.label_workflows), json?.removePrefix("ERROR:"), owner, repo)
        } else {
            items = parseWorkflows(json)
            ListCache.write(manager, cacheKey, json)
        }
        loading = false
    }

    when {
        loading -> ListLoading()
        error != null -> ListError(error!!) { retryTick++ }
        items.isEmpty() -> ListEmpty(stringResource(R.string.state_no_workflows))
        else -> LazyColumn(Modifier.fillMaxSize()) {
            // workflow id 唯一
            items(items, key = { if (it.id != 0L) it.id else it.name }) {
                WorkflowRow(it, onClick = { onItemClick(it) }, onLongClick = { onLongPress(it) })
            }
        }
    }
}

@Composable
/** 工作流行：点击进运行历史；**长按召唤操作抽屉**（执行/查看文件/浏览器打开）。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun WorkflowRow(item: WorkflowItem, onClick: () -> Unit, onLongClick: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(12.dp, 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(item.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary, modifier = Modifier.weight(1f))
        Text(stateLabelOf(item.state), fontSize = 11.sp, color = Primer.TextTertiary)
    }
}

// ── 发布列表 ──

@Composable
fun ReleaseListContent(
    sessionJson: String,
    owner: String,
    repo: String,
    refreshTick: Int = 0,
    onOpenDetail: (ReleaseItem) -> Unit = {},
    onCreate: () -> Unit = {},
) {
    val (host, token, _) = sessionInfo(sessionJson)
    val context = LocalContext.current
    // 缓存键先于状态声明：key 变化（切仓库/目录/分支）时列表自动清空，
    // 避免新请求失败时静默显示上一页内容
    val cacheKey = ListCache.key(owner, repo, ListCache.PAGE_RELEASES)
    var items by remember(cacheKey) { mutableStateOf<List<ReleaseItem>>(emptyList()) }
    var canPush by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryTick by remember { mutableStateOf(0) }
    // 哪个是「最新发布」（正式发布里被 GitHub 标为 Latest 的那条）
    var latestId by remember(cacheKey) { mutableStateOf<Long?>(null) }

    // Tab 保活 ⇒ 切走再切回不会重跑这个 effect。把「重新可见」的 tick 加进键：
    // 回来时重新校验一次（cachedFirst 命中 L1 就是同帧、TTL 内不联网）。
    // ⚠️ 已有数据时**不置加载态** —— 否则这次重新校验会把列表换成 loading 闪一下。
    val resumeTick = rememberPageResumeTick()
    LaunchedEffect(owner, repo, refreshTick, retryTick, resumeTick) {
        if (items.isEmpty()) loading = true
        error = null
        // 只有「本次确实直出了缓存」才在回源失败时静默保留旧内容；否则照常报错
        var shownStale = false
        val manager = SearchCacheManager(SearchCacheDatabase.getInstance(context).searchCacheDao())

        // ① 先直出缓存（含过期数据）
        if (refreshTick == 0 && retryTick == 0) {
            ListCache.readStale(manager, cacheKey)?.let { cached ->
                val parsed = parseReleases(cached)
                if (parsed.isNotEmpty()) {
                    items = parsed
                    shownStale = true
                    loading = false
                }
            }
        }

        // ② 回源刷新
        val json = RustBridge.getJson(host, token, "/repos/$owner/$repo/releases")
        if (json == null || json.startsWith("ERROR:")) {
            if (!shownStale) error = loadFailure(context.getString(R.string.nav_releases), json?.removePrefix("ERROR:"), owner, repo)
        } else {
            var parsed = parseReleases(json)
            // 列表接口对**刚发布**的 release 会返回空 assets（GitHub 多副本数据不一致，实测约 2.5
            // 小时后收敛；见 `releasesNeedingAssetBackfill` 的注释），而同一时刻单体接口已经是对的。
            // 只对这几条回源补齐 —— 否则「刚发完版想立刻装」这个最常见的动作恰好看到「没有附件」，
            // 而那正是最需要看到附件的时候。
            val need = releasesNeedingAssetBackfill(parsed)
            if (need.isNotEmpty()) {
                val filled = HashMap<Long, List<ReleaseAsset>>()
                for (item in need) {
                    val one = parseSingleRelease(
                        RustBridge.getJson(host, token, "/repos/$owner/$repo/releases/${item.id}"),
                    )
                    if (one != null && one.assets.isNotEmpty()) filled[item.id] = one.assets
                }
                if (filled.isNotEmpty()) {
                    // 补不上的保持原样：宁可少显示，也不要显示错的
                    parsed = parsed.map { if (filled.containsKey(it.id)) it.copy(assets = filled.getValue(it.id)) else it }
                }
            }
            items = parsed
            ListCache.write(manager, cacheKey, json)
        }
        // ③ 「最新发布」问权威端点：列表接口**每条记录里不带 latest 标记**，
        //    `/releases/latest` 才是 `make_latest` 的真实体现。拿不到（网络失败 / 引擎不可用）时
        //    退回「最新的非草稿非预发布」—— 那正是 make_latest 缺省时的规则，
        //    所以只在「有人显式改过 latest 归属」时不准，方向上不会错，也不会因此报错。
        latestId = RustBridge.latestReleaseId(host, token, owner, repo)
            ?: items.firstOrNull { !it.draft && !it.prerelease }?.id?.takeIf { it != 0L }
        // 写权限决定「新建发布」入口是否出现（缺失即视为无权限，保守）；不入缓存
        canPush = RustBridge.getRepoInfo(host, token, owner, repo)
            ?.takeIf { !it.startsWith("ERROR:") }
            ?.let { parseRepoInfo(it)?.canPush } ?: false
        loading = false
    }

    when {
        loading -> ListLoading()
        error != null -> ListError(error!!) { retryTick++ }
        else -> LazyColumn(Modifier.fillMaxSize()) {
            // 新建入口从「占满整行的描边空框」收成标题行右侧的圆形「+」。
            // 原来那个按钮没有任何信息，却永远压在列表最上面，进页面第一眼是个空框；
            // 收进标题行后入口还在（有写权限时才出现），但不再吃一整行。
            item {
                DetailSectionTitle(if (items.isEmpty()) stringResource(R.string.nav_releases) else stringResource(R.string.label_releases_count, items.size)) {
                    if (canPush) NewReleaseButton(onCreate)
                }
            }
            if (items.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.state_no_releases),
                        fontSize = 13.sp,
                        color = Primer.TextTertiary,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 56.dp),
                    )
                }
            } else {
                // release id 全局唯一；id 缺失（0）时退回 tag（仓库内唯一）兜底，避免重复 key
                items(items, key = { if (it.id != 0L) it.id else it.tag }) { item ->
                    ReleaseRow(item, isLatest = item.id != 0L && item.id == latestId) { onOpenDetail(item) }
                }
            }
        }
    }
}

/**
 * 「新建发布」入口：30dp 圆形「+」。
 *
 * 只做图标不做文字，是因为它旁边就是「发布 · N」标题 —— 位置本身已经说明了这个加号加的是什么，
 * 再写一遍「新建发布」是重复信息。填充用中性面 `Gray150`、图标用 `IconPrimary`，
 * 不抢蓝色（蓝色留给页面里真正的主链接）。
 */
@Composable
private fun NewReleaseButton(onClick: () -> Unit) {
    Box(
        Modifier.size(30.dp).clip(CircleShape).background(Primer.Gray150).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Add,
            contentDescription = stringResource(R.string.action_new_release),
            tint = Primer.IconPrimary,
            modifier = Modifier.size(17.dp),
        )
    }
}

/**
 * 发布条目。
 *
 * 版式对齐 GitHub 网页的 Releases 列表：**tag 是第一眼信息**（它是唯一稳定标识，
 * name 可能是空的或与 tag 重复），所以 tag 做成等宽胶囊放在最前；
 * 徽章紧挨着说明这条的性质（最新发布 / 预发布 / 草稿）；name 与元信息依次降一级。
 * 原来的版本把 name 放第一行、tag 混在灰色小字里 —— 一眼扫过去分不出哪条是哪个版本。
 */
@Composable
private fun ReleaseRow(item: ReleaseItem, isLatest: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ReleaseTagChip(item.tag)
                if (isLatest) {
                    Spacer(Modifier.width(7.dp))
                    ReleaseChip(stringResource(R.string.label_latest_release), Primer.SuccessTextStrong, Primer.SuccessSurface)
                }
                if (item.prerelease) {
                    Spacer(Modifier.width(7.dp))
                    ReleaseChip(stringResource(R.string.label_prerelease), Primer.AccentText, Primer.InfoSurfaceSoft)
                }
                if (item.draft) {
                    Spacer(Modifier.width(7.dp))
                    ReleaseChip(stringResource(R.string.label_draft), Primer.WarningTextStrong, Primer.WarningSurface)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                item.name,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = Primer.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                buildString {
                    append(shortTime(item.createdAt).resolve())
                    if (item.author.isNotBlank()) append(" · ${item.author}")
                    if (item.assets.isNotEmpty()) append(stringResource(R.string.suffix_asset_count, item.assets.size))
                },
                fontSize = 11.5.sp,
                color = Primer.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text("›", fontSize = 15.sp, color = Primer.TextTertiary)
    }
    Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(1.dp).background(Primer.Gray150))
}

/** 发布性质徽章（最新发布 / 预发布 / 草稿）。底色与文字色成对给，不写死。 */
@Composable
internal fun ReleaseChip(text: String, fg: Color, bg: Color) {
    Text(
        text,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        color = fg,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(bg)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/**
 * tag 胶囊（等宽）。
 *
 * 列表与详情页共用：tag 是发布唯一稳定的标识（name 可以是空的或与 tag 重复），
 * 两处必须长得一样，否则用户在列表认出的「那个 v1.2.0」到详情页就对不上了。
 */
@Composable
internal fun ReleaseTagChip(tag: String) {
    Text(
        tag,
        fontSize = 11.5.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        color = Primer.Blue500,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(6.dp))
            .background(Primer.Gray150).padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

// ── 星标/复刻/关注列表（全屏） ──

@Composable
fun PeopleListScreen(
    sessionJson: String,
    owner: String,
    repo: String,
    type: String, // "star" / "fork" / "watch"
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val (host, token, _) = sessionInfo(sessionJson)
    var users by remember { mutableStateOf<List<UserItem>>(emptyList()) }
    var forks by remember { mutableStateOf<List<ForkItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }

    val title = when (type) { "star" -> stringResource(R.string.label_stargazers); "fork" -> stringResource(R.string.action_fork); else -> stringResource(R.string.label_followers_list) }
    // per_page=100：GitHub 默认只给 30 条，而这一页没有翻页入口 —— 不写就是
    // 「第 31 个人永远看不到」且界面毫无提示（与 /user/starred 同一处理）。
    val path = when (type) {
        "star" -> "/repos/$owner/$repo/stargazers?per_page=100"
        "fork" -> "/repos/$owner/$repo/forks?per_page=100&sort=newest"
        else -> "/repos/$owner/$repo/subscribers?per_page=100"
    }

    LaunchedEffect(owner, repo, type, tick) {
        loading = true
        error = null
        val raw = RustBridge.getJson(host, token, path)
        when {
            raw == null -> error = context.getString(R.string.state_no_data_retry)
            // 失败与「真的没有人」必须分开：2026-07 起 GitHub 已把
            // `/stargazers`、`/subscribers` 限制为管理员与协作者可见，
            // 非协作者拿到的是 403 —— 再显示「暂无内容」就是在骗用户
            raw.startsWith("ERROR:") -> error = peopleListErrorText(type, raw)
            type == "fork" -> forks = parseForks(raw)
            else -> users = parseUsers(raw)
        }
        loading = false
    }

    Column(
        Modifier.fillMaxSize().background(Primer.BackgroundPrimary).statusBarsPadding().navigationBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                stringResource(R.string.action_back),
                tint = Primer.IconPrimary,
                modifier = Modifier.size(24.dp).iconTap { onBack() },
            )
            Spacer(Modifier.width(8.dp))
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
        }
        when {
            loading -> ListLoading()
            error != null -> ListError(error!!, onRetry = { tick++ })
            type == "fork" && forks.isEmpty() -> ListEmpty(stringResource(R.string.state_no_forks))
            type != "fork" && users.isEmpty() -> ListEmpty(stringResource(R.string.state_no_content))
            type == "fork" -> LazyColumn(Modifier.fillMaxSize()) { items(forks) { ForkRow(it) } }
            else -> LazyColumn(Modifier.fillMaxSize()) { items(users) { UserRow(it) } }
        }
    }
}

/**
 * 星标者 / 关注者 / 复刻列表的失败文案（纯函数，便于单测）。
 *
 * 存在的理由：这三个列表原先把一切失败都折叠成 null，界面统一显示「暂无内容」——
 * 403（GitHub 限制）、404（仓库没了）、429（限流）与「真的没有人」在用户眼里一模一样。
 * 2026 年 7 月起 `/stargazers`、`/subscribers` 已限制为**管理员与协作者**可见，
 * 这个歧义从「理论问题」变成了常态。
 */
internal fun peopleListErrorText(type: String, rawError: String): String {
    val subject = when (type) {
        "star" -> "星标者"
        "fork" -> "复刻列表"
        else -> "关注者"
    }
    val code = Regex("HTTP (\\d{3})").find(rawError)?.groupValues?.get(1)
    return when {
        rawError.contains("403") || code == "403" ->
            "GitHub 已把$subject 列表限制为仓库协作者可见，你没有权限查看。"
        rawError.contains("404") || code == "404" -> "仓库不存在或无权访问。"
        rawError.contains("429") || rawError.contains("rate limit", ignoreCase = true) ->
            "触发 GitHub 限流，请稍后再试。"
        else -> "$subject 加载失败：${rawError.removePrefix("ERROR:").take(120)}"
    }
}

@Composable
private fun UserRow(user: UserItem) {
    Row(Modifier.fillMaxWidth().padding(12.dp, 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(Primer.Blue500), contentAlignment = Alignment.Center) {
            if (user.avatarUrl != null) AsyncImage(model = user.avatarUrl, contentDescription = user.login, modifier = Modifier.size(32.dp).clip(CircleShape))
            else Text(user.login.take(1).uppercase(), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Text(user.login, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Primer.Blue500)
    }
}

@Composable
private fun ForkRow(fork: ForkItem) {
    Row(Modifier.fillMaxWidth().padding(12.dp, 16.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(Primer.Blue500), contentAlignment = Alignment.Center) {
            Text(fork.fullName.take(1).uppercase(), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(fork.fullName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Primer.Blue500)
            if (fork.description.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(fork.description, fontSize = 12.5.sp, color = Primer.TextSecondary, lineHeight = 17.sp)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fork.language?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(LanguageColors.of(it)))
                        Spacer(Modifier.width(4.dp))
                        Text(it, fontSize = 12.sp, color = Primer.TextSecondary)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Star, null, tint = Primer.IconSecondary, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(2.dp))
                    Text(fork.stars.toString(), fontSize = 12.sp, color = Primer.TextSecondary)
                }
            }
        }
    }
}

// ── 设置（入口列表 → 仓库设置决策页 / PR 一条龙） ──

/** PR 一条龙的提交信息初值（决策页里可改；只是默认，不再是写死的唯一值）。 */

/**
 * 该仓库在「文件页」留下的待提交草稿（仓库内相对路径）—— 一条龙的真实改动来源。
 *
 * 目录约定与文件页一致（`files/edit/single/{owner}/{repo}`，见 `RepositoryFileViewer.draftRoot()`）：
 * 用户在代码页编辑并**保存草稿** → 回到仓库页 ⋮ → 开 PR 一条龙，这里就能把清单接进去。
 * 只认已落盘的草稿：正在编辑、还没保存的那份只在文件页的内存里，本页看不到。
 * `.base`（草稿基准 sha）与 `.remote`（冲突副本）是旁挂文件，不是改动本身，排除。
 */
/**
 * 列表类页面的统一失败落点：**先记一条能定位的日志，再把原始错误交给 UI**。
 *
 * 打不开仓库（404/403）是最需要日志的场景 —— 用户只会说「某个仓库打不开」，
 * 而日志里能看出是哪个 owner/repo、哪种状态码、以及该怎么理解（见 [repoAccessHint]）。
 * 锚点：`私有仓库`（导出包 `report.md` 的锚点词典里有它）。
 */
/** 在系统浏览器里打开链接（失败即静默 —— 与本仓库其它 `ACTION_VIEW` 用法一致）。 */
internal fun openInBrowser(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)),
        )
    }
}

private fun loadFailure(page: String, error: String?, owner: String, repo: String): String {
    val raw = error ?: "加载失败"
    repoAccessHint(raw)?.let { hint ->
        Logger.warn(LogCategory.NETWORK, "私有仓库", "${page}页 $owner/$repo 打不开 —— $hint")
    }
    return raw
}

private fun pendingDraftPaths(context: android.content.Context, owner: String, repo: String): List<String> =
    runCatching {
        val root = java.io.File(context.getExternalFilesDir(null), "edit/single/$owner/$repo")
        if (!root.isDirectory) return@runCatching emptyList()
        root.walkTopDown()
            .filter { it.isFile && !it.name.endsWith(".base") && !it.name.endsWith(".remote") }
            .map { it.relativeTo(root).path }
            .sorted()
            .toList()
    }.getOrDefault(emptyList())

@Composable
fun RepositorySettingsContent(
    sessionJson: String,
    owner: String,
    repo: String,
    branches: List<String>,
    defaultBranch: String,
) {
    val context = LocalContext.current
    // 0=入口列表 1=仓库设置决策页 2=PR 一条龙
    var subPage by remember { mutableStateOf(0) }
    // 决策页的结果冒泡到这里：返回入口列表后仍然看得到「默认分支已切换为 x」这类反馈
    // （旧实现 onFeedback = {} 直接丢掉）。error=true 时用红字。
    var feedback by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    // 待提交草稿：重新可见时重扫（代码页存完草稿回到仓库页，这里要看到新清单）
    val resumeTick = rememberPageResumeTick()
    val changedFiles = remember(owner, repo, resumeTick) { pendingDraftPaths(context, owner, repo) }
    when (subPage) {
        1 -> {
            com.branchbase.ui.decision.RepoSettingScreen(
                sessionJson = sessionJson,
                owner = owner,
                repo = repo,
                branches = branches,
                defaultBranch = defaultBranch,
                onBack = { subPage = 0 },
                onFeedback = { msg, error -> feedback = msg to error },
            )
            return
        }
        2 -> {
            com.branchbase.ui.decision.PrOnestopScreen(
                sessionJson = sessionJson,
                owner = owner,
                repo = repo,
                baseBranch = defaultBranch,
                // 默认提交信息是**用户可见文案**（会进提交历史），所以走资源而不是常量：
                // 英文界面下应该提交英文说明，而不是把中文写进别人的仓库历史。
                commitMessage = context.getString(R.string.pr_default_commit_message),
                // 真实改动只能来自文件页草稿；一个都没有时一条龙第②步会明确拦住（不假装能开 PR）
                changedFiles = changedFiles,
                // 分支清单来自仓库页（同一份 branches），第①步据此查重名
                branches = branches,
                onBack = { subPage = 0 },
                onCreated = { msg ->
                    msg?.let { feedback = it to false }
                    subPage = 0
                },
            )
            return
        }
    }
    // 原先还有一条「许可证」占位行（enabled=false，点了没反应），已随占位清理移除
    val entries = listOf(
        Triple(stringResource(R.string.label_repo_settings_menu), 1, true),
        Triple(
            if (changedFiles.isEmpty()) {
                stringResource(R.string.label_pr_onestop_no_drafts)
            } else {
                stringResource(R.string.label_pr_onestop_with_drafts, changedFiles.size)
            },
            2,
            true,
        ),
    )
    LazyColumn(Modifier.fillMaxSize()) {
        // 决策页带回来的结果（成功 / 失败）就显示在这里 —— 沿用决策页同一条反馈行，不另造提示组件
        feedback?.let { (msg, error) -> item { FeedbackLine(msg, error = error) } }
        items(entries) { (name, target, enabled) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (enabled) Modifier.clickable { subPage = target } else Modifier)
                    .padding(14.dp, 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    name,
                    fontSize = 14.sp,
                    color = if (enabled) Primer.TextPrimary else Primer.TextTertiary,
                    modifier = Modifier.weight(1f),
                )
                Text("›", fontSize = 16.sp, color = Primer.TextTertiary)
            }
        }
    }
}