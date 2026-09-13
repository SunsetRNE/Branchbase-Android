package com.branchbase.ui.repository

import android.content.Context
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.core.RustBridge
import com.branchbase.downloader.DownloadActions
import com.branchbase.downloader.DownloadRequest
import com.branchbase.downloader.DownloadStatus
import com.branchbase.downloader.DownloadTask
import com.branchbase.downloader.DownloaderRuntime
import com.branchbase.ui.log.Logger
import com.branchbase.ui.theme.Primer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 发布（Releases）三件套：列表条目在 `RepositoryListScreens.kt`，本文件放详情页与编辑页。
 *
 * ## 这一版把「发布的性质」摆到了台面上
 *
 * GitHub 的 release 有三种性质，此前界面上只有两个互不相干的开关（草稿 / 预发布），
 * 而且**「最新发布」这个最常被引用的状态在 App 里根本没有概念**：
 *
 * | 性质 | `draft` | `prerelease` | 能否是 latest |
 * |------|---------|--------------|---------------|
 * | 正式发布 | false | false | **可以** |
 * | 预发布 | false | true | 不可以 |
 * | 草稿 | true | — | 不可以 |
 *
 * 官方原文：*"Drafts and prereleases cannot be set as latest."*（见 REST
 * `POST /repos/{owner}/{repo}/releases` 的 `make_latest` 字段）。
 * 所以「正式发布」不是一个装饰性标签，它等于「唯一有资格占据 Latest 的那一档」——
 * 编辑页因此改成三段式单选，而不是两个可以同时勾上、含义又重叠的开关。
 *
 * 另：`make_latest` 的取值是**字符串** `"true"` / `"false"` / `"legacy"`，不是布尔。
 */

// ───────────────────────────────── 详情页 ─────────────────────────────────

/**
 * 发布详情页：头部（tag + 性质 + 名字 + 署名）、附件、changelog。
 *
 * 与上一版的差别主要是「别再给每个附件画一个框」：附件原来是一条一个描边圆角盒，
 * 三个附件就是三个盒子叠着，把版面切得很碎。现在整组只靠**分隔线**分区，
 * 描边留给真正需要边界的输入框（编辑页的正文）。
 */
