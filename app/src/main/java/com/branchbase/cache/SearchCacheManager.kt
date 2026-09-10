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
        /**
         * 最大缓存条目数（超出则 LRU 淘汰最旧）。
         *
         * 预加载会把「仓库信息 / README / 语言 / 贡献者 / 分支」一次性写入（每仓库最多 5 条），
         * 100 条只够 20 个仓库；提到 160 条可覆盖约 30 个仓库的往返，同时不至于让
         * 大 README（数百 KB）把库撑爆。
         */
        const val MAX_ENTRIES = 160

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
            // 仓库详情页元数据（翻仓库时最常重复拉取的部分）
            "仓库信息" -> 15 * 60 * 1000L      // 15 分钟（star 数/默认分支等变化不频繁）
            "仓库语言" -> 60 * 60 * 1000L      // 1 小时（语言构成几乎不变）
            "仓库贡献者" -> 30 * 60 * 1000L    // 30 分钟
            // 仓库内列表页（代码树/Issue/PR/提交/工作流/发布）：切 tab 秒开，但不能太旧
            "仓库列表" -> 5 * 60 * 1000L       // 5 分钟
            // 详情页（Issue/PR/提交/Release/工作流 run/分支列表/对比/工作流 YAML）
            "页面详情" -> 5 * 60 * 1000L       // 5 分钟（详情变化慢，返回再进应秒开）
            // 文件内容：同一文件反复查看（读代码、对照 diff）命中率最高
            "文件内容" -> 10 * 60 * 1000L      // 10 分钟
            // 首页仪表盘计数（未读通知/待评审/指派给我）
            "首页" -> 5 * 60 * 1000L           // 5 分钟
            // 通知：既要有「未读」的时效性，也要避免每次进页面都转圈
            "通知" -> 2 * 60 * 1000L           // 2 分钟
            // 个人主页（我的仓库/贡献日历/活动）
            "个人主页" -> 10 * 60 * 1000L      // 10 分钟
            else -> 30 * 60 * 1000L            // 默认 30 分钟
        }
    }

    /** 查询缓存（命中返回 data，未命中/已过期返回 null）；type 参与匹配，防止跨类型误命中 */
    suspend fun get(key: String, type: String): String? {
        val now = System.currentTimeMillis()
        dao.deleteExpired(now)
        return dao.get(key, type, now)?.data
    }

    /**
     * 查询缓存并**忽略 TTL**（stale-while-revalidate 用）。
     *
     * 调用方拿到值后必须立即回源刷新（[put]），否则用户会一直看到过期数据。
     * 典型用法：仓库页先直出上次的内容，再在后台拉最新。
     */
    suspend fun getStale(key: String, type: String): String? = dao.getStale(key, type)?.data

    /** 缓存是否存在且未过期（决定是否需要回源 / 是否值得预取）。 */
    suspend fun isFresh(key: String, type: String): Boolean =
        dao.get(key, type, System.currentTimeMillis()) != null

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