package com.branchbase.ui.repository

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.ui.theme.Primer

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
 * Git 功能气泡按钮面板（对齐 `docs/navbar-wireframe.md` ④ 的气泡规格）。
 *
 * 折叠态 = 右下角 52dp 圆手柄（可带徽标）；展开态 = 自下而上的 40dp 圆图标 + 白色标签胶囊
 * （圆角 16dp、间距 6dp、阴影 2dp），点击空白处收起。
 *
 * **用法**：作为覆盖层放在屏幕根 `Box` 的最后一个子项（本组件自带 `fillMaxSize` 的
 * 透明遮罩，展开时拦截点击以收起面板）。
 *
 * 设计意图（对齐「编辑之后不重复点开设置」）：把提交模式切换、推送/拉取、分支管理这些
 * 原本只在「设置 → 本地仓库」里才有的入口，直接挂到代码页/文件页，编辑完就地执行。
 */
@Composable
fun GitBubblePanel(
    actions: List<GitBubbleAction>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    handleIcon: ImageVector = Icons.Filled.MoreVert,
    handleBadge: String? = null,
    alignment: Alignment = Alignment.BottomEnd,
    edgePadding: PaddingValues = PaddingValues(end = 16.dp, bottom = 76.dp),
) {
    val interaction = remember { MutableInteractionSource() }
    // 顶部停靠时向下展开，底部停靠时向上展开
    val topDocked = alignment == Alignment.TopEnd

    Box(modifier.fillMaxSize()) {
        // 展开时铺一层透明遮罩：点空白收起，同时避免误触下层内容
        if (expanded) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = { onExpandedChange(false) },
                    ),
            )
        }

        Column(
            Modifier.align(alignment).padding(edgePadding),
            horizontalAlignment = Alignment.End,
        ) {
            if (topDocked) {
                BubbleHandle(expanded, handleIcon, handleBadge) { onExpandedChange(!expanded) }
                Spacer(Modifier.size(if (expanded) 10.dp else 0.dp))
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(expandFrom = if (topDocked) Alignment.Top else Alignment.Bottom),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    title?.takeIf { it.isNotBlank() }?.let { BubbleTitle(it) }
                    actions.forEach { action ->
                        BubbleActionRow(action) {
                            if (!action.keepOpen) onExpandedChange(false)
                            action.onClick()
                        }
                    }
                }
            }

            if (!topDocked) {
                Spacer(Modifier.size(if (expanded) 10.dp else 0.dp))
                BubbleHandle(expanded, handleIcon, handleBadge) { onExpandedChange(!expanded) }
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
        Box(
            Modifier
                .size(52.dp)
                .shadow(6.dp, CircleShape)
                .clip(CircleShape)
                .background(Primer.Blue500)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (expanded) Icons.Filled.Close else handleIcon,
                contentDescription = if (expanded) "收起 Git 面板" else "展开 Git 面板",
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
