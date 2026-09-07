package com.branchbase.ui.decision

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.core.RustBridge
import com.branchbase.ui.profile.CommitMode
import com.branchbase.ui.profile.CommitModePickerDialog
import com.branchbase.ui.profile.commitMode
import com.branchbase.ui.profile.saveCommitMode
import com.branchbase.ui.theme.Primer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ═══════════════════════════════════════════════════════════════════
// 提交准备域决策页（对齐 design/decision-commit-prep-prototype.html）
//   · StageCommitScreen      P0-2 暂存勾选 + 提交信息（模式延迟决定收口）
//   · AuthorIdentityScreen   P0-3 提交身份确认/配置
//   · SensitiveWarningScreen P0-4 提交前敏感信息警告
//   · DraftRecoverScreen     P2-1 草稿恢复
// ═══════════════════════════════════════════════════════════════════

/** 勾选文件条目 */
data class StageFile(val path: String, val status: String, val size: String = "", val checked: Boolean = true)

/**
 * ① 提交前暂存勾选 + 提交信息（P0-2）。
 * 模式 ① 单文件跳过勾选（files 为空）；②/③ 展示勾选清单。
 * 提交模式未配置时先弹 CommitModePickerDialog（ux-review 2.1 收口）。
 */
@Composable
internal fun StageCommitScreen(
    repoName: String,
    files: List<StageFile>,
    mode: CommitMode?,
    onBack: () -> Unit,
    onPickMode: (CommitMode) -> Unit,
    onCommit: (message: String, selected: List<String>) -> Unit,
) {
    val context = LocalContext.current
    var checks by remember(files) { mutableStateOf(files.map { it.checked }) }
    var message by remember { mutableStateOf("") }
    var showModePicker by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }

    val selectedCount = checks.count { it }

    fun toggleCheck(i: Int) {
        checks = checks.toMutableList().also { it[i] = !it[i] }
    }

    fun attemptCommit() {
        if (mode == null) { showModePicker = true; return }
        if (selectedCount == 0) { feedback = "请先勾选要提交的文件"; return }
        if (message.isBlank()) { feedback = "请输入提交信息"; return }
        val selected = files.filterIndexed { i, _ -> checks[i] }.map { it.path }
        onCommit(message, selected)
    }

    DecisionScreenShell(
        title = "提交更改",
        subtitle = "$repoName · ${mode?.label ?: "模式未配置"}",
        onBack = onBack,
    content = {
        if (mode == null) {
            Text(
                "⚠ 未选择提交模式 · 提交时将询问。确定后固化到本地配置，可随时在设置中更改。",
                fontSize = 12.sp,
                color = Color(0xFF7D4C00),
                lineHeight = 18.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFFFF8C5))
                    .padding(10.dp),
            )
        }

        if (files.isNotEmpty()) {
            FactCard("变更文件（勾选本次提交范围）") {
                Column {
                    files.forEachIndexed { i, f ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { toggleCheck(i) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(18.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .border(2.dp, if (checks[i]) Primer.Green500 else Primer.Border, RoundedCornerShape(4.dp))
                                    .then(if (checks[i]) Modifier.background(Primer.Green500) else Modifier),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (checks[i]) Text("✓", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(f.path, fontSize = 12.sp, color = Primer.TextSecondary, fontFamily = FontFamily.Monospace, maxLines = 1, modifier = Modifier.weight(1f))
                            StatusChip(f.status)
                            if (f.size.isNotBlank()) Text(f.size, fontSize = 11.sp, color = Primer.TextTertiary)
                        }
                    }
                    Text(
                        "已选 $selectedCount / ${files.size} 个文件",
                        fontSize = 12.sp,
                        color = if (selectedCount > 0) Primer.Green500 else Primer.TextTertiary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        } else {
            DecisionNote("模式 ① 单文件提交：跳过勾选，直接填写提交信息（PUT contents）。")
        }

        FactCard("提交信息") {
            Column(Modifier.padding(12.dp)) {
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("简要描述本次更改…", fontSize = 13.sp, color = Primer.TextTertiary) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                )
            }
        }

        feedback?.let { FeedbackLine(it, error = true) }
    },
    bottom = {
        TextButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("取消") }
        Button(onClick = { attemptCommit() }, modifier = Modifier.weight(1f)) { Text("提交") }
    })

    // 提交模式延迟决定弹窗（未配置时）
    if (showModePicker) {
        CommitModePickerDialog(
            onDismiss = { showModePicker = false },
            onConfirm = { m ->
                saveCommitMode(context, m)
                showModePicker = false
                onPickMode(m)
            },
        )
    }
}

