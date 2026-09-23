package com.branchbase.cache

/**
 * 内存版假 DAO：只实现测试关心的事实（行、清扫次数、写入次数）。
 *
 * [SearchCacheDao] 是接口，所以缓存策略能在纯 JVM 单测里跑，不需要 Room / Android。
 * 抽成独立文件是因为它现在被两个测试类用：缓存管理器的口径（L1/L2、清扫节流）
 * 与页面级回源的取消语义（`PageCache.refreshDetached`）。
 */
internal class FakeSearchCacheDao : SearchCacheDao {

    val rows = mutableMapOf<String, SearchCacheEntity>()
    var deleteExpiredCalls = 0
    var insertCalls = 0

    override suspend fun get(key: String, type: String, now: Long): SearchCacheEntity? =
        rows[key]?.takeIf { it.type == type && it.expireAt > now }

    override suspend fun getStale(key: String, type: String): SearchCacheEntity? =
        rows[key]?.takeIf { it.type == type }

    override suspend fun insert(entity: SearchCacheEntity) {
        insertCalls++
        rows[entity.key] = entity
    }

    override suspend fun deleteExpired(before: Long): Int {
        deleteExpiredCalls++
        val victims = rows.filterValues { it.expireAt <= before }.keys
        victims.forEach { rows.remove(it) }
        return victims.size
    }

    override suspend fun count(): Int = rows.size

    override suspend fun deleteOldest(n: Int) {
        rows.entries.sortedBy { it.value.createdAt }.take(n).forEach { rows.remove(it.key) }
    }

    override suspend fun delete(key: String) {
        rows.remove(key)
    }
}
