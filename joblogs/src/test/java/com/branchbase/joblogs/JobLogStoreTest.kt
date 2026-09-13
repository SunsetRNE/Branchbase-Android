package com.branchbase.joblogs

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 取日志的并发契约单测。
 *
 * 这里钉的是「以前没有的东西」：同一 jobId 的并发调用必须合并成一次下载、
 * 失败不污染缓存（可重试）、取消不残留单飞记录、内存按字符数淘汰。
 * 都在真协程上跑（不引协程测试库，用 [runBlocking] + 真实时间余量）。
 */
class JobLogStoreTest {

    /** 内存实现，语义与 :app 的 PageCache 适配层一致（先直出 / 回源）。 */
    private class FakeCache : JobLogCache {
        val map = mutableMapOf<String, String>()
        val keys = mutableListOf<String>()

        override suspend fun cached(key: String, force: Boolean): String? {
            keys += key
            return if (force) null else map[key]
        }

        override suspend fun refresh(key: String, force: Boolean, fetch: suspend () -> String?): String? {
            keys += key
            if (!force) map[key]?.let { return it }
            val text = fetch() ?: return null
            map[key] = text
            return text
        }
    }

    // ── 成品形状 ──

    @Test
    fun `成品同时带原文与分段`() {
        val store = JobLogStore(JobLogSource { "##[group]Run tests\nFAIL\n##[endgroup]" })
        val log = runBlocking { store.load(1L) }!!
        assertEquals(1L, log.jobId)
        assertTrue(log.text.startsWith("##[group]"))
        assertEquals("Run tests", log.segments.single().title)
        assertSame(log, store.peek(1L))
    }

    @Test
    fun `jobId 原样透传给来源`() {
        val seen = mutableListOf<Long>()
        val store = JobLogStore(JobLogSource { id -> seen += id; "log-$id" })
        runBlocking { store.load(42L) }
        assertEquals(listOf(42L), seen)
    }

    // ── 并发：同一 job 只发一次 ──

    @Test
    fun `同一 job 的并发请求合并成一次下载`() {
        val calls = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        val store = JobLogStore(
            JobLogSource { id ->
                calls.incrementAndGet()
                gate.await()
                "log-$id"
            },
        )
        runBlocking {
            val first = async(Dispatchers.Default) { store.refresh(1L) }
            // 等到发起方真的进了来源
            while (calls.get() == 0) delay(1)
            // 再并发 7 个：都必须挂到同一份在飞请求上，而不是各自再下一次
            val others = (1..7).map { async(Dispatchers.Default) { store.refresh(1L) } }
            delay(200)
            gate.complete(Unit)
            assertEquals("log-1", first.await()!!.text)
            others.forEach { assertEquals("log-1", it.await()!!.text) }
        }
        assertEquals(1, calls.get())
    }

    @Test
    fun `发起方被取消后 单飞记录会清理并可重试`() {
        val calls = AtomicInteger(0)
        val gate = CompletableDeferred<String?>()
        val store = JobLogStore(
            JobLogSource {
                calls.incrementAndGet()
                gate.await()
            },
        )
        runBlocking {
            val first = launch(Dispatchers.Default) { store.refresh(7L) }
            while (calls.get() == 0) delay(1)
            first.cancelAndJoin()
            // 单飞记录若没被清掉，这里会拿到「永久 null」而不是真的再取一次
            gate.complete("after-cancel")
            assertEquals("after-cancel", store.refresh(7L)!!.text)
        }
        assertEquals(2, calls.get())
    }

    // ── 失败语义 ──

    @Test
    fun `取不到时返回 null 且下次会重试`() {
        val calls = AtomicInteger(0)
        var ok = false
        val store = JobLogStore(
            JobLogSource {
                calls.incrementAndGet()
                if (ok) "log" else null
            },
        )
        assertNull(runBlocking { store.refresh(1L) })
        // 失败不进内存，也就不会变成「永久失败」
        assertNull(store.peek(1L))
        ok = true
        assertEquals("log", runBlocking { store.refresh(1L) }!!.text)
        assertEquals(2, calls.get())
    }

    @Test
    fun `空白日志不算成品`() {
        val store = JobLogStore(JobLogSource { "   \n \n" })
        assertNull(runBlocking { store.refresh(1L) })
        assertNull(store.peek(1L))
    }

    // ── 内存淘汰 ──

    @Test
    fun `内存按字符数淘汰最久未用的那一份`() {
        val store = JobLogStore(JobLogSource { "x".repeat(600) }, memoryLimitChars = 1000)
        runBlocking {
            store.refresh(1L)
            store.refresh(2L)
        }
        assertNull(store.peek(1L))
        assertNotNull(store.peek(2L))
    }

    // ── 缓存接线 ──

    @Test
    fun `接上缓存后 换个 store 也能直出`() {
        val cache = FakeCache()
        val calls = AtomicInteger(0)
        val keyOf: (Long) -> String = { "detail:job-log:o/r#$it" }

        val online = JobLogStore(
            source = JobLogSource { calls.incrementAndGet(); "log-1" },
            cache = cache,
            keyOf = keyOf,
        )
        runBlocking { assertEquals("log-1", online.refresh(1L)!!.text) }
        assertEquals(1, calls.get())
        // 模块不拼仓库路径：键必须原样用 :app 给的
        assertEquals(listOf("detail:job-log:o/r#1"), cache.keys)

        // 等价于重新进页面：来源已经取不到，但缓存仍能直出
        val offline = JobLogStore(
            source = JobLogSource { calls.incrementAndGet(); null },
            cache = cache,
            keyOf = keyOf,
        )
        runBlocking { assertEquals("log-1", offline.load(1L)!!.text) }
        assertEquals(1, calls.get())

        // force（手动刷新）必须真的回源
        runBlocking { online.refresh(1L, force = true) }
        assertEquals(2, calls.get())
    }

    @Test
    fun `force 时不让缓存直出`() {
        val cache = FakeCache()
        cache.map["joblog:1"] = "old"
        val calls = AtomicInteger(0)
        val store = JobLogStore(JobLogSource { calls.incrementAndGet(); "new" }, cache = cache)

        // 非 force：磁盘缓存直出，完全不联网
        assertEquals("old", runBlocking { store.cached(1L) }!!.text)
        assertEquals(0, calls.get())

        // 另一个 store（内存还是空的）：force 必须放弃陈旧缓存，交给回源
        val fresh = JobLogStore(JobLogSource { calls.incrementAndGet(); "new" }, cache = cache)
        assertNull(runBlocking { fresh.cached(1L, force = true) })
        assertEquals("new", runBlocking { fresh.refresh(1L, force = true) }!!.text)
        assertEquals(1, calls.get())
    }
}
