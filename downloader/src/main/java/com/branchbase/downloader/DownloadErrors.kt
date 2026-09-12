package com.branchbase.downloader

import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.ClosedByInterruptException
import javax.net.ssl.SSLException

/**
 * 下载失败的用户文案（**只出中文，且只出用户能行动的结论**）。
 *
 * 为什么单独收口：引擎里抛出来的都是网络栈的原始异常，例如
 * `UnknownHostException: Unable to resolve host "api.github.com": No address associated with hostname`。
 * 这类英文堆栈以前会原样出现在通知栏和发布页的附件行里 —— 用户看不懂，也不知道能做什么。
 * 这里按异常类型归到「网络 / 超时 / 证书 / 权限 / 服务端」几类结论上；
 * 技术细节（主机名、异常类名）只保留在最后那条兜底里，方便用户报障时复制。
 *
 * 与 `:translate` 的 `FailKind` 同一思路：**失败分类要能推出「下一步做什么」**，
 * 而不是把底层错误原样透传。
 */
internal object DownloadErrors {

    /** HTTP 状态码 → 中文文案。416（断点失效）在引擎里单独处理，不走这里。 */
    fun fromStatus(code: Int): String = when (code) {
        400 -> "下载请求被拒绝（HTTP 400）"
        401 -> "登录状态已失效，请重新登录（HTTP 401）"
        403 -> "没有下载该文件的权限（HTTP 403）"
        404 -> "文件不存在或已被删除（HTTP 404）"
        408 -> "服务端响应超时（HTTP 408）"
        410 -> "文件已被移除（HTTP 410）"
        429 -> "请求过于频繁，请稍后再试（HTTP 429）"
        in 500..599 -> "GitHub 服务端错误（HTTP $code），请稍后重试"
        else -> "下载失败（HTTP $code）"
    }

    /**
     * 异常 → 中文文案。
     *
     * 判断顺序**必须从具体到宽泛**：`SocketTimeoutException` / `SSLException` 都是
     * `IOException` 的子类，先判 `IOException` 会把它们全吞成「网络中断」。
     */
    fun fromException(e: Throwable): String = when (e) {
        is UnknownHostException -> "网络不可达：无法解析服务器地址"
        is SocketTimeoutException -> "连接超时，请检查网络后重试"
        is SSLException -> "安全连接失败（证书校验未通过）"
        is ConnectException -> "无法连接服务器，请检查网络"
        is InterruptedIOException, is ClosedByInterruptException -> "下载被中断，请重试"
        is FileNotFoundException -> "文件不存在或已被删除"
        is IOException -> "网络中断，请重试"
        else -> "下载失败（${e.javaClass.simpleName}），请重试"
    }
}
