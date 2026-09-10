package com.branchbase.ui.repository

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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Star
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
import com.branchbase.cache.ListCache
import com.branchbase.cache.SearchCacheDatabase
import com.branchbase.cache.SearchCacheManager
import com.branchbase.core.RustBridge
import com.branchbase.ui.theme.LanguageColors
import com.branchbase.ui.theme.Primer
import org.json.JSONObject

// ── 通用 ──

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

/** 失败态 + 重试按钮（对应原型「失败」态，与「空态」区分） */
@Composable
internal fun ListError(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("加载失败", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
        Spacer(Modifier.height(4.dp))
        Text(message, fontSize = 12.sp, color = Primer.TextTertiary)
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.clip(CircleShape).background(Primer.Blue500).clickable { onRetry() }.padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text("重试", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        }
    }
}

internal fun shortTime(iso: String): String = runCatching {
    val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }
    val e = f.parse(iso)?.time ?: return@runCatching iso
    val m = (System.currentTimeMillis() - e) / 60000
    when {
        m < 1 -> "刚刚"
        m < 60 -> "$m 分钟前"
        m < 1440 -> "${m / 60} 小时前"
        else -> "${m / 1440} 天前"
    }
}.getOrDefault(iso)

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

    LaunchedEffect(owner, repo, path, branch, refreshTick, retryTick) {
        loading = true
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
            if (!shownStale) error = json?.removePrefix("ERROR:") ?: "加载失败"
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
            items.isEmpty() -> ListEmpty("空目录")
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

    LaunchedEffect(owner, repo, refreshTick, retryTick) {
        loading = true
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
            if (!shownStale) error = json?.removePrefix("ERROR:") ?: "加载失败"
        } else {
            items = parseIssues(json)
            ListCache.write(manager, cacheKey, json)
        }
        loading = false
    }

    when {
        loading -> ListLoading()
        error != null -> ListError(error!!) { retryTick++ }
        items.isEmpty() -> ListEmpty("暂无 Issue")
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
            Text("#${item.number} · ${item.author} · ${shortTime(item.createdAt)}", fontSize = 11.5.sp, color = Primer.TextTertiary)
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

    LaunchedEffect(owner, repo, branch, refreshTick, retryTick) {
        loading = true
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
            if (!shownStale) error = json?.removePrefix("ERROR:") ?: "加载失败"
        } else {
            items = parsePulls(json)
            ListCache.write(manager, cacheKey, json)
        }
        loading = false
    }

    when {
        loading -> ListLoading()
        error != null -> ListError(error!!) { retryTick++ }
        items.isEmpty() -> ListEmpty("暂无拉取请求")
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
            Text("#${item.number} · ${item.author} · ${shortTime(item.createdAt)}", fontSize = 11.5.sp, color = Primer.TextTertiary)
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

    LaunchedEffect(owner, repo, branch, refreshTick, retryTick) {
        loading = true
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
            if (!shownStale) error = json?.removePrefix("ERROR:") ?: "加载失败"
        } else {
            items = parseCommits(json)
            ListCache.write(manager, cacheKey, json)
        }
        loading = false
    }

    when {
        loading -> ListLoading()
        error != null -> ListError(error!!) { retryTick++ }
        items.isEmpty() -> ListEmpty("暂无提交")
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
            Text("${item.author} · ${item.sha} · ${shortTime(item.date)}", fontSize = 11.5.sp, color = Primer.TextTertiary)
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

    LaunchedEffect(owner, repo, branch, refreshTick, retryTick) {
        loading = true
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
            if (!shownStale) error = json?.removePrefix("ERROR:") ?: "加载失败"
        } else {
            items = parseWorkflows(json)
            ListCache.write(manager, cacheKey, json)
        }
        loading = false
    }

    when {
        loading -> ListLoading()
        error != null -> ListError(error!!) { retryTick++ }
        items.isEmpty() -> ListEmpty("暂无工作流")
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
        Text(item.state, fontSize = 11.sp, color = Primer.TextTertiary)
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

    LaunchedEffect(owner, repo, refreshTick, retryTick) {
        loading = true
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
            if (!shownStale) error = json?.removePrefix("ERROR:") ?: "加载失败"
        } else {
            items = parseReleases(json)
            ListCache.write(manager, cacheKey, json)
        }
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
            if (canPush) {
                item {
                    Box(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, Primer.Border, RoundedCornerShape(8.dp))
                            .clickable { onCreate() }
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("＋ 新建发布", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Primer.Blue500)
                    }
                }
            }
            if (items.isEmpty()) {
                item { ListEmpty("暂无发布") }
            } else {
                // release id 全局唯一；id 缺失（0）时退回 tag（仓库内唯一）兜底，避免重复 key
                items(items, key = { if (it.id != 0L) it.id else it.tag }) { ReleaseRow(it) { onOpenDetail(it) } }
            }
        }
    }
}

