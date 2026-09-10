package com.branchbase.ui.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工作流详情/手动触发的纯逻辑单测。
 *
 * 数据形状全部按 GitHub 真实响应取证（`/actions/runs/{id}`、`/jobs`、`/artifacts`、
 * check-runs annotations，以及 Rust 侧解析 YAML 后返回的 `{enabled,inputs[]}`）。
 */
class WorkflowModelsTest {

    // ── run 详情 ──

    private val runJson = """
        {"id":34128896693,"name":"CI","display_title":"修复登录","event":"workflow_dispatch",
         "status":"completed","conclusion":"success","run_number":211,"run_attempt":2,
         "head_branch":"main","head_sha":"abc1234def","created_at":"2026-09-07T13:42:30Z",
         "run_started_at":"2026-09-07T13:42:34Z","updated_at":"2026-09-07T13:44:35Z",
         "html_url":"https://github.com/o/r/actions/runs/34128896693",
         "path":".github/workflows/ci.yml","workflow_id":3160,
         "actor":{"login":"SunsetRNE"},"triggering_actor":{"login":"Janno"}}
    """.trimIndent()

    @Test
    fun `解析单个 run 对象`() {
        val run = parseWorkflowRun(runJson)!!
        assertEquals(34128896693L, run.id)
        assertEquals("CI", run.name)
        assertEquals("修复登录", run.displayTitle)
        assertEquals("workflow_dispatch", run.event)
        assertEquals(211L, run.runNumber)
        assertEquals(2, run.runAttempt)
        assertEquals("main", run.headBranch)
        assertEquals("SunsetRNE", run.actor)
        assertEquals(".github/workflows/ci.yml", run.path)
        assertEquals(3160L, run.workflowId)
        assertNull(parseWorkflowRun(null))
        assertNull(parseWorkflowRun("ERROR: 404"))
        assertNull(parseWorkflowRun("{ 不是 JSON"))
    }

    @Test
    fun `解析 jobs 与嵌套 steps`() {
        val json = """
            {"total_count":1,"jobs":[{"id":1018151951,"name":"build","status":"completed",
             "conclusion":"success","started_at":"2026-09-07T13:42:34Z","completed_at":"2026-09-07T13:44:35Z",
             "runner_name":"GitHub Actions 42","html_url":"https://github.com/o/r/actions/runs/1/job/2",
             "steps":[{"number":1,"name":"Set up job","status":"completed","conclusion":"success",
                       "started_at":"2026-09-07T13:42:35Z","completed_at":"2026-09-07T13:42:36Z"},
                      {"number":2,"name":"Run tests","status":"completed","conclusion":"failure",
                       "started_at":"2026-09-07T13:42:36Z","completed_at":"2026-09-07T13:44:33Z"}]}]}
        """.trimIndent()
        val jobs = parseRunJobs(json)
        assertEquals(1, jobs.size)
        val job = jobs[0]
        assertEquals("build", job.name)
        assertEquals("GitHub Actions 42", job.runnerName)
        assertEquals(2, job.steps.size)
        assertEquals("Run tests", job.steps[1].name)
        assertEquals("failure", job.steps[1].conclusion)
        // 13:42:34 → 13:44:35 = 121s
        assertEquals(121000L, durationMillis(job.startedAt, job.completedAt))
    }

    // ── 产物 / 注解 ──

    @Test
    fun `解析产物并格式化大小`() {
        val json = """
            {"total_count":2,"artifacts":[
              {"id":1,"name":"app-debug.apk","size_in_bytes":5242880,"expired":false,"created_at":"2026-09-07T13:44:00Z"},
              {"id":2,"name":"logs","size_in_bytes":2048,"expired":true,"created_at":"2026-09-07T13:44:00Z"}]}
        """.trimIndent()
        val arts = parseWorkflowArtifacts(json)
        assertEquals(2, arts.size)
        assertEquals("5.0 MB", arts[0].sizeText)
        assertEquals("2 KB", arts[1].sizeText)
        assertTrue(arts[1].expired)
    }

