package com.branchbase.downloader

import android.content.Context
import androidx.annotation.StringRes
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.ClosedByInterruptException
import javax.net.ssl.SSLException

/**
 * 下载失败的**稳定分类**（不是文案）。
 *
 * ## 为什么是枚举而不是字符串
 *
 * 引擎里抛出来的都是网络栈的原始异常，例如
 * `UnknownHostException: Unable to resolve host "api.github.com"`。这类英文堆栈以前会原样
 * 出现在通知栏和发布页的附件行里 —— 用户看不懂，也不知道能做什么。所以要把失败归到
 * 「网络 / 超时 / 证书 / 权限 / 服务端」几类**能推出下一步做什么**的结论上
 * （与 `:translate` 的 `FailKind` 同一思路）。
 *
 * 但结论**不能以文案的形式**在模型里流传：那样 UI 层就只能在「已经是中文的字符串」和
 * 「什么都不知道」之间二选一。分类用枚举表达之后：
 *
 * - 文案在**渲染时**由 Context 解析，跟随界面语言（通知也在内 —— 它是非 Compose 表面，
 *   本来就应该走 `Context.getString`）；
 * - 分类本身可单测：断言的是枚举，不是「这句话里有没有『超时』两个字」。
 *
 * ## 为什么不是 `@StringRes Int`
 *
 * `DownloadStore` 目前**不持久化**（见它的 KDoc：进程被杀就重来），所以存资源 ID 暂时也安全。
 * 但它的注释里明确预留了「落盘 + WorkManager 续传」这条路 —— 一旦要走，**存资源 ID 就是定时炸弹**：
 * 资源 ID 是编译期生成的整数，跨版本不稳定，旧数据会指向别的文案。枚举天然兼容那条路。
 */
enum class DownloadErrorCode {
    // ── HTTP ──
    BAD_REQUEST,
    UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND,
    REQUEST_TIMEOUT,
    GONE,
    TOO_MANY_REQUESTS,
    SERVER_ERROR,
    HTTP_OTHER,

    // ── 引擎/服务自身的判定（不是网络栈给的）──
    REDIRECT_ERROR,
    RANGE_INVALID,
    BACKGROUND_TIMEOUT,
    CHECKSUM_MISMATCH,

    // ── 网络栈异常 ──
    UNKNOWN_HOST,
    CONNECT_TIMEOUT,
    TLS_ERROR,
    CONNECT_FAILED,
    INTERRUPTED,
    FILE_NOT_FOUND,
    IO_ERROR,
    UNKNOWN,
}

/**
 * 一次失败：稳定的 [code] + 格式化参数。
 *
 * 参数的顺序与对应文案里的 `%1$s` 一致（目前最多一个参数：HTTP 状态码或异常类名）。
 */
data class DownloadFailure(val code: DownloadErrorCode, val args: List<Any> = emptyList())

/** 错误码 → 文案资源。**只有这个模块知道映射**，App 侧只调 [resolve]。 */
@StringRes
private fun messageResOf(code: DownloadErrorCode): Int = when (code) {
    DownloadErrorCode.BAD_REQUEST -> R.string.downloader_error_bad_request
    DownloadErrorCode.UNAUTHORIZED -> R.string.downloader_error_unauthorized
    DownloadErrorCode.FORBIDDEN -> R.string.downloader_error_forbidden
    DownloadErrorCode.NOT_FOUND -> R.string.downloader_error_not_found
    DownloadErrorCode.REQUEST_TIMEOUT -> R.string.downloader_error_request_timeout
    DownloadErrorCode.GONE -> R.string.downloader_error_gone
    DownloadErrorCode.TOO_MANY_REQUESTS -> R.string.downloader_error_too_many_requests
    DownloadErrorCode.SERVER_ERROR -> R.string.downloader_error_server
    DownloadErrorCode.HTTP_OTHER -> R.string.downloader_error_http_other
    DownloadErrorCode.REDIRECT_ERROR -> R.string.downloader_error_redirect
    DownloadErrorCode.RANGE_INVALID -> R.string.downloader_error_range_invalid
    DownloadErrorCode.BACKGROUND_TIMEOUT -> R.string.downloader_error_background_timeout
    DownloadErrorCode.CHECKSUM_MISMATCH -> R.string.downloader_error_checksum
    DownloadErrorCode.UNKNOWN_HOST -> R.string.downloader_error_unknown_host
    DownloadErrorCode.CONNECT_TIMEOUT -> R.string.downloader_error_connect_timeout
    DownloadErrorCode.TLS_ERROR -> R.string.downloader_error_tls
    DownloadErrorCode.CONNECT_FAILED -> R.string.downloader_error_connect_failed
    DownloadErrorCode.INTERRUPTED -> R.string.downloader_error_interrupted
    DownloadErrorCode.FILE_NOT_FOUND -> R.string.downloader_error_file_not_found
    DownloadErrorCode.IO_ERROR -> R.string.downloader_error_io
    DownloadErrorCode.UNKNOWN -> R.string.downloader_error_unknown
}

/**
 * 把失败解析成**当前界面语言**的文案。
 *
 * 这是 App 侧唯一需要知道的入口 —— 映射表与参数顺序都收在模块内。
 */
fun DownloadFailure.resolve(context: Context): String =
    context.getString(messageResOf(code), *args.toTypedArray())

/** 失败分类（纯逻辑，可单测；416 断点失效在引擎里单独处理，不走这里）。 */
internal object DownloadErrors {

    /** HTTP 状态码 → 失败分类。 */
    fun forStatus(code: Int): DownloadFailure = when (code) {
        400 -> DownloadFailure(DownloadErrorCode.BAD_REQUEST)
        401 -> DownloadFailure(DownloadErrorCode.UNAUTHORIZED)
        403 -> DownloadFailure(DownloadErrorCode.FORBIDDEN)
        404 -> DownloadFailure(DownloadErrorCode.NOT_FOUND)
        408 -> DownloadFailure(DownloadErrorCode.REQUEST_TIMEOUT)
        410 -> DownloadFailure(DownloadErrorCode.GONE)
        429 -> DownloadFailure(DownloadErrorCode.TOO_MANY_REQUESTS)
        in 500..599 -> DownloadFailure(DownloadErrorCode.SERVER_ERROR, listOf(code))
        else -> DownloadFailure(DownloadErrorCode.HTTP_OTHER, listOf(code))
    }

    /**
     * 异常 → 失败分类。
     *
     * 判断顺序**必须从具体到宽泛**：`SocketTimeoutException` / `SSLException` 都是
     * `IOException` 的子类，先判 `IOException` 会把它们全吞成「网络中断」。
     */
    fun forException(e: Throwable): DownloadFailure = when (e) {
        is UnknownHostException -> DownloadFailure(DownloadErrorCode.UNKNOWN_HOST)
        is SocketTimeoutException -> DownloadFailure(DownloadErrorCode.CONNECT_TIMEOUT)
        is SSLException -> DownloadFailure(DownloadErrorCode.TLS_ERROR)
        is ConnectException -> DownloadFailure(DownloadErrorCode.CONNECT_FAILED)
        is InterruptedIOException, is ClosedByInterruptException ->
            DownloadFailure(DownloadErrorCode.INTERRUPTED)
        is FileNotFoundException -> DownloadFailure(DownloadErrorCode.FILE_NOT_FOUND)
        is IOException -> DownloadFailure(DownloadErrorCode.IO_ERROR)
        else -> DownloadFailure(DownloadErrorCode.UNKNOWN, listOf(e.javaClass.simpleName))
    }
}
