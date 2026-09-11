package com.branchbase.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「哪一段值得翻」的判定单测。
 *
 * 判错的代价不对称：把中文段落再翻一遍会插出一段更差的中文（用户直接看到脏结果），
 * 而漏判只是少翻一段。所以这里把「不翻」的每种情况都钉住。
 */
class TranslateTextPolicyTest {

    @Test
    fun `归一化折叠空白`() {
        assertEquals("hello world", TranslateTextPolicy.normalize("  hello \n\n world  "))
        assertEquals("a b", TranslateTextPolicy.normalize("a\u3000b"))
    }

    @Test
    fun `英文段落需要翻成中文`() {
        assertTrue(TranslateTextPolicy.needsTranslation("This is a release note for the project.", LANG_ZH))
    }

    @Test
    fun `中文段落不再翻成中文`() {
        assertFalse(TranslateTextPolicy.needsTranslation("这是一段中文说明，里面有 CI 字样。", LANG_ZH))
    }

    @Test
    fun `目标英文时中文段落需要翻_纯英文不需要`() {
        assertTrue(TranslateTextPolicy.needsTranslation("这是一段中文说明。", LANG_EN))
        assertFalse(TranslateTextPolicy.needsTranslation("This is already English.", LANG_EN))
    }

    @Test
    fun `零宽字符段落视为空`() {
        assertFalse(TranslateTextPolicy.needsTranslation("\u200B\u200B\uFEFF", LANG_ZH))
    }

    @Test
    fun `纯数字_纯链接_版本号_单个提及编号都不翻`() {
        val skip = listOf(
            "1,234.56",
            "100%",
            "https://github.com/torvalds/linux",
            "v1.2.3",
            "1.0.13-rc1",
            "@torvalds",
            "#1234",
            "<style>a{}</style>",
        )
        skip.forEach {
            assertFalse("不应翻译：$it", TranslateTextPolicy.needsTranslation(it, LANG_ZH))
        }
    }

    @Test
    fun `太短或超长的段落都不翻`() {
        assertFalse(TranslateTextPolicy.needsTranslation("Hi", LANG_ZH))
        val tooLong = "word ".repeat(400)   // 2000 字符 > maxLen(1200)
        assertFalse(TranslateTextPolicy.needsTranslation(tooLong, LANG_ZH))
    }

    @Test
    fun `最长的连续拉丁字母串会被算出来`() {
        assertEquals(5, TranslateTextPolicy.longestLatinRun("中文 hello a1b2c3"))
        assertEquals(0, TranslateTextPolicy.longestLatinRun("中文标点。"))
    }

    @Test
    fun `规则可以换掉阈值而不用改判定代码`() {
        val rules = PageRules.DEFAULT.copy(latinRun = 12)
        assertFalse(TranslateTextPolicy.needsTranslation("a short note", LANG_ZH, rules))
        assertTrue(TranslateTextPolicy.needsTranslation("a considerably longer note", LANG_ZH, rules))
    }
}
