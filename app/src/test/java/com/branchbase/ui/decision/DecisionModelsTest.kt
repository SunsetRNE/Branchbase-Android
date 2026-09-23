package com.branchbase.ui.decision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 决策页**事实区解析**的单测（此前这块零专属测试）。
 *
 * 为什么值得钉：`GitStatus` 的字段直接决定「哪些选项可点、点了会发生什么」——
 * 解析错了不会崩，只会让用户在错误的判断上做不可逆操作（放弃本地提交 / 删除仓库）。
 * 数据来源是 Rust 侧 `repo_status` 的 JSON，键名两边一起改错时不会有任何东西报警。
 */
class DecisionModelsTest {

    // ───────────────────── parseGitStatus ─────────────────────

    @Test
    fun 空输入与坏_JSON_返回_null() {
        assertNull(parseGitStatus(null))
        assertNull(parseGitStatus(""))
        assertNull(parseGitStatus("   "))
        assertNull(parseGitStatus("不是 JSON"))
        // 合法 JSON 但不是对象（数组）也应判失败，而不是崩
        assertNull(parseGitStatus("[1,2,3]"))
    }

    @Test
    fun 缺键时取保守默认值() {
        val s = parseGitStatus("{}")!!
        assertEquals("main", s.branch)
        assertEquals(0, s.ahead)
        assertEquals(0, s.behind)
        assertFalse(s.hasUpstream)
        assertEquals("", s.remoteUrl)
        assertTrue(s.dirty.isEmpty())
        assertTrue(s.unpushed.isEmpty())
        // 新字段的默认值刻意偏保守：不误报「第一个提交」，但「放弃本地提交」先当成没有远端 ref
        assertTrue(s.hasParent)
        assertEquals("", s.headSha)
        assertFalse(s.hasRemoteRef)
    }

    @Test
    fun 全字段解析() {
        val json = """
            {
              "branch": "feature/x",
              "ahead": 2,
              "behind": 1,
              "has_upstream": true,
              "remote_url": "https://github.com/o/r.git",
              "has_parent": false,
              "head_sha": "a1b2c3d4e5f6",
              "has_remote_ref": true,
              "dirty": [{"path": "b.txt", "status": "M"}, {"path": "a.txt", "status": "A"}],
              "unpushed": [{"sha": "abc123", "message": "feat: x"}]
            }
        """.trimIndent()
        val s = parseGitStatus(json)!!
        assertEquals("feature/x", s.branch)
        assertEquals(2, s.ahead)
        assertEquals(1, s.behind)
        assertTrue(s.hasUpstream)
        assertEquals("https://github.com/o/r.git", s.remoteUrl)
        assertFalse("第一个提交：撤销上一次提交必然失败", s.hasParent)
        assertEquals("a1b2c3d4e5f6", s.headSha)
        assertTrue(s.hasRemoteRef)
        assertEquals(listOf(DirtyFile("b.txt", "M"), DirtyFile("a.txt", "A")), s.dirty)
        assertEquals(listOf(UnpushedCommit("abc123", "feat: x")), s.unpushed)
    }

    @Test
    fun 数组里混入非对象元素时跳过而不是整体失败() {
        val s = parseGitStatus("""{"dirty":[{"path":"a","status":"M"},7,null],"unpushed":[3]}""")!!
        assertEquals(listOf(DirtyFile("a", "M")), s.dirty)
        assertTrue(s.unpushed.isEmpty())
    }

    @Test
    fun 数组类型不对时视为空而不是抛错() {
        val s = parseGitStatus("""{"dirty":"oops","unpushed":{"sha":"x"}}""")!!
        assertTrue(s.dirty.isEmpty())
        assertTrue(s.unpushed.isEmpty())
    }

    // ───────────────────── parseSensitiveHits ─────────────────────

    @Test
    fun 敏感命中的空输入与坏_JSON_返回空列表() {
        assertTrue(parseSensitiveHits(null).isEmpty())
        assertTrue(parseSensitiveHits("").isEmpty())
        assertTrue(parseSensitiveHits("不是 JSON").isEmpty())
        // 合法 JSON 但不是数组
        assertTrue(parseSensitiveHits("""{"line":1}""").isEmpty())
    }

    @Test
    fun 敏感命中逐条映射() {
        val hits = parseSensitiveHits(
            """[{"line":3,"kind":"PAT","mask":"ghp_****"},{"line":9,"kind":"私钥","mask":"-----BEGIN****"}]""",
        )
        assertEquals(2, hits.size)
        assertEquals(SensitiveHit(3, "PAT", "ghp_****"), hits[0])
        assertEquals(SensitiveHit(9, "私钥", "-----BEGIN****"), hits[1])
    }

    @Test
    fun 敏感命中缺字段时取零值与空串() {
        val hits = parseSensitiveHits("""[{}]""")
        assertEquals(listOf(SensitiveHit(0, "", "")), hits)
    }
}
