package com.branchbase.downloader

import android.os.Bundle
import androidx.core.app.NotificationCompat

/**
 * 通知里给**第三方 Hook** 读的稳定载荷。
 *
 * ## 为什么要把这些字段写进 extras
 *
 * 「灵动岛 / 实时活动」的官方接入几乎都要**厂商白名单**（小米焦点通知权限、ColorOS 实况通知、
 * Android 16 的 promoted ongoing 都算），没批下来时通知只会以普通形态显示。
 * 这时用户唯一的补救办法是装第三方模块（LSPosed / Xposed 之类）去把通知「改造成岛」。
 * 而那些模块只看得到 `Notification.extras` —— 如果进度只存在于 `setProgress()` 的
 * 标准字段里，Hook 拿不到「这是哪条任务、下到哪了、还能不能续」，只能靠猜标题。
 *
 * 于是这里把一份**跨版本稳定**的键值对挂到每条下载通知上（前台进度通知与完成通知都挂）：
 * - 键名带 `com.branchbase.download.` 前缀，不会和系统字段撞车；
 * - 键名一旦发布就不再改（Hook 是按名字读的），要加字段只能**新增**；
 * - 值都是基本类型（String/Long/Int/Boolean），`Bundle` 原样可读。
 *
 * 这不是「内部实现细节」：它是给外部读者的契约，改动等同于破坏兼容。
 */
object DownloadNotificationHook {

    /** 所有键的公共前缀（第三方按前缀扫一遍即可认出本应用的通知）。 */
    const val NAMESPACE = "com.branchbase.download"

    const val EXTRA_TASK_ID = NAMESPACE + ".task_id"
    const val EXTRA_TITLE = NAMESPACE + ".title"
    const val EXTRA_FILE_NAME = NAMESPACE + ".file_name"

    /** queued / running / completed / failed / canceled（见 [DownloadNotificationState.statusKey]）。 */
    const val EXTRA_STATUS = NAMESPACE + ".status"

    const val EXTRA_DOWNLOADED_BYTES = NAMESPACE + ".downloaded_bytes"
    const val EXTRA_TOTAL_BYTES = NAMESPACE + ".total_bytes"

    /** 0..100；总量未知时为 **-1**（不能用 0 表示，0% 是合法进度）。 */
    const val EXTRA_PERCENT = NAMESPACE + ".percent"

    /** 是否常驻（前台服务进度通知 true，完成通知 false）。 */
    const val EXTRA_ONGOING = NAMESPACE + ".ongoing"

    /**
     * 纯数据的载荷（**可单测**：不碰任何 Android 类型）。
     * 键顺序固定，便于日志比对。
     */
    fun payload(state: DownloadNotificationState, ongoing: Boolean): Map<String, Any> = linkedMapOf<String, Any>(
        EXTRA_TASK_ID to state.taskId,
        EXTRA_TITLE to state.title,
        EXTRA_FILE_NAME to state.fileName,
        EXTRA_STATUS to state.statusKey,
        EXTRA_DOWNLOADED_BYTES to state.downloadedBytes,
        EXTRA_TOTAL_BYTES to state.totalBytes,
        EXTRA_PERCENT to (state.percent ?: -1),
        EXTRA_ONGOING to ongoing,
    )

    /** 挂到通知上（`NotificationManager.notify` 之后由系统写进 `Notification.extras`）。 */
    internal fun apply(
        builder: NotificationCompat.Builder,
        state: DownloadNotificationState,
        ongoing: Boolean,
    ) {
        val bundle = Bundle()
        payload(state, ongoing).forEach { (key, value) ->
            when (value) {
                is String -> bundle.putString(key, value)
                is Long -> bundle.putLong(key, value)
                is Int -> bundle.putInt(key, value)
                is Boolean -> bundle.putBoolean(key, value)
                else -> Unit
            }
        }
        builder.addExtras(bundle)
    }
}
