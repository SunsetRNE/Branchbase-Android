package com.branchbase.joblogs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 日志来源：由 `:app` 注入。
 *
 * 契约只有一条：**返回 null = 取不到**（网络失败、404、权限不足都由注入方折叠成 null，
 * 模块不解析任何错误字符串，也不认识 HTTP 状态码）。
 */
fun interface JobLogSource {
    suspend fun fetch(jobId: Long): String?
}

/**
 * 作业日志的取数入口：**同一个 jobId 全局只发一次请求**。
 *
 * 三层，按开销从低到高：
 * 1. 内存（[peek] / 内部 LRU）：原文 + 已切好的分段，命中即返回，不联网也不重切；
 * 2. 注入的 [JobLogCache]（`:app` 接 `PageCache`）：跨页面 / 冷启动后仍能直出；
 * 3. [source]：真正的一次下载。
 *
 * 并发语义（等价于「同一把 key 上的单飞请求」）：
 * - 同一 jobId 的多个并发调用**合并成一次** [JobLogSource.fetch]，其余等待同一结果；
 * - 取不到时返回 null 且**不写缓存**，等待者同样拿到 null，下次调用会重新发起（可重试）；
 * - 发起方被取消（用户切页）时，等待者立刻收到 null 而不是永久挂起，且单飞记录会被清掉，
 *   后续调用可以正常重试。
 *
 * 线程模型：本模块不创建线程，也不持有自己的作用域 —— 所有工作都跑在调用方的协程里；
 * 唯一的调度决策是把「逐行切段 + 正则去时间戳」下沉到 [Dispatchers.Default]
 * （日志可达数 MB，放主线程会肉眼可见地卡）。
 *
 * @param cache 可选；传 null 就是纯内存模式（单测与不需要落盘的场景）。
 * @param keyOf jobId → 缓存键；由 `:app` 提供，模块不拼仓库路径。
 * @param memoryLimitChars 内存里保留的日志原文总字符上限，超出按最久未用淘汰。
 */
class JobLogStore(
    private val source: JobLogSource,
    private val cache: JobLogCache? = null,
    private val keyOf: (Long) -> String = { "joblog:$it" },
    private val memoryLimitChars: Int = DEFAULT_MEMORY_LIMIT_CHARS,
) {

    companion object {
        /** 约 4M 字符（≈ 数 MB 日志的 3~4 份）。 */
        const val DEFAULT_MEMORY_LIMIT_CHARS: Int = 4_000_000
    }

    // accessOrder = true：命中即视为「最近使用」，淘汰的是最久没看过的那份
    private val memory = LinkedHashMap<Long, JobLog>(8, 0.75f, true)
    private var memoryChars = 0

    private val mutex = Mutex()
    private val inFlight = mutableMapOf<Long, CompletableDeferred<JobLog?>>()

    /** 内存里已有的成品（不联网、不切段）。供重组 / 返回时直接取用。 */
    fun peek(jobId: Long): JobLog? = synchronized(memory) { memory[jobId] }

    /** 便捷入口：内存 → 缓存直出 → 回源。 */
    suspend fun load(jobId: Long, force: Boolean = false): JobLog? =
        peek(jobId) ?: cached(jobId, force) ?: refresh(jobId, force)

    /** 只查缓存（含过期），命中时切段进内存。不联网。 */
    suspend fun cached(jobId: Long, force: Boolean = false): JobLog? {
        peek(jobId)?.let { return it }
        val store = cache ?: return null
        val text = store.cached(keyOf(jobId), force) ?: return null
        return remember(jobId, text)
    }

    /** 回源（同一 jobId 合并为一次请求）。取不到返回 null。 */
    suspend fun refresh(jobId: Long, force: Boolean = false): JobLog? {
        val (promise, isOwner) = mutex.withLock {
            val existing = inFlight[jobId]
            if (existing != null) {
                existing to false
            } else {
                CompletableDeferred<JobLog?>().also { inFlight[jobId] = it } to true
            }
        }
        if (!isOwner) return promise.await()

        return try {
            val text = if (cache != null) {
                cache.refresh(keyOf(jobId), force) { source.fetch(jobId) }
            } else {
                source.fetch(jobId)
            }
            val log = text?.takeIf { it.isNotBlank() }?.let { remember(jobId, it) }
            promise.complete(log)
            log
        } catch (t: Throwable) {
            // 失败与取消都要放行等待者，否则并发的第二方会一直挂着
            promise.complete(null)
            if (t is CancellationException) throw t
            null
        } finally {
            // 取消态下普通挂起会立刻抛 CancellationException，清理就永远不执行 ——
            // 单飞记录会残留成「永久失败」，所以清理必须走 NonCancellable
            withContext(NonCancellable) { mutex.withLock { inFlight.remove(jobId, promise) } }
        }
    }

    /** 切段（Default 调度器）并按字符数写进内存 LRU。 */
    private suspend fun remember(jobId: Long, text: String): JobLog {
        val segments = withContext(Dispatchers.Default) { splitJobLogBySteps(text) }
        val log = JobLog(jobId, text, segments)
        synchronized(memory) {
            memory.remove(jobId)?.let { memoryChars -= it.text.length }
            memory[jobId] = log
            memoryChars += text.length
            // 至少保留一份：否则「单份就超限」时会把刚放进去的自己淘汰掉
            val oldest = memory.entries.iterator()
            while (memoryChars > memoryLimitChars && memory.size > 1 && oldest.hasNext()) {
                val entry = oldest.next()
                oldest.remove()
                memoryChars -= entry.value.text.length
            }
        }
        return log
    }
}
