package com.branchbase.ui.profile

import org.json.JSONObject

/**
 * 贡献日历数据（GraphQL `contributionsCollection.contributionCalendar`）。
 *
 * 对齐 `design/contribution-wall-prototype.html`：
 * REST 的 `/users/{login}/events` 只有近 90 天、最多 300 条且仅公开事件，
 * 画不出 52 周；GraphQL 一次即可拿到 `weeks[].contributionDays[]`。
 */

/** 单日贡献。 */
data class ContributionDay(
    val date: String,
    val count: Int,
    /** GitHub 返回的色值（如 `#ebedf0`），缺失时回退本地色阶。 */
    val color: String?,
)

/** 贡献日历。 */
data class ContributionCalendar(
    val total: Int,
    val commitCount: Int,
    val issueCount: Int,
    val prCount: Int,
    val reviewCount: Int,
    /** 每周一组（GitHub 按周日→周六成列，首尾周可能不足 7 天）。 */
    val weeks: List<List<ContributionDay>>,
) {
    val days: List<ContributionDay> get() = weeks.flatten()

    /** 单日最大贡献数（用于色阶分级）。 */
    val maxCount: Int get() = days.maxOfOrNull { it.count } ?: 0

    /** 有贡献的天数。 */
    val activeDays: Int get() = days.count { it.count > 0 }
}

/**
 * 解析 GraphQL 响应（`data` 部分）为 [ContributionCalendar]。
 *
 * 期望结构：
 * ```json
 * { "user": { "contributionsCollection": {
 *     "totalCommitContributions": 1, "contributionCalendar": {
 *       "totalContributions": 2,
 *       "weeks": [ { "contributionDays": [ {"date":"…","contributionCount":3,"color":"#ebedf0"} ] } ] } } } }
 * ```
 * 失败（网络错误串 / 结构不符）返回 null，由调用方决定降级。
 */
fun parseContributionCalendar(json: String?): ContributionCalendar? {
    if (json.isNullOrBlank() || json.startsWith("ERROR:")) return null
    return runCatching {
        val coll = JSONObject(json)
            .optJSONObject("user")
            ?.optJSONObject("contributionsCollection")
            ?: return null
        val cal = coll.optJSONObject("contributionCalendar") ?: return null
        val weeksArr = cal.optJSONArray("weeks") ?: return null

        val weeks = ArrayList<List<ContributionDay>>(weeksArr.length())
        for (i in 0 until weeksArr.length()) {
            val weekObj = weeksArr.optJSONObject(i) ?: continue
            val daysArr = weekObj.optJSONArray("contributionDays") ?: continue
            val days = ArrayList<ContributionDay>(daysArr.length())
            for (j in 0 until daysArr.length()) {
                val d = daysArr.optJSONObject(j) ?: continue
                days += ContributionDay(
                    date = d.optString("date"),
                    count = d.optInt("contributionCount"),
                    color = d.optString("color").takeIf { it.isNotBlank() },
                )
            }
            if (days.isNotEmpty()) weeks += days
        }

        ContributionCalendar(
            total = cal.optInt("totalContributions"),
            commitCount = coll.optInt("totalCommitContributions"),
            issueCount = coll.optInt("totalIssueContributions"),
            prCount = coll.optInt("totalPullRequestContributions"),
            reviewCount = coll.optInt("totalPullRequestReviewContributions"),
            weeks = weeks,
        )
    }.getOrNull()
}

/**
 * 由事件流构造近 [weeks] 周的近似日历（GraphQL 不可用时的降级路径）。
 *
 * 只统计公开事件，因此调用方必须在 UI 上标注数据范围，不能伪装成完整贡献墙。
 */
internal fun fallbackCalendarFromEvents(events: List<ActivityEvent>, weeks: Int = 13): ContributionCalendar {
    val dayMs = 24L * 60 * 60 * 1000
    val todayStart = System.currentTimeMillis() / dayMs * dayMs
    val counts = LinkedHashMap<String, Int>()
    events.forEach { e ->
        if (e.createdAt <= 0) return@forEach
        val d = java.util.Date(e.createdAt / dayMs * dayMs)
        val key = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(d)
        counts[key] = (counts[key] ?: 0) + 1
    }

    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
    // 起始日对齐到周日（与 GitHub 网格一致）
    val start = todayStart - (weeks * 7 - 1) * dayMs
    val calStart = java.util.Calendar.getInstance()
    calStart.timeInMillis = start
    val shift = calStart.get(java.util.Calendar.DAY_OF_WEEK) - java.util.Calendar.SUNDAY
    val alignedStart = start - shift * dayMs

    val out = ArrayList<List<ContributionDay>>(weeks)
    for (w in 0 until weeks) {
        val week = ArrayList<ContributionDay>(7)
        for (d in 0 until 7) {
            val t = alignedStart + (w * 7 + d) * dayMs
            val key = fmt.format(java.util.Date(t))
            week += ContributionDay(date = key, count = counts[key] ?: 0, color = null)
        }
        out += week
    }
    val total = counts.values.sum()
    return ContributionCalendar(
        total = total,
        commitCount = events.count { it.type == "PushEvent" },
        issueCount = events.count { it.type == "IssuesEvent" || it.type == "IssueCommentEvent" },
        prCount = events.count { it.type.startsWith("PullRequest") },
        reviewCount = 0,
        weeks = out,
    )
}
