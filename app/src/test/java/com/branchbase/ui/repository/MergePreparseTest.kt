package com.branchbase.ui.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预解析缓存的钉子（D-h 的契约：**弹窗出现那一刻**触发、结果落**进程内缓存**、
 * 页面只读它）。
 *
 * 两条不许的做法各对应一条用例：
 * - 「页面打开后再从头算」→ `已经有结果时不重复启动`（第二次 start 不该再取数）；
 * - 「把预解析做成页面状态」→ `返回再进仍是上次那份`（新开一个"页面"读同一个 key，拿到的还是 Ready）。
 *
 * 取数用注入的假函数（不碰引擎、不碰 Compose）：这一族的价值全在状态机上，
 * 真去读一个仓库只会让用例变慢、还依赖 libgit2 能不能跑。
 *
 * ## 为什么这个文件里的用例名是**英文**的（与仓库其它单测相反）
 *
 * `cache.start(dir, scope) { … }` 里的那个 `{ … }` 是 **suspend lambda**，Kotlin 会把它编译成
 * 一个匿名类，**类名里带外层函数名**（`MergePreparseTest$<方法名>$1.class`）。
 * 而构建环境的文件名编码是 POSIX（`LC_CTYPE=POSIX`），中文类名写不出来 —— 编译器直接报
 * `Malformed input or input contains unmappable characters`。
 * 别的用例名可以写中文，是因为普通 lambda 走 invokedynamic / `$lambda$N`，不产生这种文件。
 */
class MergePreparseTest {

    private val dir = "/tmp/bb-merge-preparse-test"

    /** 立刻执行的 scope：用例里不引入调度器，状态迁移就是同步可见的。 */
    private fun immediate() = CoroutineScope(Dispatchers.Unconfined)

    private val readyJson = """
        {"files":[{"path":"a.txt","kind":"both_modified","patch":"-x\n+y\n"}],"truncated":false}
    """.trimIndent()

    @Test
    fun `startGoesLoadingThenReady`() {
        val cache = MergePreparseCache()
        assertEquals(MergePreparseState.Idle, cache.stateOf(dir))
        // 取数挂住：能看到中间态（真实场景里这一小段就是「弹窗已出现、详情页还没打开」）
        var release: (() -> Unit)? = null
        cache.start(dir, immediate()) {
            suspendCancellableCoroutine<String?> { cont ->
                release = { cont.resumeWith(Result.success(readyJson)) }
            }
        }
        assertEquals(MergePreparseState.Loading, cache.stateOf(dir))
        release!!.invoke()
        val ready = cache.stateOf(dir)
        assertTrue("取数回来之后应是 Ready：$ready", ready is MergePreparseState.Ready)
        assertEquals("a.txt", (ready as MergePreparseState.Ready).analysis.files[0].path)
    }

    @Test
    fun `startTwiceFetchesOnce`() {
        val cache = MergePreparseCache()
        var calls = 0
        cache.start(dir, immediate()) { calls++; readyJson }
        assertEquals(1, calls)
        // 第二次 start（详情页打开、重组、返回再进）不该再取一次数
        cache.start(dir, immediate()) { calls++; readyJson }
        assertEquals(1, calls)
        // 但页面读到的仍是那一份
        assertTrue(cache.stateOf(dir) is MergePreparseState.Ready)
    }

    @Test
    fun `stateSurvivesPageReentry`() {
        val cache = MergePreparseCache()
        cache.start(dir, immediate()) { readyJson }
        // 模拟「离开页面再回来」：新的一次读取（页面被销毁重建，但缓存还在）
        val again = cache.stateOf(dir)
        assertTrue(again is MergePreparseState.Ready)
        assertEquals(1, (again as MergePreparseState.Ready).analysis.files.size)
    }

    @Test
    fun `failedAllowsRetry`() {
        val cache = MergePreparseCache()
        var calls = 0
        cache.start(dir, immediate()) { calls++; null }
        assertEquals(MergePreparseState.Failed, cache.stateOf(dir))
        // 失败态下再 start（没有 force）＝ 重试一次，这是允许的
        cache.start(dir, immediate()) { calls++; readyJson }
        assertEquals(2, calls)
        assertTrue(cache.stateOf(dir) is MergePreparseState.Ready)
        // force 重试：即使已经有结果也重新取（详情页那枚「重新预解析」）
        cache.start(dir, immediate(), force = true) { calls++; null }
        assertEquals(3, calls)
        assertEquals(MergePreparseState.Failed, cache.stateOf(dir))
    }

    @Test
    fun `clearResetsToIdle`() {
        val cache = MergePreparseCache()
        cache.start(dir, immediate()) { readyJson }
        assertTrue(cache.stateOf(dir) is MergePreparseState.Ready)
        cache.clear(dir)
        assertEquals(MergePreparseState.Idle, cache.stateOf(dir))
        // 空目录是空操作（页面在仓库还没就绪时也会读一次）
        cache.clear("")
        assertEquals(MergePreparseState.Idle, cache.stateOf(""))
        cache.start("", immediate()) { readyJson }
        assertEquals(MergePreparseState.Idle, cache.stateOf(""))
    }
}
