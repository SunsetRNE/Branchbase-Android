package com.branchbase.downloader

import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载失败文案的**中文钉子**。
 *
 * 背景：引擎原来把 `e.message` 原样透传，通知栏与发布页会出现
 * `Unable to resolve host "api.github.com": No address associated with hostname`
 * 这类英文堆栈。这些用例同时钉两件事：
 * 1. 常见失败都有对症的中文结论；
 * 2. 文案里不出现英文单词（`HTTP` / `TLS` 这类缩写除外）。
 */
class DownloadErrorsTest {

    /** 允许出现在用户文案里的技术缩写（其余必须是中文）。 */
    private val allowedTokens = listOf("HTTP", "GitHub", "TLS")

    private fun assertChineseCopy(text: String) {
        val stripped = allowedTokens.fold(text) { acc, token -> acc.replace(token, "") }
        val englishWord = Regex("[A-Za-z]{3,}").find(stripped)
        assertFalse("文案里不应出现英文单词：\"$text\"（命中 \"${englishWord?.value}\"）", englishWord != null)
        assertTrue("文案不该为空", text.isNotBlank())
    }

    @Test
    fun `常见网络异常映射成中文`() {
        val cases = listOf(
            UnknownHostException("api.github.com") to "网络不可达",
            SocketTimeoutException("timeout") to "连接超时",
            SSLHandshakeException("bad certificate") to "安全连接失败",
            ConnectException("Connection refused") to "无法连接服务器",
            InterruptedIOException("interrupted") to "下载被中断",
            FileNotFoundException("/x") to "文件不存在",
            IOException("Connection reset by peer") to "网络中断",
        )
        cases.forEach { (error, expected) ->
            val text = DownloadErrors.fromException(error)
            assertTrue("$error → $text", text.contains(expected))
            assertChineseCopy(text)
        }
    }

    @Test
    fun `超时不会被宽泛的 IO 异常吞掉`() {
        // SocketTimeoutException / SSLException 都是 IOException 的子类：
        // 判断顺序反了会全部退化成「网络中断」，用户就不知道「等一下再试」还是「检查证书」
        assertTrue(DownloadErrors.fromException(SocketTimeoutException()).contains("连接超时"))
        assertTrue(DownloadErrors.fromException(SSLHandshakeException("x")).contains("安全连接"))
    }

    @Test
    fun `HTTP 状态码给出对症结论且全中文`() {
        assertEquals("文件不存在或已被删除（HTTP 404）", DownloadErrors.fromStatus(404))
        assertEquals("没有下载该文件的权限（HTTP 403）", DownloadErrors.fromStatus(403))
        assertTrue(DownloadErrors.fromStatus(401).contains("重新登录"))
        assertTrue(DownloadErrors.fromStatus(429).contains("过于频繁"))
        assertTrue(DownloadErrors.fromStatus(503).contains("服务端错误"))
        assertTrue(DownloadErrors.fromStatus(418).contains("下载失败"))
        listOf(400, 401, 403, 404, 408, 410, 429, 500, 503, 418).forEach { code ->
            assertChineseCopy(DownloadErrors.fromStatus(code))
        }
    }

    @Test
    fun `未知异常也有中文兜底`() {
        val text = DownloadErrors.fromException(IllegalStateException("boom"))
        assertTrue(text.startsWith("下载失败"))
        assertTrue(text.contains("请重试"))
    }
}
