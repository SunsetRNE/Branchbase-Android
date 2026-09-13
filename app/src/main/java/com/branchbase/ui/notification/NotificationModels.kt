package com.branchbase.ui.notification

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.branchbase.ui.theme.TintRole
import org.json.JSONArray
import org.json.JSONObject

/**
 * 通知解析模块。
 *
 * 数据模型 + `subject.type`/`reason` 语义映射 + JSON 解析 + 跳转目标抽取。
 * 读取复用 `RustBridge.getJson(host, token, "/notifications")`，无需新增 native 函数。
 */

/** 通知分类维度：消息通知（人协作） vs 服务通知（机器/系统） */
enum class NotifKind { MESSAGE, SERVICE }

/** 跳转目标（通知点击后的落地页路由） */
sealed class NotifTarget {
    data class Issue(val owner: String, val repo: String, val number: Long) : NotifTarget()
    data class Pull(val owner: String, val repo: String, val number: Long) : NotifTarget()
    data class Commit(val owner: String, val repo: String, val sha: String) : NotifTarget()
    data class Run(val owner: String, val repo: String, val runId: Long) : NotifTarget()

    /**
     * 落到仓库的「工作流」tab。
     *
     * 只有 CheckSuite / CheckRun 会走这里：它们的 `subject.url` 是
     * `.../check-suites/<id>` / `.../check-runs/<id>`（对 CheckSuite 还常常直接是 `null`），
     * 那个 id 与 **run id 不同域** —— 以前这里直接当成 runId 用，点通知会打开一个
     * **编号巧合的、不相干的 run**。
     *
     * ⚠️ 这只是**纯函数的兜底**：点击时 [NotificationScreen.onNotifClick] 还会拿标题里的
     * 「工作流名 + 分支」去 run 列表配对（见 [CheckSuiteHint]），配对成功会改跳
     * [Run]；配不上才真的落到这里。
     */
    data class Workflows(val owner: String, val repo: String) : NotifTarget()
    data class Security(val owner: String, val repo: String, val title: String, val subjectUrl: String) : NotifTarget()
    data class Repo(val owner: String, val repo: String) : NotifTarget() // 兜底
}

/**
 * 通知模型。
 * 原始字段 1:1 对齐 `GET /notifications` 返回；派生字段解析时计算，供 UI 直接消费。
 *
 * ⚠️ [updatedAtMs] 是**原始时间戳**，[relativeTimeOf] 在渲染期计算相对时间。
 * 改造前把「3 分钟前」在解析期算成字符串，一旦列表来自内存快照/缓存，
 * 这个字符串就被冻结成常量（首页预取后进消息页会显示过期时间）。
 */
data class Notification(
    // ── 原始字段 ──
    val id: String,                 // thread id（唯一，PATCH 已读用）
    val unread: Boolean,
    val reason: String,             // 原始 reason
    val subjectType: String,        // 原始 subject.type
    val title: String,              // subject.title
    val url: String,                // subject.url（跳转解析用）
    // subject.latest_comment_url：该 thread 最新一条评论的 API URL。
    // 通知行里的「内容预览」（谁说了什么）靠它拉取，见 NotificationPreviewLoader.kt。
    // 注意它可能退化成 issue/PR 本体或 commit/discussion 的 URL，因此消费方必须校验形态。
    val latestCommentUrl: String?,
    val repoFullName: String,       // repository.full_name
    val updatedAt: String,          // 原始 ISO8601（展示/排查用）
    val updatedAtMs: Long,          // 原始时间的毫秒值（相对时间在渲染期由它算出）
    // ── 派生字段 ──
    val kind: NotifKind,
    val icon: ImageVector,
    val tint: TintRole,
    val reasonLabel: String,
    val reasonColor: TintRole,
    val owner: String,
    val repo: String,
    val targetNumber: Long?,        // issue/PR/run/release 编号
    val targetSha: String?,         // commit sha
) {
    /** issue / PR 这两类才有「内容预览」与「过往 Issue」语义。 */
    val issueLike: Boolean get() = subjectType == "Issue" || subjectType == "PullRequest"
}

