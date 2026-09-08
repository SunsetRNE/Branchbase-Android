package com.branchbase.ui.task

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 任务中心（本地持久化任务记录）。
 *
 * 对齐 `design/task-prototype.html`：
 * - 长任务（clone / pull / push / PR 创建等）：保留至手动清理
 * - 短任务（提交 / 查询类）：按保留期自动清理
 * - 运行中任务：短时更新 progress/detail，永不自动清理
 *
 * 多账号：任务按 `account`（账号 login）隔离，互不可见、数据不互通。
 * 删除账号不会删除其任务记录 —— 重新添加同 login 账号后自动重新可见。
 */

/** 任务类型（用于列表标签与分组）。 */
enum class TaskKind(val label: String, val durable: Boolean) {
    CLONE("CLONE", true),
    PULL("PULL", true),
    PUSH("PUSH", true),
    COMMIT("COMMIT", false),
    PR("PR", true),
    MERGE("MERGE", true),
    SYNC("SYNC", false),
    OTHER("TASK", false),
}

/** 任务状态。 */
enum class TaskStatus(val label: String) {
    RUNNING("运行中"),
    SUCCESS("已完成"),
    FAILED("失败"),
    CANCELED("已取消"),
}

/** 列表筛选。 */
enum class TaskFilter(val label: String) {
    ALL("全部"),
    RUNNING("运行中"),
    SUCCESS("已完成"),
    FAILED("失败"),
}

/** 任务记录（Room 实体）。 */
@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 归属账号 login（空串 = 登录前产生的任务，首次登录后可被认领） */
    val account: String = "",
    val title: String,
    val kind: String,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    val finishedAt: Long? = null,
    /** 0..100；-1 表示不确定进度（滑动条） */
    val progress: Int = -1,
    val detail: String = "",
    /** true = 长任务（保留至手动删除） */
    val durable: Boolean = true,
)

/** 任务记录（UI 模型）。 */
data class TaskRecord(
    val id: Long,
    val title: String,
    val kind: TaskKind,
    val status: TaskStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val finishedAt: Long?,
    val progress: Int,
    val detail: String,
    val durable: Boolean,
)

fun TaskEntity.toRecord(): TaskRecord = TaskRecord(
    id = id,
    title = title,
    kind = runCatching { TaskKind.valueOf(kind) }.getOrDefault(TaskKind.OTHER),
    status = runCatching { TaskStatus.valueOf(status) }.getOrDefault(TaskStatus.RUNNING),
    createdAt = createdAt,
    updatedAt = updatedAt,
    finishedAt = finishedAt,
    progress = progress,
    detail = detail,
    durable = durable,
)

@Dao
interface TaskDao {

    @Insert
    suspend fun insert(entity: TaskEntity): Long

    @Query("UPDATE tasks SET status = :status, updatedAt = :now, progress = :progress, detail = :detail WHERE id = :id")
    suspend fun updateProgress(id: Long, status: String, now: Long, progress: Int, detail: String)

    @Query("UPDATE tasks SET status = :status, updatedAt = :now, finishedAt = :now, progress = :progress, detail = :detail WHERE id = :id")
    suspend fun finish(id: Long, status: String, now: Long, progress: Int, detail: String)

    @Query("SELECT * FROM tasks WHERE account = :account ORDER BY createdAt DESC")
    suspend fun all(account: String): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun byId(id: Long): TaskEntity?

    @Query("SELECT COUNT(*) FROM tasks WHERE account = :account")
    suspend fun count(account: String): Int

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun delete(id: Long)

    /** 清理当前账号已完成/失败/取消（保留运行中）。 */
    @Query("DELETE FROM tasks WHERE account = :account AND status != 'RUNNING'")
    suspend fun clearFinished(account: String)

    /** 清除当前账号全部任务（含运行中）。 */
    @Query("DELETE FROM tasks WHERE account = :account")
    suspend fun clearAll(account: String)

    /** 清理当前账号超期短任务（非 durable 且已结束）。 */
    @Query("DELETE FROM tasks WHERE account = :account AND durable = 0 AND status != 'RUNNING' AND finishedAt IS NOT NULL AND finishedAt < :before")
    suspend fun pruneShort(account: String, before: Long): Int

    @Query("SELECT COUNT(*) FROM tasks WHERE account = :account AND status = 'RUNNING'")
    suspend fun runningCount(account: String): Int

    /** 把登录前产生的孤儿任务认领给当前账号（只在首次登录后调用一次）。 */
    @Query("UPDATE tasks SET account = :account WHERE account = ''")
    suspend fun claimOrphans(account: String): Int
}

@Database(entities = [TaskEntity::class], version = 2, exportSchema = false)
abstract class TaskDatabase : RoomDatabase() {

    abstract fun taskDao(): TaskDao

    companion object {
        /** v1 → v2：任务增加账号归属列（老数据留在空账号下，登录后可认领）。 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN account TEXT NOT NULL DEFAULT ''")
            }
        }

        @Volatile
        private var instance: TaskDatabase? = null

        fun getInstance(context: Context): TaskDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    TaskDatabase::class.java,
                    "tasks.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
        }
    }
}
