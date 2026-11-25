package com.branchbase.ui.adaptive

import androidx.compose.ui.unit.dp

/**
 * Branchbase 的布局判定规则。
 *
 * 规则只依赖 [WindowSpec] 与「这条栏有几个槽位」，是纯函数：
 * 同一输入永远同一输出，没有 Android 上下文、没有副作用，可直接 JVM 单测。
 *
 * 底栏的“比例怪”根因是整宽等分：旧实现用 `可用宽 / 槽位数` 直接算格宽，
 * 1280dp 平板上两个 Tab 每格能得到 600dp+，图标仍只有 22dp。这里做三件事：
 * 1. 给整条栏一个 [NavPlan.maxWidth] 上限，多出来的宽度留在屏幕两侧；
 * 2. 给单格一个 [NavPlan.maxCellWidth] 上限，槽位少时栏自然收窄；
 * 3. 用 [NavMetrics.compression] 驱动图标尺寸，而不是让格子无限拉宽。
 */
object AdaptiveLayoutPolicy {

    /** 平板上悬浮栏的最大宽度；再宽就会变成一条横贯全屏的“白条”。 */
    private val TabletBarMaxWidth = 560.dp

    /** 手机上栏与屏幕边缘的留白。 */
    private val PhoneBarMargin = 12.dp

    /**
     * 底栏统一高度（系统手势条内边距之外）。
     *
     * 2026-11-25 的「整体比例」决策把两族底栏收到同一档：
     * - **原生栏**：Material3 `NavigationBar` 的 80dp → 48dp（砍掉 2/5）；
     * - **悬浮玻璃栏**：`GlassBackdrop.ContainerHeight` 的 64dp → 48dp（砍掉 1/4）。
     *
     * 个人页 / 仓库页的整宽气泡栏原来各写 60dp / 56dp，也一并落到这一档 ——
     * 三处底栏在页面之间来回切时高度不再跳，是这次「整体比例」的另一半。
     *
     * 高度不含 `navigationBarsPadding()`：手势条那块由各栏自己留，见 `NavigationShell` 的文件头。
     */
    val BottomBarHeight = 48.dp

    /**
     * 玻璃栏滑块与容器上下的内边距；滑块高度 = 栏高 - 2 × 它。
     *
     * 与 [BottomBarHeight] 一起构成玻璃材质的**尺寸真源**：`GlassBackdrop` 直接引用这两个值，
     * 栏、录制层、滑块与「栏占掉多高」就不会各算各的。
     */
    val BottomBarPadding = 3.dp

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
        // 手机横屏（height <480dp）竖向空间紧：栏与上下留白一起收窄，正文多留一点高度。
        // 栏高本身已经是 48dp 档（见 [BottomBarHeight]），横屏只再收紧上下留白。
        val compactHeight = window.heightClass == HeightClass.Compact
        val nav = NavPlan(
            maxWidth = maxWidth,
            maxCellWidth = when (window.widthClass) {
                WidthClass.Compact -> 96.dp
                WidthClass.Medium, WidthClass.Expanded -> 120.dp
            },
            idealCellWidth = 88.dp,
            containerPadding = BottomBarPadding,
            height = BottomBarHeight,
            pillHeight = BottomBarHeight - BottomBarPadding * 2,
            verticalSlot = if (compactHeight) 4.dp else 8.dp,
            iconSize = when {
                compactHeight -> 20.dp
                window.widthClass == WidthClass.Compact -> 22.dp
                else -> 24.dp
            },
            horizontalMargin = margin,
        )
        val content = ContentPlan(
            maxWidth = when (window.widthClass) {
                WidthClass.Compact -> null
                WidthClass.Medium -> 720.dp
                // 平板横屏给 840dp（列表/卡片），竖屏收一点到 760dp（长文更好读）。
                WidthClass.Expanded -> if (window.isLandscape) 840.dp else 760.dp
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
     * 底栏统一为「只留图标」（2026-11-25 决策）之后，这里不再有标签显隐这一维：
     * 槽位只需要一个能放下图标、又能被拇指点到的宽度。
     *
     * @param itemCount 槽位数（导航项 + 可选尾部手柄；手柄也占一格）
     */
    fun metrics(nav: NavPlan, itemCount: Int): NavMetrics {
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
            iconSize = (nav.iconSize.value * compression).dp.coerceIn(18.dp, 26.dp),
        )
    }
}
