package com.branchbase.ui.decision

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * PR 按仓库记忆。
 *
 * 记录两件每次开 PR / 合并都要重复决定的事：
 * - **描述模板**：同一仓库的 PR 描述结构通常固定，首次填写后自动预填
 * - **合并策略**：squash / merge / rebase 与「合并后删分支」按仓库记住
 *
 * 独立库 `pr_memory.db`，与任务库（tasks.db）分开，避免职责混杂。
 */
@Entity(tableName = "pr_memory")
data class PrMemoryEntity(
    /** `owner/repo` */
    @PrimaryKey val repo: String,
    val template: String = "",
    /** 0=squash 1=merge 2=rebase */
    val mergeStrategy: Int = 0,
    val deleteBranch: Boolean = true,
    val updatedAt: Long = 0L,
)

@Dao
interface PrMemoryDao {

    @Query("SELECT * FROM pr_memory WHERE repo = :repo")
    suspend fun get(repo: String): PrMemoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: PrMemoryEntity)

    @Query("DELETE FROM pr_memory WHERE repo = :repo")
    suspend fun clear(repo: String)
}

@Database(entities = [PrMemoryEntity::class], version = 1, exportSchema = false)
abstract class PrMemoryDatabase : RoomDatabase() {

    abstract fun dao(): PrMemoryDao

    companion object {
        @Volatile
        private var instance: PrMemoryDatabase? = null

        fun getInstance(context: Context): PrMemoryDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PrMemoryDatabase::class.java,
                    "pr_memory.db",
                ).build().also { instance = it }
            }
        }
    }
}

/** 门面：按仓库读写 PR 记忆（读写失败静默降级，不影响主流程）。 */
object PrMemoryStore {

    /** 合并策略的展示名（与 PrMergeScreen 的下标一致）。 */
    val strategyLabels = listOf("Squash", "Merge commit", "Rebase")

    suspend fun get(context: Context, repo: String): PrMemoryEntity? = runCatching {
        PrMemoryDatabase.getInstance(context).dao().get(repo)
    }.getOrNull()

    /**
     * 增量保存：只覆盖传入的字段，其余保持原值。
     */
    suspend fun save(
        context: Context,
        repo: String,
        template: String? = null,
        strategy: Int? = null,
        deleteBranch: Boolean? = null,
    ) {
        runCatching {
            val dao = PrMemoryDatabase.getInstance(context).dao()
            val old = dao.get(repo)
            dao.put(
                PrMemoryEntity(
                    repo = repo,
                    template = template ?: old?.template ?: "",
                    mergeStrategy = strategy ?: old?.mergeStrategy ?: 0,
                    deleteBranch = deleteBranch ?: old?.deleteBranch ?: true,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }
}
