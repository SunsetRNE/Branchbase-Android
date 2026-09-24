package com.branchbase.translate

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 门面单测（判定 → 缓存 → 保护 → 分片 → 还原 → 回写）。
 *
 * 用假引擎把「翻译服务说什么」变成可控输入，于是几条不变式可以被精确钉住：
 * 失败只影响自己、绝不插入半截译文、缓存命中不再发请求、保护失败要兜底重译，
 * 以及 1.0.89 新增的两条 —— **译后与原文一致就不插卡片（并记住这个判定）**、
 * **混排段落只把片段送出去**。
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
        rules: () -> PageRules = { PageRules.DEFAULT },
    ): Translator = Translator(
        engine = engine,
        cache = cache,
        scheduler = TranslateScheduler(engine, SchedulerConfig(maxRetries = 0)) {},
        rules = rules,
        protectTokens = { protect },
    )

    /** 这些用例大多只关心「整段译文是什么」，判定结果不是重点。 */
    private suspend fun Translator.whole(text: String, from: String = LANG_EN, to: String = LANG_ZH): String? =
        (translateOne(text, from, to) as? ParagraphTranslation.Whole)?.text

    @Test
    fun `不需要翻译的段落不发请求`() = runBlocking {
        val engine = RecordingEngine { EngineResult.Ok("不该被调用") }
        val t = translator(engine)
        assertEquals(ParagraphTranslation.None, t.translateOne("这是一段中文说明，不需要翻译。", LANG_EN, LANG_ZH))
        assertEquals(ParagraphTranslation.None, t.translateOne("v1.2.3", LANG_EN, LANG_ZH))
        assertEquals(0, engine.calls.size)
        assertEquals(2, t.stats().skipped)
    }

    @Test
    fun `相同段落只翻一次`() = runBlocking {
        val engine = RecordingEngine { EngineResult.Ok("你好，世界") }
        val t = translator(engine)
        val text = "Hello world, this is a test."

        assertEquals("你好，世界", t.whole(text))
        assertEquals("你好，世界", t.whole(text))
        assertEquals(1, engine.calls.size)
        assertEquals(1, t.stats().memoryCount)
    }

    @Test
    fun `链接被保护_译文里原样还原`() = runBlocking {
        val engine = RecordingEngine { text -> EngineResult.Ok(text.replace("See", "查看")) }
        val t = translator(engine)

        assertEquals("查看 https://example.com/x for details.", t.whole("See https://example.com/x for details."))
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

        assertEquals("查看 https://example.com/x", t.whole("See https://example.com/x now."))
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

        val out = t.whole(text)
        assertTrue("应该发生分片：${engine.calls.size}", engine.calls.size > 1)
        assertEquals(engine.calls.joinToString("\n") { "译" }, out)
    }

    @Test
    fun `翻译失败返回 Failed 且不写入缓存`() = runBlocking {
        val engine = RecordingEngine { EngineResult.Fail(FailKind.NETWORK, "boom") }
        val t = translator(engine)
        assertEquals(ParagraphTranslation.Failed, t.translateOne("Hello world, this is a test.", LANG_EN, LANG_ZH))
        assertEquals(0, t.stats().memoryCount)
    }

    @Test
    fun `批量翻译保持入参顺序_跳过与失败分得开`() = runBlocking {
        val engine = RecordingEngine { text ->
            if (text.contains("fail")) EngineResult.Fail(FailKind.NETWORK, "boom") else EngineResult.Ok("译文:$text")
        }
        val t = translator(engine)
        val out = t.translateBatch(
            listOf("First paragraph here.", "这是一段中文，会被跳过。", "Please fail this one."),
            LANG_EN,
            LANG_ZH,
        )

        assertEquals(3, out.size)
        assertEquals("译文:First paragraph here.", (out[0] as ParagraphTranslation.Whole).text)
        // 「判定跳过」与「翻译失败」必须是两种产物：页面靠后者判断服务不可用
        assertEquals(ParagraphTranslation.None, out[1])
        assertEquals(ParagraphTranslation.Failed, out[2])
    }

    @Test
    fun `整段只有标记时直接跳过`() = runBlocking {
        val engine = RecordingEngine { EngineResult.Ok("不该被调用") }
        val t = translator(engine)
        assertEquals(ParagraphTranslation.None, t.translateOne("https://example.com/a/b/c", LANG_EN, LANG_ZH))
        assertEquals(0, engine.calls.size)
    }

    @Test
    fun `关掉保护开关时原文直接送出去`() = runBlocking {
        val engine = RecordingEngine { EngineResult.Ok("查看链接 https://example.com/x") }
        val t = translator(engine, protect = false)
        assertEquals("查看链接 https://example.com/x", t.whole("See https://example.com/x"))
        assertEquals("See https://example.com/x", engine.calls.single())
    }

    @Test
    fun `清空缓存后需要重新翻译`() = runBlocking {
        val engine = RecordingEngine { EngineResult.Ok("你好") }
        val t = translator(engine)
        val text = "Hello world, this is a test."

        t.whole(text)
        t.clearCache()
        assertEquals(0, t.stats().memoryCount)
        t.whole(text)
        assertEquals(2, engine.calls.size)
    }

    // ── 一致即跳过（译后校验 + 判定缓存） ──

    @Test
    fun `服务端把原文原样还回来时判定为无需翻译_且下次不再请求`() = runBlocking {
        val engine = RecordingEngine { EngineResult.Ok(it) }   // 原样返回（没有可用译文）
        val t = translator(engine)
        val text = "This paragraph has no translation available."

        assertEquals(ParagraphTranslation.None, t.translateOne(text, LANG_EN, LANG_ZH))
        assertEquals(1, engine.calls.size)
        assertEquals(1, t.stats().identical)

        // 第二次：判定缓存命中 —— 连请求都不发（否则每次重开页面都要再问一遍）
        assertEquals(ParagraphTranslation.None, t.translateOne(text, LANG_EN, LANG_ZH))
        assertEquals("判定缓存应拦住第二次请求", 1, engine.calls.size)
        assertTrue(t.stats().skipped >= 1)
        assertEquals("一致的内容不进译文缓存", 0, t.stats().memoryCount)
    }

    @Test
    fun `只差标点的译文同样判为一致`() = runBlocking {
        val engine = RecordingEngine { EngineResult.Ok("This paragraph has no translation available.") }
        val t = translator(engine)
        assertEquals(
            ParagraphTranslation.None,
            t.translateOne("This paragraph has no translation available", LANG_EN, LANG_ZH),
        )
        assertEquals(0, t.stats().memoryCount)
    }

    @Test
    fun `清空缓存会一并清掉判定缓存`() = runBlocking {
        val engine = RecordingEngine { EngineResult.Ok(it) }
        val t = translator(engine)
        val text = "This paragraph has no translation available."

        t.translateOne(text, LANG_EN, LANG_ZH)
        t.clearCache()
        t.translateOne(text, LANG_EN, LANG_ZH)
        assertEquals("清空之后必须重新判一遍", 2, engine.calls.size)
    }

    // ── 匹配性翻译（混排段落只翻片段） ──

    @Test
    fun `中文段落只把外语片段送出去`() = runBlocking {
        val engine = RecordingEngine { EngineResult.Ok("包管理器") }
        val t = translator(engine)

        val out = t.translateOne("项目使用 pnpm workspace 管理依赖，发布前请先跑一遍完整测试。", LANG_EN, LANG_ZH)
        assertEquals(
            ParagraphTranslation.Matched(listOf(TranslatedPart("pnpm workspace", "包管理器"))),
            out,
        )
        // 送出去的只有片段：中文一个字都没发（既省额度，也不会被再翻一遍）
        assertEquals(listOf("pnpm workspace"), engine.calls)
        assertEquals(1, t.stats().matched)
        assertEquals(1, t.stats().parts)
    }

    @Test
    fun `同一片段在别的段落里命中缓存`() = runBlocking {
        val engine = RecordingEngine { EngineResult.Ok("运行开发") }
        val t = translator(engine)

        t.translateOne("第一段说明 npm run dev 的用法，其余内容都是中文。", LANG_EN, LANG_ZH)
        t.translateOne("第二段再次提到 npm run dev 的用法，其余内容都是中文。", LANG_EN, LANG_ZH)
        assertEquals("片段按原文进缓存：全站只翻一次", 1, engine.calls.size)
    }

    @Test
    fun `片段本身就是目标文字时不成对_并记住判定`() = runBlocking {
        val engine = RecordingEngine { EngineResult.Ok(it) }   // 专有名词原样返回
        val t = translator(engine)
        val text = "项目使用 Docker Desktop 启动，其余内容都是中文说明文字。"

        assertEquals(ParagraphTranslation.None, t.translateOne(text, LANG_EN, LANG_ZH))
        assertEquals(1, t.stats().identical)

        t.translateOne(text, LANG_EN, LANG_ZH)
        assertEquals("没有任何可展示的对照 → 记住这段不用再问", 1, engine.calls.size)
    }

    @Test
    fun `片段部分失败时已拿到的对照照常展示`() = runBlocking {
        val engine = RecordingEngine { text ->
            if (text.contains("fail")) EngineResult.Fail(FailKind.NETWORK, "boom") else EngineResult.Ok("第一段")
        }
        val t = translator(engine)

        val out = t.translateOne(
            "中文说明在这里：alpha beta 与 fail hard 都要按顺序处理，其余内容都是中文说明文字。",
            LANG_EN,
            LANG_ZH,
        )
        assertEquals(
            ParagraphTranslation.Matched(listOf(TranslatedPart("alpha beta", "第一段"))),
            out,
        )
    }

    @Test
    fun `片段全部失败时按整段失败处理`() = runBlocking {
        val engine = RecordingEngine { EngineResult.Fail(FailKind.NETWORK, "boom") }
        val t = translator(engine)
        assertEquals(
            ParagraphTranslation.Failed,
            t.translateOne("中文说明在这里：alpha beta 都要按顺序处理，其余内容都是中文说明文字。", LANG_EN, LANG_ZH),
        )
    }

    @Test
    fun `混排规则切成整段翻时走整段路径`() = runBlocking {
        val engine = RecordingEngine { EngineResult.Ok("整段译文") }
        val t = translator(engine, rules = { PageRules.DEFAULT.copy(matchPolicy = PageRules.MATCH_POLICY_WHOLE) })

        assertEquals(
            ParagraphTranslation.Whole("整段译文"),
            t.translateOne("项目使用 pnpm workspace 管理依赖，发布前请先跑一遍完整测试。", LANG_EN, LANG_ZH),
        )
        assertEquals(1, engine.calls.size)
    }

    @Test
    fun `混排规则切成不翻时一个请求都不发`() = runBlocking {
        val engine = RecordingEngine { EngineResult.Ok("不该被调用") }
        val t = translator(engine, rules = { PageRules.DEFAULT.copy(matchPolicy = PageRules.MATCH_POLICY_SKIP) })

        assertEquals(
            ParagraphTranslation.None,
            t.translateOne("项目使用 pnpm workspace 管理依赖，发布前请先跑一遍完整测试。", LANG_EN, LANG_ZH),
        )
        assertEquals(0, engine.calls.size)
    }

    @Test
    fun `改了混排规则后旧的判定结论不再生效`() = runBlocking {
        var rules = PageRules.DEFAULT
        val engine = RecordingEngine { EngineResult.Ok(it) }   // 原样返回 → 判为一致
        val t = translator(engine, rules = { rules })
        val text = "项目使用 Docker Desktop 启动，其余内容都是中文说明文字。"

        assertEquals(ParagraphTranslation.None, t.translateOne(text, LANG_EN, LANG_ZH))
        assertEquals(1, engine.calls.size)

        // 规则换成「整段一起翻」：同一段的结论不再成立，必须重新判（也就重新发请求）
        rules = PageRules.DEFAULT.copy(matchPolicy = PageRules.MATCH_POLICY_WHOLE)
        t.translateOne(text, LANG_EN, LANG_ZH)
        assertEquals(2, engine.calls.size)
    }

    // ── 测试连接 ──

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

    @Test
    fun `失败不会被记成无需翻译`() = runBlocking {
        var ok = false
        val engine = RecordingEngine { if (ok) EngineResult.Ok("你好，世界") else EngineResult.Fail(FailKind.NETWORK, "断了") }
        val t = translator(engine)
        val text = "Hello world, this is a test."

        assertEquals(ParagraphTranslation.Failed, t.translateOne(text, LANG_EN, LANG_ZH))
        ok = true
        // 失败不进判定缓存：断网恢复后必须还能翻出来
        assertEquals("你好，世界", t.whole(text))
    }
}
