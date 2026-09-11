package com.branchbase.translate

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 门面单测（判定 → 缓存 → 保护 → 分片 → 还原 → 回写）。
 *
 * 用假引擎把「翻译服务说什么」变成可控输入，于是几条不变式可以被精确钉住：
 * 失败只影响自己、绝不插入半截译文、缓存命中不再发请求、保护失败要兜底重译。
 */
class TranslatorTest {

    private class RecordingEngine(private val handler: (String) -> EngineResult) : TranslateEngine {
        val calls = mutableListOf<String>()
        override suspend fun translate(text: String, from: String, to: String): EngineResult {
            calls += text
            return handler(text)
        }
    }

    private fun translator(
        engine: TranslateEngine,
        cache: TranslateCache = TranslateCache(memoryEntries = 32) { null },
        protect: Boolean = true,
    ): Translator = Translator(
        engine = engine,
        cache = cache,
        scheduler = TranslateScheduler(engine, SchedulerConfig(maxRetries = 0)) {},
        protectTokens = { protect },
    )

    @Test
    fun `不需要翻译的段落不发请求`() = runBlocking {
        val engine = RecordingEngine { EngineResult.Ok("不该被调用") }
        val t = translator(engine)
        assertNull(t.translateParagraph("这是一段中文说明，不需要翻译。", LANG_EN, LANG_ZH))
        assertNull(t.translateParagraph("v1.2.3", LANG_EN, LANG_ZH))
        assertEquals(0, engine.calls.size)
    }

    @Test
    fun `相同段落只翻一次`() = runBlocking {
        val engine = RecordingEngine { EngineResult.Ok("你好，世界") }
        val t = translator(engine)
        val text = "Hello world, this is a test."

        assertEquals("你好，世界", t.translateParagraph(text, LANG_EN, LANG_ZH))
        assertEquals("你好，世界", t.translateParagraph(text, LANG_EN, LANG_ZH))
        assertEquals(1, engine.calls.size)
        assertEquals(1, t.stats().memoryCount)
    }

    @Test
    fun `链接被保护_译文里原样还原`() = runBlocking {
        val engine = RecordingEngine { text -> EngineResult.Ok(text.replace("See", "查看")) }
        val t = translator(engine)

        val out = t.translateParagraph("See https://example.com/x for details.", LANG_EN, LANG_ZH)
        assertEquals("查看 https://example.com/x for details.", out)
        assertTrue("送出去的文本里不应带 URL", engine.calls.single().contains("⟦0⟧"))
        assertTrue("送出去的文本里不应带 URL", !engine.calls.single().contains("example.com"))
    }

    @Test
    fun `占位符被服务端吞掉时_退化为不加保护地重翻一次`() = runBlocking {
        var round = 0
        val engine = RecordingEngine { text ->
            round++
            if (text.contains("⟦")) EngineResult.Ok("占位符被吃掉了") else EngineResult.Ok("查看 https://example.com/x")
        }
        val t = translator(engine)

        assertEquals("查看 https://example.com/x", t.translateParagraph("See https://example.com/x now.", LANG_EN, LANG_ZH))
        assertEquals(2, engine.calls.size)
        assertTrue(engine.calls[0].contains("⟦"))
        assertTrue(engine.calls[1].contains("example.com"))
        assertTrue(round == 2)
    }

    @Test
    fun `长段落分片后换行拼接`() = runBlocking {
        val engine = RecordingEngine { EngineResult.Ok("译") }
        val t = translator(engine)
        val text = "This is sentence number one. ".repeat(40)   // ~1160 字符，仍在 maxLen 内

        val out = t.translateParagraph(text, LANG_EN, LANG_ZH)
        assertTrue("应该发生分片：${engine.calls.size}", engine.calls.size > 1)
        assertEquals(engine.calls.joinToString("\n") { "译" }, out)
    }

    @Test
    fun `翻译失败返回 null 且不写入缓存`() = runBlocking {
        val engine = RecordingEngine { EngineResult.Fail(FailKind.NETWORK, "boom") }
        val t = translator(engine)
        assertNull(t.translateParagraph("Hello world, this is a test.", LANG_EN, LANG_ZH))
        assertEquals(0, t.stats().memoryCount)
    }

    @Test
    fun `批量翻译保持入参顺序_失败位为空串`() = runBlocking {
        val engine = RecordingEngine { text ->
            if (text.contains("fail")) EngineResult.Fail(FailKind.NETWORK, "boom") else EngineResult.Ok("译文:$text")
        }
        val t = translator(engine)
        val out = t.translateAll(
            listOf("First paragraph here.", "这是一段中文，会被跳过。", "Please fail this one."),
            LANG_EN,
            LANG_ZH,
        )

        assertEquals(3, out.size)
        assertEquals("译文:First paragraph here.", out[0])
        assertEquals("", out[1])          // 判定为不需要翻
        assertEquals("", out[2])          // 翻译失败
    }

    @Test
    fun `整段只有标记时直接跳过`() = runBlocking {
        val engine = RecordingEngine { EngineResult.Ok("不该被调用") }
        val t = translator(engine)
        assertNull(t.translateParagraph("https://example.com/a/b/c", LANG_EN, LANG_ZH))
        assertEquals(0, engine.calls.size)
    }

    @Test
    fun `关掉保护开关时原文直接送出去`() = runBlocking {
        val engine = RecordingEngine { EngineResult.Ok("查看链接 https://example.com/x") }
        val t = translator(engine, protect = false)
        assertEquals("查看链接 https://example.com/x", t.translateParagraph("See https://example.com/x", LANG_EN, LANG_ZH))
        assertEquals("See https://example.com/x", engine.calls.single())
    }

    @Test
    fun `清空缓存后需要重新翻译`() = runBlocking {
        val engine = RecordingEngine { EngineResult.Ok("你好") }
        val t = translator(engine)
        val text = "Hello world, this is a test."

        t.translateParagraph(text, LANG_EN, LANG_ZH)
        t.clearCache()
        assertEquals(0, t.stats().memoryCount)
        t.translateParagraph(text, LANG_EN, LANG_ZH)
        assertEquals(2, engine.calls.size)
    }

    @Test
    fun `测试连接绕过缓存与段落判定`() = runBlocking {
        val engine = RecordingEngine { EngineResult.Ok("你好") }
        val t = translator(engine)

        assertEquals(EngineResult.Ok("你好"), t.selfTest(from = LANG_EN, to = LANG_ZH))
        assertEquals(1, engine.calls.size)
        // 测试文本不进缓存（否则「测试」会污染真实译文）
        assertEquals(0, t.stats().memoryCount)
    }

    @Test
    fun `测试连接会清掉上一次的熔断_让结果反映真实原因`() = runBlocking {
        var authFails = true
        val engine = RecordingEngine {
            if (authFails) EngineResult.Fail(FailKind.AUTH, "401") else EngineResult.Ok("好了")
        }
        val t = translator(engine)

        t.selfTest()
        assertTrue(t.stats().authFailed)
        assertEquals("auth", t.engineState().pageStatus())

        // 用户去设置里改好了 Key：测试前先 reset，第二次就能测出真实结果
        authFails = false
        assertEquals(EngineResult.Ok("好了"), t.selfTest())
        assertFalse(t.stats().authFailed)
        assertEquals("ok", t.engineState().pageStatus())
    }
}
