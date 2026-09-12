package com.branchbase.downloader

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * 下载完成后的「交给系统」动作：安装 APK / 用其他应用打开 / 分享 / 在文件管理器里显示。
 *
 * ## 通用性说明（都是踩过的坑，别改成看着更"优雅"的写法）
 * 1. **必须 `content://`**：API 24 起 `file://` 直接 `FileUriExposedException`（本模块 minSdk 24），
 *    FileProvider 不是可选项；
 * 2. **安装器**：`ACTION_VIEW` + `application/vnd.android.package-archive` 是被最广泛支持的那条路；
 *    `ACTION_INSTALL_PACKAGE` 已废弃，但少数 ROM 只认它，作为一级兜底；两者都不行才提示手动安装。
 *    API 26+ 还要用户在系统里为「未知来源」授权（`canRequestPackageInstalls`）——
 *    没授权就直接跳过去只会白屏失败，所以先返回原因让调用方引导；
 * 3. **「打开所在文件夹」没有统一契约**：唯一接近标准的是
 *    `DocumentsContract.buildRootUri("com.android.externalstorage.documents", "primary")`
 *    （AOSP DocumentsUI 认，OEM 不一定认）；再按常见文件管理器包名探测一轮；
 *    都失败返回 false，由调用方降级成「分享」—— `ACTION_SEND` 是唯一人人都有实现的路。
 */
object DownloadActions {

    private const val APK_MIME = "application/vnd.android.package-archive"

    /** 常见文件管理器（探测用；命中即用，命中不了就走分享兜底）。 */
    private val FILE_MANAGER_PACKAGES = listOf(
        "com.android.documentsui",
        "com.google.android.documentsui",
        "com.mi.android.globalFileexplorer",
        "com.android.fileexplorer",
        "com.huawei.hidisk",
        "com.coloros.filemanager",
        "com.oppo.filemanager",
        "com.samsung.android.myfiles",
        "com.sec.android.app.myfiles",
        "com.oneplus.filemanager",
        "com.vivo.filemanager",
    )

    /** 本模块 FileProvider 的 authority（与模块清单里的 `${applicationId}.downloader.files` 一致）。 */
    fun authority(context: Context): String = "${context.packageName}.downloader.files"

    fun fileUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, authority(context), file)

    /** 用系统默认应用打开（按 MIME）。 */
    fun openFile(context: Context, file: File, mimeType: String? = null): Boolean {
        if (!file.exists()) return false
        val uri = runCatching { fileUri(context, file) }.getOrNull() ?: return false
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return startQuietly(context, intent)
    }

    /** 分享（多设备通用的兜底入口）。 */
    fun shareFile(context: Context, file: File, mimeType: String? = null): Boolean {
        if (!file.exists()) return false
        val uri = runCatching { fileUri(context, file) }.getOrNull() ?: return false
        val send = Intent(Intent.ACTION_SEND)
            .setType(mimeType ?: "*/*")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(send, "分享文件").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return startQuietly(context, chooser)
    }

    /** 是否已获得「安装未知应用」授权（API < 26 没有这道门槛）。 */
    fun canInstallPackages(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** 「安装未知应用」设置页；部分 ROM 没有该页面，调用方需要 `runCatching`。 */
    fun unknownSourcesSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.fromParts("package", context.packageName, null))

    /**
     * 拉起系统安装器。
     *
     * @return null = 已成功拉起；否则是给用户看的原因（调用方负责提示 / 引导去授权）。
     */
    fun installApk(context: Context, file: File): String? {
        if (!file.exists()) return "安装包不存在，请重新下载"
        if (!canInstallPackages(context)) return "需要先允许「安装未知应用」"
        val uri = runCatching { fileUri(context, file) }
            .getOrElse { return "无法读取安装包（${it.javaClass.simpleName}），请重新下载" }
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        if (startQuietly(context, view)) return null
        val legacy = Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setData(uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        if (startQuietly(context, legacy)) return null
        return "没有可用的安装器，请到文件管理器里手动安装"
    }

    /** 打开文件所在目录（尽力而为，见类注释第 3 条）。 */
    fun revealInFileManager(context: Context): Boolean {
        val root = runCatching {
            DocumentsContract.buildRootUri("com.android.externalstorage.documents", "primary")
        }.getOrNull()
        if (root != null) {
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(root, "vnd.android.document/root")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (startQuietly(context, intent)) return true
        }
        for (pkg in FILE_MANAGER_PACKAGES) {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: continue
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (startQuietly(context, intent)) return true
        }
        return false
    }

    private fun startQuietly(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}
