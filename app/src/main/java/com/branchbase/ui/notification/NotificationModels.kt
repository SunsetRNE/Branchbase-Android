package com.branchbase.ui.notification

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.vector.ImageVector
import com.branchbase.R
import com.branchbase.ui.LocalizedText
import com.branchbase.ui.settings.SettingsKeys
import com.branchbase.ui.theme.TintRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    /** 原因标签的**资源 ID**（不是文案）：模型层不认识 Context，交由 UI 侧 stringResource 解析。 */
    @StringRes val reasonLabelRes: Int,
    val reasonColor: TintRole,
    // 「这条通知是否在等我动手」：列表里只给高信号的挂原因标签，低信号的（CI 结果 / 你订阅的 /
    // 评论了…）不挂 —— 同一屏里 8 个「CI 运行结果」只是重复占位，结论已经在标题里了。
    // 与 [_reasonHighSignal] 同源，文案本身不丢：长按动作面板仍在读 reasonLabel。
    val reasonHighSignal: Boolean,
    val owner: String,
    val repo: String,
    val targetNumber: Long?,        // issue/PR/run/release 编号
    val targetSha: String?,         // commit sha
    // ── 折叠（见 [collapseCiRuns]；由页面渲染前派生，不来自网络）──
    val fold: NotifFold? = null,
) {
    /** issue / PR 这两类才有「内容预览」与「过往 Issue」语义。 */
    val issueLike: Boolean get() = subjectType == "Issue" || subjectType == "PullRequest"

    /**
     * 这一行**代表**的全部 thread id（折叠行 = 组内所有 id，普通行 = 只有自己）。
     *
     * 所有「按已读/完成写远端」的路径都必须用它，不能用 `id`：
     * 折叠行显示的是「N 次运行」的**汇总状态**，只把代表那条标为已读，
     * 刷新后剩下 N-1 条会重新组成一个新的折叠行冒出来（表现为「标记已读没生效」）。
     */
    val allIds: List<String> get() = fold?.ids ?: listOf(id)

    /**
     * 这一行**是不是**折叠行（组内 ≥ 2 条）。
     *
     * 判据必须是「count > 1」而不是「fold != null」：单条 CI 也带着 [fold]（组信息要留给
     * 合并时用），但它不该呈现折叠 —— 否则界面上会渲染出「展开其余 0 次」这种不成立的说法。
     */
    val isFolded: Boolean get() = (fold?.count ?: 1) > 1
}

/**
 * 折叠信息（**只在渲染层存在**，`GET /notifications` 不返回这个概念）。
 *
 * @param ids 组内全部 thread id，**首条是最新的那一次**（输入按时间倒序）
 * @param workflowName 工作流名（从标题解析；解析不出时为 null，此时整组不折叠）
 * @param branch 分支（同上）
 * @param runs 组内每一次运行的标题 + 时间，供展开后逐条显示
 */
data class NotifFold(
    val ids: List<String>,
    val workflowName: String,
    val branch: String,
    val runs: List<NotifFoldRun>,
) {
    /** 组内条数（含代表那条）。 */
    val count: Int get() = ids.size
}

/** 折叠组里的一次运行（标题与时间都取原始值，时间在渲染期算相对时间）。 */
data class NotifFoldRun(val title: String, val updatedAtMs: Long)

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

/** reason → (中文文案, 标签色, 是否高信号) */
/**
 * reason → (中文文案, 标签色, 是否高信号)。
 *
 * [highSignal] 是**构造参数**而不是类体属性：调用点用命名参数写 `highSignal = true`，
 * 写成类体属性则命名参数不成立（编译期直接报「No parameter with name」）。
 */
private data class ReasonMeta(@StringRes val labelRes: Int, val color: TintRole, val highSignal: Boolean = false)

/**
 * 需要你**动手**的 reason 才在列表行里挂标签；其余（CI 结果 / 你订阅的 / 评论了 / 状态更新…）
 * 一律不挂 —— 一屏 8 个「CI 运行结果」只是重复占位，而结论已经在标题里。
 *
 * 判据是「这条通知是否要求我做点什么」：被 @ / 被指派 / 被请求审查 / 安全警报都要求；
 * 「你订阅的」与「CI 结果」只要求知道，不要求动作。
 */
