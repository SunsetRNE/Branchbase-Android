package com.branchbase.ui.repository

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.branchbase.cache.PageCache
import com.branchbase.cache.SearchCacheDatabase
import com.branchbase.cache.SearchCacheManager
import com.branchbase.core.RustBridge
import com.branchbase.ui.theme.iconTap
import com.branchbase.ui.theme.Primer
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * PR / 提交 详情页（列表 → 详情贯通）。
 *
 * Issue 详情已独立到 `IssueDetailScreen.kt`（时间线、反应、输入器等体量较大），
 * 本文件保留 PR 与提交详情，以及两个页面共用的页头 / 居中态 / diff 渲染。
 *
 * 结构：
 * PR：标题 + 状态 + 分支合并信息 + 描述 + 文件变更（+/- 统计）。
 */

@Composable
fun PullDetailScreen(
    sessionJson: String,
    owner: String,
    repo: String,
    number: Long,
    onBack: () -> Unit,
) {
    val (host, token, login) = sessionInfo(sessionJson)
    val context = LocalContext.current
    var detail by remember { mutableStateOf<PullDetail?>(null) }
    var files by remember { mutableStateOf<List<PullFile>>(emptyList()) }
    var bodyHtml by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    /**
     * 应用一份 PR 详情 JSON：解析 + 正文 markdown 渲染（与改造前的渲染路径完全一致）。
     * 解析不出详情时返回 false —— 此时这一份不能算「直出成功」。
     */
    suspend fun applyDetail(json: String): Boolean {
        val d = runCatching { parsePullDetail(json) }.getOrNull() ?: return false
        detail = d
        if (d.body.isNotBlank()) bodyHtml = markdownToHtml(host, token, d.body)
        return true
    }

    LaunchedEffect(owner, repo, number) {
        loading = true
        // 与 Issue 详情同一套：本页无手动刷新/重试入口，force 恒为 false（PageCache 默认值）
        val manager = SearchCacheManager(SearchCacheDatabase.getInstance(context).searchCacheDao())
        val detailKey = PageCache.pullKey(owner, repo, number)
        val filesKey = PageCache.pullFilesKey(owner, repo, number)
        // 失败态由 `detail == null` 表达（原「加载失败」文案不变），故不需要额外的 error 状态
        var appliedJson: String? = null

        // ① 先直出缓存（含过期数据）
        PageCache.cachedFirst(manager, detailKey, PageCache.TYPE_DETAIL)?.let { cached ->
            if (applyDetail(cached)) {
                appliedJson = cached
                loading = false
            }
        }
        // 文件变更一并直出（空列表与「请求失败」的渲染结果相同，无需区分）
        PageCache.cachedFirst(manager, filesKey, PageCache.TYPE_DETAIL)?.let { cached ->
            runCatching { parsePullFiles(cached) }.getOrNull()?.let { f -> files = f }
        }

        // ② 回源并写回：详情与文件变更并行（原来是详情成功后再串行拉文件）
        coroutineScope {
            val detailJob = async {
                PageCache.refresh(manager, detailKey, PageCache.TYPE_DETAIL) {
                    RustBridge.getJson(host, token, "/repos/$owner/$repo/pulls/$number")
                }
            }
            val filesJob = async {
                PageCache.refresh(manager, filesKey, PageCache.TYPE_DETAIL) {
                    RustBridge.getJson(host, token, "/repos/$owner/$repo/pulls/$number/files")
                }
            }
            // 回源失败（null）时什么都不做：本次直出过就静默保留旧内容；没直出则 detail 仍为 null → 加载失败
            val detailJson = detailJob.await()
            if (detailJson != null && detailJson != appliedJson) applyDetail(detailJson)
            filesJob.await()?.let { json ->
                runCatching { parsePullFiles(json) }.getOrNull()?.let { f -> files = f }
            }
        }
        loading = false
    }

    Column(
        Modifier.fillMaxSize().background(Primer.BackgroundPrimary).statusBarsPadding().navigationBarsPadding(),
    ) {
        DetailHeader("#$number", onBack)
        when {
            loading -> CenterLoading()
            detail == null -> CenterText("加载失败")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                item { PullHead(detail!!) }
                if (bodyHtml != null) item { ReadmeWebView(bodyHtml!!, host, owner, repo, "main", login, token, onLinkClick = {}) }
                else if (detail!!.body.isNotBlank()) item { CommentBody(detail!!.body, detail!!.author, detail!!.createdAt) }
                item { Text("文件变更 (${files.size})", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary, modifier = Modifier.padding(16.dp, 14.dp, 16.dp, 6.dp)) }
                items(files) { f -> PullFileRow(f) }
            }
        }
    }
}

// ── 组件 ──

@Composable
private fun DetailHeader(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Primer.IconPrimary, modifier = Modifier.size(24.dp).iconTap { onBack() })
        Spacer(Modifier.width(8.dp))
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
    }
}

@Composable
private fun PullHead(d: PullDetail) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(d.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary, lineHeight = 22.sp)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(stateColor(d.state)))
            Spacer(Modifier.width(6.dp))
            Text(d.state, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = stateColor(d.state))
        }
        Spacer(Modifier.height(6.dp))
        Text("${d.author} 想将 ${d.headRef} 合并到 ${d.baseRef}", fontSize = 12.sp, color = Primer.TextTertiary)
    }
}

