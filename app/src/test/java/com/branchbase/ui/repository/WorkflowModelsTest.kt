package com.branchbase.ui.repository

import com.branchbase.joblogs.LogSegment
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
         "actor":{"login":"SunsetRNE"},"triggering_actor":{"login":"Janno"},
         "head_commit":{"id":"abc1234def","message":"修复登录时的空指针\n\n第二段不应进标题",
                        "author":{"name":"SunsetRNE","email":"a@b.c"}}}
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
        // commit message 只取首行：头部的提交行放不下多段
        assertEquals("修复登录时的空指针", run.headCommitMessage)
        assertEquals("SunsetRNE", run.headCommitAuthor)
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
              {"id":1,"name":"app-debug.apk","size_in_bytes":5242880,"expired":false,"created_at":"2026-09-07T13:44:00Z",
               "archive_download_url":"https://api.github.com/repos/o/r/actions/artifacts/1/zip"},
              {"id":2,"name":"logs","size_in_bytes":2048,"expired":true,"created_at":"2026-09-07T13:44:00Z"}]}
        """.trimIndent()
        val arts = parseWorkflowArtifacts(json)
        assertEquals(2, arts.size)
        assertEquals("5.0 MB", arts[0].sizeText)
        assertEquals("2 KB", arts[1].sizeText)
        assertTrue(arts[1].expired)
        // 下载地址是产物行「下载」按钮的唯一依据（以前没解析 → 产物只有名字没有入口）
        assertEquals("https://api.github.com/repos/o/r/actions/artifacts/1/zip", arts[0].archiveDownloadUrl)
        assertEquals("", arts[1].archiveDownloadUrl)
    }

    @Test
    fun `只挑出带注解的 check-run 并带上名字`() {
        val json = """
            {"total_count":3,"check_runs":[
              {"id":11,"name":"build","output":{"annotations_count":2}},
              {"id":22,"name":"lint","output":{"annotations_count":0}},
              {"id":33,"name":"test","output":{"annotations_count":1}}]}
        """.trimIndent()
        assertEquals(listOf(11L, 33L), annotationCheckRunIds(json))
        // 名字是「注解归回任务卡片」的依据，必须一起取出来
        assertEquals(
            listOf(AnnotationCheckRun(11L, "build"), AnnotationCheckRun(33L, "test")),
            annotationCheckRuns(json),
        )
        assertEquals(emptyList<AnnotationCheckRun>(), annotationCheckRuns("ERROR: 404"))
    }

    @Test
    fun `注解带上 check-run 名字并归回对应任务`() {
        val json = """[{"path":"src/a.kt","start_line":3,"end_line":5,"annotation_level":"failure",
                       "message":"编译失败","title":"error"}]"""
        val anno = parseWorkflowAnnotations(json, checkRunName = "test").single()
        assertEquals("test", anno.checkRunName)
        // 名字相同（或互相包含且都够长）才算这个任务报的 —— 宁可放不对，不要放错
        assertTrue(jobBelongsToAnnotation(anno, RunJob(1, "test", "completed", "failure")))
        assertTrue(jobBelongsToAnnotation(anno, RunJob(2, "Test (ubuntu-latest)", "completed", "failure")))
        assertFalse(jobBelongsToAnnotation(anno, RunJob(3, "build", "completed", "success")))
        // 名字为空（老缓存 / check-runs 里没给名字）时不归任何任务，交给「其他注解」聚合区
        assertFalse(jobBelongsToAnnotation(anno.copy(checkRunName = ""), RunJob(1, "test", "completed", "failure")))
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

    // 「按 `##[group]` 切段」的解析用例已随实现迁到 :joblogs 模块（JobLogParserTest）；
    // 这里只留需要 JobStep 这个 App 模型的「标题 ↔ 步骤名」匹配。

    private val steps = listOf(
        JobStep(1, "Set up job", "completed", "success"),
        JobStep(2, "Run actions/checkout@v4", "completed", "success"),
        JobStep(3, "Run tests", "completed", "failure"),
    )

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

    // ── 运行中的差分（决定「什么时候值得抓日志」） ──

    @Test
    fun `只挑出刚刚完成的 job`() {
        val current = listOf(
            RunJob(1, "build", "completed", "success"),
            RunJob(2, "test", "in_progress", null),
            RunJob(3, "lint", "completed", "success"),
        )
        // 首次快照：prev 为空 ⇒ 已完成的两个都算「刚完成」（页面首次直出后也要补齐日志）
        assertEquals(listOf(1L, 3L), newlyCompletedJobIds(emptyMap(), current))
        // 已经记录为 completed 的不会重复出现（差分而不是全量）⇒ 不会重复抓日志
        assertEquals(
            emptyList<Long>(),
            newlyCompletedJobIds(mapOf(1L to "completed", 2L to "in_progress", 3L to "completed"), current),
        )
        // 2 号刚跑完 ⇒ 只有它值得抓一次
        assertEquals(
            listOf(2L),
            newlyCompletedJobIds(mapOf(1L to "completed", 2L to "in_progress", 3L to "completed"), current.map {
                if (it.id == 2L) it.copy(status = "completed", conclusion = "success") else it
            }),
        )
    }

    @Test
    fun `任务列表为空时不产生任何差分`() {
        assertEquals(emptyList<Long>(), newlyCompletedJobIds(mapOf(1L to "in_progress"), emptyList()))
    }
}
