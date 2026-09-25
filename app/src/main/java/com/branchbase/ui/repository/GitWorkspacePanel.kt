package com.branchbase.ui.repository

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import com.branchbase.R
import com.branchbase.ui.theme.Primer
import com.branchbase.ui.theme.SkeletonBar
import com.branchbase.ui.theme.SkeletonRows
import com.branchbase.ui.theme.skeletonRowsFor

/** 面板视图的统一宽度：气泡贴着屏幕角落，再宽就顶到正文了。 */
private val PANEL_WIDTH = 268.dp

// 三个列表档的行高真源在各自的档文件里（`GRAPH_ROW_HEIGHT` / `REF_ROW_HEIGHT` /
// `HISTORY_ROW_HEIGHT`，都是 internal）—— 档级骨架与真实行必须同高，两处各写一个数就会漂。

/**
 * 面板视图档的宿主：**容器 + 标签条 + 内容**。
 *
 * 有哪几档、哪一档能用，由 [GitPanelKind] 说了算（`available`）；未落地的档**不装死** ——
 * 标签上标「待接入」，点进去是一句如实的说明（[GitPanelViewPlaceholder]），
 * 而不是一个点了没反应的入口（这条是本仓库的既有口径）。
 *
 * 档与档之间是**同层切换** → `PanelSwitcher` 走 fade-through（不位移，见
 * [`git-mode-design.md`](../../../../../../docs/specs/git-mode-design.md) §3.3）。
 *
 * 本地仓库目录在这里按 `repo` **算一次**（[localRepoDir]），不叫两个宿主各传一份 ——
 * 「引用树」档要按目录读 `local_branches` / `remote_branches`，这个路径写错的表现是
 * 「面板说没有引用、其实仓库是好的」，只有真机上才看得出来。
 *
 * @param refreshTick 宿主自己的刷新计数（工作区档由它驱动重读；引用树档同样跟着它重读；
 *   提交图档也靠它重读 —— 加深成功后就是这一变把图翻回本地来源）
 * @param onOpenBranches 去分支管理（切 / 建 / 删都落那边的决策流程）。**null = 这个宿主没有这个出口**
 *   —— 面板里就不画那枚胶囊，而不是画一个点了没反应的（本仓库的既有口径）
 * @param onDeepen 加深克隆（unshallow）的出口。**null = 不画那枚胶囊**；
 *   真正的长任务由宿主跑（任务中心 + 进度弹窗），面板这一族源码里**不许**出现写操作
 *   （见 `GitWorkbenchWiringTest`）
 * @param onOpenDiff 看某个改动文件的**本地 diff**（工作区档的行）。null = 行不可点
 * @param onOpenCommitDiff 看某条提交的**本地 diff**（提交图 / 文件历史档的行）。null = 行不可点
 * @param onMerge 去**合并决策页**（工作区档的「合并分支…」胶囊）。null = 不画那枚胶囊
 * @param onResumeMerge 去**冲突详情页**（合并中状态条的「继续」）。null = 不画那枚胶囊
 * @param onAbortMerge 放弃合并（合并中状态条的「放弃」）。**跑动作的是宿主** ——
 *   面板这一族源码里不许出现任何 git 写方法（`GitWorkbenchWiringTest` 扫全表）
 * @param filePath 「文件历史」档要看哪个文件：文件页传正在看的那个路径，代码页传 null
 *   （那时这一档如实说明去哪看，而不是显示一个空列表）
 */
