package com.branchbase.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缓存契约单测：键的唯一性 + 类型 TTL 必须显式登记。
 *
 * 这两件事出错都不会崩溃，只会「静默变慢」或「静默串数据」，所以必须用测试锁住：
 * - 键少带参数（曾发生：README key 漏拼分支）→ 永远命中不到，每次都联网；
 * - 类型字符串打错 → 落到 `ttlFor` 的默认 30 分钟，缓存语义悄悄变化。
 */
class PageCacheTest {

    private val o = "SunsetRNE"
    private val r = "Branchbase"

    @Test
    fun `详情页键互不冲突且带参数`() {
        val keys = listOf(
            PageCache.issueKey(o, r, 1),
            PageCache.issueCommentsKey(o, r, 1),
            PageCache.pullKey(o, r, 1),
            PageCache.pullFilesKey(o, r, 1),
            PageCache.commitKey(o, r, "abc123"),
            PageCache.releaseKey(o, r, "v1.0.13"),
            PageCache.runKey(o, r, 1),
            PageCache.runJobsKey(o, r, 1),
            PageCache.runArtifactsKey(o, r, 1),
            PageCache.jobLogKey(o, r, 1),
            PageCache.runsKey(o, r, 1, "main"),
            PageCache.fileKey(o, r, "src/a.kt", "main"),
            PageCache.branchListKey(o, r),
            PageCache.compareKey(o, r, "main", "feat"),
            PageCache.workflowFileKey(o, r, ".github/workflows/ci.yml"),
            PageCache.notificationKey("/notifications?all=false"),
            PageCache.homeKey("unread"),
            PageCache.profileKey("SunsetRNE", "calendar:2026-01-01:2026-12-31"),
        )
        assertEquals("键必须两两不同", keys.size, keys.toSet().size)
    }

    @Test
    fun `键必须包含区分参数`() {
        // Issue 1 与 Issue 2 不能共用键
        assertNotEquals(PageCache.issueKey(o, r, 1), PageCache.issueKey(o, r, 2))
        // 对比结果必须带 base 与 head
        assertNotEquals(PageCache.compareKey(o, r, "main", "feat"), PageCache.compareKey(o, r, "feat", "main"))
        assertNotEquals(PageCache.compareKey(o, r, "main", "feat"), PageCache.compareKey(o, r, "main", "dev"))
        // 同一路径不同 ref 是不同内容
        assertNotEquals(
            PageCache.fileKey(o, r, "src/a.kt", "main"),
            PageCache.fileKey(o, r, "src/a.kt", "dev"),
        )
        // 运行历史受分支筛选影响
        assertNotEquals(PageCache.runsKey(o, r, 1, "main"), PageCache.runsKey(o, r, 1, "dev"))
        // 不同仓库不共用
        assertNotEquals(PageCache.issueKey(o, r, 1), PageCache.issueKey("other", r, 1))
    }

    @Test
    fun `键带页面前缀 避免跨页覆盖`() {
        assertTrue(PageCache.issueKey(o, r, 1).startsWith("detail:issue:"))
        assertTrue(PageCache.fileKey(o, r, "a", "main").startsWith("file:"))
        assertTrue(PageCache.compareKey(o, r, "a", "b").startsWith("compare:"))
        assertTrue(PageCache.workflowFileKey(o, r, "p").startsWith("wf-file:"))
        assertTrue(PageCache.notificationKey("/x").startsWith("notifications:"))
        // 与列表页/预加载存储库的键空间不重叠
        assertTrue(PageCache.branchListKey(o, r).startsWith("branch-list:"))
        assertEquals("$o/$r", PreloadStore.branchKey(o, r))
        assertNotEquals(PreloadStore.branchKey(o, r), PageCache.branchListKey(o, r))
    }

    @Test
    fun `每个缓存类型都有显式 TTL`() {
        // 打错类型字符串会落到 else 默认值（30 分钟），这里逐个锁死期望值
        assertEquals(5 * 60 * 1000L, SearchCacheManager.ttlFor(PageCache.TYPE_DETAIL))
        assertEquals(10 * 60 * 1000L, SearchCacheManager.ttlFor(PageCache.TYPE_FILE))
        assertEquals(2 * 60 * 1000L, SearchCacheManager.ttlFor(PageCache.TYPE_NOTIFICATION))
        assertEquals(10 * 60 * 1000L, SearchCacheManager.ttlFor(PageCache.TYPE_PROFILE))
        assertEquals(5 * 60 * 1000L, SearchCacheManager.ttlFor(PageCache.TYPE_HOME))

        assertEquals(5 * 60 * 1000L, SearchCacheManager.ttlFor(ListCache.TYPE))
        assertEquals(15 * 60 * 1000L, SearchCacheManager.ttlFor(PreloadStore.TYPE_INFO))
        assertEquals(30 * 60 * 1000L, SearchCacheManager.ttlFor(PreloadStore.TYPE_README))
        assertEquals(60 * 60 * 1000L, SearchCacheManager.ttlFor(PreloadStore.TYPE_LANG))
        assertEquals(30 * 60 * 1000L, SearchCacheManager.ttlFor(PreloadStore.TYPE_CONTRIB))
        assertEquals(30 * 60 * 1000L, SearchCacheManager.ttlFor(PreloadStore.TYPE_BRANCH))
    }

    @Test
    fun `预加载容量足够覆盖多个仓库`() {
        // 每个仓库最多 5 条（信息/README/语言/贡献者/分支），预加载必须容纳多个仓库
        assertTrue("LRU 上限需 ≥ 100", SearchCacheManager.MAX_ENTRIES >= 100)
    }
}