@Composable
fun ReleaseDetailScreen(
    sessionJson: String,
    owner: String,
    repo: String,
    release: ReleaseItem,
    canEdit: Boolean,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDeleted: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val host = remember(sessionJson) { sessionInfo(sessionJson).first }
    val token = remember(sessionJson) { sessionInfo(sessionJson).second }

    var html by remember(release.id) { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    var feedback by remember { mutableStateOf<String?>(null) }
    // 是不是仓库当前的「最新发布」—— 详情页也要标，否则从列表点进来这个信息就丢了
    var isLatest by remember(release.id) { mutableStateOf(false) }
    // 附件下载状态：与系统通知同源（任务表在 :downloader 里），
    // 所以退出页面再回来、甚至应用退到后台，进度都与通知栏一致
    val downloadTasks by DownloaderRuntime.tasks.collectAsState()

    LaunchedEffect(release.id) {
        html = if (release.body.isBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) { RustBridge.renderMarkdown(host, token, release.body) }
                ?.takeIf { !it.startsWith("ERROR:") }
        }
        isLatest = release.id != 0L &&
            RustBridge.latestReleaseId(host, token, owner, repo) == release.id
    }

    LaunchedEffect(feedback) {
        val msg = feedback ?: return@LaunchedEffect
        feedback = null
        snackbar.showSnackbar(msg, duration = SnackbarDuration.Short)
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().background(Primer.BackgroundPrimary)
                .statusBarsPadding().navigationBarsPadding(),
        ) {
            DetailTopBar(title = "发布详情", onBack = onBack) {
                if (canEdit) {
                    Text(
                        "编辑", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = Primer.Blue500,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onEdit() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        "删除", fontSize = 13.sp,
                        color = if (busy) Primer.TextTertiary else Primer.DangerText,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp))
                            .clickable(enabled = !busy) { confirmDelete = true }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            Column(Modifier.verticalScroll(rememberScrollState())) {
                // ── 头部 ──
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ReleaseTagChip(release.tag)
                        if (isLatest) {
                            Spacer(Modifier.width(7.dp))
                            ReleaseChip("最新发布", Primer.SuccessTextStrong, Primer.SuccessSurface)
                        }
                        if (release.prerelease) {
                            Spacer(Modifier.width(7.dp))
                            ReleaseChip("预发布", Primer.AccentText, Primer.InfoSurfaceSoft)
                        }
                        if (release.draft) {
                            Spacer(Modifier.width(7.dp))
                            ReleaseChip("草稿", Primer.WarningTextStrong, Primer.WarningSurface)
                        }
                    }
                    Spacer(Modifier.height(9.dp))
                    Text(
                        release.name,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = Primer.TextPrimary,
                        lineHeight = 25.sp,
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        buildString {
                            if (release.author.isNotBlank()) append("${release.author} 发布 · ")
                            append(shortTime(release.createdAt))
                        },
                        fontSize = 11.5.sp,
                        color = Primer.TextTertiary,
                    )
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Primer.Gray150))

                // ── 附件 ──
                if (release.assets.isNotEmpty()) {
                    DetailSectionTitle("附件 · ${release.assets.size}")
                    release.assets.forEachIndexed { index, asset ->
                        if (index > 0) {
                            Box(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                                    .height(1.dp).background(Primer.Gray150),
                            )
                        }
                        // 下载状态来自 :downloader 的任务表（与系统通知同源），
                        // 因此退出页面再回来、甚至 App 退到后台，进度都还在
                        val taskId = assetTaskId(release.id, asset.id)
                        val task = downloadTasks.firstOrNull { it.id == taskId }
                        AssetRow(
                            asset = asset,
                            task = task,
                            onDownload = {
                                feedback = if (asset.downloadUrl.isBlank()) {
                                    "该附件没有下载地址"
                                } else {
                                    enqueueAssetDownload(context, release, asset)
                                    "已加入下载：${asset.name}"
                                }
                            },
                            onCancel = { DownloaderRuntime.cancel(taskId) },
                            onRetry = { DownloaderRuntime.retry(context, taskId) },
                            onInstall = { task?.file?.let { feedback = installDownloadedApk(context, it) } },
                            onOpen = {
                                val file = task?.file
                                if (file != null && !DownloadActions.openFile(context, file)) feedback = "没有能打开该文件的应用"
                            },
                            onShare = {
                                val file = task?.file
                                if (file != null && !DownloadActions.shareFile(context, file)) feedback = "分享失败"
                            },
                        )
                    }
                }

                // ── changelog ──
                if (html != null) {
                    DetailSectionTitle("更新内容")
                    ReadmeWebView(
                        html = html!!,
                        host = host,
                        owner = owner,
                        repo = repo,
                        branch = release.tag,
                        login = "",
                        token = token,
                        onLinkClick = {},
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除发布 ${release.tag}？", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary) },
            text = {
                Text(
                    "只删除这次发布，tag 与提交不受影响。此操作不可撤销。",
                    fontSize = 12.sp, color = Primer.TextTertiary, lineHeight = 18.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        busy = true
                        val err = withContext(Dispatchers.IO) {
                            RustBridge.deleteRelease(host, token, owner, repo, release.id)
                        }
                        Logger.net("DELETE release ${release.tag} → ${err ?: "成功"}", "GitHubAPI")
                        busy = false
                        if (err == null) onDeleted() else feedback = "删除失败：$err"
                    }
                }) { Text("删除", color = Primer.DangerText) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

// ───────────────────────────────── 编辑页 ─────────────────────────────────

/**
 * 发布的三档性质。**互斥**，对应上面表格里的三行。
 *
 * [hint] 直接写给用户看，因为「预发布和草稿到底差在哪」是这个页面最容易搞混的地方。
 */
private enum class ReleaseType(val label: String, val hint: String) {
    STABLE("正式发布", "所有人可见，并占据仓库的「最新发布」"),
    PRERELEASE("预发布", "所有人可见，但不会被标为「最新发布」"),
    DRAFT("草稿", "仅自己和有写权限的人可见，尚未公开"),
}

/** 新建 / 编辑发布。`existing == null` 即新建。 */
@Composable
fun ReleaseEditScreen(
    sessionJson: String,
    owner: String,
    repo: String,
    existing: ReleaseItem?,
    defaultBranch: String,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val host = remember(sessionJson) { sessionInfo(sessionJson).first }
    val token = remember(sessionJson) { sessionInfo(sessionJson).second }

    var tag by remember { mutableStateOf(existing?.tag.orEmpty()) }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var body by remember { mutableStateOf(existing?.body.orEmpty()) }
    var target by remember { mutableStateOf(if (existing == null) defaultBranch else "") }
    var type by remember {
        mutableStateOf(
            when {
                existing?.draft == true -> ReleaseType.DRAFT
                existing?.prerelease == true -> ReleaseType.PRERELEASE
                else -> ReleaseType.STABLE
            },
        )
    }
    // GitHub 网页允许「草稿 + 预发布」同时成立，三段式里没有这个组合。
    // 记录用户有没有亲自动过档位：没动过就保留原有的预发布标记，
    // 免得「只想改个标题」顺手把标记抹掉；动过了就以档位为准。
    var typeTouched by remember { mutableStateOf(false) }
    // 新建默认勾上「设为最新发布」—— 与 GitHub 的 make_latest 默认值一致
    var makeLatest by remember { mutableStateOf(true) }
    // 编辑已有发布时的 latest 基准：用来判断用户到底动没动这个开关
    var initialLatest by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }
    var generating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmGenerate by remember { mutableStateOf(false) }

    // 编辑时先问一次「这条现在是不是 latest」，好把开关摆到正确位置。
    // 拿不到就维持「不动它」（保存时发 legacy），不会误改归属。
    LaunchedEffect(existing?.id) {
        val id = existing?.id ?: return@LaunchedEffect
        if (id == 0L) return@LaunchedEffect
        RustBridge.latestReleaseId(host, token, owner, repo)?.let {
            initialLatest = it == id
            makeLatest = it == id
        }
    }

    val isDraft = type == ReleaseType.DRAFT
    val isPrerelease = when (type) {
        ReleaseType.PRERELEASE -> true
        ReleaseType.STABLE -> false
        ReleaseType.DRAFT -> !typeTouched && existing?.prerelease == true
    }

    /**
     * `make_latest` 的取值。
     *
     * 编辑时要点在于**「没动过就发 legacy」**：PATCH 的 `legacy` 表示「不改 latest 归属」，
     * 这样改标题/正文不会把别人的最新发布顶掉。只有用户显式改了开关才发 true/false。
     * 新建时没有基准，必须明确表态（`legacy` 在 POST 上会按日期+语义化版本自己挑，不是我们要的）。
     * 草稿与预发布一律不发这个字段（官方限制）。
     */
    fun makeLatestArg(): String = when {
        isDraft || isPrerelease -> ""
        existing == null -> if (makeLatest) "true" else "false"
        // 编辑：基准没拿到（查 latest 的请求失败）时，开关还开着 = 用户没动过 ⇒ 发 legacy 不碰归属；
        // 关掉了 = 用户明确要摘掉 ⇒ 发 false。**不能发 true** —— 那等于凭空认领最新发布。
        initialLatest == null -> if (makeLatest) "legacy" else "false"
        makeLatest == initialLatest -> "legacy"
        makeLatest -> "true"
        else -> "false"
    }

    fun save() {
        if (tag.isBlank()) { error = "请填写 tag（如 v1.0.13）"; return }
        scope.launch {
            busy = true
            error = null
            val result = withContext(Dispatchers.IO) {
                if (existing == null) {
                    RustBridge.createRelease(
                        host, token, owner, repo, tag.trim(), name.trim().ifBlank { tag.trim() }, body,
                        draft = isDraft, prerelease = isPrerelease, targetCommitish = target.trim(),
                        makeLatest = makeLatestArg(),
                    )
                } else {
                    RustBridge.updateRelease(
                        host, token, owner, repo, existing.id, tag.trim(), name.trim().ifBlank { tag.trim() }, body,
                        draft = isDraft, prerelease = isPrerelease, makeLatest = makeLatestArg(),
                    )
                }
            }
            Logger.net("${if (existing == null) "POST" else "PATCH"} release $tag → ${if (result != null) "成功" else "失败"}", "GitHubAPI")
            busy = false
            if (result != null) onSaved() else error = "保存失败（检查 tag 是否已存在、是否有写权限）"
        }
    }

    fun generate() {
        if (tag.isBlank()) { error = "先填写 tag，生成说明要按 tag 找提交"; return }
        scope.launch {
            generating = true
            error = null
            val json = withContext(Dispatchers.IO) {
                RustBridge.generateReleaseNotes(host, token, owner, repo, tag.trim(), target.trim())
            }
            generating = false
            val parsed = json?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (parsed == null) {
                error = "生成失败（确认 tag 存在、且该 tag 之前有合并记录）"
                return@launch
            }
            val generatedName = parsed.optString("name")
            val generatedBody = parsed.optString("body")
            if (name.isBlank() && generatedName.isNotBlank()) name = generatedName
            if (generatedBody.isNotBlank()) body = generatedBody
        }
    }

    val actionLabel = when {
        busy -> "保存中…"
        existing != null -> "保存"
        isDraft -> "存为草稿"
        else -> "发布"
    }

    Column(
        Modifier.fillMaxSize().background(Primer.BackgroundPrimary)
            .statusBarsPadding().navigationBarsPadding(),
    ) {
        DetailTopBar(
            title = if (existing == null) "新建发布" else "编辑发布",
            onBack = onBack,
        ) {
            PrimaryAction(label = actionLabel, enabled = !busy && !generating) { save() }
        }

        Column(
            Modifier.verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(top = 4.dp),
        ) {
            // ── 性质 ──
            FormLabel("版本类型")
            Spacer(Modifier.height(7.dp))
            ReleaseTypePicker(type) { picked ->
                type = picked
                typeTouched = true
            }
            Spacer(Modifier.height(7.dp))
            Text(type.hint, fontSize = 11.5.sp, color = Primer.TextTertiary, lineHeight = 17.sp)

            Spacer(Modifier.height(18.dp))
            FormField("标签", tag, { tag = it }, "v1.0.13", mono = true, required = true)
            Spacer(Modifier.height(14.dp))
            FormField("标题", name, { name = it }, "留空则用 tag")
            if (existing == null) {
                Spacer(Modifier.height(14.dp))
                FormField("目标分支", target, { target = it }, defaultBranch, mono = true)
            }

            // ── 更新内容 ──
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FormLabel("更新内容")
                Spacer(Modifier.weight(1f))
                Text(
                    if (generating) "生成中…" else "生成说明",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (generating) Primer.TextTertiary else Primer.Blue500,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = !generating && !busy) {
                            // 已经有内容时先问一句：生成结果是整段替换，不能悄悄吞掉用户写的字
                            if (body.isBlank()) generate() else confirmGenerate = true
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.height(7.dp))
            MarkdownBodyField(body) { body = it }

            // ── 最新发布 ──
            if (!isDraft && !isPrerelease) {
                Spacer(Modifier.height(18.dp))
                SwitchCard(
                    title = "设为最新发布",
                    desc = "仓库首页与 Releases 页会把它标为 Latest",
                    checked = makeLatest,
                    onToggle = { makeLatest = it },
                )
            }

            error?.let {
                Spacer(Modifier.height(14.dp))
                Text(it, fontSize = 12.sp, color = Primer.DangerText, lineHeight = 17.sp)
            }
            Spacer(Modifier.height(28.dp))
        }
    }

    if (confirmGenerate) {
        AlertDialog(
            onDismissRequest = { confirmGenerate = false },
            title = { Text("替换现有更新内容？", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary) },
            text = {
                Text(
                    "生成的发布说明会整段覆盖当前内容，已经写好的部分不会保留。",
                    fontSize = 12.sp, color = Primer.TextTertiary, lineHeight = 18.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmGenerate = false; generate() }) { Text("替换") }
            },
            dismissButton = { TextButton(onClick = { confirmGenerate = false }) { Text("取消") } },
        )
    }
}

// ───────────────────────────────── 编辑页零件 ─────────────────────────────────

/** 表单字段标签（比 [FormField] 内部的标签略重，用于给一整块起名）。 */
@Composable
private fun FormLabel(text: String) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextSecondary)
}

