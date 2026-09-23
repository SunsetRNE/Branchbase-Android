package com.branchbase.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AccountChecks] 的**判定与诊断**单测（不碰网络）。
 *
 * 拆出这些纯函数，是因为真机上要弄清的两件事都发生在「网络之外」：
 *
 * 1. **为什么失效**：第二份真机日志里 `/user` 与复核端点都回 401 且响应体是真 GitHub 形状
 *    （`Bad credentials`）—— 三条防线全部通过，结论「令牌已失效」是对的，但日志到此为止，
 *    答不出「过期了还是被吊销了」。GitHub 在**每一次**认证请求（含 401）上都会回
 *    `GitHub-Authentication-Token-Expiration`，而旧链路只留「状态码 + 体」、把头丢了，
 *    所以 [AccountChecks.expiryNote] / [AccountChecks.parseExpiryMs] 得单独钉住。
 * 2. **为什么「老是报」**：进账号页无条件对 `UNKNOWN` 的账号重探，而结论是「令牌已失效」时
 *    用户每次进设置都会再被报一次同样的事。[AccountChecks.isStale] 钉住「刚查过就不重探」。
 */
class AccountChecksTest {

    // ───────────────── 过期头解析 ─────────────────

    @Test
    fun `解析 GitHub 实际的过期头格式`() {
        // 实测形态：空格分隔、没有 T（与 ISO8601 不同，用错格式化器会静默解析失败）
        assertEquals(1790752354000L, AccountChecks.parseExpiryMs("2026-09-30 07:12:34 UTC"))
    }

    @Test
    fun `解析 ISO8601 变体`() {
        // GitHub Enterprise 上见过带 T 的形态
        assertEquals(1790752354000L, AccountChecks.parseExpiryMs("2026-09-30T07:12:34Z"))
    }

    @Test
    fun `其它时区缩写同样能解析`() {
        assertEquals(1790752354000L, AccountChecks.parseExpiryMs("2026-09-30 07:12:34 GMT"))
    }

    @Test
    fun `没有时区段的形态按 UTC 解释`() {
        // GitHub 有的端点上就是裸的时间，没有 UTC 后缀
        assertEquals(1790752354000L, AccountChecks.parseExpiryMs("2026-09-30 07:12:34"))
    }

    @Test
    fun `解析不出来的输入返回 null 而不是抛出或猜一个时间`() {
        assertNull(AccountChecks.parseExpiryMs("garbage"))
        assertNull(AccountChecks.parseExpiryMs(""))
        assertNull(AccountChecks.parseExpiryMs("   "))
        assertNull(AccountChecks.parseExpiryMs(null))
        // 只给日期不给时间也不接受：宁可说「查不到」，也不要编一个当天零点
        assertNull(AccountChecks.parseExpiryMs("2026-09-30"))
    }

    @Test
    fun `两端空白不影响解析`() {
        assertEquals(1790752354000L, AccountChecks.parseExpiryMs("  2026-09-30 07:12:34 UTC  "))
    }

    // ───────────────── 白话结论 ─────────────────

    /**
     * 探测时刻：2026-09-23 19:45:28 UTC —— 第二份真机日志里那台手机报失效的时刻
     * （日志打的是设备本地时间 Asia/Shanghai，这里统一换算成 UTC 作基准）。
     *
     * ⚠️ 不要手算毫秒字面量：先前写死过一个近似值、差了 5 小时，边界用例直接假红。
     * 从同一个 UTC 基准推导，改日期时不会两处对不上。
     */
    private val probeAt: Long = java.time.LocalDateTime
        // ⚠️ 必须给显式格式化器：`LocalDateTime.parse` 的默认形态是 ISO（`T` 分隔），
        //    这里的时间串是**空格**分隔的，用默认会直接 DateTimeParseException。
        .parse(
            "2026-09-23 19:45:28",
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", java.util.Locale.US),
        )
        .toInstant(java.time.ZoneOffset.UTC)
        .toEpochMilli()

    /** 与 [probeAt] 同一时刻的过期头原文（边界用例用它做「正好过期」）。 */
    private val probeAtUtc = "2026-09-23 19:45:28 UTC"

    @Test
    fun `过期时间在过去_说清楚该去重新生成`() {
        val note = AccountChecks.expiryNote("2026-09-01 00:00:00 UTC", 401, probeAt)!!
        assertTrue("要指出已过期：$note", note.contains("已过期"))
        assertTrue("要给出用户动作：$note", note.contains("重新生成"))
        // 原始时间要原样带出来，便于核对
        assertTrue(note.contains("2026-09-01"))
    }