    @Test
    fun `只挑出带注解的 check-run`() {
        val json = """
            {"total_count":3,"check_runs":[
              {"id":11,"output":{"annotations_count":2}},
              {"id":22,"output":{"annotations_count":0}},
              {"id":33,"output":{"annotations_count":1}}]}
        """.trimIndent()
        assertEquals(listOf(11L, 33L), annotationCheckRunIds(json))
    }

    @Test
    fun `解析注解`() {
        val json = """[{"path":"src/a.kt","start_line":3,"end_line":5,"annotation_level":"failure",
                       "message":"编译失败","title":"error"}]"""
        val list = parseWorkflowAnnotations(json)
        assertEquals(1, list.size)
        assertEquals("src/a.kt", list[0].path)
        assertEquals(3, list[0].startLine)
        assertEquals("failure", list[0].level)
    }

    // ── 手动触发输入 ──

    private val spec = parseDispatchSpec(
        """
        {"enabled":true,"inputs":[
          {"name":"version","description":"版本号","required":true,"default":"","type":"string","options":[]},
          {"name":"dry_run","description":"只检查","required":false,"default":"false","type":"boolean","options":[]},
          {"name":"target","description":"环境","required":false,"default":"staging","type":"choice","options":["staging","production"]},
          {"name":"retries","description":"重试","required":false,"default":"2","type":"number","options":[]}]}
        """.trimIndent(),
    )

    @Test
    fun `解析输入定义并识别类型`() {
        assertTrue(spec.enabled)
        assertEquals(4, spec.inputs.size)
        assertTrue(spec.inputs[0].required)
        assertTrue(spec.inputs[1].isBoolean)
        assertTrue(spec.inputs[2].isChoice)
        assertEquals(listOf("staging", "production"), spec.inputs[2].options)
        assertTrue(spec.inputs[3].isNumber)
    }

    @Test
    fun `非法或缺失的输入定义降级为未启用`() {
        assertFalse(parseDispatchSpec(null).enabled)
        assertFalse(parseDispatchSpec("ERROR: 引擎不可用").enabled)
        assertFalse(parseDispatchSpec("{ 不是 JSON").enabled)
        assertFalse(parseDispatchSpec("""{"enabled":false,"inputs":[]}""").enabled)
    }

    @Test
    fun `必填校验只挑空值`() {
        assertEquals(
            listOf("version"),
            missingRequiredInputs(spec, mapOf("version" to "  ", "target" to "production")),
        )
        assertTrue(missingRequiredInputs(spec, mapOf("version" to "1.0.13")).isEmpty())
    }

    @Test
    fun `按类型生成 inputs JSON`() {
        val json = buildInputsJson(
            spec,
            mapOf("version" to "1.0.13", "dry_run" to "true", "target" to "production", "retries" to "3"),
        )
        val o = org.json.JSONObject(json)
        assertEquals("1.0.13", o.getString("version"))
        // 布尔必须是真布尔，不能是字符串
        assertEquals(true, o.getBoolean("dry_run"))
        assertEquals("production", o.getString("target"))
        // 数字类型下发数字
        assertEquals(3, o.getInt("retries"))
    }

    @Test
    fun `非必填空值不下发 布尔默认值仍按类型处理`() {
        val json = buildInputsJson(spec, mapOf("version" to "1.0.13", "target" to "", "retries" to "abc"))
        val o = org.json.JSONObject(json)
        assertFalse(o.has("target"))
        // 非数字字符串退回字符串，避免整个请求 422
        assertEquals("abc", o.getString("retries"))
        // dry_run 未填 → 用默认值 "false" 并转成布尔
        assertEquals(false, o.getBoolean("dry_run"))
    }

    // ── 时长 / 状态 ──

