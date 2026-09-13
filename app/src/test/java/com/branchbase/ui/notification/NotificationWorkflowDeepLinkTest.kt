package com.branchbase.ui.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工作流通知 → 具体 run 的解析与配对单测（[parseCheckSuiteTitle] / [pickRunId] / [parseRunCandidates]）。
 *
 * 背景（审计出的缺陷）：工作流通知点进去只落到「工作流」tab，到不了这次 run 的详情页。
 * 根因是 GitHub **不在通知里给 run id** —— `subject.url` 对 CheckSuite 常常就是 null，
 * 给了也只是 `.../check-suites/<id>`（check 域编号，当 run id 用会打开一个编号巧合的无关 run，
 * 1.0.29 修过一次）。所以只能从标题里抠「工作流名 + 分支」再去 run 列表配对。
 *
 * 这里的每条断言都对应一类**误配**：宁可退回工作流列表，也不打开一个「看起来像」的无关 run。
 */
class NotificationWorkflowDeepLinkTest {

    private val t0 = 1_760_000_000_000L

    private fun run(
        id: Long,
        name: String = "CI",
        branch: String = "main",
        attempt: Int = 1,
        conclusion: String? = "failure",
        updatedAtMs: Long = t0,
    ) = RunCandidate(id, name, branch, attempt, conclusion, updatedAtMs)

    // ───────────────────────── 标题解析 ─────────────────────────

    @Test
    fun `解析基本标题`() {
        val h = parseCheckSuiteTitle("CI workflow run failed for main branch")
        assertEquals(CheckSuiteHint("CI", "main", null, "failure"), h)
    }

    @Test
    fun `解析带 attempt 与斜杠分支的标题`() {
        val h = parseCheckSuiteTitle("Deploy workflow run, Attempt #2 succeeded for release/1.0 branch")
        assertEquals("Deploy", h?.workflowName)
        assertEquals("release/1.0", h?.branch)
        assertEquals(2, h?.attemptNumber)
        assertEquals("success", h?.conclusion)
    }

    @Test
    fun `口语化状态映射到 API 的 conclusion`() {
        assertEquals("success", parseCheckSuiteTitle("A workflow run succeeded for main branch")?.conclusion)
        assertEquals("failure", parseCheckSuiteTitle("A workflow run failed for main branch")?.conclusion)
        assertEquals("failure", parseCheckSuiteTitle("A workflow run failed at startup for main branch")?.conclusion)
        assertEquals("cancelled", parseCheckSuiteTitle("A workflow run cancelled for main branch")?.conclusion)
        assertEquals("skipped", parseCheckSuiteTitle("A workflow run skipped for main branch")?.conclusion)
    }

    @Test
    fun `认不出的状态不拿它筛选但标题仍然可用`() {
        // 状态未知时 conclusion 为 null，配对时这一项不参与筛选（交给名字 + 分支 + 时间）
        val h = parseCheckSuiteTitle("CI workflow run in progress for main branch")
        assertEquals("CI", h?.workflowName)
        assertNull(h?.conclusion)
    }

    @Test
    fun `工作流名可以带空格`() {
        assertEquals("Build and Test", parseCheckSuiteTitle("Build and Test workflow run failed for main branch")?.workflowName)
    }

    @Test
    fun `不是工作流通知的标题返回 null`() {
        assertNull(parseCheckSuiteTitle("octocat commented on issue #12"))
        assertNull(parseCheckSuiteTitle("feat: 消息卡片重绘"))
        assertNull(parseCheckSuiteTitle(""))
    }

    // ───────────────────────── run 配对 ─────────────────────────

    @Test
    fun `名字与分支都对上就命中`() {
        val hint = parseCheckSuiteTitle("CI workflow run failed for main branch")!!
        assertEquals(7L, pickRunId(listOf(run(7)), hint, t0))
    }

    @Test
    fun `工作流名对不上不命中`() {
        val hint = parseCheckSuiteTitle("CI workflow run failed for main branch")!!
        assertNull(pickRunId(listOf(run(7, name = "Release")), hint, t0))
    }

