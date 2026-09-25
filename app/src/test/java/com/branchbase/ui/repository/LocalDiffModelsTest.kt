package com.branchbase.ui.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地 diff 的解析 / 拆分 / 摊平（[parseLocalDiff] · [splitPatchByFile] · [localDiffRows]）。
 *
 * ## 两个 fixture 是**真跑出来的**，不是手写的
 *
 * 它们由 `core/src/git/mod.rs` 的 `diff_worktree` 在临时仓库上真跑一遍、原样抄回来的
 * （1.0.99 取证）。手写一份「我以为引擎会吐什么」的 JSON 是这类解析测试最容易自欺的地方 ——
 * 而这一轮正是靠真跑才发现了两件手写绝对发现不了的事：
 *
 * 1. **未跟踪文件默认没有 patch**：`files` 里有它（状态 A），patch 里却没那一段 ——
 *    于是「按下标对齐」会把 mod.txt 的差异画到 bin.dat 名下。引擎侧因此补了
 *    `show_untracked_content(true)`（见那个函数与它的 cargo 测试）；
 * 2. **二进制文件的那一段没有 hunk**，只有一句 `Binary files … differ` ——
 *    直接丢给 `parseUnifiedDiff` 会画出一行假的行号 + 一句英文提示。
 *
 * ## 为什么这些必须在 JVM 侧再钉一遍
 *
 * 引擎钉的是「它吐什么」，这里钉的是「上层怎么读」：拆段、按下标对齐、过滤到某一个文件、
 * 摊成可懒加载的行。读错的后果全都只有真机上看得见 —— 差异画在别人的文件名下、
 * 或者点开一片空白。
 */
class LocalDiffModelsTest {

    /** 现场取证 ①：一个已跟踪文件改动 + 一个未跟踪文本文件 + 一个未跟踪二进制文件。 */
    private val worktreeFixture = """
        {"files":[{"additions":0,"deletions":0,"path":"bin.dat","status":"A"},{"additions":2,"deletions":1,"path":"mod.txt","status":"M"},{"additions":1,"deletions":0,"path":"new.txt","status":"A"}],"patch":"diff --git a/bin.dat b/bin.dat\nnew file mode 100644\nindex 0000000..0df5c99\nBinary files /dev/null and b/bin.dat differ\ndiff --git a/mod.txt b/mod.txt\nindex 1802a74..d352530 100644\n--- a/mod.txt\n+++ b/mod.txt\n@@ -1,3 +1,4 @@\n aaa\n-bbb\n+BBB\n ccc\n+ddd\ndiff --git a/new.txt b/new.txt\nnew file mode 100644\nindex 0000000..4858e59\n--- /dev/null\n+++ b/new.txt\n@@ -0,0 +1 @@\n+全新的\n","truncated":false}
    """.trimIndent()

    /** 现场取证 ②：两个已跟踪文件都改过 —— 多文件 patch 的真实形状。 */
    private val twoFilesFixture = """
        {"files":[{"additions":1,"deletions":1,"path":"one.txt","status":"M"},{"additions":1,"deletions":0,"path":"two.txt","status":"M"}],"patch":"diff --git a/one.txt b/one.txt\nindex 1191247..9e77aaa 100644\n--- a/one.txt\n+++ b/one.txt\n@@ -1,2 +1,2 @@\n 1\n-2\n+XX\ndiff --git a/two.txt b/two.txt\nindex 422c2b7..de98044 100644\n--- a/two.txt\n+++ b/two.txt\n@@ -1,2 +1,3 @@\n a\n b\n+c\n","truncated":false}
    """.trimIndent()

    // ── 拆段 ──

    @Test
    fun `多文件 patch 按 diff_git 边界拆开`() {
        val sections = splitPatchByFile(
            """
            diff --git a/one.txt b/one.txt
            index 1..2 100644
            --- a/one.txt
            +++ b/one.txt
            @@ -1,2 +1,2 @@
             1
            -2
            +XX
            diff --git a/two.txt b/two.txt
            index 3..4 100644
            --- a/two.txt
            +++ b/two.txt
            @@ -1,2 +1,3 @@
             a
             b
            +c
            """.trimIndent(),
        )
        assertEquals(2, sections.size)
        assertTrue(sections[0].startsWith("diff --git a/one.txt"))
        assertTrue(sections[1].startsWith("diff --git a/two.txt"))
        // 拆开之后每段都能被单文件解析器正确读懂 —— 这正是要拆的理由：
        // 整份丢给 parseUnifiedDiff，第二个文件的 `--- a/two.txt` 会被当成「删掉一行 -- a/two.txt」
        assertEquals(listOf("-2", "+XX"), sections[0].let { s ->
            parseUnifiedDiff(s).filter { it.kind == DiffLineKind.Add || it.kind == DiffLineKind.Remove }
                .map { (if (it.kind == DiffLineKind.Add) "+" else "-") + it.text }
        })
        assertEquals(1, parseUnifiedDiff(sections[1]).count { it.kind == DiffLineKind.Add })
    }

