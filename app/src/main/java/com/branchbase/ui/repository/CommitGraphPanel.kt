package com.branchbase.ui.repository

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.R
import com.branchbase.core.RustBridge
import com.branchbase.ui.theme.PlaceholderSwap
import com.branchbase.ui.theme.Primer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Git 工作台 —— **「提交图」档**（[GitPanelKind.Graph]）。
 *
 * ## 数据从哪来
 *
 * 阶段 1 走 REST：`GET /repos/{o}/{r}/commits?sha={branch}&per_page=100`（**响应里本来就带
 * `parents`**，只是旧的 `parseCommits` 把它丢了 —— 图用 [parseGraphCommits] 自己解析，
 * 不去动提交列表那份）。分页「加载更早」用窗口内最老的 sha 续取，**不设次数上限**（已拍板）。
 *
 * 本地 `log_graph`（阶段 3）落地之后，这里的来源会换成本地仓库；届时**渲染层不用改** ——
 * 它只吃 [GraphRow] 列表。
 *
 * ## 虚节点（方案 A）
 *
 * 工作区有改动时，HEAD **之上**多一行「工作区 · N 处改动」：虚线圆圈 + 不走泳道实线、
 * **不画 sha**。它是唯一一个点了**不打开提交详情**的行（点它进「工作区」档）——
 * 因为它不是 git 对象，以 sha 为键的交互（详情 / 复制 / 对比 / 回滚）对它一律不成立。
 */
@Composable
fun CommitGraphPanel(
    host: String,
    token: String,
    owner: String,
    repo: String,
    branch: String,
    dirtyCount: Int,
    onOpenWorkspace: () -> Unit,
    onOpenSync: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var commits by remember(branch) { mutableStateOf<List<GraphCommit>>(emptyList()) }
    var loading by remember(branch) { mutableStateOf(true) }
    var loadingMore by remember(branch) { mutableStateOf(false) }
    var truncated by remember(branch) { mutableStateOf(false) }
    var error by remember(branch) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // `stringResource` 不能在 suspend / 点击回调里调（不是组合上下文）——统一走 Context
    val context = LocalContext.current

    suspend fun fetch(sha: String?): List<GraphCommit>? {
        val path = buildString {
            append("/repos/$owner/$repo/commits?per_page=")
            append(PAGE_SIZE)
            if (!sha.isNullOrBlank()) append("&sha=$sha")
            else if (branch.isNotBlank()) append("&sha=$branch")
        }
        val json = RustBridge.getJson(host, token, path) ?: return null
        if (json.startsWith("ERROR:")) return null
        return parseGraphCommits(json)
    }

    LaunchedEffect(host, token, owner, repo, branch) {
        loading = true
        error = null
        val first = withContext(Dispatchers.IO) { fetch(null) }
        if (first == null) {
            error = context.getString(R.string.error_local_repo_missing_clone)
            loading = false
            return@LaunchedEffect
        }
        commits = first
        truncated = first.size >= PAGE_SIZE
        loading = false
    }

    val rows = remember(commits, dirtyCount) {
        CommitGraphLayout.layout(commits, workingTreeDirty = dirtyCount.takeIf { it > 0 })
    }

    Column(modifier.fillMaxWidth()) {
        if (loading) {
            PlaceholderSwap(loading = true, skeleton = { GraphSkeleton() }) { }
            return@Column
        }
        error?.let {
            Text(
                stringResource(R.string.error_graph_load_failed, it),
                fontSize = 11.5.sp,
                color = Primer.DangerText,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            TextButton(onClick = { scope.launch { loading = true; error = null
                val again = withContext(Dispatchers.IO) { fetch(null) }
                if (again == null) error = context.getString(R.string.error_local_repo_missing_clone) else commits = again
                loading = false } }) {
                Text(stringResource(R.string.action_retry), color = Primer.Blue500, fontSize = 12.sp)
            }
            return@Column
        }
        if (rows.isEmpty()) {
            Text(
                stringResource(R.string.state_graph_empty),
                fontSize = 12.sp,
                color = Primer.TextTertiary,
                modifier = Modifier.padding(vertical = 10.dp),
            )
            return@Column
        }

        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            items(
                items = rows,
                key = { row ->
                    when (row) {
                        is GraphRow.WorkingTree -> "working-tree"
                        is GraphRow.Commit -> row.row.commit.fullSha
                    }
                },
            ) { row ->
                when (row) {
                    is GraphRow.WorkingTree -> WorkingTreeRow(row.dirtyCount, onOpenWorkspace)
                    is GraphRow.Commit -> CommitRow(row.row)
                }
            }
        }

        // 分页脚注：**如实说明还有更早的历史**（产品决策：不设上限，但也不许把截断画成尽头）
        if (rows.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (truncated) {
                        stringResource(R.string.label_graph_truncated, commits.size)
                    } else {
                        stringResource(R.string.label_graph_all_loaded, commits.size)
                    },
                    fontSize = 10.sp,
                    color = Primer.TextTertiary,
                    modifier = Modifier.weight(1f),
                )
                if (truncated) {
                    TextButton(
                        enabled = !loadingMore,
                        onClick = {
                            val oldest = commits.lastOrNull()?.fullSha ?: return@TextButton
                            scope.launch {
                                loadingMore = true
                                val more = withContext(Dispatchers.IO) { fetch(oldest) }
                                if (more != null) {
                                    val seen = commits.map { it.fullSha }.toHashSet()
                                    val fresh = more.filter { it.fullSha !in seen }
                                    commits = commits + fresh
                                    truncated = more.size >= PAGE_SIZE
                                }
                                loadingMore = false
                            }
                        },
                    ) {
                        Text(
                            stringResource(R.string.action_load_more),
                            fontSize = 11.sp,
                            color = if (loadingMore) Primer.TextTertiary else Primer.Blue500,
                        )
                    }
                }
            }
        }
    }
}

