package com.branchbase.downloader

import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 下载失败的**分类钉子**。
 *
 * 背景：引擎原来把 `e.message` 原样透传，通知栏与发布页会出现
 * `Unable to resolve host "api.github.com": No address associated with hostname`
 * 这类英文堆栈。后来收口成「中文结论」，又进一步改成**稳定分类**：
 *
 * - 模型层只带 [DownloadErrorCode]，**文案在渲染时按界面语言解析**
 *   （通知也是非 Compose 表面，本来就走 `Context.getString`）；
 * - 所以这里的断言从「文案里有没有『超时』两个字」变成了**断言枚举** ——
 *   分类逻辑与文案彻底分开，改译文不会让这些用例红。
 *
 * 文案本身的**结构**（占位符一致性、中英覆盖率）由 `tools/i18n/check-i18n.py` 在 CI 里守。
 */
class DownloadErrorsTest {

    @Test
    fun `常见网络异常映射成稳定分类`() {
        val cases = listOf(
            UnknownHostException("api.github.com") to DownloadErrorCode.UNKNOWN_HOST,
            SocketTimeoutException("timeout") to DownloadErrorCode.CONNECT_TIMEOUT,
            SSLHandshakeException("bad certificate") to DownloadErrorCode.TLS_ERROR,
            ConnectException("Connection refused") to DownloadErrorCode.CONNECT_FAILED,
            InterruptedIOException("interrupted") to DownloadErrorCode.INTERRUPTED,
            FileNotFoundException("/x") to DownloadErrorCode.FILE_NOT_FOUND,
            IOException("Connection reset by peer") to DownloadErrorCode.IO_ERROR,
        )
        cases.forEach { (error, expected) ->
            assertEquals("$error", expected, DownloadErrors.forException(error).code)
        }
    }

    @Test
    fun `超时不会被宽泛的 IO 异常吞掉`() {
        // SocketTimeoutException / SSLException 都是 IOException 的子类：
        // 判断顺序反了会全部退化成「网络中断」，用户就不知道「等一下再试」还是「检查证书」
        assertEquals(
            DownloadErrorCode.CONNECT_TIMEOUT,
            DownloadErrors.forException(SocketTimeoutException()).code,
        )
        assertEquals(
            DownloadErrorCode.TLS_ERROR,
            DownloadErrors.forException(SSLHandshakeException("x")).code,
        )
    }

    @Test
    fun `HTTP 状态码给出对症结论`() {
        assertEquals(DownloadErrorCode.NOT_FOUND, DownloadErrors.forStatus(404).code)
        assertEquals(DownloadErrorCode.FORBIDDEN, DownloadErrors.forStatus(403).code)
        assertEquals(DownloadErrorCode.UNAUTHORIZED, DownloadErrors.forStatus(401).code)
        assertEquals(DownloadErrorCode.TOO_MANY_REQUESTS, DownloadErrors.forStatus(429).code)
        // 5xx 与其它 4xx 都要**带上状态码**：文案里要把 HTTP xxx 显示给用户
        assertEquals(DownloadErrorCode.SERVER_ERROR, DownloadErrors.forStatus(503).code)
        assertEquals(listOf(503), DownloadErrors.forStatus(503).args)
        assertEquals(DownloadErrorCode.HTTP_OTHER, DownloadErrors.forStatus(418).code)
        assertEquals(listOf(418), DownloadErrors.forStatus(418).args)
    }

    @Test
    fun `未知异常带上异常类名当兜底参数`() {
        // 兜底文案是「下载失败（%1$s），请重试」，%1$s 用异常类名，方便用户报障时复制
        val failure = DownloadErrors.forException(IllegalStateException("boom"))
        assertEquals(DownloadErrorCode.UNKNOWN, failure.code)
        assertEquals(listOf("IllegalStateException"), failure.args)
    }

    // 「每个错误码都有文案资源」这条**不写成用例**：`messageResOf` 的 `when` 没有 else 分支，
    // 加新枚举值会直接编译失败 —— 编译器已经兜住了，再写一条只会为了测试而放宽可见性。
}