/** subject.type → (kind, 图标, 语义色, 标签) */
private data class TypeMeta(val kind: NotifKind, val icon: ImageVector, val tint: TintRole, val label: String)

private val TYPE_META: Map<String, TypeMeta> = mapOf(
    "Issue" to TypeMeta(NotifKind.MESSAGE, Icons.Filled.Adjust, TintRole.SUCCESS, "Issue"),
    "PullRequest" to TypeMeta(NotifKind.MESSAGE, Icons.AutoMirrored.Filled.CallMerge, TintRole.DONE, "Pull Request"),
    "Discussion" to TypeMeta(NotifKind.MESSAGE, Icons.Filled.Code, TintRole.ACCENT, "Discussion"),
    "Release" to TypeMeta(NotifKind.MESSAGE, Icons.Filled.LocalOffer, TintRole.ACCENT, "Release"),
    "Commit" to TypeMeta(NotifKind.MESSAGE, Icons.Filled.History, TintRole.NEUTRAL, "Commit"),
    "CheckSuite" to TypeMeta(NotifKind.SERVICE, Icons.Filled.PlayCircle, TintRole.DANGER, "Workflow"),
    "CheckRun" to TypeMeta(NotifKind.SERVICE, Icons.Filled.PlayCircle, TintRole.DANGER, "Workflow"),
    "WorkflowRun" to TypeMeta(NotifKind.SERVICE, Icons.Filled.PlayCircle, TintRole.DANGER, "Workflow"),
    "RepositoryVulnerabilityAlert" to TypeMeta(NotifKind.SERVICE, Icons.Filled.Warning, TintRole.DANGER, "Security"),
    "RepositoryAdvisory" to TypeMeta(NotifKind.SERVICE, Icons.Filled.Warning, TintRole.DANGER, "Security"),
)

private val FALLBACK_TYPE = TypeMeta(NotifKind.MESSAGE, Icons.Filled.Adjust, TintRole.NEUTRAL, "Notification")

/** reason → (中文文案, 胶囊色) */
private data class ReasonMeta(val label: String, val color: TintRole)

private val REASON_META: Map<String, ReasonMeta> = mapOf(
    "mention" to ReasonMeta("提到了你", TintRole.ACCENT),
    "team_mention" to ReasonMeta("提到了你的团队", TintRole.ACCENT),
    "review_requested" to ReasonMeta("请求你审查", TintRole.DONE),
    "assign" to ReasonMeta("分配给了你", TintRole.WARNING),
    "security_alert" to ReasonMeta("安全警报", TintRole.DANGER),
    "ci_activity" to ReasonMeta("CI 运行结果", TintRole.WARNING),
    "state_change" to ReasonMeta("状态更新", TintRole.SUCCESS),
    "comment" to ReasonMeta("评论了", TintRole.NEUTRAL_SUBTLE),
    "subscribed" to ReasonMeta("你订阅的", TintRole.NEUTRAL_SUBTLE),
    "manual" to ReasonMeta("你订阅的", TintRole.NEUTRAL_SUBTLE),
    "author" to ReasonMeta("你创建的", TintRole.NEUTRAL_SUBTLE),
)

private val FALLBACK_REASON = ReasonMeta("你订阅的", TintRole.NEUTRAL_SUBTLE)

/**
 * 从 subject.url 抽取编号/sha（如 `.../issues/42` → "42"，`.../commits/abc` → "abc"）。
 *
 * 必须覆盖 `check-suites` / `check-runs`：GitHub 的 CheckSuite / CheckRun 通知
 * subject.url 就是这两种形态（只有 WorkflowRun 才是 `/actions/runs/<id>`），
 * 漏掉它们会让 targetNumber 为 null，下游再兜底成 0 → 跳到一个不存在的 run #0。
 */
private fun extractNumber(url: String): Long? =
    Regex("/(?:issues|pulls|releases|discussions|runs|check-suites|check-runs)/(\\d+)")
        .find(url)?.groupValues?.get(1)?.toLongOrNull()

private fun extractSha(url: String): String? =
    Regex("/commits/([0-9a-fA-F]+)").find(url)?.groupValues?.get(1)

