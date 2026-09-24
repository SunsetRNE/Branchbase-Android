package com.branchbase.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.ui.graphics.vector.ImageVector
import com.branchbase.R

/**
 * 底部导航目标（2 个 Tab）。
 *
 * 规格：图标 22dp，选中主蓝。
 * 说明：原「探索」Tab 只有占位页（`探索（待接入）`），已随占位页一并移除。
 */
enum class NavDestination(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    Home(R.string.nav_home, Icons.Filled.Home),
    Notifications(R.string.nav_messages, Icons.Filled.Notifications),
}