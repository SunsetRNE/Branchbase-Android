package com.branchbase.ui.repository

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.R
import com.branchbase.ui.profile.CommitMode
import com.branchbase.ui.navigation.PageMotion
import com.branchbase.ui.navigation.PanelSwitcher
import com.branchbase.ui.theme.AnimatedStateIcon
import com.branchbase.ui.theme.ElementMotion
import com.branchbase.ui.theme.rememberPressFeedback
import com.branchbase.ui.theme.Primer

/**
 * Git 悬浮球该不该出现：**只有「本地仓库（Git）」模式**。
 *
 * 这个球管的全是本地仓库的事 —— 工作树改动数、领先 / 落后、本地分支同步、
 * 以及「把改动落到本地 git」的提交入口。另外两种模式（单文件 / 多文件）直接调远端
 * API 提交，本地根本没有工作树：球挂在那里只会白挡正文，并且给出
 * 「本地仓库未拉取」这类与当前模式无关的动作。
 *
 * 抽成纯函数是为了能单测三种模式的判定（真机上要看「切模式后球有没有立刻消失」，
 * 光靠肉眼回归很容易漏），调用点只做 `if (showGitBubble(mode))`。
 */
internal fun showGitBubble(mode: CommitMode?): Boolean = mode == CommitMode.LOCAL_REPO

/**
 * Git 气泡按钮面板中的一个操作。
 *
 * @param badge 右侧小徽标（如工作区改动数 / 领先提交数），null = 不显示
 * @param keepOpen 点击后是否保持展开（例如打开子页面/弹窗的操作）
 */
data class GitBubbleAction(
    val key: String,
    val label: String,
    val icon: ImageVector,
    val badge: String? = null,
    val enabled: Boolean = true,
    val danger: Boolean = false,
    val keepOpen: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * Git 功能气泡按钮面板 —— **Git 模式的工作台**（三档，见 [GitPanelStage]）。
 *
 * ```
 * 折叠（52dp 圆手柄，可带徽标）
 *   └─ 点球 ──► 动作列表（现有的 40dp 圆图标 + 标签胶囊，点空白收起）
 *                  └─ 点「工作区」等 ──► 视图档（工作台内容，面板内换框）
 * ```
 *
 * **用法**：作为覆盖层放在屏幕根 `Box` 的最后一个子项（本组件自带 `fillMaxSize` 的
 * 透明遮罩，展开时拦截点击以收起面板）。
 *
 * ## 三档之间的切换动效
 *
 * 走 [PanelSwitcher]（与页面级共用 [PageMotion] 常量）：进档 = 新内容滑入面板宽度的 1/10 + 淡入、
 * 旧内容原地淡出；同层 = fade-through（两段不重叠）。**收起**仍然走原来的
 * `AnimatedVisibility(expand/shrink)` —— 那是「面板出现 / 消失」，不是「换框」。
 *
 * 尺寸变化（动作列表矮、视图高）交给 `animateContentSize`，规格与
 * [`git-mode-design.md`](../../../../../../docs/specs/git-mode-design.md) §3.4 一致。
 *
 * 设计意图（对齐「编辑之后不重复点开设置」）：把提交模式切换、推送/拉取、分支管理这些
 * 原本只在「设置 → 本地仓库」里才有的入口，直接挂到代码页/文件页，编辑完就地执行；
 * 视图档再把这些动作的**上下文**（工作区 / 图 / 引用 / 文件历史）摊开。
 *
 * @param view 视图档的内容槽：按 [GitPanelKind] 渲染。默认给「本档还没落地」的占位 ——
 *   调用方接错档时不会白屏，也不会假装能用。
 */
@Composable
fun GitBubblePanel(
    actions: List<GitBubbleAction>,
    stage: GitPanelStage,
    onStageChange: (GitPanelStage) -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    handleIcon: ImageVector = Icons.Filled.MoreVert,
    handleBadge: String? = null,
    alignment: Alignment = Alignment.BottomEnd,
    edgePadding: PaddingValues = PaddingValues(end = 16.dp, bottom = 76.dp),
    view: @Composable (GitPanelKind) -> Unit = { GitPanelViewPlaceholder(it) },
) {
    val interaction = remember { MutableInteractionSource() }
    // 顶部停靠时向下展开，底部停靠时向上展开
    val topDocked = alignment == Alignment.TopEnd
    val expanded = stage != GitPanelStage.Collapsed

    // 收起时 `AnimatedVisibility` 还要把内容播完退场 —— 用**最后一次展开的档**渲染它，
    // 否则退场途中会先切回动作列表（看起来像「收起来了又弹一下」）。
    var lastStage by remember { mutableStateOf<GitPanelStage>(GitPanelStage.Actions) }
    if (stage != GitPanelStage.Collapsed) lastStage = stage

    Box(modifier.fillMaxSize()) {
        // 展开时铺一层透明遮罩：点空白收起，同时避免误触下层内容
        if (expanded) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = { onStageChange(GitPanelStage.Collapsed) },
                    ),
            )
        }

        Column(
            Modifier.align(alignment).padding(edgePadding),
            horizontalAlignment = Alignment.End,
        ) {
            if (topDocked) {
                BubbleHandle(expanded, handleIcon, handleBadge) {
                    onStageChange(if (expanded) GitPanelStage.Collapsed else GitPanelStage.Actions)
                }
                Spacer(Modifier.size(if (expanded) 10.dp else 0.dp))
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(expandFrom = if (topDocked) Alignment.Top else Alignment.Bottom),
                exit = fadeOut() + shrinkVertically(),
            ) {
                PanelSwitcher(
                    state = lastStage,
                    depthOf = ::panelDepth,
                    modifier = Modifier.animateContentSize(
                        animationSpec = tween(ElementMotion.REVEAL_MS, easing = PageMotion.EnterEasing),
                    ),
                    label = "git-panel",
                ) { s ->
                    when (s) {
                        is GitPanelStage.View -> view(s.kind)
                        else -> Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            title?.takeIf { it.isNotBlank() }?.let { BubbleTitle(it) }
                            actions.forEach { action ->
                                BubbleActionRow(action) {
                                    if (!action.keepOpen) onStageChange(GitPanelStage.Collapsed)
                                    action.onClick()
                                }
                            }
                        }
                    }
                }
            }

            if (!topDocked) {
                Spacer(Modifier.size(if (expanded) 10.dp else 0.dp))
                BubbleHandle(expanded, handleIcon, handleBadge) {
                    onStageChange(if (expanded) GitPanelStage.Collapsed else GitPanelStage.Actions)
                }
            }
        }
    }
}

