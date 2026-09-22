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

    // ── 变体维度（1.0.58 补的键维度） ──

    @Test
    fun `变体不同不能互相命中_换后端不许拿旧译文冒充`() = runBlocking {
        val disk = TranslateDiskCache(tmp.newFile("cache6.tsv"), maxEntries = 8)
        val cache = TranslateCache(memoryEntries = 8) { disk }
        cache.put("en", LANG_ZH, "hello", "MyMemory 的译文", variant = "mymemory||")
        cache.put("en", LANG_ZH, "hello", "DeepSeek 的译文", variant = "deepseek|deepseek-chat|")

        assertEquals("MyMemory 的译文", cache.get("en", LANG_ZH, "hello", "mymemory||"))
        assertEquals("DeepSeek 的译文", cache.get("en", LANG_ZH, "hello", "deepseek|deepseek-chat|"))
        // 换回旧后端仍然命中（键空间并存，不是「换一次就全清」）
        assertEquals("MyMemory 的译文", cache.get("en", LANG_ZH, "hello", "mymemory||"))
        assertNull("没翻过的变体不能命中", cache.get("en", LANG_ZH, "hello", "openai|gpt|x"))
    }

    @Test
    fun `变体相同则跨进程命中_磁盘也按变体隔离`() = runBlocking {
        val disk = TranslateDiskCache(tmp.newFile("cache7.tsv"), maxEntries = 8)
        TranslateCache(memoryEntries = 8) { disk }
            .put("en", LANG_ZH, "hello", "你好", variant = "mymemory||")

        val fresh = TranslateCache(memoryEntries = 8) { disk }   // 模拟新进程
        assertEquals("你好", fresh.get("en", LANG_ZH, "hello", "mymemory||"))
        assertNull("别的变体不命中", fresh.get("en", LANG_ZH, "hello", "deepseek|chat|"))
    }

    @Test
    fun `长度前缀拼接_原文里的分隔符不会造成撞键`() = runBlocking {
        // 朴素拼接 "from|to|variant|text" 下，这两组会拼出同一个字符串：
        //   (variant="a", text="b|c") 与 (variant="a|b", text="c")
        // 撞键的后果是**返回另一段的译文**（不是慢，是错），所以必须钉住。
        val cache = TranslateCache(memoryEntries = 8) { null }
        cache.put("en", LANG_ZH, "b|c", "第一段", variant = "a")
        cache.put("en", LANG_ZH, "c", "第二段", variant = "a|b")

        assertEquals("第一段", cache.get("en", LANG_ZH, "b|c", "a"))
        assertEquals("第二段", cache.get("en", LANG_ZH, "c", "a|b"))
    }
}
