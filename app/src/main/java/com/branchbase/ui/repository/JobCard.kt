package com.branchbase.ui.repository

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.R
import com.branchbase.ui.theme.Primer
import com.branchbase.ui.theme.iconTap

/**
 * 任务卡片：一个 job 一张卡。
 *
 * ## 为什么是卡片，而不是「行 + 展开」
 *
 * 改前 `JobRow` 整行是 `clickable { onToggle() }`，里面又嵌了一个可点的「完整日志」
 * —— **可点区域套可点区域**，点偏一点就是另一个动作。现在卡片头**只做展开/收起**一件事；
 * 「看日志」统一收敛到日志页（点任一步骤进入，并落在该步骤）。
 *
 * ## 卡片里有什么
 *
 * - 头：状态点 + 任务名 + 状态胶囊 + 耗时 + **步骤进度 `6/6`** + 折叠箭头；
 * - 展开：步骤时间线（状态点 + `n. 名字` + 耗时 + **相对时长条**，让「哪一步最慢」不用读数字）；
 * - 脚：`runner: …`（这个字段模型里解析了、单测断言了，改前**整个 app 一次都没显示过**）
 *   + 「日志」/「下载日志」；
 * - 卡内注解：**该任务报的注解贴在卡片里**，而不是全部沉到页尾（归属规则见
 *   [jobBelongsToAnnotation]；归属不了的由调用方放进底部聚合区）。
 */
@Composable
fun JobCard(
    job: RunJob,
    expanded: Boolean,
    onToggle: () -> Unit,
    annotations: List<WorkflowAnnotation>,
    onOpenStep: (Long) -> Unit,
    onOpenLog: () -> Unit,
    onCopyLog: () -> Unit,
) {
    val tone = stateTone(job.status, job.conclusion)
    val failed = tone.dot == Primer.Red500
    val doneSteps = job.steps.count { it.status == "completed" }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, if (failed) Primer.DangerText else Primer.Border, RoundedCornerShape(12.dp)),
    ) {
        // ── 卡片头：整行只做展开/收起 ──
        Row(
            Modifier.fillMaxWidth().clickable { onToggle() }.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(tone.dot))
            Spacer(Modifier.width(9.dp))
            Text(
                job.name.ifBlank { stringResource(R.string.label_unnamed_job) },
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = Primer.TextPrimary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${runStatusLabel(job.status, job.conclusion)} · ${formatDuration(durationMillis(job.startedAt, job.completedAt))}",
                fontSize = 11.sp,
                color = Primer.TextTertiary,
                maxLines = 1,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "$doneSteps/${job.steps.size}",
                fontSize = 11.sp,
                color = Primer.TextTertiary,
            )
            Spacer(Modifier.width(6.dp))
            Text(if (expanded) "▾" else "▸", fontSize = 11.sp, color = Primer.TextTertiary)
        }

        if (expanded) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Primer.Gray150))

            if (job.steps.isEmpty()) {
                DetailEmptyText(stringResource(R.string.state_job_no_steps))
            } else {
                val maxMs = job.steps.mapNotNull { durationMillis(it.startedAt, it.completedAt) }
                    .maxOrNull()?.coerceAtLeast(1L) ?: 1L
                job.steps.forEach { step ->
                    StepTimelineRow(step = step, maxMs = maxMs, onClick = { onOpenStep(step.number) })
                }
            }

            if (annotations.isNotEmpty()) {
                JobAnnotations(annotations)
            }

            // ── 卡片脚 ──
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.label_runner, job.runnerName.ifBlank { stringResource(R.string.state_unassigned) }),
                    fontSize = 11.5.sp,
                    color = Primer.TextTertiary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.nav_logs),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Primer.Link,
                    modifier = Modifier.iconTap { onOpenLog() }.padding(horizontal = 6.dp, vertical = 4.dp),
                )
                // 逐 job 日志接口给的是一段**纯文本**（不是文件），所以这里是「复制」而不是「下载」——
                // 叫「下载日志」会骗人（点了没有文件落地）。
                Text(
                    stringResource(R.string.action_copy_logs),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Primer.Link,
                    modifier = Modifier.iconTap { onCopyLog() }.padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** 步骤时间线的一行：状态点 + `n. 名字` + 耗时 + 相对时长条 + 进入日志的箭头。 */
@Composable
private fun StepTimelineRow(step: JobStep, maxMs: Long, onClick: () -> Unit) {
    val tone = stateTone(step.status, step.conclusion)
    val ms = durationMillis(step.startedAt, step.completedAt)
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(start = 22.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(tone.dot))
        Spacer(Modifier.width(8.dp))
        Text(
            buildString {
                append("${step.number}. ")
                append(step.name.ifBlank { stringResource(R.string.label_unnamed_step) })
            },
            fontSize = 12.5.sp,
            color = Primer.TextPrimary,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            formatDuration(ms),
            fontSize = 11.sp,
            color = Primer.TextTertiary,
        )
        Spacer(Modifier.width(8.dp))
        // 相对时长条：同一步骤列表里最慢的那一步占满，其余按比例 —— 「哪一步最慢」不用读数字
        Box(Modifier.width(42.dp).height(5.dp).clip(RoundedCornerShape(999.dp)).background(Primer.Gray150)) {
            if (ms != null && ms > 0) {
                Box(
                    Modifier
                        .fillMaxWidth((ms.toFloat() / maxMs.toFloat()).coerceIn(0.08f, 1f))
                        .height(5.dp)
                        .background(tone.dot),
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Text("›", fontSize = 12.sp, color = Primer.TextTertiary)
    }
}

/** 卡片内的注解块：默认折起，标题行给条数。 */
@Composable
private fun JobAnnotations(annotations: List<WorkflowAnnotation>) {
    var open by remember(annotations.size) { mutableStateOf(false) }
    Column {
        Row(
            Modifier.fillMaxWidth().background(Primer.WarningSurface)
                .clickable { open = !open }.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.label_annotation_count, annotations.size),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = Primer.WarningTextStrong,
                modifier = Modifier.weight(1f),
            )
            Text(if (open) "▾" else "▸", fontSize = 11.sp, color = Primer.WarningTextStrong)
        }
        if (open) {
            annotations.forEach { AnnotationRow(it) }
        }
    }
}

/** 一条注解：等级色标 + 位置（等宽）+ 标题 + 正文。 */
@Composable
fun AnnotationRow(annotation: WorkflowAnnotation) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(annotationLevelColor(annotation.level)))
            Spacer(Modifier.width(8.dp))
            Text(
                annotationLocation(annotation),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = Primer.TextSecondary,
                maxLines = 2,
                modifier = Modifier.weight(1f),
            )
        }
        if (annotation.title.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(annotation.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
        }
        Spacer(Modifier.height(3.dp))
        Text(annotation.message.ifBlank { stringResource(R.string.state_no_content_paren) }, fontSize = 12.sp, color = Primer.TextPrimary, lineHeight = 17.sp)
    }
}
