package com.branchbase.ui.repository

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.branchbase.cache.PageCache
import com.branchbase.cache.SearchCacheDatabase
import com.branchbase.cache.SearchCacheManager
import com.branchbase.cache.networkMetered
import com.branchbase.core.RustBridge
import com.branchbase.downloader.DownloadRequest
import com.branchbase.downloader.DownloadStatus
import com.branchbase.downloader.DownloadTask
import com.branchbase.downloader.DownloaderRuntime
import com.branchbase.joblogs.JobLog
import com.branchbase.joblogs.JobLogStore
import com.branchbase.ui.theme.Primer
import com.branchbase.ui.theme.iconTap
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 工作流**运行详情**：状态与日志的持续获取 + 卡片流重绘。
 *
 * ## 结构（重绘后）
 *
 * ```
 * 顶栏（刷新 / 更多：浏览器打开 · 重新运行 · 复制链接 · 下载全部日志）
 * RunHeaderCard    三段式：状态行 / 提交行 / 次要行 + 进度条
 * 任务 · N         右侧分段控件「全部 / 失败」（仅存在失败或运行中时出现）
 *   JobCard ×N     卡片头只做展开；展开是步骤时间线 + runner + 卡内注解
 *   产物 · N       行尾直接下载（走 :downloader）
 *   其他注解        归属不到任务的那些
 * ```
 *
 * ## 两条来自 GitHub API 的硬约束（决定这里的取数方式）
 *
 * 1. **运行中拉不到日志**：远端日志文件在 job 结束后才生成（此前 404），
 *    且没有长轮询 / SSE。所以持续获取的是 `/runs/{id}/jobs` 这个几 KB 的 JSON，
 *    日志只在「job 定稿」那一刻抓一次（[newlyCompletedJobIds] 差分决定）；
 * 2. **「还没生成」不是「失败」**：[RunPollPolicy.isLogPending] 为真时显示
 *    「运行中，结束后自动出现」，不给重试按钮。
 *
 * 取数链路（run / jobs / artifacts 三路并行 + `PageCache` 直出）与日志取数（`:joblogs`）
 * 都沿用原样，本轮只换皮与补「定稿抓日志」。
 */
