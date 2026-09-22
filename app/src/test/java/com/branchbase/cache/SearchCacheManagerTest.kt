package com.branchbase.cache

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 两级缓存管理器单测：L2 命中回填 L1、读路径不写库、清扫节流、删除要两层一起删。
 *
 * 用内存版假 DAO（[SearchCacheDao] 是接口），不依赖 Room / Android ——
 * 这几条规则出错的后果都是「静默变慢」：读一次缓存写一次库、删了 L2 又被 L1 直出回来，
 * 都不会报错，只会在真机上表现为「重进页面还是慢」。
 *
 * ⚠️ **协程用例用 ASCII 方法名**：`runBlocking { }` 的 lambda 会被编译成一个匿名类，
 * 类名里带着所在方法名 —— 反引号中文方法名 + lambda 会让编译器去写一个含中文的 class 文件，
 * 在 locale 非 UTF-8 的机器上直接
 * `java.nio.file.InvalidPathException: Malformed input or input contains unmappable characters`
 * （报出来却是「Internal compiler error」，真因在 `e:` 那一行）。
 * 反引号中文名本身没问题（下面的纯函数用例就是），**别在它里面写 lambda**。
 */
class SearchCacheManagerTest {

    /** 内存版假 DAO：只实现测试关心的事实（行、清扫次数、写入次数）。 */
    private class FakeDao : SearchCacheDao {
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

        override suspend fun deleteExpired(now: Long): Int {
            deleteExpiredCalls++
            val victims = rows.filterValues { it.expireAt <= now }.keys
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

    private fun row(key: String, type: String, data: String, expireAt: Long) =
        SearchCacheEntity(key = key, type = type, data = data, createdAt = 1L, expireAt = expireAt)

    /** L2（磁盘）命中后必须回填 L1，否则「同页重进」永远还是走磁盘那一遍。 */
    @Test
    fun l2HitBackfillsL1() = runBlocking {
        val dao = FakeDao()
        val memory = MemoryCache()
        val mgr = SearchCacheManager(dao, memory)
        dao.rows["k"] = row("k", PageCache.TYPE_PROFILE, "JSON", expireAt = Long.MAX_VALUE)

        assertEquals("JSON", mgr.get("k", PageCache.TYPE_PROFILE))
        assertEquals("L2 命中要回填 L1", 1, memory.size)
        assertEquals("第二次应命中 L1（值一致）", "JSON", mgr.get("k", PageCache.TYPE_PROFILE))
    }

    /**
     * 读路径不许写库 —— 历史实现 `get()` 的第一行就是 `deleteExpired(now)`，
     * 于是「打开一个页面」在磁盘上平白多出几笔 DELETE 事务。
     *
     * ⚠️ 断言的是**契约**而不是「第一次 put 一定清扫」：节流状态
     * （`SearchCacheManager.lastSweepAt`）是**进程级**的，别的用例可能已经消费掉那次机会 ——
     * 按执行顺序写断言会变成随机红。
     */
    @Test
    fun readsNeverWrite() = runBlocking {
        val dao = FakeDao()
        val mgr = SearchCacheManager(dao, MemoryCache())

        repeat(3) { mgr.get("miss$it", PageCache.TYPE_PROFILE) }
        mgr.getStale("miss-x", PageCache.TYPE_PROFILE)
        mgr.isFresh("miss-y", PageCache.TYPE_PROFILE)
        assertEquals("读路径不许写库", 0, dao.deleteExpiredCalls)

        mgr.put("k", PageCache.TYPE_PROFILE, "JSON")
        val afterFirstPut = dao.deleteExpiredCalls
        mgr.put("k2", PageCache.TYPE_PROFILE, "JSON2")
        assertEquals("连续写入之间不再重复清理（节流）", afterFirstPut, dao.deleteExpiredCalls)
        assertTrue("写入最多触发一次清扫（进程内节流）", afterFirstPut <= 1)
    }

    /** 清扫节流判定（纯函数，不受进程内共享状态影响）。 */
    @Test
    fun `清扫节流判定是纯函数`() {
        val interval = 5 * 60 * 1000L
        assertTrue("本进程还没清过（lastSweepAt=0）时要清", SearchCacheManager.shouldSweep(1_000_000L, 0L, interval))
        assertFalse(SearchCacheManager.shouldSweep(1_000_000L, 1_000_000L, interval))
        assertFalse(SearchCacheManager.shouldSweep(1_000_000L + interval - 1, 1_000_000L, interval))
        assertTrue(SearchCacheManager.shouldSweep(1_000_000L + interval, 1_000_000L, interval))
    }

    /** 删除必须两层一起删：只删 L2 的话，下一次 `getStale` 会把 L1 里的旧值又直出回来。 */
    @Test
    fun deleteClearsBothLayers() = runBlocking {
        val dao = FakeDao()
        val memory = MemoryCache()
        val mgr = SearchCacheManager(dao, memory)
        mgr.put("k", PageCache.TYPE_NOTIFICATION, "OLD")
        assertTrue(mgr.getStale("k", PageCache.TYPE_NOTIFICATION) != null)

        mgr.delete("k")
        assertNull("L1 里不能残留", memory.getStale("k", PageCache.TYPE_NOTIFICATION))
        assertNull("L2 里也不能残留", mgr.getStale("k", PageCache.TYPE_NOTIFICATION))
    }

    /** 写入的 `expireAt` 必须由类型 TTL 决定（打错类型字符串会静默变成默认 30 分钟）。 */
    @Test
    fun putUsesTypeTtl() = runBlocking {
        val dao = FakeDao()
        val mgr = SearchCacheManager(dao, MemoryCache())
        val before = System.currentTimeMillis()
        mgr.put("k", PageCache.TYPE_NOTIFICATION, "JSON")
        val saved = dao.rows.getValue("k")
        assertEquals(PageCache.TYPE_NOTIFICATION, saved.type)
        val expected = before + SearchCacheManager.ttlFor(PageCache.TYPE_NOTIFICATION)
        assertTrue(
            "expireAt 与类型 TTL 不符：${saved.expireAt - before}",
            abs(saved.expireAt - expected) < 5_000,
        )
    }
}
