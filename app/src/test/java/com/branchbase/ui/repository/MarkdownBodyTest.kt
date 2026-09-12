package com.branchbase.ui.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.text.LinkAnnotation

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
    fun `有序列表被段落打断后重新从 1 编号`() {
        // 原来序号数的是「全文档 Ordered 总数」，第二段列表会从 3 接着编
        val blocks = parseMarkdownBlocks("1. 甲\n2. 乙\n\n中间一段\n\n1. 丙")
        val ordered = blocks.filterIsInstance<MdBlock.Ordered>()
        assertEquals(listOf(1, 2), ordered.take(2).map { it.index })
        assertEquals(1, ordered.last().index)
    }

    @Test
    fun `连续引用行合并成一段引用`() {
        // 逐行成块时多行引用会画出多根竖条，看起来像互不相干的几段
        val blocks = parseMarkdownBlocks("> 第一句\n> 第二句\n\ntext")
        assertEquals(MdBlock.Quote("第一句 第二句"), blocks[0])
        assertEquals(2, blocks.size)
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

    @Test
    fun `围栏收尾按 CommonMark 判定`() {
        // 内层 ```` ```js ```` 带 info string，不能当成收尾；缩进 ≤3 的收尾围栏要能收尾
        val indentedClose = parseMarkdownBlocks("```md\n```js\nfoo\n  ```")
        assertEquals(1, indentedClose.size)
        val code = indentedClose[0] as MdBlock.Code
        assertEquals("md", code.lang)
        assertTrue(code.code.contains("```js"))
        assertTrue(code.code.contains("foo"))

        // 收尾围栏的反引号数不能少于开围栏：4 个反引号里的 3 个反引号只是内容
        val fourTicks = parseMarkdownBlocks("````md\n```js\n````")
        assertEquals(1, fourTicks.size)
        assertTrue((fourTicks[0] as MdBlock.Code).code.contains("```js"))
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

    // ───────────────── 可点击链接（P0：解析器以前从不产生 link annotation） ─────────────────

    @Test
    fun `链接带上可点击的 LinkAnnotation`() {
        val base = MarkdownLinkBase("https://github.com", "o", "r")
        val text = inlineMarkdown("[文档](./docs/a.md)", linkBase = base, onLinkClick = {})
        assertEquals("文档", text.text)
        val link = text.getLinkAnnotations(0, text.length).firstOrNull()?.item as? LinkAnnotation.Clickable
        assertEquals("https://github.com/o/r/docs/a.md", link?.tag)
    }

    @Test
    fun `提及与编号补全成完整 URL`() {
        val base = MarkdownLinkBase("https://github.com", "o", "r")
        val mention = inlineMarkdown("cc @octocat", linkBase = base, onLinkClick = {})
        assertEquals(
            "https://github.com/octocat",
            (mention.getLinkAnnotations(0, mention.length).first().item as LinkAnnotation.Clickable).tag,
        )
        val issue = inlineMarkdown("看 #129", linkBase = base, onLinkClick = {})
        assertEquals(
            "https://github.com/o/r/issues/129",
            (issue.getLinkAnnotations(0, issue.length).first().item as LinkAnnotation.Clickable).tag,
        )
    }

    @Test
    fun `没有链接基准时只给样式不产生 annotation`() {
        val text = inlineMarkdown("cc @octocat", onLinkClick = {})
        assertEquals("cc @octocat", text.text)
        assertTrue(text.getLinkAnnotations(0, text.length).isEmpty())
    }
}
