package com.branchbase.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 底部导航目标（4 个 Tab）。
 *
 * 对齐 docs/navbar-wireframe.md：图标 22dp，选中主蓝。
 */
enum class NavDestination(
    val label: String,
    val icon: ImageVector,
) {
    Home("Home", Icons.Filled.Home),
    Explore("探索", Icons.Filled.Explore),
    Notifications("通知", Icons.Filled.Notifications),
}