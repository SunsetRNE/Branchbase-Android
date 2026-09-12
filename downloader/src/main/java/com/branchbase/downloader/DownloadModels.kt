package com.branchbase.downloader

import java.io.File

/**
 * 下载请求（不可变）。
 *
 * [id] 由调用方给出并**必须稳定**（同 id 重复入队是幂等的，见 [DownloaderRuntime.enqueue]）：
 * UI 用它把「某个附件」和「某条任务」对上，通知也用它派生通知 id。
 */
data class DownloadRequest(
    val id: String,
    val url: String,
    /** 建议文件名；落盘前会经 [DownloadPaths.sanitize] 净化（去路径分隔符 / 控制字符 / `..`）。 */
    val fileName: String,
    /** 通知标题；默认用文件名。 */
    val title: String = fileName,
    /** 完成后「打开」用的 MIME；null = 交给系统按扩展名猜。 */
    val mimeType: String? = null,
    /** 期望大小（GitHub release asset 的 `size`）；0 = 未知，进度退化为不确定态。 */
    val sizeHint: Long = 0L,
    /** 期望的 sha256（release asset 的 `digest`，去掉 `sha256:` 前缀）；null = 不校验。 */
    val sha256: String? = null,
)

/** 任务状态机：QUEUED → RUNNING → COMPLETED / FAILED / CANCELED。 */
enum class DownloadStatus { QUEUED, RUNNING, COMPLETED, FAILED, CANCELED }

/** 对外只读的任务快照（[DownloadStore] 整表替换，UI 直接 collect）。 */
data class DownloadTask(
    val id: String,
    val request: DownloadRequest,
    val status: DownloadStatus,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val file: File? = null,
    val error: String? = null,
    val updatedAtMs: Long = System.currentTimeMillis(),
) {
    /** 0f..1f；总量未知时 null（UI 用不确定进度条）。 */
    val progress: Float?
        get() = when {
            totalBytes > 0L -> (downloadedBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
            status == DownloadStatus.COMPLETED -> 1f
            else -> null
        }

    val isActive: Boolean
        get() = status == DownloadStatus.QUEUED || status == DownloadStatus.RUNNING

    val percentText: String
        get() = progress?.let { "${(it * 100).toInt()}%" } ?: ""
}
