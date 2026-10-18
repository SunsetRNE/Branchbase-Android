package com.branchbase.ui.adaptive

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit

/**
 * 一次布局判定的完整结果。
 *
 * 所有字段都是不可变 token；页面/导航栏只读它，不反向修改，也不在叶子里
 * 再读窗口尺寸。这样同一份输入必得同一份输出，单测可以覆盖整个矩阵。
 */
@Immutable
data class LayoutPlan(
    val window: WindowSpec,
    val nav: NavPlan,
    val content: ContentPlan,
)

/**
 * 导航栏的窗口级参数。
 *
 * 真正的栏宽/格宽还要乘上“这一条栏有几个槽位”，由 [AdaptiveLayoutPolicy.metrics]
 * 计算。这里只放不随槽位数变化的基准：最大宽、单格上限、理想格宽、内边距。
 */
@Immutable
data class NavPlan(
    /** 栏容器最大宽度；平板上栏不会铺满整屏。 */
    val maxWidth: Dp,
    /** 单个槽位的宽度上限；槽位少时栏自然收窄，而不是把图标拉开。 */
    val maxCellWidth: Dp,
    /** 压缩比例的基准格宽：compression = 实际格宽 / idealCellWidth。 */
    val idealCellWidth: Dp,
    /** 容器内边距（栏内容与玻璃边缘的留白）。 */
    val containerPadding: Dp,
    /** 栏高（玻璃容器高度）。 */
    val height: Dp,
    /** 基准图标尺寸；最终尺寸会乘压缩比例并 clamp。 */
    val iconSize: Dp,
    /** 文字标签字号；只有 [NavMetrics.showLabels] 为 true 时才绘制。 */
    val labelTextSize: TextUnit,
    /** 栏与屏幕左右边缘的期望留白（悬浮形态下由壳子/栏自己处理）。 */
    val horizontalMargin: Dp,
)

/** 页面内容区的窗口级参数；本阶段先下发，页面后续逐步接入。 */
@Immutable
data class ContentPlan(
    /** null = 不限宽（紧凑屏/代码 Diff 等需要整宽的页面）。 */
    val maxWidth: Dp?,
    val horizontalPadding: Dp,
)

/**
 * 一条具体导航栏（已知槽位数）的最终度量。
 *
 * [compression] 就是“压缩比例值”：窄屏 <1（压缩），宽屏 >1（但栏宽已被封顶）。
 * 它同时驱动图标尺寸与标签显隐，避免出现“平板上的超大手机底栏”。
 */
@Immutable
data class NavMetrics(
    val barWidth: Dp,
    val cellWidth: Dp,
    val compression: Float,
    val showLabels: Boolean,
    val iconSize: Dp,
)