/** ISO8601 → 毫秒（解析失败返回 0，调用方按「时间未知」处理） */
fun parseIsoMs(iso: String): Long =
    runCatching { java.time.Instant.parse(iso).toEpochMilli() }.getOrDefault(0L)

/**
 * 相对时间（**渲染期计算**，输入原始毫秒时间戳）。
 *
 * 放在渲染期而不是解析期的原因见 [Notification.updatedAtMs] 的注释；
 * 预加载快照会存几十分钟，解析期算好的相对时间必然失真。
 */
fun relativeTimeOf(ms: Long, nowMs: Long = System.currentTimeMillis()): String {
    if (ms <= 0L) return ""
    val diff = (nowMs - ms).coerceAtLeast(0L)
    val m = diff / 60000
    val h = diff / 3600000
    val d = diff / 86400000
    return when {
        m < 1 -> "刚刚"
        m < 60 -> "$m 分钟前"
        h < 24 -> "$h 小时前"
        d == 1L -> "昨天"
        d < 30 -> "$d 天前"
        else -> {
            val dt = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault())
            "${dt.monthValue} 月 ${dt.dayOfMonth} 日"
        }
    }
}

/**
 * 由「原始字段 + 派生规则」构造 [Notification]。
 *
 * 统一入口的意义：网络解析与本地归档回读（[ArchivedThread.toNotification]）
 * 走同一条派生规则，不会出现「归档回来的行没有图标/胶囊色」这种两套逻辑。
 */
fun notificationOf(
    id: String,
    unread: Boolean,
    reason: String,
    subjectType: String,
    title: String,
    url: String,
    latestCommentUrl: String?,
    repoFullName: String,
    updatedAtMs: Long,
    updatedAt: String = "",
): Notification {
    val tm = TYPE_META[subjectType] ?: FALLBACK_TYPE
    val rm = REASON_META[reason] ?: FALLBACK_REASON
    return Notification(
        id = id,
        unread = unread,
        reason = reason,
        subjectType = subjectType,
        title = title.ifBlank { "（无标题）" },
        url = url,
        latestCommentUrl = latestCommentUrl,
        repoFullName = repoFullName,
        updatedAt = updatedAt,
        updatedAtMs = updatedAtMs,
        kind = tm.kind,
        icon = tm.icon,
        tint = tm.tint,
        reasonLabel = rm.label,
        reasonColor = rm.color,
        owner = repoFullName.substringBefore('/'),
        repo = repoFullName.substringAfter('/', ""),
        targetNumber = extractNumber(url),
        targetSha = extractSha(url),
    )
}

/** 解析 `GET /notifications` 返回的 JSON 数组 */
fun parseNotifications(json: String): List<Notification> = runCatching {
    val arr = JSONArray(json)
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        val subject = o.optJSONObject("subject") ?: org.json.JSONObject()
        val updatedAt = o.optString("updated_at")
        notificationOf(
            id = o.optString("id"),
            unread = o.optBoolean("unread"),
            reason = o.optString("reason", "subscribed"),
            subjectType = subject.optString("type", "Issue"),
            title = subject.optString("title"),
            url = subject.optString("url"),
            // optString 遇到 JSON null 会返回字符串 "null"，显式滤掉
            latestCommentUrl = subject.optString("latest_comment_url")
                .takeIf { it.isNotBlank() && it != "null" },
            repoFullName = o.optJSONObject("repository")?.optString("full_name").orEmpty(),
            updatedAtMs = parseIsoMs(updatedAt),
            updatedAt = updatedAt,
        )
    }
}.getOrDefault(emptyList())

/**
 * 首屏列表的查询串（**唯一真源**）。
 *
 * 预加载器与页面必须用同一份串拼键：少一个 `&all=true` 就会导致
 * 预取写的键与页面读的键不一致、缓存永不命中（仓库页的 README 键已经踩过一次）。
 */
fun notifListPath(participating: Boolean = false, before: String? = null): String = buildString {
    append("/notifications?per_page=50&all=true")
    if (participating) append("&participating=true")
    if (before != null) append("&before=").append(java.net.URLEncoder.encode(before, "UTF-8"))
}

// ───────────────────────── 筛选 / 视图维度（右下角面板用） ─────────────────────────

