package com.branchbase.ui.notification

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
import com.branchbase.ui.theme.Primer
import org.json.JSONArray

/**
 * 通知解析模块（对齐 `docs/notification-parsing-module.md`）。
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
    data class Security(val owner: String, val repo: String) : NotifTarget()
    data class Repo(val owner: String, val repo: String) : NotifTarget() // 兜底
}

/**
 * 通知模型。
 * 原始字段 1:1 对齐 `GET /notifications` 返回；派生字段解析时计算，供 UI 直接消费。
 */
data class Notification(
    // ── 原始字段 ──
    val id: String,                 // thread id（唯一，PATCH 已读用）
    val unread: Boolean,
    val reason: String,             // 原始 reason
    val subjectType: String,        // 原始 subject.type
    val title: String,              // subject.title
    val url: String,                // subject.url（跳转解析用）
    val repoFullName: String,       // repository.full_name
    val updatedAt: String,          // 原始 ISO8601
    // ── 派生字段 ──
    val kind: NotifKind,
    val icon: ImageVector,
    val tint: Color,
    val reasonLabel: String,
    val reasonColor: Color,
    val owner: String,
    val repo: String,
    val targetNumber: Long?,        // issue/PR/run/release 编号
    val targetSha: String?,         // commit sha
    val relativeTime: String,
)

/** subject.type → (kind, 图标, 语义色, 标签) */
private data class TypeMeta(val kind: NotifKind, val icon: ImageVector, val tint: Color, val label: String)

private val TYPE_META: Map<String, TypeMeta> = mapOf(
    "Issue" to TypeMeta(NotifKind.MESSAGE, Icons.Filled.Adjust, Primer.Green500, "Issue"),
    "PullRequest" to TypeMeta(NotifKind.MESSAGE, Icons.AutoMirrored.Filled.CallMerge, Primer.Purple500, "Pull Request"),
    "Discussion" to TypeMeta(NotifKind.MESSAGE, Icons.Filled.Code, Primer.Blue500, "Discussion"),
    "Release" to TypeMeta(NotifKind.MESSAGE, Icons.Filled.LocalOffer, Primer.Blue500, "Release"),
    "Commit" to TypeMeta(NotifKind.MESSAGE, Icons.Filled.History, Primer.Gray600, "Commit"),
    "CheckSuite" to TypeMeta(NotifKind.SERVICE, Icons.Filled.PlayCircle, Primer.Red500, "Workflow"),
    "CheckRun" to TypeMeta(NotifKind.SERVICE, Icons.Filled.PlayCircle, Primer.Red500, "Workflow"),
    "WorkflowRun" to TypeMeta(NotifKind.SERVICE, Icons.Filled.PlayCircle, Primer.Red500, "Workflow"),
    "RepositoryVulnerabilityAlert" to TypeMeta(NotifKind.SERVICE, Icons.Filled.Warning, Primer.Red500, "Security"),
    "RepositoryAdvisory" to TypeMeta(NotifKind.SERVICE, Icons.Filled.Warning, Primer.Red500, "Security"),
)

private val FALLBACK_TYPE = TypeMeta(NotifKind.MESSAGE, Icons.Filled.Adjust, Primer.Gray600, "Notification")

/** reason → (中文文案, 胶囊色) */
private data class ReasonMeta(val label: String, val color: Color)

private val REASON_META: Map<String, ReasonMeta> = mapOf(
    "mention" to ReasonMeta("提到了你", Primer.Blue500),
    "team_mention" to ReasonMeta("提到了你的团队", Primer.Blue500),
    "review_requested" to ReasonMeta("请求你审查", Primer.Purple500),
    "assign" to ReasonMeta("分配给了你", Primer.Orange500),
    "security_alert" to ReasonMeta("安全警报", Primer.Red500),
    "ci_activity" to ReasonMeta("CI 运行结果", Primer.Orange500),
    "state_change" to ReasonMeta("状态更新", Primer.Green500),
    "comment" to ReasonMeta("评论了", Primer.Gray500),
    "subscribed" to ReasonMeta("你订阅的", Primer.Gray500),
    "manual" to ReasonMeta("你订阅的", Primer.Gray500),
    "author" to ReasonMeta("你创建的", Primer.Gray500),
)

