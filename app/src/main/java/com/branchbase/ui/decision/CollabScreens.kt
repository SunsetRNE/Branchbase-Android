package com.branchbase.ui.decision

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.R
import com.branchbase.core.RustBridge
import com.branchbase.ui.navigation.TabSwitcher
import com.branchbase.ui.repository.encodePath
import com.branchbase.ui.repository.encodeRef
import com.branchbase.ui.repository.parseFileContent
import com.branchbase.ui.log.LogCategory
import com.branchbase.ui.log.Logger
import com.branchbase.ui.theme.Primer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ═══════════════════════════════════════════════════════════════════
// 协作与仓库管理域决策页（规格见 docs/specs/decision-pages-design.md §4）
//   · PrOnestopScreen        P1-1 PR 一条龙（三步）
//   · RepoSettingScreen      P1-2 仓库设置页群
//   · DeleteRepoWarningScreen P1-3 删除本地仓库升级警告
//   · PatInputScreen         P0-5 私有仓库 PAT 输入
//   · PrMergeScreen          P1-5 PR 合并策略
//   · OfflineConflictScreen  P2-4 离线编辑同步冲突
// ═══════════════════════════════════════════════════════════════════

/**
 * ① PR 一条龙（P1-1 · 三步：新建分支 → 提交 → 开 PR）。
 *
 * 三步**都真落盘**（此前第②步是空转：`step = 1 → 2` 之后什么都不做，开出来的 PR 里没有任何改动）：
 * - ① 命名分支：本地校验非空 / 不含空格 / 不与 [branches] 里的现有分支重名（见 [branchNameError]）
 * - ② 提交：解析待提交文件内容 → `getRefSha(base)` → `createBranch` → `commitFiles(branch = 新分支)`
 * - ③ 开 PR：`createPullRequest(head = 新分支)`
 *
 * [changedFiles] 是**仓库内相对路径**清单（宿主从文件页草稿目录接进来），**内容在本页解析**：
 * 优先本地草稿（`files/edit/single/{owner}/{repo}/…`，与文件页同一目录约定），
 * 没有草稿的路径取它在 [baseBranch] 上的当前内容。清单为空时第②步直接拦住 —— 理由见 [commitBlockReason]。
 *
 * [commitMessage] 是**可编辑的初值**（宿主给默认值，第②步能改），提交成功后作为 PR 标题的默认值。
 */