/**
 * 分类（面板第一段）。
 *
 * 与改造前的「未读 / 全部」两格相比新增两类：
 * - [PARTICIPATING]：服务端 `participating=true`（我参与/被提及的会话），此前没有入口；
 * - [DONE]：本地归档（GitHub 没有 done 列表，官方 App 也是本地维护），
 *   让「处理完的消息」有一个可回看的去处，而不是从列表里凭空消失。
 */
enum class NotifCategory(val label: String) {
    UNREAD("未读"),
    ALL("全部"),
    PARTICIPATING("参与"),
    DONE("已完成"),
}

/** 时间范围（客户端过滤，基于 `updated_at`）。 */
enum class NotifRange(val label: String, val maxAgeMs: Long?) {
    TODAY("今天", 24 * 60 * 60 * 1000L),
    THREE_DAYS("近 3 天", 3 * 24 * 60 * 60 * 1000L),
    WEEK("近 7 天", 7 * 24 * 60 * 60 * 1000L),
    ALL("全部", null),
}

/** 排序。 */
enum class NotifSort(val label: String) {
    NEWEST("最新在前"),
    OLDEST("最早在前"),
    UNREAD_FIRST("未读优先"),
}

/** 类型筛选用到的**短名**（面板芯片与列表胶囊共用，避免同类型两种文案）。 */
fun typeShortName(subjectType: String): String = when (subjectType) {
    "PullRequest" -> "PR"
    "Discussion" -> "讨论"
    "Release" -> "版本"
    "Commit" -> "提交"
    "CheckSuite", "CheckRun", "WorkflowRun" -> "工作流"
    "RepositoryVulnerabilityAlert", "RepositoryAdvisory" -> "安全"
    else -> subjectType
}

/**
 * 决策渲染：subject.type → 落地页路由目标。
 *
 * 编号缺失时**退到仓库页**，而不是 `?: 0`：`#0` 必然是打不开的页面，
 * 仓库页至少把用户放在正确的仓库里（例如 CheckSuite 通知拿不到 run 编号时）。
 */
fun resolveTarget(n: Notification): NotifTarget = when (n.subjectType) {
    "Issue" -> n.targetNumber?.let { NotifTarget.Issue(n.owner, n.repo, it) }
        ?: NotifTarget.Repo(n.owner, n.repo)
    "PullRequest" -> n.targetNumber?.let { NotifTarget.Pull(n.owner, n.repo, it) }
        ?: NotifTarget.Repo(n.owner, n.repo)
    "Commit" -> NotifTarget.Commit(n.owner, n.repo, n.targetSha.orEmpty())
    // 只有 WorkflowRun 的 subject.url 是 `/actions/runs/<id>`，那个 id 才是 run id。
    // CheckSuite / CheckRun 的 id 是 check 域的编号，**不能**当 run id 用（会把用户带到
    // 一个编号巧合的无关 run）—— 通知里拿不到 run id，只能落到工作流列表。
    "WorkflowRun" -> n.targetNumber?.let { NotifTarget.Run(n.owner, n.repo, it) }
        ?: NotifTarget.Workflows(n.owner, n.repo)
    "CheckSuite", "CheckRun" -> NotifTarget.Workflows(n.owner, n.repo)
    "RepositoryVulnerabilityAlert", "RepositoryAdvisory" -> NotifTarget.Security(n.owner, n.repo, n.title, n.url)
    else -> NotifTarget.Repo(n.owner, n.repo)
}

// ───────────────────────── 工作流通知 → 具体 run（点击时解析） ─────────────────────────

