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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.core.RustBridge
import com.branchbase.ui.log.Logger
import com.branchbase.ui.theme.Primer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

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
                    modifier = Modifier.size(24.dp).clickable { onBack() },
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
                    if (release.draft) ReleaseBadge("草稿", Color(0xFF9A6700), Color(0xFFFFF8E5))
                    if (release.prerelease) {
                        if (release.draft) Spacer(Modifier.width(6.dp))
                        ReleaseBadge("预发布", Color(0xFF0A4E9B), Color(0xFFE6F1FF))
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
                        AssetRow(asset) {
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { downloadAsset(context, host, token, asset) }
                                feedback = r
                            }
                        }
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
                modifier = Modifier.size(24.dp).clickable { onBack() },
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
private fun AssetRow(asset: ReleaseAsset, onDownload: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, Primer.Border, RoundedCornerShape(8.dp))
            .clickable { onDownload() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
        Text("下载", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Primer.Blue500)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "0 B"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.2f MB".format(bytes / 1024.0 / 1024.0)
}

/**
 * 下载附件到应用外部目录（带 token，私有仓库也可用）。
 *
 * @return 提示文案（成功给落盘路径）
 */
private fun downloadAsset(context: Context, host: String, token: String, asset: ReleaseAsset): String {
    if (asset.downloadUrl.isBlank()) return "该附件没有下载地址"
    return runCatching {
        val dir = File(context.getExternalFilesDir(null), "downloads")
        dir.mkdirs()
        val target = File(dir, asset.name)
        val conn = (URL(asset.downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Branchbase/0.1")
            if (token.isNotBlank()) setRequestProperty("Authorization", "token $token")
        }
        try {
            if (conn.responseCode !in 200..299) return "下载失败（HTTP ${conn.responseCode}）"
            conn.inputStream.use { input -> target.outputStream().use { out -> input.copyTo(out) } }
        } finally {
            conn.disconnect()
        }
        "已保存到 downloads/${asset.name}"
    }.getOrElse { "下载失败：${it.message ?: "未知错误"}" }
}

