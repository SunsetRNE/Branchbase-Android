package com.branchbase.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮面板「快捷设置」写入口的白名单单测。
 *
 * 这个入口是**页面脚本能调到的**（`@JavascriptInterface`），所以边界必须钉死：
 * 1. 认识的键 + 合法的值 → 只改这一个字段，其余字段原样；
 * 2. 不认识的值 / 键 → 返回 null，调用方什么都不做；
 * 3. **凭据类字段永远进不来**（API Key / 模型 / 接入地址 / 服务商）——
 *    这是与「Key 绝不注入网页」同一条边界，靠白名单而不是靠自觉。
 */
class TranslateQuickSettingsTest {

    private val base = TranslateConfig(
        enabled = false,
        target = LANG_ZH,
        dual = true,
        style = TranslateConfig.STYLE_CARD,
        persist = true,
        protect = true,
        provider = TranslateProvider.MYMEMORY.code,
        apiKey = "sk-secret",
        model = "m",
        baseUrl = "https://relay.example.com/v1",
    )

    @Test
    fun `布尔键接受 1 和 0 并只改自己`() {
        val off = TranslateQuickSettings.apply(base, "dual", "0")
        assertFalse(off!!.dual)
        assertEquals(base.target, off.target)
        assertEquals(base.style, off.style)
        assertEquals(base.apiKey, off.apiKey)      // 凭据字段一动不动

        assertTrue(TranslateQuickSettings.apply(base, "dual", "1")!!.dual)
        assertTrue(TranslateQuickSettings.apply(base, "enabled", "1")!!.enabled)
        assertFalse(TranslateQuickSettings.apply(base, "persist", "0")!!.persist)
        assertFalse(TranslateQuickSettings.apply(base, "protect", "0")!!.protect)
    }

    @Test
    fun `布尔键也认字面量 true false`() {
        assertEquals(true, TranslateQuickSettings.apply(base, "dual", "true")?.dual)
        assertEquals(false, TranslateQuickSettings.apply(base, "dual", "false")?.dual)
    }

    @Test
    fun `样式只认三种`() {
        assertEquals(TranslateConfig.STYLE_PLAIN, TranslateQuickSettings.apply(base, "style", "plain")?.style)
        assertEquals(TranslateConfig.STYLE_UNDERLINE, TranslateQuickSettings.apply(base, "style", "underline")?.style)
        assertNull(TranslateQuickSettings.apply(base, "style", "neon"))
        assertNull(TranslateQuickSettings.apply(base, "style", "CARD"))   // 大小写敏感：值来自我们自己的脚本
    }

    @Test
    fun `目标语言必须是已知语言码`() {
        assertEquals(LANG_EN, TranslateQuickSettings.apply(base, "target", LANG_EN)?.target)
        assertNull(TranslateQuickSettings.apply(base, "target", "fr"))
        assertNull(TranslateQuickSettings.apply(base, "target", ""))
    }

    @Test
    fun `非法值一律拒绝`() {
        assertNull(TranslateQuickSettings.apply(base, "dual", "yes"))
        assertNull(TranslateQuickSettings.apply(base, "dual", "2"))
        assertNull(TranslateQuickSettings.apply(base, "enabled", ""))
    }

    @Test
    fun `凭据类字段不在白名单里`() {
        val forbidden = listOf("apiKey", "model", "baseUrl", "provider", "translate.deepseek.key")
        forbidden.forEach { key ->
            assertFalse("$key 不该被页面改写", TranslateQuickSettings.KEYS.contains(key))
            assertNull(TranslateQuickSettings.apply(base, key, "sk-attacker"))
        }
        // 白名单就是这六个，多一个都要先想清楚它是不是凭据
        assertEquals(setOf("enabled", "dual", "style", "target", "persist", "protect"), TranslateQuickSettings.KEYS)
    }
}