@Composable
private fun StatusChip(status: String) {
    val (fg, bg) = when (status) {
        "A" -> Color(0xFF005CC5) to Color(0xFFE3F0FF)
        "D" -> Color(0xFFCF222E) to Color(0xFFFFEBEC)
        else -> Color(0xFF176F2C) to Color(0xFFEAF9F0)
    }
    Text(
        status,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = fg,
        modifier = Modifier
            .padding(end = 8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/**
 * ② 提交身份确认/配置页（P0-3）。
 * 首次本地 commit 前触发；预填 GitHub noreply；可选「仅本次使用」。
 */
@Composable
fun AuthorIdentityScreen(
    suggestedName: String,
    suggestedEmail: String,
    onBack: () -> Unit,
    onConfirm: (name: String, email: String, saveGlobally: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf(suggestedName) }
    var email by remember { mutableStateOf(suggestedEmail) }
    var save by remember { mutableStateOf(true) }
    var feedback by remember { mutableStateOf<String?>(null) }

    fun doConfirm() {
        if (name.isBlank()) { feedback = "请输入作者名称"; return }
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) { feedback = "邮箱格式不正确"; return }
        onConfirm(name.trim(), email.trim(), save)
    }

    DecisionScreenShell(
        title = "确认提交身份",
        subtitle = "首次 · 公开可见",
        onBack = onBack,
        content = {
        DecisionNote("本地 git commit 需要签名身份（author）。此身份会写入每个提交，公开可见。")

        FactCard("签名信息") {
            Column(Modifier.padding(12.dp)) {
                Text("作者名称（必填）", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextSecondary)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                    singleLine = true,
                )
                Text("作者邮箱（必填）", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextSecondary, modifier = Modifier.padding(top = 10.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    singleLine = true,
                )
            }
        }

        FactCard("保存范围") {
            Column {
                DecisionOptionRow("保存为全局默认", "存 SharedPreferences（commit.author.*），所有仓库复用。", save, OptionTag.RECOMMENDED) { save = true }
                DecisionOptionRow("仅本次使用", "不落盘，下次提交时重新询问。", !save) { save = false }
            }
        }

        DecisionNote("noreply 邮箱可保护真实邮箱不被公开抓取，推荐保持默认。")
        feedback?.let { FeedbackLine(it, error = true) }
    },
    bottom = {
        TextButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("取消") }
        Button(onClick = { doConfirm() }, modifier = Modifier.weight(1f)) { Text("保存并继续提交") }
    })
}

/**
 * ③ 提交前敏感信息警告页（P0-4）。
 * 本地 Rust 扫描命中 → 返回修改（推荐）/ 仍要提交（勾选确认）。
 */
@Composable
fun SensitiveWarningScreen(
    hits: List<SensitiveHit>,
    onBack: () -> Unit,
    onProceed: () -> Unit,
) {
    var option by remember { mutableStateOf(0) } // 0=返回修改 1=仍要提交
    var confirmed by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }

    fun doResolve() {
        when (option) {
            0 -> onBack()
            1 -> if (confirmed) onProceed() else feedback = "请先勾选确认"
        }
    }

    DecisionScreenShell(
        title = "检测到敏感信息",
        subtitle = "提交前 · ${hits.size} 处命中",
        onBack = onBack,
        content = {
        DecisionNote("本次提交内容命中 ${hits.size} 处疑似密钥特征。提交后内容将随仓库历史永久公开，即使后续删除仍可被恢复。")

        FactCard("命中明细（已打码 · 本地扫描不上传）") {
            Column {
                hits.forEach { h ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("第 ${h.line} 行", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Primer.Red500)
                            Text(h.mask, fontSize = 11.sp, color = Primer.Red500, fontFamily = FontFamily.Monospace, maxLines = 1)
                        }
                        Text(h.kind, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Primer.Red500, modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFFFFEBEC)).padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
            }
        }

        FactCard("处理方式") {
            Column {
                DecisionOptionRow("返回修改", "回到编辑器，移除或替换密钥后重新提交。", option == 0, OptionTag.RECOMMENDED) { option = 0 }
                DecisionOptionRow("仍要提交", "需勾选下方确认（内容可公开 / 密钥将立即作废）。", option == 1, OptionTag.DANGER) { option = 1 }
            }
        }

        if (option == 1) {
            Spacer(Modifier.height(4.dp))
            DangerConfirmCard(
                description = "提交公开后，即使删除提交，密钥仍可能被爬取。建议立即在服务商后台吊销。",
                confirmLabel = "我确认内容可公开（或密钥将立即作废）",
                confirmed = confirmed,
                onToggle = { confirmed = !confirmed },
            )
        }
        feedback?.let { FeedbackLine(it, error = true) }
    },
    bottom = {
        TextButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("取消") }
        Button(
            onClick = { doResolve() },
            enabled = !(option == 1 && !confirmed),
            colors = ButtonDefaults.buttonColors(containerColor = if (option == 1) Primer.Red500 else Primer.Green500),
            modifier = Modifier.weight(1f),
        ) {
            Text(if (option == 0) "返回修改" else "仍要提交", color = Color.White)
        }
    })
}