/** 手柄：折叠=展开，展开=收起。 */
@Composable
private fun BubbleHandle(
    expanded: Boolean,
    handleIcon: ImageVector,
    handleBadge: String?,
    onClick: () -> Unit,
) {
    Box(contentAlignment = Alignment.Center) {
        // 手柄按下反馈：52dp 的圆缩到 0.94，点下去有「按到了」的确认感
        val press = rememberPressFeedback()
        Box(
            Modifier
                .size(52.dp)
                .graphicsLayer {
                    scaleX = press.scale.value
                    scaleY = press.scale.value
                }
                .shadow(6.dp, CircleShape)
                .clip(CircleShape)
                .background(Primer.Blue500)
                .clickable(
                    interactionSource = press.interaction,
                    indication = LocalIndication.current,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedStateIcon(
                icon = if (expanded) Icons.Filled.Close else handleIcon,
                contentDescription = if (expanded) stringResource(R.string.action_collapse_git_panel) else stringResource(R.string.action_expand_git_panel),
                tint = Color.White,
                modifier = Modifier.size(24.dp),
            )
        }
        if (!expanded && !handleBadge.isNullOrBlank()) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Primer.Red500)
                    .border(2.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(handleBadge, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }
}

@Composable
private fun BubbleTitle(text: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Primer.Gray900.copy(alpha = 0.86f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(text, fontSize = 11.sp, color = Color.White, maxLines = 2, lineHeight = 15.sp)
    }
}

@Composable
private fun BubbleActionRow(action: GitBubbleAction, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
        modifier = Modifier.clickable(enabled = action.enabled, onClick = onClick),
    ) {
        // 标签胶囊
        Row(
            Modifier
                .shadow(2.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(if (action.enabled) Primer.BackgroundPrimary else Primer.Gray150)
                .border(1.dp, Primer.Gray200, RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                action.label,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = when {
                    !action.enabled -> Primer.TextTertiary
                    action.danger -> Primer.Red500
                    else -> Primer.TextPrimary
                },
            )
            action.badge?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (action.danger) Primer.Red100 else Primer.Gray150)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                    Text(
                        it,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (action.danger) Primer.Red500 else Primer.TextSecondary,
                    )
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        // 40dp 圆图标
        Box(
            Modifier
                .size(40.dp)
                .shadow(2.dp, CircleShape)
                .clip(CircleShape)
                .background(
                    when {
                        !action.enabled -> Primer.Gray150
                        action.danger -> Primer.Red500
                        else -> Primer.BackgroundPrimary
                    },
                )
                .border(1.dp, Primer.Gray200, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                action.icon,
                contentDescription = action.label,
                tint = when {
                    !action.enabled -> Primer.IconSecondary
                    action.danger -> Color.White
                    else -> Primer.IconPrimary
                },
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