@Composable
fun WorkflowRunDetailScreen(
    sessionJson: String,
    owner: String,
    repo: String,
    runId: Long,
    logStore: JobLogStore,
    onBack: () -> Unit,
    onOpenLog: (jobId: Long, stepNumber: Long?) -> Unit,
    onReRun: (WorkflowItem) -> Unit,
) {
    val (host, token, _) = sessionInfo(sessionJson)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var run by remember { mutableStateOf<WorkflowRun?>(null) }
    var jobs by remember { mutableStateOf<List<RunJob>>(emptyList()) }
    var artifacts by remember { mutableStateOf<List<WorkflowArtifact>>(emptyList()) }
    var annotations by remember { mutableStateOf<List<WorkflowAnnotation>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var retryTick by remember { mutableStateOf(0) }
    var forceTick by remember { mutableStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(JobFilter.ALL) }
    // 展开态 / 日志装载态：以 jobId 为键，仅本页面生命周期有效。
    // 日志**内容**不放这里 —— 它由 :joblogs 的 store 持有（与日志页共用同一份内存分段）。
    var expandedJobs by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var jobLogs by remember { mutableStateOf<Map<Long, JobLog>>(emptyMap()) }
    var logPending by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var logFailed by remember { mutableStateOf<Set<Long>>(emptySet()) }
    // 运行中：耗时每秒走动（靠它刷新，而不是靠网络）
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }

    // 产物下载状态：与系统通知同源（任务表在 :downloader 里），
    // 退出页面再回来、甚至退到后台，进度都还在 —— 与发布附件那条路同一套
    val downloadTasks by DownloaderRuntime.tasks.collectAsState()

    // 主取数：run / jobs / artifacts 三路并行；注解依赖 headSha
    LaunchedEffect(owner, repo, runId, retryTick, forceTick) {
        loading = true
        failed = false
        run = null
        jobs = emptyList()
        artifacts = emptyList()
        annotations = emptyList()
        expandedJobs = emptySet()
        jobLogs = emptyMap()
        logPending = emptySet()
        logFailed = emptySet()

        val force = retryTick > 0 || forceTick > 0
        val manager = SearchCacheManager(SearchCacheDatabase.getInstance(context).searchCacheDao())
        val runKey = PageCache.runKey(owner, repo, runId)
        val jobsKey = PageCache.runJobsKey(owner, repo, runId)
        val artifactsKey = PageCache.runArtifactsKey(owner, repo, runId)

        // ① 先直出缓存（含过期）：返回再进时头部与任务列表立刻有内容
        PageCache.cachedFirst(manager, runKey, PageCache.TYPE_DETAIL, force)?.let { run = parseWorkflowRun(it) }
        PageCache.cachedFirst(manager, jobsKey, PageCache.TYPE_DETAIL, force)?.let { jobs = parseRunJobs(it) }
        PageCache.cachedFirst(manager, artifactsKey, PageCache.TYPE_DETAIL, force)?.let { artifacts = parseWorkflowArtifacts(it) }
        if (run != null || jobs.isNotEmpty()) loading = false

        coroutineScope {
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

            // ② 注解：先列「有注解」的 check-run，再逐个拉；**带上 check-run 名字**，
            //    这样注解能归回对应任务卡片（否则只能全部沉在页尾，与任务脱钩）
            var parsedAnnotations = emptyList<WorkflowAnnotation>()
            val headSha = parsedRun?.headSha.orEmpty()
            if (headSha.isNotBlank()) {
                val checkRuns = annotationCheckRuns(
                    RustBridge.getJson(host, token, "/repos/$owner/$repo/commits/$headSha/check-runs"),
                ).take(3)
                if (checkRuns.isNotEmpty()) {
                    val deferred = checkRuns.map { cr ->
                        async { cr to RustBridge.getJson(host, token, "/repos/$owner/$repo/check-runs/${cr.id}/annotations") }
                    }
                    parsedAnnotations = deferred.flatMap { d ->
                        val (cr, json) = d.await()
                        parseWorkflowAnnotations(json, cr.name)
                    }
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

    // 回到前台：对一次状态（首次进入不算，由主取数负责）
    var seenStart by remember { mutableStateOf(false) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (RunPollPolicy.resumeShouldForceRefresh(seenStart)) forceTick++ else seenStart = true
        }
    }

    // 运行中：耗时每秒走动
    LaunchedEffect(run?.status) {
        if (!RunPollPolicy.shouldPoll(run?.status, foreground = true)) return@LaunchedEffect
        while (isActive) {
            nowMs = System.currentTimeMillis()
            delay(1_000)
        }
    }

    // 运行中：轮询「任务状态」，任务定稿时抓一次日志（详见 RunPollPolicy）
    LaunchedEffect(owner, repo, runId, run?.status) {
        if (!RunPollPolicy.shouldPoll(run?.status, foreground = true)) return@LaunchedEffect
        val metered = networkMetered(context)
        val manager = SearchCacheManager(SearchCacheDatabase.getInstance(context).searchCacheDao())
        val runKey = PageCache.runKey(owner, repo, runId)
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var polls = 0
            var failures = 0
            var prevStatus = jobs.associate { it.id to it.status }
            while (isActive) {
                delay(RunPollPolicy.intervalMs(polls, metered))
                val json = RustBridge.getJson(host, token, "/repos/$owner/$repo/actions/runs/$runId/jobs")
                    ?.takeIf { !it.startsWith("ERROR:") }
                if (json == null) {
                    failures++
                    delay(RunPollPolicy.backoffMs(failures))
                    continue
                }
                failures = 0
                polls++
                val fresh = parseRunJobs(json)
                if (fresh.isEmpty()) continue

                val justDone = newlyCompletedJobIds(prevStatus, fresh)
                prevStatus = fresh.associate { it.id to it.status }
                jobs = fresh

                // 刚定稿的任务：抓一次日志并让界面直接可用（:joblogs 负责单飞与缓存）
                justDone.forEach { jobId ->
                    val log = logStore.refresh(jobId) ?: return@forEach
                    jobLogs = jobLogs + (jobId to log)
                    logPending = logPending - jobId
                    logFailed = logFailed - jobId
                }

                // 全部结束 ⇒ 拉一次 run 详情拿最终结论；run?.status 一变本 effect 即重启停止
                if (fresh.all { it.status == "completed" }) {
                    PageCache.refresh(manager, runKey, PageCache.TYPE_DETAIL, force = true) {
                        RustBridge.getJson(host, token, "/repos/$owner/$repo/actions/runs/$runId")
                    }?.let { parseWorkflowRun(it) }?.let { run = it }
                }
            }
        }
    }

    /** 懒加载某任务的日志（点「日志」时用）；「还没生成」不当作失败。 */
    fun loadJobLog(job: RunJob) {
        if (jobLogs.containsKey(job.id)) return
        scope.launch {
            val log = logStore.load(job.id)
            when {
                log != null -> {
                    jobLogs = jobLogs + (job.id to log)
                    logPending = logPending - job.id
                }
                RunPollPolicy.isLogPending(job.status) -> logPending = logPending + job.id
                else -> logFailed = logFailed + job.id
            }
        }
    }

    val progress = remember(jobs) { runProgress(jobs) }
    val elapsed = run?.let { r ->
        if (r.status == RunPollPolicy.RUNNING) elapsedSince(r.runStartedAt, nowMs)
        else durationMillis(r.runStartedAt, r.updatedAt)
    }
    val title = run?.let { "${it.name.ifBlank { "Run" }} · #${it.runNumber}" } ?: "Run #$runId"
    // 失败任务排最前（稳定排序）：打开页面第一眼就是挂掉的那个
    val orderedJobs = jobs
        .sortedBy { if (isFailedConclusion(it.conclusion)) 0 else 1 }
        .let { if (filter == JobFilter.FAILED) it.filter { j -> isFailedConclusion(j.conclusion) } else it }

    DetailScaffold(
        title = title,
        onBack = onBack,
        actions = {
            // 刷新：跑成功或跑一半时也能手动回源（改前只有失败态有重试）
            Text(
                "刷新",
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = Primer.Link,
                modifier = Modifier.iconTap {
                    retryTick++
                }.padding(horizontal = 6.dp, vertical = 4.dp),
            )
            Box {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "更多操作",
                    tint = Primer.IconPrimary,
                    modifier = Modifier.size(20.dp).iconTap { menuOpen = true },
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("在浏览器打开") },
                        onClick = {
                            menuOpen = false
                            run?.htmlUrl?.takeIf { it.isNotBlank() }?.let { url ->
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("重新运行") },
                        onClick = {
                            menuOpen = false
                            run?.let { r ->
                                if (r.workflowId > 0) {
                                    onReRun(WorkflowItem(id = r.workflowId, name = r.name, state = "active", path = r.path))
                                }
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("复制运行链接") },
                        onClick = {
                            menuOpen = false
                            run?.htmlUrl?.takeIf { it.isNotBlank() }?.let { clipboard.setText(AnnotatedString(it)) }
                        },
                    )
                }
            }
        },
    ) {
        when {
            loading -> DetailLoading()

            failed -> DetailErrorRetry { retryTick++ }

            else -> LazyColumn(Modifier.fillMaxSize()) {
                run?.let { r ->
                    item { RunHeaderCard(run = r, progress = progress, elapsedMs = elapsed) }
                }

                item {
                    DetailSectionTitle("任务 · ${jobs.size}") {
                        // 分段控件只在「有失败」或「运行中」时出现 —— 成功的小运行不必多一个控件
                        if (progress.failed > 0 || progress.running > 0) {
                            Row(
                                Modifier.clip(RoundedCornerShape(999.dp)).background(Primer.Gray150)
                                    .padding(2.dp),
                            ) {
                                FilterSegment(
                                    label = "全部 ${jobs.size}",
                                    on = filter == JobFilter.ALL,
                                    onClick = { filter = JobFilter.ALL },
                                )
                                FilterSegment(
                                    label = "失败 ${progress.failed}",
                                    on = filter == JobFilter.FAILED,
                                    onClick = { filter = JobFilter.FAILED },
                                )
                            }
                        }
                    }
                }

                if (orderedJobs.isEmpty()) {
                    item { DetailEmptyText(if (filter == JobFilter.FAILED) "没有失败的任务" else "暂无任务") }
                } else {
                    items(orderedJobs, key = { it.id }) { job ->
                        Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                            JobCard(
                                job = job,
                                expanded = job.id in expandedJobs,
                                onToggle = {
                                    expandedJobs = if (job.id in expandedJobs) expandedJobs - job.id else expandedJobs + job.id
                                },
                                annotations = annotations.filter { jobBelongsToAnnotation(it, job) },
                                onOpenStep = { stepNumber -> onOpenLog(job.id, stepNumber) },
                                onOpenLog = { onOpenLog(job.id, null) },
                                onCopyLog = {
                                    scope.launch {
                                        val log = jobLogs[job.id] ?: logStore.load(job.id)
                                        log?.let { clipboard.setText(AnnotatedString(it.text)) }
                                    }
                                },
                            )
                        }
                    }
                }

                if (artifacts.isNotEmpty()) {
                    item { DetailSectionTitle("产物 · ${artifacts.size}") }
                    items(artifacts, key = { it.id }) { artifact ->
                        ArtifactRow(
                            artifact = artifact,
                            context = context,
                            task = downloadTasks.firstOrNull { it.id == artifactTaskId(artifact.id) },
                        )
                    }
                }

                // 归属不到任何任务的注解（宁可放不对，不要放错）
                val loose = annotations.filter { a -> jobs.none { jobBelongsToAnnotation(a, it) } }
                if (loose.isNotEmpty()) {
                    item { DetailSectionTitle("其他注解 · ${loose.size}") }
                    items(loose) { annotation -> AnnotationRow(annotation) }
                }

                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

private enum class JobFilter { ALL, FAILED }

@Composable
private fun FilterSegment(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (on) Primer.BackgroundPrimary else Primer.Gray150)
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 4.dp),
    ) {
        Text(
            label,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (on) Primer.TextPrimary else Primer.TextSecondary,
        )
    }
}

/**
 * 产物行：名字 + 大小 + 「已过期」 + 行尾**一个主动作**。
 *
 * 动作随下载状态走，与发布附件行同一套：下载 → 取消 / 重试 / **安装**。
 * 安装那一步要先把 zip 解开 —— Actions 的产物**下载时永远是 zip**（平台约束，绕不过），
 * 所以「拿产物当第二条取包通道」的最后一环落在 App 这边，见 [installWorkflowArtifact]。
 *
 * 下载走 `:downloader`（前台服务 + 通知进度 + 重定向鉴权都是现成的）；
 * `archive_download_url` 需要鉴权，由 `:downloader` 的 `AuthProvider` 按 host 注入。
 * 地址缺失（老缓存 / 已过期被回收）时按钮置灰，不假装能下。
 */
@Composable
private fun ArtifactRow(
    artifact: WorkflowArtifact,
    context: android.content.Context,
    task: DownloadTask?,
) {
    val downloadable = artifact.archiveDownloadUrl.isNotBlank() && !artifact.expired
    val failed = task?.status == DownloadStatus.FAILED && !task.error.isNullOrBlank()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                artifact.name.ifBlank { "（未命名产物）" },
                fontSize = 12.5.sp,
                color = Primer.TextPrimary,
                maxLines = 2,
            )
            Spacer(Modifier.height(2.dp))
            // 失败原因直接写在这一行，别只留一个「重试」让人猜刚才发生了什么
            Text(
                if (failed) task.error!! else artifact.sizeText,
                fontSize = 11.sp,
                color = if (failed) Primer.DangerText else Primer.TextTertiary,
                maxLines = 2,
            )
        }
        if (artifact.expired) {
            Spacer(Modifier.width(8.dp))
            Text("已过期", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Primer.DangerText)
        }
        Spacer(Modifier.width(8.dp))
        when {
            task?.isActive == true -> ArtifactAction("取消", Primer.TextSecondary) {
                DownloaderRuntime.cancel(task.id)
            }
            task?.status == DownloadStatus.COMPLETED -> ArtifactAction("安装", Primer.Blue500) {
                val file = task.file
                Toast.makeText(
                    context,
                    if (file == null) "找不到已下载的文件" else installWorkflowArtifact(context, file),
                    Toast.LENGTH_LONG,
                ).show()
            }
            failed -> ArtifactAction("重试", Primer.Blue500) {
                DownloaderRuntime.retry(context, task.id)
            }
            else -> ArtifactAction(
                text = if (downloadable) "下载" else "不可用",
                color = if (downloadable) Primer.Blue500 else Primer.TextTertiary,
                enabled = downloadable,
            ) {
                runCatching {
                    DownloaderRuntime.enqueue(
                        context,
                        DownloadRequest(
                            id = artifactTaskId(artifact.id),
                            url = artifact.archiveDownloadUrl,
                            fileName = "${artifact.name}.zip",
                            title = artifact.name,
                            sizeHint = artifact.sizeInBytes,
                        ),
                    )
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Primer.Gray100))
}

/** 产物的下载任务 id：下载、取消、重试、取文件必须用同一个键，否则状态对不上。 */
private fun artifactTaskId(artifactId: Long): String = "artifact-$artifactId"

/** 产物行尾的动作按钮：任何时刻只给一个主动作，避免按钮堆叠（同发布附件行）。 */
@Composable
private fun ArtifactAction(
    text: String,
    color: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (enabled) color else Primer.Gray150)
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) Primer.Gray000 else Primer.TextTertiary,
        )
    }
}
