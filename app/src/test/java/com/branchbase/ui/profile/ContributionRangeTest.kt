package com.branchbase.ui.profile

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 贡献日历查询区间的单测。
 *
 * 这个区间**同时是缓存键的一部分**（`profileKey(login, "calendar:$from:$to")`），
 * 所以「精确到秒」等于「缓存永远不可能命中」：每进一次动态页就打一遍 GraphQL。
 * 真机日志里三次进页面拿到的键分别是 `…07:04:22Z` / `…07:04:24Z` / `…07:04:29Z` —— 就是这条。
 *
 * 因此这里钉的不是「区间算得对不对」（那只是近一年），而是**同一天内必须稳定**。
 */
class ContributionRangeTest {

    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

    @Test
    fun `同一天内多次调用必须给出同一个区间`() {
        // 这条是回归钉子：差值只有几秒，键却完全不同 —— 缓存会 100% miss
        val a = contributionRange(at("2026-09-22T07:04:22Z"))
        val b = contributionRange(at("2026-09-22T07:04:29Z"))
        val c = contributionRange(at("2026-09-22T23:59:59Z"))
        assertEquals("键的起点必须稳定", a.first, b.first)
        assertEquals("键的终点必须稳定", a.second, b.second)
        assertEquals(a, c)
    }

    @Test
    fun `区间端点落在 UTC 零点`() {
        val (from, to) = contributionRange(at("2026-09-22T07:04:29Z"))
        // 结束点取「明天 00:00Z」：否则今天那一格会被排除在区间外，墙上的「今天」永远是空的
        assertEquals("2026-09-23T00:00:00Z", to)
        assertEquals("2025-09-23T00:00:00Z", from)
        assertTrue("端点不该带非零时分秒：$to", to.endsWith("T00:00:00Z"))
        assertTrue("端点不该带非零时分秒：$from", from.endsWith("T00:00:00Z"))
    }

    @Test
    fun `跨过 UTC 零点才换键`() {
        val today = contributionRange(at("2026-09-22T23:59:59Z"))
        val tomorrow = contributionRange(at("2026-09-23T00:00:01Z"))
        assertNotEquals("跨天必须换键（数据窗口在滑动）", today.second, tomorrow.second)
    }

    @Test
    fun `区间长度是 365 天`() {
        val (from, to) = contributionRange(at("2026-09-22T07:04:29Z"))
        val days = (Instant.parse(to).toEpochMilli() - Instant.parse(from).toEpochMilli()) / (24L * 60 * 60 * 1000)
        assertEquals("近一年 = 365 天（含今天）", 365L, days)
    }
}
