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
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
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
import androidx.compose.ui.graphics.toArgb
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
import com.branchbase.editor.BranchbaseCodeEditor
import com.branchbase.ui.decision.AuthorIdentityScreen
import com.branchbase.ui.decision.DraftInfo
import com.branchbase.ui.decision.DraftRecoverScreen
import com.branchbase.ui.decision.OfflineConflictScreen
import com.branchbase.ui.decision.SensitiveWarningScreen
import com.branchbase.ui.decision.StageCommitScreen
import com.branchbase.ui.decision.StageFile
import com.branchbase.ui.decision.parseSensitiveHits
import com.branchbase.ui.navigation.PageBackHandler
import com.branchbase.ui.profile.CommitMode
import com.branchbase.ui.profile.CommitModePickerDialog
import com.branchbase.ui.profile.commitMode
import com.branchbase.ui.profile.saveCommitMode
import com.branchbase.ui.theme.iconTap
import com.branchbase.ui.theme.CodeSyntax
import com.branchbase.ui.theme.LocalIsDarkTheme
import com.branchbase.ui.theme.Primer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 文件查看器（blob）：拉取 `contents/{path}` 的 base64 内容，解码后按行号展示。
 *
 * 文件查看（blob + 高亮 + 行号）。
 * 语法高亮当前为「等宽 + 行号」基础形态，全语言高亮后续接 syntect。
 */
