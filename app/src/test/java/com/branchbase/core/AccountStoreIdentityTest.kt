package com.branchbase.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 账号身份判定单测（[AccountStore.indexOfSameIdentity]）。
 *
 * 防的是用户报的「伪覆盖」：**已经用 OAuth 登录过，再用密钥登录同一个账号**时，
 * 旧实现只按 `login + host` 匹配 → 原地覆盖那一条记录的 session。后果不可逆：
 *
 * - 账号列表里 OAuth 那条消失，只剩「PAT 令牌」；
 * - OAuth 的 token 已被丢掉，下一次覆盖会把密钥那条也换掉 —— **两种登录方式无法共存**；
 * - 而同一账号的本地仓库与任务按 **login** 隔离（不是按账号 id），所以共存不会把数据切两半。
 *
 * 抽成纯函数就是为了让这条判据能被钉住 —— 判错的代价是用户的凭据被静默丢弃，找不回来。
 */
class AccountStoreIdentityTest {

    private fun acc(
        id: String,
        login: String,
        host: String = "github.com",
        auth: AuthKind = AuthKind.OAUTH,
        session: String = "tok-$id",
        lastCheck: Long = 0L,
        status: AccountStatus = AccountStatus.UNKNOWN,
    ) = Account(
        id = id,
        login = login,
        host = host,
        auth = auth,
        session = session,
        addedAt = 1L,
        lastCheck = lastCheck,
        status = status,
    )

    // ───────────────── 身份 = host + login + 登录方式 ─────────────────

    @Test
    fun `同 host 同 login 同登录方式_算同一个账号`() {
        val list = listOf(acc("a", "SunsetRNE", auth = AuthKind.PAT))
        assertEquals(0, AccountStore.indexOfSameIdentity(list, "SunsetRNE", "github.com", AuthKind.PAT))
    }

    @Test
    fun `登录方式不同_算两个账号_这是伪覆盖的正解`() {
        // 已有 OAuth 记录时再用密钥登录同一个账号：必须另记一条，而不是覆盖它
        val list = listOf(acc("a", "SunsetRNE", auth = AuthKind.OAUTH))
        assertEquals(-1, AccountStore.indexOfSameIdentity(list, "SunsetRNE", "github.com", AuthKind.PAT))
    }

    @Test
    fun `反向同理_已有密钥记录时用 OAuth 登录也不覆盖`() {
        val list = listOf(acc("a", "SunsetRNE", auth = AuthKind.PAT))
        assertEquals(-1, AccountStore.indexOfSameIdentity(list, "SunsetRNE", "github.com", AuthKind.OAUTH))
    }

    @Test
    fun `两种登录方式可以共存`() {
        val list = listOf(
            acc("oauth", "SunsetRNE", auth = AuthKind.OAUTH),
            acc("pat", "SunsetRNE", auth = AuthKind.PAT),
        )
        // 各自都能在列表里找到自己那一条，互不干扰
        assertEquals(0, AccountStore.indexOfSameIdentity(list, "SunsetRNE", "github.com", AuthKind.OAUTH))
        assertEquals(1, AccountStore.indexOfSameIdentity(list, "SunsetRNE", "github.com", AuthKind.PAT))
    }

    @Test
    fun `login 忽略大小写`() {
        // GitHub 的 login 大小写不敏感，同一个人换了大小写写法不该变成两个账号
        val list = listOf(acc("a", "SunsetRNE", auth = AuthKind.OAUTH))
        assertEquals(0, AccountStore.indexOfSameIdentity(list, "sunsetrne", "github.com", AuthKind.OAUTH))
        assertEquals(0, AccountStore.indexOfSameIdentity(list, "SUNSETRNE", "github.com", AuthKind.OAUTH))
    }

    @Test
    fun `host 不同算两个账号_同名用户在 GHE 与 github_com 是两个人`() {
        val list = listOf(acc("a", "SunsetRNE", host = "github.com", auth = AuthKind.OAUTH))
        assertEquals(-1, AccountStore.indexOfSameIdentity(list, "SunsetRNE", "git.corp.example", AuthKind.OAUTH))
    }

