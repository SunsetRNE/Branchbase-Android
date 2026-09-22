package com.branchbase.translate

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `Translator` 的**缓存键维度**单测（1.0.58）。
 *
 * 这一层防的是「换了设置却拿旧译文冒充」：译文内容不只取决于原文，
 * 还取决于**谁翻的**（后端 / 模型 / 接入地址）与**怎么翻的**（占位符保护开关）。
 * 键少了这些维度时不会报错 —— 用户只会觉得「换成 DeepSeek 没什么变化」「关掉保护对比一下，结果一样」。
 *
 * 计数引擎调用次数即可判定命中与否：**没命中才真的会去调引擎**。
 */
class TranslatorCacheVariantTest {

    /** 只数调用次数的假引擎：每次返回可预测的译文。 */
    private class CountingEngine : TranslateEngine {
        var calls = 0
        override suspend fun translate(text: String, from: String, to: String): EngineResult {
            calls++
            return EngineResult.Ok("[$text]")
        }
    }

    private fun translator(
        engine: CountingEngine,
        variant: () -> String = { "mymemory||" },
        protect: () -> Boolean = { false },
    ) = Translator(
        engine = engine,
        cache = TranslateCache(memoryEntries = 32) { null },
        rules = PageRules.DEFAULT,
        protectTokens = protect,
        engineVariant = variant,
    )

    @Test
    fun `同文本同变体只翻一次`() = runBlocking {
        val engine = CountingEngine()
        val t = translator(engine)
        val first = t.translateParagraph("This is a long enough paragraph to translate.", "en", LANG_ZH)
        val second = t.translateParagraph("This is a long enough paragraph to translate.", "en", LANG_ZH)
        assertNotNull(first)
        assertEquals(first, second)
        assertEquals("第二次必须命中缓存，不再调引擎", 1, engine.calls)
    }

    @Test
    fun `换后端后必须重新翻译_不能拿旧译文冒充`() = runBlocking {
        val engine = CountingEngine()
        var variant = "mymemory||"
        val t = translator(engine, variant = { variant })

        t.translateParagraph("This is a long enough paragraph to translate.", "en", LANG_ZH)
        assertEquals(1, engine.calls)

        // 用户在设置页换到 DeepSeek：变体变了 → 键变了 → 必须重新翻
        variant = "deepseek|deepseek-chat|"
        t.translateParagraph("This is a long enough paragraph to translate.", "en", LANG_ZH)
        assertEquals("换后端是一次真正的重新翻译", 2, engine.calls)

        // 换回 MyMemory：旧译文还在，不该再翻（键空间并存）
        variant = "mymemory||"
        t.translateParagraph("This is a long enough paragraph to translate.", "en", LANG_ZH)
        assertEquals("换回去应命中旧后端的缓存", 2, engine.calls)
    }

    @Test
    fun `占位符保护开关进键_关掉保护不能命中保护版`() = runBlocking {
        val engine = CountingEngine()
        var protect = false
        val t = translator(engine, protect = { protect })
        val text = "See https://example.com/a for details, this paragraph is long enough."

        t.translateParagraph(text, "en", LANG_ZH)
        assertEquals(1, engine.calls)

        protect = true
        t.translateParagraph(text, "en", LANG_ZH)
        assertEquals("保护开关改变译文内容，必须是不同的键", 2, engine.calls)

        protect = false
        t.translateParagraph(text, "en", LANG_ZH)
        assertEquals("切回去仍命中", 2, engine.calls)
    }

    @Test
    fun `命中率统计跟着走_设置页据此判断缓存有没有在干活`() = runBlocking {
        val engine = CountingEngine()
        val t = translator(engine)
        val text = "This is a long enough paragraph to translate."
        t.translateParagraph(text, "en", LANG_ZH)
        t.translateParagraph(text, "en", LANG_ZH)
        t.translateParagraph(text, "en", LANG_ZH)

        val stats = t.stats()
        assertEquals("未命中 1 段（第一次）", 1, stats.misses)
        assertEquals("命中 2 段", 2, stats.hits)
        assertTrue("命中率应约为 2/3", (stats.hitRate ?: 0f) > 0.6f)
    }
}
