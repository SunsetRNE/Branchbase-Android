package com.branchbase.cache

/**
 * 搜索缓存管理器：封装 LRU 淘汰 + 分类型过期时间（TTL）。
 *
 * 使用方式（在协程中）：
 * ```
 * val cached = manager.get(key, type)
 * if (cached != null) { /* 命中 */ }
 * else { /* 未命中，拉远端后 manager.put(key, type, data) */ }
 * ```
 */
class SearchCacheManager(private val dao: SearchCacheDao) {

    companion object {
        /** 最大缓存条目数（超出则 LRU 淘汰最旧） */
        const val MAX_ENTRIES = 100

        /**
         * 不同类型的缓存有效期（毫秒）。
         * 代码搜索速率限制最严（10次/分钟），缓存更久。
         */
        fun ttlFor(type: String): Long = when (type) {
            "代码" -> 60 * 60 * 1000L          // 1 小时（代码搜索速率限制最严，缓存更久）
            "仓库" -> 30 * 60 * 1000L          // 30 分钟
            "分支" -> 30 * 60 * 1000L          // 30 分钟（分支列表变化不频繁）
            "README" -> 30 * 60 * 1000L        // 30 分钟（README 渲染 HTML 缓存）
            "用户", "Issues", "拉取请求" -> 15 * 60 * 1000L // 15 分钟
            "提交" -> 10 * 60 * 1000L          // 10 分钟（提交搜索也有速率限制）
            "主题" -> 60 * 60 * 1000L          // 1 小时（主题变化慢）
            else -> 30 * 60 * 1000L            // 默认 30 分钟
        }
    }

    /** 查询缓存（命中返回 data，未命中/已过期返回 null） */
    suspend fun get(key: String, type: String): String? {
        val now = System.currentTimeMillis()
        dao.deleteExpired(now)
        return dao.get(key, now)?.data
    }

    /** 写入缓存，并做 LRU 淘汰 */
    suspend fun put(key: String, type: String, data: String) {
        val now = System.currentTimeMillis()
        dao.insert(
            SearchCacheEntity(
                key = key,
                type = type,
                data = data,
                createdAt = now,
                expireAt = now + ttlFor(type),
            )
        )
        val count = dao.count()
        if (count > MAX_ENTRIES) {
            dao.deleteOldest(count - MAX_ENTRIES)
        }
    }

    /** 删除指定 key 的缓存（bypass：强制刷新时清除） */
    suspend fun delete(key: String) {
        dao.delete(key)
    }
}