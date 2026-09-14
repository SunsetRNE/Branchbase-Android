package com.branchbase.ui.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `FrameWatch` 的两条日志格式（纯函数，纯 JVM）。
 *
 * 为什么要钉住格式：这两行是**给人和给脚本同时看**的 ——
 * 人要在「设置 → 日志」里一眼看出「这一帧是主线程没空还是画得慢」（靠打星那一段），
 * 脚本（CI / 事后分析）要能按固定列切。格式一漂，两边都会静默失效。
 */
class FrameWatchFormatTest {

    /** 一次真实的 148ms 慢帧：等待占大头（主线程被占住）。 */
    private fun waitHeavy() = doubleArrayOf(
        121.5, // 等待
        0.4,   // 输入
        3.2,   // 动画
        0.1,   // 布局
        12.6,  // 绘制
        0.3,   // 上传
        4.1,   // 下发
        6.0,   // 交换
        148.2, // 总计
    )

    @Test
    fun `最长的那一段被星号标出来`() {
        val line = FrameWatch.formatSlowFrame(waitHeavy(), dropped = 0, page = "进入设置页")
        assertTrue("总计要写在最前面：$line", line.startsWith("慢帧 148.2ms（"))
        assertTrue("等待段该带星号（这一段最长）：$line", line.contains("等待 121.5*"))
        assertFalse("其它段不该带星号：$line", line.contains("绘制 12.6*"))
        assertTrue("要带上页面归属：$line", line.contains("页面「进入设置页」"))
        assertFalse("没有漏采就不要写漏采：$line", line.contains("漏采"))
    }

    @Test
    fun `画得慢的帧星号落在绘制上`() {
        val parts = doubleArrayOf(1.0, 0.0, 0.2, 0.1, 40.0, 1.0, 2.0, 3.0, 47.3)
        val line = FrameWatch.formatSlowFrame(parts, dropped = 0, page = "切换到「仓库」")
        assertTrue(line.contains("绘制 40.0*"))
        assertTrue(line.contains("页面「切换到「仓库」」"))
    }

    @Test
    fun `有漏采时把漏采数记上`() {
        val line = FrameWatch.formatSlowFrame(waitHeavy(), dropped = 3, page = null)
        assertTrue(line.contains("漏采 3"))
        assertFalse("没有页面上下文时不该凭空造一个：$line", line.contains("页面"))
    }

    @Test
    fun `每分钟小结写清次数与最慢`() {
        val line = FrameWatch.formatSummary(
            windowMs = 60_000,
            worstMs = 148.2,
            worstPage = "进入设置页",
            slowCount = 3,
            overBudget = 27,
            frames = 540,
        )
        assertEquals("近 60s 慢帧 3 次（最慢 148.2ms，进入设置页）；超 16ms 27/540 帧", line)
    }

    @Test
    fun `分段下标与帧明细一一对应`() {
        // 顺序错了会静默串栏（等待当成绘制），所以把下标本身也钉住
        assertEquals(0, FrameWatch.P_WAIT)
        assertEquals(4, FrameWatch.P_DRAW)
        assertEquals(8, FrameWatch.P_TOTAL)
    }

    /**
     * 真机上踩过：慢帧日志本身也是「UI 类」日志，于是下一条慢帧把上一条的正文当成了「页面」，
     * 日志里出现「页面「慢帧 …页面「慢帧 …」」」的五层套娃。
     */
    @Test
    fun `页面归属跳过慢帧自己的日志`() {
        LogManager.clear()
        LogManager.log(LogCategory.UI_RENDER, LogLevel.INFO, "Compose", "进入设置页")
        LogManager.log(LogCategory.UI_RENDER, LogLevel.INFO, FrameWatch.TAG, "慢帧 54.7ms（…）")

        assertEquals("进入设置页", LogManager.lastUiMessage(excludeTag = FrameWatch.TAG))
        assertEquals("慢帧 54.7ms（…）", LogManager.lastUiMessage())
    }

    @Test
    fun `超长页面名会被截断`() {
        val line = FrameWatch.formatSlowFrame(waitHeavy(), dropped = 0, page = "慢".repeat(200))
        assertTrue("只保留前 60 个字符：$line", line.contains("页面「" + "慢".repeat(60) + "」"))
        assertFalse("不该原样塞进去：$line", line.contains("慢".repeat(61)))
    }
}
