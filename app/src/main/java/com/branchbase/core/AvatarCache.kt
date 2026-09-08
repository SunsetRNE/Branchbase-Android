package com.branchbase.core

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 当前账号头像的本地缓存。
 *
 * 设计（对齐「登录即预热 + 账号绑定 + 手动刷新」的思路）：
 * - **按 login 隔离**：`filesDir/avatars/{login}.png` —— 文件名就是隔离键，
 *   和本地仓库（`repos/{login}/…`）同一套规则，绝不会串到别的账号。
 * - **登录即预热**：登录成功后立刻下载一次，之后任何页面渲染头像都能直接命中本地文件，
 *   **零网络等待、零闪烁**。
 * - **原子写入**：先写 `.tmp` 再 rename，避免渲染时读到半张图。
 * - **手动刷新**：网页端换了头像后，在个人主页长按头像触发 [refresh]。
 * - **删除账号不删头像**：与仓库/任务一致，重新登录同 login 后自动重新可见。
 */
object AvatarCache {

    private const val DIR = "avatars"

    /** 某账号的头像缓存文件（可能不存在）。 */
    fun fileFor(context: Context, login: String): File =
        File(File(context.filesDir, DIR), sanitize(login) + ".png")

    /** 已缓存则返回文件，否则 null —— 供 UI 判断能否「直接渲染」。 */
    fun localFileOrNull(context: Context, login: String): File? =
        fileFor(context, login).takeIf { it.isFile && it.length() > 0 }

    /** 该账号是否已有本地头像。 */
    fun has(context: Context, login: String): Boolean = localFileOrNull(context, login) != null

    /**
     * 下载并落盘（幂等）。登录后、启动时、长按刷新都走这里。
     *
     * @return true = 本次成功写入了新文件
     */
    suspend fun refresh(context: Context, login: String, url: String?): Boolean {
        if (login.isBlank() || url.isNullOrBlank()) return false
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Branchbase/0.1")
                }
                val bytes = try {
                    if (conn.responseCode !in 200..299) return@runCatching false
                    conn.inputStream.use { it.readBytes() }
                } finally {
                    conn.disconnect()
                }
                if (bytes.isEmpty()) return@runCatching false

                val target = fileFor(context, login)
                target.parentFile?.mkdirs()
                val tmp = File(target.absolutePath + ".tmp")
                tmp.writeBytes(bytes)
                // 原子替换：渲染线程永远看不到写了一半的文件
                if (!tmp.renameTo(target)) {
                    target.writeBytes(bytes)
                    tmp.delete()
                }
                true
            }.getOrDefault(false)
        }
    }

    /** 删除某账号的本地头像（目前不主动调用，留作清理入口）。 */
    fun clear(context: Context, login: String): Boolean =
        runCatching { fileFor(context, login).delete() }.getOrDefault(false)

    /** login 只用于拼文件名，这里挡掉路径分隔符等异常字符。 */
    private fun sanitize(login: String): String =
        login.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
