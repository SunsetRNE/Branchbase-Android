package com.branchbase.ui.repository

import com.branchbase.cache.PageCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提交图首页的**地址形状与缓存键**（`CommitGraphCache`，1.1.7）。
 *
 * 为什么值得钉：这一份 JSON 有两个写入口（提交图自己取数、宿主预热）但只允许有一个形状 ——
 * 预热用的是 `/commits?per_page=100&sha=main`，提交图取数用别的（少一个参数、页大小不同），
 * 结果就是「明明暖过，点开还是慢」，而且完全静默（缓存命中不了只会回源，不会报错）。
 * 所以这里把**地址**与**页大小**都钉成字面量，任一边改动都会红。
 */
class CommitGraphCacheTest {

    @Test
    fun `首页地址按分支取_页大小与提交图分页是同一个数`() {
        assertEquals(
            "/repos/o/r/commits?per_page=100&sha=main",
            CommitGraphCache.firstPagePath("o", "r", "main"),
        )
        // 页大小只有一份（CommitGraphPanel 里的 internal const）：两边差一条，
        // 「有没有下一页」的判定就会静默失准
        assertEquals(100, GRAPH_PAGE_SIZE)
        assertTrue(CommitGraphCache.firstPagePath("o", "r", "main").contains("per_page=$GRAPH_PAGE_SIZE"))
    }

    @Test
    fun `翻页按提交 sha 取_而不是分支`() {
        assertEquals(
            "/repos/o/r/commits?per_page=100&sha=abc123",
            CommitGraphCache.firstPagePath("o", "r", "main", "abc123"),
        )
    }

    @Test
    fun `分支为空时不写 sha 参数`() {
        // 空分支意味着「仓库的默认分支」，由 GitHub 决定 —— 硬塞一个 &sha= 会让请求形状变可疑
        val path = CommitGraphCache.firstPagePath("o", "r", "")
        assertEquals("/repos/o/r/commits?per_page=100", path)
        assertFalse(path.contains("sha="))
    }

    @Test
    fun `缓存键按仓库与分支分开_且不跟提交详情撞键`() {
        assertEquals("detail:graph:o/r@main", CommitGraphCache.key("o", "r", "main"))
        // 空分支归到 HEAD 这一档；真的有个分支叫 HEAD 时也只是同一个引用，不影响正确性
        assertEquals("detail:graph:o/r@HEAD", CommitGraphCache.key("o", "r", ""))
        assertTrue(CommitGraphCache.key("o", "r", "main") != CommitGraphCache.key("o", "r", "dev"))
        assertTrue(CommitGraphCache.key("o", "r", "main") != PageCache.commitKey("o", "r", "main"))
    }
}
