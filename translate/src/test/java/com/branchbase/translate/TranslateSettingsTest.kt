package com.branchbase.translate

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置注入契约单测。
 *
 * 「页面脚本能不能拿到正确的配置」是这条链路上最容易静默坏掉的一环：
 * 键名拼错、正则转义错，页面不会报错，只会「翻译看起来不太对」。
 * 这里直接按 JS 的读法把注入的 JSON 拆开验一遍。
 */
class TranslateSettingsTest {

    @Test
    fun `注入的 JSON 键与页面脚本约定一致`() {
        val json = TranslateSettings.pageConfigJson(
            TranslateConfig(target = LANG_EN, style = TranslateConfig.STYLE_PLAIN, persist = false),
        )
        val o = JSONObject(json)

        assertFalse(o.getBoolean("enabled"))
        assertEquals("zh-CN", o.getString("from"))   // 目标英文 → 源语言中文
        assertEquals("en", o.getString("to"))
        assertEquals("plain", o.getString("style"))
        assertTrue(o.getBoolean("protect"))

        val rules = o.getJSONObject("rules")
        assertEquals(2, rules.getInt("minLen"))
        assertEquals(1200, rules.getInt("maxLen"))
        assertEquals(3, rules.getInt("latinRun"))
        assertEquals(4, rules.getInt("minHan"))
        assertEquals(5000, rules.getInt("immediateLimit"))
        assertEquals(0.5, rules.getDouble("hanRatioMax"), 0.0001)
    }

    @Test
    fun `跳过规则以正则源码注入且仍可编译`() {
        val rules = JSONObject(PageRules.DEFAULT.toJson())
        val patterns = rules.getJSONArray("skipPatterns")
        assertTrue(patterns.length() >= 5)

        // JavaScript 侧会用 new RegExp(源码) 编译；这里用 Kotlin 编译一遍，
        // 转义写错（比如 JSON 里少了一层反斜杠）会在这里直接暴露
        val compiled = (0 until patterns.length()).map { Regex(patterns.getString(it)) }
        assertTrue(compiled.any { it.containsMatchIn("1,234.56") })
        assertTrue(compiled.any { it.containsMatchIn("https://github.com/a/b") })
        assertTrue(compiled.any { it.containsMatchIn("@torvalds") })
    }

    @Test
    fun `注入脚本是可直接执行的一行`() {
        val script = TranslateSettings.injectScript(TranslateConfig.DEFAULT)
        assertTrue(script.startsWith("window.__bbTranslate = {"))
        assertTrue(script.endsWith("};"))
    }

    @Test
    fun `页面注入里绝不能出现_API_Key`() {
        val config = TranslateConfig(
            provider = TranslateProvider.EXTERNAL.code,
            apiKey = "sk-super-secret-value",
            model = "some-model",
            baseUrl = "https://relay.example.com/v1",
        )

        // 页面脚本是注入到 GitHub HTML 里执行的，Key 一旦进去就等于交给页面
        val page = TranslateSettings.pageConfigJson(config)
        assertFalse("页面注入串里出现了 API Key：$page", page.contains("sk-super-secret-value"))
        assertFalse(page.contains("apiKey"))
        assertFalse(page.contains("relay.example.com"))

        // 凭据只走 JNI 选项 JSON
        val options = JSONObject(config.engineOptionsJson())
        assertEquals(TranslateProvider.EXTERNAL.code, options.getString("provider"))
        assertEquals("sk-super-secret-value", options.getString("apiKey"))
        assertEquals("some-model", options.getString("model"))
        assertEquals("https://relay.example.com/v1", options.getString("baseUrl"))
        // 协议也在 options 里（Rust 现在是忽略未知字段，多协议那一轮直接读这个键）
        assertEquals(TranslateProtocol.default.code, options.getString("protocol"))
    }