/**
 * 性质三段式选择器。
 *
 * 用整块 `Gray150` 做轨道、选中项浮白，而不是三个各自描边的按钮 ——
 * 描边按钮并排会得到一列竖线，看起来像三个独立的东西；轨道式才读得出「三选一」。
 */
@Composable
private fun ReleaseTypePicker(value: ReleaseType, onChange: (ReleaseType) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
            .background(Primer.Gray150).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        ReleaseType.entries.forEach { candidate ->
            val selected = candidate == value
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(7.dp))
                    .background(if (selected) Primer.BackgroundPrimary else Color.Transparent)
                    .clickable { onChange(candidate) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    candidate.label,
                    fontSize = 12.5.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) Primer.TextPrimary else Primer.TextSecondary,
                )
            }
        }
    }
}

/**
 * 单行输入：**只有一条底线，没有四面框**。
 *
 * 上一版一屏四个 `OutlinedTextField`，就是四个圆角矩形叠着，输入框的框线比内容还显眼。
 * 标签在上、底线在下之后，眼睛顺着标签走，框线不再参与构图；聚焦时底线转蓝，
 * 焦点状态反而比原来（M3 默认那圈灰边）更清楚。
 */
@Composable
private fun FormField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    mono: Boolean = false,
    required: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val family = if (mono) FontFamily.Monospace else null
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FormLabel(label)
            if (required) {
                Spacer(Modifier.width(3.dp))
                Text("*", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Primer.DangerText)
            }
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(fontSize = 14.sp, color = Primer.TextPrimary, fontFamily = family),
            cursorBrush = SolidColor(Primer.Blue500),
            modifier = Modifier.fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .padding(vertical = 8.dp),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(placeholder, fontSize = 14.sp, color = Primer.TextTertiary, fontFamily = family)
                    }
                    inner()
                }
            },
        )
        Box(
            Modifier.fillMaxWidth().height(if (focused) 1.5.dp else 1.dp)
                .background(if (focused) Primer.Blue500 else Primer.Gray200),
        )
    }
}

