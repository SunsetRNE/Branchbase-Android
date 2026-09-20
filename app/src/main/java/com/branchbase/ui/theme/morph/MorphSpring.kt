package com.branchbase.ui.theme.morph

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

/**
 * 形变的**时间轴**规格：进度怎么走。
 *
 * ## 为什么单独一层
 *
 * 时间轴是**解耦**的：`MorphPlan` 只管「路径怎么变」，本文件只管「什么时候变到哪」。
 * 「动效不跟手」和「中间帧难看」是两个独立问题，别混着归因 ——
 * 前者调这里，后者只能改对应关系或换素材（换缓动曲线对中间帧的形状没有任何影响）。
 *
 * ## ζ 的取值
 *
 * 三个预设按阻尼比 ζ 命名（与 `morphicons` 的 `smooth / snappy / bouncy` 同一组 ζ）：
 *
 * | 预设 | ζ | 手感 | 用在哪 |
 * |------|---|------|--------|
 * | [Smooth] | 1.00 | 不过冲，收尾稳 | **默认**：图标切换是高频交互 |
 * | [Snappy] | 0.73 | 起手更快，过冲极小 | 需要「利落」的一次性状态切换 |
 * | [Bouncy] | 0.40 | 明显回弹 | 强调型操作；列表里反复出现时**会显得拖沓** |
 *
 * 默认选 [Smooth] 不是保守：`docs/specs/ui-design.md` §3 已经写明元素级动效**都不用回弹**
 * —— 列表里反复出现的元素，回弹第一次看是「活泼」，第十次就是「拖沓」。
 * [Bouncy] 留着是为了在**明确需要强调**的地方显式写出来，而不是让它成为默认观感。
 *
 * 弹簧与 tween 的差别在**可打断**：快速来回触发时，弹簧从当前值和当前速度续着走，
 * 不会「先跳回起点再重播」。`Animatable` 自带这个语义（见 `MorphIcon`）。
 */
enum class MorphSpring(
    /** 阻尼比 ζ。1.0 = 临界阻尼（不过冲），< 1 = 欠阻尼（过冲 / 回弹）。 */
    val dampingRatio: Float,
    /** 刚度（Compose 的 stiffness 单位）。 */
    val stiffness: Float,
    /** 中文短名，进文档与验收清单时用。 */
    val title: String,
) {
    Smooth(dampingRatio = 1.00f, stiffness = 380f, title = "平滑"),
    Snappy(dampingRatio = 0.73f, stiffness = 700f, title = "利落"),
    Bouncy(dampingRatio = 0.40f, stiffness = 560f, title = "回弹"),
    ;

    /** 转成 Compose 的弹簧规格（进度是 0..1 的 Float）。 */
    fun spec(): SpringSpec<Float> = spring(dampingRatio = dampingRatio, stiffness = stiffness)
}
