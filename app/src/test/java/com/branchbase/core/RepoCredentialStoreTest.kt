package com.branchbase.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仓库级凭据的**纯逻辑**单测（编解码 / 增删 / 替换规则）。
 *
 * 为什么值得钉：这里存的是长期令牌，错了不会崩 ——
 * 只会「静默用错身份」（把 A 仓库的令牌用到 B 上）、或「换令牌时把添加时间冲掉」这类
 * 平时看不出来、出事时很难查的问题。
 */
class RepoCredentialStoreTest {

    private fun cred(
        host: String = "github.com",
        owner: String = "o",
        repo: String = "r",
        login: String = "me",
        token: String = "ghp_x",
        addedAt: Long = 100,
        lastUsedAt: Long = 200,
    ) = RepoCredential(host, owner, repo, login, token, addedAt, lastUsedAt)

    // ── 编解码 ──

    @Test
    fun `编码解码往返一致`() {
        val items = listOf(cred(), cred(owner = "o2", repo = "r2", token = "ghp_y"))
        assertEquals(items, parseRepoCredentials(encodeRepoCredentials(items)))
    }

    @Test
    fun `坏输入一律跳过而不是抛错`() {
        assertTrue(parseRepoCredentials(null).isEmpty())
        assertTrue(parseRepoCredentials("").isEmpty())
        assertTrue(parseRepoCredentials("不是 JSON").isEmpty())
        assertTrue("不是数组也要跳过", parseRepoCredentials("""{"a":1}""").isEmpty())
        // 缺 token / owner / repo 的条目会被丢掉（留着也用不了）
        val mixed = """[{"owner":"o","repo":"r","token":""},{"owner":"","repo":"r","token":"t"},{"owner":"o","repo":"","token":"t"},{"owner":"o","repo":"r","token":"t"}]"""
        assertEquals(1, parseRepoCredentials(mixed).size)
    }

    @Test
    fun `缺 host 与时间字段时取安全默认值`() {
        val one = parseRepoCredentials("""[{"owner":"o","repo":"r","token":"t"}]""").single()
        assertEquals("github.com", one.host)
        assertEquals(0L, one.addedAt)
        assertEquals(0L, one.lastUsedAt)
        assertEquals("", one.login)
    }

    // ── 新增 / 替换规则 ──

    @Test
    fun `同一仓库只留一条_替换时沿用原添加时间`() {
        val existing = listOf(cred(token = "old", addedAt = 100))
        val fresh = newRepoCredential(existing, "github.com", "o", "r", "new", "me2", now = 999)!!
        assertEquals("new", fresh.token)
        assertEquals("换令牌不该改写「什么时候加的这个仓库」", 100L, fresh.addedAt)
        assertEquals(999L, fresh.lastUsedAt)

        val merged = upsertRepoCredential(existing, fresh)
        assertEquals(1, merged.size)
        assertEquals("new", merged.single().token)
    }

    @Test
    fun `host_owner_repo 大小写不敏感地视为同一目标`() {
        val existing = listOf(cred(owner = "SunsetRNE", repo = "Branchbase", host = "GitHub.com"))
        val fresh = newRepoCredential(existing, "github.com", "sunsetrne", "branchbase", "t", "x", 1)!!
        val merged = upsertRepoCredential(existing, fresh)
        assertEquals("大小写不同不该新增第二条", 1, merged.size)
        assertEquals("t", merged.single().token)
        assertEquals(100L, merged.single().addedAt)
    }

    @Test
    fun `缺 host 时按 github_com 归一`() {
        val fresh = newRepoCredential(emptyList(), "", "o", "r", "t", "me", 5)!!
        assertEquals("github.com", fresh.host)
    }

    @Test
    fun `空令牌或空仓库不落库`() {
        assertNull(newRepoCredential(emptyList(), "github.com", "o", "r", "  ", "me", 1))
        assertNull(newRepoCredential(emptyList(), "github.com", "", "r", "t", "me", 1))
        assertNull(newRepoCredential(emptyList(), "github.com", "o", "", "t", "me", 1))
    }

    @Test
    fun `不同仓库互不影响且保持原有顺序`() {
        val a = cred(owner = "a", repo = "1")
        val b = cred(owner = "b", repo = "2")
        val c = cred(owner = "c", repo = "3")
        val merged = upsertRepoCredential(listOf(a, b), c)
        assertEquals(listOf("a/1", "b/2", "c/3"), merged.map { it.slug })
    }

    // ── 删除 ──

    @Test
    fun `删除按目标匹配_大小写不敏感`() {
        val items = listOf(cred(owner = "SunsetRNE", repo = "Branchbase"), cred(owner = "other", repo = "x"))
        val left = removeRepoCredential(items, "github.com", "sunsetrne", "branchbase")
        assertEquals(1, left.size)
        assertEquals("other/x", left.single().slug)
    }

    @Test
    fun `删除不存在的目标时原样返回`() {
        val items = listOf(cred())
        val left = removeRepoCredential(items, "github.com", "nobody", "nothing")
        assertEquals(1, left.size)
        assertSame(items.single(), left.single())
    }

    @Test
    fun `展示用_slug`() {
        assertEquals("SunsetRNE/Branchbase", cred(owner = "SunsetRNE", repo = "Branchbase").slug)
    }
}