@Composable
fun PrOnestopScreen(
    sessionJson: String,
    owner: String,
    repo: String,
    baseBranch: String,
    commitMessage: String,
    changedFiles: List<String>,
    branches: List<String> = emptyList(),
    onBack: () -> Unit,
    onCreated: (message: String?) -> Unit,
) {
    val context = LocalContext.current
    val host = remember(sessionJson) { runCatching { org.json.JSONObject(sessionJson).optString("host", "github.com") }.getOrDefault("github.com") }
    val token = remember(sessionJson) {
        runCatching { org.json.JSONObject(sessionJson).optJSONObject("token")?.optString("access_token").orEmpty() }.getOrDefault("")
    }
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(0) }
    var branchName by remember { mutableStateOf("patch-1") }
    var message by remember(commitMessage) { mutableStateOf(commitMessage) }
    var title by remember(commitMessage) { mutableStateOf(commitMessage) }
    // 用户是否亲手改过 PR 标题：改过就不再被提交信息覆盖（没改过才跟着提交信息走，见 prTitleAfterCommit）
    var titleEdited by remember { mutableStateOf(false) }
    var description by remember { mutableStateOf(context.getString(R.string.pr_template_default)) }
    var draftPr by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var busyText by remember { mutableStateOf(context.getString(R.string.state_running)) }
    // 本次已提交到哪个分支：步骤③据此判断「能不能开 PR」，失败重试时据此跳过重复建分支
    var committedBranch by remember { mutableStateOf<String?>(null) }
    var committedMessage by remember { mutableStateOf<String?>(null) }
    var committedSha by remember { mutableStateOf<String?>(null) }
    var committedCount by remember { mutableStateOf(0) }

    // 第②步的准入条件（纯函数）：空清单 / 空提交信息都在这里拦，按钮与反馈用同一判据
    val blockReason = commitBlockReason(changedFiles, message)
    val readyToCreatePr = committedBranch == branchName && committedSha != null

    // 按仓库记忆：该仓库上次用过的描述模板自动预填
    val repoKey = "$owner/$repo"
    LaunchedEffect(repoKey) {
        PrMemoryStore.get(context, repoKey)?.template?.takeIf { it.isNotBlank() }?.let { description = it }
    }

    /** 第②步执行体：建分支 → `commitFiles` 提交到该分支。失败即停在第②步，反馈真实原因。 */
    fun submitChanges() {
        val branch = branchName
        val msg = message.trim()
        scope.launch {
            busy = true
            feedback = null
            val taskId = com.branchbase.ui.task.TaskStore.start(
                context,
                com.branchbase.ui.task.TaskKind.COMMIT,
                context.getString(R.string.pr_commit_files_to_branch, changedFiles.size, branch),
            )
            // 锚点：`PR一条龙` —— 这条链路的每一步都要能在日志里对上（没有真机走查时的唯一线索）
            Logger.local("提交开始：$owner/$repo ${changedFiles.size} 个文件 → 分支 $branch（base=$baseBranch）", "PR一条龙")
            // ① 内容：草稿优先，其次取 base 分支上的当前内容。读不全就整体不提交（不静默少提交几个文件）
            busyText = context.getString(R.string.state_reading_commit_files)
            val resolution = resolveCommitFiles(context, host, token, owner, repo, baseBranch, changedFiles)
            if (resolution is ContentResolution.Missing) {
                busy = false
                val reason = context.getString(R.string.error_cannot_read_files, resolution.paths.joinToString("、"))
                Logger.warn(LogCategory.LOCAL_TASK, "PR一条龙", "提交中止：$reason")
                com.branchbase.ui.task.TaskStore.fail(context, taskId, reason)
                feedback = context.getString(R.string.error_commit_reason_hint, reason)
                return@launch
            }
            val files = (resolution as ContentResolution.Ready).files

            // ② base 的 sha：新分支从这里长出来
            busyText = context.getString(R.string.state_reading_base_commits, baseBranch)
            val baseSha = RustBridge.getRefSha(host, token, owner, repo, baseBranch)
            if (baseSha == null) {
                busy = false
                Logger.warn(LogCategory.LOCAL_TASK, "PR一条龙", "提交中止：读不到基准分支 $baseBranch 的 sha")
                com.branchbase.ui.task.TaskStore.fail(context, taskId, context.getString(R.string.error_cannot_read_branch, baseBranch))
                feedback = context.getString(R.string.error_cannot_read_base_commits, baseBranch)
                return@launch
            }

            // ③ 建分支（同一分支上一次已提交成功过就不重复建）
            if (committedBranch != branch) {
                busyText = context.getString(R.string.state_creating_branch, branch)
                val branchErr = RustBridge.createBranch(host, token, owner, repo, branch, baseSha)
                if (branchErr != null) {
                    // 分支已存在：只有它正好停在 base 上才能继续（上次提交失败留下的半成品）；
                    // 已经含别的提交就不能悄悄往上叠，换名重来
                    val existingSha = RustBridge.getRefSha(host, token, owner, repo, branch)
                    when (branchReuseOf(existingSha, baseSha)) {
                        BranchReuse.SAME_AS_BASE -> Unit
                        BranchReuse.DIVERGED -> {
                            busy = false
                            val reason = context.getString(R.string.error_branch_exists_with_commits, branch, baseBranch)
                            Logger.warn(LogCategory.LOCAL_TASK, "PR一条龙", "提交中止：$reason（existing=${existingSha?.take(7)}）")
                            com.branchbase.ui.task.TaskStore.fail(context, taskId, reason)
                            feedback = context.getString(R.string.error_branch_name_taken, reason)
                            return@launch
                        }
                        BranchReuse.ABSENT -> {
                            busy = false
                            Logger.warn(LogCategory.LOCAL_TASK, "PR一条龙", "建分支失败：$branch → $branchErr")
                            com.branchbase.ui.task.TaskStore.fail(context, taskId, branchErr)
                            feedback = context.getString(R.string.error_create_branch_failed, branchErr)
                            return@launch
                        }
                    }
                }
            }

            // ④ 一个 commit 提交全部文件（Git Data API：blobs → tree → commit → 移动 ref）
            busyText = context.getString(R.string.state_committing_files, files.size)
            val sha = RustBridge.commitFiles(host, token, owner, repo, branch, msg, files)
            busy = false
            if (sha == null || sha.startsWith("ERROR:")) {
                val reason = sha?.removePrefix("ERROR:")?.take(300) ?: context.getString(R.string.error_engine_no_result_commit)
                Logger.warn(LogCategory.LOCAL_TASK, "PR一条龙", "提交失败：$branch ← $reason")
                com.branchbase.ui.task.TaskStore.fail(context, taskId, reason)
                feedback = context.getString(R.string.error_commit_failed_reason, reason)
                return@launch
            }
            Logger.local("提交成功：$branch @ ${sha.take(7)} · ${files.size} 个文件", "PR一条龙")
            committedBranch = branch
            committedMessage = msg
            committedSha = sha
            committedCount = files.size
            com.branchbase.ui.task.TaskStore.success(context, taskId, context.getString(R.string.state_committed_files, files.size, sha.take(7)))
            // 「提交信息将作为 PR 默认标题」：只在用户没亲手改过标题时兑现
            title = prTitleAfterCommit(title, titleEdited, msg)
            feedback = null
            step = 2
        }
    }

    /** 第③步执行体：只开 PR —— 分支与提交已经在第②步完成。 */
    fun createPr() {
        if (title.isBlank()) { feedback = context.getString(R.string.error_pr_title_required); return }
        if (!readyToCreatePr) {
            feedback = context.getString(R.string.error_branch_has_no_commit, branchName)
            return
        }
        scope.launch {
            busy = true
            busyText = context.getString(R.string.state_creating_pr)
            feedback = null
            val taskId = com.branchbase.ui.task.TaskStore.start(context, com.branchbase.ui.task.TaskKind.PR, context.getString(R.string.state_creating_pr_detail, branchName, baseBranch))
            val prErr = withContext(Dispatchers.IO) {
                RustBridge.createPullRequest(host, token, owner, repo, title, description, branchName, baseBranch, draftPr)
            }
            busy = false
            if (prErr == null) {
                Logger.local("PR 已创建：$branchName → $baseBranch${if (draftPr) "（草稿）" else ""}", "PR一条龙")
                com.branchbase.ui.task.TaskStore.success(context, taskId, context.getString(R.string.state_pr_created))
                PrMemoryStore.save(context, repoKey, template = description)
                onCreated(context.getString(R.string.state_pr_created_detail, branchName, baseBranch))
            } else {
                Logger.warn(LogCategory.LOCAL_TASK, "PR一条龙", "创建 PR 失败：$branchName → $baseBranch ← $prErr")
                com.branchbase.ui.task.TaskStore.fail(context, taskId, prErr)
                feedback = context.getString(R.string.error_create_pr_failed, prErr)
            }
        }
    }

    fun next() {
        when (step) {
            0 -> {
                val err = branchNameError(branchName, branches)
                if (err != null) feedback = err else { feedback = null; step = 1 }
            }
            1 -> {
                val block = commitBlockReason(changedFiles, message)
                if (block != null) { feedback = block; return }
                // 提交过、内容与提交信息都没变 → 直接进第③步，不重复产生 commit
                if (committedBranch == branchName && committedSha != null && committedMessage == message.trim()) {
                    feedback = null
                    step = 2
                    return
                }
                submitChanges()
            }
            else -> createPr()
        }
    }

    DecisionScreenShell(
        title = stringResource(R.string.nav_pr_onestop),
        subtitle = stringResource(R.string.label_step_of_three, owner, repo, step + 1),
        onBack = onBack,
    content = {
        // 三步向导（填分支 → 确认信息 → 创建）：步骤之间没有层级，用同级淡入淡出提示「翻到下一步」
        TabSwitcher(state = step, modifier = Modifier.fillMaxSize(), label = "pr-steps") { step ->
            when (step) {
                0 -> {
                    DecisionNote(stringResource(R.string.pr_onestop_intro, baseBranch, baseBranch))
                    FactCard(stringResource(R.string.label_branch_naming)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(stringResource(R.string.label_new_branch_name), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextSecondary)
                            OutlinedTextField(
                                value = branchName,
                                onValueChange = { branchName = it },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                                singleLine = true,
                            )
                            Text(stringResource(R.string.label_based_on), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextSecondary, modifier = Modifier.padding(top = 10.dp))
                            OutlinedTextField(
                                value = baseBranch,
                                onValueChange = {},
                                enabled = false,
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                                singleLine = true,
                            )
                        }
                    }
                    // 文案只说这里**真的**校验了什么；保护规则本地拿不到，就说清楚是谁在什么时候校验
                    DecisionNote(
                        if (branches.isEmpty()) {
                            stringResource(R.string.note_branch_name_rules_no_list)
                        } else {
                            stringResource(R.string.note_branch_name_rules_with_list, owner, repo, branches.size)
                        },
                    )
                    if (changedFiles.isEmpty()) DecisionNote(NO_CHANGES_HINT)
                }
                1 -> {
                    DecisionNote(stringResource(R.string.note_real_commit, baseBranch))
                    FactCard(if (changedFiles.isEmpty()) stringResource(R.string.label_changed_files_zero) else stringResource(R.string.label_changed_files_count, changedFiles.size)) {
                        Column {
                            if (changedFiles.isEmpty()) {
                                Text(
                                    NO_CHANGES_HINT,
                                    fontSize = 12.sp,
                                    color = Primer.Red500,
                                    lineHeight = 18.sp,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                )
                            } else {
                                changedFiles.forEach { f -> FactRow(f, mono = true) }
                            }
                        }
                    }
                    FactCard(stringResource(R.string.label_commit_message)) {
                        Column(Modifier.padding(12.dp)) {
                            OutlinedTextField(
                                value = message,
                                onValueChange = { message = it },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                            )
                            Text(
                                stringResource(R.string.note_commit_message_becomes_title),
                                fontSize = 11.sp,
                                color = Primer.TextTertiary,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                    if (committedSha != null && committedBranch == branchName) {
                        DecisionNote(
                            if (committedMessage != message.trim()) {
                                stringResource(R.string.note_committed_message_changed, committedSha!!.take(7), committedCount)
                            } else {
                                stringResource(R.string.note_committed_continue, committedSha!!.take(7), committedCount, branchName)
                            },
                        )
                    }
                }
                else -> {
                    FactCard(stringResource(R.string.label_target)) {
                        FactRow("base", stringResource(R.string.label_target_base, baseBranch))
                        FactRow("head", stringResource(R.string.label_target_head, branchName))
                    }
                    FactCard(stringResource(R.string.label_this_commit)) {
                        Column {
                            FactRow("commit", committedSha?.take(7) ?: stringResource(R.string.state_no_commit_yet), mono = true)
                            FactRow(stringResource(R.string.label_files), pluralStringResource(R.plurals.label_file_count, committedCount, committedCount))
                            FactRow(stringResource(R.string.label_commit_message), committedMessage.orEmpty())
                        }
                    }
                    FactCard(stringResource(R.string.label_pr_info)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(stringResource(R.string.label_title), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextSecondary)
                            OutlinedTextField(
                                value = title,
                                onValueChange = { title = it; titleEdited = true },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                                singleLine = true,
                            )
                            Text(stringResource(R.string.label_description_prefilled), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextSecondary, modifier = Modifier.padding(top = 10.dp))
                            OutlinedTextField(
                                value = description,
                                onValueChange = { description = it },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                            )
                        }
                    }
                    FactCard(stringResource(R.string.label_options)) {
                        Row(
                            Modifier.fillMaxWidth().clickable { draftPr = !draftPr }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.label_draft_pr), fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
                                Text(stringResource(R.string.note_draft_pr), fontSize = 11.5.sp, color = Primer.TextTertiary)
                            }
                            Switch(checked = draftPr, onCheckedChange = { draftPr = it }, colors = SwitchDefaults.colors(checkedTrackColor = Primer.Green500))
                        }
                    }
                    if (!readyToCreatePr) {
                        DecisionNote(stringResource(R.string.warning_no_commit_before_pr, branchName))
                    }
                }
            }
        }
        feedback?.let { FeedbackLine(it, error = true) }
        if (busy) FeedbackLine(busyText)
    },
    bottom = {
        TextButton(onClick = { if (step == 0) onBack() else step-- }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(if (step == 0) stringResource(R.string.action_cancel) else stringResource(R.string.action_previous)) }
        // 第②步的空清单/空提交信息直接禁用「提交并继续」：没有改动就不该往下走（见 commitBlockReason）
        Button(
            onClick = { next() },
            enabled = !busy && (step != 1 || blockReason == null),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                when (step) {
                    0 -> stringResource(R.string.action_next)
                    1 -> stringResource(R.string.action_commit_and_continue)
                    else -> if (draftPr) stringResource(R.string.action_create_draft_pr) else stringResource(R.string.action_create_pr)
                },
            )
        }
    })
}

