package com.branchbase.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预加载策略与缓存键单测。
 *
 * 这些规则决定「会不会多花用户流量」与「预取的数据能不能被页面命中」，
 * 都是纯函数，必须锁住行为。
 */
class PrefetchPolicyTest {

    // ── 决策 ──

    @Test
    fun `点开仓库时即使计费网络也预取项目页数据`() {
        // 预取的是用户马上要看的页面 —— 不算额外流量，只是提前
        val plan = planPrefetch(
            reason = PrefetchReason.OpenRepo,
            metered = true,
            userOptIn = true,
            overviewFresh = false,
        )
        assertTrue(plan.overview)
        assertFalse(plan.tabs)
        assertFalse(plan.listWarm)
    }

    @Test
    fun `计费网络下不做投机性预取`() {
        listOf(PrefetchReason.OpenRepo, PrefetchReason.EnterRepo).forEach { reason ->
            val plan = planPrefetch(reason, metered = true, userOptIn = true, overviewFresh = true)
            assertFalse("$reason 不应预取 tab", plan.tabs)
            assertTrue("$reason 已新鲜则连 overview 也不取", plan.isEmpty)
        }
    }

    @Test
    fun `非计费网络且用户开启时预取其他 tab`() {
        val plan = planPrefetch(PrefetchReason.EnterRepo, metered = false, userOptIn = true, overviewFresh = false)
        assertTrue(plan.overview)
        assertTrue(plan.tabs)
    }

    @Test
    fun `用户关闭预加载后只剩必要的项目页预取`() {
        val plan = planPrefetch(PrefetchReason.EnterRepo, metered = false, userOptIn = false, overviewFresh = false)
        assertTrue(plan.overview)
        assertFalse(plan.tabs)
        assertFalse(plan.listWarm)
    }

    @Test
    fun `项目页数据新鲜时不再重复预取它`() {
        // overviewFresh 只约束「项目页四件套」；其他 tab 是另一批键，仍按投机策略决定
        listOf(PrefetchReason.OpenRepo, PrefetchReason.EnterRepo).forEach { reason ->
            val plan = planPrefetch(reason, metered = false, userOptIn = true, overviewFresh = true)
            assertFalse("$reason 项目页已新鲜，不应重复预取", plan.overview)
            assertTrue("$reason 其他 tab 仍可投机预取", plan.tabs)
            assertFalse(plan.listWarm)
        }
        // 关掉投机预取后就彻底没有动作
        listOf(PrefetchReason.OpenRepo, PrefetchReason.EnterRepo).forEach { reason ->
            val plan = planPrefetch(reason, metered = false, userOptIn = false, overviewFresh = true)
            assertTrue("$reason 应无动作", plan.isEmpty)
        }
    }

    @Test
    fun `列表可见与启动只做列表预热`() {
        listOf(PrefetchReason.ListVisible, PrefetchReason.AppStart).forEach { reason ->
            val plan = planPrefetch(reason, metered = false, userOptIn = true, overviewFresh = false)
            assertFalse(plan.overview)
            assertFalse(plan.tabs)
            assertTrue(plan.listWarm)
        }
    }

    @Test
    fun `列表预热有上限`() {
        assertEquals(5, PREFETCH_LIST_LIMIT)
    }

    // ── 缓存键 ──

    @Test
    fun `预加载存储库的键唯一且带类型前缀`() {
        val o = "SunsetRNE"
        val r = "Branchbase"
        assertEquals("repo-info:$o/$r", PreloadStore.infoKey(o, r))
        assertEquals("repo-lang:$o/$r", PreloadStore.langKey(o, r))
        assertEquals("repo-contrib:$o/$r", PreloadStore.contribKey(o, r))
        assertEquals("$o/$r", PreloadStore.branchKey(o, r))
        // README 键必须带分支（历史上漏拼分支导致缓存永不命中）
        assertEquals("$o/$r@main", PreloadStore.readmeKey(o, r, "main"))
        assertEquals("$o/$r@feature/x", PreloadStore.readmeKey(o, r, "feature/x"))
    }

    @Test
    fun `列表缓存键包含页面与参数`() {
        val o = "SunsetRNE"
        val r = "Branchbase"
        assertEquals("repo-list:issues:$o/$r", ListCache.key(o, r, ListCache.PAGE_ISSUES))
        assertEquals(
            "repo-list:pulls:$o/$r:main",
            ListCache.key(o, r, ListCache.PAGE_PULLS, "main"),
        )
        // 代码树：目录与分支都参与键；分支为 null 用空串占位（与预加载器写入的键一致）
        assertEquals("docs|", ListCache.codeParams("docs", null))
        assertEquals("docs|main", ListCache.codeParams("docs", "main"))
        assertEquals(
            "repo-list:code:$o/$r:|main",
            ListCache.key(o, r, ListCache.PAGE_CODE, ListCache.codeParams("", "main")),
        )
    }

    @Test
    fun `资源包可用性与就绪计数`() {
        assertFalse(PreloadStore.RepoBundle.EMPTY.usable)
        assertEquals(0, PreloadStore.RepoBundle.EMPTY.readyCount)

        val partial = PreloadStore.RepoBundle(info = "{}", readme = "<p/>", languages = null, contributors = null)
        assertTrue(partial.usable)
        assertEquals(2, partial.readyCount)

        // 没有仓库信息就算不上可用（头部画不出来）
        val noInfo = PreloadStore.RepoBundle(null, "<p/>", "{}", "[]")
        assertFalse(noInfo.usable)
        assertEquals(3, noInfo.readyCount)
    }

    @Test
    fun `默认分支解析容错`() {
        assertEquals("develop", defaultBranchOf("""{"default_branch":"develop"}"""))
        assertNull(defaultBranchOf("""{"default_branch":""}"""))
        assertNull(defaultBranchOf("{}"))
        assertNull(defaultBranchOf(null))
        assertNull(defaultBranchOf("{ 不是 JSON"))
    }
}
