package com.branchbase.downloader

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 系统通知权限（API 33+ 的 `POST_NOTIFICATIONS`）与「通知总开关」状态。
 *
 * ## 为什么下载模块要管这件事
 * 下载进度与完成提醒都靠通知，但**通知权限不能成为下载的前置条件**：
 * - 权限被拒时前台服务照常运行（通知不显示而已），下载必须成功；
 * - 所以状态查询 / 申请入口放在这里，由 App 在**通知板块**里提示，
 *   而不是在点下载时阻塞（见 App 侧 NotificationScreen 的横幅与通知设置页）。
 *
 * ## 两种「没有通知」要分开
 * 1. [status] = DENIED：运行时权限还没给 —— 可以再弹系统授权框（[canRequest]）；
 * 2. [notificationsEnabled] = false：应用级通知被用户在系统设置里关掉（或渠道被关）——
 *    权限可能仍是授予的，再申请也没用，只能引导去系统设置。
 * 横幅文案要按这两种情况分别给，否则用户会反复点一个没有反应的按钮。
 */
object NotificationPermission {

    enum class Status {
        /** API < 33：没有运行时权限这一层。 */
        NOT_REQUIRED,
        GRANTED,
        /** 还没问过或用户拒绝过（仍可再次发起系统授权框）。 */
        DENIED,
    }

    /** 需要申请时传给 `ActivityResultContracts.RequestPermission` 的权限名。 */
    const val PERMISSION: String = Manifest.permission.POST_NOTIFICATIONS

    fun status(context: Context): Status = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> Status.NOT_REQUIRED
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED ->
            Status.GRANTED
        else -> Status.DENIED
    }

    /** 权限这一层是否放行（API < 33 恒为 true）。 */
    fun isGranted(context: Context): Boolean = status(context) != Status.DENIED

    /**
     * 通知在系统层面是否真的能弹出来。
     *
     * 权限授予了也可能被用户在系统设置里整体关掉，所以**提示是否必要要看这个**，
     * 而不是只看 [status]。
     */
    fun notificationsEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** 需要且还能弹系统授权框（被永久拒绝时会直接返回 false，调用方应引导去设置页）。 */
    fun canRequest(context: Context): Boolean = status(context) == Status.DENIED

    /** 跳到本应用的通知设置；系统没有该页面时退回应用详情页。 */
    fun settingsIntent(context: Context): Intent {
        val notificationSettings = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            notificationSettings.resolveActivity(context.packageManager) != null
        ) {
            return notificationSettings
        }
        return Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        )
    }
}