/**
 * ② 仓库设置页群（P1-2 · 替换 repository-prototype ⑨ 占位）。
 *
 * 三块事实卡都只写**拿得到的事实**：
 * - 通用：默认分支单选（真落盘 `updateDefaultBranch`）
 * - 分支管理：合并状态由用户点「检查」后按 `compareBranches` 真判定，没检查就写「未检查」——
 *   不再拿写死的 `setOf("patch-1")` 当数据；删除分支要真勾选，删成功后清单立刻少一行
 * - 危险区：挽留统计取 `GET /repos/{o}/{r}` 的星标 / 复刻真值；取不到就写「统计不可用」，不填估算值
 *
 * [onFeedback] 每次有结果都回传（含失败），宿主据此把「默认分支已切换为 x」这类结果冒泡到仓库页。
 */
@Composable
fun RepoSettingScreen(
    sessionJson: String,
    owner: String,
    repo: String,
    branches: List<String>,
    defaultBranch: String,
    onBack: () -> Unit,
    onFeedback: (message: String, error: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val repoName = "$owner/$repo"
    val host = remember(sessionJson) { runCatching { org.json.JSONObject(sessionJson).optString("host", "github.com") }.getOrDefault("github.com") }
    val token = remember(sessionJson) {
        runCatching { org.json.JSONObject(sessionJson).optJSONObject("token")?.optString("access_token").orEmpty() }.getOrDefault("")
    }
    val scope = rememberCoroutineScope()
    var selectedDefault by remember { mutableStateOf(defaultBranch) }
    // 分支清单是宿主的入参（仓库页拉的）。删除成功后要立刻少一行 —— 远端已经没有了，
    // 留着那行会让人以为还能再删一次（旧实现：删完清单纹丝不动）
    var knownBranches by remember(branches) { mutableStateOf(branches) }
    // 合并状态：缺键 = 未检查（不猜）。检查是一次显式动作，每个分支一次 compare 请求
    var mergeStates by remember(branches) { mutableStateOf<Map<String, MergeState>>(emptyMap()) }
    var checkingMerged by remember { mutableStateOf(false) }
    var repoStats by remember { mutableStateOf<RepoStats?>(null) }
    var statsChecked by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var deleteBranchTarget by remember { mutableStateOf<String?>(null) }
    var deleteBranchConfirm by remember { mutableStateOf(false) }
    var deleteRepoConfirm by remember { mutableStateOf(false) }

    // 挽留统计：直接拿得到的是星标 / 复刻（提交数、贡献者要另外的端点，没取就不显示，不填 0）
    LaunchedEffect(owner, repo) {
        val json = withContext(Dispatchers.IO) { RustBridge.getRepoInfo(host, token, owner, repo) }
        repoStats = parseRepoStats(json?.takeIf { !it.startsWith("ERROR:") })
        statsChecked = true
        // 锚点：`决策页` —— 统计取不到时页面只会说「统计不可用」，日志要能回答为什么
        if (repoStats == null) {
            Logger.warn(
                LogCategory.NETWORK,
                "决策页",
                "仓库统计取不到（$owner/$repo）：" + (json?.take(120) ?: context.getString(R.string.error_engine_no_result)),
            )
        }
    }

    fun switchDefault(b: String) {
        scope.launch {
            busy = true
            val err = withContext(Dispatchers.IO) { RustBridge.updateDefaultBranch(host, token, owner, repo, b) }
            busy = false
            if (err == null) {
                selectedDefault = b
                // 合并状态是「相对某个默认分支」的结论，基准换了旧结论立刻作废
                mergeStates = emptyMap()
                feedback = context.getString(R.string.toast_default_branch_switched, b)
                onFeedback(context.getString(R.string.toast_default_branch_switched, b), false)
            } else {
                feedback = context.getString(R.string.error_switch_failed, err)
                onFeedback(context.getString(R.string.error_switch_default_failed, err), true)
            }
        }
    }

    /** 按需真实判定「已合并」：某分支相对默认分支 ahead_by = 0 = 没有独有提交。 */
    fun checkMergedStates() {
        val targets = branchesToCheck(knownBranches, selectedDefault, MERGE_CHECK_LIMIT)
        if (targets.isEmpty()) { feedback = context.getString(R.string.state_no_branches_to_check); return }
        scope.launch {
            checkingMerged = true
            feedback = null
            val out = LinkedHashMap<String, MergeState>()
            withContext(Dispatchers.IO) {
                targets.forEach { b ->
                    val json = RustBridge.compareBranches(host, token, owner, repo, selectedDefault, b)
                    out[b] = mergeStateOf(parseAheadBy(json))
                }
            }
            mergeStates = out
            checkingMerged = false
            val skipped = (knownBranches.count { it != selectedDefault } - targets.size).coerceAtLeast(0)
            // 锚点：`决策页` —— 「已合并」是算出来的（ahead_by == 0），把每个分支的结论记下来便于复核
            Logger.local(
                "已合并检查（$owner/$repo 相对 $selectedDefault）：" +
                    out.entries.joinToString("、") { "${it.key}=${mergeStateLabel(it.value)}" } +
                    if (skipped > 0) "（另有 $skipped 个超上限未检查）" else "",
                "决策页",
            )
            feedback = context.getString(R.string.state_checked_branches, targets.size, selectedDefault) +
                if (skipped > 0) context.getString(R.string.note_merge_check_limit, MERGE_CHECK_LIMIT, skipped) else ""
        }
    }

    fun removeBranch(b: String) {
        scope.launch {
            busy = true
            val err = withContext(Dispatchers.IO) { RustBridge.deleteBranch(host, token, owner, repo, b) }
            busy = false
            deleteBranchTarget = null
            deleteBranchConfirm = false
            if (err == null) {
                knownBranches = knownBranches.filterNot { it == b }
                mergeStates = mergeStates - b
                feedback = context.getString(R.string.toast_branch_deleted, b)
                onFeedback(context.getString(R.string.toast_branch_deleted, b), false)
            } else {
                feedback = context.getString(R.string.error_delete_failed, err)
                onFeedback(context.getString(R.string.error_delete_branch_failed, b, err), true)
            }
        }
    }

    fun removeRepo() {
        scope.launch {
            busy = true
            val err = withContext(Dispatchers.IO) { RustBridge.deleteRepo(host, token, owner, repo) }
            busy = false
            if (err == null) {
                feedback = context.getString(R.string.state_repo_deleted)
                onFeedback(context.getString(R.string.toast_repo_deleted, repoName), false)
            } else {
                feedback = context.getString(R.string.error_delete_failed, err)
                onFeedback(context.getString(R.string.error_delete_repo_failed, repoName, err), true)
            }
        }
    }

    DecisionScreenShell(
        title = stringResource(R.string.nav_repo_settings),
        subtitle = stringResource(R.string.label_repo_settings_sub, repoName),
        onBack = onBack,
        content = {
        FactCard(stringResource(R.string.label_general)) {
            Column {
                Text(stringResource(R.string.label_default_branch), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextSecondary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp))
                knownBranches.forEach { b ->
                    Row(
                        Modifier.fillMaxWidth().clickable { if (b != selectedDefault && !busy) switchDefault(b) }.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(b, fontSize = 12.sp, color = if (b == selectedDefault) Primer.Blue500 else Primer.TextSecondary, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                        if (b == selectedDefault) Text(stringResource(R.string.state_current_check), fontSize = 11.sp, color = Primer.Blue500)
                    }
                }
            }
            DecisionNote(stringResource(R.string.note_default_branch_impact))
        }

        FactCard(stringResource(R.string.nav_branch_manage)) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.label_merge_status, selectedDefault),
                        fontSize = 11.sp,
                        color = Primer.TextTertiary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (checkingMerged) stringResource(R.string.state_checking_ellipsis) else stringResource(R.string.action_check),
                        fontSize = 11.5.sp,
                        color = if (checkingMerged) Primer.TextTertiary else Primer.Blue500,
                        modifier = Modifier.clickable(enabled = !checkingMerged && !busy) { checkMergedStates() },
                    )
                }
                knownBranches.forEach { b ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(b, fontSize = 12.sp, color = Primer.TextSecondary, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                        if (b == selectedDefault) {
                            Text(stringResource(R.string.label_default_branch), fontSize = 11.sp, color = Primer.Blue500)
                        } else {
                            val state = mergeStates[b] ?: MergeState.Unchecked
                            Text(mergeStateLabel(state), fontSize = 11.sp, color = mergeStateColor(state))
                        }
                        if (b != selectedDefault) {
                            Spacer(Modifier.width(10.dp))
                            Text(
                                stringResource(R.string.action_delete),
                                fontSize = 11.sp,
                                color = Primer.Red500,
                                modifier = Modifier.clickable {
                                    if (!busy) { deleteBranchTarget = b; deleteBranchConfirm = false }
                                },
                            )
                        }
                    }
                }
            }
            DecisionNote(stringResource(R.string.note_merged_semantics, selectedDefault))
        }

        FactCard(stringResource(R.string.label_danger_zone)) {
            Column {
                Row(
                    Modifier.fillMaxWidth().clickable { deleteRepoConfirm = !deleteRepoConfirm }.padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.action_delete_repo), fontSize = 12.sp, color = Primer.TextSecondary, modifier = Modifier.weight(1f))
                    Text(if (deleteRepoConfirm) stringResource(R.string.state_confirmed) else stringResource(R.string.action_tap_to_confirm), fontSize = 11.sp, color = Primer.Red500)
                }
            }
        }
        // 挽留统计：三种状态都说实话 —— 有真值 / 还在读 / 读不到（不填估算值）
        val statsText = repoStatsSummary(repoStats)
        DecisionNote(
            when {
                statsText != null -> stringResource(R.string.label_retention_stats, statsText)
                !statsChecked -> stringResource(R.string.state_reading_repo_stats)
                else -> stringResource(R.string.note_repo_stats_unavailable)
            },
        )

        deleteBranchTarget?.let { b ->
            val state = mergeStates[b] ?: MergeState.Unchecked
            Spacer(Modifier.height(4.dp))
            DangerConfirmCard(
                description = stringResource(R.string.confirm_delete_remote_branch, b, mergeStateLabel(state)),
                confirmLabel = stringResource(R.string.confirm_delete_branch_checkbox),
                confirmed = deleteBranchConfirm,
                onToggle = { deleteBranchConfirm = !deleteBranchConfirm },
            )
        }
        feedback?.let { FeedbackLine(it, error = true) }
        if (busy) FeedbackLine(stringResource(R.string.state_running))
    },
    bottom = {
        TextButton(onClick = onBack, enabled = !busy, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_cancel)) }
        if (deleteBranchTarget != null) {
            Button(
                onClick = { deleteBranchTarget?.let { removeBranch(it) } },
                enabled = !busy && deleteBranchConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = Primer.Red500),
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.action_delete_branch), color = Color.White) }
        } else {
            Button(
                onClick = { if (deleteRepoConfirm) removeRepo() else feedback = context.getString(R.string.error_confirm_in_danger_zone) },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = Primer.Red500),
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.action_delete_repo), color = Color.White) }
        }
    })
}

