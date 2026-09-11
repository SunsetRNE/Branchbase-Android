package com.branchbase.ui.repository

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.branchbase.cache.PageCache
import com.branchbase.cache.SearchCacheDatabase
import com.branchbase.cache.SearchCacheManager
import com.branchbase.core.RustBridge
import com.branchbase.ui.theme.iconTap
import com.branchbase.ui.theme.Primer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 工作流运行详情（原生富渲染），对齐 GitHub 网页版的信息结构：
 *
 * run 头部（状态/标题/编号/事件/分支/提交/触发人/耗时/创建时间）
 * → jobs→steps 时间线（每步耗时，点击展开）
 * → 步骤日志（点击步骤懒加载、按 `##[group]` 归段）
 * → 注解（check-runs annotations）
 * → 产物（artifacts）。
 *
 * 解析与纯逻辑全部复用 [WorkflowModels.kt] / [RepositoryModels.kt]，本文件只负责取数与渲染。
 * 网络请求一律容错：`RustBridge.getJson` 返回 null 或以 `ERROR:` 开头时按失败处理，绝不抛出。
 */

private const val MAX_LOG_LINES = 200
private const val LOG_BOX_MAX_HEIGHT_DP = 320

private val LogBackground = Color(0xFFF6F8FA)
private val LogTextColor = Color(0xFF24292F)

