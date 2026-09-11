package com.branchbase.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.ui.theme.ElementMotion
import com.branchbase.ui.theme.Primer

/**
 * 普通底部导航栏（基础形态 ①）。
 *
 * 规格：栏高 60dp，4 Tab 均分，图标 22dp + 文字 11sp。
 * 选中指示器（胶囊背景）由 Material3 的 `NavigationBarItem` 自带过渡，不需要额外处理。
 */
@Composable
fun BranchbaseNavigationBar(
    selected: NavDestination,
    onSelect: (NavDestination) -> Unit,
    badgeCounts: Map<NavDestination, Int> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    NavigationBar(
        modifier = modifier,
        containerColor = Primer.BackgroundSecondary,
    ) {
        NavDestination.entries.forEach { dest ->
            val count = badgeCounts[dest] ?: 0
            NavigationBarItem(
                selected = dest == selected,
                onClick = { onSelect(dest) },
                icon = {
                    Box {
                        Icon(dest.icon, contentDescription = dest.label)
                        // 不再用 `if (count > 0)` 包住：那样徽标是「凭空出现、凭空消失」；
                        // 可见性交给 CountBadge 自己托管，未读清零时才有淡出
                        CountBadge(count, Modifier.align(Alignment.TopEnd))
                    }
                },
                label = { Text(dest.label, fontSize = 11.sp) },
            )
        }
    }
}

/**
 * 数量 badge（通用组件，可叠加到任意导航变体）。
 *
 * 规格：16dp 高胶囊，红底白字 10sp。出现/消失带缩放淡入淡出 ——
 * 未读数从 0 变 1 是很高频的事件，硬切会显得整条导航栏「抖了一下」。
 */
@Composable
fun CountBadge(
    count: Int,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = count > 0,
        enter = scaleIn(tween(ElementMotion.BADGE_MS), initialScale = ElementMotion.BADGE_SCALE) +
            fadeIn(tween(ElementMotion.BADGE_MS)),
        exit = scaleOut(tween(ElementMotion.BADGE_MS), targetScale = ElementMotion.BADGE_SCALE) +
            fadeOut(tween(ElementMotion.BADGE_MS)),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .background(Primer.Red500, RoundedCornerShape(8.dp))
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (count > 99) "99+" else "$count",
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
