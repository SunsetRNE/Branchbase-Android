package com.branchbase.translate

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 缓存单测（内存 LRU + 磁盘）。
 *
 * 这两层的 bug 都不会「报错」，只会表现为「翻译好像没生效」或「重开又要重翻」，
 * 所以命中/淘汰/落盘/损坏文件这几条路径都要有用例。
 */
class TranslateCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── 内存 LRU ──

    @Test
    fun `内存 LRU 淘汰最久未用的一项`() {
        val lru = LruCache<String, String>(2)
        lru.put("a", "A")
        lru.put("b", "B")
        lru.get("a")          // 触碰 a → b 成为最久未用
        lru.put("c", "C")
        assertEquals(2, lru.size())
        assertEquals("A", lru.get("a"))
        assertNull(lru.get("b"))
        assertEquals("C", lru.get("c"))
    }

    // ── 磁盘 ──

    @Test
    fun `磁盘缓存跨实例命中_含换行与制表符`() = runBlocking {
        val file = tmp.newFile("cache.tsv")
        TranslateDiskCache(file, maxEntries = 8).put("k1", "你好\n世界\t带制表符")

        // 新实例 = 新进程：只有磁盘能救它
        assertEquals("你好\n世界\t带制表符", TranslateDiskCache(file, maxEntries = 8).get("k1"))
    }

    @Test
    fun `超过容量时按访问序淘汰并压实文件`() = runBlocking {
        val file = tmp.newFile("cache2.tsv")
        val d = TranslateDiskCache(file, maxEntries = 4)
        d.put("a", "1")
        d.put("b", "2")
        d.put("c", "3")
        d.put("d", "4")
        d.get("a")            // 触碰 a → b 成为最久未用
        d.put("e", "5")       // 触发压实

        assertEquals(4, d.size())
        assertNull(d.get("b"))
        assertEquals("1", d.get("a"))
        // 压实后的文件里也不该再有 b
        assertEquals(4, TranslateDiskCache(file, maxEntries = 4).size())
        assertNull(TranslateDiskCache(file, maxEntries = 4).get("b"))
    }

    @Test
    fun `清空后旧值不再返回`() = runBlocking {
        val file = tmp.newFile("cache3.tsv")
        val d = TranslateDiskCache(file, maxEntries = 8)
        d.put("k", "v")
        d.clear()
        assertNull(d.get("k"))
        assertEquals(0, TranslateDiskCache(file, maxEntries = 8).size())
    }

    @Test
    fun `坏行被跳过而不是让缓存整体失效`() = runBlocking {
        val file = tmp.newFile("cache4.tsv")
        file.writeText("没有制表符的行\nk1\tok\\nline\n非法转义\\q\n")
        val d = TranslateDiskCache(file, maxEntries = 8)
        assertEquals("ok\nline", d.get("k1"))
        assertEquals(1, d.size())
    }

    // ── 门面 ──

    @Test
    fun `门面先内存后磁盘`() = runBlocking {
        val disk = TranslateDiskCache(tmp.newFile("cache5.tsv"), maxEntries = 8)
        TranslateCache(memoryEntries = 8) { disk }
            .put("en", LANG_ZH, "hello world", "你好，世界")

        val fresh = TranslateCache(memoryEntries = 8) { disk }   // 模拟新进程
        assertEquals("你好，世界", fresh.get("en", LANG_ZH, "hello world"))
        assertNull(fresh.get("en", LANG_ZH, "another text"))
        // 语言对参与缓存键：同样的原文、方向不同不能互相命中
        assertNull(fresh.get(LANG_ZH, "en", "hello world"))
        assertNotEquals(0, fresh.diskCount())
    }

    @Test
    fun `关掉磁盘时内存仍然工作`() = runBlocking {
        val cache = TranslateCache(memoryEntries = 8) { null }
        cache.put("en", LANG_ZH, "hello", "你好")
        assertEquals("你好", cache.get("en", LANG_ZH, "hello"))
        assertEquals(0, cache.diskCount())
    }
}