@Composable
fun GitPanelViewHost(
    kind: GitPanelKind,
    onSelect: (GitPanelKind) -> Unit,
    git: LocalRepoGitState,
    refreshTick: Int,
    host: String,
    token: String,
    owner: String,
    repo: String,
    onRefresh: () -> Unit,
    onOpenSync: () -> Unit,
    onOpenBranches: (() -> Unit)? = null,
    onDeepen: (() -> Unit)? = null,
    onOpenDiff: ((String) -> Unit)? = null,
    onOpenCommitDiff: ((String) -> Unit)? = null,
    onMerge: (() -> Unit)? = null,
    onResumeMerge: (() -> Unit)? = null,
    onAbortMerge: (() -> Unit)? = null,
    filePath: String? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repoDir = remember(repo) { localRepoDir(context, repo) }
    Column(
        modifier
            .width(PANEL_WIDTH)
            .clip(RoundedCornerShape(16.dp))
            .background(Primer.BackgroundPrimary)
            .border(1.dp, Primer.Border, RoundedCornerShape(16.dp))
            .padding(10.dp),
    ) {
        GitPanelTabs(current = kind, onSelect = onSelect)
        Spacer(Modifier.height(8.dp))
        // ── 内容区：**固定高度**（按档取，见 panelViewAreaHeight） ──
        //
        // 这是「取数前后面板高度不变」的实现点：骨架 / 空态 / 内容 / 失败**都活在这个盒子里**，
        // 所以数据到达时变的是盒子里的东西，不是盒子本身。此前骨架只有 120dp 而内容能到 300dp+，
        // 于是每次取数都会把整个面板撑高一次（`git-mode-design.md` §3.5 的现场）。
        // clipToBounds：骨架行数按盒子高度算（`skeletonRowsFor`），多出来的也不许画到面板外
        Box(Modifier.fillMaxWidth().height(panelViewAreaHeight(kind)).clipToBounds()) {
            // 本地仓库快照还没读到时**先不出内容**：`git.loaded == false` 意味着
            // 「还不知道有没有本地副本」，拿 `exists = false` 去渲染工作区档会先画一句
            // 「未拉取到本地」，拿它去择源会让提交图先白取一次 REST（见 `LocalRepoGitState.loaded`）
            if (!git.loaded) {
                PanelAreaSkeleton(kind)
            } else {
                when (kind) {
                    GitPanelKind.Workspace -> GitWorkspaceBody(
                        git = git,
                        onRefresh = onRefresh,
                        onOpenSync = onOpenSync,
                        onOpenBranches = onOpenBranches,
                        onOpenDiff = onOpenDiff,
                        onMerge = onMerge,
                        onResumeMerge = onResumeMerge,
                        onAbortMerge = onAbortMerge,
                    )
                    GitPanelKind.Graph -> CommitGraphPanel(
                        host = host,
                        token = token,
                        owner = owner,
                        repo = repo,
                        branch = git.branch,
                        repoDir = repoDir,
                        localRepoExists = git.exists,
                        refreshTick = refreshTick,
                        dirtyCount = git.dirtyCount,
                        onOpenWorkspace = { onSelect(GitPanelKind.Workspace) },
                        onOpenSync = onOpenSync,
                        onDeepen = onDeepen,
                        onOpenCommitDiff = onOpenCommitDiff,
                    )
                    GitPanelKind.FileHistory -> GitFileHistoryPanel(
                        host = host,
                        token = token,
                        owner = owner,
                        repo = repo,
                        branch = git.branch,
                        repoDir = repoDir,
                        localRepoExists = git.exists,
                        // 代码页没有「当前文件」：null 时这一档如实说明去哪看（面板里不显示空列表）
                        filePath = filePath,
                        refreshTick = refreshTick,
                        onOpenCommitDiff = onOpenCommitDiff,
                        onDeepen = onDeepen,
                    )
                    GitPanelKind.Refs -> GitRefsPanel(
                        repoDir = repoDir,
                        localRepoExists = git.exists,
                        refreshTick = refreshTick,
                        onRefresh = onRefresh,
                        onOpenSync = onOpenSync,
                        onOpenBranches = onOpenBranches,
                    )
                }
            }
        }
    }
}

/**
 * 档内容「没有东西可说」（空仓库 / 取数失败）时的居中区。
 *
 * 内容区是**固定高度**的（见 [panelViewAreaHeight]）：不居中的话，一个空仓库会在 330dp 的面板里
 * 顶着一行小字，看起来像没加载出来。调用方传 `Modifier.weight(1f)`（吃掉其余空间），
 * 页脚之类的固定元素仍然留在底部。
 */
@Composable
internal fun PanelEmptyArea(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { content() }
}