@Composable
fun WorkflowRunDetailScreen(
    sessionJson: String,
    owner: String,
    repo: String,
    runId: Long,
    onBack: () -> Unit,
    onOpenJob: (Long) -> Unit,
) {
    val (host, token, _) = sessionInfo(sessionJson)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var run by remember { mutableStateOf<WorkflowRun?>(null) }
    var jobs by remember { mutableStateOf<List<RunJob>>(emptyList()) }
    var artifacts by remember { mutableStateOf<List<WorkflowArtifact>>(emptyList()) }
    var annotations by remember { mutableStateOf<List<WorkflowAnnotation>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var retryTick by remember { mutableStateOf(0) }

    // 展开态 / 日志缓存：都以 jobId 为键，跨重组保留，仅本页面生命周期有效
    val expandedJobs = remember { mutableStateMapOf<Long, Boolean>() }
    val selectedSteps = remember { mutableStateMapOf<Long, Long>() }
    val jobLogs = remember { mutableStateMapOf<Long, String>() }
    val jobSegments = remember { mutableStateMapOf<Long, List<LogSegment>>() }
    val logLoading = remember { mutableStateMapOf<Long, Boolean>() }
    val logFailed = remember { mutableStateMapOf<Long, Boolean>() }
    val logExpanded = remember { mutableStateMapOf<Long, Boolean>() }

    /** 懒加载某个 job 的完整日志：命中缓存或正在加载则直接返回；失败不写缓存，便于再次点击重试。 */
    fun loadJobLog(job: RunJob) {
        if (jobLogs.containsKey(job.id) || logLoading[job.id] == true) return
        logLoading[job.id] = true
        logFailed[job.id] = false
        scope.launch {
            val text = RustBridge.getJson(host, token, "/repos/$owner/$repo/actions/jobs/${job.id}/logs")
            if (text == null || text.startsWith("ERROR:")) {
                logFailed[job.id] = true
            } else {
                // 分段放到后台线程：日志可达数 MB，主线程逐行 split + 正则会造成可见卡顿
                val segments = withContext(Dispatchers.Default) { splitJobLogBySteps(text, job.steps) }
                jobLogs[job.id] = text
                jobSegments[job.id] = segments
            }
            logLoading[job.id] = false
        }
    }

    LaunchedEffect(owner, repo, runId, retryTick) {
        loading = true
        failed = false
        run = null
        jobs = emptyList()
        artifacts = emptyList()
        annotations = emptyList()
        expandedJobs.clear()
        selectedSteps.clear()
        jobLogs.clear()
        jobSegments.clear()
        logLoading.clear()
        logFailed.clear()
        logExpanded.clear()

        // 手动重试必须真的回源
        val force = retryTick > 0
        val manager = SearchCacheManager(SearchCacheDatabase.getInstance(context).searchCacheDao())
        val runKey = PageCache.runKey(owner, repo, runId)
        val jobsKey = PageCache.runJobsKey(owner, repo, runId)
        val artifactsKey = PageCache.runArtifactsKey(owner, repo, runId)

        // ① 先直出缓存（含过期）：run 头部与 jobs 是返回再进的高频内容
        PageCache.cachedFirst(manager, runKey, PageCache.TYPE_DETAIL, force)?.let { run = parseWorkflowRun(it) }
        PageCache.cachedFirst(manager, jobsKey, PageCache.TYPE_DETAIL, force)?.let { jobs = parseRunJobs(it) }
        PageCache.cachedFirst(manager, artifactsKey, PageCache.TYPE_DETAIL, force)?.let { artifacts = parseWorkflowArtifacts(it) }
        if (run != null || jobs.isNotEmpty()) loading = false

        coroutineScope {
            // ①②③ 并行：run 详情 / jobs（自带 steps）/ 产物（各自走 PageCache：命中即返回，无需联网）
            val runDeferred = async {
                PageCache.refresh(manager, runKey, PageCache.TYPE_DETAIL, force) {
                    RustBridge.getJson(host, token, "/repos/$owner/$repo/actions/runs/$runId")
                }
            }
            val jobsDeferred = async {
                PageCache.refresh(manager, jobsKey, PageCache.TYPE_DETAIL, force) {
                    RustBridge.getJson(host, token, "/repos/$owner/$repo/actions/runs/$runId/jobs")
                }
            }
            val artifactsDeferred = async {
                PageCache.refresh(manager, artifactsKey, PageCache.TYPE_DETAIL, force) {
                    RustBridge.getJson(host, token, "/repos/$owner/$repo/actions/runs/$runId/artifacts")
                }
            }

            val parsedRun = parseWorkflowRun(runDeferred.await()) ?: run
            val parsedJobs = parseRunJobs(jobsDeferred.await() ?: "").ifEmpty { jobs }
            val parsedArtifacts = parseWorkflowArtifacts(artifactsDeferred.await()).ifEmpty { artifacts }

            // ④ 注解依赖 run 的 headSha：先查 check-runs，再只为「有注解」的前 3 个 run 拉详情
            var parsedAnnotations = emptyList<WorkflowAnnotation>()
            val headSha = parsedRun?.headSha.orEmpty()
            if (headSha.isNotBlank()) {
                val checkRunIds = annotationCheckRunIds(
                    RustBridge.getJson(host, token, "/repos/$owner/$repo/commits/$headSha/check-runs"),
                ).take(3)
                if (checkRunIds.isNotEmpty()) {
                    val deferred = checkRunIds.map { checkRunId ->
                        async {
                            RustBridge.getJson(host, token, "/repos/$owner/$repo/check-runs/$checkRunId/annotations")
                        }
                    }
                    parsedAnnotations = deferred.flatMap { parseWorkflowAnnotations(it.await()) }
                }
            }

            run = parsedRun
            jobs = parsedJobs
            artifacts = parsedArtifacts
            annotations = parsedAnnotations
            failed = parsedRun == null && parsedJobs.isEmpty()
        }
        loading = false
    }

    val title = run?.let { r ->
        val name = r.name.ifBlank { "Run" }
        "$name · #${r.runNumber}"
    } ?: "Run #$runId"

    Column(
        Modifier.fillMaxSize().background(Primer.BackgroundPrimary).statusBarsPadding().navigationBarsPadding(),
    ) {
        DetailTopBar(title = title, onBack = onBack)

        when {
            loading -> DetailLoading()

            failed -> DetailErrorRetry { retryTick++ }

            else -> LazyColumn(Modifier.fillMaxSize()) {
                run?.let { r -> item { RunHeaderCard(r) } }

                item { DetailSectionTitle("任务 Jobs") }
                if (jobs.isEmpty()) {
                    item { DetailEmptyText("暂无任务") }
                } else {
                    items(jobs, key = { it.id }) { job ->
                        val expanded = expandedJobs[job.id] == true
                        val selectedStepNumber = selectedSteps[job.id]

                        Column(Modifier.fillMaxWidth()) {
                            JobRow(
                                job = job,
                                expanded = expanded,
                                onToggle = { expandedJobs[job.id] = !expanded },
                                onOpenFullLog = { onOpenJob(job.id) },
                            )

                            if (expanded) {
                                if (job.steps.isEmpty()) {
                                    DetailEmptyText("该任务没有步骤")
                                } else {
                                    job.steps.forEach { step ->
                                        StepRow(
                                            step = step,
                                            selected = selectedStepNumber == step.number,
                                            onClick = {
                                                selectedSteps[job.id] = step.number
                                                loadJobLog(job)
                                            },
                                        )
                                    }
                                }

                                val currentStep = job.steps.firstOrNull { it.number == selectedStepNumber }
                                if (currentStep != null) {
                                    val segment = logSegmentForStep(jobSegments[job.id] ?: emptyList(), currentStep)
                                    StepLogBlock(
                                        step = currentStep,
                                        lines = segment?.lines ?: emptyList(),
                                        loaded = jobLogs.containsKey(job.id),
                                        loading = logLoading[job.id] == true,
                                        failed = logFailed[job.id] == true,
                                        expanded = logExpanded[job.id] == true,
                                        onToggleExpand = { logExpanded[job.id] = logExpanded[job.id] != true },
                                        onRetry = { loadJobLog(job) },
                                    )
                                }
                            }
                        }
                    }
                }

                if (artifacts.isNotEmpty()) {
                    item { DetailSectionTitle("产物 Artifacts") }
                    items(artifacts) { artifact -> ArtifactRow(artifact) }
                }

                if (annotations.isNotEmpty()) {
                    item { DetailSectionTitle("注解 Annotations") }
                    items(annotations) { annotation -> AnnotationRow(annotation) }
                }

                item { Spacer(Modifier.height(28.dp)) }
            }
        }
    }
}

// ── 顶部栏 ──

@Composable
private fun DetailTopBar(title: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = Primer.IconPrimary,
                modifier = Modifier.size(24.dp).iconTap { onBack() },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Primer.TextPrimary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Primer.Gray150))
    }
}