    @Test
    fun `分支对不上不命中`() {
        val hint = parseCheckSuiteTitle("CI workflow run failed for main branch")!!
        assertNull(pickRunId(listOf(run(7, branch = "dev")), hint, t0))
    }

    @Test
    fun `标题给了 attempt 就必须相等`() {
        val hint = parseCheckSuiteTitle("CI workflow run, Attempt #2 failed for main branch")!!
        assertNull(pickRunId(listOf(run(7, attempt = 1)), hint, t0))
        assertEquals(8L, pickRunId(listOf(run(7, attempt = 1), run(8, attempt = 2)), hint, t0))
    }

    @Test
    fun `结论对不上不命中`() {
        val hint = parseCheckSuiteTitle("CI workflow run failed for main branch")!!
        assertNull(pickRunId(listOf(run(7, conclusion = "success")), hint, t0))
    }

    @Test
    fun `结论未知时不拿结论筛选`() {
        val hint = parseCheckSuiteTitle("CI workflow run in progress for main branch")!!
        assertEquals(7L, pickRunId(listOf(run(7, conclusion = "success")), hint, t0))
    }

    @Test
    fun `同一分支跑过多次时取时间最接近的那次`() {
        val hint = parseCheckSuiteTitle("CI workflow run failed for main branch")!!
        val runs = listOf(
            run(1, updatedAtMs = t0 - 26 * 60 * 60 * 1000L), // 一天多以前
            run(2, updatedAtMs = t0 - 90_000L),             // 1.5 分钟前 ← 就是它
            run(3, updatedAtMs = t0 - 3600_000L),
        )
        assertEquals(2L, pickRunId(runs, hint, t0))
    }

    @Test
    fun `最好的候选偏差过大时宁可退回列表`() {
        val hint = parseCheckSuiteTitle("CI workflow run failed for main branch")!!
        // 超过 RUN_MATCH_MAX_GAP_MS（24h）：名字与分支都对得上，但显然不是这一次
        assertNull(pickRunId(listOf(run(7, updatedAtMs = t0 - 48 * 60 * 60 * 1000L)), hint, t0))
    }

    @Test
    fun `通知时间未知时不拿时间卡人`() {
        val hint = parseCheckSuiteTitle("CI workflow run failed for main branch")!!
        // updatedAtMs == 0：只凭名字 + 分支的硬筛结果
        assertEquals(7L, pickRunId(listOf(run(7, updatedAtMs = t0 - 48 * 60 * 60 * 1000L)), hint, 0L))
    }

    @Test
    fun `候选为空返回 null`() {
        val hint = parseCheckSuiteTitle("CI workflow run failed for main branch")!!
        assertNull(pickRunId(emptyList(), hint, t0))
    }

    // ───────────────────────── run 列表解析 ─────────────────────────

    @Test
    fun `解析 actions runs 响应`() {
        val json = """
            {"total_count":2,"workflow_runs":[
              {"id":11,"name":"CI","head_branch":"main","run_attempt":2,"conclusion":"failure",
               "updated_at":"2026-09-13T07:27:31Z"},
              {"id":12,"name":"Release","head_branch":"main","conclusion":null,
               "updated_at":"2026-09-13T07:27:31Z"}
            ]}
        """.trimIndent()
        val runs = parseRunCandidates(json)
        assertEquals(2, runs.size)
        assertEquals(11L, runs[0].id)
        assertEquals("CI", runs[0].name)
        assertEquals("main", runs[0].branch)
        assertEquals(2, runs[0].attempt)
        assertEquals("failure", runs[0].conclusion)
        assertTrue(runs[0].updatedAtMs > 0)
        // conclusion 为 null（还在跑）时保持 null，不塞空串
        assertNull(runs[1].conclusion)
        // run_attempt 缺失时按 1 处理
        assertEquals(1, runs[1].attempt)
    }

    @Test
    fun `响应缺字段或不是 JSON 时安全返回空`() {
        assertTrue(parseRunCandidates("{}").isEmpty())
        assertTrue(parseRunCandidates("not json at all").isEmpty())
        assertTrue(parseRunCandidates("").isEmpty())
    }
}
