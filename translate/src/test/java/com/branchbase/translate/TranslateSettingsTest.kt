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
            provider = TranslateProvider.DEEPSEEK.code,
            apiKey = "sk-super-secret-value",
            model = "some-model",
            baseUrl = "https://relay.example.com/v1",
        )

        // 页面脚本是注入到 GitHub HTML 里执行的，Key 一旦进去就等于交给页面
        val page = TranslateSettings.pageConfigJson(config)
        assertFalse("页面注入串里出现了 API Key：$page", page.contains("sk-super-secret-value"))
        assertFalse(page.contains("apiKey"))
        assertFalse(page.contains("relay.example.com"))
        // 非机密的「服务商代号」要注入：悬浮面板要显示当前用的是哪家服务
        assertEquals(TranslateProvider.DEEPSEEK.code, JSONObject(page).getString("provider"))

        // 凭据只走 JNI 选项 JSON
        val options = JSONObject(config.engineOptionsJson())
        assertEquals(TranslateProvider.DEEPSEEK.code, options.getString("provider"))
        assertEquals("sk-super-secret-value", options.getString("apiKey"))
        assertEquals("some-model", options.getString("model"))
        assertEquals("https://relay.example.com/v1", options.getString("baseUrl"))
    }

    @Test
    fun `选了需要 Key 的后端但没填时_ready 为 false`() {
        assertTrue(TranslateConfig.DEFAULT.ready)   // 默认 MyMemory 不需要 Key
        assertFalse(TranslateConfig(provider = TranslateProvider.DEEPSEEK.code).ready)
        assertFalse(TranslateConfig(provider = TranslateProvider.DEEPSEEK.code, apiKey = "   ").ready)
        assertTrue(TranslateConfig(provider = TranslateProvider.DEEPSEEK.code, apiKey = "sk-x").ready)
        // 认不出来的后端码回落到默认（不需要 Key）
        assertTrue(TranslateConfig(provider = "天知道").ready)
        assertEquals(TranslateProvider.MYMEMORY, TranslateConfig(provider = "天知道").providerKind)
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
