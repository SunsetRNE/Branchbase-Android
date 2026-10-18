package com.branchbase.ui.adaptive

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Branchbase 的布局判定规则。
 *
 * 规则只依赖 [WindowSpec] 与“这条栏有几个槽位/要不要文字”，是纯函数：
 * 同一输入永远同一输出，没有 Android 上下文、没有副作用，可直接 JVM 单测。
 *
 * 底栏的“比例怪”根因是整宽等分：旧实现用 `可用宽 / 槽位数` 直接算格宽，
 * 1280dp 平板上两个 Tab 每格能得到 600dp+，图标仍只有 22dp。这里做三件事：
 * 1. 给整条栏一个 [NavPlan.maxWidth] 上限，多出来的宽度留在屏幕两侧；
 * 2. 给单格一个 [NavPlan.maxCellWidth] 上限，槽位少时栏自然收窄；
 * 3. 用 [NavMetrics.compression] 驱动图标/标签，而不是让格子无限拉宽。
 */
object AdaptiveLayoutPolicy {

    /** 平板上悬浮栏的最大宽度；再宽就会变成一条横贯全屏的“白条”。 */
    private val TabletBarMaxWidth = 560.dp

    /** 手机上栏与屏幕边缘的留白。 */
    private val PhoneBarMargin = 12.dp

    fun plan(window: WindowSpec): LayoutPlan {
        val margin = when (window.widthClass) {
            WidthClass.Compact -> PhoneBarMargin
            WidthClass.Medium -> 24.dp
            WidthClass.Expanded -> 32.dp
        }
        val maxWidth = when (window.widthClass) {
            WidthClass.Compact -> (window.widthDp.dp - margin * 2).coerceAtLeast(160.dp)
            // 中/大屏不做“整宽等分”，一律封顶后居中。
            WidthClass.Medium, WidthClass.Expanded -> TabletBarMaxWidth
        }
        val nav = NavPlan(
            maxWidth = maxWidth,
            maxCellWidth = when (window.widthClass) {
                WidthClass.Compact -> 96.dp
                WidthClass.Medium, WidthClass.Expanded -> 120.dp
            },
            idealCellWidth = 88.dp,
            containerPadding = 4.dp,
            height = 64.dp,
            iconSize = when (window.widthClass) {
                WidthClass.Compact -> 22.dp
                WidthClass.Medium, WidthClass.Expanded -> 24.dp
            },
            labelTextSize = when (window.widthClass) {
                WidthClass.Compact -> 10.sp
                WidthClass.Medium, WidthClass.Expanded -> 11.sp
            },
            horizontalMargin = margin,
        )
        val content = ContentPlan(
            maxWidth = when (window.widthClass) {
                WidthClass.Compact -> null
                WidthClass.Medium -> 720.dp
                WidthClass.Expanded -> 840.dp
            },
            horizontalPadding = when (window.widthClass) {
                WidthClass.Compact -> 16.dp
                WidthClass.Medium -> 24.dp
                WidthClass.Expanded -> 32.dp
            },
        )
        return LayoutPlan(window = window, nav = nav, content = content)
    }

    /**
     * 把 [NavPlan] 落到一条具体栏上。
     *
     * @param itemCount 槽位数（导航项 + 可选尾部手柄；手柄也占一格）
     * @param wantsLabels 调用点是否希望显示文字（主界面图标指向性足够，传 false）
     */
    fun metrics(nav: NavPlan, itemCount: Int, wantsLabels: Boolean): NavMetrics {
        val count = itemCount.coerceAtLeast(1)
        val availableDp = (nav.maxWidth.value - nav.containerPadding.value * 2).coerceAtLeast(0f)
        val cellDp = (availableDp / count).coerceAtMost(nav.maxCellWidth.value)
        val barWidthDp = (cellDp * count + nav.containerPadding.value * 2)
            .coerceAtMost(nav.maxWidth.value)
        val compression = (cellDp / nav.idealCellWidth.value).coerceIn(0.60f, 1.25f)
        return NavMetrics(
            barWidth = barWidthDp.dp,
            cellWidth = cellDp.dp,
            compression = compression,
            // 标签需要可用宽度；48dp 的窄格只画图标，避免文字挤压/截断。
            showLabels = wantsLabels && cellDp >= 72f,
            iconSize = (nav.iconSize.value * compression).dp.coerceIn(18.dp, 26.dp),
        )
    }
}
