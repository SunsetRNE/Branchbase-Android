package com.branchbase.ui.repository

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.ui.LoadState
import com.branchbase.ui.loadStateOf
import com.branchbase.ui.theme.SkeletonRows
import com.branchbase.ui.theme.skeletonRowsFor
import com.branchbase.R
import com.branchbase.core.RustBridge
import com.branchbase.ui.log.LogCategory
import com.branchbase.ui.log.Logger
import com.branchbase.ui.theme.Primer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Git 工作台 —— **「提交图」档**（[GitPanelKind.Graph]）。
 *
 * ## 数据从哪来（阶段 3' 起是**双来源**，由 [graphSourceOf] 择源）
 *
 * | 来源 | 取数 | 分页键 | 什么时候用 |
 * |---|---|---|---|
 * | 本地 `log_graph` | 离线、不消耗 API 限额、**看得见还没推送的本地提交**（并逐条标出来） | `skip`（已加载条数） | 本地仓库存在**且不是浅克隆** |
 * | REST `/commits?sha=&per_page=100` | 与列表页同一个接口（响应里本来就带 `parents`） | 窗口内**最老的 sha** | 其余情况 |
 *
 * 为什么浅克隆不能当来源：clone 用 `depth(1)`，本地只有 HEAD 一条提交 —— 直接换过去
 * 就是把「一屏 100 条」换成「1 条」。浅克隆的出路是「加深」（`fetch_deepen`，阶段 4），
 * 面板底部给的就是那枚出口；加深完成后宿主把 `refreshTick` 一变，这里自然翻回本地来源。
 *
 * 本地读不出来（引擎不可用 / 目录被删）时**退回 REST**：图还能看，但日志里留一条 warn ——
 * 否则事后只看到「来源=本地」，没人知道它其实失败过。
 *
 * ## 未推送段（阶段 4 收尾）
 *
 * 本地来源下，`HEAD` 可达、**上游**不可达的提交在行尾标一枚「未推送」（`unpushed`，
 * 口径与工作区档的 `ahead` 完全同源：图上的标记数 = 那档写的「待推送 N」）；
 * 脚注给一句「其中 N 条…」，只数**已加载的这一屏**（分页没加载的不在手上，不许编数）。
 *
 * 标记**只走文字**：虚线圈已经是「未提交」虚节点在用的形状语法（§4.3），
 * 再拿空心 / 虚线去表示「未推送」就是两件事抢一套画法。
 * REST 来源一律不标 —— 那份响应里没有「本地推没推」这件事。
 *
 * ## 点一行看什么
 *
 * 提交行 → **本地 diff**（`diff_commit`，1.0.99 起）：只读动作，与工作区档的改动行同一个落点
 * （`LocalDiffScreen`）。虚节点**没有 sha**，所以它仍然只进「工作区」档 ——
 * 以 sha 为键的交互对它一律不成立。
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
    repoDir: String,
    localRepoExists: Boolean,
    refreshTick: Int,
    dirtyCount: Int,
    onOpenWorkspace: () -> Unit,
    onOpenSync: () -> Unit,
    onDeepen: (() -> Unit)? = null,
    onOpenCommitDiff: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var commits by remember { mutableStateOf<List<GraphCommit>>(emptyList()) }
    // 本轮**实际**用的来源：本地失败会退回 REST，所以它不一定等于 [graphSourceOf] 的结果
    var source by remember { mutableStateOf<GraphSource?>(null) }
    // 本地是不是浅克隆：**在取数那一趟里读**（IO 上），不在组合期读 ——
    // 组合期读文件是主线程的一次 stat，而面板可能因为任何原因重组
    var shallowLocal by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var truncated by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // `stringResource` 不能在 suspend / 点击回调里调（不是组合上下文）——统一走 Context
    val context = LocalContext.current

    suspend fun fetchRest(sha: String?): List<GraphCommit>? {
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

    suspend fun fetchPage(page: GraphPage, from: GraphSource): List<GraphCommit>? = when (page) {
        is GraphPage.Local ->
            RustBridge.gitLogGraph(repoDir, PAGE_SIZE, page.skip)?.let(::parseLocalGraphCommits)
        is GraphPage.Rest -> fetchRest(page.sha)
    }

    /**
     * 读第一页（含**退回 REST**）。首屏与「重试」共用这一份 ——
     * 重试只重读当前来源的话，本地那侧坏掉时会永远卡在同一个错误上，
     * 而真正该做的是再走一遍择源 + 兜底。
     */
    suspend fun loadFirstPage() {
        loading = true
        error = null
        // 浅克隆判定在 IO 上做（读 `.git/shallow`），拿到的结果同时供择源与脚注使用
        val shallow = withContext(Dispatchers.IO) { isShallowClone(repoDir) }
        shallowLocal = shallow
        var used = graphSourceOf(localRepoExists, shallow)
        var localFailed = false
        var first = withContext(Dispatchers.IO) { fetchPage(nextGraphPage(used, emptyList()), used) }
        if (first == null && used == GraphSource.LOCAL) {
            localFailed = true
            Logger.warn(
                LogCategory.NETWORK, GIT_WORKBENCH_LOG_TAG,
                "提交图 ▸ 本地 $repoDir 读取失败，退回 REST",
            )
            used = GraphSource.REST
            first = withContext(Dispatchers.IO) { fetchPage(GraphPage.Rest(null), GraphSource.REST) }
        }
        source = used
        if (first == null) {
            error = context.getString(
                R.string.error_graph_load_failed,
                context.getString(
                    if (localFailed) R.string.error_graph_both_failed
                    else R.string.error_graph_remote_failed,
                ),
            )
            loading = false
            // 取数失败**必须留一条**：面板上只有一句「加载失败」，事后没人知道是网络、
            // 限额还是仓库名错了（日志锚点「Git工作台」）
            Logger.warn(
                LogCategory.NETWORK, GIT_WORKBENCH_LOG_TAG,
                "提交图 ▸ $owner/$repo@${branch.ifBlank { "HEAD" }} 取数失败（来源 $used）",
            )
            return
        }
        commits = first
        truncated = first.size >= PAGE_SIZE
        loading = false
        Logger.net(
            "提交图 ▸ $owner/$repo@${branch.ifBlank { "HEAD" }}：${first.size} 条" +
                "（来源 ${if (used == GraphSource.LOCAL) "本地" else "REST"}）" +
                if (truncated) "（还有更早）" else "",
            GIT_WORKBENCH_LOG_TAG,
        )
    }

    // 取数键里带上 repoDir / refreshTick：加深成功后 refreshTick 一变，这一轮才真的会重读
    //（并重新读一次 `.git/shallow`）—— 少了它，界面会停在加深前的那一份（用户以为没生效）
    LaunchedEffect(host, token, owner, repo, branch, repoDir, localRepoExists, refreshTick) {
        loadFirstPage()
    }

    val rows = remember(commits, dirtyCount) {
        CommitGraphLayout.layout(commits, workingTreeDirty = dirtyCount.takeIf { it > 0 })
    }
    // 未推送段：**只数手上这一屏**（本地来源才有值；REST 来源解析出来全是 false，见 GraphCommit.unpushed）
    val unpushedInPage = remember(commits) { unpushedCount(commits) }

    Column(modifier.fillMaxWidth()) {
        if (loadStateOf(loading, commits.size) == LoadState.Loading) {
            // 骨架**立刻**出现（浮层不适用「延迟现身」，见 `PlaceholderSwap` 的说明），
            // 并且铺满宿主给的内容区：行数按实测高度算，不写死 ——
            // 写死的话宿主一改高度，这里不是没铺满就是画到盒子外
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                SkeletonRows(
                    rows = skeletonRowsFor(maxHeight.value.toInt(), GRAPH_ROW_HEIGHT_DP),
                    rowHeight = GRAPH_ROW_HEIGHT,
                )
            }
            return@Column
        }
        error?.let {
            // 居中在固定内容区里（靠上的一行小字会被读成「没加载出来」，见 PanelEmptyArea）
            PanelEmptyArea(Modifier.weight(1f)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(it, fontSize = 11.5.sp, color = Primer.DangerText, textAlign = TextAlign.Center)
                    TextButton(onClick = { scope.launch { loadFirstPage() } }) {
                        Text(stringResource(R.string.action_retry), color = Primer.Blue500, fontSize = 12.sp)
                    }
                }
            }
            return@Column
        }
        if (rows.isEmpty()) {
            PanelEmptyArea(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.state_graph_empty),
                    fontSize = 12.sp,
                    color = Primer.TextTertiary,
                    textAlign = TextAlign.Center,
                )
            }
            return@Column
        }

        LazyColumn(
            state = rememberLazyListState(),
            // 取满内容区的剩余高度（宿主的盒子是固定高的，列表不再自己限高：
            // 限高会让「3 条提交」和「30 条提交」在面板里长得一样高，而那正是要修的跳动来源）
            modifier = Modifier.fillMaxWidth().weight(1f),
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
                    is GraphRow.Commit -> CommitRow(
                        row = row.row,
                        // 没有出口就不让行可点（与工作区档同一条口径）
                        onClick = onOpenCommitDiff?.let { open -> { open(row.row.commit.fullSha) } },
                    )
                }
            }
        }

        // 分页脚注：**如实说明还有更早的历史**（产品决策：不设上限，但也不许把截断画成尽头）
        if (rows.isNotEmpty()) {
            Text(
                if (truncated) {
                    stringResource(R.string.label_graph_truncated, commits.size)
                } else {
                    stringResource(R.string.label_graph_all_loaded, commits.size)
                },
                fontSize = 10.sp,
                color = Primer.TextTertiary,
                modifier = Modifier.padding(top = 6.dp),
            )
            // 未推送段：只说**这一屏里有几条**没推到上游 —— 全量的那个数在工作区档（`ahead`）。
            // 这里写「一共 N 条」就是编数：分页只加载了一部分，没加载的不在手上
            if (unpushedInPage > 0) {
                Text(
                    stringResource(R.string.note_graph_unpushed, unpushedInPage),
                    fontSize = 10.sp,
                    color = Primer.TextTertiary,
                    lineHeight = 13.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            // 浅克隆：REST 的这一屏是完整的，但**本地那份不是** —— 说清「离线看图要先把历史拉下来」。
            // 不许只在有出口时才说：没有出口（宿主没接）时这一句同样是用户需要知道的事实
            if (source == GraphSource.REST && shallowLocal && localRepoExists) {
                Text(
                    stringResource(R.string.note_graph_shallow_local),
                    fontSize = 10.sp,
                    color = Primer.TextTertiary,
                    lineHeight = 13.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            // 胶囊行走 FlowRow：英文标签更长，两枚挤一行会超出 268dp 被裁掉（Row 不换行也不报错）。
            // 一枚都没有时整块不画 —— 空的 FlowRow 也会带走一段 padding
            val deepenChip = source == GraphSource.REST && shallowLocal && localRepoExists && onDeepen != null
            if (truncated || deepenChip) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    if (truncated) {
                        PanelChip(
                            label = stringResource(R.string.action_load_more),
                            enabled = !loadingMore,
                            onClick = {
                                val from = source ?: return@PanelChip
                                scope.launch {
                                    loadingMore = true
                                    val more = withContext(Dispatchers.IO) {
                                        fetchPage(nextGraphPage(from, commits), from)
                                    }
                                    if (more != null) {
                                        val seen = commits.map { it.fullSha }.toHashSet()
                                        val fresh = more.filter { it.fullSha !in seen }
                                        commits = commits + fresh
                                        truncated = more.size >= PAGE_SIZE
                                        Logger.net(
                                            "提交图 ▸ $owner/$repo 加载更早：+${fresh.size}（共 ${commits.size}，来源 $from）",
                                            GIT_WORKBENCH_LOG_TAG,
                                        )
                                    }
                                    loadingMore = false
                                }
                            },
                        )
                    }
                    if (deepenChip) {
                        onDeepen?.let {
                            PanelChip(
                                label = stringResource(R.string.action_deepen_history),
                                enabled = true,
                                onClick = it,
                            )
                        }
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

/**
 * 一条提交：gutter 画泳道 + 右侧内容（短 sha · 标题 · 作者/时间）。
 *
 * 点它 → 这条提交的**本地 diff**（`diff_commit`，[onClick] 为 null 时不可点）。
 * 这是**只读**动作，所以留在面板的出口体系里，不需要走决策页。
 */
@Composable
private fun CommitRow(row: GraphCommitRow, onClick: (() -> Unit)? = null) {
    // 颜色必须在**组合期**取好再传进绘制 lambda：`Primer.XXX` 是 @Composable getter，
    // 绘制 lambda 不是组合上下文（ContributionWall 那一轮的教训）。
    val lanes = Primer.GraphLanes
    val panelBg = Primer.BackgroundPrimary
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 4.dp),
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
                row.commit.subject.ifBlank { stringResource(R.string.state_commit_no_subject) },
                fontSize = 12.sp,
                color = Primer.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${row.commit.shortSha} · ${row.commit.author}",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Primer.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (row.commit.unpushed) {
                    // 未推送标记**只用文字**，不去改节点的画法：虚线圈已经是「未提交」虚节点
                    // 在用的语法（§4.3），再拿空心 / 虚线表示「未推送」就是两件事抢一套形状。
                    // 颜色用 WarningText（文字安全的橙）：工作区档的改动文件状态字母也是这族的橙。
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.label_unpushed_short),
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = Primer.WarningText,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** gutter 宽度（泳道画在左侧固定列里，右侧才是文字）。 */
private val GUTTER = 30.dp

/**
 * 提交图一行的高度。**internal**：宿主的档级骨架（`GitPanelViewHost` 的 `PanelAreaSkeleton`）
 * 要按同一个行高铺 —— 两处各写一个 34，改一处就会漂。
 */
internal val GRAPH_ROW_HEIGHT = 34.dp
internal const val GRAPH_ROW_HEIGHT_DP = 34
private val ROW_HEIGHT = GRAPH_ROW_HEIGHT

/** 与 [GUTTER] 对应的像素值（绘制 lambda 里拿到的是 px）。 */
private const val GUTTER_PX = 30f
