package com.branchbase.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知态快照的纯逻辑单测。
 *
 * 进度语义（百分比、不确定态、文案）是**通知栏与厂商岛上共用的那一份**，
 * 算错的表现是「进度条冲过 100%」「总量未知时卡在 0%」，都只有真机能看出来。
 */
class DownloadNotificationStateTest {

    private fun state(
        status: DownloadStatus = DownloadStatus.RUNNING,
        downloaded: Long = 0L,
        total: Long = 0L,
        title: String = "app.apk",
    ) = DownloadNotificationState(
        taskId = "task-1",
        title = title,
        fileName = title,
        status = status,
        downloadedBytes = downloaded,
        totalBytes = total,
    )

    @Test
    fun `总量已知时按字节数算百分比`() {
        assertEquals(50, state(downloaded = 512, total = 1024).percent)
        assertEquals(0, state(downloaded = 0, total = 1024).percent)
    }

    @Test
    fun `百分比不会超过一百`() {
        assertEquals(100, state(downloaded = 4096, total = 1024).percent)
    }

    @Test
    fun `总量未知时没有百分比`() {
        assertNull(state(downloaded = 512, total = 0).percent)
    }

    @Test
    fun `完成后即使总量未知也是百分之百`() {
        val done = state(status = DownloadStatus.COMPLETED, downloaded = 512, total = 0)
        assertEquals(100, done.percent)
        assertFalse(done.indeterminate)
    }

    @Test
    fun `只有还在跑且总量未知才是不确定态`() {
        assertTrue(state(status = DownloadStatus.RUNNING, total = 0).indeterminate)
        assertTrue(state(status = DownloadStatus.QUEUED, total = 0).indeterminate)
        assertFalse(state(status = DownloadStatus.FAILED, total = 0).indeterminate)
    }

    @Test
    fun `下载中的文案带百分比与字节数`() {
        assertEquals(
            "正在下载 · 50% · 512 B / 1.0 KB",
            state(downloaded = 512, total = 1024).progressText,
        )
    }

    @Test
    fun `总量未知时文案不写百分比`() {
        assertEquals("正在下载 · 512 B", state(downloaded = 512, total = 0).progressText)
    }

    @Test
    fun `结束后文案不再说正在下载`() {
        assertEquals("下载完成 · 1.0 KB", state(status = DownloadStatus.COMPLETED, downloaded = 1024).progressText)
        assertEquals("已下载 · 1.0 KB", state(status = DownloadStatus.FAILED, downloaded = 1024).progressText)
    }

    @Test
    fun `状态键覆盖全部状态`() {
        assertEquals("queued", state(status = DownloadStatus.QUEUED).statusKey)
        assertEquals("running", state(status = DownloadStatus.RUNNING).statusKey)
        assertEquals("completed", state(status = DownloadStatus.COMPLETED).statusKey)
        assertEquals("failed", state(status = DownloadStatus.FAILED).statusKey)
        assertEquals("canceled", state(status = DownloadStatus.CANCELED).statusKey)
    }

    @Test
    fun `从任务快照派生时字段一一对应`() {
        val task = DownloadTask(
            id = "task-9",
            request = DownloadRequest(
                id = "task-9",
                url = "https://example.com/a.apk",
                fileName = "a.apk",
                title = "示例",
            ),
            status = DownloadStatus.RUNNING,
            downloadedBytes = 10L,
            totalBytes = 40L,
        )
        val derived = DownloadNotificationState.of(task)
        assertEquals("task-9", derived.taskId)
        assertEquals("示例", derived.title)
        assertEquals("a.apk", derived.fileName)
        assertEquals(25, derived.percent)
        assertEquals(DownloadStatus.RUNNING, derived.status)
    }
}