/** 草稿信息（P2-1 事实区） */
data class DraftInfo(val path: String, val modifiedAt: String, val changedLines: Int, val remoteChanged: Boolean = false)

/**
 * ④ 草稿恢复决策页（P2-1）。
 */
@Composable
fun DraftRecoverScreen(
    drafts: List<DraftInfo>,
    onBack: () -> Unit,
    onRecover: () -> Unit,
    onDiscard: () -> Unit,
    onViewRemote: () -> Unit,
) {
    var option by remember { mutableStateOf(0) } // 0=恢复 1=丢弃 2=查看远端
    var confirmed by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }

    fun doResolve() {
        when (option) {
            0 -> onRecover()
            1 -> if (confirmed) onDiscard() else feedback = "请先勾选二次确认"
            2 -> onViewRemote()
        }
    }

    DecisionScreenShell(
        title = "发现未保存草稿",
        subtitle = "编辑中断恢复",
        onBack = onBack,
        content = {
        DecisionNote("上次编辑因切后台/进程被杀中断。草稿存于 files/edit/（D3 隔离目录）。")

        FactCard("草稿信息") {
            Column {
                drafts.forEach { d ->
                    FactRow(d.path, "修改于 ${d.modifiedAt} · ${d.changedLines} 行变更", mono = true)
                }
            }
        }

        FactCard("远端状态") {
            FactRow(
                "sha 对比",
                if (drafts.any { it.remoteChanged }) "远端已变化（存在多端编辑冲突）" else "远端未变化（可安全恢复编辑）",
                rightColor = if (drafts.any { it.remoteChanged }) Color(0xFF9A6700) else Primer.TextTertiary,
            )
        }

        FactCard("恢复方式") {
            Column {
                DecisionOptionRow("恢复草稿", "载入草稿继续编辑，${drafts.size} 个文件恢复为未保存状态。", option == 0, OptionTag.RECOMMENDED) { option = 0 }
                DecisionOptionRow("丢弃草稿", "草稿永久删除（需二次确认），打开远端最新版本。", option == 1, OptionTag.DANGER) { option = 1 }
                DecisionOptionRow("查看远端最新", "丢弃草稿并打开远端只读视图（不进入编辑态）。", option == 2) { option = 2 }
            }
        }

        if (option == 1) {
            Spacer(Modifier.height(4.dp))
            DangerConfirmCard(
                description = "${drafts.size} 个草稿文件将永久删除，未保存内容无法找回。",
                confirmLabel = "我确认丢弃草稿",
                confirmed = confirmed,
                onToggle = { confirmed = !confirmed },
            )
        }
        feedback?.let { FeedbackLine(it, error = true) }
    },
    bottom = {
        TextButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("取消") }
        Button(
            onClick = { doResolve() },
            enabled = !(option == 1 && !confirmed),
            colors = ButtonDefaults.buttonColors(containerColor = if (option == 1) Primer.Red500 else Primer.Green500),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                when (option) {
                    0 -> "恢复草稿"
                    1 -> "丢弃草稿"
                    else -> "查看远端最新"
                },
                color = Color.White,
            )
        }
    })
}