    @Test
    fun `login 不同算两个账号`() {
        val list = listOf(acc("a", "alice", auth = AuthKind.OAUTH))
        assertEquals(-1, AccountStore.indexOfSameIdentity(list, "bob", "github.com", AuthKind.OAUTH))
    }

    @Test
    fun `空列表返回负一`() {
        assertEquals(-1, AccountStore.indexOfSameIdentity(emptyList(), "SunsetRNE", "github.com", AuthKind.OAUTH))
    }

    @Test
    fun `多条同名记录时返回第一条匹配的登录方式`() {
        // 真实场景：OAuth 与 PAT 两条并存，按方式各取所需
        val list = listOf(
            acc("1", "other", auth = AuthKind.OAUTH),
            acc("2", "SunsetRNE", auth = AuthKind.OAUTH),
            acc("3", "SunsetRNE", auth = AuthKind.PAT),
        )
        assertEquals(1, AccountStore.indexOfSameIdentity(list, "SunsetRNE", "github.com", AuthKind.OAUTH))
        assertEquals(2, AccountStore.indexOfSameIdentity(list, "SunsetRNE", "github.com", AuthKind.PAT))
    }

    @Test
    fun `老记录的 UNKNOWN 是通配_不会每次登录都新增一条重复账号`() {
        // `fromJson` 对缺失/损坏的 auth 回落成 UNKNOWN。若把它当普通值参与相等判断，
        // 这种记录永远匹配不上 → 每次登录都新增一条重复账号（本次改动自己引入的风险）。
        val legacy = listOf(acc("old", "SunsetRNE", auth = AuthKind.UNKNOWN))
        assertEquals(0, AccountStore.indexOfSameIdentity(legacy, "SunsetRNE", "github.com", AuthKind.OAUTH))
        assertEquals(0, AccountStore.indexOfSameIdentity(legacy, "SunsetRNE", "github.com", AuthKind.PAT))
    }

    @Test
    fun `两条具体方式并存时各命中自己那条`() {
        val list = listOf(
            acc("oauth", "SunsetRNE", auth = AuthKind.OAUTH),
            acc("pat", "SunsetRNE", auth = AuthKind.PAT),
        )
        assertEquals(0, AccountStore.indexOfSameIdentity(list, "SunsetRNE", "github.com", AuthKind.OAUTH))
        assertEquals(1, AccountStore.indexOfSameIdentity(list, "SunsetRNE", "github.com", AuthKind.PAT))
    }

    @Test
    fun `UNKNOWN 老记录排在前面也不抢具体记录`() {
        // 两轮匹配的意义：老记录（UNKNOWN）即使排在前面，也不能抢走本该命中具体方式那条的机会 ——
        // 否则用户明明有 OAuth 记录，登录却去更新了一条身份不明的老记录
        val list = listOf(
            acc("legacy", "SunsetRNE", auth = AuthKind.UNKNOWN),
            acc("oauth", "SunsetRNE", auth = AuthKind.OAUTH),
        )
        assertEquals("应命中具体的 OAuth 那条", 1, AccountStore.indexOfSameIdentity(list, "SunsetRNE", "github.com", AuthKind.OAUTH))
        // 没有 PAT 那条时才退回老记录
        assertEquals("没有具体匹配才用通配兜底", 0, AccountStore.indexOfSameIdentity(list, "SunsetRNE", "github.com", AuthKind.PAT))
    }

    // ───────────────── 两种登录方式指向同一份本地数据 ─────────────────

    @Test
    fun `两种登录方式的 login 相同_本地数据不会被切成两半`() {
        // 本地仓库与任务按 login 隔离（repos/{login}/…），不按账号 id —— 这是共存可行的前提。
        // 若哪天改成按 id 隔离，这条会红，提醒重新权衡。
        val oauth = acc("oauth", "SunsetRNE", auth = AuthKind.OAUTH)
        val pat = acc("pat", "SunsetRNE", auth = AuthKind.PAT)
        assertEquals(oauth.login, pat.login)
        assertNotEquals(oauth.id, pat.id)
    }
}
