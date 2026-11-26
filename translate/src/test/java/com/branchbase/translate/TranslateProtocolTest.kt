package com.branchbase.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 协议表的纯逻辑：**请求头自动补全**、端点拼接、地址猜测。
 *
 * 这三件事各自都会静默坏掉：头名写错 → 服务端回 401、用户以为 Key 无效；
 * 端点重复前缀 → 404，而错误文案会指向「地址不对」；
 * 猜测规则写宽 → 界面上到处是错的「推荐」标记。所以都用纯函数钉住。
 */
class TranslateProtocolTest {

    @Test
    fun `四档协议与稳定码_老的_deepseek_折成_openai_chat`() {
        assertEquals(
            listOf("openai-chat", "openai-responses", "anthropic-messages", "gemini"),
            TranslateProtocol.entries.map { it.code },
        )
        // 落盘键是稳定契约：认不出来 / 空 → 默认 OpenAI Chat
        assertEquals(TranslateProtocol.OPENAI_CHAT, TranslateProtocol.byCode(null))
        assertEquals(TranslateProtocol.OPENAI_CHAT, TranslateProtocol.byCode("  "))
        assertEquals(TranslateProtocol.OPENAI_CHAT, TranslateProtocol.byCode("天知道"))
        assertEquals(TranslateProtocol.OPENAI_CHAT, TranslateProtocol.default)
        // 1.4.53 及更早只有 deepseek 一档：DeepSeek 没有私有报文，折成 OpenAI Chat
        assertEquals(TranslateProtocol.OPENAI_CHAT, TranslateProtocol.byCode("deepseek"))
        // 大小写 / 前后空格不敏感（手写配置、备份导入都可能带）
        assertEquals(TranslateProtocol.GEMINI, TranslateProtocol.byCode(" GEMINI "))
    }

    @Test
    fun `协议自带官方地址与已知厂商地址`() {
        // 第一项 = 官方（输入框占位符）；其余是文档明示的兼容入口
        assertEquals("https://api.openai.com/v1", TranslateProtocol.OPENAI_CHAT.officialBaseUrl)
        assertEquals(
            listOf("openai", "deepseek"),
            TranslateProtocol.OPENAI_CHAT.addresses.map { it.id },
        )
        assertEquals(
            "https://api.deepseek.com/anthropic",
            TranslateProtocol.ANTHROPIC_MESSAGES.addressOf(" https://api.deepseek.com/anthropic/ ")?.baseUrl,
        )
        // DeepSeek 的默认模型挂在**地址**上（不是协议上）：OpenAI 地址不给默认模型
        assertEquals("deepseek-flash", TranslateProtocol.OPENAI_CHAT.defaultModelFor("https://api.deepseek.com/v1"))
        assertEquals("", TranslateProtocol.OPENAI_CHAT.defaultModelFor("https://api.openai.com/v1"))
        assertEquals("", TranslateProtocol.OPENAI_CHAT.defaultModelFor("https://relay.example.com/v1"))
        assertTrue(TranslateProtocol.OPENAI_CHAT.suggestionsFor("https://relay.example.com/v1").isEmpty())
    }

    @Test
    fun `Bearer 系只补一个 Authorization 头`() {
        assertEquals(
            listOf("Authorization" to "Bearer sk-abc"),
            TranslateProtocol.OPENAI_CHAT.authHeaders("sk-abc"),
        )
        // 前后空格在补头之前就被吃掉（从剪贴板粘贴常带换行）
        assertEquals(
            listOf("Authorization" to "Bearer sk-abc"),
            TranslateProtocol.OPENAI_CHAT.authHeaders("  sk-abc\n"),
        )
    }

    @Test
    fun `Anthropic 补 x-api-key 与版本头`() {
        assertEquals(
            listOf("x-api-key" to "sk-ant"),
            TranslateProtocol.ANTHROPIC_MESSAGES.authHeaders("sk-ant"),
        )
        val headers = TranslateProtocol.ANTHROPIC_MESSAGES.requestHeaders("sk-ant", json = true)
        assertTrue("缺 x-api-key 会 401", headers.contains("x-api-key" to "sk-ant"))
        // 官方：缺 anthropic-version 直接 400，所以要自动补上（值不在界面暴露）
        assertTrue(headers.contains("anthropic-version" to "2023-06-01"))
        assertTrue(headers.contains("Content-Type" to "application/json"))
    }

