package com.branchbase.ui.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Markdown 块级 / 行内解析单测。
 *
 * 这一层的正确性靠「肉眼在手机上看」是不够的：围栏代码块、任务清单、行内代码
 * 三种语法会互相吞并（代码里的 `*` 被当斜体、代码里的 `#` 被当标题），
 * 而这几个 case 恰好是 issue 评论里最常见的（贴日志、贴命令、勾选清单）。
 */
class MarkdownBodyTest {

    // ───────────────── 块级 ─────────────────

    @Test
    fun `标题按级别解析`() {
        val blocks = parseMarkdownBlocks("### 问题\n\n## 建议")
        assertEquals(2, blocks.size)
        assertEquals(MdBlock.Heading(3, "问题"), blocks[0])
        assertEquals(MdBlock.Heading(2, "建议"), blocks[1])
    }

    @Test
    fun `围栏代码块内部不再解析其它语法`() {
        val src = "```kotlin\n// ### 不是标题\n- 不是列表\nval a = *b*\n```"
        val blocks = parseMarkdownBlocks(src)
        assertEquals(1, blocks.size)
        val code = blocks[0] as MdBlock.Code
        assertEquals("kotlin", code.lang)
        assertTrue(code.code.contains("### 不是标题"))
        assertTrue(code.code.contains("- 不是列表"))
        assertTrue(code.code.contains("val a = *b*"))
    }

    @Test
    fun `任务清单区分勾选状态`() {
        val blocks = parseMarkdownBlocks("- [x] 已完成\n- [ ] 未完成")
        assertEquals(MdBlock.Task(true, "已完成", 0), blocks[0])
        assertEquals(MdBlock.Task(false, "未完成", 1), blocks[1])
    }

    @Test
    fun `无序与有序列表分别解析`() {
        val blocks = parseMarkdownBlocks("- 一\n- 二\n\n1. 甲\n2. 乙")
        assertEquals(MdBlock.Bullet("一"), blocks[0])
        assertEquals(MdBlock.Bullet("二"), blocks[1])
        assertEquals(MdBlock.Ordered(1, "甲"), blocks[2])
        assertEquals(MdBlock.Ordered(2, "乙"), blocks[3])
    }

    @Test
    fun `引用与分隔线`() {
        val blocks = parseMarkdownBlocks("> 参考\n\n---")
        assertEquals(MdBlock.Quote("参考"), blocks[0])
        assertEquals(MdBlock.Divider, blocks[1])
    }

    @Test
    fun `连续普通行合并为一段`() {
        val blocks = parseMarkdownBlocks("第一行\n第二行\n\n新段落")
        assertEquals(2, blocks.size)
        assertEquals(MdBlock.Paragraph("第一行 第二行"), blocks[0])
        assertEquals(MdBlock.Paragraph("新段落"), blocks[1])
    }

    @Test
    fun `列表项的缩进续行并回该项`() {
        val blocks = parseMarkdownBlocks("- 第一句\n  第二句")
        assertEquals(1, blocks.size)
        assertEquals(MdBlock.Bullet("第一句 第二句"), blocks[0])
    }

    @Test
    fun `空输入不产生块`() {
        assertTrue(parseMarkdownBlocks("").isEmpty())
        assertTrue(parseMarkdownBlocks("\n\n  \n").isEmpty())
    }

    @Test
    fun `勾选回写只改目标行且保留缩进与原文字`() {
        val src = "说明\n\n- [ ] 甲\n- [x] 乙\n  - [ ] 缩进项"
        val a = toggleTaskLine(src, 2, true)
        assertTrue(a.contains("- [x] 甲"))
        assertTrue(a.contains("- [x] 乙"))
        assertTrue(a.contains("  - [ ] 缩进项"))
        assertEquals(src, toggleTaskLine(src, 0, true))          // 非任务行原样返回
        assertEquals(src, toggleTaskLine(src, 99, true))         // 越界原样返回
        val b = toggleTaskLine(a, 3, false)
        assertTrue(b.contains("- [ ] 乙"))
    }

    // ───────────────── 行内 ─────────────────

    @Test
    fun `粗体与斜体去掉标记并带样式`() {
        val bold = inlineMarkdown("这是 **粗体** 文本")
        assertEquals("这是 粗体 文本", bold.text)
        assertTrue(bold.spanStyles.any { it.item.fontWeight == androidx.compose.ui.text.font.FontWeight.Bold })

        val italic = inlineMarkdown("这是 *斜体* 文本")
        assertEquals("这是 斜体 文本", italic.text)
        assertTrue(italic.spanStyles.any { it.item.fontStyle == androidx.compose.ui.text.font.FontStyle.Italic })
    }

    @Test
    fun `行内代码原样保留且不被其它规则改写`() {
        val a = inlineMarkdown("执行 `rm -rf *` 即可")
        assertEquals("执行 rm -rf * 即可", a.text)
        assertTrue(a.spanStyles.any { it.item.fontFamily == androidx.compose.ui.text.font.FontFamily.Monospace })
        // 代码里的 `*` 不能把后面的文字吃成斜体
        assertFalse(a.spanStyles.any { it.item.fontStyle == androidx.compose.ui.text.font.FontStyle.Italic })
    }

    @Test
    fun `链接只保留可见文字`() {
        val a = inlineMarkdown("见 [文档](https://example.com/a_b)")
        assertEquals("见 文档", a.text)
    }

    @Test
    fun `提及与编号高亮`() {
        val a = inlineMarkdown("cc @linzhi 看下 #129")
        assertEquals("cc @linzhi 看下 #129", a.text)
        assertTrue(a.spanStyles.size >= 2)
    }

    @Test
    fun `源码里的 html 尖括号不会被当成标记`() {
        val a = inlineMarkdown("a < b && c > d")
        assertEquals("a < b && c > d", a.text)
    }
}
