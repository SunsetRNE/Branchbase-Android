package com.branchbase.cache

/**
 * 进程内一级缓存（L1）：`key → (json, expireAt)`，LRU + 字节预算。
 *
 * ## 为什么要在 Room 前面再加一层
 *
 * 「重进页面」是**高频**动作（切 Tab、返回上一层再进去、返回后重开详情），而每次重进都会
 * 把同一份数据再从磁盘读一遍。`SearchCacheManager` 直连 Room 时有三个固定开销：
 *
 * 1. **一次 Room 查询** = 一次跨线程调度 + SQL 解析 + 游标读取（即使行很小，也要走一遍）；
 * 2. 历史实现里 `get()` 的**第一行是 `deleteExpired(now)`** —— 读路径上挂了一次写事务，
 *    每读一次缓存就写一次库；
 * 3. 读出来的是**字符串**，页面还得再解析一次（`JSONObject` / `JSONArray`）。
 *
 * 这一层把 1、2 压成一次哈希查找（同帧返回，零 IO）。第 3 点不归它管：解析结果的记忆化
 * 留在页面自己手里（例如消息页按原文记忆化 [com.branchbase.ui.notification.NotifArchive]）。
 *
 * ## 两条预算，都要
 *
 * - **条数**（[maxEntries]）：与 Room 的 LRU 同量级；
 * - **字节**（[maxBytes]）：README 渲染结果动辄数百 KB，只限条数会让堆悄悄涨到几十 MB。
 *
 * 超预算按 **LRU（访问序）** 淘汰 —— 命中过的 key 会被移到队尾，而「重进页面」恰恰
 * 总是命中同一批 key，所以它们最不容易被淘汰。
 *
 * ## 线程
 *
 * 所有方法都可能在任意线程被调用（Room 的 suspend 查询本来就在 IO 线程上），
 * 内部用一把锁串行化；临界区只有哈希操作，没有 IO、没有回调，不会成为瓶颈。
 */
class MemoryCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    companion object {
        /** 条数上限：略高于 Room 的 [SearchCacheManager.MAX_ENTRIES]，让 L1 先兜住热点。 */
        const val DEFAULT_MAX_ENTRIES = 200

        /**
         * 字节上限（12 MB）。
         *
         * 取值理由：手机堆一般 192~512MB，缓存只该占零头；而单条大 README 约 200~500KB，
         * 12MB 足够放几十条大页 + 上百条小页。
         */
        const val DEFAULT_MAX_BYTES = 12L * 1024 * 1024

        /** 字符串按 UTF-16 估算的字节数（够用即可，不必真的编码一遍）。 */
        private fun bytesOf(data: String): Long = data.length * 2L
    }

    class Entry(val type: String, val data: String, val expireAt: Long) {
        internal val bytes: Long = bytesOf(data)
    }

    // accessOrder = true：get 也算「访问」，队尾是最新的 —— 这正是 LRU 要的语义
    private val map = LinkedHashMap<String, Entry>(64, 0.75f, true)
    private var bytes = 0L

    /**
     * 读**未过期**的条目（新鲜才算命中）。
     *
     * `type` 参与匹配，与 Room 的 `get(key, type, now)` 语义保持一致 ——
     * 不同类型共用同一个 key 时不能互相污染（历史上键空间出过这个问题）。
     */
    fun get(key: String, type: String, now: Long = System.currentTimeMillis()): Entry? =
        synchronized(this) {
            val e = map[key] ?: return null
            if (e.type != type || e.expireAt <= now) return null
            e
        }

    /**
     * 读**忽略 TTL** 的条目（stale-while-revalidate 的「先直出」用）。
     *
     * 拿到的可能是过期数据，调用方必须紧接着回源刷新。
     */
    fun getStale(key: String, type: String): Entry? = synchronized(this) {
        val e = map[key] ?: return null
        if (e.type != type) return null
        e
    }

    /** 写入（含覆盖）：同时维护字节计数与 LRU 顺序。 */
    fun put(key: String, type: String, data: String, expireAt: Long) {
        synchronized(this) {
            map.remove(key)?.let { bytes -= it.bytes }
            val e = Entry(type, data, expireAt)
            map[key] = e
            bytes += e.bytes
            trim()
        }
    }

    fun delete(key: String) {
        synchronized(this) {
            map.remove(key)?.let { bytes -= it.bytes }
        }
    }

    fun clear() {
        synchronized(this) {
            map.clear()
            bytes = 0L
        }
    }

    val size: Int get() = synchronized(this) { map.size }

    val bytesUsed: Long get() = synchronized(this) { bytes }

    /** 超预算就按 LRU 淘汰；两条预算任一超出都裁。 */
    private fun trim() {
        val it = map.entries.iterator()
        while ((map.size > maxEntries || bytes > maxBytes) && it.hasNext()) {
            val victim = it.next()
            bytes -= victim.value.bytes
            it.remove()
        }
    }
}

/**
 * 进程级共享实例（[SearchCacheManager] 默认用它）。
 *
 * 单独暴露成顶层值而不是藏在 manager 里：manager 是**按页面构造**的
 * （`remember { SearchCacheManager(dao) }`），缓存必须跨页面、跨重建共享，否则等于没有。
 */
internal val sharedMemoryCache = MemoryCache()
