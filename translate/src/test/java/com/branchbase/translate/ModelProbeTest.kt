package com.branchbase.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型列表探测的纯逻辑（**按协议**拼端点 / 解析响应 / 读响应头）。
 *
 * 不测真实网络：那需要起一个 HTTP 服务，收益是「HttpURLConnection 会不会写错请求头」，
 * 而这条路径只有两行；真正会静默出错的是**端点拼接**、**响应结构**与**响应头解读**，
 * 三者都是纯函数。
 */
class ModelProbeTest {

    private val chat = TranslateProtocol.OPENAI_CHAT
    private val anthropic = TranslateProtocol.ANTHROPIC_MESSAGES
    private val gemini = TranslateProtocol.GEMINI
    private val openai = TranslateProtocol.OPENAI_CHAT

    @Test
    fun `空接入地址回落到该协议自己的官方端点`() {
        assertEquals("https://api.openai.com/v1/models", ModelProbe.endpoint(chat, ""))
        assertEquals("https://api.openai.com/v1/models", ModelProbe.endpoint(chat, "   "))
        assertEquals("https://api.openai.com/v1/models", ModelProbe.endpoint(openai, ""))
        assertEquals("https://api.anthropic.com/v1/models", ModelProbe.endpoint(anthropic, ""))
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models",
            ModelProbe.endpoint(gemini, ""),
        )
    }

    @Test
    fun `接入地址去掉末尾斜杠再拼路径`() {
        assertEquals("https://gw.example.com/v1/models", ModelProbe.endpoint(openai, "https://gw.example.com/v1/"))
        assertEquals("https://api.deepseek.com/v1/models", ModelProbe.endpoint(chat, "https://api.deepseek.com/v1/"))
    }

    @Test
    fun `只填域名时补 https`() {
        assertEquals("https://api.deepseek.com/v1/models", ModelProbe.endpoint(chat, "api.deepseek.com/v1"))
    }

    @Test
    fun `已经以完整路径结尾不再重复追加`() {
        assertEquals("https://gw.example.com/v1/models", ModelProbe.endpoint(chat, "https://gw.example.com/v1/models"))
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models",
            ModelProbe.endpoint(gemini, "https://generativelanguage.googleapis.com/v1beta/models"),
        )
    }

    @Test
    fun `地址里已含路径第一段时只补剩余段`() {
        // 用户把 /v1 或 /v1beta 一起粘进地址（中转网关常见写法）→ 不能写出 /v1/v1/
        assertEquals("https://api.openai.com/v1/models", ModelProbe.endpoint(openai, "https://api.openai.com/v1"))
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models",
            ModelProbe.endpoint(gemini, "https://generativelanguage.googleapis.com/v1beta"),
        )
    }

    @Test
    fun `解析 data 里的 id 并去重排序`() {
        val json = """
            {"object":"list","data":[
              {"id":"deepseek-v4-pro"},
              {"id":"deepseek-flash"},
              {"id":"deepseek-flash"}
            ]}
        """.trimIndent()
        assertEquals(listOf("deepseek-flash", "deepseek-v4-pro"), ModelProbe.parse(chat, json))
    }

    @Test
    fun `Anthropic 的 models 响应与 OpenAI 同形`() {
        val json = """{"data":[{"id":"claude-opus-5-5","display_name":"Claude Opus 5.5","type":"model"}],"has_more":false}"""
        assertEquals(listOf("claude-opus-5-5"), ModelProbe.parse(anthropic, json))
    }

    @Test
    fun `Gemini 的 models 响应去掉 models 前缀并滤掉只能嵌入的模型`() {
        val json = """
            {"models":[
              {"name":"models/gemini-2.5-pro","supportedGenerationMethods":["generateContent","countTokens"]},
              {"name":"models/gemini-2.5-flash","supportedGenerationMethods":["generateContent"]},
              {"name":"models/text-embedding-004","supportedGenerationMethods":["embedContent"]},
              {"name":"models/gemini-2.5-pro"}
            ]}
        """.trimIndent()
        // 最后一条没带 supportedGenerationMethods：字段缺失时不筛（有的网关不返回它）
        assertEquals(
            listOf("gemini-2.5-flash", "gemini-2.5-pro"),
            ModelProbe.parse(gemini, json),
        )
    }

    @Test
    fun `解析坏 JSON 或结构不符返回空表`() {
        assertTrue(ModelProbe.parse(chat, "not json").isEmpty())
        assertTrue(ModelProbe.parse(chat, """{"object":"list"}""").isEmpty())
        // 拿 OpenAI 的解析器读 Gemini 的体：结构不同 → 空表（而不是抛异常）
        assertTrue(ModelProbe.parse(chat, """{"models":[{"name":"models/x"}]}""").isEmpty())
    }

    @Test
    fun `服务商没有模型列表接口时给专门的哨兵值`() {
        // DeepSeek 的 /anthropic 兼容通道没有 GET /v1/models：这不该被当成「地址填错」
        assertTrue(ModelProbe.NO_MODELS_ENDPOINT.isNotEmpty())
        assertEquals("NO_MODELS_ENDPOINT", ModelProbe.NO_MODELS_ENDPOINT)
    }

    @Test
    fun `未填 Key 不发起请求`() {
        val result = ModelProbe.fetch(openai, "https://gw.example.com/v1", "  ")
        assertTrue(result is ModelProbe.Result.Fail)
        assertEquals(ModelProbe.EMPTY_KEY, (result as ModelProbe.Result.Fail).detail)
    }

    @Test
    fun `失败结果只带状态码与数值_文案留给界面语言`() {
        val retry = ModelProbe.failure(429, mapOf<String?, List<String>>("retry-after" to listOf("12")))
        // 模块里不出现中文界面文案（英文界面切不过去）；句子由 :app 用资源拼
        assertEquals("HTTP 429", retry.detail)
        assertEquals(12, retry.retryAfterSeconds)
        assertFalse(retry.quotaExhausted)

        // 没有可用的头时只有状态码，不回显响应头
        val plain = ModelProbe.failure(401, emptyMap())
        assertEquals("HTTP 401", plain.detail)
        assertNull(plain.retryAfterSeconds)
        assertFalse(plain.quotaExhausted)

        // 与翻译无关的响应头不参与（Content-Type 之类不进提示）
        assertNull(ModelProbe.failure(500, mapOf<String?, List<String>>("content-type" to listOf("text/html"))).retryAfterSeconds)
    }

    @Test
    fun `额度用尽的响应头转成布尔位`() {
        val quota = ModelProbe.failure(429, mapOf<String?, List<String>>("x-ratelimit-remaining" to listOf("0")))
        assertEquals("HTTP 429", quota.detail)
        assertTrue(quota.quotaExhausted)
        assertNull(quota.retryAfterSeconds)
        assertFalse(ResponseHints.remainingZero(mapOf<String?, List<String>>("x-ratelimit-remaining" to listOf("7"))))
    }

    @Test
    fun `响应头名大小写不敏感`() {
        val fields = mapOf<String?, List<String>>("Retry-After" to listOf("3"))
        assertEquals(3, ResponseHints.retryAfterSeconds(fields))
        // HTTP-date 形态不解析（容错成本大于收益），只认秒数
        assertNull(
            ResponseHints.retryAfterSeconds(
                mapOf<String?, List<String>>("retry-after" to listOf("Wed, 21 Oct 2026 07:28:00 GMT")),
            ),
        )
    }
}
