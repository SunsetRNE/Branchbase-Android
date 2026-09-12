package com.branchbase.downloader

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 凭据提供者。
 *
 * **每一个重定向跳都会重新问一次**（参数是这一跳的地址）：由调用方按 host 决定给不给，
 * 于是「GitHub 附件 302 到 objects.githubusercontent.com」这种链路天然不会把 token 带过去。
 * 把策略放在提供者里，比在引擎里硬编码域名白名单更不容易漏。
 */
fun interface AuthProvider {
    fun authorizationFor(url: String): String?
}

/** 一次下载的终态。 */
sealed interface DownloadResult {
    data class Ok(val bytes: Long) : DownloadResult
    data class Failed(val message: String) : DownloadResult
    data object Canceled : DownloadResult
}

/**
 * HTTP 下载引擎（`HttpURLConnection`，无第三方依赖）。
 *
 * 四个必须自己处理的点：
 * 1. **手动跟随重定向**：GitHub 附件是 302 → 签名 URL，而 [AuthProvider] 要在每一跳重新决策；
 *    交给 `instanceFollowRedirects` 就没有这个钩子（还会把 Authorization 一路带下去）；
 * 2. **Range 断点续传**：临时文件已有字节就带 `Range`，服务端返回 206 就追加、返回 200 就重下；
 * 3. **`Accept-Encoding: identity`**：默认的透明 gzip 会让 `Content-Length`（压缩后长度）
 *    与实际落盘字节数对不上 —— 进度条冲到 100% 再回退，Range 偏移也全错；
 * 4. **进度节流**：[PROGRESS_INTERVAL_MS] 之前的回调直接丢掉，否则通知栏会被每 64KB 一次的
 *    notify 刷爆（系统对同包通知有速率限制，而且非常耗电）。
 */
internal class HttpDownloadEngine(
    private val auth: AuthProvider,
    private val userAgent: String,
) {

    fun download(
        request: DownloadRequest,
        target: File,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        isCanceled: () -> Boolean,
    ): DownloadResult {
        var current = request.url
        var redirects = 0
        while (true) {
            if (isCanceled()) return DownloadResult.Canceled
            var resumeFrom = target.length().takeIf { it > 0L } ?: 0L
            val conn = open(current, resumeFrom)
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                    if (location.isNullOrBlank() || redirects >= MAX_REDIRECTS) {
                        return DownloadResult.Failed("重定向异常（HTTP $code）")
                    }
                    redirects++
                    current = URL(URL(current), location).toString()
                    continue
                }
                // 临时文件比远端还新/更完整时 Range 会被拒：断点信息已失效，删掉让用户重试
                if (code == 416) {
                    target.delete()
                    return DownloadResult.Failed("断点信息已失效，请重试")
                }
                if (code !in 200..299) return DownloadResult.Failed(DownloadErrors.fromStatus(code))

                val append = code == 206
                if (!append) resumeFrom = 0L
                val declared = conn.contentLengthLong
                val total = if (declared > 0L) declared + resumeFrom else request.sizeHint
                target.parentFile?.mkdirs()
                if (!append) target.delete()

                var written = resumeFrom
                var lastNotify = 0L
                val buffer = ByteArray(BUFFER_BYTES)
                conn.inputStream.use { input ->
                    FileOutputStream(target, append).use { out ->
                        while (true) {
                            if (isCanceled()) return DownloadResult.Canceled
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            written += n
                            val now = System.currentTimeMillis()
                            if (now - lastNotify >= PROGRESS_INTERVAL_MS) {
                                lastNotify = now
                                onProgress(written, total)
                            }
                        }
                        out.flush()
                    }
                }
                onProgress(written, total)
                return DownloadResult.Ok(written)
            } catch (e: Exception) {
                if (isCanceled()) return DownloadResult.Canceled
                // 网络栈的原始异常（多为英文）不进用户视野，统一翻译成中文结论
                return DownloadResult.Failed(DownloadErrors.fromException(e))
            } finally {
                runCatching { conn.disconnect() }
            }
        }
    }

    private fun open(url: String, resumeFrom: Long): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Accept-Encoding", "identity")
            if (resumeFrom > 0L) setRequestProperty("Range", "bytes=$resumeFrom-")
            auth.authorizationFor(url)?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Authorization", it) }
        }

    private companion object {
        const val MAX_REDIRECTS = 5
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 30_000
        const val BUFFER_BYTES = 64 * 1024
        const val PROGRESS_INTERVAL_MS = 250L
    }
}
