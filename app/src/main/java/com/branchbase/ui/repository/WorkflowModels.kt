package com.branchbase.ui.repository

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * 工作流（Actions）运行详情 / 手动触发相关的模型与纯逻辑。
 *
 * 数据来源（全部实测过字段）：
 * - run 详情：`GET /repos/{o}/{r}/actions/runs/{id}`
 * - jobs + steps：`GET /repos/{o}/{r}/actions/runs/{id}/jobs`（每个 job 自带 `steps[]` 与耗时）
 * - 产物：`GET /repos/{o}/{r}/actions/runs/{id}/artifacts`
 * - 注解：check-runs 的 `output.annotations_count` + `GET /check-runs/{id}/annotations`
 * - 手动触发输入：读工作流 YAML 的 `on.workflow_dispatch.inputs`（REST 的 workflow 对象不含）
 */

// ── 运行详情（单对象） ──

/** 解析单个 run 对象（run 详情接口直接返回对象，不是 `{workflow_runs:[…]}`）。 */
fun parseWorkflowRun(json: String?): WorkflowRun? {
    if (json.isNullOrBlank() || json.startsWith("ERROR:")) return null
    return runCatching { JSONObject(json) }.getOrNull()?.let { o ->
        parseWorkflowRuns("""{"workflow_runs":[${o}]}""").firstOrNull()
    }
}

// ── 产物 ──

data class WorkflowArtifact(
    val id: Long,
    val name: String,
    val sizeInBytes: Long,
    val expired: Boolean,
    val createdAt: String,
) {
    val sizeText: String
        get() = when {
            sizeInBytes >= 1024 * 1024 -> "%.1f MB".format(sizeInBytes / 1024.0 / 1024.0)
            sizeInBytes >= 1024 -> "%.0f KB".format(sizeInBytes / 1024.0)
            else -> "$sizeInBytes B"
        }
}

fun parseWorkflowArtifacts(json: String?): List<WorkflowArtifact> {
    if (json.isNullOrBlank() || json.startsWith("ERROR:")) return emptyList()
    return runCatching {
        val arr = JSONObject(json).optJSONArray("artifacts") ?: return@runCatching emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            WorkflowArtifact(
                id = o.optLong("id"),
                name = o.optString("name"),
                sizeInBytes = o.optLong("size_in_bytes"),
                expired = o.optBoolean("expired", false),
                createdAt = o.optString("created_at"),
            )
        }
    }.getOrDefault(emptyList())
}

// ── 注解（check-run annotations） ──

data class WorkflowAnnotation(
    val path: String,
    val startLine: Int,
    val endLine: Int,
    val level: String,      // notice / warning / failure
    val message: String,
    val title: String,
)

fun parseWorkflowAnnotations(json: String?): List<WorkflowAnnotation> {
    if (json.isNullOrBlank() || json.startsWith("ERROR:")) return emptyList()
    return runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            WorkflowAnnotation(
                path = o.optString("path"),
                startLine = o.optInt("start_line", 0),
                endLine = o.optInt("end_line", 0),
                level = o.optString("annotation_level"),
                message = o.optString("message"),
                title = o.optString("title"),
            )
        }
    }.getOrDefault(emptyList())
}

/** 从 check-runs 列表里挑出「有注解」的 run id（避免为每个 check-run 都发一次请求）。 */
fun annotationCheckRunIds(json: String?): List<Long> {
    if (json.isNullOrBlank() || json.startsWith("ERROR:")) return emptyList()
    return runCatching {
        val arr = JSONObject(json).optJSONArray("check_runs") ?: return@runCatching emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val count = o.optJSONObject("output")?.optInt("annotations_count", 0) ?: 0
            o.optLong("id").takeIf { count > 0 }
        }
    }.getOrDefault(emptyList())
}

// ── 手动触发：输入定义 ──

data class WorkflowInputSpec(
    val name: String,
    val description: String,
    val required: Boolean,
    val default: String,
    val type: String,          // string / boolean / choice / number / environment
    val options: List<String>,
) {
    val isBoolean: Boolean get() = type == "boolean"
    val isChoice: Boolean get() = type == "choice" && options.isNotEmpty()
    val isNumber: Boolean get() = type == "number"
}