    @Test
    fun `时长解析与格式化`() {
        assertEquals(2000L, durationMillis("2026-09-07T13:42:34Z", "2026-09-07T13:42:36Z"))
        assertEquals(121000L, durationMillis("2026-09-07T13:42:34Z", "2026-09-07T13:44:35Z"))
        assertNull(durationMillis(null, "2026-09-07T13:44:35Z"))
        assertNull(durationMillis("2026-09-07T13:44:35Z", "2026-09-07T13:42:34Z"))
        assertNull(durationMillis("坏时间", "2026-09-07T13:42:34Z"))

        assertEquals("2s", formatDuration(2000L))
        assertEquals("2m 01s", formatDuration(121000L))
        assertEquals("1h 02m", formatDuration(3720000L))
        assertEquals("0.4s", formatDuration(400L))
        assertEquals("—", formatDuration(null))
    }

    @Test
    fun `状态与事件中文标签`() {
        assertEquals("成功", runStatusLabel("completed", "success"))
        assertEquals("失败", runStatusLabel("completed", "failure"))
        assertEquals("进行中", runStatusLabel("in_progress", null))
        assertEquals("排队中", runStatusLabel("queued", null))
        assertEquals("启动失败", runStatusLabel("completed", "startup_failure"))
        assertEquals("手动触发", eventLabel("workflow_dispatch"))
        assertEquals("推送", eventLabel("push"))
        assertEquals("—", eventLabel(""))
    }

    // ── 日志分段 ──

    private val steps = listOf(
        JobStep(1, "Set up job", "completed", "success"),
        JobStep(2, "Run actions/checkout@v4", "completed", "success"),
        JobStep(3, "Run tests", "completed", "failure"),
    )

    @Test
    fun `按 group 切分日志并去掉时间戳`() {
        val log = """
            2026-09-07T13:42:35.1234567Z ##[group]Set up job
            2026-09-07T13:42:35.2345678Z Runner name: foo
            2026-09-07T13:42:36.0000000Z ##[endgroup]
            2026-09-07T13:42:36.1000000Z ##[group]Run actions/checkout@v4
            2026-09-07T13:42:37.0000000Z with: fetch-depth: 0
            2026-09-07T13:42:38.0000000Z ##[endgroup]
            2026-09-07T13:42:39.0000000Z ##[group]Run tests
            2026-09-07T13:42:40.0000000Z FAIL src/a.kt
            2026-09-07T13:42:41.0000000Z ##[endgroup]
        """.trimIndent()

        val segs = splitJobLogBySteps(log, steps)
        assertEquals(3, segs.size)
        assertEquals("Set up job", segs[0].title)
        assertEquals(listOf("Runner name: foo"), segs[0].lines)
        assertEquals("Run actions/checkout@v4", segs[1].title)
        assertEquals("FAIL src/a.kt", segs[2].lines.single())
        // 时间戳被剥掉
        assertTrue(segs.all { s -> s.lines.none { it.contains("2026-09-07T") } })
    }

    @Test
    fun `无 group 标记时整体一段 不丢内容`() {
        val log = "第一行\n第二行"
        val segs = splitJobLogBySteps(log, steps)
        assertEquals(1, segs.size)
        assertEquals("全部日志", segs[0].title)
        assertEquals(listOf("第一行", "第二行"), segs[0].lines)
        assertTrue(splitJobLogBySteps("", steps).isEmpty())
    }

    @Test
    fun `段落标题与步骤名匹配`() {
        assertTrue(segmentMatchesStep("Run tests", "Run tests"))
        assertTrue(segmentMatchesStep("run tests", "Run Tests"))
        assertTrue(segmentMatchesStep("Run actions/checkout@v4", "Run actions/checkout@v4"))
        // 步骤名是段落名的子串（GitHub 有时会加前缀）
        assertTrue(segmentMatchesStep("##[group] Run tests", "Run tests"))
        assertFalse(segmentMatchesStep("Set up job", "Run tests"))
        assertFalse(segmentMatchesStep("", "Run tests"))
    }

    @Test
    fun `为步骤找到对应日志段`() {
        val segs = listOf(
            LogSegment("Set up job", listOf("a")),
            LogSegment("Run tests", listOf("b")),
        )
        assertEquals("b", logSegmentForStep(segs, steps[2])!!.lines.single())
        assertNull(logSegmentForStep(segs, JobStep(9, "不存在", "completed", "success")))
    }
}
