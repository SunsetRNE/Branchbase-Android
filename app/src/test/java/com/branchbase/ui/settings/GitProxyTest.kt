package com.branchbase.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Git 代理的两件纯逻辑：**脱敏显示**与**格式校验**。
 *
 * 两件事都是「写错了不报错、真机上才发现」的类型：
 * - 脱敏漏了 → 代理 URL 里的用户名密码直接显示在设置列表里（规范 §6.5）；
 * - 校验漏了 → 用户填错地址后 libgit2 只会静默失效，没有任何提示（规范 §5.5）。
 */
class GitProxyTest {

    // ── 脱敏（§6.5） ───────────────────────────────────────────────

    @Test
    fun `脱敏只留 host 与 port`() {
        assertEquals("127.0.0.1:7890", displayGitProxy("http://127.0.0.1:7890"))
        assertEquals("proxy.corp:1080", displayGitProxy("socks5://user:pass@proxy.corp:1080"))
        assertEquals("proxy.corp:7890", displayGitProxy("http://user:pass@proxy.corp:7890/path"))
        assertEquals("proxy.corp:7890", displayGitProxy("https://proxy.corp:7890"))
    }

    @Test
    fun `脱敏会去掉凭据与路径`() {
        val shown = displayGitProxy("socks5://username:password@proxy.internal.example.com:1080")
        assertFalse("凭据不能出现在列表里", shown.contains("username"))
        assertFalse("凭据不能出现在列表里", shown.contains("password"))
        assertEquals("proxy.internal.example.com:1080", shown)
    }

    @Test
    fun `空值脱敏后仍为空`() {
        assertEquals("", displayGitProxy(""))
        assertEquals("", displayGitProxy("   "))
    }

    @Test
    fun `没有协议头时也能脱敏`() {
        assertEquals("127.0.0.1:7890", displayGitProxy("127.0.0.1:7890"))
    }

    // ── 校验（§5.5） ───────────────────────────────────────────────

    @Test
    fun `留空表示不使用代理且是合法的`() {
        val r = validateGitProxy("")
        assertTrue("留空不是错误，是「不使用代理」", r.ok)
        assertTrue(r.message.contains("不使用代理"))
    }

    @Test
    fun `合法地址通过`() {
        assertTrue(validateGitProxy("http://127.0.0.1:7890").ok)
        assertTrue(validateGitProxy("https://proxy.corp:8080").ok)
        assertTrue(validateGitProxy("socks5://proxy.corp:1080").ok)
        assertTrue("协议大小写不敏感", validateGitProxy("HTTP://proxy.corp:8080").ok)
    }

    @Test
    fun `缺协议头会被指出`() {
        val r = validateGitProxy("127.0.0.1:7890")
        assertFalse(r.ok)
        assertTrue("错因要可执行，不能只说「格式错误」", r.message.contains("http://"))
    }

    @Test
    fun `缺端口会被指出`() {
        val r = validateGitProxy("http://proxy.corp")
        assertFalse(r.ok)
        assertTrue("错因要指明缺什么", r.message.contains("端口"))
    }

    @Test
    fun `不支持的协议被判失败`() {
        assertFalse(validateGitProxy("ftp://proxy.corp:21").ok)
        assertFalse(validateGitProxy("proxy.corp:1080").ok)
    }

    @Test
    fun `前后空白不影响判定`() {
        assertTrue(validateGitProxy("  http://127.0.0.1:7890  ").ok)
        assertTrue(validateGitProxy("   ").ok)
    }
}
