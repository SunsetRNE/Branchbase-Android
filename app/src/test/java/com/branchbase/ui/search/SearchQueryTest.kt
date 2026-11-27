package com.branchbase.ui.search

import com.branchbase.R
import com.branchbase.ui.LocalizedText

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    /** 测试里的默认矩阵：类型 + 原文 + 无语言 / 无高级 / 无排序。 */
    private fun key(
        type: SearchType,
        query: String,
        sortKey: String = "",
        login: String = "alice",
        language: String? = null,
        advanced: Map<String, String> = emptyMap(),
    ) = searchCacheKey(type, query, language, advanced, filters, sortKey, login)

    @Test
    fun `查询串拼上语言_高级筛选与类型限定`() {
        val q = buildSearchQuery(
            query = "  compose  ",
            type = SearchType.PULLS,
            language = "kotlin",
            advanced = mapOf("星标数" to ">100"),
            advancedFilters = filters,
        )
        assertEquals("compose language:kotlin stars:>100 type:pr", q)
    }

    @Test
    fun `议题与拉取请求复用同一接口但用 type 限定区分`() {
        assertTrue(buildSearchQuery("x", SearchType.ISSUES, null, emptyMap(), filters).endsWith("type:issue"))
        assertTrue(buildSearchQuery("x", SearchType.PULLS, null, emptyMap(), filters).endsWith("type:pr"))
        // 其它类型不加限定符（加上会搜不出东西）
        assertFalse(buildSearchQuery("x", SearchType.REPOS, null, emptyMap(), filters).contains("type:"))
    }

    @Test
    fun `空的语言与筛选值不拼进查询串`() {
        val q = buildSearchQuery("x", SearchType.REPOS, "", mapOf("星标数" to "", "主题" to "android"), filters)
        assertEquals("x topic:android", q)
    }

    @Test
    fun `未知筛选名不会拼出半截语法`() {
        // 表里没有的筛选项（比如老版本残留的键）必须整个跳过，否则会拼出半截语法
        val q = buildSearchQuery("x", SearchType.REPOS, null, mapOf("已删除的筛选项" to ">1"), filters)
        assertEquals("x", q)
    }

    @Test
    fun `缓存键必须绑定账号`() {
        val a = key(SearchType.REPOS, "foo", login = "alice")
        val b = key(SearchType.REPOS, "foo", login = "bob")
        // 搜索结果含私有仓库/私有代码：同一查询在不同账号下必须各存一份
        assertNotEquals(a, b)
        assertTrue(a.contains("alice"))
    }

    @Test
    fun `只有仓库类型带排序键`() {
        assertTrue(key(SearchType.REPOS, "foo", "stars").endsWith("stars"))
        // 其余类型不支持排序：带上会让 sortKey 残留污染缓存键
        assertFalse(key(SearchType.USERS, "foo", "stars").contains("stars"))
    }

    @Test
    fun `改语言或高级筛选必须换一条缓存键`() {
        // 1.4.92 的真机缺陷：键里只有用户原文，改语言/筛选后算出来还是同一条键，
        // SearchCacheManager 命中旧行直出旧结果 —— 表现就是「点完成没重搜」。
        val base = key(SearchType.REPOS, "foo")
        val lang = key(SearchType.REPOS, "foo", language = "kotlin")
        val advanced = key(SearchType.REPOS, "foo", advanced = mapOf("星标数" to ">100"))
        assertNotEquals(base, lang)
        assertNotEquals(base, advanced)
        assertNotEquals(lang, advanced)
        // 生效查询串进键：语言限定符必须能在键里看到（否则只是换了个随机种子）
        assertTrue(lang.contains("language:kotlin"))
        // 类型限定符同样在生效查询串里：议题 / PR 共用接口但不能互相命中
        assertNotEquals(key(SearchType.ISSUES, "foo"), key(SearchType.PULLS, "foo"))
    }

    @Test
    fun `缓存键用稳定英文键而不是展示文案`() {
        // 缓存是落库的：键里若混进展示文案，切一次界面语言就等于换了一套键，
        // 旧行全成孤儿（读不到、也不会被 TTL 之外的逻辑清掉）。
        val key = key(SearchType.PULLS, "foo")
        assertTrue("缓存键里出现了展示文案", key.contains(SearchType.PULLS.cacheKey))
        SearchType.entries.forEach { t ->
            assertFalse(
                "SearchType.${t.name} 的 cacheKey 混进了非 ASCII 字符：${t.cacheKey}",
                t.cacheKey.any { it.code > 127 },
            )
        }
    }

    @Test
    fun `限流与语法错误分别给出可行动的提示`() {
        // 钉资源 ID（文案会搬家）：限流 / 语法 / 登录失效 / 无响应各是一条独立提示，
        // 认错任何一个都会让用户按错误的办法行动（白改筛选 / 白等一分钟）。
        val limited = friendlySearchError(
            """ERROR:HTTP 403 Forbidden: {"message":"API rate limit exceeded for user ID 1"}""",
        )
        assertEquals(R.string.search_error_rate_limited, limited.res)

        val invalid = friendlySearchError("""ERROR:HTTP 422 Unprocessable Entity: {"message":"Validation Failed"}""")
        assertEquals(R.string.search_error_validation, invalid.res)

        assertEquals(
            R.string.search_error_unauthorized,
            friendlySearchError("ERROR:HTTP 401 Unauthorized").res,
        )
        // 拿不到任何细节时也要说清是「无响应」，而不是含糊的「失败」
        assertEquals(R.string.search_error_no_response, friendlySearchError(null).res)

        // 有原始报文时透出截断后的原文（不吞掉后端信息）
        val raw = friendlySearchError("ERROR:boom")
        assertEquals(R.string.search_error_raw, raw.res)
        assertEquals(listOf("ERROR:boom"), raw.args)
    }

    @Test
    fun `结果计数说清只显示第一页`() {
        // 只显示 30 条却写「共 1200 个结果」，用户会一直下拉找剩下的 —— 用两条资源分开表达
        val partial = resultCountText(total = 1200, shown = 30, unitRes = R.string.label_results)
        assertEquals(R.string.result_count_partial, partial.res)
        assertEquals(30, partial.args[0])
        assertEquals(1200L, partial.args[1])
        assertEquals(R.string.label_results, (partial.args[2] as LocalizedText).res)
        // 一页装得下时不必画蛇添足
        val all = resultCountText(total = 12, shown = 30, unitRes = R.string.label_results)
        assertEquals(R.string.result_count_all, all.res)
        assertEquals(12L, all.args[0])
        assertEquals(R.string.label_results, (all.args[1] as LocalizedText).res)
    }
}