    @Test
    fun `Gemini 补 x-goog-api-key 而不是查询串`() {
        for (protocol in TranslateProtocol.entries) {
            val headers = protocol.requestHeaders("k-1")
            assertEquals("$protocol 的默认头", listOf("Accept" to "application/json"), headers.take(1))
        }
        assertEquals(
            listOf("Accept" to "application/json", "x-goog-api-key" to "k-1"),
            TranslateProtocol.GEMINI.requestHeaders("k-1"),
        )
        // Key 不进 URL（查询串会进日志、进代理访问记录）
        assertTrue(!TranslateProtocol.GEMINI.modelsEndpoint("").contains("k-1"))
    }

    @Test
    fun `没有 Key 时不补鉴权头`() {
        assertEquals(listOf("Accept" to "application/json"), TranslateProtocol.GEMINI.requestHeaders("  "))
        // POST 才补 Content-Type：GET 没有请求体
        assertEquals(
            listOf("Accept" to "application/json"),
            TranslateProtocol.OPENAI_CHAT.requestHeaders("", json = false),
        )
    }

    @Test
    fun `翻译端点按协议拼上模型`() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            TranslateProtocol.OPENAI_CHAT.chatEndpoint("", ""),
        )
        // DeepSeek 现在只是一个地址（OpenAI Chat 协议 + /v1 通道）
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            TranslateProtocol.OPENAI_CHAT.chatEndpoint("https://api.deepseek.com/v1", "deepseek-flash"),
        )
        assertEquals(
            "https://api.openai.com/v1/responses",
            TranslateProtocol.OPENAI_RESPONSES.chatEndpoint("", "gpt-5"),
        )
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            TranslateProtocol.ANTHROPIC_MESSAGES.chatEndpoint("", ""),
        )
        // Gemini 的模型名在路径里；没填模型时保留占位符（不要让路径变成 :generateContent）
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-pro:generateContent",
            TranslateProtocol.GEMINI.chatEndpoint("", "gemini-2.5-pro"),
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
            TranslateProtocol.GEMINI.chatEndpoint("", "  "),
        )
    }

    @Test
    fun `接入地址已含路径前缀时不写出双份`() {
        // DeepSeek 同时提供 Anthropic 兼容入口：base = /anthropic，路径仍是 /v1/messages
        assertEquals(
            "https://api.deepseek.com/anthropic/v1/messages",
            TranslateProtocol.ANTHROPIC_MESSAGES.chatEndpoint("https://api.deepseek.com/anthropic", ""),
        )
        assertEquals(
            "https://gw.example.com/openai/v1/chat/completions",
            TranslateProtocol.OPENAI_CHAT.chatEndpoint("https://gw.example.com/openai/v1", ""),
        )
    }

    @Test
    fun `从接入地址猜协议只认确定的主机`() {
        // DeepSeek 的三条兼容通道：默认按 OpenAI Chat 认（/anthropic 那行仍是这一档，用户可再改）
        assertEquals(TranslateProtocol.OPENAI_CHAT, TranslateProtocol.guessFrom("https://api.deepseek.com/v1"))
        assertEquals(TranslateProtocol.OPENAI_CHAT, TranslateProtocol.guessFrom("api.deepseek.com/v1"))
        assertEquals(TranslateProtocol.OPENAI_CHAT, TranslateProtocol.guessFrom("https://api.openai.com/v1"))
        assertEquals(TranslateProtocol.ANTHROPIC_MESSAGES, TranslateProtocol.guessFrom("api.anthropic.com"))
        assertEquals(
            TranslateProtocol.GEMINI,
            TranslateProtocol.guessFrom("https://generativelanguage.googleapis.com/v1beta"),
        )
        // 中转 / 自建网关 / 空地址 / 只有端口：不猜（返回 null，界面也就不显示「推荐」）
        assertNull(TranslateProtocol.guessFrom("https://relay.example.com/v1"))
        assertNull(TranslateProtocol.guessFrom(""))
        assertNull(TranslateProtocol.guessFrom("localhost:8080"))
    }

    @Test
    fun `内置模型候选只挂在已知的地址行上`() {
        val deepseek = TranslateProtocol.OPENAI_CHAT.addressOf("https://api.deepseek.com/v1")!!
        assertEquals("deepseek-flash", deepseek.defaultModel)
        assertEquals(listOf("deepseek-flash", "deepseek-v4-pro"), deepseek.modelSuggestions)
        // 其它地址行的模型名变化快，不臆造候选（界面回落到「探测过才有芯片」）
        TranslateProtocol.entries.forEach { protocol ->
            protocol.addresses.filter { it.id != "deepseek" }.forEach { address ->
                assertEquals("${protocol.code}/${address.id} 不应预填模型", "", address.defaultModel)
                assertTrue("${protocol.code}/${address.id} 不应有内置候选", address.modelSuggestions.isEmpty())
            }
        }
    }
}
