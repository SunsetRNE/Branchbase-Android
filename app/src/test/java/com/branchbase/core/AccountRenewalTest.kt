package com.branchbase.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「换过令牌就作废旧结论」（[AccountStore.tokenChanged]）与「续期把新 token 装回会话」
 * （[AccountRenewal.swapToken]）的单测（1.1.6）。
 *
 * 防的是用户报的那句话：**「明确能够登录，但校验令牌异常」**。
 *
 * 结论（`Account.status`）是关于**某一枚 token** 的，不是关于这个账号的。而
 * [AccountStore.planUpsert] 原本刻意保留 `status` / `lastCheck`（理由：重置会让「刚查过」作废），
 * 那条理由只对同一枚令牌成立 —— 于是刚重新授权 / 刚续期拿到的新令牌，账号页仍然挂着上一枚
 * 死令牌的判决书「令牌已失效」；[AccountChecks.isStale] 又规定 5 分钟内不自动重查，
 * 用户以为登录没生效，就去反复重新登录。
 *
 * 这两条规则判错的代价都是**用户被误导**（一个让他白重登，一个让新令牌静默失效），
 * 所以抽成纯函数钉在这里。
 */
class AccountRenewalTest {

    // ───────────────────────── 夹具 ─────────────────────────

    /** 造一份会话 JSON（结构必须与 `sessionInfo()` 同构：`host` / `token.access_token` / `user.login`）。 */
    private fun sessionJson(
        access: String,
        refresh: String? = null,
        login: String = "XK-Pro",
    ): String {
        val token = JSONObject().put("access_token", access)
        if (refresh != null) token.put("refresh_token", refresh)
        return JSONObject()
            .put("host", "github.com")
            .put("token", token)
            .put("user", JSONObject().put("login", login).put("avatar_url", "https://avatars/x.png"))
            .toString()
    }

    private fun acc(
        session: String,
        status: AccountStatus = AccountStatus.INVALID,
        lastCheck: Long = 123L,
        login: String = "XK-Pro",
        auth: AuthKind = AuthKind.OAUTH,
    ) = Account(
        id = "a1",
        login = login,
        host = "github.com",
        auth = auth,
        session = session,
        addedAt = 1L,
        lastCheck = lastCheck,
        status = status,
    )

    // ────────────────── tokenChanged：结论的适用范围 ──────────────────

    @Test
    fun `换了一枚 access_token 算换过`() {
        assertTrue(AccountStore.tokenChanged(acc(sessionJson("tok-old")), sessionJson("tok-new")))
    }

    @Test
    fun `同一枚令牌只是补了 user 字段_不算换过_别把刚查过的结论丢掉`() {
        // 登录成功后 persistAccount 会往会话里补 user —— 那种变化与令牌是否有效无关
        val before = JSONObject().put("host", "github.com")
            .put("token", JSONObject().put("access_token", "tok-same"))
            .toString()
        assertFalse(AccountStore.tokenChanged(acc(before), sessionJson("tok-same")))
    }

    @Test
    fun `新会话解析不出 token_不算换过_别把旧结论丢了`() {
        // 写进来的会话本身有问题时，留着旧结论信息量更大（空 token 会被判成「令牌已失效」）
        assertFalse(AccountStore.tokenChanged(acc(sessionJson("tok-old")), "{}"))
        assertFalse(AccountStore.tokenChanged(acc(sessionJson("tok-old")), "不是 JSON"))
    }

    @Test
    fun `旧会话本来没有 token_新会话有_算换过`() {
        assertTrue(AccountStore.tokenChanged(acc("{}"), sessionJson("tok-new")))
    }

    // ────────────────── planUpsert：换令牌即作废结论 ──────────────────

    @Test
    fun `planUpsert 换过令牌就作废旧结论_并保留身份与登录方式`() {
        val plan = AccountStore.planUpsert(
            existing = listOf(acc(sessionJson("tok-old", refresh = "r-old"))),
            login = "XK-Pro",
            session = sessionJson("tok-new", refresh = "r-new"),
            host = "github.com",
            avatar = null,
            auth = AuthKind.OAUTH,
            now = 999L,
            makeCurrent = true,
            currentId = "a1",
        )!!
        assertFalse("同一账号应更新原记录，而不是新增一条", plan.added)
        assertEquals("结论是关于旧令牌的，换了令牌必须重新探", AccountStatus.UNKNOWN, plan.account.status)
        assertEquals("lastCheck 一并清零，否则 5 分钟内不会被重查", 0L, plan.account.lastCheck)
        assertEquals("a1", plan.account.id)
        assertEquals("tok-new", AccountStore.accessTokenOf(plan.account.session))
        assertEquals("r-new", AccountStore.refreshTokenOf(plan.account.session))
    }

    @Test
    fun `planUpsert 同一枚令牌只补字段_保留刚查过的结论`() {
        val before = JSONObject().put("host", "github.com")
            .put("token", JSONObject().put("access_token", "tok-same"))
            .toString()
        val plan = AccountStore.planUpsert(
            existing = listOf(acc(before)),
            login = "XK-Pro",
            session = sessionJson("tok-same"),
            host = "github.com",
            avatar = null,
            auth = AuthKind.OAUTH,
            now = 999L,
        )!!
        assertEquals("令牌没变就不该把「刚查过」作废", AccountStatus.INVALID, plan.account.status)
        assertEquals(123L, plan.account.lastCheck)
    }

    // ────────────────── swapToken：续期响应装回会话 ──────────────────

    @Test
    fun `swapToken 保留 host 与 user_只换 token 段`() {
        val swapped = AccountRenewal.swapToken(
            sessionJson("tok-old", refresh = "r-old"),
            JSONObject().put("access_token", "tok-new").put("refresh_token", "r-new").put("expires_in", 28800).toString(),
        )!!
        val root = JSONObject(swapped)
        assertEquals("github.com", root.optString("host"))
        assertEquals("XK-Pro", root.optJSONObject("user")?.optString("login"))
        assertEquals("tok-new", AccountStore.accessTokenOf(swapped))
        assertEquals("refresh token 是一次性的，必须换成响应里那枚新的", "r-new", AccountStore.refreshTokenOf(swapped))
    }

    @Test
    fun `swapToken 响应里没有 access_token_返回 null_别把空令牌写进账号表`() {
        assertNull(AccountRenewal.swapToken(sessionJson("tok-old"), JSONObject().put("error", "bad_refresh_token").toString()))
    }

    @Test
    fun `swapToken 响应不是 JSON_返回 null`() {
        assertNull(AccountRenewal.swapToken(sessionJson("tok-old"), "<html>502</html>"))
    }

    @Test
    fun `swapToken 会话本身不是 JSON_也照样能装出一份会话`() {
        // 账号表里会话坏掉时，续期仍应产出一份可用的新会话（host 由调用方从账号记录里带）
        val swapped = AccountRenewal.swapToken("坏会话", JSONObject().put("access_token", "tok-new").toString())!!
        assertEquals("tok-new", AccountStore.accessTokenOf(swapped))
    }

    @Test
    fun `续期前后是两枚不同的令牌_指纹必须不同`() {
        assertNotEquals(AccountChecks.fingerprint("tok-old"), AccountChecks.fingerprint("tok-new"))
    }
}
