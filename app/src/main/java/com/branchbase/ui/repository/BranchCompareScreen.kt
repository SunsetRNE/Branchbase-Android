package com.branchbase.ui.repository

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.cache.PageCache
import com.branchbase.cache.SearchCacheDatabase
import com.branchbase.cache.SearchCacheManager
import com.branchbase.core.RustBridge
import com.branchbase.ui.theme.iconTap
import com.branchbase.ui.theme.Primer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 单个文件默认展开的代码片段行数上限（超出需手动「展开全部」）。 */
private const val SNIPPET_PREVIEW_LINES = 120

/**
 * 分支对比页：显示两个分支的**代码片段差异**。
 *
 * 文件对比（Compare branches）：
 * - 对比头：`比较 base ← head` + 领先/落后 + 文件数
 * - 文件行：状态标签 + 路径 + `+N -M`
 * - 代码片段：unified diff 逐行渲染，增行 `#E6FFEC` / 删行 `#FFEBE9`、
 *   行号列 + 前缀列、11.5sp 等宽（与网页端一致）
 *
 * GitHub 边界：单次最多 300 个文件；超大 diff 不返回 `patch`（显示「无代码片段」提示）。
 */
@Composable
fun BranchCompareScreen(
    sessionJson: String,
    owner: String,
    repo: String,
    initialBase: String,
    initialHead: String,
    onBack: () -> Unit,
    onOpenFile: (path: String) -> Unit,
) {
    val context = LocalContext.current
    val host = remember(sessionJson) {
        runCatching { org.json.JSONObject(sessionJson).optString("host", "github.com") }.getOrDefault("github.com")
    }
    val token = remember(sessionJson) {
        runCatching { org.json.JSONObject(sessionJson).optJSONObject("token")?.optString("access_token").orEmpty() }
            .getOrDefault("")
    }

    var branches by remember { mutableStateOf<List<String>>(emptyList()) }
    var base by remember(initialBase) { mutableStateOf(initialBase) }
    var head by remember(initialHead) { mutableStateOf(initialHead) }
    var baseMenu by remember { mutableStateOf(false) }
    var headMenu by remember { mutableStateOf(false) }

    var result by remember { mutableStateOf<CompareResult?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showAllLines by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showCommits by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }

    // 分支名列表：与分支管理页共用 branchListKey（同一份服务端数据），进页面先直出再回源
    LaunchedEffect(owner, repo, reloadKey) {
        val manager = SearchCacheManager(SearchCacheDatabase.getInstance(context).searchCacheDao())
        val cacheKey = PageCache.branchListKey(owner, repo)
        // 「刷新」按钮同时刷新分支名列表：忽略缓存强制回源
        val force = reloadKey > 0

        PageCache.cachedFirst(manager, cacheKey, PageCache.TYPE_DETAIL, force)?.let { cached ->
            val names = withContext(Dispatchers.IO) { parseBranches(cached).map { b -> b.name } }
            if (names.isNotEmpty()) branches = names
        }
        PageCache.refresh(manager, cacheKey, PageCache.TYPE_DETAIL, force) {
            RustBridge.listBranches(host, token, owner, repo)
        }?.let { json ->
            val names = withContext(Dispatchers.IO) { parseBranches(json).map { b -> b.name } }
            if (names.isNotEmpty()) branches = names
        }
    }

    LaunchedEffect(owner, repo, base, head, reloadKey) {
        if (base.isBlank() || head.isBlank() || base == head) {
            result = null
            loading = false
            error = if (base == head) "请选择两个不同的分支" else null
            return@LaunchedEffect
        }
        loading = true
        error = null
        expanded = emptySet()
        // 手动刷新：忽略缓存强制回源
        val force = reloadKey > 0
        val manager = SearchCacheManager(SearchCacheDatabase.getInstance(context).searchCacheDao())
        // 键含 base 与 head：切分支即换键，不会复用另一对分支的旧结果
        val cacheKey = PageCache.compareKey(owner, repo, base, head)
        // 「本次是否已拿到可展示数据」：直出或回源任一成功即为 true
        var shown = false

        // ① 先直出（含过期数据）：返回再进、切回看过的分支对都立即有内容
        PageCache.cachedFirst(manager, cacheKey, PageCache.TYPE_DETAIL, force)?.let { cached ->
            val cachedResult = withContext(Dispatchers.IO) { parseCompareResult(cached) }
            if (cachedResult != null) {
                result = cachedResult
                // 默认展开第一个文件，进入页面即可看到代码片段
                cachedResult.files.firstOrNull()?.let { expanded = setOf(it.filename) }
                shown = true
                loading = false
            }
        }

        // ② 回源刷新（缓存未过期时直接复用，不重复联网）
        val json = PageCache.refresh(manager, cacheKey, PageCache.TYPE_DETAIL, force) {
            RustBridge.compareBranches(host, token, owner, repo, base, head)
        }
        val parsed = withContext(Dispatchers.IO) { parseCompareResult(json) }
        if (parsed != null) {
            result = parsed
            shown = true
            // 默认展开第一个文件，进入页面即可看到代码片段
            parsed.files.firstOrNull()?.let { expanded = setOf(it.filename) }
        }
        // 与改造前一致：既无直出又回源失败 → 清空并给出原错误文案
        if (!shown) {
            result = null
            error = "对比失败：无法读取 $base...$head"
        }
        loading = false
    }

    Column(
        Modifier.fillMaxSize().background(Primer.BackgroundPrimary)
            .statusBarsPadding().navigationBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Primer.IconPrimary,
                modifier = Modifier.size(24.dp).iconTap { onBack() },
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("分支对比", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
                Text("$owner/$repo", fontSize = 11.sp, color = Primer.TextTertiary, maxLines = 1)
            }
            Text(
                "刷新", fontSize = 12.5.sp, color = Primer.Blue500,
                modifier = Modifier.clickable(enabled = !loading) { reloadKey++ },
            )
        }

        // 选择器：base ← head（可一键互换）
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BranchChip("比较基准", base, branches, baseMenu, { baseMenu = !baseMenu }, { base = it; baseMenu = false }, Modifier.weight(1f))
            Box(Modifier.padding(horizontal = 6.dp)) {
                Icon(
                    Icons.Filled.SwapVert, "互换", tint = Primer.IconPrimary,
                    modifier = Modifier.size(20.dp).iconTap {
                        val t = base; base = head; head = t
                    },
                )
            }
            BranchChip("对比分支", head, branches, headMenu, { headMenu = !headMenu }, { head = it; headMenu = false }, Modifier.weight(1f))
        }

        Spacer(Modifier.height(10.dp))

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Primer.Blue500)
            }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(error!!, fontSize = 13.sp, color = Primer.TextTertiary, textAlign = TextAlign.Center)
            }
            result != null -> CompareBody(
                result = result!!,
                base = base,
                head = head,
                expanded = expanded,
                onToggleFile = { name ->
                    expanded = if (name in expanded) expanded - name else expanded + name
                },
                showAllLines = showAllLines,
                onShowAll = { name -> showAllLines = showAllLines + name },
                showCommits = showCommits,
                onToggleCommits = { showCommits = !showCommits },
                onOpenFile = onOpenFile,
            )
        }
    }
}

