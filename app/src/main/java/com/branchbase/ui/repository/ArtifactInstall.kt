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
 * 产物在 **2026-02 之前**一律被打包成 zip（`upload-artifact` v7 起才有 `archive: false`，
 * 见 `build-beta.yml` 的 Upload artifacts），所以这段代码要同时认两种形态 —— 见
 * [extractSingleApk] 的注释。
 *
 * 但无论哪种形态，**「一个 artifact 里只有一个包」都是前提**：工作流侧已把产物拆成
 * 「一个 APK 一个 artifact」。反过来，若一个 zip 里有 0 个或 2 个以上 APK，**不猜** ——
 * 猜错的代价是装上一个用户没想要、甚至不该装的包（debug 与 perfBeta 是两个不同签名的变体）。
 * 这时如实告诉他先去手动挑，比自作主张好。
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
 * 条目名看起来是不是**一个 APK 自身的内部结构**（而不是「装着 APK 的压缩包」）。
 *
 * 为什么需要它：**APK 本身就是 zip**，光看魔数分不出「这是个 apk」还是「这是个装着 apk 的 zip」。
 * 可靠的区别是 APK 根目录必有 `AndroidManifest.xml`。
 *
 * 纯函数，可 JVM 单测。
 */
internal fun looksLikeApk(entryNames: List<String>): Boolean =
    entryNames.any { it == "AndroidManifest.xml" }

/**
 * 把下载到的产物变成**可安装的 APK 文件**；做不到返回 `null`。
 *
 * 两种形态都要认，因为工作流侧前后变过：
 *
 * | 产物形态 | 来源 | 怎么处理 |
 * |---|---|---|
 * | 裸 `.apk` | `upload-artifact@v7` + `archive: false`（**现在**） | 原样返回 |
 * | 装着唯一一个 `.apk` 的 zip | v7 之前的行为（`archive: true`）或存量产物 | 解出来 |
 *
 * 输出路径只取条目名的最后一段（`substringAfterLast('/')`）：zip 里的相对路径一律丢弃，
 * 于是「条目名带 `../`」这类 zip-slip 构造在这里天然不成立 —— 不是靠过滤，是靠不采纳。
 */
internal fun extractSingleApk(file: File, destDir: File): File? = runCatching {
    ZipFile(file).use { zf ->
        val names = zf.entries().toList().map { it.name }
        when {
            // ① 它自己就是 APK（`archive: false`）：直接用，不必也不能再解一层
            looksLikeApk(names) -> file

            // ② 它是装着 APK 的 zip：解出唯一那个
            else -> {
                val entryName = pickSingleApkEntry(names) ?: return@use null
                val entry = zf.getEntry(entryName) ?: return@use null
                destDir.mkdirs()
                val out = File(destDir, entryName.substringAfterLast('/'))
                zf.getInputStream(entry).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
                out
            }
        }
    }
}.getOrNull()

/**
 * 「把产物交给安装器」——产物行「安装」按钮的实现。
 *
 * 复用发布附件那条路径的 [installDownloadedApk]：**「安装未知应用」的授权引导只有一份**，
 * 不再抄一遍（抄一份就多一处将来会忘记同步的权限处理）。
 */
internal fun installWorkflowArtifact(context: Context, file: File): String {
    // 解压目录每次清空：同一路径反复解压会让上一次的残留被误当成这一次的产物
    val dir = File(context.cacheDir, "wf-artifact")
    runCatching { dir.deleteRecursively() }
    val apk = extractSingleApk(file, dir)
        ?: return "这个产物里不是「恰好一个 APK」，无法直接安装 —— 先分享到文件管理器再手动挑"
    return installDownloadedApk(context, apk)
}
