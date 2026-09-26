package com.branchbase.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「常用仓库」的**数据源**钉子。
 *
 * 这一栏有两个源，选谁只由「有没有置顶」决定（[FrequentRepoSource]）：置顶过 → 「我能用的仓库」
 * （[MY_REPOS_PATH]），没置顶过或取消全部 → 收藏（星标）仓库最近 [FrequentRepoRules.HOME_LIMIT] 个
 * （[STARRED_RECENT_PATH]）。所以这里钉三件事：
 * 1. 查询串必须显式写出三类 `affiliation`（= 需求原话的四类仓库）并按最近推送排序 ——
 *    漏掉 `affiliation` 今天也能跑（GitHub 默认值恰好是这三个），但那是「靠默认值过日子」，
 *    默认值一变，用户的仓库就会静默变少，没有任何报错；
 * 2. 收藏仓库那一页必须由服务端按最近星标倒序 —— 「最近 5 个」这个语义全在这个顺序上；
 * 3. 同一个查询的响应形状必须能被首页与管理页共用的 [parseRepoList] 解析 ——
 *    页面上那几个字段（描述 / 语言 / 星标 / 复刻 / 私有）在这两份响应里同样存在，只是可能为 `null`。
 */
class MyReposSourceTest {

    @Test
    fun `查询串显式覆盖自己持有的-有权限的-组织与团队的仓库`() {
        assertTrue(
            "affiliation 必须显式写全三个值：owner=自己持有，collaborator=协作/有权限，organization_member=所属组织与团队",
            MY_REPOS_PATH.contains("affiliation=owner,collaborator,organization_member"),
        )
    }

    @Test
    fun `排序必须交给服务端`() {
        // per_page=100 只取第一页：客户端排序会变成「先按仓库名取一页再排序」，
        // 名字靠后的活跃仓库进不了这一页，首页那 5 个就会挑错人。
        assertTrue(MY_REPOS_PATH.contains("per_page=100"))
        assertTrue(MY_REPOS_PATH.contains("sort=pushed"))
        assertTrue(MY_REPOS_PATH.contains("direction=desc"))
    }

    @Test
    fun `没置顶过时读收藏仓库、置顶过时读我能用的仓库`() {
        // 需求原话：「若设置常用仓库，则不显示收藏仓库，只显示常用仓库，
        // 除非取消所有常用仓库的选择」→ 缺键与空数组都算「没置顶过」。
        assertEquals(STARRED_RECENT_PATH, FrequentRepoSource.pathFor(null))
        assertEquals(STARRED_RECENT_PATH, FrequentRepoSource.pathFor(emptyList()))
        assertEquals(MY_REPOS_PATH, FrequentRepoSource.pathFor(listOf("SunsetRNE/Branchbase-Android")))

        assertEquals(STARRED_RECENT_CACHE_KEY, FrequentRepoSource.cacheKeyFor(null))
        assertEquals(STARRED_RECENT_CACHE_KEY, FrequentRepoSource.cacheKeyFor(emptyList()))
        assertEquals(MY_REPOS_CACHE_KEY, FrequentRepoSource.cacheKeyFor(listOf("a/b")))

        // 两个源必须各有一份缓存：共用键会让切源后的第一帧拿另一份 JSON 渲染
        assertNotEquals(STARRED_RECENT_CACHE_KEY, MY_REPOS_CACHE_KEY)
    }

    @Test
    fun `收藏仓库按最近星标取一页`() {
        // 「最近最新 5 个」完全依赖服务端排序：per_page=100 只取第一页，
        // 客户端排序会变成「先随便取一页再排」，而默认值将来一变就会静默取错。
        assertTrue(STARRED_RECENT_PATH.startsWith("/user/starred"))
        assertTrue(STARRED_RECENT_PATH.contains("per_page=100"))
        assertTrue(STARRED_RECENT_PATH.contains("sort=created"))
        assertTrue(STARRED_RECENT_PATH.contains("direction=desc"))
    }

    @Test
    fun `解析 user-repos 响应里的私有仓库与空描述`() {
        val json = """
            [
              {"full_name":"SunsetRNE/Branchbase-Android","description":null,"language":null,
               "stargazers_count":13,"forks_count":1,"private":true,"fork":false,
               "pushed_at":"2026-09-26T15:00:00Z"},
              {"full_name":"ORG/Data","description":"内部数据管道","language":"Go",
               "stargazers_count":2500,"forks_count":0,"private":false,"fork":true,
               "pushed_at":"2026-08-01T09:30:00Z"}
            ]
        """.trimIndent()

        val repos = parseRepoList(json)

        assertEquals(2, repos.size)
        assertEquals("SunsetRNE/Branchbase-Android", repos[0].fullName)
        assertEquals("", repos[0].desc)
        assertEquals(null, repos[0].language)
        assertEquals("13", repos[0].stars)
        assertEquals("1", repos[0].forks)
        assertEquals("ORG/Data", repos[1].fullName)
        assertEquals("内部数据管道", repos[1].desc)
        assertEquals("Go", repos[1].language)
        assertEquals("2.5k", repos[1].stars)

        // 徽标（私有 / 复刻）的两个字段：两份响应（/user/repos 与 /user/starred）都带
        assertTrue(repos[0].isPrivate)
        assertEquals(false, repos[0].isFork)
        assertEquals(false, repos[1].isPrivate)
        assertTrue(repos[1].isFork)
    }

    @Test
    fun `缺 private-fork 字段时徽标不亮而不是抛异常`() {
        // 只给最小字段的响应（老版本缓存 / 精简接口）也必须解析得出来
        val repos = parseRepoList("""[{"full_name":"a/b","stargazers_count":0}]""")
        assertEquals(1, repos.size)
        assertEquals(false, repos[0].isPrivate)
        assertEquals(false, repos[0].isFork)
    }

    @Test
    fun `空列表与坏 JSON 都退化成空候选集而不是抛异常`() {
        assertEquals(emptyList<RepoSummary>(), parseRepoList("[]"))
        assertEquals(emptyList<RepoSummary>(), parseRepoList("<html>502 Bad Gateway</html>"))
    }
}
