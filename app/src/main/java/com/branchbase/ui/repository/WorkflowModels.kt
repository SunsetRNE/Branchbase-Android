package com.branchbase.ui.repository

import com.branchbase.joblogs.LogSegment
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.branchbase.R

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
    /**
     * `archive_download_url` —— 产物的 zip 地址（**需要鉴权**，由 :downloader 的
     * `AuthProvider` 按 host 注入；以前没解析，所以产物只有名字没有下载入口）。
     */
    val archiveDownloadUrl: String = "",
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
                archiveDownloadUrl = o.optString("archive_download_url"),
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
    /**
     * 产生这条注解的 check-run 名字。
     *
     * GitHub 的注解接口只给「文件 + 行号」，不给「哪个任务报的」—— 那正是它以前只能沉在页面
     * 底部的原因。名字从 `/commits/{sha}/check-runs` 一起取（同一次请求里就有 `id` 与 `name`），
     * 于是注解得以回到对应的任务卡片里（见 [jobBelongsToAnnotation]）。
     */
    val checkRunName: String = "",
)

fun parseWorkflowAnnotations(json: String?, checkRunName: String = ""): List<WorkflowAnnotation> {
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
                checkRunName = checkRunName,
            )
        }
    }.getOrDefault(emptyList())
}

/** 一个「有注解」的 check-run：[id] + 名字（名字用来把注解归回任务）。 */
data class AnnotationCheckRun(val id: Long, val name: String)

/** 从 check-runs 列表里挑出「有注解」的那些（避免为每个 check-run 都发一次请求）。 */
fun annotationCheckRuns(json: String?): List<AnnotationCheckRun> {
    if (json.isNullOrBlank() || json.startsWith("ERROR:")) return emptyList()
    return runCatching {
        val arr = JSONObject(json).optJSONArray("check_runs") ?: return@runCatching emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val count = o.optJSONObject("output")?.optInt("annotations_count", 0) ?: 0
            if (count <= 0) null else AnnotationCheckRun(o.optLong("id"), o.optString("name"))
        }
    }.getOrDefault(emptyList())
}

/**
 * 这条注解是不是某个任务报的。
 *
 * 依据是 check-run 名字与任务名（GitHub 的 check-run 名字通常就等于 job 名，
 * 但会带前缀/后缀，所以复用与「日志段 ↔ 步骤」同一套模糊匹配）。
 * 匹配不上就留空，由调用方放进底部的「其他注解」聚合区 —— **宁可放不对，不要放错**。
 */
fun jobBelongsToAnnotation(annotation: WorkflowAnnotation, job: RunJob): Boolean =
    segmentMatchesStep(annotation.checkRunName, job.name)

/** 从 check-runs 列表里挑出「有注解」的 run id（避免为每个 check-run 都发一次请求）。 */
fun annotationCheckRunIds(json: String?): List<Long> = annotationCheckRuns(json).map { it.id }

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

/**
 * 运行/步骤状态的资源 ID（GitHub 的 status + conclusion 组合）；**null = 没有专属资源**，
 * 调用方原样透出 status。
 *
 * 模型层不认识 Context，所以这里只给 ID；「未知状态原样透出」这条约定由 [runStatusLabel]
 * 落地，映射本身仍可单测（见 `WorkflowModelsTest`）。
 */
fun runStatusLabelResOrNull(status: String, conclusion: String?): Int? =
    when (conclusion.normalizedConclusion()) {
        "success" -> R.string.workflow_status_success
        "failure" -> R.string.workflow_status_failure
        "cancelled" -> R.string.workflow_status_cancelled
        "skipped" -> R.string.workflow_status_skipped
        "timed_out" -> R.string.workflow_status_timed_out
        "action_required" -> R.string.workflow_status_action_required
        "neutral" -> R.string.workflow_status_neutral
        "stale" -> R.string.workflow_status_stale
        "startup_failure" -> R.string.workflow_status_startup_failure
        else -> when (status) {
            "queued" -> R.string.workflow_status_queued
            "in_progress" -> R.string.workflow_status_in_progress
            "requested" -> R.string.workflow_status_requested
            "waiting", "pending" -> R.string.workflow_status_waiting
            "completed" -> R.string.workflow_status_completed
            "" -> R.string.workflow_status_unknown
            else -> null
        }
    }