private val REASON_META: Map<String, ReasonMeta> = mapOf(
    "mention" to ReasonMeta(R.string.notif_reason_mention, TintRole.ACCENT, highSignal = true),
    "team_mention" to ReasonMeta(R.string.notif_reason_team_mention, TintRole.ACCENT, highSignal = true),
    "review_requested" to ReasonMeta(R.string.notif_reason_review_requested, TintRole.DONE, highSignal = true),
    "assign" to ReasonMeta(R.string.notif_reason_assign, TintRole.WARNING, highSignal = true),
    "security_alert" to ReasonMeta(R.string.notif_reason_security_alert, TintRole.DANGER, highSignal = true),
    "ci_activity" to ReasonMeta(R.string.notif_reason_ci_activity, TintRole.WARNING),
    "state_change" to ReasonMeta(R.string.notif_reason_state_change, TintRole.SUCCESS),
    "comment" to ReasonMeta(R.string.notif_reason_comment, TintRole.NEUTRAL_SUBTLE),
    "subscribed" to ReasonMeta(R.string.notif_reason_subscribed, TintRole.NEUTRAL_SUBTLE),
    "manual" to ReasonMeta(R.string.notif_reason_subscribed, TintRole.NEUTRAL_SUBTLE),
    "author" to ReasonMeta(R.string.notif_reason_author, TintRole.NEUTRAL_SUBTLE),
)

private val FALLBACK_REASON = ReasonMeta(R.string.notif_reason_subscribed, TintRole.NEUTRAL_SUBTLE)

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
 *
 * 返回 [LocalizedText] 而不是 `String`：这里六档里**混着 string 与 plurals**
 * （「刚刚」是 string，「N 分钟前」是 plurals —— 英文要分 `1 minute ago` / `2 minutes ago`），
 * 所以需要一个能同时装下两者的载体。分档判断留在纯函数里，因此可单测。
 */
fun relativeTimeOf(ms: Long, nowMs: Long = System.currentTimeMillis()): LocalizedText {
    // 时间戳缺失：解析为空串，界面上不占位（不显示「1970 年」这类噪声）
    if (ms <= 0L) return LocalizedText()
    val diff = (nowMs - ms).coerceAtLeast(0L)
    val m = diff / 60000
    val h = diff / 3600000
    val d = diff / 86400000
    return when {
        m < 1 -> LocalizedText(R.string.relative_just_now)
        m < 60 -> LocalizedText.plural(R.plurals.relative_minutes, m.toInt(), listOf(m))
        h < 24 -> LocalizedText.plural(R.plurals.relative_hours, h.toInt(), listOf(h))
        d == 1L -> LocalizedText(R.string.relative_yesterday)
        d < 30 -> LocalizedText.plural(R.plurals.relative_days, d.toInt(), listOf(d))
        else -> {
            val dt = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault())
            LocalizedText(R.string.relative_date_md, listOf(dt.monthValue, dt.dayOfMonth))
        }
    }
}

/**
 * 折叠「同一仓库 + 同一工作流 + 同一分支 + 同一天」的**相邻** CI 通知（纯函数，钉子见
 * `NotificationCiFoldTest`）。
 *
 * ## 为什么必须折叠
 *
 * 真机上（就是主分支上的一个仓库）连着 8 条 `Build workflow run failed for main branch`：
 * 同仓库、同分支、同一天，标题逐字相同。逐条渲染就是一屏一模一样的行 —— 读不出「今天挂了几次」，
 * 也不像消息列表。这与动态页「最近 30 条里 28 条是 PushEvent」是同一个问题，口径照抄
 * [com.branchbase.ui.profile.collapsePushes]。
 *
 * ## 边界（与 collapsePushes 逐条对齐）
 *
 * - **只在相邻条目之间折叠**：输入必须已按时间倒序排好（页面走 [sortedNotifications]），
 *   中间夹了别的通知就断开；
 * - **不跨天**（本地时区）：跨天合并会把「今天 3 次 + 昨天 5 次」写成 8 次，那是在编造事实；
 * - **工作流名与分支必须一致**：两者都从标题解析（[parseCheckSuiteTitle]）。解析不出来的
 *   （标题格式变了 / 不是工作流通知）**不参与折叠** —— 宁可多几行，也不要猜错把两次不同的运行并成一条；
 * - 组内保留**最新一次**（输入倒序 ⇒ 组内首条），它是这一组里唯一有定位价值的东西。
 *
 * 折叠后的代表行：`title` 换成汇总文案（`Build workflow 连续失败 · main`），`fold.count` 给出条数，
 * `fold.runs` 保留每一次的原始标题与时间 —— 展开后逐条可读，信息一条不丢。
 */
