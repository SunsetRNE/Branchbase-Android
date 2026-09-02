package com.branchbase.ui.profile

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import com.branchbase.ui.log.LogCategory
import com.branchbase.ui.log.LogLevel
import com.branchbase.ui.log.Logger

/**
 * 发布版本（按安装包签名证书指纹区分）。
 *
 * - RELEASE：正式版签名（release keystore）
 * - BETA：测试版签名（debug keystore）
 * - UNKNOWN：未知签名 → 报异常
 */
enum class ReleaseVariant(val label: String) {
    RELEASE("正式版"),
    BETA("测试版"),
    UNKNOWN("异常"),
}

// 内置签名指纹（SHA-256，对应 keystore 证书指纹，大写 hex + 冒号分隔）
private const val RELEASE_FINGERPRINT =
    "B3:72:AB:52:EE:47:A0:8E:45:26:6F:1C:11:E0:75:6D:86:E3:83:A0:74:BE:EB:A3:77:FD:3E:BA:7C:F7:99:94"
private const val BETA_FINGERPRINT =
    "AC:AB:BC:09:9F:91:81:8B:58:C5:45:DD:7F:D6:4D:E5:E2:8D:13:31:9E:63:E6:E3:73:02:2B:BB:90:19:F3:DD"

/**
 * 读取当前 APK 的签名证书 SHA-256 指纹（大写 hex + 冒号分隔）。
 * 失败返回空串。
 */
fun signatureFingerprint(context: Context): String = try {
    val pm = context.packageManager
    val pkgInfo = if (Build.VERSION.SDK_INT >= 28) {
        pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
    }
    val cert = if (Build.VERSION.SDK_INT >= 28) {
        pkgInfo.signingInfo?.apkContentsSigners?.firstOrNull()
    } else {
        @Suppress("DEPRECATION")
        pkgInfo.signatures?.firstOrNull()
    } ?: return ""

    val md = MessageDigest.getInstance("SHA-256")
    val fp = md.digest(cert.toByteArray()).joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
    Logger.debug(LogCategory.LOCAL_TASK, "SigningVerify", "读取签名指纹 ${fp.take(23)}…")
    fp
} catch (e: Exception) {
    ""
}

/**
 * 按签名指纹判定发布版本。非测试版/正式版签名时返回 UNKNOWN（异常）。
 */
fun resolveReleaseVariant(context: Context): ReleaseVariant {
    val fp = signatureFingerprint(context)
    return when (fp) {
        RELEASE_FINGERPRINT -> ReleaseVariant.RELEASE
        BETA_FINGERPRINT -> ReleaseVariant.BETA
        else -> ReleaseVariant.UNKNOWN
    }
}

/** 解析 signature.txt 的 fingerprint 行（`fingerprint=...`），返回指纹或空串。 */
fun parseSignatureFingerprint(signatureContent: String): String =
    signatureContent
        .lineSequence()
        .firstOrNull { it.startsWith("fingerprint=") }
        ?.removePrefix("fingerprint=")
        ?.trim()
        .orEmpty()

/** 拉取远端校验文件内容（公开仓库 raw URL），失败返回 null。 */
fun fetchRemoteSignature(owner: String, repo: String, branch: String, path: String): String? = try {
    val url = URL("https://raw.githubusercontent.com/$owner/$repo/$branch/$path")
    val conn = url.openConnection() as HttpURLConnection
    conn.connectTimeout = 10_000
    conn.readTimeout = 10_000
    conn.requestMethod = "GET"
    if (conn.responseCode == 200) {
        Logger.remote("拉取校验文件 $path → 200", "SigningVerify")
        conn.inputStream.bufferedReader().use { it.readText() }
    } else {
        Logger.warn(LogCategory.REMOTE_EXEC, "SigningVerify", "拉取校验文件失败 HTTP ${conn.responseCode}")
        null
    }
} catch (e: Exception) {
    Logger.warn(LogCategory.REMOTE_EXEC, "SigningVerify", "拉取校验文件异常：$e")
    null
}

/** 拉取 latest release 的 signature.txt 附件内容（GitHub API），失败返回 null。 */
fun fetchLatestReleaseSignature(owner: String, repo: String): String? = try {
    // 1. 拉 latest release JSON，找 signature.txt 的 browser_download_url
    val apiUrl = URL("https://api.github.com/repos/$owner/$repo/releases/latest")
    val apiConn = apiUrl.openConnection() as HttpURLConnection
    apiConn.connectTimeout = 10_000
    apiConn.readTimeout = 10_000
    apiConn.requestMethod = "GET"
    apiConn.setRequestProperty("Accept", "application/vnd.github+json")
    apiConn.setRequestProperty("User-Agent", "Branchbase")
    val releaseJson = apiConn.inputStream.bufferedReader().use { it.readText() }
    val assetsArr = JSONObject(releaseJson).optJSONArray("assets")
    val downloadUrl = (0 until (assetsArr?.length() ?: 0))
        .map { assetsArr!!.getJSONObject(it) }
        .firstOrNull { it.optString("name") == "signature.txt" }
        ?.optString("browser_download_url")
        ?: return null
    // 2. 下载 signature.txt 附件
    val dlConn = URL(downloadUrl).openConnection() as HttpURLConnection
    dlConn.connectTimeout = 10_000
    dlConn.readTimeout = 10_000
    dlConn.requestMethod = "GET"
    if (dlConn.responseCode == 200) {
        Logger.remote("拉取 latest release signature.txt → 200", "SigningVerify")
        dlConn.inputStream.bufferedReader().use { it.readText() }
    } else {
        Logger.warn(LogCategory.REMOTE_EXEC, "SigningVerify", "拉取 latest release 校验文件失败 HTTP ${dlConn.responseCode}")
        null
    }
} catch (e: Exception) {
    Logger.warn(LogCategory.REMOTE_EXEC, "SigningVerify", "拉取 latest release 校验文件异常：$e")
    null
}
