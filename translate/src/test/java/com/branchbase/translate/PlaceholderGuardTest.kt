package com.branchbase.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 占位符保护单测。
 *
 * 这一层坏掉的样子特别隐蔽：译文看着「通顺」，只是里面的链接指向了别处、
 * `@用户名` 少了几个字符、`#123` 变成了 `#1,234`。所以每个模式都要有用例。
 */
class PlaceholderGuardTest {

    @Test
    fun `URL 被保护且能原样还原`() {
        val src = "See https://github.com/torvalds/linux for details"
        val g = PlaceholderGuard.protect(src)
        assertEquals(1, g.tokens.size)
        assertEquals("See ⟦0⟧ for details", g.text)
        assertFalse("占位符不应把 URL 留在待译文本里", g.text.contains("github.com"))

        assertEquals("详见 https://github.com/torvalds/linux", PlaceholderGuard.restore("详见 ⟦0⟧", g.tokens))
    }

    @Test
    fun `提及_编号_邮箱_模板变量都各占一枚`() {
        val src = "ping @alice and @bob-2 about #42, mail dev@example.com, var \${name}"
        val g = PlaceholderGuard.protect(src)
        // 规则按优先级依次替换：邮箱在 @提及 之前，所以 dev@ 先拿到 0 号
        assertEquals(listOf("dev@example.com", "@alice", "@bob-2", "#42", "\${name}"), g.tokens)
        assertEquals(src, PlaceholderGuard.restore(g.text, g.tokens))
    }

    @Test
    fun `提交 SHA 被保护_但纯字母单词不误伤`() {
        val sha = PlaceholderGuard.protect("fix 3f2a1b9c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f90")
        assertEquals(listOf("3f2a1b9c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f90"), sha.tokens)

        // added / beefed 这类纯字母词必须原样保留（否则译文里会出现莫名其妙的占位符）
        val words = PlaceholderGuard.protect("added beefed deadbeef")
        assertTrue(words.isEmpty)
    }

    @Test
    fun `行内代码被保护`() {
        val g = PlaceholderGuard.protect("run `cargo build --release` first")
        assertEquals(listOf("`cargo build --release`"), g.tokens)
    }

    @Test
    fun `还原容忍服务端插入空白`() {
        val g = PlaceholderGuard.protect("hello @alice")
        // 部分服务端会把 ⟦0⟧ 拆成「⟦ 0 ⟧」
        val mangled = g.text.replace("⟦", "⟦ ").replace("⟧", " ⟧")
        assertEquals("你好 hello @alice", PlaceholderGuard.restore("你好 $mangled", g.tokens))
    }

    @Test
    fun `占位符丢失时返回 null 交给上层重译`() {
        val g = PlaceholderGuard.protect("hello @alice")
        assertNull(PlaceholderGuard.restore("你好", g.tokens))
    }

    @Test
    fun `服务端把占位符改成了别的编号也判失败`() {
        val g = PlaceholderGuard.protect("hello @alice")
        assertNull(PlaceholderGuard.restore("你好 ⟦0⟧⟦7⟧", g.tokens))
    }

    @Test
    fun `整段只有标记时 hasContent 为 false`() {
        assertFalse(PlaceholderGuard.protect("https://example.com/a/b").hasContent)
        assertTrue(PlaceholderGuard.protect("visit https://example.com now").hasContent)
    }

    /**
     * 部分还原：判定引擎用它把「守卫坐标里的片段」还原回原文。
     *
     * 一个片段往往只用到整段里的某一两枚占位符，用 [PlaceholderGuard.restore] 会因为
     * 「其余占位符没出现」而判失败 —— 那样混排段落里的 `See ⟦0⟧ here` 这类片段
     * 就永远进不了匹配性翻译。
     */
    @Test
    fun `部分还原只要求出现的占位符对得上`() {
        val g = PlaceholderGuard.protect("See https://example.com/a and @alice for details")
        assertEquals(listOf("https://example.com/a", "@alice"), g.tokens)

        // 只含 0 号占位符的片段：1 号没出现不算丢
        assertEquals("https://example.com/a", PlaceholderGuard.restorePartial("⟦0⟧", g.tokens))
        // 一个占位符都没有的片段：原样返回
        assertEquals("plain text", PlaceholderGuard.restorePartial("plain text", g.tokens))
    }

    @Test
    fun `部分还原同样对越界与残留判失败`() {
        val g = PlaceholderGuard.protect("See https://example.com/a for details")
        // 编号越界：对不上就是不还原（宁可不翻，也不要插一段带 ⟦7⟧ 的文字）
        assertNull(PlaceholderGuard.restorePartial("⟦7⟧", g.tokens))
        // 还原后仍残留 ⟦…⟧（服务端可能把 ⟦ 归一到别的括号）
        assertNull(PlaceholderGuard.restorePartial("⟦abc⟧", g.tokens))
    }
}
