package com.branchbase.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.branchbase.ui.theme.Primer
import com.branchbase.ui.theme.selectionColor

/**
 * 液态玻璃悬浮导航栏。
 *
 * 背景由 [LiquidGlassSurface] 负责绘制，按钮仍由 Compose 承担，保证点击、语义
 * 和导航状态不被视觉层影响。
 */
@Composable
fun GlassNavigationBar(
    selected: NavDestination,
    onSelect: (NavDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { LiquidGlassSurface(it) },
            modifier = Modifier.matchParentSize(),
        )
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(28.dp))
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
                        .background(selectionColor(isSelected, on = Primer.Blue500))
                        .clickable { onSelect(dest) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        dest.icon,
                        contentDescription = stringResource(dest.labelRes),
                        tint = selectionColor(
                            isSelected,
                            on = Color.White,
                            off = Primer.IconPrimary,
                        ),
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}
