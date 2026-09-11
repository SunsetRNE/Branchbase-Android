package com.branchbase.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 翻译分片单测。
 *
 * 为什么值得测：服务端（MyMemory）单次上限 500 字符，超了**不会报错**，
 * 而是把一条英文告警当成「译文」返回（`QUERY LENGTH LIMIT DONE...`），
 * 页面上就出现「译文是一句英文错误」。分片是唯一挡住这种情况的地方。
 */
class TextSegmenterTest {

    @Test
    fun `短文本不分片`() {
        assertEquals(listOf("hello world"), chunk("hello world", 450))
    }

    @Test
    fun `按句末切分_不在句子中间断开`() {
        val text = "First sentence. Second sentence! Third one?"
        val parts = chunk(text, 24)
        assertTrue("应至少切成 2 片，实际 ${parts.size}", parts.size >= 2)
        parts.forEach { assertTrue("单片不能超限：${it.length}", it.length <= 24) }
        // 每片都应该是完整句子（以句末标点或文本结尾收尾）
        parts.dropLast(1).forEach { assertTrue("不应在句子中间断开：$it", it.matches(Regex(".*[.!?。！？]$"))) }
    }

    @Test
    fun `中文句号同样作为切分点`() {
        val text = "第一句话。第二句话！第三句话？"
        val parts = chunk(text, 12)
        assertTrue(parts.size >= 2)
        parts.forEach { assertTrue(it.length <= 12) }
    }

    @Test
    fun `单句超限时退化到按空格切`() {
        val text = "word ".repeat(50).trim()   // 250 字符、无句末标点
        val parts = chunk(text, 60)
        assertTrue(parts.size >= 4)
        parts.forEach { assertTrue("不应超限：${it.length}", it.length <= 60) }
        assertTrue(parts.none { it.isBlank() })
    }

    @Test
    fun `连空格都没有的长串硬切且不丢字符`() {
        val text = "a".repeat(100)
        val parts = chunk(text, 30)
        assertEquals(4, parts.size)
        assertEquals(text, parts.joinToString(""))
    }

    @Test
    fun `切出来的片段拼回去内容不丢`() {
        val text = "One. Two. Three. Four. Five. Six. Seven. Eight."
        val parts = chunk(text, 18)
        // 空白会被 normalize，比较去掉空白后的内容
        assertEquals(text.replace(" ", ""), parts.joinToString("").replace(" ", ""))
    }
}