private val FALLBACK_REASON = ReasonMeta("你订阅的", Primer.Gray500)

/** 从 subject.url 抽取编号/sha（如 `.../issues/42` → "42"，`.../commits/abc` → "abc"） */
private fun extractNumber(url: String): Long? =
    Regex("/(?:issues|pulls|releases|discussions|runs)/(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()

private fun extractSha(url: String): String? =
    Regex("/commits/([0-9a-fA-F]+)").find(url)?.groupValues?.get(1)

/** 相对时间（刚刚 / N 分钟前 / N 小时前 / 昨天 / 月日） */
private fun fmtTime(iso: String): String {
    if (iso.isBlank()) return ""
    val t = runCatching { java.time.Instant.parse(iso).toEpochMilli() }.getOrNull() ?: return ""
    val diff = System.currentTimeMillis() - t
    val m = diff / 60000
    val h = diff / 3600000
    val d = diff / 86400000
    return when {
        m < 1 -> "刚刚"
        m < 60 -> "$m 分钟前"
        h < 24 -> "$h 小时前"
        d == 1L -> "昨天"
        else -> {
            val dt = java.time.Instant.ofEpochMilli(t).atZone(java.time.ZoneId.systemDefault())
            "${dt.monthValue} 月 ${dt.dayOfMonth} 日"
        }
    }
}

/** 解析 `GET /notifications` 返回的 JSON 数组 */
fun parseNotifications(json: String): List<Notification> = runCatching {
    val arr = JSONArray(json)
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        val subject = o.optJSONObject("subject") ?: org.json.JSONObject()
        val subjectType = subject.optString("type", "Issue")
        val tm = TYPE_META[subjectType] ?: FALLBACK_TYPE
        val reason = o.optString("reason", "subscribed")
        val rm = REASON_META[reason] ?: FALLBACK_REASON
        val fullName = o.optJSONObject("repository")?.optString("full_name").orEmpty()
        val owner = fullName.substringBefore('/')
        val repo = fullName.substringAfter('/', "")

        Notification(
            id = o.optString("id"),
            unread = o.optBoolean("unread"),
            reason = reason,
            subjectType = subjectType,
            title = subject.optString("title").ifBlank { "（无标题）" },
            url = subject.optString("url"),
            repoFullName = fullName,
            updatedAt = o.optString("updated_at"),
            kind = tm.kind,
            icon = tm.icon,
            tint = tm.tint,
            reasonLabel = rm.label,
            reasonColor = rm.color,
            owner = owner,
            repo = repo,
            targetNumber = extractNumber(subject.optString("url")),
            targetSha = extractSha(subject.optString("url")),
            relativeTime = fmtTime(o.optString("updated_at")),
        )
    }
}.getOrDefault(emptyList())

/** 决策渲染：subject.type → 落地页路由目标 */
fun resolveTarget(n: Notification): NotifTarget = when (n.subjectType) {
    "Issue" -> NotifTarget.Issue(n.owner, n.repo, n.targetNumber ?: 0)
    "PullRequest" -> NotifTarget.Pull(n.owner, n.repo, n.targetNumber ?: 0)
    "Commit" -> NotifTarget.Commit(n.owner, n.repo, n.targetSha.orEmpty())
    "CheckSuite", "CheckRun", "WorkflowRun" -> NotifTarget.Run(n.owner, n.repo, n.targetNumber ?: 0)
    "RepositoryVulnerabilityAlert", "RepositoryAdvisory" -> NotifTarget.Security(n.owner, n.repo)
    else -> NotifTarget.Repo(n.owner, n.repo)
}