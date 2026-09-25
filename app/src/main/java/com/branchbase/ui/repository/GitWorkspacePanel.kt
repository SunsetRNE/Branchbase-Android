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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.R
import com.branchbase.ui.theme.Primer

/**
 * Git 工作台 —— **「工作区」档**（[GitPanelKind.Workspace]）。
 *
 * ## 这一档是什么（以及不是什么）
 *
 * 它是「HEAD 之上还没提交的那一层」：`repo_status.dirty` 的文件清单 + 当前分支 / 领先落后 / 上游。
 * 数据与徽标**同源**（[LocalRepoGitState]，一次 `repo_status`）—— 面板上下的数字必须一致，
 * 各读一份是这个仓库已经踩过的坑（消息页显示模式两个入口各持一份 `remember`）。
 *
 * **不是**提交页：提交要走暂存勾选（P0-2 决策页）与身份检查（P0-3），
 * 那些入口按阶段接入（[`git-mode-design.md`](../../../../../../docs/specs/git-mode-design.md) §9），
 * 这里只如实说明，不放一个点了会跳到别处的假按钮。
 *
 * ## 尺寸
 *
 * 面板自己带 `heightIn(max = 220.dp)` 上限并在内部滚动：气泡面板贴着屏幕角落，
 * 让它随仓库大小长到半屏高会把底下的正文顶没（动效规格见 §3.4，
 * 尺寸变化由外层 `animateContentSize` 接管，这里只给上限）。
 */
@Composable
fun GitWorkspacePanel(
    git: LocalRepoGitState,
    onRefresh: () -> Unit,
    onOpenSync: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .width(268.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Primer.BackgroundPrimary)
            .border(1.dp, Primer.Border, RoundedCornerShape(16.dp))
            .padding(12.dp),
    ) {
        // ── 头：分支 + 领先 / 落后 + 工作区改动数（数字全部来自同一份快照） ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.label_git_workspace),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Primer.TextPrimary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                git.branch.ifBlank { "—" },
                fontSize = 11.5.sp,
                fontFamily = FontFamily.Monospace,
                color = Primer.TextSecondary,
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
 * **本档还没落地**时占位（[GitPanelKind.available] = false）。
 *
 * 为什么要有它：视图枚举里那三档（提交图 / 引用树 / 文件历史）按阶段接入，
 * 而面板必须**现在就**能安全渲染任一档 —— 不放一个「点了没反应」的入口，
 * 也不假装它已经能用（这条是本仓库的既有口径：禁用必须给出路）。
 */
@Composable
fun GitPanelViewPlaceholder(kind: GitPanelKind, modifier: Modifier = Modifier) {
    Column(
        modifier
            .width(268.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Primer.BackgroundPrimary)
            .border(1.dp, Primer.Border, RoundedCornerShape(16.dp))
            .padding(12.dp),
    ) {
        Text(
            gitPanelKindLabel(kind),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = Primer.TextPrimary,
        )
        Text(
            stringResource(R.string.note_git_view_unavailable),
            fontSize = 11.5.sp,
            color = Primer.TextTertiary,
            lineHeight = 15.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** 各档的中文名（面板内标题与占位共用一份，别在两处各写一遍）。 */
@Composable
internal fun gitPanelKindLabel(kind: GitPanelKind): String = when (kind) {
    GitPanelKind.Workspace -> stringResource(R.string.label_git_workspace)
    GitPanelKind.Graph -> stringResource(R.string.label_git_graph)
    GitPanelKind.Refs -> stringResource(R.string.label_git_refs)
    GitPanelKind.FileHistory -> stringResource(R.string.label_git_file_history)
}
