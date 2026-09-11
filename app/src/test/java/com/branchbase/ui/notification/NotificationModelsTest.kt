package com.branchbase.ui.notification

import com.branchbase.cache.PrefetchPlan
import com.branchbase.cache.PrefetchReason
import com.branchbase.cache.planPrefetch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 消息页改造相关单测。
 *
 * 覆盖三处「改错了不会崩、但会静默变慢或静默失效」的地方：
 * 1. **缓存键**：预取器与页面必须拼出完全一致的 `/notifications` 路径，差一个参数就是永不命中；
 * 2. **相对时间**：快照/缓存会让解析期算好的相对时间失真，因此必须由原始时间戳在渲染期算；
 * 3. **预取策略**：计费网络 / 关闭开关时绝不能投机预取（用户流量账单是硬约束）。
 */
class NotificationModelsTest {

    // ───────────────── 类型短名（改造前既有） ─────────────────

    @Test
    fun `长名映射为短名`() {
        assertEquals("PR", typeShortName("PullRequest"))
        assertEquals("讨论", typeShortName("Discussion"))
        assertEquals("版本", typeShortName("Release"))
        assertEquals("提交", typeShortName("Commit"))
    }

    @Test
    fun `三种 CI 类型统一显示为工作流`() {
        assertEquals("工作流", typeShortName("CheckSuite"))
        assertEquals("工作流", typeShortName("CheckRun"))
        assertEquals("工作流", typeShortName("WorkflowRun"))
    }

    @Test
    fun `两种安全警报统一显示为安全`() {
        assertEquals("安全", typeShortName("RepositoryVulnerabilityAlert"))
        assertEquals("安全", typeShortName("RepositoryAdvisory"))
    }

    @Test
    fun `Issue 本身够短_保持原样`() {
        assertEquals("Issue", typeShortName("Issue"))
    }

    @Test
    fun `未知类型原样返回_不吞掉新类型`() {
        assertEquals("SomethingNew", typeShortName("SomethingNew"))
        assertEquals("", typeShortName(""))
    }

    // ───────────────── 查询串（预取器与页面共用） ─────────────────

    @Test
    fun `首屏路径固定为 per_page 50 且 all=true`() {
        assertEquals("/notifications?per_page=50&all=true", notifListPath())
    }

    @Test
    fun `参与筛选与游标参数按约定拼接`() {
        assertEquals("/notifications?per_page=50&all=true&participating=true", notifListPath(participating = true))
        // before 里的时间戳含 `+` 与 `:`，必须 URL 编码，否则分页会拿到错误页
        val path = notifListPath(before = "2026-09-11T10:00:00+08:00")
        assertTrue(path.startsWith("/notifications?per_page=50&all=true&before="))
        assertTrue(path.contains("%3A"))
    }

    // ───────────────── 解析 ─────────────────

    @Test
    fun `解析通知时保留原始时间戳`() {
        val json = """
            [{"id":"1","unread":true,"reason":"mention","updated_at":"2026-09-11T00:00:00Z",
              "subject":{"type":"Issue","title":"标题","url":"https://api.github.com/repos/a/b/issues/42"},
              "repository":{"full_name":"a/b"}}]
        """.trimIndent()
        val list = parseNotifications(json)
        assertEquals(1, list.size)
        val n = list[0]
        assertEquals("a/b", n.repoFullName)
        assertEquals("a", n.owner)
        assertEquals("b", n.repo)
        assertEquals(42L, n.targetNumber)
        assertTrue(n.issueLike)
        assertEquals(parseIsoMs("2026-09-11T00:00:00Z"), n.updatedAtMs)
        assertTrue(n.updatedAtMs > 0)
    }

    @Test
    fun `相对时间由时间戳在渲染期计算`() {
        val now = 1_700_000_000_000L
        assertEquals("刚刚", relativeTimeOf(now - 30_000, now))
        assertEquals("5 分钟前", relativeTimeOf(now - 5 * 60_000, now))
        assertEquals("3 小时前", relativeTimeOf(now - 3 * 3_600_000, now))
        assertEquals("昨天", relativeTimeOf(now - 26 * 3_600_000, now))
        assertEquals("2 天前", relativeTimeOf(now - 50 * 3_600_000, now))
        // 时间戳缺失时不显示「1970 年」这类噪声
        assertEquals("", relativeTimeOf(0, now))
    }

    @Test
    fun `通知构造器派生字段一致`() {
        val n = notificationOf(
            id = "t1", unread = true, reason = "review_requested", subjectType = "PullRequest",
            title = "标题", url = "https://api.github.com/repos/a/b/pulls/9",
            latestCommentUrl = null, repoFullName = "a/b", updatedAtMs = 1L,
        )
        assertEquals(9L, n.targetNumber)
        assertEquals("请求你审查", n.reasonLabel)
        assertTrue(n.issueLike)
        assertTrue(n.copy(unread = false).unread.not())
    }

    // ───────────────── 预取策略 ─────────────────

    @Test
    fun `首页场景在非计费网络且用户开启预加载时预取通知`() {
        val plan: PrefetchPlan = planPrefetch(PrefetchReason.AppStart, metered = false, userOptIn = true, overviewFresh = true)
        assertTrue("非计费 + 已开启 → 应预取消息首屏", plan.notifications)
    }

    @Test
    fun `计费网络绝不投机预取通知`() {
        val plan = planPrefetch(PrefetchReason.AppStart, metered = true, userOptIn = true, overviewFresh = true)
        assertFalse("移动数据下不应预取", plan.notifications)
    }

    @Test
    fun `用户关闭预加载开关后不预取通知`() {
        val plan = planPrefetch(PrefetchReason.AppStart, metered = false, userOptIn = false, overviewFresh = true)
        assertFalse(plan.notifications)
    }

    @Test
    fun `打开仓库的场景不预取通知`() {
        val plan = planPrefetch(PrefetchReason.OpenRepo, metered = false, userOptIn = true, overviewFresh = false)
        assertFalse(plan.notifications)
        assertTrue("仓库信息该预取还是要预取", plan.overview)
    }

    @Test
    fun `归档条目可以还原成与网络同构的行`() {
        val entry = ArchivedThread(
            id = "t9", title = "标题", repoFullName = "a/b", number = 7L,
            subjectType = "PullRequest", reason = "review_requested",
            updatedAtMs = 123L, state = ArchivedThread.STATE_DONE, archivedAtMs = 456L,
        )
        val n = entry.toNotification()
        assertEquals("t9", n.id)
        assertEquals("a", n.owner)
        assertEquals("b", n.repo)
        assertEquals(7L, n.targetNumber)
        assertFalse("归档行必须是已读", n.unread)
        assertTrue(n.issueLike)
        // 派生字段与网络解析一致（同一套派生规则）
        assertEquals("请求你审查", n.reasonLabel)
    }

    @Test
    fun `未读计数只统计未读项`() {
        val list = listOf(
            notificationOf("1", true, "mention", "Issue", "a", "", null, "a/b", 1L),
            notificationOf("2", false, "comment", "Issue", "b", "", null, "a/b", 2L),
        )
        assertEquals(1, list.count { it.unread })
        assertNull(list.first { it.id == "2" }.targetNumber)
    }
}
