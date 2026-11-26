package com.branchbase.translate

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 思维深度档位的**映射规则**（档位 → OpenAI 系 effort / Anthropic·Gemini 的 token 预算）。
 *
 * 这些数字是四条协议各自的「语言」：映射写错不会崩，只会让「选了低档」发出去一个
 * 服务商不认识的值 —— 所以每个档位都钉住，自定义值的分流规则（数字 / 词）也钉住。
 */
class TranslateThinkingTest {

    @Test
    fun `四档的_effort_与预算`() {
        assertEquals("", TranslateThinking.OFF.effort(""))
        assertEquals(0, TranslateThinking.OFF.budgetTokens(""))
        assertFalse(TranslateThinking.OFF.isActive(""))

        assertEquals("low", TranslateThinking.LOW.effort(""))
        assertEquals(1024, TranslateThinking.LOW.budgetTokens(""))
        assertTrue(TranslateThinking.LOW.isActive(""))

        assertEquals("high", TranslateThinking.HIGH.effort(""))
        assertEquals(4096, TranslateThinking.HIGH.budgetTokens(""))

        // OpenAI 的枚举到 high 为止：Max 在 OpenAI 系与 High 同值，靠「自定义」去填更大的词
        assertEquals("high", TranslateThinking.MAX.effort(""))
        assertEquals(16384, TranslateThinking.MAX.budgetTokens(""))
    }

    @Test
    fun `自定义值按数字或词自动分流`() {
        // 纯数字 → token 预算（Anthropic / Gemini）
        assertEquals("", TranslateThinking.CUSTOM.effort("2048"))
        assertEquals(2048, TranslateThinking.CUSTOM.budgetTokens("2048"))
        // 非数字 → effort 词（OpenAI 系）
        assertEquals("medium", TranslateThinking.CUSTOM.effort("medium"))
        assertEquals(0, TranslateThinking.CUSTOM.budgetTokens("medium"))
        // 前后空格不算值
        assertEquals("medium", TranslateThinking.CUSTOM.effort("  medium "))
        assertEquals(2048, TranslateThinking.CUSTOM.budgetTokens(" 2048 "))
        // 空 → 等于关闭（不发字段）
        assertFalse(TranslateThinking.CUSTOM.isActive("   "))
    }

    @Test
    fun `认不出来的档位回落到关闭`() {
        assertEquals(TranslateThinking.OFF, TranslateThinking.byCode(null))
        assertEquals(TranslateThinking.OFF, TranslateThinking.byCode(""))
        assertEquals(TranslateThinking.OFF, TranslateThinking.byCode("天知道"))
        assertEquals(TranslateThinking.LOW, TranslateThinking.byCode(" LOW "))
        assertEquals(TranslateThinking.default, TranslateThinking.OFF)
    }

    @Test
    fun `档位进_options_json_且探测可要求严格模式`() {
        val on = TranslateConfig(
            provider = TranslateProvider.EXTERNAL.code,
            apiKey = "sk-x",
            model = "m",
            thinking = TranslateThinking.LOW.code,
        )
        val t = JSONObject(on.engineOptionsJson()).getJSONObject("thinking")
        assertEquals("low", t.getString("effort"))
        assertEquals(1024, t.getLong("budgetTokens"))
        assertFalse("默认不是探测模式（允许去掉字段兜底）", t.getBoolean("strict"))

        val strict = JSONObject(on.engineOptionsJson(strictThinking = true)).getJSONObject("thinking")
        assertTrue("探测必须严格：失败要暴露出来", strict.getBoolean("strict"))
    }