    @Test
    fun `空 patch 拆出空列表，第一段之前的内容不算任何人的`() {
        assertEquals(emptyList<String>(), splitPatchByFile(""))
        assertEquals(emptyList<String>(), splitPatchByFile("   \n"))
        // 没有 diff --git 开头的裸文本（理论上不该出现）：不产生段，不硬凑一段出来
        assertEquals(emptyList<String>(), splitPatchByFile("@@ -1 +1 @@\n+x\n"))
    }

    // ── 按下标对齐 + 三种文件的读法 ──

    @Test
    fun `未跟踪的新文件带内容，二进制文件只有一段没有 hunk`() {
        val view = parseLocalDiff(worktreeFixture)!!
        assertEquals(3, view.files.size)
        assertFalse(view.truncated)

        val bin = view.files[0]
        val mod = view.files[1]
        val new = view.files[2]
        assertEquals("bin.dat", bin.path)
        assertEquals("A", bin.status)
        assertFalse("二进制段没有 hunk，不能当文本差异画", bin.hasHunks)
        assertEquals("mod.txt", mod.path)
        assertEquals(2, mod.additions)
        assertEquals(1, mod.deletions)
        assertTrue(mod.hasHunks)
        assertEquals("new.txt", new.path)
        assertEquals(1, new.additions)
        assertTrue("未跟踪的新文件必须有可渲染的内容", new.hasHunks)
        assertTrue(new.patch.contains("+全新的"))
    }

    @Test
    fun `两段数量对不上时不许按下标硬配`() {
        // 旧 .so（未跟踪文件不给 patch）的形状：files 三条、patch 只有一段。
        // 按下标硬配会把 mod.txt 的差异画到 bin.dat 名下 —— 宁可全都不画
        val legacy = """
            {"files":[{"additions":0,"deletions":0,"path":"bin.dat","status":"A"},{"additions":2,"deletions":1,"path":"mod.txt","status":"M"}],"patch":"diff --git a/mod.txt b/mod.txt\n@@ -1,3 +1,4 @@\n aaa\n-bbb\n+BBB\n","truncated":false}
        """.trimIndent()
        val view = parseLocalDiff(legacy)!!
        assertEquals(2, view.files.size)
        assertTrue("对不上就不配 —— 一条都不给", view.files.all { !it.hasHunks })
        assertTrue("只有 BBB 一处，绝不该出现在 bin.dat 名下", view.files.none { it.patch.contains("BBB") })
    }

    // ── 解析的容错口径 ──

    @Test
    fun `读不到给 null，没有改动给空视图 —— 两件事不许混`() {
        assertNull(parseLocalDiff(null))
        assertNull(parseLocalDiff(""))
        assertNull("坏 JSON = 读不到", parseLocalDiff("不是 JSON"))
        val empty = parseLocalDiff("""{"files":[],"patch":"","truncated":false}""")!!
        assertTrue(empty.isEmpty)
        assertFalse(empty.truncated)
    }

    @Test
    fun `截断如实带出来`() {
        val view = parseLocalDiff("""{"files":[],"patch":"","truncated":true}""")!!
        assertTrue("引擎按 200 KB 截断，界面必须如实说", view.truncated)
    }

    @Test
    fun `没有 path 的条目直接丢，状态缺省按 M`() {
        val json = """{"files":[{"additions":1,"deletions":0},{"additions":1,"deletions":1,"path":"a.txt","status":""}],"patch":"diff --git a/a.txt b/a.txt\n@@ -1 +1 @@\n-x\n+y\n","truncated":false}"""
        val view = parseLocalDiff(json)!!
        assertEquals(1, view.files.size)
        assertEquals("a.txt", view.files[0].path)
        assertEquals("M", view.files[0].status)
    }

