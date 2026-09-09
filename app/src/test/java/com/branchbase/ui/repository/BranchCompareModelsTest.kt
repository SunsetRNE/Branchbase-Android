package com.branchbase.ui.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分支对比模型解析单测。
 *
 * JSON 取自 GitHub 真实响应（`GET /repos/octocat/Hello-World/compare/master...test`），
 * 字段名与取值以实测为准；解析器必须对缺字段 / 异常输入降级而不是抛异常。
 */
class BranchCompareModelsTest {

    /** 真实响应（裁剪掉本模型不使用的字段）。 */
    private val realJson = """
        {
          "status": "ahead",
          "ahead_by": 1,
          "behind_by": 0,
          "total_commits": 1,
          "commits": [
            {
              "sha": "b3cbd5bbd7e81436d2eee04537ea2b4c0cad4cdf",
              "commit": {
                "message": "Create CONTRIBUTING.md",
                "author": { "name": "The Octocat", "date": "2014-06-10T22:22:26Z" }
              }
            }
          ],
          "files": [
            {
              "filename": "CONTRIBUTING.md",
              "status": "added",
              "additions": 1,
              "deletions": 0,
              "patch": "@@ -0,0 +1 @@\n+## Contributing",
              "blob_url": "https://github.com/octocat/Hello-World/blob/b3cbd5b/CONTRIBUTING.md",
              "previous_filename": null
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `真实响应解析出领先提交与文件差异`() {
        val r = parseCompareResult(realJson)!!

        assertEquals("ahead", r.status)
        assertEquals(1, r.aheadBy)
        assertEquals(0, r.behindBy)
        assertEquals(1, r.totalCommits)
        assertFalse(r.diverged)
        assertFalse(r.identical)

        assertEquals(1, r.commits.size)
        assertEquals("b3cbd5b", r.commits[0].shortSha)
        assertEquals("Create CONTRIBUTING.md", r.commits[0].subject)
        assertEquals("The Octocat", r.commits[0].author)
        assertEquals("2014-06-10", r.commits[0].date)

        assertEquals(1, r.files.size)
        val f = r.files[0]
        assertEquals("CONTRIBUTING.md", f.filename)
        assertEquals("新增", f.statusLabel)
        assertEquals("+1 -0", f.changeSummary)
        assertTrue(f.hasPatch)
        assertTrue(f.patch.startsWith("@@ -0,0 +1 @@"))
    }

    @Test
    fun `分叉与相同分支的判定`() {
        val diverged = parseCompareResult("""{"status":"diverged","ahead_by":2,"behind_by":3}""")!!
        assertTrue(diverged.diverged)
        assertFalse(diverged.identical)

        val identical = parseCompareResult("""{"status":"identical","ahead_by":0,"behind_by":0,"files":[]}""")!!
        assertTrue(identical.identical)
        assertFalse(identical.diverged)
    }

    @Test
    fun `大文件无 patch 时标记为无代码片段`() {
        val r = parseCompareResult(
            """{"status":"ahead","ahead_by":1,"behind_by":0,"files":[{"filename":"big.json","status":"modified","additions":9,"deletions":9}]}""",
        )!!

        val f = r.files.single()
        assertFalse(f.hasPatch)
        assertEquals("", f.patch)
        assertEquals("+9 -9", f.changeSummary)
    }

    @Test
    fun `目录与文件名拆分正确`() {
        val r = parseCompareResult(
            """{"files":[{"filename":"docs/img/logo.png","status":"added"},{"filename":"README.md","status":"modified"}]}""",
        )!!

        assertEquals("docs/img", r.files[0].dir)
        assertEquals("logo.png", r.files[0].name)
        assertEquals("", r.files[1].dir)
        assertEquals("README.md", r.files[1].name)
    }

    @Test
    fun `重命名保留旧文件名`() {
        val r = parseCompareResult(
            """{"files":[{"filename":"b.md","previous_filename":"a.md","status":"renamed","additions":0,"deletions":0}]}""",
        )!!

        assertEquals("重命名", r.files.single().statusLabel)
        assertEquals("a.md", r.files.single().previousFilename)
    }

    @Test
    fun `异常与空输入返回 null 或空列表`() {
        assertNull(parseCompareResult(null))
        assertNull(parseCompareResult(""))
        assertNull(parseCompareResult("ERROR: 网络失败"))
        assertNull(parseCompareResult("{ 不是 JSON"))

        // 合法 JSON 但缺字段：降级为默认值而不是抛异常
        val r = parseCompareResult("{}")!!
        assertEquals(0, r.aheadBy)
        assertTrue(r.files.isEmpty())
        assertTrue(r.commits.isEmpty())
        assertTrue(r.identical)
    }

    @Test
    fun `按顶层目录分组并保持原始顺序`() {
        val files = listOf(
            CompareFile("README.md", null, "modified", 1, 1, "", ""),
            CompareFile("docs/a.md", null, "added", 2, 0, "", ""),
            CompareFile("docs/img/b.png", null, "added", 0, 0, "", ""),
            CompareFile("src/c.kt", null, "modified", 3, 4, "", ""),
        )

        val groups = groupCompareFiles(files)

        assertEquals(listOf("", "docs/", "src/"), groups.map { it.first })
        assertEquals(listOf("README.md"), groups[0].second.map { it.filename })
        assertEquals(listOf("docs/a.md", "docs/img/b.png"), groups[1].second.map { it.filename })
        assertTrue(groupCompareFiles(emptyList()).isEmpty())
    }
}
