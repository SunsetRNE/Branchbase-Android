package com.branchbase.downloader

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * 落盘位置与文件名规则。
 *
 * 目录选 `getExternalFilesDir(null)/downloads`：
 * - **零权限**（API 24–28 也不需要 `WRITE_EXTERNAL_STORAGE`）；
 * - 卸载应用时随之清理，不留垃圾；
 * - 要交给别的应用（安装器 / 查看器）时，用模块自带 FileProvider 换成 `content://`。
 *
 * 需要用户能在系统文件管理器里直接看到文件时另走「导出」（MediaStore / SAF），
 * 不把权限追加到下载主链路上。
 */
object DownloadPaths {

    internal const val DIR = "downloads"
    internal const val PART_SUFFIX = ".part"

    fun dir(context: Context): File = File(context.getExternalFilesDir(null), DIR).apply { mkdirs() }

    /** 断点续传用的临时文件（下载完成后才改名成 [finalFile]）。 */
    fun partFile(context: Context, fileName: String): File = File(dir(context), sanitize(fileName) + PART_SUFFIX)

    fun finalFile(context: Context, fileName: String): File = File(dir(context), sanitize(fileName))

    /**
     * 文件名净化（纯函数，可单测）。
     *
     * 素材名来自网络（release asset 名 / 远端 Content-Disposition），**不可信**：
     * 直接 `File(dir, name)` 时 `../../foo` 会写到下载目录之外、`a/b` 会变成子路径，
     * 控制字符还会让通知栏显示乱码。规则：
     * 只取路径最后一段 → 白名单之外的字符替换成 `_` → 去掉首尾点号与空格 → 空名兜底。
     */
    fun sanitize(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        val sb = StringBuilder(base.length)
        for (ch in base) {
            sb.append(if (ch.isLetterOrDigit() || ch in "-_. +()[]@,!#&'=~") ch else '_')
        }
        val name = sb.toString().trim().trim('.')
        if (name.isEmpty()) return "download"
        return if (name.length <= MAX_NAME) name else name.take(MAX_NAME)
    }

    /** 文件 sha256（小写 hex）；用于校验远端给出的 digest。 */
    fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 人类可读字节数（通知文案用）。 */
    fun formatBytes(bytes: Long): String = when {
        bytes <= 0L -> "0 B"
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    }

    private const val MAX_NAME = 100
}
