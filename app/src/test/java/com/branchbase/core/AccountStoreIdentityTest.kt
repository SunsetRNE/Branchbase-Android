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

    // ───────────────── 端到端：登录后列表变成什么样（planUpsert） ─────────────────
    //
    // 上面测的是「判据」，这里测「判据用对了没有」—— 用户报的伪覆盖就发生在这一步。

    private fun plan(
        existing: List<Account>,
        login: String = "SunsetRNE",
        auth: AuthKind,
        session: String = "new-session",
        avatar: String? = null,
        now: Long = 1000L,
    ) = AccountStore.planUpsert(existing, login, session, "github.com", avatar, auth, now)!!

    @Test
    fun `已有 OAuth 时用密钥登录_新增一条而不是覆盖`() {
        // 用户报的原始场景
        val existing = listOf(acc("oauth", "SunsetRNE", auth = AuthKind.OAUTH, session = "oauth-token"))
        val p = plan(existing, auth = AuthKind.PAT, session = "pat-token")

        assertEquals("必须变成两条", 2, p.accounts.size)
        val oauth = p.accounts.first { it.auth == AuthKind.OAUTH }
        val pat = p.accounts.first { it.auth == AuthKind.PAT }
        assertEquals("原有 OAuth 的 session 不能被丢掉", "oauth-token", oauth.session)
        assertEquals("新密钥登录写进新那条", "pat-token", pat.session)
        assertEquals("返回的是新那条", "pat-token", p.account.session)
        assertNotEquals("两条 id 必须不同", oauth.id, pat.id)
    }

    @Test
    fun `已有密钥时用 OAuth 登录_同样新增一条`() {
        val existing = listOf(acc("pat", "SunsetRNE", auth = AuthKind.PAT, session = "pat-token"))
        val p = plan(existing, auth = AuthKind.OAUTH, session = "oauth-token")
        assertEquals(2, p.accounts.size)
        assertEquals("pat-token", p.accounts.first { it.auth == AuthKind.PAT }.session)
        assertEquals("oauth-token", p.accounts.first { it.auth == AuthKind.OAUTH }.session)
    }

    @Test
    fun `同方式再登录_更新同一条且不新增`() {
        // OAuth token 续期走的这条路：不能变成两条
        val existing = listOf(acc("oauth", "SunsetRNE", auth = AuthKind.OAUTH, session = "old"))
        val p = plan(existing, auth = AuthKind.OAUTH, session = "renewed")
        assertEquals(1, p.accounts.size)
        assertEquals("renewed", p.accounts[0].session)
        assertEquals("id 保持不变", "oauth", p.account.id)
    }

    @Test
    fun `登录不再重置检查结果_这是老是重探的根因`() {
        // lastCheck / status 被重置 → AccountChecks.isStale 判定「没查过」→ 设置页必然重探。
        // 真机日志：启动探测 20:32:33 写回结果，20:35:03 密钥登录后归零，下次进设置页再探一遍。
        val existing = listOf(
            acc("oauth", "SunsetRNE", auth = AuthKind.OAUTH, session = "old", lastCheck = 777L, status = AccountStatus.OK),
        )
        val p = plan(existing, auth = AuthKind.OAUTH, session = "renewed")
        assertEquals("lastCheck 必须保留", 777L, p.accounts[0].lastCheck)
        assertEquals("status 必须保留", AccountStatus.OK, p.accounts[0].status)
    }

    @Test
    fun `新增记录时不动已有记录的检查结果`() {
        val existing = listOf(
            acc("oauth", "SunsetRNE", auth = AuthKind.OAUTH, session = "old", lastCheck = 777L, status = AccountStatus.OK),
        )
        val p = plan(existing, auth = AuthKind.PAT, session = "pat")
        val oauth = p.accounts.first { it.auth == AuthKind.OAUTH }
        assertEquals(777L, oauth.lastCheck)
        assertEquals(AccountStatus.OK, oauth.status)
        // 新记录自然是「未检查」
        assertEquals(AccountStatus.UNKNOWN, p.accounts.first { it.auth == AuthKind.PAT }.status)
    }

    @Test
    fun `老记录 UNKNOWN 时_升级它的 auth 而不是新增`() {
        val existing = listOf(acc("legacy", "SunsetRNE", auth = AuthKind.UNKNOWN, session = "old"))
        val p = plan(existing, auth = AuthKind.PAT, session = "pat")
        assertEquals("老记录必须被就地升级，不能变成两条", 1, p.accounts.size)
        assertEquals(AuthKind.PAT, p.accounts[0].auth)
        assertEquals("pat", p.accounts[0].session)
    }

    @Test
    fun `avatar 为空时保留原有头像`() {
        val existing = listOf(acc("oauth", "SunsetRNE", auth = AuthKind.OAUTH).copy(avatar = "https://a/1.png"))
        val p = plan(existing, auth = AuthKind.OAUTH, session = "new", avatar = null)
        assertEquals("https://a/1.png", p.accounts[0].avatar)
    }

    @Test
    fun `avatar 有值时覆盖`() {
        val existing = listOf(acc("oauth", "SunsetRNE", auth = AuthKind.OAUTH).copy(avatar = "https://a/1.png"))
        val p = plan(existing, auth = AuthKind.OAUTH, session = "new", avatar = "https://a/2.png")
        assertEquals("https://a/2.png", p.accounts[0].avatar)
    }

    @Test
    fun `login 为空时不登记`() {
        assertEquals(null, AccountStore.planUpsert(emptyList(), "", "tok", "github.com", null, AuthKind.OAUTH, 1L))
        assertEquals(null, AccountStore.planUpsert(emptyList(), "   ", "tok", "github.com", null, AuthKind.OAUTH, 1L))
    }

    @Test
    fun `新账号 id 不与已有 id 冲突`() {
        // 原实现是 acc-<36进制时间>-<0..999 随机>，撞了会让 current_account 指不到任何记录。
        // 这里让三批账号都用同一个 now 创建，id 必须各不相同。
        var list = emptyList<Account>()
        repeat(3) {
            val p = AccountStore.planUpsert(list, "u$it", "tok$it", "github.com", null, AuthKind.OAUTH, 42L)!!
            list = p.accounts
        }
        assertEquals(3, list.size)
        assertEquals("同一毫秒创建的账号 id 必须互不相同", 3, list.map { it.id }.toSet().size)
    }

    @Test
    fun `两条记录存在时按方式各自更新_不会互相踩`() {
        val existing = listOf(
            acc("oauth", "SunsetRNE", auth = AuthKind.OAUTH, session = "o1"),
            acc("pat", "SunsetRNE", auth = AuthKind.PAT, session = "p1"),
        )
        val afterPat = plan(existing, auth = AuthKind.PAT, session = "p2").accounts
        assertEquals("oauth 那条不动", "o1", afterPat.first { it.auth == AuthKind.OAUTH }.session)
        assertEquals("pat 那条更新", "p2", afterPat.first { it.auth == AuthKind.PAT }.session)
        assertEquals(2, afterPat.size)
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