data class WorkflowDispatchSpec(
    val enabled: Boolean,
    val inputs: List<WorkflowInputSpec>,
)

/** 解析 Rust `parseWorkflowInputs` 返回的 JSON。 */
fun parseDispatchSpec(json: String?): WorkflowDispatchSpec {
    if (json.isNullOrBlank() || json.startsWith("ERROR:")) {
        return WorkflowDispatchSpec(enabled = false, inputs = emptyList())
    }
    return runCatching {
        val o = JSONObject(json)
        val arr = o.optJSONArray("inputs")
        val inputs = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
            val it = arr?.optJSONObject(i) ?: return@mapNotNull null
            val name = it.optString("name")
            if (name.isBlank()) return@mapNotNull null
            WorkflowInputSpec(
                name = name,
                description = it.optString("description"),
                required = it.optBoolean("required", false),
                default = it.optString("default"),
                type = it.optString("type").ifBlank { "string" },
                options = it.optJSONArray("options")?.let { opts ->
                    (0 until opts.length()).mapNotNull { j -> opts.optString(j).takeIf { s -> s.isNotBlank() } }
                } ?: emptyList(),
            )
        }
        WorkflowDispatchSpec(enabled = o.optBoolean("enabled", false), inputs = inputs)
    }.getOrDefault(WorkflowDispatchSpec(enabled = false, inputs = emptyList()))
}

/** 必填但为空的输入项名称（用于提交前校验）。 */
fun missingRequiredInputs(spec: WorkflowDispatchSpec, values: Map<String, String>): List<String> =
    spec.inputs.filter { it.required && values[it.name].isNullOrBlank() }.map { it.name }

/**
 * 按类型生成 dispatch 的 `inputs` JSON 对象。
 *
 * - boolean → 真布尔（GitHub 要求 `true`/`false`，不能是字符串）；
 * - number → 能解析成数字时用数字，否则退回字符串（避免整个请求 422）；
 * - 其余 → 字符串；空值且非必填时不下发该键。
 */
fun buildInputsJson(spec: WorkflowDispatchSpec, values: Map<String, String>): String {
    val obj = JSONObject()
    spec.inputs.forEach { input ->
        val raw = values[input.name] ?: input.default
        if (raw.isBlank() && !input.required) return@forEach
        when {
            input.isBoolean -> obj.put(input.name, raw.trim().equals("true", ignoreCase = true))
            input.isNumber -> {
                val n = raw.trim().toDoubleOrNull()
                if (n != null) obj.put(input.name, n) else obj.put(input.name, raw)
            }
            else -> obj.put(input.name, raw)
        }
    }
    return obj.toString()
}

// ── 时长 / 状态 ──

/** 解析 GitHub 的 ISO-8601 时间（`2026-09-07T13:42:34Z`，可带小数秒），失败返回 null。 */
fun parseInstantOrNull(iso: String?): Instant? = runCatching {
    iso?.takeIf { it.isNotBlank() }?.let { Instant.parse(it) }
}.getOrNull()

/** 两个时间点之间的毫秒差；任一不可解析或为负则返回 null。 */
fun durationMillis(startedAt: String?, completedAt: String?): Long? {
    val a = parseInstantOrNull(startedAt) ?: return null
    val b = parseInstantOrNull(completedAt) ?: return null
    val ms = b.toEpochMilli() - a.toEpochMilli()
    return ms.takeIf { it >= 0 }
}

/** 人类可读时长：`1h 02m`、`3m 07s`、`12s`、`0.4s`。 */
fun formatDuration(ms: Long?): String {
    if (ms == null) return "—"
    if (ms < 1000) return "%.1fs".format(ms / 1000.0)
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> "%dh %02dm".format(h, m)
        m > 0 -> "%dm %02ds".format(m, s)
        else -> "%ds".format(s)
    }
}

