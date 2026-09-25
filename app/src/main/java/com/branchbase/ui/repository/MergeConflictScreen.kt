package com.branchbase.ui.repository

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.R
import com.branchbase.core.RustBridge
import com.branchbase.editor.BranchbaseCodeEditor
import com.branchbase.ui.decision.DecisionNote
import com.branchbase.ui.decision.DecisionScreenShell
import com.branchbase.ui.decision.FactCard
import com.branchbase.ui.decision.FactRow
import com.branchbase.ui.decision.parseSensitiveHits
import com.branchbase.ui.log.LogCategory
import com.branchbase.ui.log.Logger
import com.branchbase.ui.theme.CodeSyntax
import com.branchbase.ui.theme.LocalIsDarkTheme
import com.branchbase.ui.theme.Primer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **冲突详情对比页**（D-h 的落点，阶段 5 的 UI 那半）。
 *
 * ## 它回答什么
 *
 * 「合到一半停住了 —— 现在还剩哪几个文件、每个文件两边各改了什么、我该怎么处置」。
 * 逐文件三选一：**用我方 / 用对方 / 手工编辑**；全部解决后「提交合并」；
 * 任何时候都能「放弃合并」（回到合并前，一个字节都不丢）。
 *
 * ## 三条实现约定（都有代价付在前面的理由）
 *
 * 1. **预解析不在这里发起**：弹窗出现那一刻就已经在算了（[MergePreparse]），这一页只读缓存
 *    ——「页面打开后再从头算」是被 D-h 点名不许的两条之一（另一条是把它做成页面状态）；
 * 2. **diff 复用共享渲染**：`parseUnifiedDiff` + `DiffLineRow`（与本地 diff 页、分支对比页同一套）——
 *    冲突块就是 ours ↔ theirs 的 hunk，不需要第二套「冲突块」模型；
 * 3. **「用某一侧」的内容取自索引三方条目**（引擎侧保证），不是工作区那份带 `<<<<<<<` 的文本 ——
 *    拿带标记的文本当「我方」等于把标记一起提交上去。
 *
 * @param startedWith 本次合并**开始时**的冲突清单（「本次共 N 个」只能来自这里：已解决的
 *   引擎不落盘、也不重算）
 */
