package com.branchbase.ui.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知内容预览的三个纯函数单测（[latestCommentPath] / [previewText] / [parsePreview]）。
 *
 * 只测纯函数：网络与缓存部分（[prefetchPreviews]）依赖 Context / Room / RustBridge，
 * 属于集成范畴，这里不碰 —— 但预览的**正确性风险几乎全在这三个纯函数里**：
 * - URL 形态判错 → 拉错对象（把 issue 本体当评论解析）；
 * - markdown 剥离顺序写反 → 规则互相破坏，预览里出现一堆残留符号；
 * - 截断切在代理对中间 → emoji 变乱码。
 */
class NotificationPreviewLoaderTest {

    // ── latestCommentPath：只认「评论」形态 ──

    @Test
    fun `issues_comments 形态返回 path`() {
        assertEquals(
            "/repos/SunsetRNE/Branchbase/issues/comments/12345",
            latestCommentPath("https://api.github.com/repos/SunsetRNE/Branchbase/issues/comments/12345"),
        )
    }

    @Test
    fun `pulls_comments 形态返回 path`() {
        assertEquals(
            "/repos/SunsetRNE/Branchbase/pulls/comments/7",
            latestCommentPath("https://api.github.com/repos/SunsetRNE/Branchbase/pulls/comments/7"),
        )
    }

    @Test
    fun `review comment 的 comments 形态返回 path`() {
        assertEquals(
            "/repos/o/r/comments/9",
            latestCommentPath("https://api.github.com/repos/o/r/comments/9"),
        )
    }

    @Test
    fun `查询串与锚点被丢掉`() {
        assertEquals(
            "/repos/o/r/issues/comments/5",
            latestCommentPath("https://api.github.com/repos/o/r/issues/comments/5?per_page=1#frag"),
        )
    }

    @Test
    fun `退化成 issue 或 PR 本体的 URL 不当作评论`() {
        // latest_comment_url 在「无人评论」时可能仍指向 issue 本体，这时不该发请求
        assertNull(latestCommentPath("https://api.github.com/repos/o/r/issues/42"))
        assertNull(latestCommentPath("https://api.github.com/repos/o/r/pulls/12"))
    }

    @Test
    fun `commit discussion 等非评论 URL 一律 null`() {
        assertNull(latestCommentPath("https://api.github.com/repos/o/r/commits/deadbeef"))
        assertNull(latestCommentPath("https://github.com/o/r/issues/1"))
        assertNull(latestCommentPath(""))
        assertNull(latestCommentPath("not a url"))
    }

    // ── previewText：markdown → 一行纯文本 ──

    @Test
    fun `链接只留文字_图片整段丢弃`() {
        // 链接文字里的 `#12` 不受「行首标题标记」规则影响（那条规则锚定行首且要求 # 后跟空格）
        assertEquals(
            "见 PR #12",
            previewText("见 [PR #12](https://github.com/o/r/pull/12)"),
        )
        // 图片只剩空白，trim 后为空；图片与文字混排时只留文字
        assertEquals("", previewText("![badge](https://img.shields.io/x.svg)"))
        assertEquals("构建结果", previewText("![ci](https://img.shields.io/y.svg) 构建结果"))
    }

    @Test
    fun `强调标记先长后短_不留孤立符号`() {
        assertEquals("加粗 斜体", previewText("**加粗** *斜体*"))
        assertEquals("加粗", previewText("__加粗__"))
        assertEquals("斜体", previewText("_斜体_"))
    }

    @Test
    fun `围栏代码块先于行内反引号处理`() {
        assertEquals(
            "run gradle test",
            previewText("```bash\nrun gradle test\n```"),
        )
        assertEquals("用 build 命令", previewText("用 `build` 命令"))
    }

    @Test
    fun `HTML 注释整段丢弃_标签去壳留字`() {
        assertEquals(
            "正文",
            previewText("<!-- 这是审核用的备注，不该出现在预览里 -->正文"),
        )
        // 两个注释之间的正文不能被一起吃掉（非贪婪的关键）
        assertEquals(
            "中间这句要留下",
            previewText("<!--a-->中间这句要留下<!--b-->"),
        )
        assertEquals("补充说明", previewText("<sub>补充说明</sub>"))
    }

    @Test
    fun `行首标记被剥离_但正文中的符号不受影响`() {
        assertEquals(
            "标题 引用 列表项 有序项",
            previewText("# 标题\n> 引用\n- 列表项\n1. 有序项"),
        )
        // 行首锚定的意义：3.14 / a-b 不能被当成有序列表和列表项
        assertEquals("版本 3.14 发布", previewText("版本 3.14 发布"))
        assertEquals("a-b 是范围", previewText("a-b 是范围"))
    }

