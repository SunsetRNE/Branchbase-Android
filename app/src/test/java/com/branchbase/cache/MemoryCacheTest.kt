package com.branchbase.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 进程内一级缓存（L1）单测：命中 / 过期 / 类型隔离 / LRU / 字节预算。
 *
 * 这些行为出错都**不会崩**，只会「静默变慢」或「静默返回错数据」——
 * 例如类型不参与匹配时，`file:` 与 `wf-file:` 同名 key 会互相污染；
 * LRU 写反时，重进页面（总是命中同一批 key）反而最先被淘汰。
 */
class MemoryCacheTest {

    private fun cache(maxEntries: Int = 16, maxBytes: Long = 1_000_000) =
        MemoryCache(maxEntries = maxEntries, maxBytes = maxBytes)

    @Test
    fun `未过期才算命中_过期仍可直出`() {
        val c = cache()
        val now = 1_000_000L
        c.put("k", "t", "data", expireAt = now + 60_000)

        assertEquals("data", c.get("k", "t", now)?.data)
        assertNull("过期后不算新鲜", c.get("k", "t", now + 60_001))
        assertEquals("过期数据仍要能直出（stale-while-revalidate）", "data", c.getStale("k", "t")?.data)
    }

    @Test
    fun `类型不匹配不算命中_防止跨类型污染`() {
        val c = cache()
        c.put("same", "文件内容", "A", expireAt = Long.MAX_VALUE)
        assertNull("同 key 不同类型不能命中", c.get("same", "仓库列表"))
        assertNull(c.getStale("same", "仓库列表"))
        assertEquals("A", c.get("same", "文件内容")?.data)
    }

    @Test
    fun `超过条数上限按 LRU 淘汰_访问过的留得住`() {
        val c = cache(maxEntries = 2)
        c.put("a", "t", "A", Long.MAX_VALUE)
        c.put("b", "t", "B", Long.MAX_VALUE)
        // 访问 a：它应被移到队尾（最新）
        assertNotNull(c.get("a", "t"))
        c.put("c", "t", "C", Long.MAX_VALUE)

        assertEquals("容量保持在上限", 2, c.size)
        assertNotNull("刚访问过的 a 不能被淘汰", c.get("a", "t"))
        assertNotNull("刚写入的 c 必须在", c.get("c", "t"))
        assertNull("最久未访问的 b 被淘汰", c.getStale("b", "t"))
    }

    @Test
    fun `超过字节预算也要淘汰`() {
        // 每条 "x"*100 → 200 字节；预算只够 3 条
        val c = cache(maxEntries = 100, maxBytes = 600)
        repeat(4) { i -> c.put("k$i", "t", "x".repeat(100), Long.MAX_VALUE) }
        assertEquals("按字节裁到预算内", 3, c.size)
        assertEquals("字节计数要跟着减", 600L, c.bytesUsed)
    }

    @Test
    fun `覆盖写不重复计字节_删除与清空归零`() {
        val c = cache()
        c.put("k", "t", "x".repeat(50), Long.MAX_VALUE)
        val one = c.bytesUsed
        c.put("k", "t", "x".repeat(50), Long.MAX_VALUE)
        assertEquals("同 key 覆盖不应累加", one, c.bytesUsed)

        c.delete("k")
        assertEquals(0L, c.bytesUsed)
        assertEquals(0, c.size)

        c.put("k2", "t", "y", Long.MAX_VALUE)
        c.clear()
        assertEquals(0L, c.bytesUsed)
        assertEquals(0, c.size)
    }
}
