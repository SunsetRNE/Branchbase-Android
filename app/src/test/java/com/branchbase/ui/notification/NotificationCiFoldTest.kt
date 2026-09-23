package com.branchbase.ui.notification

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CI 通知折叠（[collapseCiRuns]）的口径单测。
 *
 * 背景（真机复现）：主分支上连着 8 条 `Build workflow run failed for main branch` ——
 * 同仓库、同分支、同一天，标题逐字相同，逐条渲染就是一屏一模一样的行。口径照抄动态页的
 * `collapsePushes`（`ActivityFeedProfileTest` 钉了它 7 条边界），本文件钉同一组边界。
 *
 * 这里全部用**固定时刻**而不是 `now - 24h`：跑在午夜附近时「同一天」会随机失败。
 */
class NotificationCiFoldTest {

    /** 固定时刻（本地时区）：同一天内、差 1 小时。 */
    private fun at(dayOffset: Int, hour: Int, minute: Int = 0): Long {
        val c = Calendar.getInstance()
        c.set(2026, Calendar.MARCH, 10, hour, minute, 0)
        c.set(Calendar.MILLISECOND, 0)
        c.add(Calendar.DAY_OF_YEAR, dayOffset)
        return c.timeInMillis
    }

    private fun ci(
        id: String,
        workflow: String = "Build",
        branch: String = "main",
        repo: String = "o/r",
        reason: String = "ci_activity",
        type: String = "CheckSuite",
        ms: Long = at(0, 10),
        /** 标题里的状态词，用来造出「标题不同但仍是同一次运行」的场景 */
        status: String = "failed",
    ) = notificationOf(
        id = id,
        unread = true,
        reason = reason,
        subjectType = type,
        title = "$workflow workflow run $status for $branch branch",
        url = "https://api.github.com/repos/$repo/check-suites/$id",
        latestCommentUrl = null,
        repoFullName = repo,
        updatedAtMs = ms,
    )

    private fun issue(id: String, repo: String = "o/r", ms: Long = at(0, 9)) = notificationOf(
        id = id,
        unread = true,
        reason = "subscribed",
        subjectType = "Issue",
        title = "标题 $id",
        url = "https://api.github.com/repos/$repo/issues/1",
        latestCommentUrl = null,
        repoFullName = repo,
        updatedAtMs = ms,
    )

    // ───────────────── 基本折叠 ─────────────────

    @Test
    fun `同仓库同工作流同分支同一天的相邻 CI 通知折成一行`() {
        val list = listOf(ci("1", ms = at(0, 12)), ci("2", ms = at(0, 11)), ci("3", ms = at(0, 10)))
        val out = collapseCiRuns(list)

        assertEquals(1, out.size)
        // 代表行保留**最新一次**（输入倒序 ⇒ 组内首条），它是唯一有定位价值的东西
        assertEquals("1", out[0].id)
        assertTrue(out[0].isFolded)
        assertEquals(3, out[0].fold?.count)
        assertEquals(listOf("1", "2", "3"), out[0].fold?.ids)
        assertEquals(listOf("1", "2", "3"), out[0].allIds)
        assertEquals("Build 连续失败 · main", out[0].title)
    }

    @Test
    fun `折叠行保留每一次运行的原始标题与时间`() {
        val list = listOf(ci("1", ms = at(0, 12)), ci("2", ms = at(0, 11)))
        val fold = collapseCiRuns(list)[0].fold!!
        // 明细是「不丢信息」的凭据：展开后仍能逐条读到原始标题
        assertEquals("Build workflow run failed for main branch", fold.runs[0].title)
        assertEquals(at(0, 12), fold.runs[0].updatedAtMs)
        assertEquals(at(0, 11), fold.runs[1].updatedAtMs)
    }

    @Test
    fun `单条 CI 携带组信息但不呈现折叠`() {
        val out = collapseCiRuns(listOf(ci("1")))
        // fold 要留着（合并第二条时要用它的工作流名/分支/原始标题），但不该被当成折叠行 ——
        // 否则界面上会出现「展开其余 0 次」这种不成立的说法
        assertEquals(1, out[0].fold?.count)
        assertFalse(out[0].isFolded)
        assertEquals("Build · main", out[0].title)
        assertEquals(listOf("1"), out[0].allIds)
    }

    // ───────────────── 边界（与 collapsePushes 对齐） ─────────────────

    @Test
    fun `不跨天`() {
        // 今天 3 次 + 昨天 5 次合成 8 次是在编造事实
        val out = collapseCiRuns(listOf(ci("1", ms = at(0, 23)), ci("2", ms = at(-1, 22))))
        assertEquals(2, out.size)
        assertTrue(out.none { it.isFolded })
    }

