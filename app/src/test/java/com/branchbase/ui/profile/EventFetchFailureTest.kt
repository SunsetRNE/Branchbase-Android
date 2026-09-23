package com.branchbase.ui.profile

import java.io.File
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

    /**
     * 失败留痕修好之后，**第一个被它抓出来的结论**：`/user/events` 根本不存在。
     *
     * ```
     * 23:21:43.053 事件源 /user/events 第 1 页失败：ERROR:未知错误: HTTP 404 Not Found
     * ```
     *
     * GitHub 的活动端点里，所谓「List events for the authenticated user」就是
     * `/users/{username}/events`（认证成该用户时返回里才含私有活动），**没有 `/user/events`**
     * （<https://docs.github.com/en/rest/activity/events>）。而旧代码是「先试 `/user/events`，
     * 空了再回退」——每次进动态页都要先白等一次注定 404 的往返；更糟的是回退方向在
     * 「看别人的主页」时写反了（会去取**你自己**的活动，显示在别人的动态里）。
     *
     * 这条钉子钉住「那条腿不许回来」：只要 ProfileScreen 里再出现 `/user/events`，
     * 就说明有人又把「认证用户自己的活动」当成一个独立端点写了。
     */
    @Test
    fun `事件源只留 users 那一条腿`() {
        val file = File("src/main/java/com/branchbase/ui/profile/ProfileScreen.kt")
        assertTrue("找不到源文件：${file.absolutePath}", file.exists())
        val src = file.readText()
        assertFalse(
            "`/user/events` 是 404 端点（真机实测）：不许再当成一条可用的腿去试",
            src.contains("\"/user/events\""),
        )
        assertTrue(
            "唯一的源应该是 /users/{login}/events",
            src.contains("val source = \"/users/\$login/events\""),
        )
    }
}