    @Test
    fun `hunk 判据认的是行首那两个 at`() {
        assertTrue(patchHasHunks("@@ -1,2 +1,3 @@\n a\n+b\n"))
        assertFalse(patchHasHunks("Binary files /dev/null and b/x differ\n"))
        // 内容行里出现 @@ 不算（只有行首才是 hunk 头）
        assertFalse(patchHasHunks("+let x = \"@@ nope @@\"\n"))
    }

    // ── 摊成行 ──

    @Test
    fun `摊平：文件头 + 差异行，二进制给一句如实说明`() {
        val rows = localDiffRows(parseLocalDiff(worktreeFixture)!!)
        val headers = rows.filterIsInstance<LocalDiffRow.Header>().map { it.file.path }
        assertEquals(listOf("bin.dat", "mod.txt", "new.txt"), headers)
        // 二进制：头之后紧跟一句说明，而不是一行假的行号
        val binIndex = rows.indexOfFirst { it is LocalDiffRow.Header && it.file.path == "bin.dat" }
        assertTrue(rows[binIndex + 1] is LocalDiffRow.NoPatch)
        // 文本文件：+BBB 必须作为「新增行」出现（行号是新的那一侧）
        val added = rows.filterIsInstance<LocalDiffRow.Line>()
            .filter { it.line.kind == DiffLineKind.Add }
            .map { it.line.text }
        assertTrue("新增行里要有 BBB 与全新的：$added", added.containsAll(listOf("BBB", "全新的")))
        val removed = rows.filterIsInstance<LocalDiffRow.Line>()
            .filter { it.line.kind == DiffLineKind.Remove }.map { it.line.text }
        assertEquals(listOf("bbb"), removed)
    }

    @Test
    fun `只留点开的那一个文件`() {
        val view = parseLocalDiff(worktreeFixture)!!
        val rows = localDiffRows(view, onlyPath = "mod.txt")
        assertEquals(listOf("mod.txt"), rows.filterIsInstance<LocalDiffRow.Header>().map { it.file.path })
        assertTrue(rows.none { it is LocalDiffRow.Header && it.file.path == "new.txt" })
        // 点上不存在的路径（例如点开之后那个文件被撤销了）：给空行，界面显示「这个文件现在没有改动了」
        assertTrue(localDiffRows(view, onlyPath = "gone.txt").isEmpty())
    }

    @Test
    fun `行的渲染键在整份 diff 里唯一`() {
        // LazyColumn 撞 key 会直接崩，不是画错。危险形状是「两个文件的 hunk 头逐字相同」——
        // 构造一份最小的例子把它摆出来：两个文件都只改一行，hunk 头一模一样
        val sameShape = """
            {"files":[{"additions":1,"deletions":1,"path":"a.txt","status":"M"},{"additions":1,"deletions":1,"path":"b.txt","status":"M"}],"patch":"diff --git a/a.txt b/a.txt\n--- a/a.txt\n+++ b/a.txt\n@@ -1,2 +1,2 @@\n keep\n-old\n+new\ndiff --git a/b.txt b/b.txt\n--- a/b.txt\n+++ b/b.txt\n@@ -1,2 +1,2 @@\n keep\n-old\n+new\n","truncated":false}
        """.trimIndent()
        val rows = localDiffRows(parseLocalDiff(sameShape)!!)
        val lines = rows.filterIsInstance<LocalDiffRow.Line>()

        // ① 光靠行号做键会撞（这正是危险所在）
        val naive = lines.map { "${it.line.kind}:${it.line.oldLine}:${it.line.newLine}" }
        assertTrue("构造的例子里两个文件的 hunk 头必须逐字相同，否则这条钉子测不到东西", naive.size > naive.toSet().size)

        // ② 带上文件身份就不撞 —— 页面里的 key 正是这么拼的
        val keys = lines.map { "${it.filePath}:${it.line.kind}:${it.line.oldLine}:${it.line.newLine}" }
        assertEquals("行键必须唯一：$keys", keys.size, keys.toSet().size)
    }

    @Test
    fun `两个文件的差异各归各家`() {
        val view = parseLocalDiff(twoFilesFixture)!!
        val rows = localDiffRows(view)
        val byFile = rows.filterIsInstance<LocalDiffRow.Line>()
            .groupBy { it.filePath }
            .mapValues { (_, v) -> v.filter { it.line.kind == DiffLineKind.Add }.map { it.line.text } }
        assertEquals(listOf("XX"), byFile["one.txt"])
        assertEquals(listOf("c"), byFile["two.txt"])
    }
}
