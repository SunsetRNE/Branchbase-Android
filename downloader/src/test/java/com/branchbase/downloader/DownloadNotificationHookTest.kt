package com.branchbase.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 给第三方 Hook 读的 extras 载荷单测。
 *
 * 这些键名是**对外契约**（LSPosed 模块按名字读）：改动等于破坏兼容，
 * 所以用测试把「键名 + 值的类型/含义」钉住，防止以后顺手重命名。
 */
class DownloadNotificationHookTest {

    private val state = DownloadNotificationState(
        taskId = "task-7",
        title = "Branchbase.apk",
        fileName = "Branchbase.apk",
        status = DownloadStatus.RUNNING,
        downloadedBytes = 512L,
        totalBytes = 1024L,
    )

    @Test
    fun `键名带统一前缀`() {
        assertTrue(DownloadNotificationHook.EXTRA_TASK_ID.startsWith(DownloadNotificationHook.NAMESPACE))
        assertTrue(DownloadNotificationHook.EXTRA_PERCENT.startsWith(DownloadNotificationHook.NAMESPACE))
    }

    @Test
    fun `载荷字段与值一一对应`() {
        val payload = DownloadNotificationHook.payload(state, ongoing = true)
        assertEquals("task-7", payload[DownloadNotificationHook.EXTRA_TASK_ID])
        assertEquals("Branchbase.apk", payload[DownloadNotificationHook.EXTRA_TITLE])
        assertEquals("running", payload[DownloadNotificationHook.EXTRA_STATUS])
        assertEquals(512L, payload[DownloadNotificationHook.EXTRA_DOWNLOADED_BYTES])
        assertEquals(1024L, payload[DownloadNotificationHook.EXTRA_TOTAL_BYTES])
        assertEquals(50, payload[DownloadNotificationHook.EXTRA_PERCENT])
        assertEquals(true, payload[DownloadNotificationHook.EXTRA_ONGOING])
    }

    @Test
    fun `总量未知时百分比用负一而不是零`() {
        val unknown = state.copy(totalBytes = 0L, status = DownloadStatus.RUNNING)
        val payload = DownloadNotificationHook.payload(unknown, ongoing = true)
        // 0% 是合法进度，必须用 -1 表示「不知道」
        assertEquals(-1, payload[DownloadNotificationHook.EXTRA_PERCENT])
    }

    @Test
    fun `完成通知不标记为常驻`() {
        val done = state.copy(status = DownloadStatus.COMPLETED)
        val payload = DownloadNotificationHook.payload(done, ongoing = false)
        assertEquals("completed", payload[DownloadNotificationHook.EXTRA_STATUS])
        assertEquals(false, payload[DownloadNotificationHook.EXTRA_ONGOING])
    }
}
