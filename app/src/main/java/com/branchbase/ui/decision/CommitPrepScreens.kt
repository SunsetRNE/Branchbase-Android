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
import androidx.compose.ui.res.stringResource
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
import com.branchbase.R
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
// 提交准备域决策页（规格见 docs/specs/decision-pages-design.md §4）
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
    // 本地仓库（Git）模式的口径是「整个工作区一起提交」（core `commit_repo`：先 add_all(".") 再
    // update_all(".")），勾选在这一档决定不了任何事情 —— 留一排可点的勾选框就成了
    // 「取消了勾选、提交照样带上」这种静默失效。所以这一档把清单降级成**信息**：
    // 勾选只属于多文件合并模式（单文件模式 files 为空，本来也不勾）。
    val localRepo = mode == CommitMode.LOCAL_REPO

    fun toggleCheck(i: Int) {
        checks = checks.toMutableList().also { it[i] = !it[i] }
    }

    fun attemptCommit() {
        if (mode == null) { showModePicker = true; return }
        if (!localRepo && selectedCount == 0) { feedback = context.getString(R.string.error_tick_files_first); return }
        if (message.isBlank()) { feedback = context.getString(R.string.error_commit_message_required); return }
        val selected = if (localRepo) files.map { it.path } else files.filterIndexed { i, _ -> checks[i] }.map { it.path }
        onCommit(message, selected)
    }

    DecisionScreenShell(
        title = stringResource(R.string.action_commit_changes),
        subtitle = stringResource(R.string.label_repo_mode, repoName, mode?.let { stringResource(it.labelRes) } ?: stringResource(R.string.state_mode_not_configured)),
        onBack = onBack,
    content = {
        if (mode == null) {
            Text(
                stringResource(R.string.note_no_commit_mode),
                fontSize = 12.sp,
                color = Primer.WarningTextStrong,
                lineHeight = 18.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Primer.WarningSurface)
                    .padding(10.dp),
            )
        }

        if (files.isNotEmpty()) {
            FactCard(
                stringResource(
                    if (localRepo) R.string.label_changed_files_local else R.string.label_changed_files_scope,
                ),
            ) {
                Column {
                    files.forEachIndexed { i, f ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .then(if (localRepo) Modifier else Modifier.clickable { toggleCheck(i) })
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
                    if (localRepo) {
                        Text(
                            stringResource(R.string.note_commit_local_repo_scope),
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = Primer.TextTertiary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    } else {
                        Text(
                            stringResource(R.string.label_selected_files, selectedCount, files.size),
                            fontSize = 12.sp,
                            color = if (selectedCount > 0) Primer.Green500 else Primer.TextTertiary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        } else {
            DecisionNote(stringResource(R.string.note_mode_single_file))
        }

        FactCard(stringResource(R.string.label_commit_message)) {
            Column(Modifier.padding(12.dp)) {
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.hint_describe_change), fontSize = 13.sp, color = Primer.TextTertiary) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                )
            }
        }

        feedback?.let { FeedbackLine(it, error = true) }
    },
    bottom = {
        TextButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_cancel)) }
        Button(onClick = { attemptCommit() }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_commit)) }
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
        "A" -> Primer.AccentText to Primer.InfoSurfaceStrong
        "D" -> Primer.DangerText to Primer.DangerSurface
        else -> Primer.SuccessTextStrong to Primer.SuccessSurfaceSoft
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
    val context = LocalContext.current
    var name by remember { mutableStateOf(suggestedName) }
    var email by remember { mutableStateOf(suggestedEmail) }
    var save by remember { mutableStateOf(true) }
    var feedback by remember { mutableStateOf<String?>(null) }

    fun doConfirm() {
        if (name.isBlank()) { feedback = context.getString(R.string.error_author_name_required); return }
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) { feedback = context.getString(R.string.error_invalid_email); return }
        onConfirm(name.trim(), email.trim(), save)
    }

    DecisionScreenShell(
        title = stringResource(R.string.label_confirm_commit_identity),
        subtitle = stringResource(R.string.label_first_time_public),
        onBack = onBack,
        content = {
        DecisionNote(stringResource(R.string.note_commit_identity))

        FactCard(stringResource(R.string.label_signature_info)) {
            Column(Modifier.padding(12.dp)) {
                Text(stringResource(R.string.label_author_name_required), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextSecondary)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                    singleLine = true,
                )
                Text(stringResource(R.string.label_author_email_required), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextSecondary, modifier = Modifier.padding(top = 10.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    singleLine = true,
                )
            }
        }

        FactCard(stringResource(R.string.label_save_scope)) {
            Column {
                DecisionOptionRow(stringResource(R.string.label_save_global_default), stringResource(R.string.note_save_global_default), save, OptionTag.RECOMMENDED) { save = true }
                DecisionOptionRow(stringResource(R.string.label_use_once), stringResource(R.string.note_use_once), !save) { save = false }
            }
        }

        DecisionNote(stringResource(R.string.note_noreply_email))
        feedback?.let { FeedbackLine(it, error = true) }
    },
    bottom = {
        TextButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_cancel)) }
        Button(onClick = { doConfirm() }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_save_and_continue)) }
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
    // 本页的确认提示走资源，需要 Context（局部 fun doResolve 在非组合上下文里取值）
    val context = LocalContext.current
    var option by remember { mutableStateOf(0) } // 0=返回修改 1=仍要提交
    var confirmed by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }

    fun doResolve() {
        when (option) {
            0 -> onBack()
            1 -> if (confirmed) onProceed() else feedback = context.getString(R.string.error_confirm_required_short)
        }
    }

    DecisionScreenShell(
        title = stringResource(R.string.state_sensitive_detected),
        subtitle = stringResource(R.string.label_hits_before_commit, hits.size),
        onBack = onBack,
        content = {
        DecisionNote(stringResource(R.string.warning_secrets_detected, hits.size))

        FactCard(stringResource(R.string.label_hit_details)) {
            Column {
                hits.forEach { h ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.label_line_number, h.line), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Primer.DangerText)
                            Text(h.mask, fontSize = 11.sp, color = Primer.DangerText, fontFamily = FontFamily.Monospace, maxLines = 1)
                        }
                        Text(h.kind, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Primer.DangerText, modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Primer.DangerSurface).padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
            }
        }

        FactCard(stringResource(R.string.label_handling)) {
            Column {
                DecisionOptionRow(stringResource(R.string.action_back_to_edit), stringResource(R.string.note_remove_secret), option == 0, OptionTag.RECOMMENDED) { option = 0 }
                DecisionOptionRow(stringResource(R.string.action_commit_anyway), stringResource(R.string.note_tick_secret_confirm), option == 1, OptionTag.DANGER) { option = 1 }
            }
        }

        if (option == 1) {
            Spacer(Modifier.height(4.dp))
            DangerConfirmCard(
                description = stringResource(R.string.warning_revoke_key),
                confirmLabel = stringResource(R.string.confirm_public_checkbox),
                confirmed = confirmed,
                onToggle = { confirmed = !confirmed },
            )
        }
        feedback?.let { FeedbackLine(it, error = true) }
    },
    bottom = {
        TextButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_cancel)) }
        Button(
            onClick = { doResolve() },
            enabled = !(option == 1 && !confirmed),
            colors = ButtonDefaults.buttonColors(containerColor = if (option == 1) Primer.Red500 else Primer.Green500),
            modifier = Modifier.weight(1f),
        ) {
            Text(if (option == 0) stringResource(R.string.action_back_to_edit) else stringResource(R.string.action_commit_anyway), color = Color.White)
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
    val context = LocalContext.current
    var option by remember { mutableStateOf(0) } // 0=恢复 1=丢弃 2=查看远端
    var confirmed by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }

    fun doResolve() {
        when (option) {
            0 -> onRecover()
            1 -> if (confirmed) onDiscard() else feedback = context.getString(R.string.error_confirm_required)
            2 -> onViewRemote()
        }
    }

    DecisionScreenShell(
        title = stringResource(R.string.state_unsaved_draft_found),
        subtitle = stringResource(R.string.label_recover_interrupted_edit),
        onBack = onBack,
        content = {
        DecisionNote(stringResource(R.string.note_draft_interrupted))

        FactCard(stringResource(R.string.label_draft_info)) {
            Column {
                drafts.forEach { d ->
                    FactRow(d.path, stringResource(R.string.label_draft_modified, d.modifiedAt, d.changedLines), mono = true)
                }
            }
        }

        FactCard(stringResource(R.string.label_remote_status)) {
            FactRow(
                stringResource(R.string.label_sha_comparison),
                if (drafts.any { it.remoteChanged }) stringResource(R.string.state_remote_changed) else stringResource(R.string.state_remote_unchanged),
                rightColor = if (drafts.any { it.remoteChanged }) Primer.WarningText else Primer.TextTertiary,
            )
        }

        FactCard(stringResource(R.string.label_recovery_method)) {
            Column {
                DecisionOptionRow(stringResource(R.string.action_restore_draft), stringResource(R.string.note_load_draft, drafts.size), option == 0, OptionTag.RECOMMENDED) { option = 0 }
                DecisionOptionRow(stringResource(R.string.action_discard_draft), stringResource(R.string.note_discard_draft_open_remote), option == 1, OptionTag.DANGER) { option = 1 }
                DecisionOptionRow(stringResource(R.string.action_view_remote_latest), stringResource(R.string.note_discard_open_readonly), option == 2) { option = 2 }
            }
        }

        if (option == 1) {
            Spacer(Modifier.height(4.dp))
            DangerConfirmCard(
                description = stringResource(R.string.warning_drafts_deleted, drafts.size),
                confirmLabel = stringResource(R.string.confirm_discard_drafts_checkbox),
                confirmed = confirmed,
                onToggle = { confirmed = !confirmed },
            )
        }
        feedback?.let { FeedbackLine(it, error = true) }
    },
    bottom = {
        TextButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_cancel)) }
        Button(
            onClick = { doResolve() },
            enabled = !(option == 1 && !confirmed),
            colors = ButtonDefaults.buttonColors(containerColor = if (option == 1) Primer.Red500 else Primer.Green500),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                when (option) {
                    0 -> stringResource(R.string.action_restore_draft)
                    1 -> stringResource(R.string.action_discard_draft)
                    else -> stringResource(R.string.action_view_remote_latest)
                },
                color = Color.White,
            )
        }
    })
}
