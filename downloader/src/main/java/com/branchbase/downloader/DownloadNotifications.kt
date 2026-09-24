package com.branchbase.downloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.branchbase.downloader.R

/**
 * 下载相关的系统通知：**一条**前台服务进度通知（常驻、随当前任务刷新）
 * + 每条任务各自的完成 / 失败通知。
 *
 * 为什么拆成两种：前台服务通知是「服务还活着」的凭据，必须常驻且只能有一条；
 * 完成通知是「这条任务结束了」的一次性提醒，可以多条、可以点掉。
 *
 * 通知权限被拒时不抛异常：`NotificationManager.notify` 会静默失败，
 * 下载与前台服务照常（这正是「通知」与「下载」解耦的意义）。
 *
 * ## 进度通知上的三个叠加层（都在 `build()` 之前挂到**同一条**通知上）
 * 1. 标准进度：`setProgress` + 百分比 / 字节数文案（[DownloadNotificationState] 统一算的）；
 * 2. Hook 载荷：`com.branchbase.download.*` 一组稳定 extras，给第三方模块读（[DownloadNotificationHook]）；
 * 3. 厂商「上岛」：[DownloadIslandExtensions] 里已注册且可用的扩展（小米超级岛 / 谷歌实时更新 / OPPO）。
 *
 * 之所以不另发一条「岛通知」：用户会在通知栏里看到两条重复的下载。厂商能力要么作用在
 * 同一条通知上，要么由扩展自己维护独立卡片（那就由扩展在 [DownloadIslandExtension.onFinished] 里收尾）。
 */
internal class DownloadNotifications(
    private val context: Context,
    private val smallIconRes: Int,
) {

    /**
     * 建渠道，**每次都重写名称与描述**。
     *
     * 改前这里是 `if (manager.getNotificationChannel(CHANNEL_ID) != null) return` ——
     * 渠道只在**首次创建**时命名，之后永不更新：用户在系统设置里看到的渠道名会永远停在
     * 安装时那一刻的语言上，切换界面语言也不变（一个不会报错、只在设置里看得见的陈旧值）。
     *
     * 渠道 ID 不变时重建是幂等的，而且是更新名称/描述的**唯一**途径 ——
     * 重要度、声音等用户可见的开关属于用户，系统会保留用户的选择，重建不会覆盖。
     */
    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.downloader_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.downloader_channel_desc)
            setShowBadge(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    /** 前台服务的进度通知（id 固定，整条替换）。 */
    fun progress(task: DownloadTask, downloaded: Long, total: Long): Notification {
        // 任务表里的字节数是服务侧刚写进去的权威值；总量取「已知的最大值」，避免服务端
        // 不报 Content-Length（total=0）时进度条先冲高再回退
        val state = DownloadNotificationState.of(task).copy(
            downloadedBytes = maxOf(downloaded, task.downloadedBytes),
            totalBytes = maxOf(total, task.totalBytes, downloaded),
        )
        val builder = builder(state.title, state.progressText)
            .setProgress(100, state.percent ?: 0, state.indeterminate)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        DownloadNotificationHook.apply(builder, state, ongoing = true)
        DownloadIslandExtensions.decorate(context, builder, state)
        return builder.build()
    }

    /** 服务被拉起、但任务已经不在表里时的占位通知（只为满足 5 秒内 startForeground 的约定）。 */
    fun placeholder(): Notification =
        builder(context.getString(R.string.downloader_channel_name), context.getString(R.string.downloader_placeholder_text))
            .setOngoing(true).setSilent(true).build()

    /** 完成 / 失败通知。取消不发通知（用户刚刚就是自己取消的）。 */
    fun finished(task: DownloadTask, ok: Boolean) {
        val state = DownloadNotificationState.of(task)
        // 「下载失败：<原因>」整句成资源，不做「中文前缀 + 变量」的拼接 ——
        // 英文语序下那个前缀未必还在前面（与全 App 的抽取纪律一致）
        val text = when {
            ok -> context.getString(R.string.downloader_finished)
            task.failure != null ->
                context.getString(R.string.downloader_failed_with_reason, task.failure.resolve(context))
            else -> context.getString(R.string.downloader_failed)
        }
        val builder = builder(task.request.title, text)
            .setOngoing(false)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
        DownloadNotificationHook.apply(builder, state, ongoing = false)
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.notify(finishedId(task.id), builder.build())
        }
        // 进度通知到此为止；厂商侧若维护了独立卡片，趁结束信号收掉
        DownloadIslandExtensions.finished(context, state, ok)
    }

    fun cancelFinished(id: String) {
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancel(finishedId(id))
        }
        DownloadIslandExtensions.removed(context, id)
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

        /** 前台服务通知 id（固定，进程内只有一条）。 */
        internal const val FOREGROUND_ID = 0x0D01

        /** 每条任务的完成通知 id：与服务通知 id 分开，避免互相覆盖。 */
        internal fun finishedId(taskId: String): Int = 0x0E00 + (taskId.hashCode() and 0xFF)
    }
}
