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

    /**
     * 头像目录的**文件数上限**。
     *
     * 这个目录不只放账号头像：贡献者列表等位置也会渲染 `ui/theme/Avatar`，而 1.0.62 起
     * 那些头像同样会落盘（为了「首帧同步直出」与离线可用）。不加上界的话，
     * 翻几个大仓库就能把 `filesDir` 撑起来 —— 所以超限时按**最后修改时间**淘汰最旧的。
     */
    internal const val MAX_FILES = 200

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
                trimDir(context)
                true
            }.getOrDefault(false)
        }
    }

    /** 删除某账号的本地头像（目前不主动调用，留作清理入口）。 */
    fun clear(context: Context, login: String): Boolean =
        runCatching { fileFor(context, login).delete() }.getOrDefault(false)

    /**
     * 目录超出 [MAX_FILES] 时淘汰最旧的（按最后修改时间）。
     *
     * 只在**写入路径**上调用（写完才可能超），失败静默 —— 清理不该影响头像本身。
     * 淘汰策略抽成纯函数 [evictionVictims]，单测钉住「保留最新、删最旧」。
     */
    private fun trimDir(context: Context) = runCatching {
        val dir = File(context.filesDir, DIR)
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".png") } ?: return@runCatching
        if (files.size <= MAX_FILES) return@runCatching
        val victims = evictionVictims(
            files.map { it.name to it.lastModified() },
            keep = MAX_FILES,
        )
        victims.forEach { File(dir, it).delete() }
    }

    /** login 只用于拼文件名，这里挡掉路径分隔符等异常字符。 */
    private fun sanitize(login: String): String =
        login.replace(Regex("[^A-Za-z0-9._-]"), "_")
}

/**
 * 该淘汰哪些文件（纯函数，便于单测）：按修改时间**从旧到新**排出淘汰顺序，
 * 保留最新的 [keep] 个，前面的都是淘汰对象。
 *
 * 单独提出来是因为「淘汰顺序」只有边界条件才出错（比如反过来把最新的删了），
 * 而它一旦出错，用户会看到「头像刚存下就没了，每次都要重新下载」。
 */
internal fun evictionVictims(files: List<Pair<String, Long>>, keep: Int): List<String> =
    files.sortedBy { it.second }.dropLast(keep.coerceAtLeast(0)).map { it.first }
