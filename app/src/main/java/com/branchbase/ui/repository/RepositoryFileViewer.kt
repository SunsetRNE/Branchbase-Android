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
import com.branchbase.ui.profile.CommitMode
import com.branchbase.ui.profile.CommitModePickerDialog
import com.branchbase.ui.profile.commitMode
import com.branchbase.ui.profile.saveCommitMode
import com.branchbase.ui.theme.CodeSyntax
import com.branchbase.ui.theme.Primer
import kotlinx.coroutines.launch
import org.json.JSONObject

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

    // 提交（①单文件提交 PUT contents）
    fun doCommitSingle() {
        if (commitMsg.isBlank()) { feedback = "请输入提交信息"; return }
        scope.launch {
            submitting = true
            feedback = null
            val result = RustBridge.putContents(host, token, owner, repo, path, commitMsg, draft, sha, "main")
            submitting = false
            if (result != null && !result.startsWith("ERROR:")) {
                content = draft
                editing = false
                feedback = "已提交"
            } else {
                feedback = "提交失败"
            }
        }
    }

    // 提交入口：按提交模式分发（UI 切换）
    fun onCommitClick() {
        when (commitMode(context)) {
            null -> showModePicker = true
            CommitMode.SINGLE_FILE -> doCommitSingle()
            CommitMode.MULTI_FILE -> feedback = "多文件模式：已暂存（批量提交待接 Git Data API）"
            CommitMode.LOCAL_REPO -> feedback = "本地 Git 模式：commit/push 待接 git2"
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
                Text("编辑", fontSize = 14.sp, color = Primer.Blue500, modifier = Modifier.clickable { editing = true; draft = content })
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
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(content.lines()) { i, line ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                (i + 1).toString(),
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