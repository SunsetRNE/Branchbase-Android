package com.branchbase.ui.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「运行中怎么持续获取状态」的策略单测。
 *
 * 这些数字不是拍的，是权衡的结果，所以钉住：
 * - **间隔阶梯**：刚点进来那两分钟 5s（用户正盯着看），之后 15s，再之后 30s；
 *   CI 动辄十几分钟，一直 5s 一次十几次请求纯属浪费；
 * - **计费网络只降速不停止**：用户正开着这一页，停了等于功能坏了；抬到 15s 下限即可；
 * - **日志「还没生成」≠「失败」**：远端日志 blob 只有 job 结束后才存在。
 */
class RunPollPolicyTest {

    // ── 自适应间隔 ──

    @Test
    fun `间隔按阶梯放大`() {
        assertEquals(5_000L, RunPollPolicy.intervalMs(0))
        assertEquals(5_000L, RunPollPolicy.intervalMs(RunPollPolicy.FAST_POLLS - 1))
        assertEquals(15_000L, RunPollPolicy.intervalMs(RunPollPolicy.FAST_POLLS))
        assertEquals(15_000L, RunPollPolicy.intervalMs(RunPollPolicy.MID_POLLS - 1))
        assertEquals(30_000L, RunPollPolicy.intervalMs(RunPollPolicy.MID_POLLS))
        // 上限就是 30s，不随次数继续放大（否则长跑的工作流会「看起来死了」）
        assertEquals(30_000L, RunPollPolicy.intervalMs(10_000))
    }

    @Test
    fun `计费网络只抬高下限 不停止轮询`() {
        // 用户正开着这一页看，停止轮询等于功能坏了 —— 所以是降速而不是中断
        assertEquals(15_000L, RunPollPolicy.intervalMs(0, metered = true))
        assertEquals(15_000L, RunPollPolicy.intervalMs(RunPollPolicy.FAST_POLLS, metered = true))
        // 本来就慢于下限的档位不受影响
        assertEquals(30_000L, RunPollPolicy.intervalMs(RunPollPolicy.MID_POLLS, metered = true))
    }

    // ── 失败退避 ──

    @Test
    fun `失败退避是指数且封顶`() {
        assertEquals(0L, RunPollPolicy.backoffMs(0))
        assertEquals(5_000L, RunPollPolicy.backoffMs(1))
        assertEquals(10_000L, RunPollPolicy.backoffMs(2))
        assertEquals(20_000L, RunPollPolicy.backoffMs(3))
        assertEquals(40_000L, RunPollPolicy.backoffMs(4))
        // 第 5 次本该 80s，被 60s 上限截住
        assertEquals(60_000L, RunPollPolicy.backoffMs(5))
        assertEquals(60_000L, RunPollPolicy.backoffMs(99))
    }

    // ── 该不该轮 ──

    @Test
    fun `只有运行中且在前台才轮询`() {
        assertTrue(RunPollPolicy.shouldPoll("in_progress", foreground = true))
        assertFalse(RunPollPolicy.shouldPoll("completed", foreground = true))
        assertFalse(RunPollPolicy.shouldPoll("queued", foreground = true))
        // 退到后台即停（前台服务/常驻轮询都被这个决定排除了）
        assertFalse(RunPollPolicy.shouldPoll("in_progress", foreground = false))
        // 状态还没拿到（首帧）时不轮
        assertFalse(RunPollPolicy.shouldPoll(null, foreground = true))
    }

    // ── 日志「还没生成」≠「失败」 ──

    @Test
    fun `job 没结束时日志属于还没生成`() {
        assertTrue(RunPollPolicy.isLogPending("in_progress"))
        assertTrue(RunPollPolicy.isLogPending("queued"))
        assertTrue(RunPollPolicy.isLogPending("waiting"))
        // 只有结束了才拿得到日志；此时再取不到才是真失败
        assertFalse(RunPollPolicy.isLogPending("completed"))
    }

    // ── 回前台对齐 ──

    @Test
    fun `首次进入不算回到前台`() {
        // 首次进入由正常加载负责；把它也算成「回到前台」会让每次开页都多打一次网络
        assertFalse(RunPollPolicy.resumeShouldForceRefresh(seenStart = false))
        assertTrue(RunPollPolicy.resumeShouldForceRefresh(seenStart = true))
    }
}
