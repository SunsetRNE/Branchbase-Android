package com.branchbase.ui.repository

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.branchbase.ui.theme.Primer

/**
 * 工作流页面共用的**展示层小工具**（纯映射 / 纯格式化，无状态）。
 *
 * 这些原本散在 `WorkflowRunDetailScreen` 里当私有函数，重绘拆成多个文件后需要共用，
 * 于是集中到这里 —— 顺带让「状态点用什么颜色」只有一个出处。
 */

/** 状态点 / 状态胶囊的配色三件套。 */
data class StateTone(val dot: Color, val bg: Color, val text: Color)

/**
 * 状态语义 → 配色。
 *
 * 注意用的是**文字角色**（`SuccessTextStrong` / `DangerText` / `WarningTextStrong`）而不是填充色：
 * 拿 `Red500` 当文字压在 `DangerSurface` 上只有 4.0，低于 WCAG AA（见 `ThemeContrastTest`）。
 */
@Composable
fun stateTone(status: String, conclusion: String?): StateTone = when (conclusion) {
    "success" -> StateTone(Primer.Green500, Primer.SuccessSurface, Primer.SuccessTextStrong)
    "failure", "timed_out", "startup_failure" ->
        StateTone(Primer.Red500, Primer.DangerSurface, Primer.DangerText)
    "cancelled", "skipped", "stale" ->
        StateTone(Primer.TextTertiary, Primer.Gray150, Primer.TextSecondary)
    "action_required", "neutral" ->
        StateTone(Primer.Orange500, Primer.WarningSurface, Primer.WarningTextStrong)
    else -> when (status) {
        "queued", "in_progress", "requested", "waiting", "pending" ->
            StateTone(Primer.Orange500, Primer.WarningSurface, Primer.WarningTextStrong)
        else -> StateTone(Primer.TextTertiary, Primer.Gray150, Primer.TextSecondary)
    }
}

/** 状态点颜色（进度点 / 步骤点用；只要一个色时比 [stateTone] 省事）。 */
@Composable
fun runDotColor(status: String, conclusion: String?): Color = stateTone(status, conclusion).dot

/**
 * 是不是「失败」结论 —— **非 Composable 的纯谓词**，给排序 / 过滤 / 计数用
 * （`stateTone` 要读主题色，放在 `remember` 里或排序 lambda 里都不行）。
 *
 * 口径与 [RunProgress] 一致：`cancelled` / `skipped` 不算失败（它们不该把列表染红）。
 */
fun isFailedConclusion(conclusion: String?): Boolean =
    conclusion != null && conclusion !in setOf("success", "skipped", "cancelled")

/** 注解等级色标：failure 红 / warning 橙 / 其它蓝。 */
@Composable
fun annotationLevelColor(level: String): Color = when (level.lowercase()) {
    "failure" -> Primer.Red500
    "warning" -> Primer.Orange500
    else -> Primer.Blue500
}

/** 注解位置 `path:startLine`（path 为空时退化为 `—`）。 */
fun annotationLocation(annotation: WorkflowAnnotation): String = when {
    annotation.path.isBlank() -> "—"
    annotation.startLine > 0 -> "${annotation.path}:${annotation.startLine}"
    else -> annotation.path
}

/** 提交短 sha（前 7 位）。 */
fun shaShort(sha: String): String = if (sha.isBlank()) "—" else sha.take(7)

/** 时间展示：截取前 16 字符并把 `T` 换成空格（`2026-09-07T13:42:34Z` → `2026-09-07 13:42`）。 */
fun isoShort(iso: String): String = if (iso.isBlank()) "—" else iso.take(16).replace('T', ' ')

/**
 * **运行中**的已跑时长：`startIso` 到 `nowMs`。
 *
 * 与 [durationMillis]（起止都在响应里）不同 —— 运行中的 run 还没有 `updatedAt` 意义上的终点，
 * 用 `updatedAt - runStartedAt` 会得到一个**冻住**的数字。每秒走动靠调用方刷新 `nowMs`。
 */
fun elapsedSince(startIso: String, nowMs: Long): Long? =
    // 负数（设备与服务器有时钟漂移）按 0 处理：显示「刚开始」比显示「—」有用
    parseInstantOrNull(startIso)?.let { (nowMs - it.toEpochMilli()).coerceAtLeast(0L) }

// ── 日志行分级与搜索（纯逻辑，可 JVM 单测） ──

/** 日志行的严重级别（只用于**上色**，不改变内容）。 */
enum class LogLineLevel { ERROR, WARNING, NORMAL }

/**
 * 判断一行日志的级别。
 *
 * GitHub 的日志里 `##[error]` / `##[warning]` 是显式标记；其余靠内容判断。
 * 宁可漏标也不要误标成一片红 —— 所以只认比较确定的写法。
 */
fun logLineLevel(line: String): LogLineLevel {
    val l = line.lowercase()
    return when {
        "##[error]" in l || "error:" in l || "failed" in l || "assertionerror" in l -> LogLineLevel.ERROR
        "##[warning]" in l || "warning:" in l || " warn" in l -> LogLineLevel.WARNING
        else -> LogLineLevel.NORMAL
    }
}

/** 日志里所有命中 [query] 的行号（0-based，按顺序）—— 搜索的「第 n / 共 m 条」用它。 */
fun logHitIndexes(lines: List<String>, query: String): List<Int> {
    if (query.isBlank()) return emptyList()
    return lines.indices.filter { lines[it].contains(query, ignoreCase = true) }
}