/**
 * 工作流通知的**标题线索**（CheckSuite / CheckRun / WorkflowRun 共用同一套标题格式）。
 *
 * ## 为什么只能从标题里抠
 *
 * GitHub **不在通知里给 run id**：
 * - `subject.url` 对 CheckSuite 常常直接就是 `null`（社区讨论 #158253「Missing subject URL
 *   field for CheckSuite Notification type」）；
 * - 即便给了，形态也是 `.../check-suites/<id>` —— 那是 **check 域的编号，不是 run id**，
 *   直接当 run id 用会打开一个编号巧合的无关 run（1.0.29 修过一次这个 bug）。
 *
 * 能用的只有标题，格式为
 * `"<workflowName> workflow run[, Attempt #N] <status> for <branch> branch"`，
 * 例如 `"CI workflow run failed for main branch"`、
 * `"Deploy workflow run, Attempt #2 succeeded for release/1.0 branch"`。
 * 这个格式与 gitify（成熟的三方通知客户端，见 `utils/forges/github/handlers/checkSuite.ts`）
 * 从真实报文反推出的正则一致；它的注释也写明「目前没有干净的办法用 API 直接拿 CheckSuite /
 * WorkflowRun 的状态」，所以那边同样退回带筛选的 Actions 列表页。
 *
 * 本应用能做得更好一点：拿「工作流名 + 分支（+ attempt / 结论）」去 run 列表里配对，
 * 配对不上就**老实退回工作流列表** —— 绝不猜一个编号去开一个无关的 run。
 */
data class CheckSuiteHint(
    val workflowName: String,
    val branch: String,
    val attemptNumber: Int?,
    /** 归一化到 API 的 `conclusion` 取值域；对不上时为 null（不参与筛选） */
    val conclusion: String?,
)

/** 标题：`<workflow> workflow run[, Attempt #N] <status> for <branch> branch` */
private val CHECK_SUITE_TITLE = Regex("^(.*?) workflow run(?:, Attempt #(\\d+))? (.*?) for (.*?) branch$")

/** 解析工作流通知标题；格式对不上返回 null（调用方退回工作流列表）。 */
fun parseCheckSuiteTitle(title: String): CheckSuiteHint? {
    val m = CHECK_SUITE_TITLE.find(title.trim()) ?: return null
    val workflow = m.groupValues[1].trim()
    val branch = m.groupValues[4].trim()
    if (workflow.isEmpty() || branch.isEmpty()) return null
    return CheckSuiteHint(
        workflowName = workflow,
        branch = branch,
        attemptNumber = m.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull(),
        conclusion = conclusionOf(m.groupValues[3].trim()),
    )
}

/** 标题里的口语化状态 → API 的 `conclusion` 取值域（认不出就返回 null，不拿它筛选）。 */
private fun conclusionOf(display: String): String? = when (display) {
    "succeeded" -> "success"
    "failed", "failed at startup" -> "failure"
    "cancelled" -> "cancelled"
    "skipped" -> "skipped"
    else -> null
}

/** run 列表里的一条，只保留配对用得到的字段。 */
data class RunCandidate(
    val id: Long,
    val name: String,
    val branch: String,
    val attempt: Int,
    val conclusion: String?,
    val updatedAtMs: Long,
)

/** 解析 `GET /repos/{o}/{r}/actions/runs` 的 `{workflow_runs:[…]}`（只取配对需要的那几项）。 */
fun parseRunCandidates(json: String): List<RunCandidate> = runCatching {
    val arr = JSONObject(json).optJSONArray("workflow_runs") ?: return@runCatching emptyList()
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        RunCandidate(
            id = o.optLong("id"),
            name = o.optString("name").trim(),
            branch = o.optString("head_branch"),
            attempt = o.optInt("run_attempt", 1),
            conclusion = o.optString("conclusion").takeIf { it.isNotBlank() },
            updatedAtMs = parseIsoMs(o.optString("updated_at")),
        )
    }
}.getOrDefault(emptyList())

/** 允许的最大时间偏差：超过就认为没找到（宁可退回列表，也不开一个「看起来像」的 run）。 */
private const val RUN_MATCH_MAX_GAP_MS = 24 * 60 * 60 * 1000L

/**
 * 从候选 run 里挑出通知所指的那一次（纯函数，有单测）。
 *
 * 先用「工作流名 + 分支」硬筛（标题里给了 attempt / 结论就一并要求相等），再用**时间最近**收口：
 * 同一个工作流在同一分支上会跑很多次，而 run 的 `updated_at` 就是它结束、通知发出的那一刻。
 * 一个都匹配不上、或最好的那个偏差超过 [RUN_MATCH_MAX_GAP_MS] 时返回 null —— 退回工作流列表。
 */
