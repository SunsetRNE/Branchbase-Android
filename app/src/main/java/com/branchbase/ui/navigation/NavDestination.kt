package com.branchbase.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 底部导航目标（2 个 Tab）。
 *
 * 规格：图标 22dp，选中主蓝。
 * 说明：原「探索」Tab 只有占位页（`探索（待接入）`），已随占位页一并移除。
 */
enum class NavDestination(
    val label: String,
    val icon: ImageVector,
) {
    Home("Home", Icons.Filled.Home),
    Notifications("消息", Icons.Filled.Notifications),
}