    @Test
    fun `协议进 options JSON 并进缓存变体`() {
        val anthropic = TranslateConfig(
            provider = TranslateProvider.EXTERNAL.code,
            apiKey = "sk-x",
            baseUrl = "https://api.anthropic.com",
            protocol = TranslateProtocol.ANTHROPIC_MESSAGES.code,
        )
        val options = JSONObject(anthropic.engineOptionsJson())
        assertEquals("anthropic-messages", options.getString("protocol"))

        // 换协议 = 换方言，译文可能不同，所以缓存键必须跟着变（否则两个方言共用一份译文）
        val other = anthropic.copy(protocol = TranslateProtocol.GEMINI.code)
        assertTrue(anthropic.cacheVariant() != other.cacheVariant())
        assertEquals(TranslateProtocol.GEMINI, other.protocolKind)
        // 脏值回落默认，且落盘值被规范化（byCode → code）
        assertEquals(TranslateProtocol.OPENAI_CHAT, anthropic.copy(protocol = "天知道").protocolKind)
    }

    @Test
    fun `选了需要 Key 的后端但没填时_ready 为 false`() {
        assertTrue(TranslateConfig.DEFAULT.ready)   // 默认 MyMemory 不需要 Key
        assertFalse(TranslateConfig(provider = TranslateProvider.EXTERNAL.code).ready)
        assertFalse(TranslateConfig(provider = TranslateProvider.EXTERNAL.code, apiKey = "   ").ready)
        assertTrue(TranslateConfig(provider = TranslateProvider.EXTERNAL.code, apiKey = "sk-x").ready)
        // 认不出来的后端码回落到默认（不需要 Key）
        assertTrue(TranslateConfig(provider = "天知道").ready)
        assertEquals(TranslateProvider.MYMEMORY, TranslateConfig(provider = "天知道").providerKind)
    }

    @Test
    fun `模型列表按协议与地址分桶_换档不串模型`() {
        val chatOpenAi = TranslateSettings.modelsScope(TranslateProtocol.OPENAI_CHAT, "https://api.openai.com/v1")
        val chatDeepSeek = TranslateSettings.modelsScope(TranslateProtocol.OPENAI_CHAT, "https://api.deepseek.com/v1")
        val anthropic = TranslateSettings.modelsScope(TranslateProtocol.ANTHROPIC_MESSAGES, "https://api.anthropic.com")
        // 协议不同 / 地址不同 → 不同的桶（1.4.53 的全局一份会把这些模型混在一起）
        assertEquals(3, setOf(chatOpenAi, chatDeepSeek, anthropic).size)
        // 尾斜杠与大小写不该产生新桶；空地址等同官方地址
        assertEquals(
            chatOpenAi,
            TranslateSettings.modelsScope(TranslateProtocol.OPENAI_CHAT, " https://api.openai.com/v1/ "),
        )
        assertEquals(
            chatOpenAi,
            TranslateSettings.modelsScope(TranslateProtocol.OPENAI_CHAT, ""),
        )
    }

    @Test
    fun `老配置_deepseek_且地址留空时补成_deepseek_v1`() {
        // 老语义：DeepSeek 的 Key + DeepSeek 的地址
        assertEquals("https://api.deepseek.com/v1", TranslateSettings.migrateLegacyBaseUrl("deepseek", ""))
        assertEquals("https://api.deepseek.com/v1", TranslateSettings.migrateLegacyBaseUrl(" DeepSeek ", "  "))
        // 用户自己填过地址 → 一字不改（可能是他的中转）
        assertEquals(
            "https://relay.example.com/v1",
            TranslateSettings.migrateLegacyBaseUrl("deepseek", "https://relay.example.com/v1"),
        )
        // 新配置（协议已不是 deepseek）→ 不迁移
        assertEquals("", TranslateSettings.migrateLegacyBaseUrl("openai-chat", ""))
        assertEquals("", TranslateSettings.migrateLegacyBaseUrl(null, ""))
    }

    @Test
    fun `未知语言码回落到默认目标语言`() {
        val c = TranslateConfig(target = "xx-YY")
        assertEquals(TranslateLang.ZH, c.targetLang)
        assertEquals("en", c.source)
    }

    @Test
    fun `目标语言与源语言互为反向`() {
        assertEquals(LANG_ZH, TranslateLang.sourceOf(LANG_EN))
        assertEquals(LANG_EN, TranslateLang.sourceOf(LANG_ZH))
        assertEquals(TranslateLang.EN, TranslateLang.byCode("EN"))   // 大小写不敏感
    }
}
