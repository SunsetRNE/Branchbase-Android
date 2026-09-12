package com.branchbase.downloader

/**
 * 一帧「下载通知态」快照（**纯数据**）。
 *
 * ## 为什么单独抽出来
 *
 * 系统通知（[DownloadNotifications]）、厂商「上岛」扩展（[DownloadIslandExtension]）与
 * 第三方 Hook（[DownloadNotificationHook]）需要的是**同一份进度语义**：
 * 百分比怎么算、总量未知时算不算「不确定态」、文案怎么拼。抽成纯数据之后：
 * - 这套语义只有一处实现，且是纯函数，可直接单测（见 `DownloadNotificationStateTest`）；
 * - 厂商扩展拿到稳定结构，不会随通知构建细节（Builder 写法）变化；
 * - 通知里给第三方读的 extras 也由它派生 —— 三处永远一致。
 *
 * [taskId] 同时用作厂商侧独立卡片的去重键（同一条任务多次刷新必须是同一张卡）。
 */
data class DownloadNotificationState(
    val taskId: String,
    /** 通知标题（默认是附件名，可由 [DownloadRequest.title] 覆盖）。 */
    val title: String,
    val fileName: String,
    val status: DownloadStatus,
    val downloadedBytes: Long,
    val totalBytes: Long,
) {

    val isActive: Boolean
        get() = status == DownloadStatus.QUEUED || status == DownloadStatus.RUNNING

    /** 0..100；总量未知时 null（进度条退化为「不确定态」）。 */
    val percent: Int?
        get() = when {
            totalBytes > 0L -> ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
            status == DownloadStatus.COMPLETED -> 100
            else -> null
        }

    /** 是否走不确定进度条（总量未知、且任务还在跑）。 */
    val indeterminate: Boolean
        get() = percent == null && isActive

    /** `1.2 MB / 3.4 MB`；总量未知时只有已下载量。 */
    val sizeText: String
        get() = if (totalBytes > 0L) {
            "${DownloadPaths.formatBytes(downloadedBytes)} / ${DownloadPaths.formatBytes(totalBytes)}"
        } else {
            DownloadPaths.formatBytes(downloadedBytes)
        }

    /**
     * 进度通知正文：`正在下载 · 42% · 1.2 MB / 3.4 MB`。
     * 结束后（完成/失败）不再说「正在下载」，避免完成通知里出现自相矛盾的文案。
     */
    val progressText: String
        get() = when {
            isActive -> buildString {
                append("正在下载")
                percent?.let { append(" · ").append(it).append('%') }
                if (sizeText != "0 B") append(" · ").append(sizeText)
            }
            status == DownloadStatus.COMPLETED -> "下载完成 · ${DownloadPaths.formatBytes(downloadedBytes)}"
            else -> "已下载 · ${DownloadPaths.formatBytes(downloadedBytes)}"
        }

    /** 给 Hook / 厂商看的状态键（比 enum 名更适合跨版本传输）。 */
    val statusKey: String
        get() = when (status) {
            DownloadStatus.QUEUED -> "queued"
            DownloadStatus.RUNNING -> "running"
            DownloadStatus.COMPLETED -> "completed"
            DownloadStatus.FAILED -> "failed"
            DownloadStatus.CANCELED -> "canceled"
        }

    companion object {

        /** 从任务表快照派生（进度字段用任务里的值）。 */
        fun of(task: DownloadTask): DownloadNotificationState = DownloadNotificationState(
            taskId = task.id,
            title = task.request.title,
            fileName = task.request.fileName,
            status = task.status,
            downloadedBytes = task.downloadedBytes,
            totalBytes = task.totalBytes,
        )
    }
}
