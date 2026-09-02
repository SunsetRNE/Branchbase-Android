package com.branchbase.ui.navigation

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
import com.branchbase.ui.theme.Primer

/**
 * 普通底部导航栏（基础形态 ①）。
 *
 * 对齐 docs/navbar-wireframe.md：栏高 60dp，4 Tab 均分，图标 22dp + 文字 11sp。
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
                        if (count > 0) {
                            CountBadge(count, Modifier.align(Alignment.TopEnd))
                        }
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
 * 对齐 docs/navbar-wireframe.md：16dp 高胶囊，红底白字 10sp。
 */
@Composable
fun CountBadge(
    count: Int,
    modifier: Modifier = Modifier,
) {
    if (count <= 0) return
    Box(
        modifier = modifier
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