package com.branchbase.downloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * 下载相关的系统通知：**一条**前台服务进度通知（常驻、随当前任务刷新）
 * + 每条任务各自的完成 / 失败通知。
 *
 * 为什么拆成两种：前台服务通知是「服务还活着」的凭据，必须常驻且只能有一条；
 * 完成通知是「这条任务结束了」的一次性提醒，可以多条、可以点掉。
 *
 * 通知权限被拒时不抛异常：`NotificationManager.notify` 会静默失败，
 * 下载与前台服务照常（这正是「通知」与「下载」解耦的意义）。
 */
internal class DownloadNotifications(
    private val context: Context,
    private val smallIconRes: Int,
) {

    /** 渠道只建一次；重建是幂等的，但每次查一遍能少一次跨进程调用。 */
    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
            description = "下载进度与完成提醒"
            setShowBadge(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    /** 前台服务的进度通知（id 固定，整条替换）。 */
    fun progress(task: DownloadTask, downloaded: Long, total: Long): Notification {
        val percent = if (total > 0L) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 0
        val text = if (total > 0L) {
            "${DownloadPaths.formatBytes(downloaded)} / ${DownloadPaths.formatBytes(total)}"
        } else {
            DownloadPaths.formatBytes(downloaded)
        }
        return builder(task.request.title, "正在下载 · $text")
            .setProgress(100, percent, total <= 0L)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    /** 服务被拉起、但任务已经不在表里时的占位通知（只为满足 5 秒内 startForeground 的约定）。 */
    fun placeholder(): Notification =
        builder(CHANNEL_NAME, "准备下载").setOngoing(true).setSilent(true).build()

    /** 完成 / 失败通知。取消不发通知（用户刚刚就是自己取消的）。 */
    fun finished(task: DownloadTask, ok: Boolean) {
        val text = when {
            ok -> "下载完成"
            !task.error.isNullOrBlank() -> "下载失败：${task.error}"
            else -> "下载失败"
        }
        val notification = builder(task.request.title, text)
            .setOngoing(false)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.notify(finishedId(task.id), notification)
        }
    }

    fun cancelFinished(id: String) {
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancel(finishedId(id))
        }
    }

    private fun builder(title: String, text: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIconRes)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(launchIntent())

    /**
     * 点通知回应用。
     *
     * 模块不认识 App 的 Activity，用 `getLaunchIntentForPackage` 拿启动 Intent ——
     * 这样 :downloader 不需要（也不应该）依赖 :app 的类名。
     */
    private fun launchIntent(): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            // API 31+ 必须显式声明可变性
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    internal companion object {
        internal const val CHANNEL_ID = "branchbase_downloads"
        private const val CHANNEL_NAME = "下载"

        /** 前台服务通知 id（固定，进程内只有一条）。 */
        internal const val FOREGROUND_ID = 0x0D01

        /** 每条任务的完成通知 id：与服务通知 id 分开，避免互相覆盖。 */
        internal fun finishedId(taskId: String): Int = 0x0E00 + (taskId.hashCode() and 0xFF)
    }
}