fun collapseCiRuns(list: List<Notification>): List<Notification> {
    val out = ArrayList<Notification>(list.size)
    list.forEach { n ->
        val last = out.lastOrNull()
        // ⚠️ 与上一条比较时**不能**重新解析 last.title：折叠行的标题已经被改写成汇总文案
        // （`Build workflow 连续失败 · main`），再解析必然失败 —— 那样任何一组都永远折不起来。
        // 工作流名与分支随 fold 一起存着，直接比它。
        val prev = last?.fold
        val hint = ciRunHint(n)
        if (last != null && prev != null && hint != null && sameRunGroup(last, prev, n, hint)) {
            val runs = prev.runs + NotifFoldRun(n.title, n.updatedAtMs)
            val ids = prev.ids + n.id
            out[out.lastIndex] = last.copy(
                fold = prev.copy(ids = ids, runs = runs),
                title = foldedCiTitle(prev.workflowName, prev.branch, ids.size),
            )
        } else {
            out += if (hint == null) {
                n
            } else {
                n.copy(
                    title = foldedCiTitle(hint.workflowName, hint.branch, 1),
                    fold = NotifFold(
                        ids = listOf(n.id),
                        workflowName = hint.workflowName,
                        branch = hint.branch,
                        runs = listOf(NotifFoldRun(n.title, n.updatedAtMs)),
                    ),
                )
            }
        }
    }
    return out
}

/**
 * 这一条能不能参与折叠：reason 必须是 `ci_activity`，且标题能解析出工作流名与分支。
 *
 * 解析不出来就返回 null —— 宁可多几行，也不要猜错把两次不同的运行并成一条。
 * `reason` 也要卡：任何理由都可能挂着工作流形态的标题（例如 `subscribed`），
 * 那些不是「CI 结果」，不该被折成「连续失败 N 次」。
 */
private fun ciRunHint(n: Notification): CheckSuiteHint? {
    if (n.reason != CI_ACTIVITY) return null
    return parseCheckSuiteTitle(n.title)
}

/** 相邻两条是否属于同一组折叠（同仓库 + 同类型 + 同工作流 + 同分支 + 同一天）。 */
private fun sameRunGroup(
    last: Notification,
    prev: NotifFold,
    n: Notification,
    hint: CheckSuiteHint,
): Boolean =
    last.subjectType == n.subjectType &&
        last.repoFullName == n.repoFullName &&
        prev.workflowName == hint.workflowName &&
        prev.branch == hint.branch &&
        sameDay(last.updatedAtMs, n.updatedAtMs)

/** CI 通知的 reason（GitHub 对「工作流跑完了」一律给这个）。 */
private const val CI_ACTIVITY = "ci_activity"

/** 折叠行的汇总标题（`Build workflow 连续失败 · main`）。 */
private fun foldedCiTitle(workflow: String, branch: String, count: Int): String =
    if (count > 1) "$workflow 连续失败 · $branch" else "$workflow · $branch"

/**
 * 是否同一天（本地时区）。
 *
 * 时间未知（[Notification.updatedAtMs] <= 0）时**一律判为不同天**：
 * `parseIsoMs` 解析失败返回 0，把一批「时间未知」的通知并成一组会凭空造出一个
 * 「今天连续失败 N 次」的结论 —— 与「不跨天」是同一条原则。
 */
private fun sameDay(aMs: Long, bMs: Long): Boolean {
    if (aMs <= 0L || bMs <= 0L) return false
    return localDay(aMs) == localDay(bMs)
}

