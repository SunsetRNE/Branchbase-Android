package com.branchbase.cache

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * 搜索缓存 DAO。
 */
@Dao
interface SearchCacheDao {

    /** 查询未过期的缓存 */
    @Query("SELECT * FROM search_cache WHERE key = :key AND expireAt > :now")
    suspend fun get(key: String, now: Long): SearchCacheEntity?

    /** 插入或替换缓存 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SearchCacheEntity)

    /** 删除已过期的缓存 */
    @Query("DELETE FROM search_cache WHERE expireAt <= :now")
    suspend fun deleteExpired(now: Long)

    /** 缓存条目数 */
    @Query("SELECT COUNT(*) FROM search_cache")
    suspend fun count(): Int

    /** 删除最旧的 n 条（LRU 淘汰，按创建时间升序） */
    @Query("DELETE FROM search_cache WHERE key IN (SELECT key FROM search_cache ORDER BY createdAt ASC LIMIT :n)")
    suspend fun deleteOldest(n: Int)

    /** 删除指定 key 的缓存（bypass：强制刷新时清除） */
    @Query("DELETE FROM search_cache WHERE key = :key")
    suspend fun delete(key: String)
}