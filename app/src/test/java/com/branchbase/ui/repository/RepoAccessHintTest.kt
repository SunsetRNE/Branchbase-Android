package com.branchbase.ui.repository

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 私有仓库打不开时的**判定与出路**：纯函数单测。
 *
 * 这几条逻辑的价值在于「不下错结论」：
 * 404 在 GitHub 上既可能是「私有仓库没权限」也可能是「不存在」，代码必须把两种可能都说清楚，
 * 而不是替用户断言；scope 判读则是唯一能把两者分开的确定信号。
 */
class RepoAccessHintTest {

    // ── 错误串 → 解释 ──

    @Test
    fun `404 要说明歧义而不是断言私有`() {
        val hint = repoAccessHint("HTTP 404 Not Found: {\"message\":\"Not Found\"}")!!
        assertTrue("要点出 404 的两种可能：$hint", hint.contains("私有仓库") && hint.contains("不存在"))
    }

    @Test
    fun `403 提示权限与限流两类原因`() {
        val hint = repoAccessHint("HTTP 403 Forbidden: {}")!!
        assertTrue(hint.contains("SSO") || hint.contains("SSO 未授权"))
        assertTrue(hint.contains("限流") || hint.contains("二级限流"))
    }

    @Test
    fun `其它错误不给仓库级解释`() {
        assertNull(repoAccessHint(null))
        assertNull(repoAccessHint("HTTP 500 Internal Server Error"))
        assertNull(repoAccessHint("引擎不可用"))
    }

    // ── scopes → 判读 ──

    @Test
    fun `有 repo 权限要明确说不是权限问题`() {
        assertEquals(ScopeVerdict.HAS_REPO, scopeVerdict("repo, read:user"))
        assertEquals(ScopeVerdict.HAS_REPO, scopeVerdict("REPO"))
        assertTrue(scopeVerdictLine(ScopeVerdict.HAS_REPO)!!.contains("不是权限问题"))
    }

    @Test
    fun `没有 repo 权限要点明私有仓库必然看不到`() {
        assertEquals(ScopeVerdict.MISSING_REPO, scopeVerdict("read:user, gist"))
        // public_repo 只覆盖公开仓库，不算够用
        assertEquals(ScopeVerdict.MISSING_REPO, scopeVerdict("public_repo"))
        assertTrue(scopeVerdictLine(ScopeVerdict.MISSING_REPO)!!.contains("没有"))
    }

    @Test
    fun `拿不到 scopes 时不额外说明`() {
        assertEquals(ScopeVerdict.UNKNOWN, scopeVerdict(null))
        assertEquals(ScopeVerdict.UNKNOWN, scopeVerdict(""))
        assertEquals(ScopeVerdict.UNKNOWN, scopeVerdict("   ,  , "))
        assertNull("未知就不该多嘴", scopeVerdictLine(ScopeVerdict.UNKNOWN))
    }

    // ── 会话覆盖 ──

    private val session = """
        {"host":"github.com","token":{"access_token":"old","refresh_token":"r"},"user":{"login":"me"}}
    """.trimIndent()

    @Test
    fun `覆盖令牌只替换 access_token 其余原样`() {
        val out = JSONObject(sessionWithToken(session, "new"))
        assertEquals("new", out.getJSONObject("token").getString("access_token"))
        assertEquals("r", out.getJSONObject("token").getString("refresh_token"))
        assertEquals("github.com", out.getString("host"))
        assertEquals("me", out.getJSONObject("user").getString("login"))
        assertEquals("旧的解析结果不变", "me", sessionInfo(sessionWithToken(session, "new")).third)
        assertEquals("new", sessionInfo(sessionWithToken(session, "new")).second)
    }

    @Test
    fun `bad_json_时原样返回_不造坏会话`() {
        val bad = "不是 JSON"
        assertSame(bad, sessionWithToken(bad, "new"))
        assertEquals("", sessionInfo(sessionWithToken(bad, "new")).second)
    }

    @Test
    fun `会话缺少 token 对象时补一个`() {
        val out = sessionWithToken("""{"host":"ghe.example.com"}""", "new")
        assertEquals("new", sessionInfo(out).second)
        assertEquals("ghe.example.com", sessionInfo(out).first)
    }
}
