package com.branchbase.ui.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 统一 diff 解析器单测。
 *
 * 输入是 GitHub compare API 的 `files[].patch`（每个文件一段 unified diff 片段），
 * 规则全部按真实 patch 文本取证：hunk 头给出后续行号的起点，`+`/`-`/空格 决定行类型，
 * `\ No newline at end of file` 等元信息行不占行号。
 */
class BranchDiffTest {

    private fun kindsOf(lines: List<DiffLine>): List<DiffLineKind> = lines.map { it.kind }

    // ── 真实样本 ──

    @Test
    fun `真实样本 新增文件首个 hunk 从新行号 1 开始`() {
        val lines = parseUnifiedDiff("@@ -0,0 +1 @@\n+## Contributing")

        assertEquals(2, lines.size)
        assertEquals(DiffLineKind.Hunk, lines[0].kind)
        assertEquals("@@ -0,0 +1 @@", lines[0].text)
        assertNull(lines[0].oldLine)
        assertNull(lines[0].newLine)
        assertEquals(DiffLineKind.Add, lines[1].kind)
        assertNull(lines[1].oldLine)
        assertEquals(1, lines[1].newLine)
        assertEquals("## Contributing", lines[1].text)
    }

    // ── 多 hunk ──

    @Test
    fun `多 hunk 的第二个 hunk 从自己的起始行号重新计数`() {
        val patch = "@@ -1,3 +1,4 @@\n a\n-b\n+c\n+d\n@@ -10,2 +11,2 @@\n e\n-f\n+g"
        val lines = parseUnifiedDiff(patch)

        assertEquals(9, lines.size)
        assertEquals(
            listOf(
                DiffLineKind.Hunk,
                DiffLineKind.Context,
                DiffLineKind.Remove,
                DiffLineKind.Add,
                DiffLineKind.Add,
                DiffLineKind.Hunk,
                DiffLineKind.Context,
                DiffLineKind.Remove,
                DiffLineKind.Add,
            ),
            kindsOf(lines),
        )

        // 第一个 hunk：a(1,1) b(2,·) c(·,2) d(·,3)
        assertEquals(1, lines[1].oldLine)
        assertEquals(1, lines[1].newLine)
        assertEquals(2, lines[2].oldLine)
        assertNull(lines[2].newLine)
        assertNull(lines[3].oldLine)
        assertEquals(2, lines[3].newLine)
        assertNull(lines[4].oldLine)
        assertEquals(3, lines[4].newLine)

        // 第二个 hunk 头保留原始文本，并从 10 / 11 起算
        assertEquals("@@ -10,2 +11,2 @@", lines[5].text)
        assertEquals(10, lines[6].oldLine)
        assertEquals(11, lines[6].newLine)
        assertEquals(11, lines[7].oldLine)
        assertNull(lines[7].newLine)
        assertNull(lines[8].oldLine)
        assertEquals(12, lines[8].newLine)
    }

    // ── 省略计数 ──

    @Test
    fun `省略计数的 hunk 头也能解析出起始行号`() {
        val lines = parseUnifiedDiff("@@ -5 +5 @@\n x")

        assertEquals(2, lines.size)
        assertEquals(DiffLineKind.Hunk, lines[0].kind)
        assertEquals("@@ -5 +5 @@", lines[0].text)
        assertEquals(DiffLineKind.Context, lines[1].kind)
        assertEquals(5, lines[1].oldLine)
        assertEquals(5, lines[1].newLine)
        assertEquals("x", lines[1].text)
    }

    // ── 行号推进 ──

    @Test
    fun `Context 行的旧新行号同步递增`() {
        val lines = parseUnifiedDiff("@@ -3,3 +7,3 @@\n a\n b\n c")

        assertEquals(4, lines.size)
        assertEquals(3, lines[1].oldLine)
        assertEquals(7, lines[1].newLine)
        assertEquals(4, lines[2].oldLine)
        assertEquals(8, lines[2].newLine)
        assertEquals(5, lines[3].oldLine)
        assertEquals(9, lines[3].newLine)
    }

    // ── CRLF ──

    @Test
    fun `CRLF 输入不残留回车符`() {
        val patch = "@@ -1,2 +1,2 @@\r\n a\r\n-b\r\n+c"
        val lines = parseUnifiedDiff(patch)

        assertEquals(4, lines.size)
        assertTrue(lines.none { it.text.contains("\r") })
        assertEquals("@@ -1,2 +1,2 @@", lines[0].text)
        assertEquals("a", lines[1].text)
        assertEquals("b", lines[2].text)
        assertEquals("c", lines[3].text)
    }

    // ── 元信息行 ──

    @Test
    fun `跳过 no newline 标记行且不占行号`() {
        val patch = "@@ -1,2 +1,2 @@\n a\n\\ No newline at end of file\n-b\n+c"
        val lines = parseUnifiedDiff(patch)

        assertEquals(4, lines.size)
        assertEquals(
            listOf(DiffLineKind.Hunk, DiffLineKind.Context, DiffLineKind.Remove, DiffLineKind.Add),
            kindsOf(lines),
        )
        assertEquals(2, lines[2].oldLine)
        assertEquals("b", lines[2].text)
        assertEquals(2, lines[3].newLine)
        assertEquals("c", lines[3].text)
    }