    @Test
    fun `不跨分支`() {
        val out = collapseCiRuns(listOf(ci("1", branch = "main"), ci("2", branch = "release")))
        assertEquals(2, out.size)
        assertTrue(out.none { it.isFolded })
    }

    @Test
    fun `不跨工作流`() {
        val out = collapseCiRuns(listOf(ci("1", workflow = "Build"), ci("2", workflow = "Deploy")))
        assertEquals(2, out.size)
        assertTrue(out.none { it.isFolded })
    }

    @Test
    fun `不跨仓库`() {
        val out = collapseCiRuns(listOf(ci("1", repo = "o/r"), ci("2", repo = "o/other")))
        assertEquals(2, out.size)
        assertTrue(out.none { it.isFolded })
    }

    @Test
    fun `中间夹了别的通知就断开`() {
        // 只在**相邻**条目之间折叠：1 与 3 本该同组，但 2 夹在中间
        val out = collapseCiRuns(listOf(ci("1", ms = at(0, 12)), issue("2", ms = at(0, 11)), ci("3", ms = at(0, 10))))
        assertEquals(3, out.size)
        assertFalse(out[0].isFolded)
        assertFalse(out[1].isFolded)
        assertNull(out[1].fold)
        assertFalse(out[2].isFolded)
    }

    @Test
    fun `不同 run 类型不折`() {
        // CheckSuite 与 WorkflowRun 是两种通知，别把「看起来像」的并成一条
        val out = collapseCiRuns(listOf(ci("1", type = "CheckSuite"), ci("2", type = "WorkflowRun")))
        assertEquals(2, out.size)
        assertTrue(out.none { it.isFolded })
    }

    @Test
    fun `不是 ci_activity 的不折`() {
        val out = collapseCiRuns(listOf(ci("1", reason = "subscribed"), ci("2", reason = "subscribed")))
        assertEquals(2, out.size)
        // 标题长得再像工作流，reason 不是 ci_activity 就不是「CI 结果」，不参与折叠
        assertNull(out[0].fold)
        assertNull(out[1].fold)
    }

    @Test
    fun `标题解析不出来时原样保留不参与折叠`() {
        // 标题格式一旦变了，宁可多几行也不猜 —— 猜错会把两次不同的运行并成一条
        val odd = notificationOf(
            id = "x",
            unread = true,
            reason = "ci_activity",
            subjectType = "CheckSuite",
            title = "某种没见过的标题",
            url = "https://api.github.com/repos/o/r/check-suites/9",
            latestCommentUrl = null,
            repoFullName = "o/r",
            updatedAtMs = at(0, 10),
        )
        val out = collapseCiRuns(listOf(ci("1"), odd))
        assertEquals(2, out.size)
        assertEquals("某种没见过的标题", out[1].title)
        assertNull(out[1].fold)
    }

    @Test
    fun `时间未知的通知不合并`() {
        // updatedAtMs <= 0 = 解析失败。把一批「时间未知」并成一组会凭空造出「今天连续失败 N 次」
        val out = collapseCiRuns(listOf(ci("1", ms = 0L), ci("2", ms = 0L)))
        assertEquals(2, out.size)
        assertTrue(out.none { it.isFolded })
    }

    @Test
    fun `状态词不同不影响折叠`() {
        // cancelled / failed 都发生在「同一分支的同一天」，但这是**不同次**运行 ——
        // 折叠键里没有结论，所以它们仍会折在一起；这正是我们要的（一屏只留一行）
        val out = collapseCiRuns(listOf(ci("1", status = "failed"), ci("2", status = "cancelled")))
        assertEquals(1, out.size)
        assertTrue(out[0].isFolded)
        assertEquals(2, out[0].fold?.count)
    }

    // ───────────────── 不改动的东西 ─────────────────

    @Test
    fun `普通通知一个字段都不动`() {
        val original = issue("1")
        val out = collapseCiRuns(listOf(original))
        assertEquals(original, out[0])
        assertFalse(out[0].isFolded)
    }

    @Test
    fun `空列表安全`() {
        assertTrue(collapseCiRuns(emptyList()).isEmpty())
    }

    @Test
    fun `折叠后总条数不变`() {
        val list = listOf(ci("1"), ci("2"), ci("3"), issue("4"), ci("5", repo = "o/other"))
        val out = collapseCiRuns(list)
        assertEquals(list.size, out.sumOf { it.allIds.size })
    }

    @Test
    fun `allIds 在普通行上就是自己`() {
        val n = issue("7")
        assertNull(n.fold)
        assertEquals(listOf("7"), n.allIds)
    }
}
