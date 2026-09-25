package com.branchbase.ui.repository

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.platform.LocalContext
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
 * Git 工作台 —— **「文件历史」档**（[GitPanelKind.FileHistory]）。
 *
 * 真源：[`git-mode-design.md`](../../../../../../docs/specs/git-mode-design.md) §3.2 与 D-e。
 *
 * ## 数据从哪来（与提交图同一套择源口径）
 *
 * | 来源 | 取数 | 分页键 | 什么时候用 |
 * |---|---|---|---|
 * | 本地 `log_file` | revwalk + 逐提交比对树（**只列真的碰过这个路径的提交**） | `skip` | 本地仓库存在**且不是浅克隆** |
 * | REST `/commits?path=` | 与提交列表同一个接口，多一个 `path` 参数 | 窗口内最老 sha | 其余情况（含浅克隆） |
 *
 * 浅克隆走 REST 的理由与提交图一样：本地只有 HEAD 一条提交，`log_file` 会给出
 * 「这个文件没有历史」这种**假话**。区别在于这一档还多一条后果 ——
 * **REST 来源的行不可点**（`diff_commit` 要本地对象库，那些提交本地没有）。
 *
 * ## 代码页怎么办（没有「当前文件」）
 *
 * 这一档要一个文件路径。文件页有（就是正在看的那个文件），代码页没有 ——
 * 那时**如实说明去哪看**，而不是显示一个空列表（空列表会被读成「这个文件没有历史」）。
 */
