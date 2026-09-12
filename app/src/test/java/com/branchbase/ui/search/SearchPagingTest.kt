package com.branchbase.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索分页纯逻辑单测。
 *
 * 分页的坑都在「只在真机上才暴露」的地方：GitHub 跨页重复项、最后一页不足 30 条、
 * 被限流时返回空页、`total_count` 虚高。这些都不是靠肉眼看界面能发现的，
 * 所以把判定抽成纯函数钉在这里。
 *
 * 另有一条产品决策也靠测试固定：**不自动翻页**（限流太紧），底部只给显式按钮。
 */
class SearchPagingTest {

    private data class Row(val owner: String, val repo: String, val sha: String)
    private val key: (Row) -> String = { "${it.owner}/${it.repo}@${it.sha}" }

    @Test
    fun `新一页按 key 去重并保留先出现的那份`() {
        val page1 = listOf(Row("a", "x", "1"), Row("a", "y", "2"))
        // 第 2 页把 a/x@1 又带回来了（按 stars / updated 排序时真实存在）
        val page2 = listOf(Row("a", "x", "1"), Row("b", "z", "3"))

        val merged = mergePage(page1, page2, key)

        assertEquals(3, merged.size)
        assertEquals(listOf("a/x@1", "a/y@2", "b/z@3"), merged.map(key))
    }

    @Test
    fun `空白页不动原列表`() {
        val page1 = listOf(Row("a", "x", "1"))
        assertEquals(page1, mergePage(page1, emptyList(), key))
        // 首页本身就是空的时候也不能炸
        assertEquals(emptyList<Row>(), mergePage(emptyList(), emptyList(), key))
    }

    @Test
    fun `整页都是重复项时列表不增长也不丢项`() {
        val page1 = listOf(Row("a", "x", "1"), Row("a", "y", "2"))
        val merged = mergePage(page1, page1, key)
        assertEquals(page1, merged)
    }

    @Test
    fun `本页拿到数据才推进页码_空页原地不动`() {
        assertEquals(2, nextPage(1, 30))
        // 最后一页不足 30 条：仍要推进，否则会重复请求同一页
        assertEquals(3, nextPage(2, 7))
        // 被限流 / 服务端返回空页：不推进，避免跳到拿不到的页码
        assertEquals(3, nextPage(3, 0))
    }

    @Test
    fun `还有下一页的判定`() {
        assertTrue(PagingState(page = 1, total = 1200, shown = 30).hasMore)
        // 已展示数追平总量 → 到底
        assertFalse(PagingState(page = 2, total = 30, shown = 30).hasMore)
        // total 虚高但列表为空 → 不显示「加载更多」（点了也只会再空一次）
        assertFalse(PagingState(page = 1, total = 999, shown = 0).hasMore)
    }

    @Test
    fun `底部按钮文案把进度说清`() {
        assertEquals("加载更多（已显示 30 / 共 1200）", loadMoreText(30, 1200))
        // 只有一页时不该出现按钮文案（页脚会走「已到底」分支）
        assertFalse(PagingState(page = 1, total = 12, shown = 12).hasMore)
    }

    @Test
    fun `翻到 1000 条上限的报错被单独判出来`() {
        val raw = "ERROR:HTTP 422 Unprocessable Entity: {\"message\":\"Only the first 1000 search results are available\"}"
        assertTrue(searchResultCapReached(raw))
        // 提示要指向「缩小范围」而不是「改语法」
        assertTrue(friendlySearchError(raw).contains("1000"))
        assertFalse(friendlySearchError(raw).contains("语法"))
        // 普通 422（筛选值写错）不能被误判成上限
        val syntax = "ERROR:HTTP 422 Unprocessable Entity: {\"message\":\"Validation Failed\"}"
        assertFalse(searchResultCapReached(syntax))
        assertFalse(searchResultCapReached(null))
    }

    @Test
    fun `不同仓库的同名 issue 不会被去重键误合并`() {
        val a = SearchTarget.Issue("a", "x", 1)
        val b = SearchTarget.Issue("b", "y", 1)
        assertEquals("Fix typo", "Fix typo") // 标题相同
        assertTrue(searchItemKey(a, "Fix typo") != searchItemKey(b, "Fix typo"))
        // 同一目标在不同页重复出现时键必须一致（否则去重失效、列表里出现重复卡片）
        assertEquals(searchItemKey(a, "Fix typo"), searchItemKey(SearchTarget.Issue("a", "x", 1), "别的标题"))
        // 拿不到目标时才退回标题
        assertEquals(searchItemKey(null, "Fix typo"), searchItemKey(null, "Fix typo"))
        assertTrue(searchItemKey(null, "Fix typo") != searchItemKey(null, "Bump version"))
    }

    @Test
    fun `计数文案在只有一页时说共多少_多页时说已显示多少`() {
        assertEquals("12 个代码结果", resultCountText(12, 12, "代码结果"))
        assertEquals("已显示前 30 条 · 共 1200 条代码结果", resultCountText(1200, 30, "代码结果"))
        // total 比已展示还小（服务端 total 抖动）也不能写出「已显示 30 · 共 12」
        assertEquals("12 个代码结果", resultCountText(12, 30, "代码结果"))
    }
}
