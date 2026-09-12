package com.branchbase.downloader

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * 下载前台服务（`foregroundServiceType="dataSync"`）。
 *
 * ## 生命周期
 * - `DownloaderRuntime.enqueue()` → `startForegroundService(ACTION_ENQUEUE)`；
 * - `onStartCommand` 里**必须先 `startForeground`**（系统只给 5 秒窗口，超时直接 ANR/崩溃）；
 * - 任务**串行**执行：同一条链路上并发多个大文件只会互相抢带宽，进度条也失去意义；
 * - 队列清空 → 撤掉前台通知并 `stopSelf()`。
 *
 * ## 版本适配
 * - API 29+ 必须把 [ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC] 传给 `startForeground`；
 * - API 34+ 清单里还要有 `FOREGROUND_SERVICE_DATA_SYNC` 权限与 service 的 type（见模块清单）；
 * - Android 15 起 `dataSync` 前台服务有累计时长上限，超时回调 [onTimeout]：超时后必须自己停，
 *   并把在跑的任务落成「可重试的失败态」（手机下载附件远到不了这个量级，这是纯兜底）；
 * - API 31+ 禁止从后台启动前台服务：本项目只在用户点击下载（应用在前台）时启动，符合要求。
 *
 * ## 进程
 * 与 App 默认同进程：取消信号 [CancelRegistry] 是进程内集合，跨进程不成立 ——
 * 真要把服务拆到独立进程时，取消必须改成 Intent/Binder 传递（见 [CancelRegistry] 注释）。
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val pending = AtomicInteger(0)

    private lateinit var notifications: DownloadNotifications
    private lateinit var engine: HttpDownloadEngine

    override fun onCreate() {
        super.onCreate()
        val config = DownloaderRuntime.config
        notifications = DownloadNotifications(this, config.smallIconRes)
        notifications.ensureChannel()
        engine = HttpDownloadEngine(config.auth, config.userAgent)
        scope.launch {
            while (true) {
                val id = queue.receive()
                runCatching { runTask(id) }
                if (pending.decrementAndGet() <= 0) stopSelfSafely()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_ENQUEUE) {
            val id = intent.getStringExtra(EXTRA_ID)
            val task = if (id != null) DownloadStore.task(id) else null
            if (task == null) {
                // 同进程入队时理论上不会走到这里；真发生了也必须先满足「5 秒内 startForeground」
                startForegroundCompat(notifications.placeholder())
                stopSelfSafely()
            } else {
                startForegroundCompat(notifications.progress(task, 0L, task.request.sizeHint))
                pending.incrementAndGet()
                queue.trySend(task.id)
            }
        }
        // 进程被杀不重启：任务表在内存里，重启也无从恢复
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Android 15+：`dataSync` 前台服务累计超时回调。
     * 超时后系统不会再让我们继续跑，必须主动收尾，否则会被判 ANR。
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        DownloadStore.tasks.value.filter { it.isActive }.forEach { active ->
            DownloadStore.update(active.id) {
                it.copy(
                    status = DownloadStatus.FAILED,
                    error = "后台下载超时（系统限制），可重试续传",
                    updatedAtMs = System.currentTimeMillis(),
                )
            }
        }
        stopSelfSafely()
    }

    private suspend fun runTask(id: String) {
        val queued = DownloadStore.task(id) ?: return
        // 排队期间被取消：清掉半截文件，保持「可重试」语义
        if (CancelRegistry.isCanceled(id)) {
            CancelRegistry.clear(id)
            DownloadPaths.partFile(this, queued.request.fileName).delete()
            return
        }
        if (queued.status != DownloadStatus.QUEUED) return

        DownloadStore.update(id) { it.copy(status = DownloadStatus.RUNNING, updatedAtMs = System.currentTimeMillis()) }
        val target = DownloadPaths.partFile(this, queued.request.fileName)
        val result = engine.download(
            request = queued.request,
            target = target,
            onProgress = { done, total ->
                DownloadStore.update(id) {
                    it.copy(
                        downloadedBytes = done,
                        totalBytes = maxOf(total, done),
                        updatedAtMs = System.currentTimeMillis(),
                    )
                }
                val current = DownloadStore.task(id) ?: return@download
                runCatching { startForegroundCompat(notifications.progress(current, done, total)) }
            },
            isCanceled = { CancelRegistry.isCanceled(id) },
        )
        finalize(id, target, result)
    }

    private fun finalize(id: String, part: File, result: DownloadResult) {
        val task = DownloadStore.task(id) ?: return
        when (result) {
            is DownloadResult.Ok -> {
                val expected = task.request.sha256
                if (!expected.isNullOrBlank() && !DownloadPaths.sha256Of(part).equals(expected, ignoreCase = true)) {
                    part.delete()
                    DownloadStore.update(id) {
                        it.copy(
                            status = DownloadStatus.FAILED,
                            error = "文件校验失败（sha256 不匹配）",
                            updatedAtMs = System.currentTimeMillis(),
                        )
                    }
                    DownloadStore.task(id)?.let { notifications.finished(it, ok = false) }
                    return
                }
                val finalFile = DownloadPaths.finalFile(this, task.request.fileName)
                if (finalFile.exists()) finalFile.delete()
                val moved = part.renameTo(finalFile)
                DownloadStore.update(id) {
                    it.copy(
                        status = DownloadStatus.COMPLETED,
                        downloadedBytes = result.bytes,
                        totalBytes = maxOf(result.bytes, it.totalBytes),
                        file = if (moved) finalFile else part,
                        error = null,
                        updatedAtMs = System.currentTimeMillis(),
                    )
                }
                DownloadStore.task(id)?.let { notifications.finished(it, ok = true) }
            }
            is DownloadResult.Failed -> {
                DownloadStore.update(id) {
                    it.copy(
                        status = DownloadStatus.FAILED,
                        error = result.message,
                        updatedAtMs = System.currentTimeMillis(),
                    )
                }
                DownloadStore.task(id)?.let { notifications.finished(it, ok = false) }
            }
            DownloadResult.Canceled -> {
                CancelRegistry.clear(id)
                DownloadStore.update(id) {
                    it.copy(status = DownloadStatus.CANCELED, updatedAtMs = System.currentTimeMillis())
                }
                notifications.cancelFinished(id)
            }
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                DownloadNotifications.FOREGROUND_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(DownloadNotifications.FOREGROUND_ID, notification)
        }
    }

    private fun stopSelfSafely() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    companion object {
        internal const val ACTION_ENQUEUE = "com.branchbase.downloader.action.ENQUEUE"
        internal const val EXTRA_ID = "com.branchbase.downloader.extra.ID"
    }
}
