package com.branchbase.ui.adaptive

import androidx.compose.runtime.Immutable

/**
 * 当前窗口的“事实”，不是“结论”。
 *
 * 只描述窗口有多宽、多高、密度和字体缩放；具体用哪种布局由
 * [AdaptiveLayoutPolicy] 判定。页面与导航栏都只消费判定后的 [LayoutPlan]，
 * 不再各自读 `LocalConfiguration.screenWidthDp` 写一套 if/else。
 */
@Immutable
data class WindowSpec(
    val widthDp: Int,
    val heightDp: Int,
    val density: Float,
    val fontScale: Float,
) {
    val widthClass: WidthClass
        get() = when {
            widthDp < 600 -> WidthClass.Compact
            widthDp < 840 -> WidthClass.Medium
            else -> WidthClass.Expanded
        }

    val heightClass: HeightClass
        get() = when {
            heightDp < 480 -> HeightClass.Compact
            heightDp < 900 -> HeightClass.Medium
            else -> HeightClass.Expanded
        }

    val isLandscape: Boolean get() = widthDp > heightDp
}

/** Material 3 的宽度断点：<600 / 600–839 / >=840（dp）。 */
enum class WidthClass { Compact, Medium, Expanded }

/** 高度断点：手机横屏时 height 常常 <480dp，底栏应让位给侧栏/更紧的形态。 */
enum class HeightClass { Compact, Medium, Expanded }
