package com.branchbase.ui.repository

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.R
import com.branchbase.core.RustBridge
import com.branchbase.ui.log.LogCategory
import com.branchbase.ui.log.Logger
import com.branchbase.ui.task.TaskKind
import com.branchbase.ui.task.TaskStore
import com.branchbase.ui.theme.Primer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 本地合并（阶段 5 的 UI 那半）在**宿主侧**的状态与动作。
 *
 * 两个宿主（代码页 / 文件页）各持一份：合并是「落在某个本地仓库上的动作」，
 * 而这两页都可能正开着同一个仓库 —— 状态放这里，界面只读它，别在页面里各记一套
 * （本仓库在消息页显示模式上吃过这个亏：两个入口各持一份 `remember`，只在页面活着时一致）。
 *
 * @param conflict 冲突弹窗的内容；非 null = 弹窗正开着
 * @param startedWith 本次合并**开始时**的冲突清单（「本次共 N 个」只能来自这里 ——
 *   已解决的冲突引擎不落盘、也不重算，见 `MergeState` 的说明）
 *
 * **页面路由不在这里**（决策页 / 详情页由各宿主自己的 `RepoRoute` / `FilePage` 表达）：
 * 路由状态有两份的话，「返回时先关哪个」就会出现两种答案。
 */
@Stable
internal class MergeFlowState {
    var running by mutableStateOf(false)
    var conflict by mutableStateOf<MergeConflictNotice?>(null)
    var startedWith: List<String> = emptyList()

    /** 合并收尾（提交合并 / 放弃合并）后清干净，免得下一次合并先闪出上一次的清单。 */
    fun settle() {
        conflict = null
        startedWith = emptyList()
    }
}

/** 冲突弹窗要画的东西。 */
internal data class MergeConflictNotice(
    val branch: String,
    val files: List<String>,
    val message: String,
)

/** 合并流程的日志锚点（登记在 `ui/log/Logging.kt` 的 `LOG_ANCHORS`）。 */
internal const val MERGE_LOG_TAG = "合并"

/** 两个宿主各持一份（`remember` 在页面级，跨重组保留）。 */
@Composable
internal fun rememberMergeFlowState(): MergeFlowState = remember { MergeFlowState() }

/**
 * 跑一次合并，并按结果分派（这一步是**唯一**执行合并的地方）。
 *
 * 四条出口各有各的落点：
 * - `up_to_date` / `fast_forward` / `merged` → 一行反馈 + 刷新（工作区/徽标/引用树都要重读）；
 * - `conflict` → **立刻弹窗**，并在**这一刻**启动预解析（D-h 的契约：触发点是弹窗出现，
 *   不是用户点「查看并解决」时）；
 * - 失败 → 把引擎那句话原样给用户（工作区脏 / 浅克隆 / 已在合并中 / 找不到分支，
 *   每一种的出路都不一样，压成「合并失败」等于什么都没说）。
 *
 * 长任务口径（§10）：记一条 `TaskKind.MERGE` 到任务中心。合并本身是本地秒级动作，
 * 但「目标分支只在远端」那一档会带一次 fetch —— 任务记录是事后说清「那次慢是为什么」的唯一线索。
 */