/**
 * 视图档内容区的高度（**按档固定**）：骨架、空态、内容、失败态都在这个尺寸里。
 *
 * 数值取自各档原本的内容上限 + 页脚（列表 `heightIn(max=…)` 的时代）：
 * 提交图 260+70、引用树 240+40、文件历史 220+40、工作区约 280。
 * 取整之后四档各自是一个稳定的「工作台尺寸」——
 * 面板不再因为「取数回来了」而改变大小（§3.5），代价是内容很短时会留白：
 * 留白是**稳定**的，撑高是**跳动**的，这次返工选前者。
 */
internal fun panelViewAreaHeight(kind: GitPanelKind): Dp = when (kind) {
    GitPanelKind.Workspace -> 280.dp
    GitPanelKind.Graph -> 330.dp
    GitPanelKind.Refs -> 280.dp
    GitPanelKind.FileHistory -> 260.dp
}

/**
 * 「本地仓库快照还没读到」时的档级占位（一次面板打开最多出现几十毫秒）。
 *
 * 它按档的形状铺满内容区（工作区多两条「分支行 / 胶囊」的横条），行高与真实行同源 ——
 * 目的是让随后的内容**填在同一个尺寸里**，而不是好看。
 */
@Composable
private fun PanelAreaSkeleton(kind: GitPanelKind) {
    // 区域高度取自 [panelViewAreaHeight] 这个真源：写死数字的话，改档高时骨架会先漂
    val area = panelViewAreaHeight(kind).value.toInt()
    Column(Modifier.fillMaxSize()) {
        when (kind) {
            GitPanelKind.Workspace -> {
                SkeletonBar(height = 16.dp)
                Spacer(Modifier.height(8.dp))
                SkeletonBar(height = 26.dp)
                Spacer(Modifier.height(10.dp))
                // 减去上面三条横条与间距（16+8+26+10 = 60）
                SkeletonRows(
                    rows = skeletonRowsFor(areaDp = area - 60, rowDp = 28, gapDp = 2),
                    rowHeight = 28.dp,
                    gap = 2.dp,
                )
            }
            GitPanelKind.Graph -> SkeletonRows(
                rows = skeletonRowsFor(areaDp = area, rowDp = GRAPH_ROW_HEIGHT_DP),
                rowHeight = GRAPH_ROW_HEIGHT,
            )
            GitPanelKind.Refs -> SkeletonRows(
                rows = skeletonRowsFor(areaDp = area, rowDp = REF_ROW_HEIGHT_DP, gapDp = 1),
                rowHeight = REF_ROW_HEIGHT,
                gap = 1.dp,
            )
            GitPanelKind.FileHistory -> SkeletonRows(
                rows = skeletonRowsFor(areaDp = area, rowDp = HISTORY_ROW_HEIGHT_DP, gapDp = 2),
                rowHeight = HISTORY_ROW_HEIGHT,
                gap = 2.dp,
            )
        }
    }
}

/**
 * 分档标签条（工作区 / 提交图 / 引用树 / 文件历史）。
 *
 * 未落地的档仍然可点 —— 点进去是一句「按阶段接入」的说明，**不是死按钮**；
 * 视觉上靠标签右侧的「待接入」小字与弱化文字色区分（禁用必须给出路，与设置页同一口径）。
 */
@Composable
private fun GitPanelTabs(current: GitPanelKind, onSelect: (GitPanelKind) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GitPanelKind.entries.forEach { k ->
            val selected = k == current
            val fg = when {
                selected -> Color.White
                k.available -> Primer.TextSecondary
                else -> Primer.TextTertiary
            }
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) Primer.Blue500 else Primer.Gray100)
                    .clickable { onSelect(k) }
                    .padding(horizontal = 7.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(gitPanelKindLabel(k), fontSize = 10.5.sp, fontWeight = FontWeight.Medium, color = fg)
                if (!k.available) {
                    Spacer(Modifier.width(3.dp))
                    Text(stringResource(R.string.state_git_view_pending), fontSize = 8.5.sp, color = fg)
                }
            }
        }
    }
}