/**
 * ③ 删除本地仓库「未推送提交」升级警告（P1-3）。
 *
 * [stats] 是**真实**仓库统计（宿主给什么显示什么）。默认 null = 宿主没有这个数据 ——
 * 此时整行不显示，绝不填「12 星标 · 3 复刻」这类估算值（旧实现就是写死的演示数字）。
 */
@Composable
fun DeleteRepoWarningScreen(
    repoName: String,
    unpushed: List<UnpushedCommit>,
    stats: RepoStats? = null,
    onBack: () -> Unit,
    onPushFirst: () -> Unit,
    onDelete: () -> Unit,
) {
    var option by remember { mutableStateOf(0) } // 0=先推送 1=仍要删除
    var confirmed by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }

    DecisionScreenShell(
        title = stringResource(R.string.action_delete_local_repo),
        subtitle = stringResource(R.string.label_upgrade_warning, repoName),
        onBack = onBack,
        content = {
        DecisionNote(stringResource(R.string.warning_unpushed_commits, unpushed.size))

        // 只有拿到真值才显示这一行（拿不到就不显示，见 KDoc）
        repoStatsSummary(stats)?.let { DecisionNote(stringResource(R.string.label_repo_stats, it)) }

        FactCard(stringResource(R.string.label_unpushed_commits)) {
            Column {
                unpushed.forEach { c -> FactRow("${c.sha}  ${c.message}", mono = true) }
            }
        }

        FactCard(stringResource(R.string.label_handling)) {
            Column {
                DecisionOptionRow(stringResource(R.string.action_push_then_delete), stringResource(R.string.note_push_flow_redirect), option == 0, OptionTag.RECOMMENDED) { option = 0 }
                DecisionOptionRow(stringResource(R.string.action_delete_anyway), stringResource(R.string.note_tick_to_enable_delete), option == 1, OptionTag.DANGER) { option = 1 }
            }
        }

        if (option == 1) {
            Spacer(Modifier.height(4.dp))
            DangerConfirmCard(
                description = stringResource(R.string.confirm_discard_local_repo, unpushed.size),
                confirmLabel = stringResource(R.string.confirm_checkbox_enable),
                confirmed = confirmed,
                onToggle = { confirmed = !confirmed },
            )
        }
        feedback?.let { FeedbackLine(it, error = true) }
    },
    bottom = {
        val context = LocalContext.current
        TextButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_cancel)) }
        Button(
            onClick = { if (option == 0) onPushFirst() else if (confirmed) onDelete() else feedback = context.getString(R.string.error_confirm_required_short) },
            enabled = !(option == 1 && !confirmed),
            colors = ButtonDefaults.buttonColors(containerColor = if (option == 0) Primer.Green500 else Primer.Red500),
            modifier = Modifier.weight(1f),
        ) {
            Text(if (option == 0) stringResource(R.string.action_push_then_delete) else stringResource(R.string.action_delete_anyway), color = Color.White)
        }
    })
}