internal fun CoroutineScope.runMerge(
    context: Context,
    flow: MergeFlowState,
    repoDir: String,
    repoName: String,
    target: String,
    token: String,
    onFeedback: (String, Boolean) -> Unit,
    onChanged: () -> Unit,
) {
    if (flow.running) return
    launch {
        flow.running = true
        val taskId = TaskStore.start(
            context,
            TaskKind.MERGE,
            context.getString(R.string.label_merge_task_title, target),
        )
        Logger.local("$MERGE_LOG_TAG ▸ $repoName：请求合并 $target", MERGE_LOG_TAG)
        val raw = RustBridge.gitMerge(
            dir = repoDir,
            branch = target,
            token = token,
            authorName = gitAuthorName(context),
            authorEmail = gitAuthorEmail(context),
        )
        when (val outcome = parseMergeOutcome(raw)) {
            is MergeOutcome.UpToDate -> {
                TaskStore.success(context, taskId, context.getString(R.string.state_merge_up_to_date))
                Logger.local("$MERGE_LOG_TAG ▸ $repoName：已包含 $target（无需动作）", MERGE_LOG_TAG)
                onFeedback(context.getString(R.string.toast_merge_up_to_date, target), true)
                onChanged()
            }
            is MergeOutcome.FastForward -> {
                TaskStore.success(context, taskId, context.getString(R.string.state_merge_fast_forward))
                Logger.local("$MERGE_LOG_TAG ▸ $repoName：快进到 $target（${outcome.sha.take(7)}）", MERGE_LOG_TAG)
                onFeedback(context.getString(R.string.toast_merge_fast_forward, target), true)
                onChanged()
            }
            is MergeOutcome.Merged -> {
                TaskStore.success(context, taskId, context.getString(R.string.state_merged))
                Logger.local("$MERGE_LOG_TAG ▸ $repoName：合并提交 ${outcome.sha.take(7)}（$target）", MERGE_LOG_TAG)
                onFeedback(context.getString(R.string.toast_merged, outcome.sha.take(7)), true)
                onChanged()
            }
            is MergeOutcome.Conflict -> {
                TaskStore.fail(
                    context,
                    taskId,
                    context.getString(R.string.state_merge_conflict_task, outcome.conflicts.size),
                )
                Logger.warn(
                    LogCategory.LOCAL_TASK,
                    MERGE_LOG_TAG,
                    "$MERGE_LOG_TAG ▸ $repoName：$target 有 ${outcome.conflicts.size} 个冲突文件（仓库停在合并中）",
                )
                flow.startedWith = outcome.conflicts
                flow.conflict = MergeConflictNotice(
                    branch = outcome.branch.ifBlank { target },
                    files = outcome.conflicts,
                    message = outcome.message,
                )
                // 弹窗出现的那一刻就预解析（只读、进程内缓存）—— 用户点「查看并解决」时
                // 结果通常已经在手上，而不是先看一段骨架
                MergePreparse.start(repoDir, this@runMerge) { RustBridge.gitAnalyzeConflicts(repoDir) }
            }
            is MergeOutcome.Failed -> {
                // 引擎原话优先；`null` = 结果看不懂（那时用资源文案，别在这里写死中文）
                val reason = outcome.reason ?: context.getString(R.string.error_merge_bad_result)
                TaskStore.fail(context, taskId, reason)
                Logger.warn(LogCategory.LOCAL_TASK, MERGE_LOG_TAG, "$MERGE_LOG_TAG ▸ $repoName：$target 未执行 —— $reason")
                onFeedback(reason, false)
            }
        }
        flow.running = false
    }
}

/**
 * 放弃合并（回到合并前）。两个入口共用：冲突弹窗 / 详情页 / 面板的状态条。
 *
 * 成功之后**必须清预解析缓存**：不清的话下一次合并会先渲染上一次的冲突清单
 * （而那时仓库里的冲突早就是另一批文件了）。
 */
internal fun CoroutineScope.runMergeAbort(
    context: Context,
    flow: MergeFlowState,
    repoDir: String,
    repoName: String,
    onFeedback: (String, Boolean) -> Unit,
    onChanged: () -> Unit,
) {
    launch {
        val reason = RustBridge.gitMergeAbort(repoDir)
        if (reason == null) {
            Logger.local("$MERGE_LOG_TAG ▸ $repoName：已放弃合并，工作区回到合并前", MERGE_LOG_TAG)
            MergePreparse.clear(repoDir)
            flow.settle()
            onFeedback(context.getString(R.string.toast_merge_aborted), true)
        } else {
            Logger.warn(LogCategory.LOCAL_TASK, MERGE_LOG_TAG, "$MERGE_LOG_TAG ▸ $repoName：放弃合并失败 —— $reason")
            onFeedback(reason, false)
        }
        onChanged()
    }
}

/**
 * 冲突弹窗（D-h：合并一返回冲突就出现）。
 *
 * 三件事必须在弹窗里说清，缺一件用户就只能猜：**哪两个分支**（合的是谁）、
 * **几个文件**、以及**现在能做什么**（查看并解决 / 稍后 —— 仓库停在合并中，
 * 「稍后」不是丢失，面板上一直有「继续 / 放弃」）。
 */
@Composable
internal fun MergeConflictDialog(
    notice: MergeConflictNotice,
    onOpenDetail: () -> Unit,
    onLater: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onLater,
        title = {
            Text(
                stringResource(R.string.conflict_dialog_title, notice.files.size),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Primer.TextPrimary,
            )
        },
        text = {
            Column {
                Text(
                    stringResource(R.string.conflict_dialog_body, notice.branch),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = Primer.TextSecondary,
                )
                if (notice.files.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    // 只列前几个：弹窗不是清单页（完整清单在详情页里逐条处理）
                    Text(
                        notice.files.take(5).joinToString("\n"),
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        color = Primer.TextTertiary,
                    )
                    if (notice.files.size > 5) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.conflict_dialog_more, notice.files.size - 5),
                            fontSize = 11.sp,
                            color = Primer.TextTertiary,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenDetail) {
                Text(stringResource(R.string.action_resolve_conflicts), color = Primer.Blue500)
            }
        },
        dismissButton = {
            TextButton(onClick = onLater) { Text(stringResource(R.string.action_later)) }
        },
    )
}
