package com.branchbase.ui.log

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 慢帧明细的限流规则：**更慢的帧必须留下明细**。
 *
 * 真机上踩过：小结写着「最慢 240.9ms」，明细里最慢只有 179.8ms —— 那两帧挨得太近，
 * 被 400ms 限流吃掉了。而最慢的那几帧恰恰是唯一想看的样本，被限流吞掉等于仪表失效。
 */
class FrameWatchThrottleTest {

    private val t0 = 1_000_000L

    @Test
    fun `够久没记过就记`() {
        assertTrue(FrameWatch.shouldLogFrame(35.0, t0 + 500, lastAtMs = t0, lastTotalMs = 0.0))
    }

    @Test
    fun `限流窗口内，更慢的帧仍然要记`() {
        // 距上次记录只有 100ms，但这一帧 60ms 比上次记的 50ms 更慢 → 记
        assertTrue(FrameWatch.shouldLogFrame(60.0, t0 + 100, lastAtMs = t0, lastTotalMs = 50.0))
    }

    @Test
    fun `限流窗口内，更快的小慢帧不记`() {
        // 40ms < 上次记的 50ms，且只隔了 100ms → 不记（防连击刷屏）
        assertFalse(FrameWatch.shouldLogFrame(40.0, t0 + 100, lastAtMs = t0, lastTotalMs = 50.0))
    }

    @Test
    fun `不到慢帧阈值的一律不记`() {
        assertFalse(FrameWatch.shouldLogFrame(20.0, t0 + 5_000, lastAtMs = t0, lastTotalMs = 0.0))
        assertFalse(FrameWatch.shouldLogFrame(31.9, t0 + 5_000, lastAtMs = t0, lastTotalMs = 0.0))
        assertTrue(FrameWatch.shouldLogFrame(32.0, t0 + 5_000, lastAtMs = t0, lastTotalMs = 0.0))
    }

    @Test
    fun `单调升级保证最慢的那一帧一定在明细里`() {
        // 一串挨得很近的帧：40 → 90 → 120 → 60；限流只该滤掉最后那个 60
        var lastAt = t0
        var lastTotal = 0.0
        val logged = mutableListOf<Double>()
        listOf(40.0, 90.0, 120.0, 60.0).forEachIndexed { i, ms ->
            val now = t0 + i * 50L // 全部落在 400ms 限流窗口内
            if (FrameWatch.shouldLogFrame(ms, now, lastAt, lastTotal)) {
                logged += ms
                lastAt = now
                lastTotal = ms
            }
        }
        assertTrue("最慢的 120ms 必须在明细里：$logged", logged.contains(120.0))
        assertFalse("比已记录更慢的 40/90 也要留：$logged", logged.isEmpty())
        assertFalse("限流仍要生效（60ms 不比已记录的 120ms 慢）：$logged", logged.last() == 60.0)
    }
}