// ── run 头部卡片 ──

@Composable
private fun RunHeaderCard(run: WorkflowRun) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, Primer.Border, RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(runDotColor(run.status, run.conclusion)))
            Spacer(Modifier.width(8.dp))
            Text(
                runStatusLabel(run.status, run.conclusion),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = runDotColor(run.status, run.conclusion),
            )
        }

        Spacer(Modifier.height(6.dp))
        Text(
            run.displayTitle.ifBlank { run.name },
            fontSize = 14.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = Primer.TextPrimary,
        )

        Spacer(Modifier.height(8.dp))
        val attemptText = if (run.runAttempt > 1) " · 第 ${run.runAttempt} 次尝试" else ""
        MetaLine("编号", "#${run.runNumber}$attemptText")
        MetaLine("事件", eventLabel(run.event))
        MetaLine("分支", run.headBranch.ifBlank { "—" })
        MetaLine("提交", shaShort(run.headSha), mono = true)
        MetaLine("触发人", run.actor.ifBlank { "—" })
        MetaLine("耗时", formatDuration(durationMillis(run.runStartedAt, run.updatedAt)))
        MetaLine("创建", isoShort(run.createdAt))
    }
}

@Composable
private fun MetaLine(label: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, fontSize = 11.5.sp, color = Primer.TextTertiary, modifier = Modifier.width(52.dp))
        Text(
            value,
            fontSize = 12.sp,
            color = Primer.TextPrimary,
            fontFamily = if (mono) FontFamily.Monospace else null,
            modifier = Modifier.weight(1f),
        )
    }
}

// ── jobs / steps 时间线 ──

@Composable
private fun JobRow(
    job: RunJob,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenFullLog: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { onToggle() }.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(runDotColor(job.status, job.conclusion)))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    job.name.ifBlank { "（未命名任务）" },
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Primer.TextPrimary,
                    maxLines = 2,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${runStatusLabel(job.status, job.conclusion)} · ${formatDuration(durationMillis(job.startedAt, job.completedAt))}",
                    fontSize = 11.5.sp,
                    color = Primer.TextTertiary,
                )
            }
            Text(
                "完整日志",
                fontSize = 11.5.sp,
                color = Primer.Blue500,
                modifier = Modifier
                    .clickable { onOpenFullLog() }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
            Text(if (expanded) "▾" else "▸", fontSize = 12.sp, color = Primer.TextTertiary)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Primer.Gray100))
    }
}

@Composable
private fun StepRow(step: JobStep, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (selected) Primer.Gray150 else Color.Transparent)
                .clickable { onClick() }
                .padding(start = 30.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(runDotColor(step.status, step.conclusion)))
            Spacer(Modifier.width(8.dp))
            Text(
                "${step.number}. ${step.name.ifBlank { "（未命名步骤）" }}",
                fontSize = 12.5.sp,
                color = Primer.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                runStatusLabel(step.status, step.conclusion),
                fontSize = 11.sp,
                color = Primer.TextTertiary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                formatDuration(durationMillis(step.startedAt, step.completedAt)),
                fontSize = 11.sp,
                color = Primer.TextTertiary,
            )
        }
        Box(Modifier.fillMaxWidth().padding(start = 30.dp).height(1.dp).background(Primer.Gray100))
    }
}

// ── 步骤日志 ──