/** 运行/步骤状态的中文标签（GitHub 的 status + conclusion 组合）。 */
fun runStatusLabel(status: String, conclusion: String?): String = when (conclusion) {
    "success" -> "成功"
    "failure" -> "失败"
    "cancelled" -> "已取消"
    "skipped" -> "已跳过"
    "timed_out" -> "超时"
    "action_required" -> "待处理"
    "neutral" -> "中性"
    "stale" -> "已过期"
    "startup_failure" -> "启动失败"
    else -> when (status) {
        "queued" -> "排队中"
        "in_progress" -> "进行中"
        "requested" -> "已请求"
        "waiting" -> "等待中"
        "pending" -> "等待中"
        "completed" -> "已完成"
        else -> status.ifBlank { "未知" }
    }
}

/** 触发事件的中文标签（GitHub 的 event 字段）。 */
fun eventLabel(event: String): String = when (event) {
    "push" -> "推送"
    "pull_request" -> "拉取请求"
    "workflow_dispatch" -> "手动触发"
    "schedule" -> "定时"
    "release" -> "发布"
    "issues" -> "Issue"
    "issue_comment" -> "Issue 评论"
    "pull_request_review" -> "PR 审查"
    "merge_group" -> "合并队列"
    "dynamic" -> "动态"
    "repository_dispatch" -> "仓库事件"
    else -> event.ifBlank { "—" }
}

// ── 日志分段 ──

/** 一段日志（按 `##[group]` 切分，标题通常等于步骤名）。 */
data class LogSegment(val title: String, val lines: List<String>)

private val LOG_TIMESTAMP = Regex("^\\d{4}-\\d{2}-\\d{2}T[0-9:.]+Z ?")

/** 去掉 GitHub 日志行首的 ISO 时间戳（保留正文，便于阅读）。 */
fun stripLogTimestamp(line: String): String = LOG_TIMESTAMP.replaceFirst(line, "")

/**
 * 把 job 日志按 `##[group]…##[endgroup]` 切成段。
 *
 * GitHub 的日志里每个 step 通常对应一个 group（`run:` 步骤的 group 名是步骤名或命令行，
 * `uses:` 步骤是 `Run owner/repo@ref`），因此可以据此把日志归到具体步骤下。
 * 没有 group 标记时返回单段「全部日志」，绝不丢内容。
 */
fun splitJobLogBySteps(log: String, steps: List<JobStep> = emptyList()): List<LogSegment> {
    if (log.isBlank()) return emptyList()
    val segments = mutableListOf<LogSegment>()
    var currentTitle: String? = null
    var current = mutableListOf<String>()

    fun flush() {
        if (current.isNotEmpty()) {
            segments += LogSegment(currentTitle ?: "全部日志", current.toList())
        }
        current = mutableListOf()
    }

    log.split('\n').forEach { raw ->
        val line = stripLogTimestamp(raw.trimEnd('\r'))
        val groupAt = line.indexOf("##[group]")
        when {
            groupAt >= 0 -> {
                flush()
                currentTitle = line.substring(groupAt + "##[group]".length).trim()
            }

            line.contains("##[endgroup]") -> {
                flush()
                currentTitle = null
            }

            else -> current += line
        }
    }
    flush()

    // 完全没有 group：内容整体落在「全部日志」一段里，直接返回
    if (segments.size == 1 && segments[0].title == "全部日志") return segments
    return segments
}

/** 段落标题与步骤名是否指同一步骤（GitHub 的 group 名不总是完全等于步骤名）。 */
fun segmentMatchesStep(segmentTitle: String, stepName: String): Boolean {
    val a = segmentTitle.trim().lowercase()
    val b = stepName.trim().lowercase()
    if (a.isEmpty() || b.isEmpty()) return false
    if (a == b) return true
    if (b.length >= 4 && a.contains(b)) return true
    if (a.length >= 4 && b.contains(a)) return true
    return false
}

/** 为某一步骤找出对应日志段（找不到返回 null）。 */
fun logSegmentForStep(segments: List<LogSegment>, step: JobStep): LogSegment? =
    segments.firstOrNull { segmentMatchesStep(it.title, step.name) }
