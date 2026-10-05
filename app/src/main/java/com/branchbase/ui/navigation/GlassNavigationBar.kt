package com.branchbase.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import com.branchbase.ui.theme.LocalIsDarkTheme
import com.branchbase.ui.theme.Primer
import com.branchbase.ui.theme.selectionColor

/**
 * 液态玻璃悬浮导航栏。
 *
 * 背景由 [LiquidGlassSurface] 负责绘制，按钮仍由 Compose 承担，保证点击、语义
 * 和导航状态不被视觉层影响。
 *
 * 玻璃层是**原生 `View`**，读不到 Compose 主题，所以色组在这里按当前主题算好后
 * 经 `AndroidView.update` 下发（见 [glassTint]）—— 写死颜色的老做法在深色页面上
 * 会把导航栏变成一条亮带。
 */
@Composable
fun GlassNavigationBar(
    selected: NavDestination,
    onSelect: (NavDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = LocalIsDarkTheme.current
    val surface = Primer.BackgroundSecondary
    val ink = Primer.TextPrimary
    val tint = remember(dark, surface, ink) { glassTint(surface, ink, dark) }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { LiquidGlassSurface(it) },
            update = { it.tint = tint },
            // AndroidView 不能参与父级测量，否则会把导航槽位撑满；尺寸必须与下方 Row 一致。
            modifier = Modifier
                .width((NavDestination.entries.size * 44 + (NavDestination.entries.size - 1) * 4 + 12).dp)
                .height(56.dp),
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

/**
 * 从主题角色算出玻璃色组 —— 这是「玻璃什么颜色」的**唯一真源**。
 *
 * 参数只取两个角色（[surface] = `BackgroundSecondary`、[ink] = `TextPrimary`）加深浅标志：
 * - **浅色**：白玻璃（顶部高光在两段白之间过渡）+ [surface] 收底 + [ink] 压一层极淡的
 *   下沿暗色（玻璃厚度感）；
 * - **深色**：以 [surface]（`#161B22`）为主体，白只以 0.06~0.20 的 alpha 参与高光与描边 ——
 *   原来是固定 172/255 的白叠在深底上，那正是「深色下一条亮带」的来源。
 *
 * 底色都不用纯黑/纯白顶替，是为了跟色板走：换主题色板时玻璃自动跟着换。
 */
private fun glassTint(surface: Color, ink: Color, dark: Boolean): LiquidGlassSurface.Tint =
    if (dark) {
        LiquidGlassSurface.Tint(
            top = surface.copy(alpha = 0.92f).toArgb(),
            upper = surface.copy(alpha = 0.72f).toArgb(),
            lower = surface.copy(alpha = 0.80f).toArgb(),
            bottom = Color.Black.copy(alpha = 0.34f).toArgb(),
            sheen = Color.White.copy(alpha = 0.10f).toArgb(),
            rimTop = Color.White.copy(alpha = 0.20f).toArgb(),
            rimBottom = Color.White.copy(alpha = 0.06f).toArgb(),
        )
    } else {
        LiquidGlassSurface.Tint(
            top = Color.White.copy(alpha = 0.70f).toArgb(),
            upper = Color.White.copy(alpha = 0.50f).toArgb(),
            lower = surface.copy(alpha = 0.34f).toArgb(),
            bottom = ink.copy(alpha = 0.10f).toArgb(),
            sheen = Color.White.copy(alpha = 0.55f).toArgb(),
            rimTop = Color.White.copy(alpha = 0.85f).toArgb(),
            rimBottom = Color.White.copy(alpha = 0.16f).toArgb(),
        )
    }