@Composable
private fun StepLogBlock(
    step: JobStep,
    lines: List<String>,
    loaded: Boolean,
    loading: Boolean,
    failed: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(start = 30.dp, end = 12.dp, top = 4.dp, bottom = 12.dp)) {
        Text(
            "步骤日志 · ${step.name}",
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = Primer.TextSecondary,
        )
        Spacer(Modifier.height(6.dp))

        when {
            loading -> Text("正在加载日志…", fontSize = 11.5.sp, color = Primer.TextTertiary)

            failed -> Text(
                "日志加载失败，点击重试",
                fontSize = 11.5.sp,
                color = Primer.Red500,
                modifier = Modifier.clickable { onRetry() }.padding(vertical = 4.dp),
            )

            !loaded -> Text("点击步骤名加载日志", fontSize = 11.5.sp, color = Primer.TextTertiary)

            lines.isEmpty() -> Text("该步骤没有日志输出", fontSize = 11.5.sp, color = Primer.TextTertiary)

            else -> {
                val limit = if (expanded) lines.size else MAX_LOG_LINES
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = LOG_BOX_MAX_HEIGHT_DP.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(LogBackground)
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp),
                ) {
                    Text(
                        lines.take(limit).joinToString("\n"),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = LogTextColor,
                    )
                }
                if (lines.size > MAX_LOG_LINES) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (expanded) "收起" else "展开剩余 ${lines.size - MAX_LOG_LINES} 行",
                        fontSize = 11.5.sp,
                        color = Primer.Blue500,
                        modifier = Modifier.clickable { onToggleExpand() }.padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}

// ── 产物 / 注解 ──

@Composable
private fun ArtifactRow(artifact: WorkflowArtifact) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    artifact.name.ifBlank { "（未命名产物）" },
                    fontSize = 13.sp,
                    color = Primer.TextPrimary,
                    maxLines = 2,
                )
                Spacer(Modifier.height(2.dp))
                Text(artifact.sizeText, fontSize = 11.5.sp, color = Primer.TextTertiary)
            }
            if (artifact.expired) {
                Spacer(Modifier.width(8.dp))
                Text("已过期", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = Primer.Red500)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Primer.Gray100))
    }
}

@Composable
private fun AnnotationRow(annotation: WorkflowAnnotation) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.padding(top = 4.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(annotationLevelColor(annotation.level)))
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    annotationLocation(annotation),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Primer.TextSecondary,
                    maxLines = 2,
                )
                if (annotation.title.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        annotation.title,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Primer.TextPrimary,
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    annotation.message.ifBlank { "（无内容）" },
                    fontSize = 12.sp,
                    color = Primer.TextPrimary,
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Primer.Gray100))
    }
}

// ── 通用小件 ──

@Composable
private fun DetailSectionTitle(title: String) {
    Text(
        title,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.SemiBold,
        color = Primer.TextSecondary,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 6.dp),
    )
}

@Composable
private fun DetailLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Primer.Blue500)
    }
}

@Composable
private fun DetailErrorRetry(onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("加载失败", fontSize = 13.sp, color = Primer.TextSecondary)
            Spacer(Modifier.height(10.dp))
            Text(
                "重试",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Primer.Blue500,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, Primer.Blue500, RoundedCornerShape(6.dp))
                    .clickable { onRetry() }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun DetailEmptyText(text: String) {
    Text(
        text,
        fontSize = 12.5.sp,
        color = Primer.TextTertiary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
    )
}

// ── 纯函数小工具 ──

/** 状态点颜色：成功绿、失败红、取消/跳过灰、进行中橙。 */
private fun runDotColor(status: String, conclusion: String?): Color = when (conclusion) {
    "success" -> Primer.Green500
    "failure", "timed_out", "startup_failure" -> Primer.Red500
    "cancelled", "skipped", "stale" -> Primer.TextTertiary
    "action_required", "neutral" -> Primer.Orange500
    else -> when (status) {
        "queued", "in_progress", "requested", "waiting", "pending" -> Primer.Orange500
        else -> Primer.TextTertiary
    }
}

/** 注解等级色标：failure 红 / warning 橙 / 其它蓝。 */
private fun annotationLevelColor(level: String): Color = when (level.lowercase()) {
    "failure" -> Primer.Red500
    "warning" -> Primer.Orange500
    else -> Primer.Blue500
}

/** 注解位置 `path:startLine`（path 为空时退化为 `—`）。 */
private fun annotationLocation(annotation: WorkflowAnnotation): String = when {
    annotation.path.isBlank() -> "—"
    annotation.startLine > 0 -> "${annotation.path}:${annotation.startLine}"
    else -> annotation.path
}

/** 提交短 sha（前 7 位）。 */
private fun shaShort(sha: String): String = if (sha.isBlank()) "—" else sha.take(7)

/** 时间展示：截取前 16 字符并把 `T` 换成空格（`2026-09-07T13:42:34Z` → `2026-09-07 13:42`）。 */
private fun isoShort(iso: String): String = if (iso.isBlank()) "—" else iso.take(16).replace('T', ' ')
