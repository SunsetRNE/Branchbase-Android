package com.branchbase.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 事件源抓取失败**留痕**的单测（2026-09-22 修）。
 *
 * ## 修的是什么
 *
 * 旧代码是这么写的：
 * ```
 * val pageJson = PageCache.refresh(...) { RustBridge.getJson(...) } ?: break   // ← 静默退出
 * if (pageJson.startsWith("ERROR:")) { Logger.net("事件源 … 失败：…") }        // ← 永远到不了
 * ```
 * `PageCache.refresh` 按契约把 `ERROR:` 串吞成 `null`，所以第二行是**死代码**：
 * 「失败留痕」从写下的那天起就没生效过。
 *
 * 真机日志（同一份，v1.0.64）里的证据是**数量对不上**：
 * 3 次 `跳过刚失败过的事件源 /user/events`、**0 次** `事件源 … 第 N 页失败`。
 * 于是「`/user/events` 为什么总是不走」这个问题，日志里一个字都没有 ——
 * 是 401（令牌类型不支持）？404（端点没有）？还是断网？三种原因的处置完全不同。
 *
 * ## 钉住什么
 *
 * 1. 三种失败在日志里必须**能分开**（无响应 / 空响应 / `ERROR:` 原文）；
 * 2. `eventFetchFailure` 不许把 `ERROR:` 原文吞掉 —— 它是唯一能给出 4xx/5xx 的线索；
 * 3. 长度要截断（响应体可能带整页 HTML，别把日志撑成散文）。
 */
class EventFetchFailureTest {

    @Test
    fun `没拿到响应要说清是没响应`() {
        assertEquals("无响应（未联网 / 请求未发出）", eventFetchFailure(null))
    }

    @Test
    fun `空响应与没响应不是一回事`() {
        val blank = eventFetchFailure("   ")
        assertTrue("空响应要单独成一句：$blank", blank.contains("空响应"))
        assertTrue("不能和「没响应」混成一句", blank != eventFetchFailure(null))
    }

    @Test
    fun `ERROR 串要原样带出来_这是唯一能看出 401 和 404 的线索`() {
        val raw = """ERROR:未知错误: HTTP 401 Unauthorized: {"message":"Bad credentials"}"""
        val msg = eventFetchFailure(raw)
        assertTrue("401 必须出现在日志里：$msg", msg.contains("401"))
        assertTrue("原始响应体要留着：$msg", msg.contains("Bad credentials"))
        assertFalse("不许再吞成一句笼统的「失败」", msg.contains("无响应"))
    }

    @Test
    fun `超长响应要截断_别把一行日志撑成散文`() {
        val msg = eventFetchFailure("ERROR:" + "x".repeat(1000))
        assertEquals("截断到 140 字符", 140, msg.length)
    }
}
