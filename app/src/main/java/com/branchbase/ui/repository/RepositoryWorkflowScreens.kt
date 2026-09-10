package com.branchbase.ui.repository

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.cache.PageCache
import com.branchbase.cache.SearchCacheDatabase
import com.branchbase.cache.SearchCacheManager
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import com.branchbase.core.RustBridge
import com.branchbase.ui.theme.Primer

/**
 * 工作流（Actions）四级页面：工作流列表（已有）→ 运行历史 → Run 详情（jobs）→ Job 详情（steps+日志）。
 * 对齐 `docs/workflow-wireframe.md`。
 */

private fun runStatusColor(status: String, conclusion: String?): Color = when (conclusion) {
    "success" -> Color(0xFF28A745)
    "failure" -> Color(0xFFD73A49)
    "cancelled", "skipped" -> Color(0xFF6A6D7C)
    else -> when (status) {
        "queued", "in_progress" -> Color(0xFFF66A0A)
        else -> Color(0xFF6A6D7C)
    }
}

@Composable
fun WorkflowRunsContent(
    sessionJson: String,
    owner: String,
    repo: String,
    workflowId: Long,
    workflowName: String,
    branch: String? = null,
    refreshTick: Int = 0,
    onBack: () -> Unit,
    onOpenRun: (Long) -> Unit,
    onOpenActions: (() -> Unit)? = null,
) {
    val (host, token, _) = sessionInfo(sessionJson)
    val context = LocalContext.current
    // 缓存键先于状态声明：切工作流/分支时列表自动清空，避免显示上一页内容
    val cacheKey = PageCache.runsKey(owner, repo, workflowId, branch.orEmpty())
    var runs by remember(cacheKey) { mutableStateOf<List<WorkflowRun>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryTick by remember { mutableStateOf(0) }

    LaunchedEffect(owner, repo, workflowId, branch, refreshTick, retryTick) {
        loading = true
        error = null
        // 手动刷新/重试必须真的回源
        val force = refreshTick > 0 || retryTick > 0
        val manager = SearchCacheManager(SearchCacheDatabase.getInstance(context).searchCacheDao())
        // 分支筛选：runs 接口 ?branch={branch}（空串=全部/默认分支）
        val br = branch?.takeIf { it.isNotBlank() }?.let { b -> "?branch=${encodeRef(b)}" } ?: ""
        var shown = false

        // ① 先直出缓存（含过期）：运行历史是最高频的往返页面之一
        PageCache.cachedFirst(manager, cacheKey, PageCache.TYPE_DETAIL, force)?.let { cached ->
            parseWorkflowRuns(cached).takeIf { it.isNotEmpty() }?.let {
                runs = it
                shown = true
                loading = false
            }
        }

        // ② 回源并写回
        val json = PageCache.refresh(manager, cacheKey, PageCache.TYPE_DETAIL, force) {
            RustBridge.getJson(host, token, "/repos/$owner/$repo/actions/workflows/$workflowId/runs$br")
        }
        if (json == null) {
            if (!shown && runs.isEmpty()) error = "加载失败"
        } else {
            runs = parseWorkflowRuns(json)
        }
        loading = false
    }

    FullScreen(
        title = workflowName,
        onBack = onBack,
        actions = if (onOpenActions != null) {
            {
                // 右上角操作入口：召唤工作流操作抽屉（执行工作流 / 查看文件 / 浏览器打开）
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "工作流操作",
                    tint = Primer.IconPrimary,
                    modifier = Modifier.size(22.dp).clickable { onOpenActions() },
                )
            }
        } else {
            {}
        },
    ) {
        when {
            loading -> CenterLoading()
            error != null -> ListError(error!!) { retryTick++ }
            runs.isEmpty() -> CenterText("暂无运行记录")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(runs) { run -> WorkflowRunRow(run) { onOpenRun(run.id) } }
            }
        }
    }
}

@Composable
private fun WorkflowRunRow(run: WorkflowRun, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(12.dp, 16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(runStatusColor(run.status, run.conclusion)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(run.name, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary, maxLines = 2)
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    append("#").append(run.runNumber)
                    if (run.runAttempt > 1) append("（第 ${run.runAttempt} 次尝试）")
                    if (run.event.isNotBlank()) append(" · ").append(eventLabel(run.event))
                    if (run.headBranch.isNotBlank()) append(" · ").append(run.headBranch)
                    append(" · ").append(runStatusLabel(run.status, run.conclusion))
                    val d = formatDuration(durationMillis(run.runStartedAt.ifBlank { run.createdAt }, run.updatedAt))
                    if (d != "—") append(" · ").append(d)
                    append(" · ").append(shortTime(run.createdAt))
                },
                fontSize = 11.5.sp,
                color = Primer.TextTertiary,
            )
        }
    }
}

