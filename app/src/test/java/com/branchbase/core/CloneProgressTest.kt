package com.branchbase.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * clone 进度解析与百分比口径的钉子（`core/src/git/progress.rs` 的 Kotlin 侧）。
 *
 * 这一层最容易出的两种错，都是「看起来没问题」的那种：
 *
 * 1. **假百分比**：分母未知时顺手 `received / max(total, 1)` —— 进度条冲到 100% 然后
 *    卡在那里等半天；或者把 total=0 当成「已完成」。所以「分母未知 → `null`（不确定态）」
 *    是硬约定，这里逐阶段钉住。
 * 2. **阶段回退 / 越界**：`received` 偶尔会等于乃至超过 `total`（重传、服务端计数口径），
 *    没夹紧就会画出 73% 然后跳回 68%，或者 105% 的进度条。
 */
class CloneProgressTest {

    private fun progress(
        phase: String,
        received: Int = 0,
        total: Int = 0,
        indexed: Int = 0,
        bytes: Long = 0,
        checkoutDone: Int = 0,
        checkoutTotal: Int = 0,
    ) = CloneProgress.parse(
        """{"phase":"$phase","received":$received,"total":$total,"indexed":$indexed,""" +
            """"bytes":$bytes,"checkoutDone":$checkoutDone,"checkoutTotal":$checkoutTotal}""",
    )!!

    @Test
    fun `解析引擎快照的全部字段`() {
        val p = progress("receive", received = 142, total = 380, indexed = 120, bytes = 1024, checkoutDone = 0, checkoutTotal = 0)
        assertEquals(CloneProgress.Phase.RECEIVE, p.phase)
        assertEquals(142, p.received)
        assertEquals(380, p.total)
        assertEquals(120, p.indexed)
        assertEquals(1024L, p.bytes)
        assertTrue(p.running)
    }

    @Test
    fun `认不出的阶段退回 UNKNOWN 而不是抛异常`() {
        assertEquals(CloneProgress.Phase.UNKNOWN, progress("deepening").phase)
        assertNull("UNKNOWN 没有可用的百分比", progress("deepening").percent)
    }

    @Test
    fun `脏 JSON 返回 null 而不是零进度`() {
        // 零进度会让进度条打回原点 —— 看起来像「重新开始拉了」，比不更新更糟
        assertNull(CloneProgress.parse(""))
        assertNull(CloneProgress.parse("not json"))
        assertNull(CloneProgress.parse("[1,2,3]"))
    }

    @Test
    fun `缺字段按 0 处理，不崩`() {
        val p = CloneProgress.parse("""{"phase":"connect"}""")!!
        assertEquals(CloneProgress.Phase.CONNECT, p.phase)
        assertEquals(0, p.total)
    }

    @Test
    fun `接收阶段按已收比总数落在 5 到 70 之间`() {
        assertEquals(5, progress("receive", received = 0, total = 100).percent)
        assertEquals(37, progress("receive", received = 50, total = 100).percent)
        assertEquals(70, progress("receive", received = 100, total = 100).percent)
    }

    @Test
    fun `分母未知时不给百分比`() {
        // 远端没报对象总数：进度条走不确定态，而不是假装按已收数算得出比例
        assertNull(progress("receive", received = 142, total = 0).percent)
        assertNull(progress("resolve", indexed = 142, total = 0).percent)
        assertNull(progress("checkout", checkoutDone = 3, checkoutTotal = 0).percent)
    }

    @Test
    fun `解析增量阶段接在接收之后`() {
        assertEquals(70, progress("resolve", indexed = 0, total = 100).percent)
        assertEquals(85, progress("resolve", indexed = 100, total = 100).percent)
    }

    @Test
    fun `检出阶段收在 99 而不是 100`() {
        assertEquals(85, progress("checkout", checkoutDone = 0, checkoutTotal = 200).percent)
        // 100% 只留给「写完引用」那一刻：检出直接画到 100，收尾阶段就会从 100 退回 99
        assertEquals(99, progress("checkout", checkoutDone = 200, checkoutTotal = 200).percent)
        assertEquals(100, progress("done").percent)
    }

    @Test
    fun `百分比随阶段单调不减`() {
        // 顺序与引擎的 phase 迁移一致：收 → 解 → 检出 → 收尾 → 完成
        val walk = listOf(
            progress("connect"),
            progress("receive", received = 1, total = 100),
            progress("receive", received = 100, total = 100),
            progress("resolve", indexed = 50, total = 100),
            progress("resolve", indexed = 100, total = 100),
            progress("checkout", checkoutDone = 10, checkoutTotal = 100),
            progress("checkout", checkoutDone = 100, checkoutTotal = 100),
            progress("finalize"),
            progress("done"),
        ).map { it.percent ?: -1 }
        assertEquals("进度条不能往回跳（含「检出满 → 收尾」这一步）", walk.sorted(), walk)
    }

    @Test
    fun `计数越界被夹住`() {
        // 服务端计数口径不一致 / 重传时 received 可能超过 total：进度条不能画到 105%
        assertEquals(70, progress("receive", received = 130, total = 100).percent)
        assertEquals(99, progress("checkout", checkoutDone = 130, checkoutTotal = 100).percent)
    }

    @Test
    fun `终态与空闲不算运行中`() {
        assertFalse(progress("done").running)
        assertFalse(progress("failed").running)
        assertFalse(progress("idle").running)
        assertTrue(progress("finalize").running)
    }
}