/**
 * 多行正文（Markdown）：**这一处保留描边**。
 *
 * 单行字段可以只靠底线，但一块 200dp 高的可编辑区域不给出边界，用户分不清
 * 「这是输入区」还是「这是页面上的说明文字」。底色另用 `Gray100`（中性面角色的既定用途：
 * chip / 代码底 / 未选中底），与只读预览那类面积拉平。
 */
@Composable
private fun MarkdownBodyField(value: String, onChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.fillMaxWidth().height(200.dp)
            .clip(shape)
            .background(Primer.Gray100)
            .border(1.dp, if (focused) Primer.Blue500 else Primer.Gray200, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(fontSize = 12.5.sp, color = Primer.TextPrimary, lineHeight = 19.sp),
            cursorBrush = SolidColor(Primer.Blue500),
            modifier = Modifier.fillMaxSize().onFocusChanged { focused = it.isFocused },
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            "## 变更\n- …",
                            fontSize = 12.5.sp, color = Primer.TextTertiary, lineHeight = 19.sp,
                        )
                    }
                    inner()
                }
            },
        )
    }
}

/**
 * 开关卡片：整块可点，不用去戳那个小滑块。
 *
 * 底色用 `Gray100` 而不是描边 —— 这一屏的描边已经留给正文了，再加一圈会和它抢。
 */
