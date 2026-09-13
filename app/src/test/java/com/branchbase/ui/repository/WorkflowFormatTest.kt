package com.branchbase.ui.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工作流页面**展示层**纯逻辑单测：状态语义、时长、日志分级与命中。
 *
 * 这些函数决定了「哪里上色、哪一行算错误、搜索命中几条」，改错了编译不报、单测不红，
 * 只能靠真机肉眼 —— 所以逐条钉住。
 */
class WorkflowFormatTest {

    // ── 状态语义 ──

    @Test
    fun `取消与跳过不算失败`() {
        // 它们不该把任务排到「失败优先」的最前面，也不该把进度条染红
        assertTrue(isFailedConclusion("failure"))
        assertTrue(isFailedConclusion("timed_out"))
        assertTrue(isFailedConclusion("startup_failure"))
        assertFalse(isFailedConclusion("success"))
        assertFalse(isFailedConclusion("cancelled"))
        assertFalse(isFailedConclusion("skipped"))
        assertFalse(isFailedConclusion(null))   // 还没结论 = 还在跑
    }

    @Test
    fun `进度计数与总任务数`() {
        val jobs = listOf(
            RunJob(1, "build", "completed", "success"),
            RunJob(2, "test", "completed", "failure"),
            RunJob(3, "lint", "completed", "skipped"),
            RunJob(4, "docs", "in_progress", null),
            RunJob(5, "e2e", "queued", null),
        )
        val p = runProgress(jobs)
        assertEquals(1, p.ok)
        assertEquals(1, p.failed)
        assertEquals(1, p.running)
        assertEquals(2, p.waiting)      // skipped 归「等待」而不是失败
        assertEquals(5, p.total)
        assertTrue(p.worthShowing)      // 有失败 / 有没跑完的 → 头部值得显示进度

        // 全成功的小运行不必占一行放进度
        val allOk = runProgress(listOf(RunJob(1, "a", "completed", "success")))
        assertFalse(allOk.worthShowing)
    }

    // ── 时长与时间 ──

    @Test
    fun `运行中的已跑时长按起点算`() {
        val start = "2026-09-07T13:42:34Z"
        val startMs = parseInstantOrNull(start)!!.toEpochMilli()
        assertEquals(90_000L, elapsedSince(start, startMs + 90_000))
        // 起点在未来（时钟漂移）时不返回负数
        assertEquals(0L, elapsedSince(start, startMs - 5_000))
        assertNull(elapsedSince("坏时间", startMs))
        assertNull(elapsedSince("", startMs))
    }

    @Test
    fun `sha 与时间展示`() {
        assertEquals("a1b2c3d", shaShort("a1b2c3def4567890"))
        assertEquals("—", shaShort(""))
        assertEquals("2026-09-07 13:42", isoShort("2026-09-07T13:42:34Z"))
        assertEquals("—", isoShort(""))
    }

    // ── 日志分级 ──

    @Test
    fun `日志行分级只认比较确定的写法`() {
        assertEquals(LogLineLevel.ERROR, logLineLevel("##[error]Process completed with exit code 1."))
        assertEquals(LogLineLevel.ERROR, logLineLevel("java.lang.AssertionError: expected:<登录态>"))
        assertEquals(LogLineLevel.ERROR, logLineLevel("error: unresolved reference"))
        assertEquals(LogLineLevel.WARNING, logLineLevel("##[warning]Node.js 16 actions are deprecated"))
        assertEquals(LogLineLevel.WARNING, logLineLevel("warning: unused import"))
        assertEquals(LogLineLevel.NORMAL, logLineLevel("> Task :app:compileDebugKotlin"))
        assertEquals(LogLineLevel.NORMAL, logLineLevel("187 tests completed"))
    }

    // ── 搜索命中 ──

    @Test
    fun `命中行号按顺序且不区分大小写`() {
        val lines = listOf("Starting a Gradle Daemon", "> Task :app:preBuild", "> task :app:compile", "done")
        // 不区分大小写；第 0 行没有 task，所以命中 1、2
        assertEquals(listOf(1, 2), logHitIndexes(lines, "task"))
        assertEquals(listOf(1), logHitIndexes(lines, ":app:preBuild"))
        assertEquals(emptyList<Int>(), logHitIndexes(lines, "找不到"))
        // 空查询不算命中（否则整个日志都会高亮）
        assertEquals(emptyList<Int>(), logHitIndexes(lines, ""))
        assertEquals(emptyList<Int>(), logHitIndexes(lines, "   "))
    }
}
