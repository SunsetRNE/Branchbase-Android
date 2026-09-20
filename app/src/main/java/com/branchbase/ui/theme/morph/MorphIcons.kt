package com.branchbase.ui.theme.morph

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Tune

/**
 * 可形变配对的**台账**：哪个配对做路径插值、哪个明确不做，以及为什么。
 *
 * ## 这张表就是「极端组合清单」
 *
 * 形变难看的根源是「顶点对应关系在两个形状之间本身就是错的」—— 这是方法上限，不是参数问题。
 * 所以处理方式不是事后调参，而是**选素材**：能对上的写进 [Morph]（由单测钉住实测指标），
 * 对不上的写进 [MorphExpectation.Fallback]（就地降级成交叉过渡）。
 *
 * | 配对 | viewBox | 子路径 | 段数 | 闭合 | 结论 |
 * |------|---------|--------|------|------|------|
 * | `KeyboardArrowDown ⇄ KeyboardArrowUp` | 24 → 24 | 1 → 1 | 6 → 6 | 闭合 → 闭合 | 形变（实测 travel 0.76） |
 * | `Add ⇄ Remove` | 24 → 24 | 1 → 1 | 12 → 4 | 闭合 → 闭合 | 形变（点数不齐由重采样兜底，实测 travel 0.67） |
 * | `Tune ⇄ Close` | 24 → 24 | **6 → 1** | 36 → 12 | — | **降级**：子路径数量不等，中间帧会凭空多线 |
 *
 * 「段数 6 → 6」这条不是巧合：两个箭头是同一套画法的镜像（起笔点、绕向、闭合状态都一致），
 * 这正是**跟设计师提需求时该说的话** —— 需要同网格、同绕向、同闭合状态、路径段数一致的两个 SVG。
 *
 * 顺带一个反直觉的实测：这对箭头的 Procrustes 拟合残差高达 **0.71**（它们是**镜像**关系，
 * 旋转 + 等比缩放根本对不上），可它们的中间帧是「翻过去」而不是「拧过去」（见 `MorphPairTest`）。
 * 所以**残差不能拿来当门控**：它衡量的是「能不能用一个刚体变换对上」，而不是「顶点对应对不对」。
 * 真正决定成败的是移动量与中间帧形态。
 *
 * ## 为什么不让它自动判断一切
 *
 * 结构性的东西（子路径数、闭合性、描边/填充）自动拦，因为它们有客观对错；
 * 「中间帧好不好看」没有可靠的标量阈值，所以写成**显式清单 + 单测预算**：
 * `MorphPairTest` 会跑真实图标，把 `travel` / 残差 / 中间帧形态钉在数字上。
 * 谁把图标换坏了，测试当场红，而不是等用户在真机上看到中间帧拧麻花。
 */
object MorphIcons {

    /** 下拉/展开箭头：收起 ↓、展开 ↑。带菜单的按钮都该用它。 */
    val ChevronDownUp = MorphPair(
        name = "chevron-down⇄chevron-up",
        from = Icons.Filled.KeyboardArrowDown,
        to = Icons.Filled.KeyboardArrowUp,
        expectation = MorphExpectation.Morph,
    )

    /** 加号 ⇄ 减号：折叠区的展开/收起按钮。 */
    val PlusMinus = MorphPair(
        name = "add⇄remove",
        from = Icons.Filled.Add,
        to = Icons.Filled.Remove,
        expectation = MorphExpectation.Morph,
    )

    /**
     * 过滤 ⇄ 关闭：**明确不做形变**的例子，留着是为了让降级路径有真实用例。
     *
     * `Tune` 是三条推子（每条推子 = 线 + 圆点两条子路径，共 **6** 条），`Close` 是一个叉（**1** 条）。
     * 子路径数量不等时，顶点对应关系根本没有定义：中间帧只能「凭空多出五条线」再消失。
     * 这类配对改交叉过渡，视觉上干净得多。
     */
    val FilterOpen = MorphPair(
        name = "tune⇄close",
        from = Icons.Filled.Tune,
        to = Icons.Filled.Close,
        expectation = MorphExpectation.Fallback(
            "子路径数量不等：Tune 6 条 vs Close 1 条，中间帧会凭空多线/并线",
        ),
    )

    /** 台账全量：单测按这份清单逐对校验「声明」与「实测」一致。 */
    val all: List<MorphPair> = listOf(ChevronDownUp, PlusMinus, FilterOpen)
}
