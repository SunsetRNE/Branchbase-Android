package com.branchbase.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 主界面（首页 / 消息）的悬浮玻璃导航栏。
 *
 * 形状、材质、透镜、降级全在 [GlassBar] 里 —— 三处底部导航（主界面 / 仓库页 / 个人页）
 * 共用同一份实现，这里只负责把 [NavDestination] 翻译成 [GlassBarItem]。
 *
 * 主界面 / 仓库页 / 个人页**一律不显示文字**（2026-11-25 决策）：底栏只留图标，
 * 读屏名仍由 `labelRes` 提供 —— 视觉上干净，无障碍语义不丢。
 */
@Composable
fun GlassNavigationBar(
    selected: NavDestination,
    onSelect: (NavDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassBar(
        items = NavDestination.entries.map { dest ->
            GlassBarItem(
                icon = dest.icon,
                labelRes = dest.labelRes,
                selected = dest == selected,
                onClick = { onSelect(dest) },
            )
        },
        modifier = modifier,
    )
}
