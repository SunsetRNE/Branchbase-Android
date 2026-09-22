package com.branchbase.cache

import com.branchbase.ui.log.LogCategory
import com.branchbase.ui.log.Logger

/**
 * 搜索缓存管理器：**两级**（进程内 L1 + Room L2）+ LRU 淘汰 + 分类型过期时间（TTL）。
 *
 * 使用方式（在协程中）：
 * ```
 * val cached = manager.get(key, type)
 * if (cached != null) { /* 命中 */ }
 * else { /* 未命中，拉远端后 manager.put(key, type, data) */ }
 * ```
 *
 * ## 两级的分工（2026-09 加的内存层）
 *
 * | 层 | 存什么 | 命中代价 | 谁受益 |
 * |----|--------|----------|--------|
 * | L1 [MemoryCache] | 同一份 JSON 字符串 | 一次哈希查找（**同帧**） | 「重进页面」这类高频动作：切 Tab、返回再进 |
 * | L2 Room | 同上，落盘 | 跨线程 + SQL + 游标 | 冷启动、跨进程 |
 *
 * ## 读路径不再写库（修掉的一处浪费）
 *
 * 原实现 `get()` 的第一行是 `dao.deleteExpired(now)` —— **每读一次缓存就写一次库**
 * （DELETE 事务）。重进一个页面往往要读好几个 key，于是「打开页面」这件事在磁盘上
 * 平白多出几笔写。现在过期清理挪到 [sweepIfDue]：**每个进程一次 + 之后每 5 分钟一次**，
 * 由写入路径触发，读路径只剩查询。
 *
 * ## 命中日志
 *
 * 四种结果都记一条 DEBUG（`缓存` tag）：`L1 命中` / `L2 命中` / `直出（含过期）` / `未命中`。
 * 「重进页面到底还付了什么」这个问题，此前只能靠猜 —— 页面自己那句 `GET /xxx → 200`
 * 无论命中与否都会打，证明不了任何事。
 */
class SearchCacheManager(
    private val dao: SearchCacheDao,
    private val memory: MemoryCache = sharedMemoryCache,
) {

    companion object {
        /**
         * 最大缓存条目数（超出则 LRU 淘汰最旧）。
         *
         * 预加载会把「仓库信息 / README / 语言 / 贡献者 / 分支」一次性写入（每仓库最多 5 条），
         * 100 条只够 20 个仓库；提到 160 条可覆盖约 30 个仓库的往返，同时不至于让
         * 大 README（数百 KB）把库撑爆。
         */
        const val MAX_ENTRIES = 160

        /** 过期清理的最小间隔（进程内节流，见 [sweepIfDue]）。 */
        private const val SWEEP_INTERVAL_MS = 5 * 60 * 1000L

        /** 上次清理时间（进程级）。0 表示本进程还没清过。 */
        @Volatile
        private var lastSweepAt = 0L

        /**
         * 该不该做一次过期清理（纯函数，便于单测）。
         *
         * 提出来是因为「节流」这件事只能靠时间判断，而时间在单测里不可控 ——
         * 判断留在 [sweepIfDue] 里就只能测「调用了几次」这种间接事实。
         */
        internal fun shouldSweep(
            now: Long,
            lastSweepAt: Long,
            intervalMs: Long = SWEEP_INTERVAL_MS,
        ): Boolean = now - lastSweepAt >= intervalMs

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
            // 当前用户与仓库的关系（星标双向态 / Watch 档位 / 复刻能力）：
            // 只给 5 分钟 —— 它决定按钮点下去做什么，太旧会把「已星标」显示成「星标」。
            // 换来的是「返回上一层再进来」不再重复一次判定请求。
            "仓库关系" -> 5 * 60 * 1000L
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

    /**
     * 查询缓存（命中返回 data，未命中/已过期返回 null）；type 参与匹配，防止跨类型误命中。
     *
     * **先 L1 再 L2**：L1 命中即同帧返回；L2 命中会**回填 L1**（下次同页重进便是同帧）。
     */
    suspend fun get(key: String, type: String): String? {
        val now = System.currentTimeMillis()
        memory.get(key, type, now)?.let {
            Logger.debug(LogCategory.NETWORK, "缓存", "L1 命中 $key")
            return it.data
        }
        val row = dao.get(key, type, now)
        if (row == null) {
            Logger.debug(LogCategory.NETWORK, "缓存", "未命中 $key（type=$type）")
            return null
        }
        memory.put(row.key, row.type, row.data, row.expireAt)
        Logger.debug(LogCategory.NETWORK, "缓存", "L2 命中 $key（已回填 L1）")
        return row.data
    }

    /**
     * 查询缓存并**忽略 TTL**（stale-while-revalidate 用）。
     *
     * 调用方拿到值后必须立即回源刷新（[put]），否则用户会一直看到过期数据。
     * 典型用法：仓库页先直出上次的内容，再在后台拉最新。
     */
    suspend fun getStale(key: String, type: String): String? {
        memory.getStale(key, type)?.let {
            Logger.debug(LogCategory.NETWORK, "缓存", "L1 直出（含过期）$key")
            return it.data
        }
        val row = dao.getStale(key, type)
        if (row == null) {
            Logger.debug(LogCategory.NETWORK, "缓存", "无缓存可直出 $key（type=$type）")
            return null
        }
        memory.put(row.key, row.type, row.data, row.expireAt)
        Logger.debug(LogCategory.NETWORK, "缓存", "L2 直出（含过期）$key（已回填 L1）")
        return row.data
    }

    /** 缓存是否存在且未过期（决定是否需要回源 / 是否值得预取）。 */
    suspend fun isFresh(key: String, type: String): Boolean {
        val now = System.currentTimeMillis()
        if (memory.get(key, type, now) != null) return true
        val row = dao.get(key, type, now) ?: return false
        memory.put(row.key, row.type, row.data, row.expireAt)
        return true
    }

    /** 写入缓存：L1 与 L2 同时写，并按需做 LRU 淘汰 / 过期清理。 */
    suspend fun put(key: String, type: String, data: String) {
        val now = System.currentTimeMillis()
        val expireAt = now + ttlFor(type)
        memory.put(key, type, data, expireAt)
        dao.insert(
            SearchCacheEntity(
                key = key,
                type = type,
                data = data,
                createdAt = now,
                expireAt = expireAt,
            )
        )
        val count = dao.count()
        if (count > MAX_ENTRIES) {
            dao.deleteOldest(count - MAX_ENTRIES)
        }
        sweepIfDue(now)
    }

    /**
     * 删除指定 key 的缓存（bypass：强制刷新时清除）。
     *
     * **两层一起删**：只删 L2 的话，下一次 [getStale] 会把 L1 里的旧值又直出回来。
     */
    suspend fun delete(key: String) {
        memory.delete(key)
        dao.delete(key)
    }

    /**
     * 过期清理：**每个进程一次，之后每 [SWEEP_INTERVAL_MS] 一次**，由写入路径触发。
     *
     * 为什么不在读路径做：那是「每读一次缓存就写一次库」（历史实现就在 `get()` 第一行）。
     * 为什么仍要清：Room 的 LRU 只按条数淘汰，过期行会一直占着名额。
     */
    private suspend fun sweepIfDue(now: Long) {
        if (!shouldSweep(now, lastSweepAt)) return
        synchronized(this::class.java) {
            if (!shouldSweep(System.currentTimeMillis(), lastSweepAt)) return
            lastSweepAt = now
        }
        val removed = dao.deleteExpired(now)
        if (removed > 0) {
            Logger.debug(LogCategory.LOCAL_TASK, "缓存", "清理过期条目 $removed 条")
        }
    }
}