@Composable
private fun ReleaseRow(item: ReleaseItem, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(12.dp, 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Filled.LocalOffer, null, tint = Primer.Blue500, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.name,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Primer.TextPrimary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (item.draft) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "草稿",
                        fontSize = 10.sp,
                        color = Color(0xFF9A6700),
                        modifier = Modifier.clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFFFFF8E5)).padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
                if (item.prerelease) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "预发布",
                        fontSize = 10.sp,
                        color = Color(0xFF0A4E9B),
                        modifier = Modifier.clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFFE6F1FF)).padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    append("${item.tag} · ${shortTime(item.createdAt)}")
                    if (item.assets.isNotEmpty()) append(" · ${item.assets.size} 个附件")
                },
                fontSize = 11.5.sp,
                color = Primer.TextTertiary,
            )
        }
        Text("›", fontSize = 15.sp, color = Primer.TextTertiary)
    }
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
    val (host, token, _) = sessionInfo(sessionJson)
    var users by remember { mutableStateOf<List<UserItem>>(emptyList()) }
    var forks by remember { mutableStateOf<List<ForkItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    val title = when (type) { "star" -> "星标者"; "fork" -> "复刻"; else -> "关注者" }
    val path = when (type) {
        "star" -> "/repos/$owner/$repo/stargazers"
        "fork" -> "/repos/$owner/$repo/forks"
        else -> "/repos/$owner/$repo/subscribers"
    }

    LaunchedEffect(owner, repo, type) {
        loading = true
        RustBridge.getJson(host, token, path)?.takeIf { !it.startsWith("ERROR:") }?.let { json ->
            if (type == "fork") forks = parseForks(json) else users = parseUsers(json)
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
                "返回",
                tint = Primer.IconPrimary,
                modifier = Modifier.size(24.dp).clickable { onBack() },
            )
            Spacer(Modifier.width(8.dp))
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
        }
        when {
            loading -> ListLoading()
            type == "fork" && forks.isEmpty() -> ListEmpty("暂无复刻")
            type != "fork" && users.isEmpty() -> ListEmpty("暂无内容")
            type == "fork" -> LazyColumn(Modifier.fillMaxSize()) { items(forks) { ForkRow(it) } }
            else -> LazyColumn(Modifier.fillMaxSize()) { items(users) { UserRow(it) } }
        }
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

@Composable
fun RepositorySettingsContent(
    sessionJson: String,
    owner: String,
    repo: String,
    branches: List<String>,
    defaultBranch: String,
) {
    // 0=入口列表 1=仓库设置决策页 2=PR 一条龙
    var subPage by remember { mutableStateOf(0) }
    when (subPage) {
        1 -> {
            com.branchbase.ui.decision.RepoSettingScreen(
                sessionJson = sessionJson,
                owner = owner,
                repo = repo,
                branches = branches,
                defaultBranch = defaultBranch,
                onBack = { subPage = 0 },
                onFeedback = {},
            )
            return
        }
        2 -> {
            com.branchbase.ui.decision.PrOnestopScreen(
                sessionJson = sessionJson,
                owner = owner,
                repo = repo,
                baseBranch = defaultBranch,
                commitMessage = "chore: 通过 Branchbase 提交",
                changedFiles = emptyList(),
                onBack = { subPage = 0 },
                onCreated = { subPage = 0 },
            )
            return
        }
    }
    // 原先还有一条「许可证」占位行（enabled=false，点了没反应），已随占位清理移除
    val entries = listOf(
        Triple("仓库设置（默认分支 / 分支管理 / 危险区）", 1, true),
        Triple("开 PR 一条龙（新建分支 + 开 PR）", 2, true),
    )
    LazyColumn(Modifier.fillMaxSize()) {
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