package com.branchbase.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件名净化与进度的纯逻辑单测。
 *
 * 素材名来自网络（release asset 名 / 远端 Content-Disposition），**不可信**：
 * 这些用例钉住的就是「不可信输入不会写到下载目录之外」。
 */
class DownloadPathsTest {

    @Test
    fun `路径分隔符不会逃出下载目录`() {
        assertEquals("passwd", DownloadPaths.sanitize("../../etc/passwd"))
        assertEquals("evil.apk", DownloadPaths.sanitize("..\\..\\evil.apk"))
        assertFalse(DownloadPaths.sanitize("../../x").contains("/"))
        assertFalse(DownloadPaths.sanitize("../../x").contains("\\"))
    }

    @Test
    fun `控制字符与非法字符被替换`() {
        assertEquals("a_b", DownloadPaths.sanitize("a\u0000b"))
        assertEquals("a_b.txt", DownloadPaths.sanitize("a:b.txt"))
        assertEquals("a_b.txt", DownloadPaths.sanitize("a*b.txt"))
        assertEquals("a_b.txt", DownloadPaths.sanitize("a|b.txt"))
    }

    @Test
    fun `中文与常见符号保留`() {
        assertEquals("发布说明 v1.2.0.md", DownloadPaths.sanitize("发布说明 v1.2.0.md"))
        assertEquals("Branchbase-1.0.13 (113).apk", DownloadPaths.sanitize("Branchbase-1.0.13 (113).apk"))
    }

    @Test
    fun `空名与纯点号有兜底`() {
        assertEquals("download", DownloadPaths.sanitize(""))
        assertEquals("download", DownloadPaths.sanitize(".."))
        assertEquals("download", DownloadPaths.sanitize("../"))
        assertEquals("download", DownloadPaths.sanitize("   "))
    }

    @Test
    fun `前导点号被去掉`() {
        assertEquals("hidden.txt", DownloadPaths.sanitize(".hidden.txt"))
    }

    @Test
    fun `超长文件名被截断`() {
        val long = "a".repeat(300) + ".apk"
        assertTrue(DownloadPaths.sanitize(long).length <= 100)
    }

    @Test
    fun `字节数文案`() {
        assertEquals("512 B", DownloadPaths.formatBytes(512))
        assertEquals("1.0 KB", DownloadPaths.formatBytes(1024))
        assertEquals("1.5 MB", DownloadPaths.formatBytes(1024L * 1024L * 3 / 2))
    }

    @Test
    fun `进度在总量未知时为 null 完成时为 1`() {
        val request = DownloadRequest(id = "1", url = "https://example.com/a", fileName = "a")
        val running = DownloadTask(id = "1", request = request, status = DownloadStatus.RUNNING, downloadedBytes = 10)
        assertEquals(null, running.progress)
        assertEquals(1f, running.copy(status = DownloadStatus.COMPLETED).progress ?: -1f, 0f)
        assertEquals(0.5f, running.copy(totalBytes = 100, downloadedBytes = 50).progress ?: -1f, 0f)
    }
}
