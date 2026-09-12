package com.branchbase.downloader

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 进程内的任务表（**唯一事实来源**）。
 *
 * 三条约束：
 * 1. **不做持久化**：下载是短生命周期行为，进程被杀就重来（也没有「重启后续传」的语义）；
 *    真要跨进程恢复，得落盘 + WorkManager，是另一套设计 —— 这里不做半套；
 * 2. **整表替换**：StateFlow 里放不可变 `List`，每次变更产出新列表 —— 读取方永远看到自洽的一份，
 *    Compose 侧的 diff 也稳定；
 * 3. **加锁读改写**：写入来自下载线程与主线程（取消 / 移除 / 清理），必须串行化。
 */
object DownloadStore {

    private val lock = Any()
    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())

    /** 全部任务（新任务在前）。 */
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    fun task(id: String): DownloadTask? = _tasks.value.firstOrNull { it.id == id }

    /** 新增或整条替换。 */
    fun put(task: DownloadTask) = synchronized(lock) {
        val list = _tasks.value
        val index = list.indexOfFirst { it.id == task.id }
        _tasks.value = if (index >= 0) list.toMutableList().also { it[index] = task } else listOf(task) + list
    }

    /** 只改一条任务的若干字段（进度这类高频更新用，避免调用方拼整条）。 */
    fun update(id: String, transform: (DownloadTask) -> DownloadTask) = synchronized(lock) {
        val index = _tasks.value.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized
        val list = _tasks.value.toMutableList()
        list[index] = transform(list[index])
        _tasks.value = list
    }

    fun remove(id: String) = synchronized(lock) {
        _tasks.value = _tasks.value.filterNot { it.id == id }
    }

    /** 清掉已结束的记录（完成 / 失败 / 取消）。 */
    fun clearFinished() = synchronized(lock) {
        _tasks.value = _tasks.value.filter { it.isActive }
    }
}

/**
 * 取消信号表。
 *
 * 为什么不用「给服务发 ACTION_CANCEL 的 Intent」：服务是 `startForegroundService` 起来的，
 * 每个 Intent 都必须配套一次 `startForeground`（5 秒约定）；
 * 而「用户取消」时服务可能已经跑完在收尾 —— 为一次取消再拉起前台服务，既重又容易踩坑。
 * 同进程内用一个并发集合传信号最直接（模块与 App 默认同进程，见 DownloadService 的说明）。
 */
internal object CancelRegistry {

    private val ids = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    fun cancel(id: String) {
        ids.add(id)
    }

    fun isCanceled(id: String): Boolean = id in ids

    fun clear(id: String) {
        ids.remove(id)
    }
}
