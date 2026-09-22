package com.branchbase.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ApiEvidence] 的单测。
 *
 * 它防的是「应用明明能用，界面却挂着令牌已失效」：真机上出现过 `/user` 报失效、
 * 而 `/user/repos`、`/notifications`、GraphQL 全 200 的同场会话。证据过期或键算错，
 * 这条兜底就形同虚设，所以把**过期判定**与**键的隔离**都钉住。
 */
class ApiEvidenceTest {

    @Test
    fun `没记过就没有证据`() {
        ApiEvidence.clear()
        assertFalse(ApiEvidence.sawSuccessRecently("github.com", "tok", 1_000_000L))
    }

    @Test
    fun `记过之后在 TTL 内算有证据`() {
        ApiEvidence.clear()
        val now = 1_000_000L
        ApiEvidence.noteSuccess("github.com", "tok", now)
        assertTrue(ApiEvidence.sawSuccessRecently("github.com", "tok", now + 1))
        assertTrue(ApiEvidence.sawSuccessRecently("github.com", "tok", now + ApiEvidence.TTL_MS - 1))
    }

    @Test
    fun `过期后不再算证据`() {
        ApiEvidence.clear()
        val now = 1_000_000L
        ApiEvidence.noteSuccess("github.com", "tok", now)
        assertFalse(ApiEvidence.sawSuccessRecently("github.com", "tok", now + ApiEvidence.TTL_MS))
    }

    @Test
    fun `证据按 host 与 token 隔离_别的账号的成功不能兜住这个账号`() {
        ApiEvidence.clear()
        val now = 1_000_000L
        ApiEvidence.noteSuccess("github.com", "token-a", now)
        assertFalse("换个 token 就没有证据", ApiEvidence.sawSuccessRecently("github.com", "token-b", now))
        assertFalse("换个 host 也没有", ApiEvidence.sawSuccessRecently("ghe.example.com", "token-a", now))
    }

    @Test
    fun `空 token 永远不算证据`() {
        ApiEvidence.clear()
        ApiEvidence.noteSuccess("github.com", "", 1_000_000L)
        assertFalse(ApiEvidence.sawSuccessRecently("github.com", "", 1_000_000L))
    }

    @Test
    fun `过期判定是纯函数`() {
        val ttl = ApiEvidence.TTL_MS
        assertTrue(evidenceFresh(now = 1_000_000L, at = 1_000_000L, ttlMs = ttl))
        assertTrue(evidenceFresh(now = 1_000_000L + ttl - 1, at = 1_000_000L, ttlMs = ttl))
        assertFalse(evidenceFresh(now = 1_000_000L + ttl, at = 1_000_000L, ttlMs = ttl))
    }
}