/** 本地时区的「日」键（与 `collapsePushes` 的 `localDay` 同口径）。 */
private fun localDay(ms: Long): Long {
    val c = java.util.Calendar.getInstance()
    c.timeInMillis = ms
    return c.get(java.util.Calendar.YEAR) * 1000L + c.get(java.util.Calendar.DAY_OF_YEAR)
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
        reasonLabelRes = rm.labelRes,
        reasonColor = rm.color,
        reasonHighSignal = rm.highSignal,
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
enum class NotifCategory(@StringRes val labelRes: Int) {
    UNREAD(R.string.notif_category_unread),
    ALL(R.string.notif_category_all),
    PARTICIPATING(R.string.notif_category_participating),
    DONE(R.string.notif_category_done),
}

/** 时间范围（客户端过滤，基于 `updated_at`）。 */
enum class NotifRange(@StringRes val labelRes: Int, val maxAgeMs: Long?) {
    TODAY(R.string.notif_range_today, 24 * 60 * 60 * 1000L),
    THREE_DAYS(R.string.notif_range_three_days, 3 * 24 * 60 * 60 * 1000L),
    WEEK(R.string.notif_range_week, 7 * 24 * 60 * 60 * 1000L),
    ALL(R.string.notif_range_all, null),
}

/** 排序。 */
enum class NotifSort(@StringRes val labelRes: Int) {
    NEWEST(R.string.notif_sort_newest),
    OLDEST(R.string.notif_sort_oldest),
    UNREAD_FIRST(R.string.notif_sort_unread_first),
}

/** 类型筛选用到的**短名**（面板芯片与列表胶囊共用，避免同类型两种文案）。 */
/**
 * 类型短名的资源 ID；**没有对应资源时返回 null**。
 *
 * 这个 null 就是「未知类型原样透出、不吞掉后端新增类型」这条约定的载体 ——
 * 它可单测（`NotificationModelsTest` 里那条钉子），UI 侧按 null 回落到 `subjectType`。
 * 之所以不让模型层直接给文案：这里没有 Context。
 */
internal fun typeShortNameResOrNull(subjectType: String): Int? =
    typeShortNameRes(subjectType).takeIf { it != 0 }

@StringRes
fun typeShortNameRes(subjectType: String): Int = when (subjectType) {
    "PullRequest" -> R.string.notif_type_short_pr
    "Discussion" -> R.string.notif_type_short_discussion
    "Release" -> R.string.notif_type_short_release
    "Commit" -> R.string.notif_type_short_commit
    "CheckSuite", "CheckRun", "WorkflowRun" -> R.string.notif_type_short_workflow
    "RepositoryVulnerabilityAlert", "RepositoryAdvisory" -> R.string.notif_type_short_security
    // 未知类型原样透出：模型层给不出资源 ID，用 0 表示「没有对应资源」，
    // 调用方按 0 判断后回落到原始 subjectType
    else -> 0
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
enum class NotifLayout(@StringRes val labelRes: Int, @StringRes val descRes: Int) {
    FLAT(R.string.notif_layout_flat, R.string.notif_layout_flat_desc),
    GROUP_BY_REPO(R.string.notif_layout_group_by_repo, R.string.notif_layout_group_by_repo_desc),
    MERGE_BY_THREAD(R.string.notif_layout_merge_by_thread, R.string.notif_layout_merge_by_thread_desc),
    TWO_LEVEL(R.string.notif_layout_two_level, R.string.notif_layout_two_level_desc),
}

/** 读取通知显示模式（未设置默认 FLAT） */
fun readNotifLayout(context: Context): NotifLayout {
    val name = SettingsKeys.prefs(context)
        .getString(SettingsKeys.NOTIF_LAYOUT, null) ?: return NotifLayout.FLAT
    return NotifLayout.entries.firstOrNull { it.name == name } ?: NotifLayout.FLAT
}

/** 持久化通知显示模式 */
fun writeNotifLayout(context: Context, layout: NotifLayout) {
    SettingsKeys.prefs(context).edit().putString(SettingsKeys.NOTIF_LAYOUT, layout.name).apply()
}

/**
 * 通知显示模式的**运行时真源**（照 `ui/theme/ThemeRuntime.kt` 的形状）。
 *
 * ## 为什么不能各页各持一份 `remember { readNotifLayout(context) }`
 *
 * 显示模式有**两个入口**：消息页右下角面板、设置 → 通知 → 通知显示模式。
 * 两个页面各自 `remember` 一份的话，谁后改都不会通知对方：
 * 在设置页选了「平铺」，退回消息页看到的仍是进来时那份（按仓库分组）——
 * 用户看到的是「选了平铺，列表还是按仓库分组」，也就是**设置不生效**。
 * 更麻烦的是它只在「消息页还活着」时复现：TabSwitcher 重建页面后又是对的，
 * 于是变成「偶尔不生效」，最难查的那类。
 *
 * 单一真源 + `StateFlow`：两个入口都写它、都读它，改完立刻就一致（不需要重建页面）。
 * 落盘仍是 [writeNotifLayout]（prefs），所以杀进程重进也保持。
 */
object NotifLayoutRuntime {

    private val _layout = MutableStateFlow(NotifLayout.FLAT)

    /** 当前显示模式。页面用 `collectAsState()` 订阅。 */
    val layout: StateFlow<NotifLayout> = _layout.asStateFlow()

    /**
     * 启动时同步一次持久化的档位（由 `MainActivity` 调用，照 `ThemeRuntime.init` 的形状）。
     *
     * 必须在 `setContent` **之前**调：`collectAsState()` 的初始值是 [NotifLayout.FLAT]，
     * 若等页面进入再同步，存了「按仓库分组」的用户会先看到一帧平铺再跳成分组。
     * 页内不重复同步 —— 写入口只有两个，都走 [set]，内存里的值不会落后于 prefs。
     */
    fun init(context: Context) {
        _layout.value = readNotifLayout(context)
    }

    /** 改模式：内存与 prefs 一起写，两个入口共享。 */
    fun set(context: Context, layout: NotifLayout) {
        writeNotifLayout(context, layout)
        _layout.value = layout
    }
}
