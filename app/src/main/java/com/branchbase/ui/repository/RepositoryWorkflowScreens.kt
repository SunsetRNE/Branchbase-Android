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
    onBack: () -> Unit,
    onOpenRun: (Long) -> Unit,
) {
    val (host, token, _) = sessionInfo(sessionJson)
    var runs by remember { mutableStateOf<List<WorkflowRun>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryTick by remember { mutableStateOf(0) }

    LaunchedEffect(owner, repo, workflowId, branch, retryTick) {
        loading = true
        error = null
        // 分支筛选：runs 接口 ?branch={branch}（空串=全部/默认分支）
        val br = branch?.takeIf { it.isNotBlank() }?.let { b -> "?branch=${encodeRef(b)}" } ?: ""
        val json = RustBridge.getJson(host, token, "/repos/$owner/$repo/actions/workflows/$workflowId/runs$br")
        if (json == null || json.startsWith("ERROR:")) {
            error = json?.removePrefix("ERROR:") ?: "加载失败"
        } else {
            runs = parseWorkflowRuns(json)
        }
        loading = false
    }

    FullScreen(title = workflowName, onBack = onBack) {
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
            Text("#${run.runNumber} · ${run.headBranch} · ${run.conclusion ?: run.status} · ${shortTime(run.createdAt)}", fontSize = 11.5.sp, color = Primer.TextTertiary)
        }
    }
}

@Composable
fun RunDetailContent(
    sessionJson: String,
    owner: String,
    repo: String,
    runId: Long,
    onBack: () -> Unit,
    onOpenJob: (Long) -> Unit,
) {
    val (host, token, _) = sessionInfo(sessionJson)
    var jobs by remember { mutableStateOf<List<RunJob>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(owner, repo, runId) {
        loading = true
        RustBridge.getJson(host, token, "/repos/$owner/$repo/actions/runs/$runId/jobs")
            ?.takeIf { !it.startsWith("ERROR:") }
            ?.let { jobs = parseRunJobs(it) }
        loading = false
    }

    FullScreen(title = "Run #$runId", onBack = onBack) {
        when {
            loading -> CenterLoading()
            jobs.isEmpty() -> CenterText("暂无任务")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(jobs) { job -> RunJobRow(job) { onOpenJob(job.id) } }
            }
        }
    }
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
    var steps by remember { mutableStateOf<List<JobStep>>(emptyList()) }
    var logs by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(owner, repo, jobId) {
        loading = true
        RustBridge.getJson(host, token, "/repos/$owner/$repo/actions/jobs/$jobId")
            ?.takeIf { !it.startsWith("ERROR:") }
            ?.let { steps = parseJobSteps(it) }
        RustBridge.getJson(host, token, "/repos/$owner/$repo/actions/jobs/$jobId/logs")
            ?.takeIf { !it.startsWith("ERROR:") }
            ?.let { logs = it }
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
private fun FullScreen(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Primer.BackgroundPrimary).statusBarsPadding().navigationBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Primer.IconPrimary, modifier = Modifier.size(24.dp).clickable { onBack() })
            Spacer(Modifier.width(8.dp))
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary, maxLines = 1)
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