/**
 * 运行详情：委托给 `WorkflowRunDetailScreen`（原生富渲染：run 头部 + jobs→steps 时间线 +
 * 步骤日志 + 注解 + 产物）。保留本函数名与签名，调用方无需改动。
 */
@Composable
fun RunDetailContent(
    sessionJson: String,
    owner: String,
    repo: String,
    runId: Long,
    onBack: () -> Unit,
    onOpenJob: (Long) -> Unit,
) {
    WorkflowRunDetailScreen(
        sessionJson = sessionJson,
        owner = owner,
        repo = repo,
        runId = runId,
        onBack = onBack,
        onOpenJob = onOpenJob,
    )
}

@Composable
private fun RunJobRow(job: RunJob, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(12.dp, 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(runStatusColor(job.status, job.conclusion)))
        Spacer(Modifier.width(10.dp))
        Text(job.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary, modifier = Modifier.weight(1f))
        Text(job.conclusion ?: job.status, fontSize = 11.sp, color = Primer.TextTertiary)
    }
}

@Composable
fun JobDetailContent(
    sessionJson: String,
    owner: String,
    repo: String,
    jobId: Long,
    onBack: () -> Unit,
) {
    val (host, token, _) = sessionInfo(sessionJson)
    val context = LocalContext.current
    var steps by remember { mutableStateOf<List<JobStep>>(emptyList()) }
    var logs by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(owner, repo, jobId) {
        loading = true
        val manager = SearchCacheManager(SearchCacheDatabase.getInstance(context).searchCacheDao())
        val jobKey = PageCache.jobKey(owner, repo, jobId)
        val logKey = PageCache.jobLogKey(owner, repo, jobId)

        // ① 直出（steps 与日志都可能已缓存）
        PageCache.cachedFirst(manager, jobKey, PageCache.TYPE_DETAIL)?.let { steps = parseJobSteps(it) }
        PageCache.cachedFirst(manager, logKey, PageCache.TYPE_FILE)?.let { logs = it }
        if (steps.isNotEmpty() || logs.isNotBlank()) loading = false

        // ② 回源（steps 与日志并行）
        coroutineScope {
            val stepsJob = async {
                PageCache.refresh(manager, jobKey, PageCache.TYPE_DETAIL) {
                    RustBridge.getJson(host, token, "/repos/$owner/$repo/actions/jobs/$jobId")
                }
            }
            val logJob = async {
                PageCache.refresh(manager, logKey, PageCache.TYPE_FILE) {
                    RustBridge.getJson(host, token, "/repos/$owner/$repo/actions/jobs/$jobId/logs")
                }
            }
            stepsJob.await()?.let { steps = parseJobSteps(it) }
            logJob.await()?.let { logs = it }
        }
        loading = false
    }

    FullScreen(title = "Job #$jobId", onBack = onBack) {
        when {
            loading -> CenterLoading()
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(steps) { step -> JobStepRow(step) }
                if (logs.isNotBlank()) {
                    item {
                        Text(
                            logs,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = Color(0xFF24292F),
                            modifier = Modifier.fillMaxWidth().background(Color(0xFFF6F8FA)).padding(12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun JobStepRow(step: JobStep) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(runStatusColor(step.status, step.conclusion)))
        Spacer(Modifier.width(10.dp))
        Text("${step.number}. ${step.name}", fontSize = 13.5.sp, color = Primer.TextPrimary, modifier = Modifier.weight(1f))
    }
}

// ── 通用全屏容器 ──

@Composable
private fun FullScreen(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(Primer.BackgroundPrimary).statusBarsPadding().navigationBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Primer.IconPrimary, modifier = Modifier.size(24.dp).clickable { onBack() })
            Spacer(Modifier.width(8.dp))
            Text(
                title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Primer.TextPrimary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            actions()
        }
        content()
    }
}

@Composable
private fun CenterLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Primer.Blue500)
    }
}

@Composable
private fun CenterText(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 13.sp, color = Primer.TextTertiary)
    }
}