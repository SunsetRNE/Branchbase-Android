package com.branchbase.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.branchbase.ui.theme.Primer

/**
 * 悬浮玻璃球导航栏（基础形态 ②）。
 *
 * 对齐 docs/navbar-wireframe.md：胶囊 56dp·圆角 28dp·离底 12dp，内项 44dp 圆。
 * 毛玻璃用半透明背景模拟（真正的 backdrop blur 后续可用 RenderEffect / haze 库实现）。
 */
@Composable
fun GlassNavigationBar(
    selected: NavDestination,
    onSelect: (NavDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .background(Color.White.copy(alpha = 0.35f))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavDestination.entries.forEach { dest ->
            val isSelected = dest == selected
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) Primer.Blue500 else Color.Transparent)
                    .clickable { onSelect(dest) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    dest.icon,
                    contentDescription = dest.label,
                    tint = if (isSelected) Color.White else Primer.IconPrimary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}