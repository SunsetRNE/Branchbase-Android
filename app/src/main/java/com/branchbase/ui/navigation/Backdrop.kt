package com.branchbase.ui.navigation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop

/**
 * 页面背景采样层 —— 由 [com.kyant.backdrop.backdrops.rememberLayerBackdrop] 持有。
 *
 * ## 这块为什么不自己写了（1.2.3 的第二次返工）
 *
 * 1.2.3 的第一版是自研的：壳子用 `GraphicsLayer` 录下整页、把图层与「录制原点」一起下发，
 * 栏自己 `translate(录制原点 − 自身原点)` 再 `drawLayer` + `Modifier.blur`。能出模糊，
 * 但**出不了玻璃**——因为真正的液态玻璃差在三处，而这三处都不是「自己写四十行」的量：
 *
 * 1. **折射**：按圆角 SDF 把边缘的背景像素**位移**（`coord + d * grad`），光才会在边上「弯」；
 * 2. **边缘高光**：SDF 法线点乘光源方向、`pow(abs(d), falloff)` 出的一条沿圆角流动的高光带；
 * 3. **深度与阴影**：玻璃的「厚度」是阴影与内阴影给的，不是一条描边给的。
 *
 * 上游 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)（Maven
 * `io.github.kyant0:backdrop`，Apache-2.0）把这三件事都用 AGSL `RuntimeShader` 做完了，
 * 而且它的采样链路（录整页 → 按两个节点的 `localPositionOf` 自己算偏移）与我们自研那版同构。
 * 酷安 16.6.4 的 `com.coolapk.market.widget.compose.liquid` 用的就是它（着色器逐字相同，
 * 取证见 `/root/Project-Integrated-Workspace/apk-lab/out/酷安-液态玻璃-取证报告.md`）。
 * 结论：**这一层不再自研**，改由库持有；我们只负责「给什么形状、糊多狠、染什么色、怎么降级」。
 *
 * ## 谁提供它
 *
 * 只有 `NavigationShell` 的**悬浮形态**下发：它给内容挂
 * [com.kyant.backdrop.backdrops.layerBackdrop]（把内容录进图层），再把图层经本 CompositionLocal
 * 交给栏。栏在别处被单用时读到 `null`，就老实退回「主题色玻璃」而不是崩。
 *
 * 类型是上游的 [Backdrop] 而**不是** `LayerBackdrop`：正文页在屏时壳子会换成一个「静止底」
 * （[com.kyant.backdrop.backdrops.rememberCanvasBackdrop] 画主题底色，见 [LiveBackdropGate]），
 * 那是另一种 `Backdrop` 实现 —— 换实现不换调用点，栏那边一行都不用改。
 */
val LocalBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * 采样安全阀：**页面上挂着 interop 正文视图（WebView）时，不要录内容**。
 *
 * ## 为什么需要它（2026-10-07 的现场：自述文件那一块在闪）
 *
 * 开了玻璃栏之后，壳子会把整页内容**每帧画两遍**：一遍正常上屏，一遍录进采样图层给栏当背景
 * （见 `NavigationShell` 的 `layerBackdrop`）。普通 Compose 内容画两遍是幂等的，无所谓；
 * 但 `WebView` 的画面来自 Chromium 的渲染 functor，**一帧只能被消费一次** ——
 * 同一帧里画第二遍时拿到的是空的或上一帧，观感就是「自述文件那一块在闪」。
 *
 * ## 为什么不给它加硬件层顶过去
 *
 * `setLayerType(LAYER_TYPE_HARDWARE)` 是这类「重复绘制」问题的常见解法，但这里**不能**用：
 * 正文 WebView 的高度等于整篇内容高度（`MAX_README_HEIGHT` 允许到 20 万 dp），
 * 远超 GPU 纹理上限，硬件层会整块渲染不出来 —— 拿「闪」换「白」是更糟的交换。
 *
 * ## 所以怎么做
 *
 * WebView 在**挂载期间** [enter]，壳子据此停掉录制，并把采样层换成一个画主题底色的静止底：
 * 栏仍然拿到 `drawBackdrop` 的全部材质（边缘高光、投影、圆角折射、模糊），只是身后不再透出
 * 正文。WebView 一卸载（切到别的 Tab / 离开仓库页）就 [exit]，活的采样自动回来。
 *
 * 这是**结构性**的：谁用 `ReadmeWebView` 谁就被覆盖，不需要维护一张「哪些页面有 WebView」的
 * 名单 —— 那种名单迟早会漏，而漏掉的表现就是闪。
 */
@Stable
object LiveBackdropGate {

    private var mounted = 0

    /** 现在允许「录内容」吗；`false` = 有 interop 正文在屏，壳子必须改用静止底。 */
    var allowed by mutableStateOf(true)
        private set

    /** 正文视图挂载时登记（在组合期调用，主线程）。 */
    fun enter() {
        mounted++
        allowed = false
    }

    /** 正文视图卸载时注销；最后一个走了才把采样放回来。 */
    fun exit() {
        mounted = (mounted - 1).coerceAtLeast(0)
        if (mounted == 0) allowed = true
    }
}

/**
 * 玻璃表面的可调参数（**唯一真源**；栏与源码级钉子都读这里）。
 *
 * 前几个是上游 `drawBackdrop` 的效果入参 —— 上游**不给默认值**（都要显式传），
 * 所以这里就是「我们的取值」：改观感只改这一处。
 */
object GlassBackdrop {

    /**
     * **未选中态**的模糊半径：整条栏的底面，把身后的页面像素糊掉多少。
     *
     * 24dp 是「看得出是背景、但读不出内容」的档位。再大会把背景糊成一块纯色，
     * 玻璃就退回成不透明色块；再小则会在玻璃里读出正文，喧宾夺主。
     */
    val BlurRadius: Dp = 24.dp

    /**
     * **选中态**（液态滑块）的模糊半径：几乎不糊，只留一点柔化。
     *
     * 「选中项透明、其余磨砂」那层对比就来自这里 —— 滑块与底面用的是**同一份采样**，
     * 差别只在这个半径与下面那组折射参数。0dp 像在栏上挖了个洞，2dp 才像贴在页面上的清玻璃。
     */
    val LensBlurRadius: Dp = 2.dp

    /**
     * 折射带宽度：从圆角边缘往里多少距离内做位移；超出这条带的像素**原样透出**。
     *
     * 56dp 高的胶囊取 12dp ≈ 五分之一，边缘那圈才有厚度感；取满高会把整块玻璃糊成凸透镜。
     */
    val RefractionHeight: Dp = 12.dp

    /**
     * 折射位移量：边缘像素最多被推开多少。
     *
     * 16dp 是「看得出弯、但背景不会明显错位」的档位。它比 [RefractionHeight] 大是故意的 ——
     * 上游 `lens()` 用 `circleMap` 做非线性映射，位移峰值出现在折射带中段。
     */
    val RefractionAmount: Dp = 16.dp

    /**
     * 是否叠「厚度方向」的折射分量（把位移再朝中心偏一点）。
     *
     * 开：像一块有厚度的板，边缘弯曲更立体；关：像一层贴纸。代价是同一像素多算一次法线。
     */
    const val RefractionDepthEffect: Boolean = true

    /**
     * 滑块滑动时的弹簧阻尼比：略小于 1，收尾带一点回弹。
     *
     * 液态观感一半来自**拉长**（见 `GlassNavigationBar` 的 lo/hi），一半来自这条收尾；
     * 再小会晃得像弹球，=1 则是机械地匀速停住。
     */
    const val LensDampingRatio: Float = 0.72f
}