@Composable
internal fun MergeConflictScreen(
    repoDir: String,
    repoName: String,
    startedWith: List<String>,
    flow: MergeFlowState,
    onBack: () -> Unit,
    onFeedback: (String, Boolean) -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember(repoDir) { mutableStateOf<MergeState?>(null) }
    var loading by remember(repoDir) { mutableStateOf(true) }
    var reloadKey by remember(repoDir) { mutableIntStateOf(0) }
    var busy by remember(repoDir) { mutableStateOf(false) }
    var editing by remember(repoDir) { mutableStateOf<String?>(null) }
    var draft by remember(repoDir) { mutableStateOf("") }
    var message by remember(repoDir) { mutableStateOf("") }
    var messageEdited by remember(repoDir) { mutableStateOf(false) }
    var sensitive by remember(repoDir) { mutableStateOf<List<String>>(emptyList()) }
    var confirmAbort by remember(repoDir) { mutableStateOf(false) }
    var feedback by remember(repoDir) { mutableStateOf<Pair<String, Boolean>?>(null) }
    val preparse = MergePreparse.stateOf(repoDir)

    LaunchedEffect(repoDir, reloadKey) {
        loading = true
        val loaded = withContext(Dispatchers.IO) {
            parseMergeState(RustBridge.gitMergeState(repoDir))
        }
        state = loaded
        if (!messageEdited) message = defaultMergeMessage(loaded)
        loading = false
    }

    val remaining = unresolvedCount(state)
    val canContinue = canContinueMerge(state) && !busy

    /** 提交合并（含敏感扫描：与普通提交同一条口径，扫不了就拦下）。 */
    fun doContinue() {
        if (busy) return
        val raw = RustBridge.scanSensitive(message)
        if (raw == null) {
            Logger.warn(LogCategory.LOCAL_TASK, "敏感扫描", "扫描不可用，已拦下合并提交（$repoName）")
            feedback = context.getString(R.string.error_scan_unavailable_blocked) to false
            return
        }
        val hits = parseSensitiveHits(raw)
        if (hits.isNotEmpty() && sensitive.isEmpty()) {
            Logger.warn(
                LogCategory.LOCAL_TASK,
                "敏感扫描",
                "合并提交信息命中 ${hits.size} 处（$repoName）：" + hits.joinToString("、") { "#${it.line} ${it.kind}" },
            )
            sensitive = hits.map { "#${it.line} ${it.kind}" }
            feedback = context.getString(R.string.warning_sensitive_in_message) to false
            return
        }
        scope.launch {
            busy = true
            val out = RustBridge.gitMergeContinue(
                dir = repoDir,
                message = message,
                authorName = gitAuthorName(context),
                authorEmail = gitAuthorEmail(context),
            )
            busy = false
            val reason = com.branchbase.core.engineErrorOrNull(out)
            if (reason == null) {
                Logger.local("$MERGE_LOG_TAG ▸ $repoName：合并提交 ${out.trim().take(7)}", MERGE_LOG_TAG)
                MergePreparse.clear(repoDir)
                flow.settle()
                onFeedback(context.getString(R.string.toast_merged, out.trim().take(7)), true)
                onChanged()
                onBack()
            } else {
                Logger.warn(LogCategory.LOCAL_TASK, MERGE_LOG_TAG, "$MERGE_LOG_TAG ▸ $repoName：提交合并失败 —— $reason")
                feedback = reason to false
                reloadKey++
            }
        }
    }

    DecisionScreenShell(
        title = stringResource(R.string.title_merge_conflicts),
        subtitle = repoName,
        onBack = onBack,
        bottom = {
            TextButton(
                onClick = { confirmAbort = true },
                enabled = !busy && state?.merging == true,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.action_abort_merge), color = Primer.DangerText)
            }
            TextButton(onClick = { doContinue() }, enabled = canContinue, modifier = Modifier.weight(1f)) {
                Text(
                    if (busy) stringResource(R.string.state_committing) else stringResource(R.string.action_commit_merge),
                    color = if (canContinue) Primer.Blue500 else Primer.TextTertiary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
    ) {
        if (loading) {
            DecisionNote(stringResource(R.string.state_loading))
            return@DecisionScreenShell
        }
        val st = state
        if (st == null || !st.merging) {
            // 不在合并中：如实说明（多半是「刚才已经解决完并提交了」）—— 不画一个空清单
            DecisionNote(stringResource(R.string.state_not_merging))
            return@DecisionScreenShell
        }

        FactCard(stringResource(R.string.label_merge_progress)) {
            FactRow(stringResource(R.string.label_merge_target), st.branch.ifBlank { "—" }, mono = true)
            FactRow(stringResource(R.string.label_merge_remaining), "$remaining")
            if (startedWith.isNotEmpty()) {
                FactRow(stringResource(R.string.label_merge_total), "${startedWith.size}")
            }
            FactRow(stringResource(R.string.label_merge_commit_head), st.mergeHeadSha.take(7), mono = true)
        }

        Text(
            stringResource(R.string.label_merge_message),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Primer.TextSecondary,
            modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp),
        )
        OutlinedTextField(
            value = message,
            onValueChange = { message = it; messageEdited = true },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
        )
        sensitive.forEach { hit ->
            DecisionNote(stringResource(R.string.warning_sensitive_line, hit))
        }

        if (remaining == 0) {
            DecisionNote(stringResource(R.string.state_all_conflicts_resolved))
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                items(st.conflicts, key = { it.path }) { file ->
                    ConflictFileCard(
                        file = file,
                        detail = preparse.let { if (it is MergePreparseState.Ready) it.analysis.detailOf(file.path) else null },
                        preparse = preparse,
                        busy = busy,
                        editing = editing == file.path,
                        draft = draft,
                        onDraft = { draft = it },
                        onRetryPreparse = {
                            MergePreparse.start(repoDir, scope, force = true) { RustBridge.gitAnalyzeConflicts(repoDir) }
                        },
                        onEdit = { content ->
                            editing = file.path
                            draft = content
                        },
                        onCancelEdit = { editing = null },
                        onResolveSide = { side ->
                            scope.launch {
                                busy = true
                                val reason = RustBridge.gitResolveConflict(repoDir, file.path, side.wire)
                                busy = false
                                if (reason == null) {
                                    Logger.local("$MERGE_LOG_TAG ▸ $repoName：${file.path} 用了${if (side == MergeResolveSide.OURS) "我方" else "对方"}", MERGE_LOG_TAG)
                                    editing = null
                                    reloadKey++
                                } else {
                                    feedback = reason to false
                                }
                            }
                        },
                        onSaveManual = {
                            scope.launch {
                                busy = true
                                val reason = RustBridge.gitWriteResolved(repoDir, file.path, draft)
                                busy = false
                                if (reason == null) {
                                    Logger.local("$MERGE_LOG_TAG ▸ $repoName：${file.path} 手工解决已登记", MERGE_LOG_TAG)
                                    editing = null
                                    reloadKey++
                                } else {
                                    feedback = reason to false
                                }
                            }
                        },
                    )
                }
            }
        }

        feedback?.let { (text, ok) ->
            Text(
                text,
                fontSize = 12.sp,
                color = if (ok) Primer.Green500 else Primer.DangerText,
                lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        if (confirmAbort) {
            AlertDialog(
                onDismissRequest = { confirmAbort = false },
                title = {
                    Text(
                        stringResource(R.string.action_abort_merge),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Primer.TextPrimary,
                    )
                },
                text = {
                    Text(
                        stringResource(R.string.confirm_abort_merge_body),
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = Primer.TextSecondary,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmAbort = false
                        scope.runMergeAbort(context, flow, repoDir, repoName, onFeedback, onChanged)
                        onBack()
                    }) { Text(stringResource(R.string.action_abort_merge), color = Primer.DangerText) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmAbort = false }) { Text(stringResource(R.string.action_cancel)) }
                },
            )
        }
    }
}

