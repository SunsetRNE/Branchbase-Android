package com.branchbase.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `LruCache` 与头像目录淘汰策略的单测。
 *
 * 这一层出错的后果都很难看且不报错：LRU 顺序写反 → 「刚看过的头像下一次又被淘汰」
 * （每次都要重新下载）；目录淘汰顺序写反 → 「刚存下的头像立刻被删」。
 * 两者都是纯逻辑，必须被测到（这也是不用 `android.util.LruCache` 的原因 —— 它在 JVM 单测里是空壳）。
 */
class AvatarCachePolicyTest {

    @Test
    fun `LRU 淘汰最久未访问的一项`() {
        val lru = LruCache<String, String>(2)
        lru.put("a", "A")
        lru.put("b", "B")
        lru.get("a")            // 触碰 a → b 成为最久未用
        lru.put("c", "C")
        assertEquals(2, lru.size())
        assertEquals("A", lru.get("a"))
        assertNull("最久未用的 b 应被淘汰", lru.get("b"))
        assertEquals("C", lru.get("c"))
    }

    @Test
    fun `LRU 支持按前缀删除`() {
        val lru = LruCache<String, String>(8)
        lru.put("me|40|0", "x")
        lru.put("me|80|0", "y")
        lru.put("other|40|0", "z")
        lru.removeWhere { it.startsWith("me|") }
        assertNull(lru.get("me|40|0"))
        assertNull(lru.get("me|80|0"))
        assertEquals("别的账号不能被连坐", "z", lru.get("other|40|0"))
    }

    @Test
    fun `LRU 覆盖写不增加条数`() {
        val lru = LruCache<String, String>(4)
        lru.put("k", "1")
        lru.put("k", "2")
        assertEquals(1, lru.size())
        assertEquals("2", lru.get("k"))
    }

    @Test
    fun `目录淘汰保留最新的_删最旧的`() {
        val files = listOf(
            "old.png" to 100L,
            "newest.png" to 300L,
            "mid.png" to 200L,
        )
        assertEquals(listOf("old.png"), evictionVictims(files, keep = 2))
        assertEquals(listOf("old.png", "mid.png"), evictionVictims(files, keep = 1))
        assertEquals("没超上限就一个都不删", emptyList<String>(), evictionVictims(files, keep = 3))
    }

    @Test
    fun `目录淘汰在所有时间相同时也不炸`() {
        val files = listOf("a.png" to 5L, "b.png" to 5L, "c.png" to 5L)
        assertEquals(2, evictionVictims(files, keep = 1).size)
    }
}