/**
 * Git 工作台 —— **「工作区」档**（[GitPanelKind.Workspace]）的正文。
 *
 * 它是「HEAD 之上还没提交的那一层」：`repo_status.dirty` 的文件清单 + 当前分支 / 领先落后 / 上游。
 * 数据与徽标**同源**（[LocalRepoGitState]，一次 `repo_status`）—— 面板上下的数字必须一致，
 * 各读一份是这个仓库已经踩过的坑（消息页显示模式两个入口各持一份 `remember`）。
 *
 * **不是**提交页：提交要走暂存勾选（P0-2 决策页）与身份检查（P0-3），那些入口按阶段接入，
 * 这里只如实说明，不放一个点了会跳到别处的假按钮。
 *
 * ## 改动清单点开看 **diff**（1.0.99 起可点）
 *
 * 1.0.96 一度接过「点一行 → 打开那个文件」，写完才发现**目的地是错的**：文件查看器读的是
 * `GET /repos/{o}/{r}/contents/{path}`（**远端**，见 `RepositoryFileViewer.kt:199`），
 * 而这一档列的是**本地改动** —— 点开看到的是没改过的那一份，比点不动更坏，于是退回并登记。
 *
 * 现在的落点是**本地 diff 页**（`LocalDiffScreen`：`diff_worktree` 的输出，
 * 见 [`git-mode-design.md`](../../../../../../docs/specs/git-mode-design.md) §11.6 的选型）：
 * 点一行看的就是那个文件相对 HEAD 改了什么。这一档的硬约束没变 ——
 * **不做「点开看到另一份内容」的入口**，所以这里只在宿主给了 `onOpenDiff` 时才让行可点。
 *
 * ## 有后果的动作都只是**出口**
 *
 * 分支管理（切 / 建 / 删）与同步都不在这一档里执行，只把用户送到既有页面 ——
 * 「面板内直接执行（安全）/ 走决策页（有后果）」这条分档见 `git-mode-design.md` §6.1，
 * 执法者是 `GitWorkbenchWiringTest`（面板源码里不许出现 git 写操作）。
 *
 * @param onOpenDiff 打开某个改动文件的**本地 diff**（null = 这个宿主没有这个出口 → 行不可点）
 * @param onMerge 去合并决策页（null = 这个宿主没有这个出口 → **不画那枚胶囊**）
 * @param onResumeMerge 去冲突详情页（合并中才有意义；null = 不画）
 * @param onAbortMerge 放弃合并 —— **面板只给出口，跑它的是宿主**（见 `GitWorkbenchWiringTest`）
 */