/**
 * 一个冲突文件：类型 + 三方大小 + ours ↔ theirs 的差异 + 三个处置动作。
 *
 * 二进制文件不给 diff（引擎不给内容），只给「请选一边」——画一个空 diff 比不给更坏。
 */
@Composable
private fun ConflictFileCard(
    file: MergeConflictFile,
    detail: MergeConflictDetail?,
    preparse: MergePreparseState,
    busy: Boolean,
    editing: Boolean,
    draft: String,
    onDraft: (String) -> Unit,
    onRetryPreparse: () -> Unit,
    onEdit: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onResolveSide: (MergeResolveSide) -> Unit,
    onSaveManual: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, Primer.Border, RoundedCornerShape(8.dp)),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                file.path,
                fontSize = 12.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = Primer.TextPrimary,
            )
            Text(
                stringResource(file.kind.labelRes),
                fontSize = 11.sp,
                color = Primer.TextTertiary,
                modifier = Modifier.padding(top = 2.dp),
            )
            detail?.let {
                Text(
                    stringResource(R.string.label_merge_sizes, it.oursSize, it.theirsSize),
                    fontSize = 10.5.sp,
                    color = Primer.TextTertiary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        when {
            preparse is MergePreparseState.Failed -> {
                DecisionNote(stringResource(R.string.error_preparse_failed))
                TextButton(onClick = onRetryPreparse) {
                    Text(stringResource(R.string.action_retry_preparse), color = Primer.Blue500, fontSize = 12.sp)
                }
            }
            preparse is MergePreparseState.Loading || detail == null -> {
                DecisionNote(stringResource(R.string.state_preparse_running))
            }
            detail.binary -> {
                DecisionNote(stringResource(R.string.note_conflict_binary))
            }
            else -> {
                val lines = remember(detail.patch) { parseUnifiedDiff(detail.patch) }
                Column(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                    LazyColumn(Modifier.fillMaxWidth()) {
                        items(lines) { line -> DiffLineRow(line) }
                    }
                }
                if (detail.truncated) {
                    DecisionNote(stringResource(R.string.note_local_diff_truncated))
                }
            }
        }

        if (editing) {
            Column(Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 320.dp).padding(horizontal = 8.dp)) {
                BranchbaseCodeEditor(
                    text = draft,
                    onTextChange = onDraft,
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    darkTheme = LocalIsDarkTheme.current,
                    backgroundColor = CodeSyntax.CodeBg.toArgb(),
                )
                if (detail?.contentTruncated == true) {
                    DecisionNote(stringResource(R.string.note_conflict_content_truncated))
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = onCancelEdit, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    TextButton(onClick = onSaveManual, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.action_mark_resolved), color = Primer.Blue500)
                    }
                }
            }
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
            ) {
                PanelChip(stringResource(R.string.action_use_ours), enabled = !busy) { onResolveSide(MergeResolveSide.OURS) }
                PanelChip(stringResource(R.string.action_use_theirs), enabled = !busy) { onResolveSide(MergeResolveSide.THEIRS) }
                PanelChip(stringResource(R.string.action_edit_manually), enabled = !busy) {
                    // 手工编辑的初值 = **工作区那一份（带冲突标记）**：git 留给人的就是这个形状，
                    // 从空文本开始等于让用户自己把两边再抄一遍。引擎没带回内容时（老 `.so`）退回我方那份
                    onEdit(detail?.worktree?.takeIf { it.isNotEmpty() } ?: detail?.ours.orEmpty())
                }
            }
        }
    }
}