/**
 * ④ 私有仓库 PAT 输入（P0-5 · 仅内存，不写日志/不落盘）。
 */
@Composable
fun PatInputScreen(
    onBack: () -> Unit,
    /**
     * 校验令牌并返回它的身份（`@login`）；`null` = 无效 / 权限不足 / 拿不到。
     *
     * 由调用方注入（它才知道 host）—— 校验放在这里做，是为了让「输错令牌」**当场可见**，
     * 而不是覆盖上去、加载再失败一次才回到本页。
     */
    validate: suspend (String) -> String?,
    /** 记住范围提示（仓库级凭据）。`null` = 不显示「记住」开关（只按本次会话处理）。 */
    rememberLabel: String? = null,
    /** @param login `validate` 探到的身份；@param remember 用户是否勾了「记住」 */
    onConfirm: (token: String, login: String, remember: Boolean) -> Unit,
) {
    val context = LocalContext.current
    var token by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    /** 校验通过的身份（`@login`）；null = 还没验过。 */
    var verifiedLogin by remember { mutableStateOf<String?>(null) }
    var remember by remember { mutableStateOf(rememberLabel != null) }
    val scope = rememberCoroutineScope()

    DecisionScreenShell(
        title = stringResource(R.string.state_token_required),
        subtitle = stringResource(R.string.state_auth_failed_private),
        onBack = onBack,
        content = {
        DecisionNote(stringResource(R.string.note_private_needs_pat))

        FactCard(stringResource(R.string.label_token)) {
            Column(Modifier.padding(12.dp)) {
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it; verifiedLogin = null; feedback = null },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.hint_token_placeholder), fontSize = 13.sp, color = Primer.TextTertiary) },
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        Text(if (visible) stringResource(R.string.action_hide) else stringResource(R.string.action_show), fontSize = 12.sp, color = Primer.Blue500, modifier = Modifier.clickable { visible = !visible })
                    },
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    singleLine = true,
                )
                Text(stringResource(R.string.note_token_not_logged), fontSize = 11.sp, color = Primer.TextTertiary, modifier = Modifier.padding(top = 6.dp))
                verifiedLogin?.let {
                    Text(stringResource(R.string.state_identified_as, it), fontSize = 11.5.sp, color = Primer.SuccessText, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }

        FactCard(stringResource(R.string.label_handling)) {
            Column {
                DecisionOptionRow(
                    stringResource(R.string.action_verify_and_continue),
                    verifiedLogin?.let { stringResource(R.string.note_open_as_identity, it) }
                        ?: stringResource(R.string.note_token_validated_first),
                    true,
                    OptionTag.RECOMMENDED,
                ) {}
                DecisionOptionRow(stringResource(R.string.action_reauth_oauth), stringResource(R.string.note_reauth_oauth_path), false) {}
            }
        }

        rememberLabel?.let { label ->
            FactCard(stringResource(R.string.label_remember_scope)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        androidx.compose.material3.Switch(checked = remember, onCheckedChange = { remember = it })
                        Spacer(Modifier.width(10.dp))
                        Text(label, fontSize = 12.5.sp, color = Primer.TextPrimary)
                    }
                    Text(
                        stringResource(R.string.note_credential_fallback),
                        fontSize = 11.sp,
                        color = Primer.TextTertiary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
        feedback?.let { FeedbackLine(it, error = true) }
    },
    bottom = {
        TextButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_cancel)) }
        Button(
            onClick = {
                val t = token.trim()
                if (t.isBlank()) { feedback = context.getString(R.string.error_pat_required); return@Button }
                if (busy) return@Button
                if (verifiedLogin != null) { onConfirm(t, verifiedLogin!!, remember); return@Button }
                // 先验一次：输错当场可见，而不是覆盖上去再失败一遍
                scope.launch {
                    busy = true
                    feedback = null
                    val login = validate(t)
                    busy = false
                    if (login.isNullOrBlank()) {
                        feedback = context.getString(R.string.error_token_invalid_scope)
                    } else {
                        verifiedLogin = login
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.weight(1f),
        ) { Text(if (busy) stringResource(R.string.state_checking) else if (verifiedLogin != null) stringResource(R.string.action_open_with_token) else stringResource(R.string.action_verify)) }
    })
}

