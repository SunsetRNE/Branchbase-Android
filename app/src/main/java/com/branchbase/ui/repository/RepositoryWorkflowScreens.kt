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
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
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
import com.branchbase.R
import com.branchbase.cache.PageCache
import com.branchbase.cache.SearchCacheDatabase
import com.branchbase.cache.SearchCacheManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import com.branchbase.core.RustBridge
import com.branchbase.joblogs.JobLogStore
import com.branchbase.ui.resolve
import com.branchbase.ui.theme.iconTap
import com.branchbase.ui.theme.CodeSyntax
import com.branchbase.ui.theme.Primer

/**
 * 工作流（Actions）四级页面：工作流列表 → 运行历史 → Run 详情（jobs）→ Job 详情（steps+日志）。
 */

/**
 * 运行 / 任务 / 步骤的状态点颜色。
 *
 * 必须读主题角色：以前这里写死的是**浅色色板**的 `success` / `danger` / `warning` 取值
 * （`#28A745` / `#D73A49` / `#F66A0A`），深色下那些色偏暗，状态点会糊在深色底上。
 * 中性态用 `Gray500`（浅色 `#6A6D7C`，深色 `#8B949E`）—— 取值与改前的 `#6A6D7C` 完全一致。
 */
@Composable
@ReadOnlyComposable
private fun runStatusColor(status: String, conclusion: String?): Color = when (conclusion) {
    "success" -> Primer.Green500
    "failure" -> Primer.Red500
    "cancelled", "skipped" -> Primer.Gray500
    else -> when (status) {
        "queued", "in_progress" -> Primer.Orange500
        else -> Primer.Gray500
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

    // 回到前台对一次：运行历史是最需要「切回来就是新的」的页面之一（CI 在后台跑完了）
    val lifecycleOwner = LocalLifecycleOwner.current
    var seenStart by remember { mutableStateOf(false) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (RunPollPolicy.resumeShouldForceRefresh(seenStart)) retryTick++ else seenStart = true
        }
    }

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
            if (!shown && runs.isEmpty()) error = context.getString(R.string.error_load_failed)
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
                    contentDescription = stringResource(R.string.label_workflow_actions),
                    tint = Primer.IconPrimary,
                    modifier = Modifier.size(22.dp).iconTap { onOpenActions() },
                )
            }
        } else {
            {}
        },
    ) {
        when {
            loading -> CenterLoading()
            error != null -> ListError(error!!) { retryTick++ }
            runs.isEmpty() -> CenterText(stringResource(R.string.state_no_runs))
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
                    if (run.runAttempt > 1) append(stringResource(R.string.label_attempt, run.runAttempt))
                    if (run.event.isNotBlank()) append(" · ").append(eventLabel(run.event))
                    if (run.headBranch.isNotBlank()) append(" · ").append(run.headBranch)
                    append(" · ").append(runStatusLabel(run.status, run.conclusion))
                    val d = formatDuration(durationMillis(run.runStartedAt.ifBlank { run.createdAt }, run.updatedAt))
                    if (d != "—") append(" · ").append(d)
                    append(" · ").append(shortTime(run.createdAt).resolve())
                },
                fontSize = 11.5.sp,
                color = Primer.TextTertiary,
            )
        }
    }
}

/**
 * 运行详情：委托给 `WorkflowRunDetailScreen`（卡片流重绘版）。
 *
 * 取数链路与日志 store 都不变，调用方按新形参补两个回调：点步骤进日志页、重跑。
 */
@Composable
fun RunDetailContent(
    sessionJson: String,
    owner: String,
    repo: String,
    runId: Long,
    logStore: JobLogStore,
    onBack: () -> Unit,
    onOpenLog: (Long, Long?) -> Unit,
    onReRun: (WorkflowItem) -> Unit,
) {
    WorkflowRunDetailScreen(
        sessionJson = sessionJson,
        owner = owner,
        repo = repo,
        runId = runId,
        logStore = logStore,
        onBack = onBack,
        onOpenLog = onOpenLog,
        onReRun = onReRun,
    )
}

@Composable
private fun RunJobRow(job: RunJob, onClick: () -> Unit) {
    val jobTone = stateTone(job.status, job.conclusion)
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(12.dp, 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(jobTone.dot))
        Spacer(Modifier.width(10.dp))
        Text(job.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary, modifier = Modifier.weight(1f))
        // 文案走 runStatusLabel（多状态：进行中 / 排队中 / 已取消 / 超时…），颜色走 stateTone。
        // 之前这里显示的是 `conclusion ?: status` 的**裸值** —— 运行中的 job 会显示成 `null`
        // （conclusion 被 org.json 解析成字符串 "null"），既不是中文也不分状态。
        Text(
            runStatusLabel(job.status, job.conclusion),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = jobTone.text,
        )
    }
}


@Composable
private fun JobStepRow(step: JobStep) {
    val stepTone = stateTone(step.status, step.conclusion)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(stepTone.dot))
        Spacer(Modifier.width(10.dp))
        Text("${step.number}. ${step.name}", fontSize = 13.5.sp, color = Primer.TextPrimary, modifier = Modifier.weight(1f))
        // 步骤也要有多状态显示：只有色点的话，「跳过 / 取消 / 失败」在色盲或小尺寸下分不出来
        Text(
            runStatusLabel(step.status, step.conclusion),
            fontSize = 11.sp,
            color = stepTone.text,
        )
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
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = Primer.IconPrimary, modifier = Modifier.size(24.dp).iconTap { onBack() })
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