private const val PAGE_SIZE = 100

/** 虚节点：HEAD 之上那一层「还没提交」的东西。虚线 + 无 sha + 点它进「工作区」档。 */
@Composable
private fun WorkingTreeRow(dirtyCount: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(GUTTER), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(15.dp)) {
                drawCircle(
                    color = Color.Gray,
                    radius = size.minDimension / 2 - 1.5f,
                    style = Stroke(width = 1.6f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f))),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.label_working_tree_node, dirtyCount),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Primer.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.state_working_tree_uncommitted),
                fontSize = 10.sp,
                color = Primer.TextTertiary,
            )
        }
    }
}

/** 一条提交：gutter 画泳道 + 右侧内容（短 sha · 标题 · 作者/时间）。 */
@Composable
private fun CommitRow(row: GraphCommitRow) {
    // 颜色必须在**组合期**取好再传进绘制 lambda：`Primer.XXX` 是 @Composable getter，
    // 绘制 lambda 不是组合上下文（ContributionWall 那一轮的教训）。
    val lanes = Primer.GraphLanes
    val panelBg = Primer.BackgroundPrimary
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(
            Modifier
                .width(GUTTER)
                .height(ROW_HEIGHT),
        ) {
            val step = GUTTER_PX / (row.laneCount.coerceAtLeast(1) + 1)
            fun x(lane: Int) = step * (lane + 1)
            val cy = size.height / 2f
            val colorOf = { lane: Int -> lanes[lane % lanes.size] }

            row.edges.forEach { e ->
                val c = colorOf(e.fromLane)
                val x1 = x(e.fromLane)
                val x2 = x(e.toLane)
                if (e.fromNode) {
                    // 节点 → 父：从圆心往下（同泳道是直线，跨泳道是斜线）
                    if (e.dangling) {
                        // 父不在窗口里：画一个终止小横杠，**不许画断头线**
                        drawLine(c, Offset(x1, cy), Offset(x1, size.height), strokeWidth = 1.6f)
                        drawLine(c, Offset(x1 - 3f, size.height - 1.5f), Offset(x1 + 3f, size.height - 1.5f), strokeWidth = 1.6f)
                    } else {
                        drawLine(c, Offset(x1, cy), Offset(x2, size.height), strokeWidth = 1.6f)
                    }
                } else {
                    // 只是穿过 / 位移：整行从上到下
                    drawLine(
                        c,
                        Offset(x1, 0f),
                        Offset(x2, size.height),
                        strokeWidth = 1.6f,
                        cap = StrokeCap.Round,
                    )
                }
            }
            // 节点
            drawCircle(lanes[row.lane % lanes.size], radius = 4.2f, center = Offset(x(row.lane), cy))
            drawCircle(panelBg, radius = 1.8f, center = Offset(x(row.lane), cy))
        }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(
                row.commit.subject.ifBlank { "（无提交信息）" },
                fontSize = 12.sp,
                color = Primer.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${row.commit.shortSha} · ${row.commit.author}",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = Primer.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun GraphSkeleton() {
    Column(Modifier.fillMaxWidth()) {
        repeat(5) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Primer.Gray150),
            )
        }
    }
}

/** gutter 宽度（泳道画在左侧固定列里，右侧才是文字）。 */
private val GUTTER = 30.dp
private val ROW_HEIGHT = 34.dp

/** 与 [GUTTER] 对应的像素值（绘制 lambda 里拿到的是 px）。 */
private const val GUTTER_PX = 30f