@Composable
private fun CompareBody(
    result: CompareResult,
    base: String,
    head: String,
    expanded: Set<String>,
    onToggleFile: (String) -> Unit,
    showAllLines: Set<String>,
    onShowAll: (String) -> Unit,
    showCommits: Boolean,
    onToggleCommits: () -> Unit,
    onOpenFile: (String) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        // 对比头
        item {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(8.dp)).background(Primer.Gray150).padding(12.dp),
            ) {
                Text(
                    "比较 $base ← $head",
                    fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace, color = Primer.TextPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        result.identical -> "两个分支内容一致，没有差异"
                        result.diverged -> "分叉：领先 ${result.aheadBy} 个提交，落后 ${result.behindBy} 个"
                        result.aheadBy > 0 -> "领先 ${result.aheadBy} 个提交"
                        else -> "落后 ${result.behindBy} 个提交"
                    },
                    fontSize = 11.sp, color = Primer.TextSecondary,
                )
                if (result.files.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${result.files.size} 个文件变更",
                        fontSize = 11.sp, color = Primer.TextTertiary,
                    )
                }
            }
        }

        // 提交列表（默认折叠）
        if (result.commits.isNotEmpty()) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
                        .clickable { onToggleCommits() },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "提交（${result.commits.size}）",
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(if (showCommits) "收起" else "展开", fontSize = 12.sp, color = Primer.Blue500)
                }
            }
            if (showCommits) {
                items(result.commits) { c ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            c.shortSha, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                            color = Primer.Blue500, modifier = Modifier.width(62.dp),
                        )
                        Text(
                            c.subject.ifBlank { "(无提交信息)" },
                            fontSize = 12.5.sp, color = Primer.TextPrimary,
                            maxLines = 1, modifier = Modifier.weight(1f),
                        )
                        Text(c.date, fontSize = 10.5.sp, color = Primer.TextTertiary)
                    }
                }
            }
        }

        // 文件差异
        if (result.files.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (result.identical) "没有文件差异" else "该对比没有返回文件差异（可能超过 300 个文件上限）",
                        fontSize = 12.5.sp, color = Primer.TextTertiary,
                    )
                }
            }
        } else {
            items(result.files, key = { it.filename }) { file ->
                FileDiffBlock(
                    file = file,
                    expanded = file.filename in expanded,
                    showAll = file.filename in showAllLines,
                    onToggle = { onToggleFile(file.filename) },
                    onShowAll = { onShowAll(file.filename) },
                    onOpenFile = { onOpenFile(file.filename) },
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun FileDiffBlock(
    file: CompareFile,
    expanded: Boolean,
    showAll: Boolean,
    onToggle: () -> Unit,
    onShowAll: () -> Unit,
    onOpenFile: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
                .clickable { onToggle() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (expanded) "▾" else "▸",
                fontSize = 11.sp, color = Primer.TextTertiary,
            )
            Spacer(Modifier.width(6.dp))
            StatusTag(file)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    file.name,
                    fontSize = 12.5.sp, fontFamily = FontFamily.Monospace,
                    color = Primer.TextPrimary, maxLines = 1,
                )
                if (file.dir.isNotEmpty()) {
                    Text(file.dir, fontSize = 10.5.sp, color = Primer.TextTertiary, maxLines = 1)
                }
            }
            Text(
                "+${file.additions}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                color = Primer.Green500,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "-${file.deletions}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                color = Primer.Red500,
            )
        }

        if (expanded) {
            if (!file.hasPatch) {
                Text(
                    "该文件差异过大，GitHub 未返回代码片段；可打开文件查看当前内容",
                    fontSize = 11.5.sp, color = Primer.TextTertiary, lineHeight = 17.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            } else {
                val lines = remember(file.patch) { parseUnifiedDiff(file.patch) }
                val shown = if (showAll) lines else lines.take(SNIPPET_PREVIEW_LINES)
                Column(Modifier.fillMaxWidth().background(Primer.Gray100)) {
                    shown.forEach { line -> DiffLineRow(line) }
                    if (!showAll && lines.size > shown.size) {
                        Box(
                            Modifier.fillMaxWidth().clickable { onShowAll() }.padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "展开剩余 ${lines.size - shown.size} 行",
                                fontSize = 12.sp, color = Primer.Blue500,
                            )
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    "打开文件",
                    fontSize = 12.sp, color = Primer.Blue500,
                    modifier = Modifier.clickable { onOpenFile() },
                )
            }
        }
    }
}

/** 单行 diff：行号列 + 前缀列 + 代码（增/删底色对齐网页端）。 */
@Composable
private fun DiffLineRow(line: DiffLine) {
    if (line.kind == DiffLineKind.Hunk) {
        Text(
            line.text,
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace,
            color = Primer.TextTertiary,
            modifier = Modifier.fillMaxWidth().background(Primer.Gray150).padding(horizontal = 16.dp, vertical = 4.dp),
        )
        return
    }
    val bg = when (line.kind) {
        DiffLineKind.Add -> Color(0xFFE6FFEC)
        DiffLineKind.Remove -> Color(0xFFFFEBE9)
        else -> Color.Transparent
    }
    val prefix = when (line.kind) {
        DiffLineKind.Add -> "+"
        DiffLineKind.Remove -> "-"
        else -> " "
    }
    Row(Modifier.fillMaxWidth().background(bg)) {
        Text(
            line.oldLine?.toString().orEmpty(),
            fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Primer.TextTertiary,
            textAlign = TextAlign.End,
            modifier = Modifier.width(30.dp).padding(end = 4.dp),
        )
        Text(
            line.newLine?.toString().orEmpty(),
            fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Primer.TextTertiary,
            textAlign = TextAlign.End,
            modifier = Modifier.width(30.dp).padding(end = 6.dp),
        )
        Text(
            prefix,
            fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = Primer.TextTertiary,
            modifier = Modifier.width(12.dp),
        )
        Text(
            line.text.ifEmpty { " " },
            fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF24292F),
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
    }
}

@Composable
private fun StatusTag(file: CompareFile) {
    val (fg, bg) = when (file.status) {
        "added" -> Primer.Green500 to Color(0xFFE6FFEC)
        "removed" -> Primer.Red500 to Color(0xFFFFEBE9)
        "renamed" -> Primer.Purple500 to Color(0xFFF3EEFF)
        else -> Primer.TextSecondary to Primer.Gray150
    }
    Box(
        Modifier.width(38.dp).clip(RoundedCornerShape(6.dp)).background(bg).padding(vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(file.statusLabel, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = fg)
    }
}

@Composable
private fun BranchChip(
    label: String,
    value: String,
    branches: List<String>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(label, fontSize = 10.5.sp, color = Primer.TextTertiary)
        Spacer(Modifier.height(4.dp))
        Box {
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, Primer.Border, RoundedCornerShape(8.dp))
                    .clickable { onToggle() }
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    value.ifBlank { "请选择" },
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    color = Primer.TextPrimary, maxLines = 1, modifier = Modifier.weight(1f),
                )
                Text("▾", fontSize = 11.sp, color = Primer.TextTertiary)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { onToggle() }) {
                branches.forEach { b ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                b, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace,
                                color = if (b == value) Primer.Blue500 else Primer.TextPrimary,
                            )
                        },
                        onClick = { onPick(b) },
                    )
                }
            }
        }
    }
}
