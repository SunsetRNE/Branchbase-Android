package com.branchbase.downloader

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.StateFlow

/**
 * 模块装配配置（由 :app 注入；模块内不引用任何 App 类型）。
 */
data class DownloaderConfig(
    /**
     * 通知小图标。
     *
     * 状态栏图标要求**单色小图**，把 launcher 图标塞进去会显示成一块白方块；
     * 不注入时用系统自带的下载图标兜底（保证模块单独可跑）。
     */
    val smallIconRes: Int = android.R.drawable.stat_sys_download,
    /**
     * 凭据提供者：按 URL 决定是否附带 `Authorization`。
     * 引擎在**每一跳重定向**都会重新问一次，调用方只需按 host 判断（见 [AuthProvider]）。
     */
    val auth: AuthProvider = AuthProvider { null },
    /** User-Agent（GitHub 对无 UA 的请求会 403）。 */
    val userAgent: String = "Branchbase/0.1",
)

/**
 * 内建下载的装配点与门面。
 *
 * 依赖方向：`:app → :downloader`。App 侧只需要三件事：
 * 1. `Application.onCreate` 里 [install] 一次（注入凭据与小图标）；
 * 2. 下载时 [enqueue] 一条 [DownloadRequest]；
 * 3. UI 侧 `collect` [tasks] 画进度（与系统通知同源，不会出现两个进度）。
 *
 * 失败 / 取消 / 重试都**不依赖返回值**：状态一律通过 [tasks] 回流，
 * 这样「退到后台再回来」也能看到正确状态。
 */
object DownloaderRuntime {

    @Volatile
    internal var config: DownloaderConfig = DownloaderConfig()
        private set

    /** 由 `Application.onCreate` 调用一次。 */
    fun install(context: Context, config: DownloaderConfig = DownloaderConfig()) {
        this.config = config
    }

    /** 全部任务（新任务在前）。 */
    val tasks: StateFlow<List<DownloadTask>> get() = DownloadStore.tasks

    fun task(id: String): DownloadTask? = DownloadStore.task(id)

    /**
     * 入队下载。
     *
     * **幂等**：同一 [DownloadRequest.id] 已在队列 / 进行中时直接返回，不会重复下载
     * （用户在发布页连点两下「下载」是常态）。
     *
     * @return 任务 id（等于 `request.id`）。
     */
    fun enqueue(context: Context, request: DownloadRequest): String {
        CancelRegistry.clear(request.id)
        val existing = DownloadStore.task(request.id)
        if (existing != null && existing.isActive) return request.id
        DownloadStore.put(DownloadTask(id = request.id, request = request, status = DownloadStatus.QUEUED))
        val intent = Intent(context, DownloadService::class.java)
            .setAction(DownloadService.ACTION_ENQUEUE)
            .putExtra(DownloadService.EXTRA_ID, request.id)
        ContextCompat.startForegroundService(context, intent)
        return request.id
    }

    /**
     * 取消。
     *
     * 本地状态**立刻**改成 CANCELED（UI 不等服务），真正的 I/O 打断由服务侧的
     * [CancelRegistry] 轮询完成；已下载的 `.part` 保留，重试时走断点续传。
     */
    fun cancel(id: String) {
        CancelRegistry.cancel(id)
        DownloadStore.update(id) {
            if (it.isActive) {
                it.copy(status = DownloadStatus.CANCELED, updatedAtMs = System.currentTimeMillis())
            } else {
                it
            }
        }
    }

    /** 重试：清掉旧记录后走同一条入队链路（`.part` 还在就续传）。 */
    fun retry(context: Context, id: String) {
        val task = DownloadStore.task(id) ?: return
        DownloadStore.remove(id)
        enqueue(context, task.request)
    }

    /** 从列表里移除一条记录（不动已落盘的文件）。 */
    fun dismiss(id: String) {
        CancelRegistry.clear(id)
        DownloadStore.remove(id)
    }

    /** 清掉所有已结束的记录。 */
    fun clearFinished() = DownloadStore.clearFinished()
}