/**
 * ⑤ PR 合并策略（P1-5 · squash 默认 + 合并后删分支）。
 */
@Composable
fun PrMergeScreen(
    sessionJson: String,
    owner: String,
    repo: String,
    prNumber: Int,
    prTitle: String,
    headBranch: String,
    baseBranch: String,
    onBack: () -> Unit,
    /**
     * @param message 合并结果原话（null = 合并页没有附加说明，由调用方给默认文案）
     * @param partial 是否**半成功**（合并成功但删分支失败）。
     *   这是个**事实**，由产生方给出 —— 调用方不要去解析 [message] 里有没有「失败」二字：
     *   文案迟早要抽成资源，那时 `contains("失败")` 在英文界面下永不成立，
     *   半成功会被渲染成纯成功（不崩溃，只是把「有件事没做完」藏了起来）。
     */
    onMerged: (message: String?, partial: Boolean) -> Unit,
) {
    val host = remember(sessionJson) { runCatching { org.json.JSONObject(sessionJson).optString("host", "github.com") }.getOrDefault("github.com") }
    val token = remember(sessionJson) {
        runCatching { org.json.JSONObject(sessionJson).optJSONObject("token")?.optString("access_token").orEmpty() }.getOrDefault("")
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var strategy by remember { mutableStateOf(0) } // 0=squash 1=merge 2=rebase
    var deleteBranch by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }

    // 按仓库记忆：合并策略与「合并后删分支」预选
    val repoKey = "$owner/$repo"
    LaunchedEffect(repoKey) {
        PrMemoryStore.get(context, repoKey)?.let { m ->
            strategy = m.mergeStrategy.coerceIn(0, 2)
            deleteBranch = m.deleteBranch
        }
    }

    // 策略下标三处必须一一对应：`methods[strategy]`（发给 mergePullRequest 的字符串）、
    // PrMemoryEntity.mergeStrategy（0=squash 1=merge 2=rebase）、以及展示名。
    // 展示名只在 PrMemoryStore.strategyLabels 里存一份 —— 本页曾经自己又写了一份 names，两份必然分叉。
    val methods = listOf("squash", "merge", "rebase")
    val names = methods.indices.map { i -> PrMemoryStore.strategyLabels.getOrElse(i) { methods[i] } }

    fun doMerge() {
        scope.launch {
            busy = true
            feedback = null
            val taskId = com.branchbase.ui.task.TaskStore.start(context, com.branchbase.ui.task.TaskKind.MERGE, context.getString(R.string.action_merge_pr, prNumber))
            Logger.local("合并开始：$owner/$repo #$prNumber 策略=${methods[strategy]} 删分支=$deleteBranch", "PR合并")
            val err = withContext(Dispatchers.IO) {
                RustBridge.mergePullRequest(host, token, owner, repo, prNumber, methods[strategy])
            }
            if (err != null) {
                Logger.warn(LogCategory.REMOTE_EXEC, "PR合并", "合并失败：#$prNumber 策略=${methods[strategy]} ← $err")
                busy = false
                feedback = context.getString(R.string.error_merge_failed, err)
                return@launch
            }
            var note = context.getString(R.string.state_merged_pr, prNumber)
            // 半成功的判据在这里产生（删分支失败），跟着 note 一起交出去
            var partial = false
            if (deleteBranch) {
                val delErr = withContext(Dispatchers.IO) { RustBridge.deleteBranch(host, token, owner, repo, headBranch) }
                if (delErr != null) {
                    partial = true
                    Logger.warn(LogCategory.REMOTE_EXEC, "PR合并", "合并成功但删分支失败：$headBranch ← $delErr")
                    note += context.getString(R.string.suffix_branch_delete_failed, delErr)
                } else {
                    Logger.local("已删除 head 分支：$headBranch", "PR合并")
                }
            }
            Logger.local("合并成功：#$prNumber · $note", "PR合并")
            busy = false
            com.branchbase.ui.task.TaskStore.success(context, taskId, note)
            PrMemoryStore.save(context, repoKey, strategy = strategy, deleteBranch = deleteBranch)
            onMerged(note, partial)
        }
    }

    val descs = listOf(
        stringResource(R.string.merge_strategy_squash_desc),
        stringResource(R.string.merge_strategy_merge_desc),
        stringResource(R.string.merge_strategy_rebase_desc, baseBranch),
    )

    DecisionScreenShell(
        title = stringResource(R.string.label_merge_pr),
        subtitle = "$prTitle · $headBranch → $baseBranch",
        onBack = onBack,
        content = {
        // 本页不查冲突（没有 compare/mergeable 调用）—— 只说「谁会在什么时候判」，不替 GitHub 下结论
        DecisionNote(stringResource(R.string.note_merge_decided_by_github, headBranch, baseBranch))

        FactCard(stringResource(R.string.label_merge_strategy)) {
            Column {
                names.forEachIndexed { i, n ->
                    DecisionOptionRow(n, descs.getOrElse(i) { "" }, strategy == i, if (i == 0) OptionTag.RECOMMENDED else OptionTag.NONE) { strategy = i }
                }
            }
        }

        FactCard(stringResource(R.string.label_after_merge)) {
            Row(
                Modifier.fillMaxWidth().clickable { deleteBranch = !deleteBranch }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.label_delete_head_branch, headBranch), fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
                    Text(stringResource(R.string.note_delete_head_branch), fontSize = 11.5.sp, color = Primer.TextTertiary)
                }
                Switch(checked = deleteBranch, onCheckedChange = { deleteBranch = it }, colors = SwitchDefaults.colors(checkedTrackColor = Primer.Green500))
            }
        }

        DecisionNote(stringResource(R.string.note_strategy_remembered))
        feedback?.let { FeedbackLine(it, error = true) }
        if (busy) FeedbackLine(stringResource(R.string.state_running))
    },
    bottom = {
        TextButton(onClick = onBack, enabled = !busy, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_cancel)) }
        Button(onClick = { doMerge() }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_merge_with_strategy, names[strategy])) }
    })
}