    @Test
    fun `过期时间在未来_说明这不是过期的锅`() {
        val note = AccountChecks.expiryNote("2026-09-30 07:12:34 UTC", 401, probeAt)!!
        assertTrue("要给出到期时间：$note", note.contains("2026-09-30"))
        assertTrue("要说明还剩多久：$note", note.contains("还剩 6 天"))
        assertFalse("没过期就不能说已过期：$note", note.contains("已过期"))
    }

    @Test
    fun `401 且没有过期头_老实说查不到而不是编一个结论`() {
        val note = AccountChecks.expiryNote(null, 401, probeAt)!!
        assertTrue("要说清查不到：$note", note.contains("未回过期头"))
        assertTrue("要给出两个可能的原因：$note", note.contains("吊销"))
    }

    @Test
    fun `非 401 且没有过期头_不产出任何噪音`() {
        // 成功的探测不该往日志里塞一行「未回过期头」
        assertNull(AccountChecks.expiryNote(null, 200, probeAt))
        assertNull(AccountChecks.expiryNote("", 200, probeAt))
    }

    @Test
    fun `过期头无法解析_原样报出来`() {
        val note = AccountChecks.expiryNote("not-a-date", 401, probeAt)!!
        assertTrue("要带原始值便于排查：$note", note.contains("not-a-date"))
        assertTrue(note.contains("无法解析"))
    }

    @Test
    fun `正好在过期那一刻算已过期`() {
        // 边界：parsed == now 时必须算过期（否则会说「还剩 0 天」而 GitHub 已经拒收）
        assertTrue(AccountChecks.expiryNote(probeAtUtc, 401, probeAt)!!.contains("已过期"))
    }

    // ───────────────── 自动重探的时机 ─────────────────

    @Test
    fun `没查过的账号需要探测`() {
        assertTrue(AccountChecks.isStale(AccountStatus.UNKNOWN, 0L, probeAt))
    }

    @Test
    fun `刚查过就不重探_结论要稳定`() {
        // 这条是「老是报失效」的正解：结论已经写回，进页面不该再探一次
        assertFalse(AccountChecks.isStale(AccountStatus.INVALID, probeAt - 1000L, probeAt))
        assertFalse(AccountChecks.isStale(AccountStatus.OK, probeAt - 1000L, probeAt))
        assertFalse(AccountChecks.isStale(AccountStatus.UNREACHABLE, probeAt - 1000L, probeAt))
    }

    @Test
    fun `结论陈旧到超过窗口才重探`() {
        val justInside = probeAt - (AccountChecks.AUTO_RECHECK_MS - 1)
        val justOutside = probeAt - AccountChecks.AUTO_RECHECK_MS
        assertFalse(AccountChecks.isStale(AccountStatus.OK, justInside, probeAt))
        assertTrue(AccountChecks.isStale(AccountStatus.OK, justOutside, probeAt))
    }

    @Test
    fun `自己手动检查过之后立刻进页面不会再探`() {
        // 手动点「检查」→ lastCheck 刷新为此刻 → 紧接着进页面不该又探一次
        assertFalse(AccountChecks.isStale(AccountStatus.INVALID, probeAt, probeAt))
    }

    // ───────────────── token 指纹 ─────────────────

    @Test
    fun `指纹是稳定的且不含原文`() {
        val token = "ghp_abcdefghijklmnopqrstuvwxyz0123456789"
        val fp = AccountChecks.fingerprint(token)
        assertEquals(8, fp.length)
        assertEquals("同一 token 必须算出同一指纹", fp, AccountChecks.fingerprint(token))
        assertFalse("日志会被导出，绝不能带原文", fp.contains("ghp_"))
        assertTrue("应是十六进制", fp.all { it in "0123456789abcdef" })
    }

    @Test
    fun `不同 token 的指纹不同`() {
        assertFalse(AccountChecks.fingerprint("token-a") == AccountChecks.fingerprint("token-b"))
    }

    // ───────────────── 日志时刻 ─────────────────

    @Test
    fun `时刻格式化成人能读的形态`() {
        val s = stamp(probeAt)
        // 只断言形态（时区随机器变，不断言具体小时）
        assertTrue("实际：$s", Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""").matches(s))
    }
}