@Composable
fun GitFileHistoryPanel(
    host: String,
    token: String,
    owner: String,
    repo: String,
    branch: String,
    repoDir: String,
    localRepoExists: Boolean,
    filePath: String?,
    refreshTick: Int,
    onOpenCommitDiff: ((String) -> Unit)? = null,
    onDeepen: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (filePath.isNullOrBlank()) {
        Column(modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.label_git_file_history),
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = Primer.TextPrimary,
            )
            Text(
                stringResource(R.string.note_file_history_needs_file),
                fontSize = 11.sp,
                color = Primer.TextTertiary,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        return
    }

    var commits by remember { mutableStateOf<List<FileHistoryCommit>>(emptyList()) }
    var source by remember { mutableStateOf<FileHistorySource?>(null) }
    var shallowLocal by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var truncated by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val encoded = remember(filePath) { encodePath(filePath) }

    suspend fun fetchRest(sha: String?): List<FileHistoryCommit>? {
        val path = buildString {
            append("/repos/$owner/$repo/commits?path=$encoded&per_page=$PAGE_SIZE")
            if (!sha.isNullOrBlank()) append("&sha=$sha")
            else if (branch.isNotBlank()) append("&sha=${encodeRef(branch)}")
        }
        val json = RustBridge.getJson(host, token, path) ?: return null
        if (json.startsWith("ERROR:")) return null
        return parseRestFileHistory(json)
    }

    suspend fun fetchPage(page: FileHistoryPage, from: FileHistorySource): List<FileHistoryCommit>? =
        when (page) {
            is FileHistoryPage.Local ->
                RustBridge.gitLogFile(repoDir, filePath, PAGE_SIZE, page.skip)?.let(::parseLocalFileHistory)
            is FileHistoryPage.Rest -> fetchRest(page.sha)
        }

    /** 读第一页（含**退回 REST**）：首屏与「重试」共用一份（与提交图同一条口径）。 */
    suspend fun loadFirstPage() {
        loading = true
        error = null
        val shallow = withContext(Dispatchers.IO) { isShallowClone(repoDir) }
        shallowLocal = shallow
        var used = fileHistorySourceOf(localRepoExists, shallow)
        var localFailed = false
        var first = withContext(Dispatchers.IO) { fetchPage(nextFileHistoryPage(used, emptyList()), used) }
        if (first == null && used == FileHistorySource.LOCAL) {
            localFailed = true
            Logger.warn(
                LogCategory.NETWORK, GIT_WORKBENCH_LOG_TAG,
                "文件历史 ▸ 本地读取失败（$filePath），退回 REST",
            )
            used = FileHistorySource.REST
            first = withContext(Dispatchers.IO) { fetchPage(FileHistoryPage.Rest(null), FileHistorySource.REST) }
        }
        source = used
        if (first == null) {
            error = context.getString(
                R.string.error_file_history_load_failed,
                context.getString(
                    if (localFailed) R.string.error_graph_both_failed
                    else R.string.error_graph_remote_failed,
                ),
            )
            loading = false
            Logger.warn(
                LogCategory.NETWORK, GIT_WORKBENCH_LOG_TAG,
                "文件历史 ▸ $owner/$repo $filePath 取数失败（来源 $used）",
            )
            return
        }
        commits = first
        truncated = first.size >= PAGE_SIZE
        loading = false
        Logger.net(
            "文件历史 ▸ $owner/$repo $filePath：${first.size} 条" +
                "（来源 ${if (used == FileHistorySource.LOCAL) "本地" else "REST"}）" +
                if (truncated) "（还有更早）" else "",
            GIT_WORKBENCH_LOG_TAG,
        )
    }

    LaunchedEffect(host, token, owner, repo, branch, repoDir, localRepoExists, filePath, refreshTick) {
        loadFirstPage()
    }

    // 能不能点开看 diff：**由来源决定**（REST 来源本地没有那些对象），再叠上「宿主有没有给出口」
    val openDiff = onOpenCommitDiff?.takeIf { source?.let(::canOpenCommitDiff) == true }

    Column(modifier.fillMaxWidth()) {
        // 这是**哪个文件**的历史：面板里必须看得见，否则「文件历史」只是一个没有主语的列表
        Text(
            filePath,
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace,
            color = Primer.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = 6.dp),
        )

        if (loadStateOf(loading, commits.size) == LoadState.Loading) {
            // 骨架立刻出现并铺满宿主的内容区（浮层不适用「延迟现身」）；行数按实测高度算
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                SkeletonRows(
                    rows = skeletonRowsFor(maxHeight.value.toInt(), HISTORY_ROW_HEIGHT_DP, gapDp = 2),
                    rowHeight = HISTORY_ROW_HEIGHT,
                    gap = 2.dp,
                )
            }
            return@Column
        }
        error?.let {
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
        if (commits.isEmpty()) {
            PanelEmptyArea(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.state_file_history_empty),
                    fontSize = 12.sp,
                    color = Primer.TextTertiary,
                    textAlign = TextAlign.Center,
                )
            }
            return@Column
        }

        LazyColumn(
            state = rememberLazyListState(),
            // 取满剩余高度（宿主的内容区是固定高的，列表不再自己限高）
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            items(items = commits, key = { it.fullSha }) { c ->
                FileHistoryRow(commit = c, onClick = openDiff?.let { open -> { open(c.fullSha) } })
            }
        }

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
        // REST 来源说清两件事：为什么走远端，以及**为什么这些行点不开**（本地没有那些对象）
        if (source == FileHistorySource.REST && shallowLocal && localRepoExists) {
            Text(
                stringResource(R.string.note_file_history_remote),
                fontSize = 10.sp,
                color = Primer.TextTertiary,
                lineHeight = 13.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        val deepenChip = source == FileHistorySource.REST && shallowLocal && localRepoExists && onDeepen != null
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
                                    fetchPage(nextFileHistoryPage(from, commits), from)
                                }
                                if (more != null) {
                                    val seen = commits.map { it.fullSha }.toHashSet()
                                    val fresh = more.filter { it.fullSha !in seen }
                                    commits = commits + fresh
                                    truncated = more.size >= PAGE_SIZE
                                    Logger.net(
                                        "文件历史 ▸ $filePath 加载更早：+${fresh.size}（共 ${commits.size}，来源 $from）",
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

/** 一条提交：短 sha + 标题 + 作者。本地来源时可以点开看这次提交的 diff。 */
@Composable
private fun FileHistoryRow(commit: FileHistoryCommit, onClick: (() -> Unit)? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 5.dp),
    ) {
        Text(
            commit.subject.ifBlank { stringResource(R.string.state_commit_no_subject) },
            fontSize = 12.sp,
            color = Primer.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                commit.shortSha,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = Primer.TextTertiary,
            )
            if (commit.author.isNotBlank()) {
                Text(
                    " · ${commit.author}",
                    fontSize = 10.sp,
                    color = Primer.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 文件历史一行的高度：标题 12sp + 一行 meta 10sp + 上下各 5dp。**internal**：宿主的档级骨架同高。 */
internal val HISTORY_ROW_HEIGHT = 40.dp
internal const val HISTORY_ROW_HEIGHT_DP = 40

/** 文件历史一页的条数（与 `RustBridge.gitLogFile` 的默认值一致）。 */
private const val PAGE_SIZE = 50
