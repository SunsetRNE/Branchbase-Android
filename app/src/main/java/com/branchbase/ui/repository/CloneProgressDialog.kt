package com.branchbase.ui.repository

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.branchbase.R
import com.branchbase.core.CloneProgress
import com.branchbase.downloader.DownloadPaths
import com.branchbase.ui.theme.Primer

/**
 * 长任务（clone / 加深历史）的模态进度弹窗。
 *
 * ## 为什么必须是弹窗，而不是列表上方那行小字
 *
 * 旧实现在列表上方挂一行「正在克隆…」，加一条一闪而过的 Snackbar。它有两个后果：
 *
 * 1. **看不出在不在动**：浅 clone 一个中等仓库在手机上要几十秒，一个正常但慢的拉取
 *    与一次已经卡死的拉取，在界面上长得一模一样 —— 用户唯一的动作是反复点、或者退出重进。
 * 2. **失败原因活不过三秒**：Snackbar 三秒后消失，而那条原因（真机上是一条
 *    `clone 失败: failed to lock file '…'`）恰恰是用户唯一能拿去做判断的东西，
 *    日志页又是另一个入口。
 *
 * 所以这里改成：开始即弹出模态窗，进度条跟着引擎的真实计数走（不插值、不假动画），
 * 失败时**停在原地**把原因摊开，并给「重试」——用户不用自己去日志页翻。
 *
 * ## 交互口径
 *
 * - **运行中不可点外部 / 不可返回键关闭**：这是一次写盘操作，关掉弹窗并不等于停止它，
 *   让人以为「关了就没在跑」比不让关更糟。要中断只有 [onCancel]（真的会停）。
 * - **取消是「尽快」而不是「立刻」**：libgit2 只在进度回调里接受中断，所以点下之后
 *   先显示「正在取消…」，等引擎在下一次回调里真的停下（见 `core/src/git/progress.rs`）。
 * - **失败态可以关闭 / 重试**：重试会把这次留下的半成品目录清掉重新来（引擎侧负责）。
 *
 * [title] 由调用方给：加深历史复用同一只弹窗（同一套进度与终态），标题必须跟着动作走 ——
 * 点的是「加深历史」却弹出「拉取仓库」会让人以为点错了。
 */
sealed interface CloneDialogState {
    /** 正在拉取（[progress] 为 null = 引擎还没给出第一份快照，或这一次没读到）。 */
    data class Running(val progress: CloneProgress?, val cancelling: Boolean = false) : CloneDialogState

    /** 失败：停在原地，把原因摊开。 */
    data class Failed(val reason: String) : CloneDialogState
}