fun pickRunId(candidates: List<RunCandidate>, hint: CheckSuiteHint, notifUpdatedAtMs: Long): Long? {
    val matched = candidates.filter { c ->
        c.name == hint.workflowName &&
            c.branch == hint.branch &&
            (hint.attemptNumber == null || c.attempt == hint.attemptNumber) &&
            (hint.conclusion == null || c.conclusion == hint.conclusion)
    }
    val best = matched.minByOrNull { kotlin.math.abs(it.updatedAtMs - notifUpdatedAtMs) } ?: return null
    // 通知时间未知（0）时不拿时间卡人，只凭「名字 + 分支」的硬筛结果
    if (notifUpdatedAtMs > 0 && kotlin.math.abs(best.updatedAtMs - notifUpdatedAtMs) > RUN_MATCH_MAX_GAP_MS) {
        return null
    }
    return best.id
}

// ───────────────────────── 安全警报详情（Dependabot alerts / security-advisories） ─────────────────────────

/** 从完整 API URL 提取 path（去掉 host 前缀，供 `getJson(host, token, path)` 复用） */
fun extractPathFromUrl(url: String): String? {
    val idx = url.indexOf("/repos/")
    return if (idx >= 0) url.substring(idx) else null
}

/** 安全警报详情（跨 Dependabot alerts / security-advisories 两种数据源的统一字段） */
data class SecurityDetail(
    val severity: String? = null,
    val summary: String? = null,
    val description: String? = null,
    val dependency: String? = null,
    val publishedAt: String? = null,
    val cveId: String? = null,
)

/** 解析安全警报详情 JSON（兼容 Dependabot alerts 的 `security_advisory` 嵌套与 security-advisories 的平铺字段） */
fun parseSecurityDetail(json: String): SecurityDetail = runCatching {
    val o = org.json.JSONObject(json)
    val advisory = o.optJSONObject("security_advisory")
    fun pick(key: String): String? =
        advisory?.optString(key)?.takeIf { it.isNotBlank() }
            ?: o.optString(key).takeIf { it.isNotBlank() }
    val severity = pick("severity")
    val summary = pick("summary")
    val description = pick("description")
    val cveId = o.optString("cve_id").takeIf { it.isNotBlank() && it != "null" }
        ?: advisory?.optString("cve_id")?.takeIf { it.isNotBlank() && it != "null" }
    val dependency = o.optJSONObject("dependency")?.optJSONObject("package")?.optString("name")?.takeIf { it.isNotBlank() }
    val publishedAt = o.optString("published_at").takeIf { it.isNotBlank() }
        ?: o.optString("created_at").takeIf { it.isNotBlank() }
    SecurityDetail(severity, summary, description, dependency, publishedAt, cveId)
}.getOrDefault(SecurityDetail())

// ───────────────────────── 通知显示模式（用户可选，默认平铺） ─────────────────────────

/** 通知列表显示模式：平铺 / 按仓库分组 / 按 thread 合并 / 两级折叠 */
enum class NotifLayout(val label: String, val desc: String) {
    FLAT("平铺", "每条通知独立展示"),
    GROUP_BY_REPO("按仓库分组", "同一仓库的通知折叠为一组"),
    MERGE_BY_THREAD("按主题合并", "同一 issue/PR 的多条更新折叠"),
    TWO_LEVEL("两级折叠", "仓库分组 + 主题合并"),
}

const val KEY_NOTIF_LAYOUT = "notif_layout"

/** 读取通知显示模式（未设置默认 FLAT） */
fun readNotifLayout(context: Context): NotifLayout {
    val name = context.getSharedPreferences("branchbase", Context.MODE_PRIVATE)
        .getString(KEY_NOTIF_LAYOUT, null) ?: return NotifLayout.FLAT
    return NotifLayout.entries.firstOrNull { it.name == name } ?: NotifLayout.FLAT
}

/** 持久化通知显示模式 */
fun writeNotifLayout(context: Context, layout: NotifLayout) {
    context.getSharedPreferences("branchbase", Context.MODE_PRIVATE)
        .edit().putString(KEY_NOTIF_LAYOUT, layout.name).apply()
}
