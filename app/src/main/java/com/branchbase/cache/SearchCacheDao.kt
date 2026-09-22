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

    /** 查询未过期的缓存（同时匹配 type，避免不同类型同 key 互相污染命中结果） */
    @Query("SELECT * FROM search_cache WHERE key = :key AND type = :type AND expireAt > :now")
    suspend fun get(key: String, type: String, now: Long): SearchCacheEntity?

    /**
     * 查询缓存**忽略 TTL**（stale-while-revalidate：先渲染旧数据，再后台刷新）。
     *
     * 与 [get] 的差别只有不过滤 `expireAt`：调用方拿到旧数据后必须触发一次回源，
     * 否则会一直看到过期内容。
     */
    @Query("SELECT * FROM search_cache WHERE key = :key AND type = :type")
    suspend fun getStale(key: String, type: String): SearchCacheEntity?

    /** 插入或替换缓存 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SearchCacheEntity)

    /**
     * 删除 `expireAt <= before` 的缓存，返回删掉的条数。
     *
     * ⚠️ 调用方传的是**过期宽限之后的时刻**（`now - STALE_GRACE_MS`），不是 `now`：
     * 直接按 `now` 删会把「过期但仍要直出」的行（[getStale] 的 stale-while-revalidate）
     * 一起删掉，两条机制互相抵消。口径见 `SearchCacheManager.STALE_GRACE_MS`。
     *
     * 返回条数是给调用方记日志用的（「清理过期条目 N 条」）—— 只返回 Unit 的话，
     * 清理这条路径在日志里就是不可见的，出了问题只能靠猜。
     */
    @Query("DELETE FROM search_cache WHERE expireAt <= :before")
    suspend fun deleteExpired(before: Long): Int

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