    @Test
    fun `换行与连续空白折成单个空格`() {
        assertEquals("第一行 第二行", previewText("第一行\n\n\n   第二行   "))
    }

    @Test
    fun `超长按码点截断并补省略号`() {
        val long = "字".repeat(200)
        val out = previewText(long)
        assertEquals(141, out.length) // 140 个字符 + 「…」
        assertTrue(out.endsWith("…"))
    }

    @Test
    fun `emoji 不会被切坏`() {
        // 每个 emoji 是两个 UTF-16 char：按**码点**截断后应留下 5 个完整 emoji，
        // 且不能出现孤立代理字符（高位/低位数量相等才说明每个代理对都成对）
        val out = previewText("🎉".repeat(100), maxChars = 5)
        assertEquals("🎉🎉🎉🎉🎉…", out)
        assertEquals(out.count { Character.isHighSurrogate(it) }, out.count { Character.isLowSurrogate(it) })
    }

    @Test
    fun `emoji 未超长时按码点计数不截断`() {
        // 10 个 emoji = 20 个 char：按 char 数会误判为超长（maxChars=15）而截断，按码点则原样保留
        assertEquals("🎉".repeat(10), previewText("🎉".repeat(10), maxChars = 15))
    }

    @Test
    fun `未超长时不补省略号`() {
        assertEquals("刚好够长", previewText("刚好够长", maxChars = 4))
    }

    @Test
    fun `极端参数不崩`() {
        assertEquals("", previewText("内容", maxChars = 0))
        assertEquals("", previewText("内容", maxChars = -1))
        assertEquals("", previewText(""))
        assertEquals("", previewText("   \n\t  "))
    }

    // ── parsePreview：原始评论 JSON → 预览 ──

    private fun commentJson(
        login: String = "octocat",
        avatar: String = "https://avatars.githubusercontent.com/u/1",
        body: String = "看起来不错，建议补一个单测。",
        created: String = "2026-09-10T10:00:00Z",
    ) = """
        {
          "id": 12345,
          "user": {"login": "$login", "avatar_url": "$avatar"},
          "body": ${if (body == "null") "null" else "\"$body\""},
          "created_at": "$created"
        }
    """.trimIndent()

    @Test
    fun `正常评论解析出作者头像与正文`() {
        val p = parsePreview("t1", commentJson())
        assertEquals("t1", p?.threadId)
        assertEquals("octocat", p?.author)
        assertEquals("https://avatars.githubusercontent.com/u/1", p?.avatarUrl)
        assertEquals("看起来不错，建议补一个单测。", p?.body)
        assertEquals("2026-09-10T10:00:00Z", p?.createdAt)
    }

    @Test
    fun `正文里的 markdown 在解析阶段就被折成纯文本`() {
        val p = parsePreview("t2", commentJson(body = "**已修复** 见 [提交](https://x/y)"))
        assertEquals("已修复 见 提交", p?.body)
    }

    @Test
    fun `空正文或只有图片时返回 null_调用方少一个判空分支`() {
        assertNull(parsePreview("t3", commentJson(body = "")))
        assertNull(parsePreview("t3", commentJson(body = "   ")))
        assertNull(parsePreview("t3", commentJson(body = "![img](https://x/y.png)")))
        assertNull(parsePreview("t3", commentJson(body = "null")))
    }

    @Test
    fun `非法输入返回 null 而不抛异常`() {
        assertNull(parsePreview("t4", null))
        assertNull(parsePreview("t4", ""))
        assertNull(parsePreview("t4", "   "))
        assertNull(parsePreview("t4", "ERROR: 404"))
        assertNull(parsePreview("t4", "{\"body\": 不是 JSON"))
        assertNull(parsePreview("t4", "[1,2,3]"))
    }

    @Test
    fun `字段为 JSON null 时不产出字符串 null`() {
        val json = """{"user": {"login": null, "avatar_url": null}, "body": "正文", "created_at": null}"""
        val p = parsePreview("t5", json)
        assertEquals("", p?.author)
        assertEquals("", p?.avatarUrl)
        assertEquals("", p?.createdAt)
        assertEquals("正文", p?.body)
    }

    @Test
    fun `缺少 user 对象时作者为空但不影响正文`() {
        val p = parsePreview("t6", """{"body": "只有正文"}""")
        assertEquals("", p?.author)
        assertEquals("只有正文", p?.body)
    }
}
