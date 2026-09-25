package com.branchbase.ui.repository

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.R
import com.branchbase.ui.theme.Primer

/** 面板视图的统一宽度：气泡贴着屏幕角落，再宽就顶到正文了。 */
private val PANEL_WIDTH = 268.dp

/**
 * 面板视图档的宿主：**容器 + 标签条 + 内容**。
 *
 * 有哪几档、哪一档能用，由 [GitPanelKind] 说了算（`available`）；未落地的档**不装死** ——
 * 标签上标「待接入」，点进去是一句如实的说明（[GitPanelViewPlaceholder]），
 * 而不是一个点了没反应的入口（这条是本仓库的既有口径）。
 *
 * 档与档之间是**同层切换** → `PanelSwitcher` 走 fade-through（不位移，见
 * [`git-mode-design.md`](../../../../../../docs/specs/git-mode-design.md) §3.4）。
 */
@Composable
fun GitPanelViewHost(
    kind: GitPanelKind,
    onSelect: (GitPanelKind) -> Unit,
    git: LocalRepoGitState,
    host: String,
    token: String,
    owner: String,
    repo: String,
    onRefresh: () -> Unit,
    onOpenSync: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
        when (kind) {
            GitPanelKind.Workspace -> GitWorkspaceBody(git = git, onRefresh = onRefresh, onOpenSync = onOpenSync)
            GitPanelKind.Graph -> CommitGraphPanel(
                host = host,
                token = token,
                owner = owner,
                repo = repo,
                branch = git.branch,
                dirtyCount = git.dirtyCount,
                onOpenWorkspace = { onSelect(GitPanelKind.Workspace) },
                onOpenSync = onOpenSync,
            )
            else -> GitPanelViewPlaceholder(kind)
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
 */
@Composable
fun GitWorkspaceBody(
    git: LocalRepoGitState,
    onRefresh: () -> Unit,
    onOpenSync: () -> Unit,
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            PanelChip(stringResource(R.string.action_refresh), enabled = true, onClick = onRefresh)
            Spacer(Modifier.width(6.dp))
            PanelChip(stringResource(R.string.nav_local_branch_sync), enabled = git.exists, onClick = onOpenSync)
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
private fun PanelChip(label: String, enabled: Boolean, onClick: () -> Unit) {
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
