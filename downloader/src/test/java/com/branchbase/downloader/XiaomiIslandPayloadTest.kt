package com.branchbase.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 小米超级岛参数（`miui.focus.param`）的构造单测。
 *
 * 这段 JSON 会被系统当成「岛参数」解析：拼错一个引号（标题里带引号是常态，
 * 文件名来自网络）整条参数就被丢弃，岛不显示，而且**没有任何报错**。
 * 所以这里至少钉住三件事：结构合法、必填字段在、字符串一定转义。
 */
class XiaomiIslandPayloadTest {

    private fun state(
        status: DownloadStatus = DownloadStatus.RUNNING,
        title: String = "Branchbase.apk",
        downloaded: Long = 512L,
        total: Long = 1024L,
    ) = DownloadNotificationState(
        taskId = "task-1",
        title = title,
        fileName = title,
        status = status,
        downloadedBytes = downloaded,
        totalBytes = total,
    )

    @Test
    fun `参数键与官方文档一致`() {
        assertEquals("miui.focus.param", XiaomiIslandPayload.EXTRA_PARAM)
        assertEquals("miui.focus.pics", XiaomiIslandPayload.EXTRA_PICS)
    }

    @Test
    fun `JSON 结构完整且字段就位`() {
        val json = XiaomiIslandPayload.build(state())
        assertTrue(json.startsWith("{\"param_v2\":{"))
        assertTrue(json.endsWith("}}"))
        assertTrue(json.contains("\"protocol\":1"))
        assertTrue(json.contains("\"business\":\"file_download\""))
        assertTrue(json.contains("\"param_island\":{\"islandProperty\":1}"))
        assertTrue(json.contains("\"ticker\":\"正在下载 · 50% · 512 B / 1.0 KB\""))
        assertTrue(json.contains("\"title\":\"Branchbase.apk\""))
        assertTrue(json.contains("\"content\":\"512 B / 1.0 KB\""))
    }

    @Test
    fun `没有焦点通知权限时不允许吞掉普通通知`() {
        // 官方字段含义：false = 权限被关时按普通通知显示（true 才会被过滤掉）
        assertTrue(XiaomiIslandPayload.build(state()).contains("\"filterWhenNoPermission\":false"))
    }

    @Test
    fun `下载中可更新结束后不可更新`() {
        assertTrue(XiaomiIslandPayload.build(state(status = DownloadStatus.RUNNING)).contains("\"updatable\":true"))
        assertFalse(XiaomiIslandPayload.build(state(status = DownloadStatus.COMPLETED)).contains("\"updatable\":true"))
    }

    @Test
    fun `标题里的引号与反斜杠被转义`() {
        assertEquals("a\\\"b", XiaomiIslandPayload.escape("a\"b"))
        assertEquals("a\\\\b", XiaomiIslandPayload.escape("a\\b"))
        assertEquals("a\\nb", XiaomiIslandPayload.escape("a\nb"))
        assertEquals("a\\tb", XiaomiIslandPayload.escape("a\tb"))
    }

    @Test
    fun `控制字符转成 unicode 转义`() {
        assertEquals("\\u0001", XiaomiIslandPayload.escape("\u0001"))
    }

    @Test
    fun `中文与百分号原样保留`() {
        assertEquals("发布说明 v1.2.0（100%）", XiaomiIslandPayload.escape("发布说明 v1.2.0（100%）"))
    }

    @Test
    fun `带引号的标题不会拼出非法 JSON`() {
        val json = XiaomiIslandPayload.build(state(title = "a\"b.apk"))
        assertTrue(json.contains("\"title\":\"a\\\"b.apk\""))
        // 引号必须成对：未转义的引号会让 JSON 解析直接失败
        val unescapedQuotes = json.filterIndexed { index, ch ->
            ch == '"' && (index == 0 || json[index - 1] != '\\')
        }.length
        assertEquals(0, unescapedQuotes % 2)
    }
}
