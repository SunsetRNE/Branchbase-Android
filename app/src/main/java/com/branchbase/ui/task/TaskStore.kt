package com.branchbase.ui.task

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 任务中心门面：全 App 统一的任务记录入口（本地 Room，不上传）。
 *
 * 用法：
 * ```kotlin
 * val id = TaskStore.start(context, TaskKind.CLONE, "拉取仓库 owner/repo")
 * TaskStore.progress(context, id, 37, "正在接收对象 142/380")
 * TaskStore.finish(context, id, success = true, detail = "已拉取")
 * ```
 */
object TaskStore {

    /** 短任务保留期：7 天。 */
    private const val SHORT_TASK_TTL_MS = 7L * 24 * 60 * 60 * 1000

    private fun dao(context: Context): TaskDao = TaskDatabase.getInstance(context).taskDao()

    /** 新建任务（返回 id；引擎不可用时返回 0）。 */
    suspend fun start(context: Context, kind: TaskKind, title: String): Long = withContext(Dispatchers.IO) {
        runCatching {
            val now = System.currentTimeMillis()
            dao(context).insert(
                TaskEntity(
                    title = title,
                    kind = kind.name,
                    status = TaskStatus.RUNNING.name,
                    createdAt = now,
                    updatedAt = now,
                    progress = -1,
                    detail = "已开始",
                    durable = kind.durable,
                ),
            )
        }.getOrDefault(0L)
    }

    /** 短时更新（运行中任务的进度/详情）。 */
    suspend fun progress(context: Context, id: Long, progress: Int, detail: String) {
        if (id <= 0) return
        withContext(Dispatchers.IO) {
            runCatching {
                dao(context).updateProgress(id, TaskStatus.RUNNING.name, System.currentTimeMillis(), progress, detail)
            }
        }
    }

    /** 结束任务（成功/失败/取消）。 */
    suspend fun finish(
        context: Context,
        id: Long,
        status: TaskStatus,
        detail: String = "",
        progress: Int = 100,
    ) {
        if (id <= 0) return
        withContext(Dispatchers.IO) {
            runCatching {
                dao(context).finish(id, status.name, System.currentTimeMillis(), progress, detail)
            }
        }
    }

    /** 便捷：成功结束。 */
    suspend fun success(context: Context, id: Long, detail: String = "已完成") =
        finish(context, id, TaskStatus.SUCCESS, detail)

    /** 便捷：失败结束（detail 建议透出具体原因）。 */
    suspend fun fail(context: Context, id: Long, detail: String) =
        finish(context, id, TaskStatus.FAILED, detail, 100)

    /** 便捷：取消。 */
    suspend fun cancel(context: Context, id: Long, detail: String = "已取消（用户中断）") =
        finish(context, id, TaskStatus.CANCELED, detail, 100)

    /** 查询全部（按创建时间倒序）。 */
    suspend fun list(context: Context): List<TaskRecord> = withContext(Dispatchers.IO) {
        runCatching { dao(context).all().map { it.toRecord() } }.getOrDefault(emptyList())
    }

    /** 查询单条。 */
    suspend fun byId(context: Context, id: Long): TaskRecord? = withContext(Dispatchers.IO) {
        runCatching { dao(context).byId(id)?.toRecord() }.getOrNull()
    }

    /** 删除单条。 */
    suspend fun delete(context: Context, id: Long) {
        withContext(Dispatchers.IO) { runCatching { dao(context).delete(id) } }
    }

    /** 清理已完成/失败/取消（保留运行中）。返回清理条数。 */
    suspend fun clearFinished(context: Context): Int = withContext(Dispatchers.IO) {
        runCatching {
            val before = dao(context).all().size
            dao(context).clearFinished()
            val after = dao(context).all().size
            before - after
        }.getOrDefault(0)
    }

    /** 全部清除（含运行中）。返回清理条数。 */
    suspend fun clearAll(context: Context): Int = withContext(Dispatchers.IO) {
        runCatching {
            val before = dao(context).all().size
            dao(context).clearAll()
            before
        }.getOrDefault(0)
    }

    /** 运行中任务数（用于入口角标）。 */
    suspend fun runningCount(context: Context): Int = withContext(Dispatchers.IO) {
        runCatching { dao(context).runningCount() }.getOrDefault(0)
    }

    /** 清理超期短任务（App 启动时调用一次）。返回清理条数。 */
    suspend fun prune(context: Context): Int = withContext(Dispatchers.IO) {
        runCatching {
            dao(context).pruneShort(System.currentTimeMillis() - SHORT_TASK_TTL_MS)
        }.getOrDefault(0)
    }
}
