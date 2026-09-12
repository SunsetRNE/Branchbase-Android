package com.branchbase.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索链路纯逻辑单测。
 *
 * 这三个函数各自对应一个「用户频繁遇到」的问题，且都只有真机走完整流程才试得出来：
 * 查询组装（搜出别的语言/类型）、缓存键（跨账号串私有结果）、错误分级（限流被当成网络错误）。
 */
class SearchQueryTest {

    private val filters = listOf("星标数" to "stars:", "主题" to "topic:")

    @Test
    fun `查询串拼上语言_高级筛选与类型限定`() {
        val q = buildSearchQuery(
            query = "  compose  ",
            type = "拉取请求",
            language = "kotlin",
            advanced = mapOf("星标数" to ">100"),
            advancedFilters = filters,
        )
        assertEquals("compose language:kotlin stars:>100 type:pr", q)
    }

    @Test
    fun `议题与拉取请求复用同一接口但用 type 限定区分`() {
        assertTrue(buildSearchQuery("x", "议题", null, emptyMap(), filters).endsWith("type:issue"))
        assertTrue(buildSearchQuery("x", "拉取请求", null, emptyMap(), filters).endsWith("type:pr"))
        // 其它类型不加限定符（加上会搜不出东西）
        assertFalse(buildSearchQuery("x", "仓库", null, emptyMap(), filters).contains("type:"))
    }

    @Test
    fun `空的语言与筛选值不拼进查询串`() {
        val q = buildSearchQuery("x", "仓库", "", mapOf("星标数" to "", "主题" to "android"), filters)
        assertEquals("x topic:android", q)
    }

    @Test
    fun `未知筛选名不会拼出半截语法`() {
        // 表里没有的筛选项（比如老版本残留的键）必须整个跳过，否则会拼出半截语法
        val q = buildSearchQuery("x", "仓库", null, mapOf("已删除的筛选项" to ">1"), filters)
        assertEquals("x", q)
    }

    @Test
    fun `缓存键必须绑定账号`() {
        val a = searchCacheKey("仓库", "foo", "", login = "alice")
        val b = searchCacheKey("仓库", "foo", "", login = "bob")
        // 搜索结果含私有仓库/私有代码：同一查询在不同账号下必须各存一份
        assertFalse(a == b)
        assertTrue(a.contains("alice"))
    }

    @Test
    fun `只有仓库类型带排序键`() {
        assertTrue(searchCacheKey("仓库", "foo", "stars", "alice").endsWith("stars"))
        // 其余类型不支持排序：带上会让 sortKey 残留污染缓存键
        assertFalse(searchCacheKey("用户", "foo", "stars", "alice").contains("stars"))
    }

    @Test
    fun `限流与语法错误分别给出可行动的提示`() {
        val limited = friendlySearchError(
            """ERROR:HTTP 403 Forbidden: {"message":"API rate limit exceeded for user ID 1"}""",
        )
        assertTrue(limited.contains("限流"))
        assertTrue(limited.contains("1 分钟"))

        val invalid = friendlySearchError("""ERROR:HTTP 422 Unprocessable Entity: {"message":"Validation Failed"}""")
        assertTrue(invalid.contains("筛选"))

        assertTrue(friendlySearchError("ERROR:HTTP 401 Unauthorized").contains("重新登录"))
        // 拿不到任何细节时也要说清是「无响应」，而不是含糊的「失败」
        assertTrue(friendlySearchError(null).contains("无响应"))
    }

    @Test
    fun `结果计数说清只显示第一页`() {
        // 只显示 30 条却写「共 1200 个结果」，用户会一直下拉找剩下的
        val text = resultCountText(total = 1200, shown = 30, unit = "结果")
        assertTrue(text.contains("已显示前 30"))
        assertTrue(text.contains("1200"))
        // 一页装得下时不必画蛇添足
        assertEquals("12 个结果", resultCountText(total = 12, shown = 30, unit = "结果"))
    }
}