/**
 * ⑥ 离线编辑同步冲突（P2-4 · 并排 diff 事实区 + 三选项）。
 */
@Composable
fun OfflineConflictScreen(
    fileName: String,
    localContent: String,
    remoteContent: String,
    onBack: () -> Unit,
    onChoose: (choice: String) -> Unit,
) {
    val context = LocalContext.current
    var option by remember { mutableStateOf(0) } // 0=保留本地 1=放弃本地 2=复制远端
    var confirmed by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }

    val localLines = localContent.lines().take(6)
    val remoteLines = remoteContent.lines().take(6)

    DecisionScreenShell(
        title = stringResource(R.string.state_sync_conflict),
        subtitle = stringResource(R.string.label_multi_device_edit, fileName),
        onBack = onBack,
        content = {
        DecisionNote(stringResource(R.string.note_offline_conflict))

        FactCard(stringResource(R.string.label_side_by_side, fileName)) {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.label_local_draft_offline), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Primer.Green500, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth().background(Primer.SuccessSurface).padding(vertical = 6.dp))
                    localLines.forEach { l ->
                        Text(l.ifEmpty { " " }, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = Primer.SuccessText, maxLines = 1, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.label_remote_new_version), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Primer.Blue500, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth().background(Primer.InfoSurface).padding(vertical = 6.dp))
                    remoteLines.forEach { l ->
                        Text(l.ifEmpty { " " }, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = Primer.AccentText, maxLines = 1, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                }
            }
        }

        FactCard(stringResource(R.string.label_handling)) {
            Column {
                DecisionOptionRow(stringResource(R.string.action_keep_local), stringResource(R.string.note_local_unchanged_rejected), option == 0, OptionTag.RECOMMENDED) { option = 0 }
                DecisionOptionRow(stringResource(R.string.action_discard_local_load_remote), stringResource(R.string.note_discard_local_permanent), option == 1, OptionTag.DANGER) { option = 1 }
                DecisionOptionRow(stringResource(R.string.action_copy_remote_as_new), stringResource(R.string.note_keep_both_remote_copy, fileName), option == 2) { option = 2 }
            }
        }

        if (option == 1) {
            Spacer(Modifier.height(4.dp))
            DangerConfirmCard(
                description = stringResource(R.string.warning_local_draft_lost),
                confirmLabel = stringResource(R.string.confirm_discard_local_draft),
                confirmed = confirmed,
                onToggle = { confirmed = !confirmed },
            )
        }
        feedback?.let { FeedbackLine(it, error = true) }
    },
    bottom = {
        TextButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_cancel)) }
        Button(
            onClick = {
                when (option) {
                    0 -> onChoose("keep")
                    1 -> if (confirmed) onChoose("remote") else feedback = context.getString(R.string.error_confirm_required)
                    2 -> onChoose("copy")
                }
            },
            enabled = !(option == 1 && !confirmed),
            colors = ButtonDefaults.buttonColors(containerColor = if (option == 1) Primer.Red500 else Primer.Green500),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                when (option) {
                    0 -> stringResource(R.string.action_keep_local)
                    1 -> stringResource(R.string.action_discard_local)
                    else -> stringResource(R.string.action_copy_remote_as_new)
                },
                color = Color.White,
            )
        }
    })
}

// ═══════════════════════════════════════════════════════════════════
// 纯逻辑（单测见 app/src/test/java/com/branchbase/ui/decision/CollabDecisionRulesTest.kt）
// 抽出来的理由：这些都是「界面说了什么」与「实际做了什么」必须一致的判据 ——
// 写错不会崩，只会让用户看着一句不成立的话点下不可逆的按钮。
// ═══════════════════════════════════════════════════════════════════

/** 一条龙第②步的引导文案（空清单时同时用于第①步提前提示与第②步红字）。 */
internal const val NO_CHANGES_HINT: String =
    "没有待提交的改动：请到「代码」页打开文件 → 编辑 → 保存草稿，再回到这里开 PR。"

/** 单次「检查合并状态」最多查几个分支：每个分支一次 compare 请求，避免大仓库把配额打满。 */
internal const val MERGE_CHECK_LIMIT: Int = 20

/**
 * 一条龙第①步的分支名校验（界面文案必须与这里的判据一致）。
 *
 * [existing] 是宿主的仓库分支清单；宿主没给（空）时只有前两条成立，
 * 此时界面要写明「本次不查重」，不能承诺「已查重」。
 */
internal fun branchNameError(name: String, existing: List<String>): String? = when {
    name.isBlank() -> "分支名不能为空"
    name.contains(' ') -> "分支名不能含空格"
    existing.any { it == name } -> "分支 $name 已存在，请换一个名字"
    else -> null
}

/**
 * 一条龙第②步的准入条件：null = 可以提交，否则是给用户看的原因。
 *
 * **空清单刻意拦住**（不做「带警告继续」）：没有改动时 `commitFiles` 无事可做，
 * 唯一的「结果」是开出一个与 base 同 sha、diff 为空的 PR —— 那正是这次要消灭的假象。
 */
internal fun commitBlockReason(changedFiles: List<String>, commitMessage: String): String? = when {
    changedFiles.isEmpty() -> NO_CHANGES_HINT
    commitMessage.isBlank() -> "请填写提交信息"
    else -> null
}

/** PR 标题默认值：用户没亲手改过标题时才跟提交信息走（改过就不动他的）。 */
internal fun prTitleAfterCommit(title: String, titleEdited: Boolean, commitMessage: String): String =
    if (titleEdited) title else commitMessage