@Composable
fun GitWorkspaceBody(
    git: LocalRepoGitState,
    onRefresh: () -> Unit,
    onOpenSync: () -> Unit,
    onOpenBranches: (() -> Unit)? = null,
    onOpenDiff: ((String) -> Unit)? = null,
    onMerge: (() -> Unit)? = null,
    onResumeMerge: (() -> Unit)? = null,
    onAbortMerge: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        // ── 头：分支 + 领先 / 落后（数字全部来自同一份快照） ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                git.branch.ifBlank { "—" },
                fontSize = 11.5.sp,
                fontFamily = FontFamily.Monospace,
                color = Primer.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val synced = when {
                git.ahead > 0 && git.behind > 0 -> stringResource(R.string.state_diverged_badge)
                git.ahead > 0 -> "↑${git.ahead}"
                git.behind > 0 -> "↓${git.behind}"
                else -> null
            }
            if (synced != null) {
                Text(
                    synced,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (git.diverged) Primer.WarningText else Primer.TextTertiary,
                )
            }
        }
        Text(
            if (git.hasUpstream && git.upstream.isNotBlank()) git.upstream else stringResource(R.string.state_no_upstream_short),
            fontSize = 10.5.sp,
            color = Primer.TextTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )

        // ── 合并中：这一档最先要说的是「现在停在哪儿」，不是「工作区干不干净」──
        // 停在合并中时工作区**必然**是脏的（冲突文件带标记），先列改动清单只会让人更糊涂。
        // 两条出路都只在这里给出口：真正的写操作在宿主（面板里零写操作，§6.1）
        if (git.merging) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.state_merging_in_panel, git.mergeConflicts),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                color = Primer.WarningText,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 6.dp),
            ) {
                onResumeMerge?.let {
                    PanelChip(stringResource(R.string.action_resolve_conflicts), enabled = true, onClick = it)
                }
                onAbortMerge?.let {
                    PanelChip(stringResource(R.string.action_abort_merge), enabled = true, onClick = it)
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── 内容：改动文件清单 / 干净 ──
        if (!git.exists) {
            Text(
                stringResource(R.string.state_local_repo_missing),
                fontSize = 12.sp,
                color = Primer.TextTertiary,
                modifier = Modifier.padding(vertical = 10.dp),
            )
        } else if (git.dirty.isEmpty()) {
            Text(
                stringResource(R.string.state_workspace_clean),
                fontSize = 12.sp,
                color = Primer.TextTertiary,
                modifier = Modifier.padding(vertical = 10.dp),
            )
        } else {
            Text(
                stringResource(R.string.label_changed_files_scope),
                fontSize = 10.5.sp,
                color = Primer.TextTertiary,
            )
            Spacer(Modifier.height(4.dp))
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 148.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(git.dirty, key = { it.path }) { f ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            // 没有出口就不让行可点：画一个「按下去有反馈、结果什么都没发生」的行
                            // 比不让点更坏（本仓库的既有口径）
                            .then(
                                if (onOpenDiff != null) Modifier.clickable { onOpenDiff(f.path) }
                                else Modifier,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            f.status.take(1),
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Primer.Orange500,
                            modifier = Modifier.width(14.dp),
                        )
                        Text(
                            f.path,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Primer.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── 底：两个现在就真的能用的动作 + 一句实话 ──
        // 用 FlowRow 而不是 Row：英文标签长得多（"Local branch sync" / "Branch management"），
        // 三枚挤一行会超出 268dp 的面板宽度**被裁掉**（Row 不换行，也不报错）
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PanelChip(stringResource(R.string.action_refresh), enabled = true, onClick = onRefresh)
            PanelChip(stringResource(R.string.nav_local_branch_sync), enabled = git.exists, onClick = onOpenSync)
            onOpenBranches?.let {
                PanelChip(stringResource(R.string.nav_branch_manage), enabled = git.exists, onClick = it)
            }
            // 合并中时不给这枚入口：同时开着「合并」与「解决冲突」两条路，用户会以为要先合完这一次
            onMerge?.let {
                PanelChip(
                    stringResource(R.string.action_merge_branch),
                    enabled = git.exists && !git.merging,
                    onClick = it,
                )
            }
        }
        Text(
            stringResource(R.string.note_git_views_pending),
            fontSize = 10.sp,
            color = Primer.TextTertiary,
            lineHeight = 13.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** 面板里的一个小胶囊按钮（面板内不用 Material 的 TextButton：它的最小高度会把面板撑肿）。 */
@Composable
internal fun PanelChip(label: String, enabled: Boolean, onClick: () -> Unit) {
    val bg = if (enabled) Primer.Gray150 else Primer.Gray100
    val fg = if (enabled) Primer.TextPrimary else Primer.TextTertiary
    Row(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = fg)
    }
}

/**
 * **本档还没落地**时的说明（[GitPanelKind.available] = false）。
 *
 * 为什么要有它：视图枚举里那几档按阶段接入，而面板必须**现在就**能安全渲染任一档 ——
 * 不放一个「点了没反应」的入口，也不假装它已经能用。
 */
@Composable
fun GitPanelViewPlaceholder(kind: GitPanelKind, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text(
            gitPanelKindLabel(kind),
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = Primer.TextPrimary,
        )
        Text(
            stringResource(R.string.note_git_view_unavailable),
            fontSize = 11.sp,
            color = Primer.TextTertiary,
            lineHeight = 15.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** 各档的名字（标签条与占位共用一份，别在两处各写一遍）。 */
@Composable
internal fun gitPanelKindLabel(kind: GitPanelKind): String = when (kind) {
    GitPanelKind.Workspace -> stringResource(R.string.label_git_workspace)
    GitPanelKind.Graph -> stringResource(R.string.label_git_graph)
    GitPanelKind.Refs -> stringResource(R.string.label_git_refs)
    GitPanelKind.FileHistory -> stringResource(R.string.label_git_file_history)
}
