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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.branchbase.core.RustBridge
import com.branchbase.ui.theme.Primer

/**
 * Issue / PR 详情页（列表 → 详情贯通）。
 *
 * 对齐 `docs/repository-detail-wireframe.md`：
 * Issue：标题 + 状态胶囊 + 标签 + 正文 + 评论 timeline。
 * PR：标题 + 状态 + 分支合并信息 + 描述 + 文件变更（+/- 统计）。
 * 正文当前为纯文本（markdown 原始），后续可经 markdown API 转 HTML 复用 ReadmeRenderer。
 */

@Composable
fun IssueDetailScreen(
    sessionJson: String,
    owner: String,
    repo: String,
    number: Long,
    onBack: () -> Unit,
) {
    val (host, token, login) = sessionInfo(sessionJson)
    var detail by remember { mutableStateOf<IssueDetail?>(null) }
    var comments by remember { mutableStateOf<List<CommentItem>>(emptyList()) }
    var bodyHtml by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(owner, repo, number) {
        loading = true
        val d = RustBridge.getJson(host, token, "/repos/$owner/$repo/issues/$number")
            ?.takeIf { !it.startsWith("ERROR:") }
            ?.let { parseIssueDetail(it) }
        if (d != null) {
            detail = d
            RustBridge.getJson(host, token, "/repos/$owner/$repo/issues/$number/comments")
                ?.takeIf { !it.startsWith("ERROR:") }
                ?.let { comments = parseComments(it) }
            if (d.body.isNotBlank()) bodyHtml = markdownToHtml(host, token, d.body)
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
                item { IssueHead(detail!!) }
                if (bodyHtml != null) item { ReadmeWebView(bodyHtml!!, host, owner, repo, "main", login, token, onLinkClick = {}) }
                else if (detail!!.body.isNotBlank()) item { CommentBody(detail!!.body, detail!!.author, detail!!.createdAt) }
                items(comments) { c -> CommentCard(c) }
            }
        }
    }
}

@Composable
fun PullDetailScreen(
    sessionJson: String,
    owner: String,
    repo: String,
    number: Long,
    onBack: () -> Unit,
) {
    val (host, token, login) = sessionInfo(sessionJson)
    var detail by remember { mutableStateOf<PullDetail?>(null) }
    var files by remember { mutableStateOf<List<PullFile>>(emptyList()) }
    var bodyHtml by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(owner, repo, number) {
        loading = true
        val d = RustBridge.getJson(host, token, "/repos/$owner/$repo/pulls/$number")
            ?.takeIf { !it.startsWith("ERROR:") }
            ?.let { parsePullDetail(it) }
        if (d != null) {
            detail = d
            RustBridge.getJson(host, token, "/repos/$owner/$repo/pulls/$number/files")
                ?.takeIf { !it.startsWith("ERROR:") }
                ?.let { files = parsePullFiles(it) }
            if (d.body.isNotBlank()) bodyHtml = markdownToHtml(host, token, d.body)
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
        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Primer.IconPrimary, modifier = Modifier.size(24.dp).clickable { onBack() })
        Spacer(Modifier.width(8.dp))
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
    }
}

@Composable
private fun IssueHead(d: IssueDetail) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(d.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary, lineHeight = 22.sp)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(stateColor(d.state)))
            Spacer(Modifier.width(6.dp))
            Text(d.state, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = stateColor(d.state))
            Spacer(Modifier.width(8.dp))
            Text("${d.author} 于 ${shortTime(d.createdAt)} 打开", fontSize = 12.sp, color = Primer.TextTertiary)
        }
        if (d.labels.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row {
                d.labels.forEach { label ->
                    Box(
                        Modifier.padding(end = 6.dp).clip(RoundedCornerShape(10.dp)).background(Primer.Blue500).padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    }
                }
            }
        }
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
    var detail by remember { mutableStateOf<CommitDetail?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(owner, repo, sha) {
        loading = true
        RustBridge.getJson(host, token, "/repos/$owner/$repo/commits/$sha")
            ?.takeIf { !it.startsWith("ERROR:") }
            ?.let { detail = parseCommitDetail(it) }
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