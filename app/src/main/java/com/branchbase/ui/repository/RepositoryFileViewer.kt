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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.core.RustBridge
import com.branchbase.ui.decision.AuthorIdentityScreen
import com.branchbase.ui.decision.DraftInfo
import com.branchbase.ui.decision.DraftRecoverScreen
import com.branchbase.ui.decision.SensitiveWarningScreen
import com.branchbase.ui.decision.StageCommitScreen
import com.branchbase.ui.decision.StageFile
import com.branchbase.ui.decision.parseSensitiveHits
import com.branchbase.ui.profile.CommitMode
import com.branchbase.ui.profile.CommitModePickerDialog
import com.branchbase.ui.profile.commitMode
import com.branchbase.ui.profile.saveCommitMode
import com.branchbase.ui.theme.CodeSyntax
import com.branchbase.ui.theme.Primer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 文件查看器（blob）：拉取 `contents/{path}` 的 base64 内容，解码后按行号展示。
 *
 * 对齐 `docs/repository-overview-wireframe.md` 的「文件查看（blob + 高亮 + 行号）」。
 * 语法高亮当前为「等宽 + 行号」基础形态，全语言高亮后续接 syntect（见 third-party-components.md）。
 */
@Composable
fun FileViewerScreen(
    sessionJson: String,
    owner: String,
    repo: String,
    path: String,
    highlightLines: String? = null,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val host = remember(sessionJson) { runCatching { JSONObject(sessionJson).optString("host", "github.com") }.getOrDefault("github.com") }
    val token = remember(sessionJson) { runCatching { JSONObject(sessionJson).optJSONObject("token")?.optString("access_token").orEmpty() }.getOrDefault("") }

    var content by remember { mutableStateOf("") }
    var sha by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var commitMsg by remember { mutableStateOf("") }
    var showModePicker by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }

    // ── 决策页状态机（对齐 docs/decision-pages-gap.md） ──
    var page by remember { mutableStateOf<FilePage>(FilePage.None) }

    // 草稿（D3 隔离目录 files/edit/single/{owner}/{repo}/{path}）
    fun draftFile() = File(context.getExternalFilesDir(null), "edit/single/$owner/$repo/$path")

    fun saveDraft() = runCatching {
        draftFile().parentFile?.mkdirs()
        draftFile().writeText(draft)
    }.isSuccess

    fun clearDraft() = runCatching { draftFile().delete() }.isSuccess

    fun loadDraft(): String? = runCatching { draftFile().takeIf { it.exists() }?.readText() }.getOrNull()

    LaunchedEffect(owner, repo, path) {
        loading = true
        error = null
        val encoded = encodePath(path)
        val json = RustBridge.getJson(host, token, "/repos/$owner/$repo/contents/$encoded")
        if (json == null || json.startsWith("ERROR:")) {
            error = "文件不存在或无法读取"
        } else {
            content = parseFileContent(json)
            sha = runCatching { JSONObject(json).optString("sha") }.getOrDefault("")
        }
        loading = false
    }

    /** 提交前敏感扫描（P0-4）：命中 → 警告页；否则执行动作。 */
    fun proceedWithScan(action: () -> Unit) {
        val hits = RustBridge.scanSensitive(draft)?.let { parseSensitiveHits(it) } ?: emptyList()
        if (hits.isNotEmpty()) page = FilePage.Sensitive(hits) else action()
    }

    /** 该仓库的草稿根目录（`edit/single/{owner}/{repo}`，D3 隔离）。 */
    fun draftRoot() = File(context.getExternalFilesDir(null), "edit/single/$owner/$repo")

    /**
     * 收集该仓库下所有待提交草稿（多文件模式的数据来源）。
     *
     * 含当前正在编辑的文件 —— 它的内容可能还只在内存里（未落盘为草稿）。
     */
    fun collectStagedFiles(): List<StageFile> {
        val root = draftRoot()
        val out = LinkedHashMap<String, StageFile>()
        if (root.exists()) {
            root.walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = f.relativeTo(root).path
                out[rel] = StageFile(rel, "M", size = "${f.length()} B", checked = true)
            }
        }
        if (draft.isNotBlank() && !out.containsKey(path)) {
            out[path] = StageFile(path, "M", checked = true)
        }
        return out.values.sortedBy { it.path }
    }

    /**
     * 多文件模式：一次提交多个文件（Git Data API，只产生一个 commit）。
     *
     * 与单文件模式（[doCommitSingle] 逐个 PUT /contents）的区别就在这里。
     */
    fun doBatchCommit(message: String, selectedPaths: List<String>) {
        if (message.isBlank()) { feedback = "请输入提交信息"; return }
        if (selectedPaths.isEmpty()) { feedback = "请至少勾选一个文件"; return }
        scope.launch {
            submitting = true
            feedback = null
            val root = draftRoot()
            val files = selectedPaths.mapNotNull { rel ->
                val text = if (rel == path && draft.isNotBlank()) {
                    draft
                } else {
                    runCatching { File(root, rel).takeIf { it.exists() }?.readText() }.getOrNull()
                }
                text?.let { rel to it }
            }
            if (files.isEmpty()) {
                feedback = "没有可提交的内容"
                submitting = false
                return@launch
            }

            val taskId = com.branchbase.ui.task.TaskStore.start(
                context,
                com.branchbase.ui.task.TaskKind.COMMIT,
                "提交 ${files.size} 个文件到 $owner/$repo",
            )
            val result = RustBridge.commitFiles(host, token, owner, repo, "main", message, files)
            if (result != null && !result.startsWith("ERROR:")) {
                com.branchbase.ui.task.TaskStore.success(
                    context, taskId, "已提交 ${files.size} 个文件 · ${result.take(7)}",
                )
                // 提交成功的草稿清理掉
                files.forEach { (rel, _) -> runCatching { File(root, rel).delete() } }
                if (files.any { it.first == path }) { content = draft; editing = false }
                feedback = "已提交 ${files.size} 个文件"
            } else {
                val reason = result?.removePrefix("ERROR:") ?: "提交失败"
                com.branchbase.ui.task.TaskStore.fail(context, taskId, reason)
                feedback = "提交失败：$reason"
            }
            submitting = false
        }
    }

    // 提交（①单文件提交 PUT contents）
    fun doCommitSingle() {
        if (commitMsg.isBlank()) { feedback = "请输入提交信息"; return }
        scope.launch {
            submitting = true
            feedback = null
            val taskId = com.branchbase.ui.task.TaskStore.start(context, com.branchbase.ui.task.TaskKind.COMMIT, "提交 $path")
            val result = RustBridge.putContents(host, token, owner, repo, path, commitMsg, draft, sha, "main")
            if (result != null && !result.startsWith("ERROR:")) {
                com.branchbase.ui.task.TaskStore.success(context, taskId, "PUT /contents 成功")
            } else {
                com.branchbase.ui.task.TaskStore.fail(context, taskId, result?.removePrefix("ERROR:") ?: "提交失败")
            }
            submitting = false
            if (result != null && !result.startsWith("ERROR:")) {
                content = draft
                editing = false
                clearDraft()
                feedback = "已提交"
            } else {
                feedback = "提交失败"
            }
        }
    }

    // 执行本地 git commit（identity 已就绪）
    fun doGitCommit(message: String) {
        scope.launch {
            val taskId = com.branchbase.ui.task.TaskStore.start(context, com.branchbase.ui.task.TaskKind.COMMIT, "本地提交 $path")
            val prefs = context.getSharedPreferences("branchbase", android.content.Context.MODE_PRIVATE)
            val repoDir = File(context.getExternalFilesDir(null), "repos/$owner/$repo").absolutePath
            val sha = RustBridge.gitCommit(
                repoDir, message,
                prefs.getString("commit.author.name", "Branchbase") ?: "Branchbase",
                prefs.getString("commit.author.email", "branchbase@users.noreply.github.com") ?: "branchbase@users.noreply.github.com",
            )
            if (sha != null) {
                com.branchbase.ui.task.TaskStore.success(context, taskId, "已提交 $sha")
                clearDraft()
                editing = false
                feedback = "已提交（本地 git · $sha）"
            } else {
                com.branchbase.ui.task.TaskStore.fail(context, taskId, "提交失败（引擎不可用）")
                feedback = "提交失败（引擎不可用）"
            }
        }
    }

    // 提交（③本地 git：写入工作树 + 身份检查 + commit）
    fun doLocalCommit() {
        scope.launch {
            val repoDir = File(context.getExternalFilesDir(null), "repos/$owner/$repo")
            if (!File(repoDir, ".git").exists()) {
                feedback = "本地仓库不存在：请先在「设置 → 本地仓库」拉取"
                return@launch
            }
            val target = File(repoDir, path)
            val wrote = withContext(Dispatchers.IO) {
                runCatching { target.parentFile?.mkdirs(); target.writeText(draft); true }.getOrDefault(false)
            }
            if (!wrote) { feedback = "写入工作树失败"; return@launch }
            val prefs = context.getSharedPreferences("branchbase", android.content.Context.MODE_PRIVATE)
            if (prefs.getString("commit.author.name", null) == null || prefs.getString("commit.author.email", null) == null) {
                page = FilePage.Identity(commitMsg)
            } else {
                doGitCommit(commitMsg)
            }
        }
    }

    // 提交入口：按提交模式分发（UI 切换）
    fun onCommitClick() {
        when (commitMode(context)) {
            null -> showModePicker = true
            CommitMode.SINGLE_FILE -> proceedWithScan { doCommitSingle() }
            CommitMode.MULTI_FILE -> proceedWithScan {
                page = FilePage.Stage(collectStagedFiles())
            }
            CommitMode.LOCAL_REPO -> proceedWithScan { doLocalCommit() }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(CodeSyntax.CodeBg)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // 头部（返回 + 文件名 + 编辑）
        Row(
            Modifier
                .fillMaxWidth()
                .background(Primer.BackgroundPrimary)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Primer.IconPrimary, modifier = Modifier.size(24.dp).clickable { onBack() })
            Spacer(Modifier.width(8.dp))
            Text(
                path.substringAfterLast('/'),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Primer.TextPrimary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (!editing && error == null && !loading) {
                Text("编辑", fontSize = 14.sp, color = Primer.Blue500, modifier = Modifier.clickable {
                    editing = true
                    draft = content
                    // 草稿恢复检测（P2-1）：存在未提交草稿且与远端不同 → 决策页
                    val saved = loadDraft()
                    if (saved != null && saved != content) {
                        page = FilePage.Draft(listOf(DraftInfo(path, "本地草稿", saved.lines().size)))
                    }
                })
            }
        }

        if (editing) {
            // 编辑模式
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = commitMsg,
                    onValueChange = { commitMsg = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("提交信息（必填）", fontSize = 13.sp, color = Primer.TextTertiary) },
                )
                Spacer(Modifier.height(8.dp))
                if (submitting) Text("提交中…", fontSize = 12.sp, color = Primer.TextTertiary)
                feedback?.let { Text(it, fontSize = 12.sp, color = if (it == "已提交") Primer.Green500 else Primer.Red500) }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = { editing = false }, modifier = Modifier.weight(1f)) { Text("取消") }
                    TextButton(
                        onClick = { if (saveDraft()) feedback = "草稿已保存" else feedback = "草稿保存失败" },
                        modifier = Modifier.weight(1f),
                    ) { Text("保存草稿") }
                    Button(onClick = { onCommitClick() }, enabled = !submitting, modifier = Modifier.weight(1f)) { Text("提交") }
                }
            }
        } else {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Primer.Blue500)
                }
                error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(error!!, fontSize = 13.sp, color = Primer.TextTertiary)
                }
                else -> {
                    val highlightRange = remember(highlightLines) { parseLineRange(highlightLines) }
                    LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(content.lines()) { i, line ->
                            val lineNo = i + 1
                            val highlighted = highlightRange?.contains(lineNo) == true
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .then(if (highlighted) Modifier.background(CodeSyntax.MatchBg) else Modifier),
                            ) {
                                Text(
                                    lineNo.toString(),
                                    fontSize = 11.sp,
                                    color = CodeSyntax.LineNo,
                                    textAlign = TextAlign.End,
                                    modifier = Modifier.width(34.dp).padding(end = 10.dp),
                                )
                                Text(
                                    line.ifEmpty { " " },
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF24292F),
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ── 决策页分发（覆盖主界面，处理完回主流程） ──
    when (val p = page) {
        is FilePage.Sensitive -> {
            SensitiveWarningScreen(
                hits = p.hits,
                onBack = { page = FilePage.None },
                onProceed = {
                    page = FilePage.None
                    // 已确认内容可公开：跳过扫描直接执行当前模式的提交
                    when (commitMode(context)) {
                        CommitMode.SINGLE_FILE -> doCommitSingle()
                        CommitMode.MULTI_FILE -> doBatchCommit(commitMsg, collectStagedFiles().map { it.path })
                        CommitMode.LOCAL_REPO -> doLocalCommit()
                        null -> Unit
                    }
                },
            )
            return
        }
        is FilePage.Stage -> {
            StageCommitScreen(
                repoName = "$owner/$repo",
                files = p.files,
                mode = CommitMode.MULTI_FILE,
                onBack = { page = FilePage.None },
                onPickMode = { /* 已固化 */ },
                onCommit = { message, selected ->
                    page = FilePage.None
                    doBatchCommit(message, selected)
                },
            )
            return
        }
        is FilePage.Identity -> {
            AuthorIdentityScreen(
                suggestedName = "",
                suggestedEmail = "@users.noreply.github.com",
                onBack = { page = FilePage.None },
                onConfirm = { name, email, save ->
                    if (save) {
                        context.getSharedPreferences("branchbase", android.content.Context.MODE_PRIVATE)
                            .edit().putString("commit.author.name", name).putString("commit.author.email", email).apply()
                    }
                    page = FilePage.None
                    doGitCommit(p.message)
                },
            )
            return
        }
        is FilePage.Draft -> {
            DraftRecoverScreen(
                drafts = p.drafts,
                onBack = { page = FilePage.None },
                onRecover = {
                    page = FilePage.None
                    loadDraft()?.let { draft = it }
                },
                onDiscard = {
                    page = FilePage.None
                    clearDraft()
                    draft = content
                },
                onViewRemote = {
                    page = FilePage.None
                    clearDraft()
                    editing = false
                    draft = ""
                },
            )
            return
        }
        FilePage.None -> Unit
    }

    // 提交模式选择弹窗（未配置时）
    if (showModePicker) {
        CommitModePickerDialog(
            onDismiss = { showModePicker = false },
            onConfirm = { mode ->
                saveCommitMode(context, mode)
                showModePicker = false
                onCommitClick()
            },
        )
    }
}

/** 文件查看器页内决策状态机。 */
private sealed interface FilePage {
    data object None : FilePage
    data class Sensitive(val hits: List<com.branchbase.ui.decision.SensitiveHit>) : FilePage
    data class Stage(val files: List<StageFile>) : FilePage
    data class Identity(val message: String) : FilePage
    data class Draft(val drafts: List<DraftInfo>) : FilePage
}

/** 解析行号锚点（如 "L12-L34"、"L12"）为闭区间 [start..end]，非法返回 null。 */
private fun parseLineRange(s: String?): IntRange? {
    if (s.isNullOrBlank()) return null
    val t = s.removePrefix("L").removePrefix("l")
    if (t.isBlank()) return null
    return if (t.contains('-')) {
        val parts = t.split('-', limit = 2)
        val a = parts[0].trim().toIntOrNull() ?: return null
        val b = parts[1].trim().toIntOrNull() ?: return null
        minOf(a, b)..maxOf(a, b)
    } else {
        t.trim().toIntOrNull()?.let { it..it }
    }
}