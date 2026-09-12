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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.core.RustBridge
import com.branchbase.downloader.DownloadActions
import com.branchbase.downloader.DownloadRequest
import com.branchbase.downloader.DownloadStatus
import com.branchbase.downloader.DownloadTask
import com.branchbase.downloader.DownloaderRuntime
import com.branchbase.ui.log.Logger
import com.branchbase.ui.theme.iconTap
import com.branchbase.ui.theme.Primer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 发布详情页：changelog（Markdown 渲染）+ 附件列表（下载）+ 编辑/删除入口。
 *
 * `canEdit` 来自仓库的 `permissions.push` —— 没有写权限时不渲染编辑/删除，
 * 避免点了才报 403。
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
            // 头部
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Primer.IconPrimary,
                    modifier = Modifier.size(24.dp).iconTap { onBack() },
                )
                Spacer(Modifier.width(8.dp))
                Text("发布详情", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
                Spacer(Modifier.weight(1f))
                if (canEdit) {
                    Text("编辑", fontSize = 13.sp, color = Primer.Blue500, modifier = Modifier.clickable { onEdit() })
                    Spacer(Modifier.width(14.dp))
                    Text(
                        "删除",
                        fontSize = 13.sp,
                        color = if (busy) Primer.TextTertiary else Primer.Red500,
                        modifier = Modifier.clickable(enabled = !busy) { confirmDelete = true },
                    )
                }
            }

            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                // 标题区
                Text(release.name, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        release.tag,
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Primer.Blue500,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Primer.Gray150)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    if (release.draft) ReleaseBadge("草稿", Primer.WarningText, Color(0xFFFFF8E5))
                    if (release.prerelease) {
                        if (release.draft) Spacer(Modifier.width(6.dp))
                        ReleaseBadge("预发布", Color(0xFF0A4E9B), Primer.InfoSurfaceSoft)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    buildString {
                        if (release.author.isNotBlank()) append("${release.author} 发布 · ")
                        append(shortTime(release.createdAt))
                    },
                    fontSize = 11.5.sp,
                    color = Primer.TextTertiary,
                )

                // 附件
                if (release.assets.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text("附件（${release.assets.size}）", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
                    Spacer(Modifier.height(8.dp))
                    release.assets.forEach { asset ->
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

                // changelog
                if (html != null) {
                    Spacer(Modifier.height(16.dp))
                    Text("更新内容", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
                    Spacer(Modifier.height(8.dp))
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
                }) { Text("删除", color = Primer.Red500) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
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
    var draft by remember { mutableStateOf(existing?.draft ?: false) }
    var prerelease by remember { mutableStateOf(existing?.prerelease ?: false) }
    var target by remember { mutableStateOf(if (existing == null) defaultBranch else "") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun save() {
        if (tag.isBlank()) { error = "请填写 tag（如 v1.0.13）"; return }
        scope.launch {
            busy = true
            error = null
            val result = withContext(Dispatchers.IO) {
                if (existing == null) {
                    RustBridge.createRelease(host, token, owner, repo, tag.trim(), name.trim().ifBlank { tag.trim() }, body, draft, prerelease, target.trim())
                } else {
                    RustBridge.updateRelease(host, token, owner, repo, existing.id, tag.trim(), name.trim().ifBlank { tag.trim() }, body, draft, prerelease)
                }
            }
            Logger.net("${if (existing == null) "POST" else "PATCH"} release $tag → ${if (result != null) "成功" else "失败"}", "GitHubAPI")
            busy = false
            if (result != null) onSaved() else error = "保存失败（检查 tag 是否已存在、是否有写权限）"
        }
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
            Text(
                if (existing == null) "新建发布" else "编辑发布",
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (busy) "保存中…" else "保存",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (busy) Primer.TextTertiary else Primer.Blue500,
                modifier = Modifier.clickable(enabled = !busy) { save() },
            )
        }

        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Field("Tag（必填）", tag, { tag = it }, "v1.0.13", mono = true)
            Spacer(Modifier.height(12.dp))
            Field("标题", name, { name = it }, "留空则用 tag")
            if (existing == null) {
                Spacer(Modifier.height(12.dp))
                Field("目标分支", target, { target = it }, defaultBranch, mono = true)
            }
            Spacer(Modifier.height(12.dp))
            Text("更新内容（Markdown）", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextSecondary)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                modifier = Modifier.fillMaxWidth().height(220.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.5.sp),
                placeholder = { Text("## 变更\n- …", fontSize = 12.sp, color = Primer.TextTertiary) },
            )
            Spacer(Modifier.height(14.dp))
            SwitchRow("草稿", "保存后不公开，仅自己可见", draft) { draft = it }
            SwitchRow("预发布", "标记为 pre-release，不占用 latest", prerelease) { prerelease = it }
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, fontSize = 12.sp, color = Primer.Red500, lineHeight = 17.sp)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, placeholder: String, mono: Boolean = false) {
    Column {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextSecondary)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 13.sp,
                fontFamily = if (mono) FontFamily.Monospace else null,
            ),
            placeholder = { Text(placeholder, fontSize = 12.sp, color = Primer.TextTertiary) },
        )
    }
}

@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
            Text(desc, fontSize = 11.sp, color = Primer.TextTertiary, modifier = Modifier.padding(top = 2.dp))
        }
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}

@Composable
private fun ReleaseBadge(text: String, fg: Color, bg: Color) {
    Text(
        text,
        fontSize = 10.sp,
        color = fg,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

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
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(
                1.dp,
                if (active || done) Primer.Blue500.copy(alpha = 0.45f) else Primer.Border,
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    asset.name,
                    fontSize = 12.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Primer.TextPrimary,
                    maxLines = 1,
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
            Text(error, fontSize = 10.5.sp, color = Primer.Red500, modifier = Modifier.padding(top = 6.dp))
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
        modifier = Modifier.clickable { onClick() },
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