@Composable
fun CloneProgressDialog(
    repoFullName: String,
    state: CloneDialogState,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    title: String = stringResource(R.string.action_clone_repository),
) {
    val running = state is CloneDialogState.Running
    Dialog(
        onDismissRequest = { if (!running) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !running,
            dismissOnClickOutside = !running,
        ),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Primer.BackgroundPrimary, RoundedCornerShape(12.dp))
                .padding(16.dp),
        ) {
            Text(
                // 标题与触发它的动作同一个词：用户点的是「拉取仓库」/「加深历史」，弹窗就得说同一句
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Primer.TextPrimary,
            )
            Text(
                repoFullName,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = Primer.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )

            Spacer(Modifier.height(14.dp))

            when (state) {
                is CloneDialogState.Running -> {
                    val percent = state.progress?.percent
                    if (percent == null) {
                        // 进度不可知（握手阶段 / 远端没报总数）：不确定态，而不是假装 0%
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            color = Primer.Blue500,
                            trackColor = Primer.Gray150,
                        )
                    } else {
                        LinearProgressIndicator(
                            progress = { percent / 100f },
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            color = Primer.Blue500,
                            trackColor = Primer.Gray150,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            progressLine(state.progress, state.cancelling),
                            fontSize = 12.5.sp,
                            color = Primer.TextSecondary,
                            modifier = Modifier.weight(1f),
                        )
                        if (percent != null) {
                            Text(
                                stringResource(R.string.clone_percent, percent),
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = Primer.TextPrimary,
                            )
                        }
                    }
                    // 字节数只在真的有数据时出现：0 B 那一行没有信息量
                    val bytes = state.progress?.bytes ?: 0L
                    if (bytes > 0L) {
                        Text(
                            stringResource(R.string.clone_bytes_received, DownloadPaths.formatBytes(bytes)),
                            fontSize = 11.sp,
                            color = Primer.TextTertiary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onCancel, enabled = !state.cancelling) {
                            Text(
                                stringResource(
                                    if (state.cancelling) R.string.clone_phase_cancelling else R.string.action_cancel,
                                ),
                                color = if (state.cancelling) Primer.TextTertiary else Primer.Red500,
                            )
                        }
                    }
                }

                is CloneDialogState.Failed -> {
                    Text(
                        stringResource(R.string.clone_failed_title),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Primer.DangerText,
                    )
                    Text(
                        // 原因原样透出（含引擎给的路径）：这是用户唯一能拿去搜/能反馈的东西。
                        // 最长 300 字符（`engineErrorOrNull` 的口径），给个上限并允许滚动，
                        // 免得一条长错误把弹窗顶出屏幕、按钮点不到。
                        state.reason,
                        fontSize = 12.sp,
                        color = Primer.TextSecondary,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .heightIn(max = 180.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 10.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.action_close), color = Primer.TextSecondary)
                        }
                        TextButton(onClick = onRetry) {
                            Text(stringResource(R.string.action_retry), color = Primer.Blue500)
                        }
                    }
                }
            }
        }
    }
}

/** 运行中的那一行说明（阶段 + 计数）；引擎还没给快照时给一句兜底。 */
@Composable
private fun progressLine(progress: CloneProgress?, cancelling: Boolean): String =
    clonePhaseText(LocalContext.current, progress, cancelling)

/**
 * 阶段文案的**唯一真源**：弹窗那一行与任务记录里的 `detail` 用的是同一句
 * （两处各写一份，迟早会出现「弹窗说解析增量、任务记录说正在克隆」这种对不上的情况）。
 *
 * 非组合上下文（协程里写任务记录）也能调，所以收 Context 而不是走 `stringResource`。
 */
fun clonePhaseText(context: Context, progress: CloneProgress?, cancelling: Boolean = false): String {
    if (cancelling) return context.getString(R.string.clone_phase_cancelling)
    if (progress == null) return context.getString(R.string.clone_phase_preparing)
    return when (progress.phase) {
        CloneProgress.Phase.CONNECT -> context.getString(R.string.clone_phase_connecting)
        CloneProgress.Phase.RECEIVE -> context.getString(
            R.string.clone_phase_receiving,
            cloneCountText(progress.received, progress.total),
        )
        CloneProgress.Phase.RESOLVE -> context.getString(
            R.string.clone_phase_resolving,
            cloneCountText(progress.indexed, progress.total),
        )
        CloneProgress.Phase.CHECKOUT -> context.getString(
            R.string.clone_phase_checkout,
            cloneCountText(progress.checkoutDone, progress.checkoutTotal),
        )
        CloneProgress.Phase.FINALIZE -> context.getString(R.string.clone_phase_finalizing)
        CloneProgress.Phase.DONE -> context.getString(R.string.state_completed)
        // 认不出的阶段 / 不是进度该出现的时候：给一句通用的，不编数字
        CloneProgress.Phase.IDLE, CloneProgress.Phase.FAILED, CloneProgress.Phase.UNKNOWN ->
            context.getString(R.string.clone_phase_preparing)
    }
}

/**
 * 「已完成 / 总数」文本；**分母未知时只给分子** —— 不编一个假分母（`142/0` 这种）。
 *
 * 纯函数，可 JVM 单测（`CloneProgressDialogTest`）。
 */
internal fun cloneCountText(done: Int, total: Int): String =
    if (total > 0) "$done/$total" else done.toString()