@Composable
private fun SwitchCard(
    title: String,
    desc: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(Primer.Gray100)
            .clickable { onToggle(!checked) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
            Text(desc, fontSize = 11.sp, color = Primer.TextTertiary, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}

/** 顶栏主操作：实心胶囊。文字不跟状态走（草稿态写「存为草稿」），避免点下去才知道会发生什么。 */
@Composable
private fun PrimaryAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier.clip(shape)
            .background(if (enabled) Primer.Blue500 else Primer.Gray300)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
        )
    }
}

// ───────────────────────────────── 附件行 ─────────────────────────────────

@Composable
private fun AssetRow(
    asset: ReleaseAsset,
    task: DownloadTask?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onInstall: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
) {
    val activeTask = task?.takeIf { it.isActive }
    val active = activeTask != null
    val done = task?.status == DownloadStatus.COMPLETED
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    asset.name,
                    fontSize = 12.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Primer.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(formatBytes(asset.size))
                        if (asset.downloadCount > 0) append(" · ${asset.downloadCount} 次下载")
                    },
                    fontSize = 10.5.sp,
                    color = Primer.TextTertiary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            // 动作区：任何时刻只给「一个主动作 + 至多一个次要动作」，避免按钮堆叠
            when {
                active -> AssetAction("取消", Primer.TextSecondary, onCancel)
                done -> {
                    AssetAction("分享", Primer.TextSecondary, onShare)
                    Spacer(Modifier.width(14.dp))
                    if (isApk(asset.name)) AssetAction("安装", Primer.Blue500, onInstall) else AssetAction("打开", Primer.Blue500, onOpen)
                }
                task?.status == DownloadStatus.FAILED -> AssetAction("重试", Primer.Blue500, onRetry)
                else -> AssetAction("下载", Primer.Blue500, onDownload)
            }
        }
        if (activeTask != null) {
            Spacer(Modifier.height(8.dp))
            val progress = activeTask.progress
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = Primer.Blue500,
                    trackColor = Primer.Gray150,
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = Primer.Blue500,
                    trackColor = Primer.Gray150,
                )
            }
            Text(
                buildString {
                    append(activeTask.percentText)
                    val downloaded = activeTask.downloadedBytes
                    if (downloaded > 0L) {
                        if (isNotEmpty()) append(" · ")
                        append(formatBytes(downloaded))
                    }
                },
                fontSize = 10.sp,
                color = Primer.TextTertiary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        val error = task?.error
        if (task?.status == DownloadStatus.FAILED && !error.isNullOrBlank()) {
            Text(error, fontSize = 10.5.sp, color = Primer.DangerText, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun AssetAction(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

private fun isApk(name: String): Boolean = name.lowercase().endsWith(".apk")

/** 下载任务的稳定 id：同一条 release 的同一个附件重复点「下载」是幂等的。 */
private fun assetTaskId(releaseId: Long, assetId: Long): String = "release-$releaseId-asset-$assetId"

/** 入队下载（进度 / 通知 / 断点续传都由 :downloader 负责）。 */
private fun enqueueAssetDownload(context: Context, release: ReleaseItem, asset: ReleaseAsset) {
    DownloaderRuntime.enqueue(
        context,
        DownloadRequest(
            id = assetTaskId(release.id, asset.id),
            url = asset.downloadUrl,
            fileName = asset.name,
            title = asset.name,
            sizeHint = asset.size,
        ),
    )
}

/**
 * 安装已下载的 APK。
 *
 * 没拿到「安装未知应用」授权时先跳设置页 —— 直接拉起安装器只会白屏失败，
 * 用户完全不知道要做什么。
 */
private fun installDownloadedApk(context: Context, file: File): String {
    if (!DownloadActions.canInstallPackages(context)) {
        runCatching { context.startActivity(DownloadActions.unknownSourcesSettingsIntent(context)) }
        return "请先允许「安装未知应用」，已为你打开设置页"
    }
    val error = DownloadActions.installApk(context, file)
    return error ?: "已交给系统安装器"
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "0 B"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.2f MB".format(bytes / 1024.0 / 1024.0)
}
