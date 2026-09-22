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
fun stateTone(status: String, conclusion: String?): StateTone = when (conclusion.normalizedConclusion()) {
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
 * 把「缺省 / 空 / 字面量 `"null"`」三种伪值统一成 `null`（纯函数，便于单测）。
 *
 * 为什么要在这一层再兜一次：`org.json` 的 `optString` 对 JSON `null` 返回的是**字符串 "null"**，
 * 一旦哪条解析路径漏了归一化（`RepositoryModels` 里已经统一走 `optNullableString`），
 * 判定层就会把「还没结论」当成「有结论」—— 真机现象是**正在跑的工作流被算成失败**。
 * 判定与配色属于「不能出错的那一侧」，所以这里独立再防一次，不依赖上游是否干净。
 */
fun String?.normalizedConclusion(): String? = this?.takeIf { it.isNotBlank() && it != "null" }

/**
 * **明确的失败结论**（白名单）。
 *
 * ⚠️ 不能用「非空且不是 success/skipped/cancelled」这种**黑名单**反推失败：
 * 未知结论、`"null"` 伪值、后端将来新增的取值都会被误判成失败。
 * 真机踩过的正是这条 —— 进行中（`conclusion: null`）的工作流被判失败。
 * 拿不准就不算失败（少报一个失败，好过把「正在跑」报成失败）。
 */
private val FAIL_CONCLUSIONS = setOf("failure", "timed_out", "startup_failure")

/**
 * 是不是「失败」结论 —— **非 Composable 的纯谓词**，给排序 / 过滤 / 计数用
 * （`stateTone` 要读主题色，放在 `remember` 里或排序 lambda 里都不行）。
 *
 * 口径与 [RunProgress] 一致：`cancelled` / `skipped` 不算失败（它们不该把列表染红），
 * **未知结论也不算**（见 [FAIL_CONCLUSIONS] 的注释）。
 */
fun isFailedConclusion(conclusion: String?): Boolean =
    conclusion.normalizedConclusion() in FAIL_CONCLUSIONS

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
