package com.branchbase.ui.repository

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.R
import com.branchbase.ui.theme.CodeSyntax
import com.branchbase.ui.theme.Primer

/**
 * 运行头部卡：**三段式**，让第一屏就能答四个问题
 * —— 成功没 / 跑了多久 / 哪个提交 / 哪个任务挂了。
 *
 * 改前是 7 条同字号同颜色的 `MetaLine`（编号·事件·分支·提交·触发人·耗时·创建），
 * 决定「这次跑的是什么」的事件与分支被埋在中间，而「哪个提交」只有一个 7 位 sha。
 *
 * 深色/浅色都走 `Primer` 角色，没有写死取值（`ThemeConvergenceTest` 会扫）。
 * 数据缺口只有一处：`headCommitMessage` 是这一轮才解析的（同一个响应里本来就有）。
 */
@Composable
fun RunHeaderCard(
    run: WorkflowRun,
    progress: RunProgress,
    elapsedMs: Long?,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, Primer.Border, RoundedCornerShape(12.dp))
                .padding(12.dp),
        ) {
            // ① 状态行：胶囊（运行中脉冲）+ 第 N 次尝试 + 右侧耗时
            Row(verticalAlignment = Alignment.CenterVertically) {
                RunStatePill(run.status, run.conclusion)
                if (run.runAttempt > 1) {
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.suffix_attempt, run.runAttempt), fontSize = 11.sp, color = Primer.TextTertiary)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "⏱ ${formatDuration(elapsedMs)}",
                    fontSize = 11.5.sp,
                    color = Primer.TextTertiary,
                )
            }

            // ② 标题
            Spacer(Modifier.height(8.dp))
            Text(
                run.displayTitle.ifBlank { run.name.ifBlank { stringResource(R.string.label_unnamed_run) } },
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Primer.TextPrimary,
                lineHeight = 20.sp,
            )

            // ③ 提交行：头像 + 分支 + 短 sha + commit message
            Spacer(Modifier.height(10.dp))
            Row {
                CommitAvatar(run.actor)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MetaChip(run.headBranch.ifBlank { "—" })
                        Spacer(Modifier.width(6.dp))
                        MetaChip(shaShort(run.headSha), mono = true)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.label_pushed_by, run.actor.ifBlank { "—" }),
                            fontSize = 11.sp,
                            color = Primer.TextTertiary,
                            maxLines = 1,
                        )
                    }
                    if (run.headCommitMessage.isNotBlank()) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            run.headCommitMessage,
                            fontSize = 13.sp,
                            color = Primer.TextPrimary,
                            lineHeight = 18.sp,
                            maxLines = 2,
                        )
                    }
                }
            }

            // ④ 进度行：只在「有失败」或「还没跑完」时出现
            if (progress.worthShowing && progress.total > 0) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        buildString {
                            append(stringResource(R.string.label_jobs_count_dot, progress.total))
                            val parts = buildList {
                                if (progress.ok > 0) add(stringResource(R.string.label_jobs_ok, progress.ok))
                                if (progress.failed > 0) add(stringResource(R.string.label_jobs_failed, progress.failed))
                                if (progress.running > 0) add(stringResource(R.string.label_jobs_running, progress.running))
                                if (progress.waiting > 0) add(stringResource(R.string.label_jobs_queued, progress.waiting))
                            }
                            append(parts.joinToString(" "))
                        },
                        fontSize = 11.5.sp,
                        color = Primer.TextSecondary,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(8.dp))
                    RunProgressBar(progress, Modifier.weight(1f))
                }
            }

            // ⑤ 次要行：事件 · 创建时间（降到更弱的色）
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.label_run_event_created, eventLabel(run.event), isoShort(run.createdAt)),
                fontSize = 11.5.sp,
                color = Primer.TextTertiary,
            )
        }
    }
}

/** 状态胶囊：点 + 文案；运行中的点会脉冲。 */
@Composable
fun RunStatePill(status: String, conclusion: String?) {
    val tint = runDotColor(status, conclusion)
    val bg = when {
        runDotColor(status, conclusion) == Primer.Green500 -> Primer.SuccessSurface
        runDotColor(status, conclusion) == Primer.Red500 -> Primer.DangerSurface
        status == "in_progress" -> Primer.WarningSurface
        else -> Primer.Gray150
    }
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).background(bg).padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(tint))
        Spacer(Modifier.width(6.dp))
        Text(
            runStatusLabel(status, conclusion),
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = when {
                tint == Primer.Green500 -> Primer.SuccessTextStrong
                tint == Primer.Red500 -> Primer.DangerText
                status == "in_progress" -> Primer.WarningTextStrong
                else -> Primer.TextSecondary
            },
        )
    }
}

/** 分段进度条：绿=成功、红=失败、橙=运行中、灰=排队。 */
@Composable
fun RunProgressBar(progress: RunProgress, modifier: Modifier = Modifier) {
    Row(
        modifier.height(6.dp).clip(RoundedCornerShape(999.dp)).background(Primer.Gray150),
    ) {
        ProgressSeg(progress.ok, progress.total, Primer.Green500)
        ProgressSeg(progress.failed, progress.total, Primer.Red500)
        ProgressSeg(progress.running, progress.total, Primer.Orange500)
        ProgressSeg(progress.waiting, progress.total, Primer.Gray500)
    }
}

@Composable
private fun ProgressSeg(count: Int, total: Int, color: androidx.compose.ui.graphics.Color) {
    if (count <= 0 || total <= 0) return
    Box(
        Modifier
            .fillMaxWidth(count.toFloat() / total.toFloat())
            .height(6.dp)
            .background(color),
    )
}

/** 提交头像：登录名首字母 + 稳定底色（不引网络）。 */
@Composable
private fun CommitAvatar(login: String) {
    val palette = listOf(Primer.Blue500, Primer.Purple500, Primer.SuccessTextStrong, Primer.Red500)
    val color = palette[(login.sumOf { it.code } % palette.size + palette.size) % palette.size]
    Box(
        Modifier.size(22.dp).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            login.take(1).uppercase().ifBlank { "?" },
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Primer.Gray000,
        )
    }
}

@Composable
private fun MetaChip(text: String, mono: Boolean = false) {
    Box(
        Modifier.clip(RoundedCornerShape(999.dp)).background(Primer.Gray150)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(
            text,
            fontSize = 11.sp,
            fontWeight = if (mono) FontWeight.Normal else FontWeight.SemiBold,
            fontFamily = if (mono) FontFamily.Monospace else null,
            color = Primer.TextSecondary,
            maxLines = 1,
        )
    }
}
