package com.branchbase.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 账号状态判定的单测（1.0.61 补）。
 *
 * 这段逻辑此前**一条测试都没有** —— 而它正是真机上产出「令牌已失效」误判的地方：
 * 同一次会话里 `/user` 报失效，`/user/repos`、`/notifications`、GraphQL 却全是 200。
 * 误判的代价很具体：用户去重新登录（没用），而且结论会粘住整场会话。
 *
 * 这里钉两件事：**分类**（谁算失效、谁只算连不上）与**形状判据**（这是不是 GitHub 在回话）。
 */
class AccountStatusTest {

    private val gh401 = """ERROR: HTTP 401 Unauthorized: {"message":"Bad credentials","documentation_url":"https://docs.github.com/rest"}"""
    private val gh403 = """ERROR: HTTP 403 Forbidden: {"message":"Resource not accessible by personal access token","documentation_url":"https://docs.github.com/rest"}"""

    @Test
    fun `正常响应是 OK`() {
        assertEquals(AccountStatus.OK, AccountStore.statusFromResponse("""{"login":"XK-Pro"}"""))
    }

    @Test
    fun `null 与无码错误都算连不上`() {
        assertEquals(AccountStatus.UNREACHABLE, AccountStore.statusFromResponse(null))
        // 连接失败/超时：引擎不回 HTTP 码（解析出 0）
        assertEquals(AccountStatus.UNREACHABLE, AccountStore.statusFromResponse("ERROR: 连接超时"))
    }

    @Test
    fun `GitHub 形状的 401 才算令牌失效`() {
        assertEquals(AccountStatus.INVALID, AccountStore.statusFromResponse(gh401))
    }

    @Test
    fun `GitHub 形状的 403 也算失效_但只在像 GitHub 时`() {
        assertEquals(AccountStatus.INVALID, AccountStore.statusFromResponse(gh403))
    }

    @Test
    fun `代理或门户的 401 403 只能算连不上_不能误导用户去重新登录`() {
        val portal403 = "ERROR: HTTP 403 Forbidden: <html><head><title>403 Forbidden</title></head><body>nginx</body></html>"
        val empty401 = "ERROR: HTTP 401 Unauthorized: "
        val relay403 = """ERROR: HTTP 403 Forbidden: {"code":403,"msg":"当前 IP 未授权，请先登录中转站"}"""
        assertEquals(AccountStatus.UNREACHABLE, AccountStore.statusFromResponse(portal403))
        assertEquals(AccountStatus.UNREACHABLE, AccountStore.statusFromResponse(empty401))
        assertEquals(AccountStatus.UNREACHABLE, AccountStore.statusFromResponse(relay403))
    }

    @Test
    fun `封禁与限流的文案优先于状态码`() {
        val suspended = """ERROR: HTTP 403 Forbidden: {"message":"Your account was suspended.","documentation_url":"…"}"""
        val limited = """ERROR: HTTP 403 Forbidden: {"message":"API rate limit exceeded","documentation_url":"…"}"""
        assertEquals(AccountStatus.SUSPENDED, AccountStore.statusFromResponse(suspended))
        assertEquals(AccountStatus.LIMITED, AccountStore.statusFromResponse(limited))
        assertEquals(
            AccountStatus.LIMITED,
            AccountStore.statusFromResponse("""ERROR: HTTP 429 Too Many Requests: {"message":"rate limit"}"""),
        )
    }

    @Test
    fun `形状判据本身`() {
        assertTrue(AccountStore.looksLikeGitHub(gh401))
        assertTrue("只要带 documentation_url 就算", AccountStore.looksLikeGitHub("""{"documentation_url":"x"}"""))
        assertTrue(AccountStore.looksLikeGitHub("""{"message":"Bad credentials"}"""))
        assertFalse("HTML 门户不算", AccountStore.looksLikeGitHub("<html>403</html>"))
        assertFalse("空体不算", AccountStore.looksLikeGitHub(""))
        assertFalse("中转站自己的 JSON 不算（没有 message 字段）", AccountStore.looksLikeGitHub("""{"code":403,"msg":"未授权"}"""))
    }
}