@Composable
private fun CommentBody(body: String, author: String, createdAt: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(6.dp)).background(Primer.Gray150).padding(12.dp),
    ) {
        Text(body, fontSize = 13.sp, color = Primer.TextPrimary, lineHeight = 20.sp)
        Spacer(Modifier.height(6.dp))
        Text("$author · ${shortTime(createdAt)}", fontSize = 11.5.sp, color = Primer.TextTertiary)
    }
}

@Composable
private fun CommentCard(c: CommentItem) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(Primer.Blue500), contentAlignment = Alignment.Center) {
            if (c.avatarUrl != null) AsyncImage(model = c.avatarUrl, contentDescription = c.author, modifier = Modifier.size(28.dp).clip(CircleShape))
            else Text(c.author.take(1).uppercase(), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Column(
            Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(Primer.Gray100).padding(10.dp),
        ) {
            Text(c.author, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
            Spacer(Modifier.height(3.dp))
            Text(c.body, fontSize = 13.sp, color = Primer.TextPrimary, lineHeight = 19.sp)
            Spacer(Modifier.height(4.dp))
            Text(shortTime(c.createdAt), fontSize = 11.sp, color = Primer.TextTertiary)
        }
    }
}

@Composable
private fun PullFileRow(f: PullFile) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(f.filename, fontSize = 13.sp, color = Primer.TextPrimary, modifier = Modifier.weight(1f), maxLines = 1)
        if (f.additions > 0) Text("+${f.additions}", fontSize = 12.sp, color = Color(0xFF1A7F37), fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        if (f.deletions > 0) Text("-${f.deletions}", fontSize = 12.sp, color = Color(0xFFCF222E), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CenterLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Primer.Blue500)
    }
}

@Composable
private fun CenterText(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 13.sp, color = Primer.TextTertiary)
    }
}

@Composable
fun CommitDetailScreen(
    sessionJson: String,
    owner: String,
    repo: String,
    sha: String,
    onBack: () -> Unit,
) {
    val (host, token, _) = sessionInfo(sessionJson)
    val context = LocalContext.current
    var detail by remember { mutableStateOf<CommitDetail?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(owner, repo, sha) {
        loading = true
        // 本页无手动刷新/重试入口，force 恒为 false（PageCache 默认值）
        val manager = SearchCacheManager(SearchCacheDatabase.getInstance(context).searchCacheDao())
        val key = PageCache.commitKey(owner, repo, sha)

        // ① 先直出缓存（含过期数据）：从提交列表返回再进来立即有内容
        PageCache.cachedFirst(manager, key, PageCache.TYPE_DETAIL)?.let { cached ->
            runCatching { parseCommitDetail(cached) }.getOrNull()?.let { d ->
                detail = d
                loading = false
            }
        }

        // ② 回源并写回（命中未过期缓存时 refresh 直接返回，不再联网）；
        // 失败（null）时不动 detail：直出过就静默保留，没直出则仍为 null → 加载失败
        PageCache.refresh(manager, key, PageCache.TYPE_DETAIL) {
            RustBridge.getJson(host, token, "/repos/$owner/$repo/commits/$sha")
        }?.let { json ->
            runCatching { parseCommitDetail(json) }.getOrNull()?.let { d -> detail = d }
        }
        loading = false
    }

    Column(
        Modifier.fillMaxSize().background(Primer.BackgroundPrimary).statusBarsPadding().navigationBarsPadding(),
    ) {
        DetailHeader(sha, onBack)
        when {
            loading -> CenterLoading()
            detail == null -> CenterText("加载失败")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                item { CommitHead(detail!!) }
                items(detail!!.files) { f -> CommitFileBlock(f) }
            }
        }
    }
}

@Composable
private fun CommitHead(d: CommitDetail) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(d.message, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary, lineHeight = 22.sp)
        Spacer(Modifier.height(6.dp))
        Text("${d.author} · ${d.sha} · ${shortTime(d.date)}", fontSize = 12.sp, color = Primer.TextTertiary)
    }
}

@Composable
private fun CommitFileBlock(f: CommitFile) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().background(Primer.Gray150).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(f.filename, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary, modifier = Modifier.weight(1f), maxLines = 1)
            if (f.additions > 0) Text("+${f.additions}", fontSize = 12.sp, color = Color(0xFF1A7F37), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            if (f.deletions > 0) Text("-${f.deletions}", fontSize = 12.sp, color = Color(0xFFCF222E), fontWeight = FontWeight.SemiBold)
        }
        if (f.patch.isNotBlank()) DiffLines(f.patch)
    }
}

@Composable
private fun DiffLines(patch: String) {
    Column(Modifier.fillMaxWidth()) {
        patch.lines().forEach { line ->
            val isAdd = line.startsWith("+") && !line.startsWith("+++")
            val isDel = line.startsWith("-") && !line.startsWith("---")
            val isHunk = line.startsWith("@@")
            val color = when {
                isAdd -> Color(0xFF1A7F37)
                isDel -> Color(0xFFCF222E)
                isHunk -> Color(0xFF0969DA)
                line.startsWith("+++") || line.startsWith("---") -> Color(0xFF57606A)
                else -> Color(0xFF24292F)
            }
            val bg = when {
                isAdd -> Color(0xFFE6FFEC)
                isDel -> Color(0xFFFFEBE9)
                else -> Color.Transparent
            }
            Text(
                line.ifEmpty { " " },
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = color,
                modifier = Modifier.fillMaxWidth().background(bg).padding(horizontal = 4.dp),
            )
        }
    }
}