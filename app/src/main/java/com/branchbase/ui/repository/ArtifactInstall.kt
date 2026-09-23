package com.branchbase.ui.repository

import android.content.Context
import java.io.File
import java.util.zip.ZipFile

/**
 * 工作流产物（artifact）→ **可安装的 APK**。
 *
 * ## 为什么需要这一段
 *
 * 产物是发布页之外的**第二条取包通道**。列表接口对刚发布的 release 会有一段空 assets 的
 * 缓存窗口（约 1~2 小时，见 `releasesNeedingAssetBackfill` 的注释），那段时间里发布页上
 * 什么附件都没有 —— 这时候只能靠 CI 产物。
 *
 * 但产物这条路原来**走到一半就断了**：
 *
 * ```
 * 发布附件  →  下载 .apk    →  有「安装」按钮（installDownloadedApk）
 * CI 产物   →  下载 .zip    →  结束。文件管理器里自己解压去
 * ```
 *
 * `:downloader` 只是把 GitHub 给的 zip 原样落盘；`app` / `downloader` / `core` 三个模块里
 * `ZipFile` / `ZipInputStream` **零命中** —— 也就是说手机上根本没有把产物变成安装包的能力。
 * 这一段补的就是它。
 *
 * ## 为什么要求「恰好一个 APK」
 *
 * Actions 的产物**下载时永远是 zip**，没有绕过的办法（平台约束）。既然去不掉那层壳，
 * 就把壳做成**确定性**的：工作流侧已把产物拆成「一个 APK 一个 artifact」
 * （`build-beta.yml` 的 Upload artifacts），所以正常情况解出来就是唯一一个包。
 *
 * 反过来，若一个 zip 里有 0 个或 2 个以上 APK，**不猜** —— 猜错的代价是装上一个
 * 用户没想要、甚至不该装的包（debug 与 perfBeta 是两个不同签名的变体）。
 * 这时如实告诉他先去解压手动挑，比自作主张好。
 */

/**
 * 从 zip 条目名里挑出**唯一**那个 APK 的条目名；没有或不止一个返回 `null`。
 *
 * 纯函数，可 JVM 单测（`ArtifactInstallTest`）。判定只看**文件名**，允许条目在子目录里 ——
 * Actions 产物可能带一层目录。
 */
internal fun pickSingleApkEntry(entryNames: List<String>): String? {
    val apks = entryNames.filter { name ->
        !name.endsWith("/") && name.substringAfterLast('/').lowercase().endsWith(".apk")
    }
    return apks.singleOrNull()
}

/**
 * 把 [zip] 里那个唯一的 APK 解到 [destDir]，返回解出的文件；条件不满足或出错返回 `null`。
 *
 * **输出路径只取条目名的最后一段**（`substringAfterLast('/')`）：zip 里的相对路径一律丢弃，
 * 于是「条目名带 `../`」这类 zip-slip 构造在这里天然不成立 —— 不是靠过滤，是靠不采纳。
 */
internal fun extractSingleApk(zip: File, destDir: File): File? = runCatching {
    ZipFile(zip).use { zf ->
        val names = zf.entries().toList().map { it.name }
        val entryName = pickSingleApkEntry(names) ?: return@use null
        val entry = zf.getEntry(entryName) ?: return@use null
        destDir.mkdirs()
        val out = File(destDir, entryName.substringAfterLast('/'))
        zf.getInputStream(entry).use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
        out
    }
}.getOrNull()

/**
 * 「解出产物里的 APK 并交给安装器」——产物行「安装」按钮的实现。
 *
 * 复用发布附件那条路径的 [installDownloadedApk]：**「安装未知应用」的授权引导只有一份**，
 * 不再抄一遍（抄一份就多一处将来会忘记同步的权限处理）。
 */
internal fun installWorkflowArtifact(context: Context, zip: File): String {
    // 解压目录每次清空：同一路径反复解压会让上一次的残留被误当成这一次的产物
    val dir = File(context.cacheDir, "wf-artifact")
    runCatching { dir.deleteRecursively() }
    val apk = extractSingleApk(zip, dir)
        ?: return "这个产物里不是「恰好一个 APK」，无法直接安装 —— 先解压（或分享到文件管理器）再手动挑"
    return installDownloadedApk(context, apk)
}