/** 待提交文件内容的解析结果：要么全齐，要么列出读不到的路径（不静默少提交几个文件）。 */
internal sealed interface ContentResolution {
    data class Ready(val files: List<Pair<String, String>>) : ContentResolution
    data class Missing(val paths: List<String>) : ContentResolution
}

/** `path to content` 列表里 content 为 null = 这个路径读不到。 */
internal fun contentResolutionOf(resolved: List<Pair<String, String?>>): ContentResolution {
    val missing = resolved.filter { it.second == null }.map { it.first }
    if (missing.isNotEmpty()) return ContentResolution.Missing(missing)
    return ContentResolution.Ready(resolved.map { (path, text) -> path to text!! })
}

/** 建分支撞名时的处置：只有当已存在的分支正好停在 base 上才能复用。 */
internal enum class BranchReuse { ABSENT, SAME_AS_BASE, DIVERGED }

internal fun branchReuseOf(existingSha: String?, baseSha: String): BranchReuse = when {
    existingSha == null -> BranchReuse.ABSENT
    existingSha == baseSha -> BranchReuse.SAME_AS_BASE
    else -> BranchReuse.DIVERGED
}

/** 分支的「合并状态」：查不到就是查不到，不拿「未合并」顶上。 */
internal sealed interface MergeState {
    /** 没检查过（本页默认态）。 */
    data object Unchecked : MergeState

    /** 相对默认分支 ahead_by = 0：没有独有提交，可安全删。 */
    data object Merged : MergeState

    /** 有 N 个独有提交（注意：squash 合并过的分支也会落在这里）。 */
    data class Ahead(val commits: Int) : MergeState

    /** 查过但没拿到结果（网络 / 引擎失败）。 */
    data object Unknown : MergeState
}

internal fun mergeStateOf(aheadBy: Int?): MergeState = when {
    aheadBy == null -> MergeState.Unknown
    aheadBy <= 0 -> MergeState.Merged
    else -> MergeState.Ahead(aheadBy)
}

internal fun mergeStateLabel(state: MergeState): String = when (state) {
    MergeState.Unchecked -> "未检查"
    MergeState.Merged -> "已合并"
    is MergeState.Ahead -> "${state.commits} 个独有提交"
    MergeState.Unknown -> "检查失败"
}

/** 界面配色：把「未知」和「有独有提交」都标红（都不适合当没事一样删掉）。 */
@Composable
private fun mergeStateColor(state: MergeState): Color = when (state) {
    MergeState.Merged -> Primer.Green500
    is MergeState.Ahead -> Primer.Red500
    MergeState.Unknown -> Primer.Red500
    MergeState.Unchecked -> Primer.TextTertiary
}

/** `GET /repos/{o}/{r}/compare/{base}...{head}` 的 `ahead_by`；缺键 = 未知（不默认成 0）。 */
internal fun parseAheadBy(json: String?): Int? {
    if (json.isNullOrBlank()) return null
    return runCatching {
        val o = org.json.JSONObject(json)
        if (o.has("ahead_by")) o.optInt("ahead_by") else null
    }.getOrNull()
}

/** 要检查哪些分支：默认分支自己不查，单次最多 [limit] 个（超出部分留给下一次点击）。 */
internal fun branchesToCheck(branches: List<String>, defaultBranch: String, limit: Int): List<String> =
    branches.filter { it != defaultBranch }.take(limit)

/**
 * 仓库统计（删除挽留展示）。
 *
 * 每个字段可空 = **这个维度没拿到** —— 拿不到就不显示它，绝不填 0 或估算值。
 */
data class RepoStats(
    val stars: Long? = null,
    val forks: Long? = null,
    val commits: Long? = null,
    val contributors: Long? = null,
)

/** 统计的单行文案；一个维度都没有时返回 null（调用方据此不显示这一行）。 */
internal fun repoStatsSummary(stats: RepoStats?): String? {
    if (stats == null) return null
    val parts = mutableListOf<String>()
    stats.stars?.let { parts += "⭐ $it 星标" }
    stats.forks?.let { parts += "🍴 $it 复刻" }
    stats.commits?.let { parts += "$it 提交" }
    stats.contributors?.let { parts += "$it 贡献者" }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/**
 * 从 `GET /repos/{o}/{r}` 的响应里取**直接拿得到**的两个维度（星标 / 复刻）。
 *
 * 提交数与贡献者要额外端点，这里不取 —— `RepoStats.commits/contributors` 留空即不显示。
 * 字段缺失返回 null 而不是 0：0 星标和「没拿到」是两件事。
 */
internal fun parseRepoStats(json: String?): RepoStats? {
    if (json.isNullOrBlank()) return null
    return runCatching {
        val o = org.json.JSONObject(json)
        RepoStats(
            stars = if (o.has("stargazers_count")) o.optLong("stargazers_count") else null,
            forks = if (o.has("forks_count")) o.optLong("forks_count") else null,
        )
    }.getOrNull()
}

/**
 * 解析待提交文件的内容（Git Data API 的 `files` 需要 `path to content` 成对）。
 *
 * 目录约定与文件页一致（`files/edit/single/{owner}/{repo}/{相对路径}`，见 RepositoryFileViewer.kt
 * 的 `draftRoot()`）：**草稿优先** —— 那是用户在文件页改过、但还没提交的内容；
 * 没有草稿的路径取它在 [ref]（base 分支）上的当前内容，于是「改已有文件」两条路都行，
 * 「新建文件」只能来自草稿。任一文件读不到 → 整体 [ContentResolution.Missing]，绝不静默少提交。
 */
private suspend fun resolveCommitFiles(
    context: Context,
    host: String,
    token: String,
    owner: String,
    repo: String,
    ref: String,
    paths: List<String>,
): ContentResolution = withContext(Dispatchers.IO) {
    val root = File(context.getExternalFilesDir(null), "edit/single/$owner/$repo")
    val resolved = paths.map { rel ->
        val draft = File(root, rel)
        val draftText = runCatching { draft.takeIf { it.isFile }?.readText() }.getOrNull()
        if (draftText != null) {
            rel to draftText
        } else {
            val json = runCatching {
                RustBridge.getJson(host, token, "/repos/$owner/$repo/contents/${encodePath(rel)}?ref=${encodeRef(ref)}")
            }.getOrNull()
            // 只有确实是一份 contents 响应才解内容：`parseFileContent` 解析失败也返回 ""，
            // 直接采信会把「读失败」当成「文件是空的」提交上去（空文件的内容同样合法，事后无法分辨）
            val isContents = json != null && !json.startsWith("ERROR:") &&
                runCatching { org.json.JSONObject(json).has("content") }.getOrDefault(false)
            rel to if (isContents) parseFileContent(json) else null
        }
    }
    contentResolutionOf(resolved)
}