    @Test
    fun `跳过 diff 与 index 以及文件头三行`() {
        val patch = "diff --git a/x b/x\n" +
            "index abc123..def456 100644\n" +
            "--- a/x\n" +
            "+++ b/x\n" +
            "@@ -1 +1 @@\n" +
            "-a\n" +
            "+b"
        val lines = parseUnifiedDiff(patch)

        assertEquals(3, lines.size)
        assertEquals(
            listOf(DiffLineKind.Hunk, DiffLineKind.Remove, DiffLineKind.Add),
            kindsOf(lines),
        )
        assertEquals(1, lines[1].oldLine)
        assertEquals("a", lines[1].text)
        assertEquals(1, lines[2].newLine)
        assertEquals("b", lines[2].text)
    }

    @Test
    fun `跳过新增删除与重命名等元信息行`() {
        val patch = "diff --git a/old.txt b/new.txt\n" +
            "similarity index 90%\n" +
            "rename from old.txt\n" +
            "rename to new.txt\n" +
            "new file mode 100644\n" +
            "deleted file mode 100644\n" +
            "old mode 100644\n" +
            "new mode 100755\n" +
            "@@ -1 +1 @@\n" +
            "-a\n" +
            "+b"
        val lines = parseUnifiedDiff(patch)

        assertEquals(3, lines.size)
        assertEquals(DiffLineKind.Hunk, lines[0].kind)
    }

    // ── 空与异常输入 ──

    @Test
    fun `null 与空串返回空列表`() {
        assertEquals(emptyList<DiffLine>(), parseUnifiedDiff(null))
        assertEquals(emptyList<DiffLine>(), parseUnifiedDiff(""))
        assertEquals(emptyList<DiffLine>(), parseUnifiedDiff("   \n\r\n  "))
    }

    @Test
    fun `畸形输入不抛异常并降级为 Context`() {
        // 缺少收尾 @@ 的头、非数字计数：都按内容行处理。
        val lines = parseUnifiedDiff("@@ -a,b +c,d @@\n@@ -1,3 +1,4\n-x")

        assertEquals(3, lines.size)
        assertEquals(DiffLineKind.Context, lines[0].kind)
        assertEquals("@@ -a,b +c,d @@", lines[0].text)
        assertEquals(1, lines[0].oldLine)
        assertEquals(1, lines[0].newLine)
        assertEquals(DiffLineKind.Context, lines[1].kind)
        assertEquals("@@ -1,3 +1,4", lines[1].text)
        assertEquals(2, lines[1].oldLine)
        assertEquals(2, lines[1].newLine)
        assertEquals(DiffLineKind.Remove, lines[2].kind)
        assertEquals(3, lines[2].oldLine)
        assertEquals("x", lines[2].text)
    }

    // ── 无 hunk 头的裸行 ──

    @Test
    fun `没有 hunk 头时裸行按 Context 从 1 开始计数`() {
        val lines = parseUnifiedDiff("裸行一\n裸行二")

        assertEquals(2, lines.size)
        assertEquals(DiffLineKind.Context, lines[0].kind)
        assertEquals(1, lines[0].oldLine)
        assertEquals(1, lines[0].newLine)
        assertEquals("裸行一", lines[0].text)
        assertEquals(2, lines[1].oldLine)
        assertEquals(2, lines[1].newLine)
        assertEquals("裸行二", lines[1].text)
    }

    // ── 空行 ──

    @Test
    fun `空行视为 Context 且文本为空串`() {
        val lines = parseUnifiedDiff("@@ -1,3 +1,3 @@\n a\n\n b")

        assertEquals(4, lines.size)
        assertEquals(DiffLineKind.Context, lines[2].kind)
        assertEquals("", lines[2].text)
        assertEquals(2, lines[2].oldLine)
        assertEquals(2, lines[2].newLine)
        assertEquals(3, lines[3].oldLine)
        assertEquals(3, lines[3].newLine)
    }

    // ── 文本保真 ──

    @Test
    fun `text 保留行内与行尾原始空格`() {
        val patch = "@@ -1,1 +1,1 @@\n-    val x = 1  \n+    val x = 2  "
        val lines = parseUnifiedDiff(patch)

        assertEquals(3, lines.size)
        assertEquals("    val x = 1  ", lines[1].text)
        assertEquals("    val x = 2  ", lines[2].text)
        assertFalse(lines[1].text.contains("-"))
        assertEquals(1, lines[1].oldLine)
        assertNull(lines[1].newLine)
        assertNull(lines[2].oldLine)
        assertEquals(1, lines[2].newLine)
    }

    // ── 边界修正 ──

    @Test
    fun `结尾换行不产生幻影空行`() {
        // 拼接多段 patch 时很常见：末尾多一个 \n 不应多出一行 Context
        val withTrailing = parseUnifiedDiff("@@ -1 +1 @@\n-a\n+b\n")
        val without = parseUnifiedDiff("@@ -1 +1 @@\n-a\n+b")

        assertEquals(3, withTrailing.size)
        assertEquals(kindsOf(without), kindsOf(withTrailing))
        assertEquals(3, without.size)
    }

    @Test
    fun `hunk 内以三个连字符开头的内容行不被当成文件头`() {
        // 删除一行 SQL 注释 `-- 旧字段` 时，diff 文本就是 `--- 旧字段`
        val lines = parseUnifiedDiff("@@ -1,2 +1,1 @@\n--- 旧字段\n+新字段")

        assertEquals(3, lines.size)
        assertEquals(DiffLineKind.Remove, lines[1].kind)
        assertEquals("-- 旧字段", lines[1].text)
        assertEquals(1, lines[1].oldLine)
        assertEquals(DiffLineKind.Add, lines[2].kind)
        assertEquals("新字段", lines[2].text)
    }
}