@Composable
fun FileViewerScreen(
    sessionJson: String,
    owner: String,
    repo: String,
    path: String,
    highlightLines: String? = null,
    defaultBranch: String = "main",
    branches: List<String> = emptyList(),
    onOpenBranchManage: () -> Unit = {},
    onOpenLocalSync: () -> Unit = {},
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
    // true = 选完模式后继续提交（未配置时）；false = 只切换模式（面板就地改）
    var modePickerForCommit by remember { mutableStateOf(true) }
    // 本次编辑的提交模式覆盖（气泡面板就地切换，不必再进设置）
    var modeOverride by remember { mutableStateOf<CommitMode?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }

    // ── 决策页状态机 ──
    var page by remember { mutableStateOf<FilePage>(FilePage.None) }

    // 草稿（D3 隔离目录 files/edit/single/{owner}/{repo}/{path}）
    fun draftFile() = File(context.getExternalFilesDir(null), "edit/single/$owner/$repo/$path")

    /** 草稿的基准 sha 记录（离线冲突检测：提交前与远端当前 sha 比对）。 */
    fun draftBaseFile() = File(draftFile().absolutePath + ".base")

    fun saveDraft() = runCatching {
        draftFile().parentFile?.mkdirs()
        draftFile().writeText(draft)
        if (sha.isNotBlank()) draftBaseFile().writeText(sha)
    }.isSuccess

    fun clearDraft() = runCatching {
        draftFile().delete()
        draftBaseFile().delete()
    }.isSuccess

    fun loadDraft(): String? = runCatching { draftFile().takeIf { it.exists() }?.readText() }.getOrNull()

    fun loadDraftBaseSha(): String? =
        runCatching { draftBaseFile().takeIf { it.exists() }?.readText()?.trim() }.getOrNull()

    /**
     * 离线冲突检测（P2-4）。
     *
     * 草稿保存时记录了当时的远端 sha；提交前重新拉一次远端：
     * sha 变了说明离线期间别人改过同一文件 → 返回远端内容，交给冲突决策页。
     * 没有草稿基准（首次编辑）或网络失败时不拦截。
     *
     * 这里**刻意不走 PageCache**：冲突检测必须看远端当前状态，读缓存（最长 10 分钟前的）
     * 会漏判冲突。
     */
    suspend fun detectRemoteChange(): String? {
        val base = loadDraftBaseSha()?.takeIf { it.isNotBlank() } ?: return null
        val json = RustBridge.getJson(host, token, "/repos/$owner/$repo/contents/${encodePath(path)}")
            ?: return null
        if (json.startsWith("ERROR:")) return null
        val remoteSha = runCatching { JSONObject(json).optString("sha") }.getOrNull() ?: return null
        if (remoteSha == base) return null
        return parseFileContent(json)
    }

    /**
     * 文件内容缓存键（[p] 默认当前文件）。
     *
     * ref 用**空串**：内容是按 `GET /contents/{path}` 拉的，而该请求当前**不带 ref 参数**
     * （由服务端按仓库默认分支返回），所以键里只能写「实际请求的 ref」= 空串。
     * 不能用 PUT 里那个硬编码的 `"main"`，否则默认分支不是 main 的仓库会串键；
     * 也不该用 `defaultBranch` 参数——它并不参与这次请求。
     */
    fun fileCacheKey(p: String = path) = PageCache.fileKey(owner, repo, p, "")

    /** 缓存管理器：读路径（直出 / 回源）与写路径（提交后失效）共用。 */
    fun cacheManager() = SearchCacheManager(SearchCacheDatabase.getInstance(context).searchCacheDao())

    LaunchedEffect(owner, repo, path) {
        loading = true
        error = null
        // 本页没有手动刷新/重试入口（refreshTick/retryTick 留在仓库页），force 恒为 false；
        // 「提交后必须看到新内容」靠写操作成功后的 delete 失效来实现，而不是靠 force。
        val manager = cacheManager()
        val key = fileCacheKey()
        val encoded = encodePath(path)
        // 本次是否确实直出了缓存：空文件的内容也是 ""，所以用「解析成功」判定
        // （缓存里的值只会是回源成功的响应体，parseFileContent 失败才返回 ""）
        var shown = false
        var appliedJson: String? = null

        // ① 先直出缓存（含过期数据）：同一文件反复查看立即有内容
        PageCache.cachedFirst(manager, key, PageCache.TYPE_FILE)?.let { cached ->
            runCatching { parseFileContent(cached) }.getOrNull()?.let { text ->
                content = text
                sha = runCatching { JSONObject(cached).optString("sha") }.getOrDefault("")
                appliedJson = cached
                shown = true
                loading = false
            }
        }

        // ② 回源并写回（命中未过期缓存时 refresh 直接返回，不再联网）
        val json = PageCache.refresh(manager, key, PageCache.TYPE_FILE) {
            RustBridge.getJson(host, token, "/repos/$owner/$repo/contents/$encoded")
        }
        if (json == null) {
            // 只有「本次确实直出过缓存」才静默保留旧内容；否则保持原有错误提示
            if (!shown) error = "文件不存在或无法读取"
        } else if (json != appliedJson) {
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
            // 离线冲突检测：当前编辑文件若被他人改过 → 冲突决策页（P2-4）
            detectRemoteChange()?.let { remote ->
                page = FilePage.Conflict(local = draft, remote = remote)
                return@launch
            }
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
                // 远端内容已变：本次提交涉及的每个文件都失效缓存（当前文件也在 files 里），
                // 否则提交后返回再进来还是旧内容（TTL 10 分钟）
                val manager = cacheManager()
                files.forEach { (rel, _) -> manager.delete(fileCacheKey(rel)) }
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
            // 离线冲突检测：远端已变 → 冲突决策页（P2-4）
            detectRemoteChange()?.let { remote ->
                page = FilePage.Conflict(local = draft, remote = remote)
                return@launch
            }
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
                // 远端内容已变：失效文件内容缓存（TTL 10 分钟），否则提交后返回再进来还是旧内容
                cacheManager().delete(fileCacheKey())
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
            val repoDir = File(
                com.branchbase.core.LocalRepos.rootFor(
                    context,
                    com.branchbase.core.AccountStore.currentLogin(context),
                ),
                repo,
            ).absolutePath
            val sha = RustBridge.gitCommit(
                repoDir, message,
                prefs.getString("commit.author.name", "Branchbase") ?: "Branchbase",
                prefs.getString("commit.author.email", "branchbase@users.noreply.github.com") ?: "branchbase@users.noreply.github.com",
            )
            if (sha != null) {
                com.branchbase.ui.task.TaskStore.success(context, taskId, "已提交 $sha")
                clearDraft()
                editing = false
                // 本地提交后文件已变：失效内容缓存。本地提交的两条入口（doLocalCommit 直接提交 /
                // Identity 页补完身份后提交）最终都汇入这里，所以落点放在这个成功分支。
                cacheManager().delete(fileCacheKey())
                feedback = "已提交（本地 git · $sha）"
            } else {
                com.branchbase.ui.task.TaskStore.fail(context, taskId, "提交失败（引擎不可用）")
                feedback = "提交失败（引擎不可用）"
            }
        }
    }

    // 提交（③本地 git：写入工作树 + 身份检查 + commit）
    fun doLocalCommit() {
        // 与单文件/多文件路径保持一致：提交信息必填。
        // （此前缺校验，libgit2 允许空 message，会推出一个 subject 为空的提交）
        if (commitMsg.isBlank()) { feedback = "请输入提交信息"; return }
        scope.launch {
            // 按账号隔离的目录：repos/{login}/{repo}（此前硬编码 repos/{owner}/{repo}，
            // 只在 owner == login 时恰好成立，换别人的仓库会找不到）
            val repoDir = File(
                com.branchbase.core.LocalRepos.rootFor(
                    context,
                    com.branchbase.core.AccountStore.currentLogin(context),
                ),
                repo,
            )
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

    // 提交入口：按提交模式分发（UI 切换）；modeOverride = 本次编辑的就地覆盖
    fun onCommitClick() {
        when (modeOverride ?: commitMode(context)) {
            null -> { modePickerForCommit = true; showModePicker = true }
            CommitMode.SINGLE_FILE -> proceedWithScan { doCommitSingle() }
            CommitMode.MULTI_FILE -> proceedWithScan {
                page = FilePage.Stage(collectStagedFiles())
            }
            CommitMode.LOCAL_REPO -> proceedWithScan { doLocalCommit() }
        }
    }

    val effectiveMode = modeOverride ?: commitMode(context)
    val localGit = rememberLocalRepoGitState(repo)
    var bubbleExpanded by remember { mutableStateOf(false) }

    // 返回键先消费本页自己的三层覆盖（从内到外）：
    // 1. 决策页（敏感内容 / 暂存提交 / 身份 / 草稿恢复 / 离线冲突）—— 叠在正文之上的全屏层，
    //    它们的返回箭头都是「回编辑态」；
    // 2. Git 悬浮球展开态 —— 铺了全屏透明遮罩，返回键应当是收起面板；
    // 3. 编辑态 —— 底部有「取消」，返回键同样应该是取消编辑。
    // 以前这一页没有 handler：决策页按系统返回会把**整个文件页**一起关掉，
    // 与页面内的返回箭头不是同一条路径（用户刚做的选择随页面一起消失）。
    PageBackHandler(page != FilePage.None || bubbleExpanded || editing) {
        when {
            page != FilePage.None -> page = FilePage.None
            bubbleExpanded -> bubbleExpanded = false
            else -> editing = false
        }
    }

    Box(Modifier.fillMaxSize()) {

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
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Primer.IconPrimary, modifier = Modifier.size(24.dp).iconTap { onBack() })
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
            // 正文区用 :editor 模块的代码编辑器（等宽 + 行号 + 无边框），底色与只读预览
            // 同一块（CodeSyntax.CodeBg）—— 切换「查看 ↔ 编辑」时不会闪出一块异色区域。
            // 以前这里是一个 OutlinedTextField：四周一圈方框、没有行号，比只读预览还难看。
            // weight(1f)：根 Column 里已有头部 Row 作为兄弟节点
            Column(Modifier.weight(1f).fillMaxWidth()) {
                BranchbaseCodeEditor(
                    text = draft,
                    onTextChange = { draft = it },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    // 三档主题（跟随系统 / 浅色 / 深色）的生效值在这里，不能用 isSystemInDarkTheme()
                    darkTheme = LocalIsDarkTheme.current,
                    backgroundColor = CodeSyntax.CodeBg.toArgb(),
                )
                // 提交区：与页面头部同色，把编辑区上下夹住（反馈文字 / 按钮的底色也就有了着落）
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Primer.BackgroundPrimary)
                        .padding(12.dp),
                ) {
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
            }
        } else {
            when {
                loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Primer.Blue500)
                }
                error != null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(error!!, fontSize = 13.sp, color = Primer.TextTertiary)
                }
                else -> {
                    val highlightRange = remember(highlightLines) { parseLineRange(highlightLines) }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
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

        // Git 悬浮球：**绑定「本地仓库（Git）」模式**（判定见 showGitBubble）——
        // 单文件 / 多文件两种模式不显示。它管的都是本地仓库的事（工作树 / 提交 / 分支同步），
        // 模式不对时挂着只会白挡正文（编辑态的提交 / 保存草稿 / 取消在底部本来就有一份）。
        if (showGitBubble(effectiveMode)) {
            GitBubblePanel(
                actions = buildList {
                    if (editing) {
                        add(
                            GitBubbleAction("commit", "提交", Icons.Filled.Check, enabled = !submitting) {
                                onCommitClick()
                            },
                        )
                        add(
                            GitBubbleAction("draft", "保存草稿", Icons.Filled.Save) {
                                feedback = if (saveDraft()) "草稿已保存" else "草稿保存失败"
                            },
                        )
                        add(GitBubbleAction("cancel", "取消编辑", Icons.Filled.Close) { editing = false })
                    } else if (!loading && error == null) {
                        add(
                            GitBubbleAction("edit", "编辑", Icons.Filled.Edit) {
                                editing = true
                                draft = content
                                // 草稿恢复检测（P2-1）：存在未提交草稿且与远端不同 → 决策页
                                val saved = loadDraft()
                                if (saved != null && saved != content) {
                                    page = FilePage.Draft(listOf(DraftInfo(path, "本地草稿", saved.lines().size)))
                                }
                            },
                        )
                    }
                    add(
                        GitBubbleAction(
                            "mode",
                            "提交模式：${effectiveMode?.label ?: "未设置"}",
                            Icons.Filled.Settings,
                        ) {
                            modePickerForCommit = false
                            showModePicker = true
                        },
                    )
                    add(GitBubbleAction("branch", "分支管理", Icons.Filled.AccountTree) { onOpenBranchManage() })
                    add(
                        GitBubbleAction(
                            "sync",
                            if (localGit.exists) "本地分支同步" else "本地仓库未拉取",
                            Icons.Filled.Sync,
                            badge = when {
                                localGit.diverged -> "分叉"
                                localGit.ahead > 0 -> "↑${localGit.ahead}"
                                localGit.behind > 0 -> "↓${localGit.behind}"
                                else -> null
                            },
                            enabled = localGit.exists,
                        ) { onOpenLocalSync() },
                    )
                },
                expanded = bubbleExpanded,
                onExpandedChange = { bubbleExpanded = it },
                title = localGit.summary(),
                // 顶部停靠：编辑态的底部是「提交信息 + 按钮」，面板停右上角避免遮挡
                alignment = Alignment.TopEnd,
                edgePadding = androidx.compose.foundation.layout.PaddingValues(end = 12.dp, top = 52.dp),
                handleBadge = when {
                    localGit.diverged -> "!"
                    localGit.dirtyCount > 0 -> localGit.dirtyCount.toString()
                    localGit.ahead > 0 -> localGit.ahead.toString()
                    else -> null
                },
            )
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
        is FilePage.Conflict -> {
            OfflineConflictScreen(
                fileName = path.substringAfterLast('/'),
                localContent = p.local,
                remoteContent = p.remote,
                onBack = { page = FilePage.None },
                onChoose = { choice ->
                    page = FilePage.None
                    when (choice) {
                        // 保留本地：重新以远端最新 sha 为基准继续提交
                        "keep" -> scope.launch {
                            // 远端已变 → 缓存里的旧内容已不可信：先失效（即使紧随的提交失败也不会
                            // 让用户回来看到冲突前的旧内容），提交成功后新内容会在下次进入时写回
                            cacheManager().delete(fileCacheKey())
                            sha = runCatching {
                                JSONObject(
                                    RustBridge.getJson(host, token, "/repos/$owner/$repo/contents/${encodePath(path)}")
                                        ?: "{}",
                                ).optString("sha")
                            }.getOrDefault(sha)
                            doCommitSingle()
                        }
                        // 放弃本地，载入远端
                        "remote" -> {
                            // 采用远端版本：旧缓存（冲突前的版本）已失效，否则返回再进来会直出旧内容
                            scope.launch { cacheManager().delete(fileCacheKey()) }
                            draft = p.remote
                            content = p.remote
                            clearDraft()
                            editing = false
                            feedback = "已载入远端版本，本地草稿已删除"
                        }
                        // 复制远端为新文件：本地草稿保留，远端另存一份
                        else -> {
                            val side = File(draftFile().absolutePath + ".remote")
                            runCatching {
                                side.parentFile?.mkdirs()
                                side.writeText(p.remote)
                            }
                            feedback = "远端版本已另存为 ${side.name}"
                        }
                    }
                },
            )
            return
        }
        FilePage.None -> Unit
    }

    // 提交模式选择弹窗（未配置时选完即提交；面板里改模式则只切换）
    if (showModePicker) {
        CommitModePickerDialog(
            onDismiss = { showModePicker = false },
            onConfirm = { mode ->
                saveCommitMode(context, mode)
                modeOverride = mode
                showModePicker = false
                if (modePickerForCommit) {
                    onCommitClick()
                } else {
                    feedback = "提交模式已改为「${mode.label}」（本次编辑立即生效）"
                }
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
    /** 离线冲突：本地草稿基于的远端 sha 已变化 */
    data class Conflict(val local: String, val remote: String) : FilePage
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