/** 运行/步骤状态的展示文案。`let` 是 inline，所以这里能调 [stringResource]。 */
@Composable
fun runStatusLabel(status: String, conclusion: String?): String =
    runStatusLabelResOrNull(status, conclusion)?.let { stringResource(it) } ?: status

/** 触发事件的资源 ID（GitHub 的 event 字段）；null = 没有专属资源，调用方原样透出。 */
fun eventLabelResOrNull(event: String): Int? = when (event) {
    "push" -> R.string.workflow_event_push
    "pull_request" -> R.string.workflow_event_pull_request
    "workflow_dispatch" -> R.string.workflow_event_workflow_dispatch
    "schedule" -> R.string.workflow_event_schedule
    "release" -> R.string.workflow_event_release
    "issues" -> R.string.workflow_event_issues
    "issue_comment" -> R.string.workflow_event_issue_comment
    "pull_request_review" -> R.string.workflow_event_pull_request_review
    "merge_group" -> R.string.workflow_event_merge_queue
    "dynamic" -> R.string.workflow_event_dynamic
    "repository_dispatch" -> R.string.workflow_event_repository_dispatch
    else -> null
}

/** 触发事件的展示文案（空事件用 `—`）。 */
@Composable
fun eventLabel(event: String): String =
    eventLabelResOrNull(event)?.let { stringResource(it) } ?: event.ifBlank { "—" }

// ── 日志分段 ──

// 日志的取数、缓存与「按 `##[group]` 切段」都在独立模块 :joblogs 里
// （`JobLogStore` / `LogSegment` / `splitJobLogBySteps`）；本文件只留
// 「段落标题 ↔ 步骤名」的匹配 —— 它要读 JobStep 这个 App 模型，属于渲染关切。

// ── 运行中的差分 ──

/**
 * 一次运行的进度快照：头部进度条的**计数**与分段控件的数字都读它。
 *
 * 分类口径与状态点一致（[isFailedConclusion] 那套白名单）：`success` 算成功；
 * `cancelled` / `skipped` / 未知结论都**不算失败**；还没跑完的按 `status` 分「运行中 / 排队」。
 *
 * ⚠️ 这里曾经用「非空结论且不是 skipped/cancelled 就算失败」反推，而 `conclusion: null`
 * （进行中的 job）被 `org.json` 解析成字符串 `"null"` ⇒ **正在跑的工作流被算成失败**。
 * 现在判定走白名单，且结论先过 [normalizedConclusion]。
 */
data class RunProgress(val ok: Int, val failed: Int, val running: Int, val waiting: Int) {
    val total: Int get() = ok + failed + running + waiting
    /** 只有「有失败」或「还没跑完」时才值得占头部一行（全成功的小运行不必显示进度）。 */
    val worthShowing: Boolean get() = failed > 0 || running > 0 || waiting > 0
}

fun runProgress(jobs: List<RunJob>): RunProgress {
    var ok = 0; var failed = 0; var running = 0; var waiting = 0
    jobs.forEach { j ->
        val conclusion = j.conclusion.normalizedConclusion()
        when {
            conclusion == "success" -> ok++
            // 白名单：只有明确的失败结论才计失败（未知结论宁可不算，见 isFailedConclusion）
            isFailedConclusion(conclusion) -> failed++
            j.status == "in_progress" -> running++
            else -> waiting++
        }
    }
    return RunProgress(ok, failed, running, waiting)
}

/**
 * 与上一次快照比较，挑出**刚刚变成 `completed`** 的 job id。
 *
 * 这是运行中唯一值得抓日志的时刻：远端的日志 blob 只有 job 结束后才存在
 * （在此之前 404），所以「步骤定稿」= 「可以抓一次日志」。
 * 已经 completed 的 job 不会重复出现（差分而不是全量），因此也不会重复抓。
 *
 * 纯函数，可 JVM 单测；`previous` 用 `id → status` 的映射，避免传整个模型。
 */
fun newlyCompletedJobIds(previous: Map<Long, String>, current: List<RunJob>): List<Long> =
    current.filter { it.status == "completed" && previous[it.id] != "completed" }.map { it.id }

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
