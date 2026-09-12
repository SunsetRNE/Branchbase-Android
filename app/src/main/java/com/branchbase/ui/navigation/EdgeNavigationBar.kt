package com.branchbase.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.ui.theme.selectionColor
import com.branchbase.ui.theme.Primer
import com.branchbase.ui.theme.bubbleEnter
import com.branchbase.ui.theme.bubbleExit
import com.branchbase.ui.theme.ElementMotion
import com.branchbase.ui.theme.rememberPressFeedback

/**
 * 侧边隐藏 + 弹出气泡导航栏（基础形态 ④）。
 *
 * 主项 + 右侧圆形手柄，点击手柄后隐藏选项以浮动气泡从右下角弹出。
 * 规格：手柄 40dp 圆，气泡 chip 40dp·gap 6dp。
 */
@Composable
fun EdgeNavigationBar(
    selected: NavDestination,
    onSelect: (NavDestination) -> Unit,
    extraActions: List<Pair<ImageVector, String>> = listOf(
        Icons.Filled.Star to "收藏",
        Icons.Filled.PushPin to "固定",
        Icons.Filled.Add to "新建",
    ),
    onExtraAction: (ImageVector) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        // 主项 + 手柄
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .background(Primer.BackgroundSecondary),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavDestination.entries.forEach { dest ->
                EdgeNavItem(
                    dest = dest,
                    selected = dest == selected,
                    onClick = { onSelect(dest) },
                    modifier = Modifier.weight(1f),
                )
            }
            // 圆形手柄（三点）：填充用 Gray150（中性面），Border 只做描边 ——
            // 之前把描边色当填充用，和设计里「Border 只用于 stroke」的用法冲突
            val press = rememberPressFeedback()
            val rotation = animateFloatAsState(
                targetValue = if (expanded) 90f else 0f,
                animationSpec = tween(ElementMotion.ICON_MS),
                label = "edge-more-rotation",
            )
            Box(
                modifier = Modifier
                    .padding(end = 10.dp)
                    .size(40.dp)
                    .graphicsLayer {
                        scaleX = press.scale.value
                        scaleY = press.scale.value
                    }
                    .clip(CircleShape)
                    .border(
                        1.dp,
                        selectionColor(expanded, on = Primer.Blue500, off = Primer.Border),
                        CircleShape,
                    )
                    .background(selectionColor(expanded, on = Primer.Blue500, off = Primer.Gray150))
                    .clickable(
                        interactionSource = press.interaction,
                        indication = LocalIndication.current,
                    ) { expanded = !expanded },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.MoreHoriz,
                    contentDescription = if (expanded) "收起更多" else "更多",
                    tint = selectionColor(expanded, on = Color.White, off = Primer.IconPrimary),
                    modifier = Modifier
                        .size(22.dp)
                        .graphicsLayer { rotationZ = rotation.value },
                )
            }
        }

        // 弹出气泡（隐藏选项）
        AnimatedVisibility(
            visible = expanded,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = 68.dp),
            // 与个人页的 More 气泡同一套原语（同一个锚点角落、同一时长）
            enter = bubbleEnter(),
            exit = bubbleExit(),
        ) {
            Column(
                modifier = Modifier
                    .background(Color.White, RoundedCornerShape(16.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                extraActions.forEach { (icon, label) ->
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Primer.BackgroundPrimary)
                            .clickable { onExtraAction(icon) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            icon,
                            contentDescription = label,
                            tint = Primer.IconPrimary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 侧边导航的主项（图标 + 文字，选中主蓝） */
@Composable
private fun EdgeNavItem(
    dest: NavDestination,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = if (selected) Primer.Blue500 else Primer.IconPrimary
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(dest.icon, contentDescription = dest.label, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(3.dp))
        Text(
            dest.label,
            fontSize = 11.sp,
            color = tint,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}