    @Test
    fun `思维深度的探测_scope_按协议地址与模型区分`() {
        val a = TranslateSettings.thinkingScope(TranslateProtocol.OPENAI_CHAT, "https://api.openai.com/v1", "gpt-x")
        val b = TranslateSettings.thinkingScope(TranslateProtocol.OPENAI_CHAT, "https://api.openai.com/v1", "gpt-y")
        val c = TranslateSettings.thinkingScope(TranslateProtocol.OPENAI_CHAT, "https://api.deepseek.com/v1", "gpt-x")
        assertEquals(3, setOf(a, b, c).size)
        // 尾斜杠 / 大小写不产生新 scope；空地址等同官方地址
        assertEquals(
            a,
            TranslateSettings.thinkingScope(TranslateProtocol.OPENAI_CHAT, " https://api.openai.com/v1/ ", "gpt-x"),
        )
        assertEquals(
            TranslateSettings.thinkingScope(TranslateProtocol.OPENAI_CHAT, "", "gpt-x"),
            TranslateSettings.thinkingScope(TranslateProtocol.OPENAI_CHAT, "https://api.openai.com/v1", "gpt-x"),
        )
    }
}

/**
 * 自定义值的**即时校验**（[TranslateThinking.checkCustom]）。
 *
 * 最要紧的是「填了但会被忽略」这一类：它不会报错、也不会发字段，用户以为开了思维深度。
 */
class TranslateThinkingCustomCheckTest {

    private fun check(value: String, protocol: TranslateProtocol) =
        TranslateThinking.checkCustom(value, protocol)

    @Test
    fun `空值等同关闭`() {
        assertEquals(CustomValueIssue.EMPTY, check("", TranslateProtocol.ANTHROPIC_MESSAGES))
        assertEquals(CustomValueIssue.EMPTY, check("   ", TranslateProtocol.OPENAI_CHAT))
    }

    @Test
    fun `数字只在预算系协议下有效`() {
        assertEquals(CustomValueIssue.OK_BUDGET, check("2048", TranslateProtocol.ANTHROPIC_MESSAGES))
        assertEquals(CustomValueIssue.OK_BUDGET, check("2048", TranslateProtocol.GEMINI))
        // OpenAI 系只认 effort 词：数字会被忽略（等同关闭）
        assertEquals(CustomValueIssue.DIGITS_IGNORED, check("2048", TranslateProtocol.OPENAI_CHAT))
        assertEquals(CustomValueIssue.DIGITS_IGNORED, check("2048", TranslateProtocol.OPENAI_RESPONSES))
    }

    @Test
    fun `预算的下限与上限`() {
        // Anthropic 要求 ≥1024
        assertEquals(CustomValueIssue.BUDGET_TOO_SMALL, check("512", TranslateProtocol.ANTHROPIC_MESSAGES))
        assertEquals(CustomValueIssue.OK_BUDGET, check("1024", TranslateProtocol.ANTHROPIC_MESSAGES))
        assertEquals(CustomValueIssue.OK_BUDGET, check("32768", TranslateProtocol.GEMINI))
        assertEquals(CustomValueIssue.BUDGET_TOO_LARGE, check("65536", TranslateProtocol.GEMINI))
    }

    @Test
    fun `词只在_effort_系协议下有效`() {
        assertEquals(CustomValueIssue.OK_EFFORT, check("medium", TranslateProtocol.OPENAI_CHAT))
        assertEquals(CustomValueIssue.OK_EFFORT, check(" HIGH ", TranslateProtocol.OPENAI_RESPONSES))
        // Anthropic / Gemini 只认 token 数：词会被忽略（等同关闭）
        assertEquals(CustomValueIssue.WORD_IGNORED, check("medium", TranslateProtocol.ANTHROPIC_MESSAGES))
        assertEquals(CustomValueIssue.WORD_IGNORED, check("medium", TranslateProtocol.GEMINI))
    }

    @Test
    fun `未知_effort_词给出警告但不算错`() {
        assertEquals(CustomValueIssue.UNKNOWN_EFFORT, check("疯狂思考", TranslateProtocol.OPENAI_CHAT))
        assertEquals(CustomValueIssue.UNKNOWN_EFFORT, check("highest", TranslateProtocol.OPENAI_CHAT))
    }
}
