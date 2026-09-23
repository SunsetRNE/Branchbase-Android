package com.branchbase.ui.repository

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.core.AttachmentStatus
import com.branchbase.core.DraftAttachment
import com.branchbase.core.ReleaseAttachmentStore
import com.branchbase.core.ReleaseDraft
import com.branchbase.core.ReleaseDraftStore
import com.branchbase.core.RustBridge
import com.branchbase.downloader.DownloadActions
import com.branchbase.downloader.DownloadPaths
import com.branchbase.downloader.DownloadRequest
import com.branchbase.downloader.DownloadStatus
import com.branchbase.downloader.DownloadTask
import com.branchbase.downloader.DownloaderRuntime
import com.branchbase.ui.log.Logger
import com.branchbase.ui.theme.Primer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 * 新建 / 编辑发布。
 *
 * ## 这一版为什么整体重排（第二轮设计）
 *
 * 旧版的垂直预算 ≈694dp：类型 81 + 三个字段 176 + 更新内容 233（固定 200dp 的描边盒）
 * + 「设为最新发布」56 + 间距 112 —— 一屏（可视约 790dp）装下这些就没地方放附件了，
 * 而 GitHub release 的一半价值恰恰在资产。现在压到 ≈360dp，主要靠四件事：
 *
 * | 改动 | 省 |
 * |------|----|
 * | 标签 / 标题 / 目标分支**并成一行**（标签是 chip、分支是行尾只读 chip） | 80dp |
 * | 「设为最新」并进类型行（只在该有意义时出现） | 38dp |
 * | 更新内容换成行标识槽编辑器（3 行起步、自动增高） | 136dp |
 * | 去掉分组标题、统计并入分组头、分组之间改用发丝线 | 30dp+ |
 *
 * 逐项预算与设计依据见 `design/release-redesign/README.md`
 * （入库副本 `docs/specs/prototypes/release-redesign.md`）。
 *
 * ## 附件是「先落盘、再上传」
 *
 * 系统选择器给的 `content://` 是**临时凭据**：拿到就复制进 [ReleaseAttachmentStore] 的暂存区
 * （`getExternalFilesDir/release-uploads/{owner}/{repo}/{tag}/`）。不用 `cacheDir` ——
 * 系统在低存储时会清它，「导入 → 切出去查个东西 → 回来」就发现文件没了，那是这个功能最不能被接受的失败。
 * 表单与附件清单以 JSON 落在 `filesDir/release-drafts/`，改字段后 900ms 防抖落盘：
 * **退出再回来，草稿和附件都还在**是这一版的核心承诺。
 *
 * ## 发布是两步（资产必须挂在 release 上）
 *
 * 先 create/update 拿到 release id，再逐个 `uploadReleaseAsset`。拿到 id 后记在 [createdId] 里：
 * 附件失败重试时**不能**再 create 一次（同名 tag 会 422 already_exists）。有附件失败就不算完成 ——
 * 不清理暂存、不关页面，让用户重试或移除。
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val host = remember(sessionJson) { sessionInfo(sessionJson).first }
    val token = remember(sessionJson) { sessionInfo(sessionJson).second }
    val releaseId = existing?.id ?: 0L

    // 草稿按 (仓库, 发布) 恢复：同一仓库下「新建」与「编辑已有发布」是两份上下文，
    // 用 releaseId 区分，免得在编辑 A 时把「新建 B」的半成品灌进来
    val draft = remember(owner, repo, releaseId) {
        ReleaseDraftStore.load(context, owner, repo)?.takeIf { it.releaseId == releaseId }
    }
    // 附件可能已被清理（TTL / 手动删）：只恢复还在盘上的那些；引用 downloads/ 的项不检查
    val restored = remember(draft) {
        draft?.attachments?.filter { it.reference || File(it.path).isFile } ?: emptyList()
    }

    var tag by remember { mutableStateOf(existing?.tag ?: draft?.tag.orEmpty()) }
    var name by remember { mutableStateOf(existing?.name ?: draft?.title.orEmpty()) }
    var body by remember { mutableStateOf(existing?.body ?: draft?.body.orEmpty()) }
    var target by remember {
        mutableStateOf(
            when {
                existing != null -> ""
                !draft?.target.isNullOrBlank() -> draft.target
                else -> defaultBranch
            },
        )
    }
    var type by remember {
        mutableStateOf(
            draft?.type?.let { wire -> ReleaseType.entries.firstOrNull { it.name.equals(wire, ignoreCase = true) } }
                ?: when {
                    existing?.draft == true -> ReleaseType.DRAFT
                    existing?.prerelease == true -> ReleaseType.PRERELEASE
                    else -> ReleaseType.STABLE
                },
        )
    }
    // GitHub 网页允许「草稿 + 预发布」同时成立，三段式里没有这个组合。
    // 记录用户有没有亲自动过档位：没动过就保留原有的预发布标记（免得「只想改个标题」顺手抹掉它）
    var typeTouched by remember { mutableStateOf(false) }
    // 新建默认勾上「设为最新发布」—— 与 GitHub 的 make_latest 默认值一致
    var makeLatest by remember { mutableStateOf(draft?.latest ?: true) }
    // 编辑已有发布时的 latest 基准：用来判断用户到底动没动这个开关
    var initialLatest by remember { mutableStateOf<Boolean?>(null) }
    var attachments by remember { mutableStateOf(restored) }
    // 生成说明写进来的「行文本」（不是行号：用户在生成前后敲几行，行号会整体移动）
    var generatedTexts by remember { mutableStateOf(emptySet<String>()) }
    // 本次保存拿到的 release id：附件失败重试时复用它，不再 create 第二次
    var createdId by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    var generating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var savedHint by remember { mutableStateOf<String?>(null) }
    var showStoreInfo by remember { mutableStateOf(false) }
    var previewOpen by remember { mutableStateOf(false) }
    var previewHtml by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }

    // 顺手回收过期的暂存文件（TTL 7 天）：导入的附件在「没发布就放弃」时会一直留在盘上，
    // 而清理的时机只有进这个页面时才自然 —— 用户不会去设置里点「清理缓存」。
    // 失败不影响页面（prune 是 best-effort）。
    LaunchedEffect(owner, repo) {
        withContext(Dispatchers.IO) { ReleaseAttachmentStore.pruneExpired(context) }
    }

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
        initialLatest == null -> if (makeLatest) "legacy" else "false"
        makeLatest == initialLatest -> "legacy"
        makeLatest -> "true"
        else -> "false"
    }

    // 生成说明的标记行（纯函数派生：用户把某行改掉，标记自然消失）
    val generatedLines = remember(body, generatedTexts) { generatedLineIndices(body, generatedTexts) }

    // 自动保存草稿：改任何字段 900ms 后落盘。文件在 release-uploads/、清单在 filesDir，
    // 两边一起才叫「退出再回来不丢」
    LaunchedEffect(tag, name, body, target, type, makeLatest, attachments) {
        delay(900)
        val ok = withContext(Dispatchers.IO) {
            ReleaseDraftStore.save(
                context,
                owner,
                repo,
                ReleaseDraft(
                    releaseId = releaseId,
                    tag = tag,
                    title = name,
                    body = body,
                    target = target,
                    type = type.name.lowercase(),
                    latest = makeLatest,
                    attachments = attachments,
                    savedAt = System.currentTimeMillis(),
                ),
            )
        }
        if (ok) savedHint = "草稿已自动保存"
    }

    // 预览：正文 → HTML 走 Rust 的渲染器（与详情页同一条路径），放在底部弹层里看，不挤占编辑区
    LaunchedEffect(previewOpen, body) {
        if (!previewOpen) {
            previewHtml = null
            return@LaunchedEffect
        }
        previewHtml = withContext(Dispatchers.IO) { RustBridge.renderMarkdown(host, token, body) }
            ?.takeIf { !it.startsWith("ERROR:") }
    }

    /** 系统文件选择器（SAF）：不需要任何存储权限，与 App「零权限」的基调一致。 */
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val staged = withContext(Dispatchers.IO) {
                uris.mapNotNull { ReleaseAttachmentStore.stage(context, owner, repo, tag, it).getOrNull() }
            }
            if (staged.isEmpty()) {
                error = "导入失败：读不到所选文件"
                return@launch
            }
            val known = attachments.map { it.path }.toSet()
            val fresh = staged.filterNot { it.absolutePath in known }.map {
                DraftAttachment(
                    name = it.name,
                    size = it.length(),
                    path = it.absolutePath,
                    mime = ReleaseAttachmentStore.mimeOf(it.name),
                )
            }
            attachments = attachments + fresh
            error = null
            snackbar.showSnackbar("已导入 ${fresh.size} 个文件 · 暂存在 release-uploads/")
        }
    }

    fun removeAttachment(item: DraftAttachment) {
        scope.launch {
            withContext(Dispatchers.IO) {
                // 引用 downloads/ 的文件不是我们的，不能替用户删
                if (!item.reference) ReleaseAttachmentStore.remove(File(item.path))
            }
            attachments = attachments.filterNot { it.path == item.path }
        }
    }

    fun save() {
        if (tag.isBlank()) {
            error = "请填写 tag（如 v1.0.13）"
            return
        }
        scope.launch {
            busy = true
            error = null
            val pending = attachments.filter { it.status != AttachmentStatus.DONE }

            // ① 先建/更新 release —— 附件必须挂在 release 上，拿不到 id 就不能往下走
            val knownId = createdId ?: existing?.id?.takeIf { it != 0L }
            val releaseJson = withContext(Dispatchers.IO) {
                if (knownId == null) {
                    RustBridge.createRelease(
                        host, token, owner, repo, tag.trim(), name.trim().ifBlank { tag.trim() }, body,
                        draft = isDraft, prerelease = isPrerelease, targetCommitish = target.trim(),
                        makeLatest = makeLatestArg(),
                    )
                } else {
                    RustBridge.updateRelease(
                        host, token, owner, repo, knownId, tag.trim(), name.trim().ifBlank { tag.trim() }, body,
                        draft = isDraft, prerelease = isPrerelease, makeLatest = makeLatestArg(),
                    )
                }
            }
            Logger.net("${if (knownId == null) "POST" else "PATCH"} release $tag → ${if (releaseJson != null) "成功" else "失败"}", "GitHubAPI")
            if (releaseJson == null) {
                busy = false
                error = "保存失败（检查 tag 是否已存在、是否有写权限）"
                return@launch
            }
            val id = createdId
                ?: runCatching { JSONObject(releaseJson).optLong("id") }.getOrDefault(0L).takeIf { it != 0L }
            createdId = id
            if (pending.isNotEmpty() && id == null) {
                busy = false
                error = "发布已保存，但没拿到发布 id，附件没能上传"
                return@launch
            }

            // ② 逐个上传附件（Rust 侧目前是一次阻塞调用，拿不到百分比 → UI 用不确定进度条）
            for (item in pending) {
                attachments = attachments.map {
                    if (it.path == item.path) it.copy(status = AttachmentStatus.UPLOADING, error = null) else it
                }
                val assetJson = withContext(Dispatchers.IO) {
                    RustBridge.uploadReleaseAsset(
                        host, token, owner, repo, id ?: 0L, item.name, item.path, item.mime,
                    )
                }
                attachments = attachments.map { current ->
                    when {
                        current.path != item.path -> current
                        assetJson != null -> current.copy(
                            status = AttachmentStatus.DONE,
                            uploadedId = runCatching { JSONObject(assetJson).optLong("id") }.getOrDefault(0L),
                        )
                        else -> current.copy(status = AttachmentStatus.FAILED, error = "上传失败（可重试）")
                    }
                }
            }

            busy = false
            val failed = attachments.count { it.status == AttachmentStatus.FAILED }
            if (failed == 0) {
                // ③ 成功才清理：暂存文件 + 草稿清单
                withContext(Dispatchers.IO) {
                    ReleaseAttachmentStore.clear(context, owner, repo, tag)
                    ReleaseDraftStore.clear(context, owner, repo)
                }
                onSaved()
            } else {
                error = "$failed 个附件没传上去：修好后点「重试」继续，别直接退出"
            }
        }
    }

    fun generate() {
        if (tag.isBlank()) {
            error = "先填写 tag，生成说明要按 tag 找提交"
            return
        }
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
            if (generatedBody.isNotBlank()) {
                // 追加而不是替换：上一版整段覆盖，所以必须配一个确认弹窗；现在手写的字一行都不会被吞
                val (merged, inserted) = insertGeneratedNotes(body, generatedBody)
                body = merged
                generatedTexts = inserted
                snackbar.showSnackbar("已插入 ${inserted.size} 行生成说明 · 手写内容保留")
            }
        }
    }

    val actionLabel = when {
        busy -> "保存中…"
        existing != null -> "保存"
        isDraft -> "存为草稿"
        else -> "发布"
    }

    val pendingCount = attachments.count { it.status != AttachmentStatus.DONE }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().background(Primer.BackgroundPrimary)
                .statusBarsPadding().navigationBarsPadding(),
        ) {
            DetailTopBar(
                title = if (existing == null) "新建发布" else "编辑发布",
                onBack = onBack,
            ) {
                ReleasePrimaryAction(label = actionLabel, enabled = !busy && !generating) { save() }
            }

            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 14.dp),
            ) {
                // 状态条：默认不占高度（见 ReleaseStatusNote 的注释）
                ReleaseStatusNote(error ?: savedHint, bad = error != null)

                // ── 发布：类型（含「设为最新」）+ 标签 / 标题 / 目标分支（全在前 90dp 里） ──
                Spacer(Modifier.height(6.dp))
                ReleaseTypeRow(
                    type = type,
                    onType = { picked ->
                        type = picked
                        typeTouched = true
                    },
                    latest = makeLatest,
                    onLatest = { makeLatest = it },
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(4.dp).clip(CircleShape).background(Primer.Gray300))
                    Spacer(Modifier.width(5.dp))
                    Text(type.hint, fontSize = 10.5.sp, color = Primer.TextTertiary, lineHeight = 15.sp)
                }
                Spacer(Modifier.height(6.dp))
                ReleaseTagTitleRow(
                    tag = tag,
                    onTag = { tag = it },
                    title = name,
                    onTitle = { name = it },
                    // 目标分支只在新建时可改（GitHub 的 PATCH 不接受 target_commitish）
                    branch = if (existing == null) target else "",
                )

                // ── 附件 ──
                Spacer(Modifier.height(10.dp))
                ReleaseHairline()
                Spacer(Modifier.height(10.dp))
                ReleaseGroupHeader(
                    title = "附件",
                    counter = if (attachments.isEmpty()) {
                        null
                    } else {
                        "${attachments.size} 个 · ${DownloadPaths.formatBytes(attachments.sumOf { it.size })}"
                    },
                ) {
                    ReleaseStoreInfoAction { showStoreInfo = true }
                    Spacer(Modifier.width(4.dp))
                    ReleaseHeaderAction("导入", Icons.Filled.Add) { picker.launch("*/*") }
                }
                if (attachments.isEmpty()) {
                    ReleaseAttachmentEmpty { picker.launch("*/*") }
                } else {
                    attachments.forEachIndexed { index, attachment ->
                        if (index > 0) ReleaseHairline()
                        ReleaseAttachmentRow(
                            attachment = attachment,
                            onRetry = { save() },
                            onRemove = { removeAttachment(attachment) },
                        )
                    }
                    if (pendingCount > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "点右上「$actionLabel」会先保存这条发布，再逐个上传附件",
                            fontSize = 10.5.sp,
                            color = Primer.TextTertiary,
                            lineHeight = 15.sp,
                        )
                    }
                }

                // ── 更新内容 ──
                Spacer(Modifier.height(10.dp))
                ReleaseHairline()
                Spacer(Modifier.height(10.dp))
                ReleaseGroupHeader(
                    title = "更新内容",
                    counter = "${lineStartOffsets(body).size} 行 · ${body.count { !it.isWhitespace() }} 字",
                ) {
                    ReleaseHeaderAction("预览", Icons.Filled.Visibility) { previewOpen = true }
                    ReleaseHeaderAction(
                        label = if (generating) "生成中…" else "生成说明",
                        icon = Icons.Filled.AutoAwesome,
                        enabled = !generating && !busy,
                    ) { generate() }
                }
                Spacer(Modifier.height(4.dp))
                ReleaseNotesEditor(
                    text = body,
                    onTextChange = { body = it },
                    generatedLines = generatedLines,
                )
                if (generatedLines.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    ReleaseHairline()
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "+",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Primer.SuccessTextStrong,
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            "${generatedLines.size} 行来自生成说明",
                            fontSize = 10.5.sp,
                            color = Primer.SuccessTextStrong,
                        )
                        Spacer(Modifier.weight(1f))
                        ReleaseHeaderAction("全部保留") { generatedTexts = emptySet() }
                        ReleaseHeaderAction("丢弃生成行", danger = true) {
                            body = dropGeneratedNotes(body, generatedLines)
                            generatedTexts = emptySet()
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    // 「导入的文件存在哪」：常驻一行说明太贵（这一屏每 dp 都算过），改成按需展开
    if (showStoreInfo) {
        ModalBottomSheet(
            onDismissRequest = { showStoreInfo = false },
            sheetState = rememberModalBottomSheetState(),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
                Text("导入的文件存在哪？", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary)
                Spacer(Modifier.height(10.dp))
                Text(
                    "导入后立刻复制到 App 私有目录，不依赖系统选择器给的那张临时凭据：",
                    fontSize = 12.sp,
                    color = Primer.TextSecondary,
                    lineHeight = 18.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "getExternalFilesDir(null)/release-uploads/$owner/$repo/${tag.ifBlank { "_untagged" }}/",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Primer.TextPrimary,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "· 退出编辑、切后台、重启 App 都不会丢（不用 cacheDir：系统低存储时会清它）；\n" +
                        "· 发布成功后自动清理；未发布的草稿保留 7 天；点「移除」立即删除；\n" +
                        "· 引用 downloads/ 里已下载的文件时不复制，只记路径。",
                    fontSize = 11.5.sp,
                    color = Primer.TextTertiary,
                    lineHeight = 18.sp,
                )
            }
        }
    }

    if (previewOpen) {
        ModalBottomSheet(
            onDismissRequest = { previewOpen = false },
            sheetState = rememberModalBottomSheetState(),
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp).padding(bottom = 24.dp),
            ) {
                val html = previewHtml
                if (html.isNullOrBlank()) {
                    Text(
                        "正在渲染…",
                        fontSize = 12.5.sp,
                        color = Primer.TextTertiary,
                        modifier = Modifier.padding(12.dp),
                    )
                } else {
                    ReadmeWebView(
                        html = html,
                        host = host,
                        owner = owner,
                        repo = repo,
                        branch = tag,
                        login = "",
                        token = token,
                        onLinkClick = {},
                    )
                }
            }
        }
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
                        append(DownloadPaths.formatBytes(asset.size))
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
                        append(DownloadPaths.formatBytes(downloaded))
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
 *
 * `internal` 而不是 `private`：[ArtifactInstall] 的「工作流产物 → 安装」也走这里 ——
 * 授权引导只能有一份，抄第二份就多一处将来会忘记同步的权限处理。
 */
internal fun installDownloadedApk(context: Context, file: File): String {
    if (!DownloadActions.canInstallPackages(context)) {
        runCatching { context.startActivity(DownloadActions.unknownSourcesSettingsIntent(context)) }
        return "请先允许「安装未知应用」，已为你打开设置页"
    }
    val error = DownloadActions.installApk(context, file)
    return error ?: "已交给系统安装器"
}

