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
 * 酷安 16.6.4 的 `com.coolapk.market.widget.compose.liquid` 用的就是它（着色器逐字相同）。
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
 * 液态玻璃栏的可调参数（**唯一真源**；栏、物理层与源码级钉子都读这里）。
 *
 * ## 取值出处：上游 catalog 的 `LiquidBottomTabs` + `LiquidBottomTab`
 *
 * 圆整到上游 `65ab177`（tag `2.0.1`）那一版的示例值，**不是我们拍的**：
 * 64dp 容器 / 56dp 滑块、4dp 内边距、`vibrancy() → blur(8dp) → lens(24dp, 24dp)` 的容器、
 * `lens(10dp, 14dp, chromaticAberration)` 的滑块、按压时 `78/56` 的横向拉伸与 `1.2` 的图标放大。
 *
 * 上游 `drawBackdrop` 的效果入参**没有默认值**（都要显式传），所以这些数字必须有个家；
 * 散在调用点就必然改漏一处 —— 这个对象就是那个家。
 *
 * ## 与 1.2.3 那版「两档模糊」的关系
 *
 * 旧版把「选中 / 未选中」的差别做成 `blur(24dp)` 与 `blur(2dp)` 两个半径。移植后
 * **选中态不再靠模糊档位表达**：滑块几乎不糊，它把身后那张「染成强调色的录制层」折射出来，
 * 于是选中项是**彩色 + 边缘色散**的，未选项是**去色磨砂**的 —— 对比更强，而且不用两套半径。
 * 底色那档（[ContainerBlurRadius]）仍在，管的是整条栏的磨砂。
 */
object GlassBackdrop {

    // ── 尺寸 ────────────────────────────────────────────────────────────────

    /** 容器高度（含上下各 [BarPadding]）—— 上游 `LiquidBottomTabs` 的 64dp。 */
    val ContainerHeight: Dp = 64.dp

    /** 滑块高度 —— 上游的 56dp，正好等于容器减去上下内边距。 */
    val PillHeight: Dp = 56.dp

    /** 内容与容器边缘的留白 —— 上游的 4dp，三处共用。 */
    val BarPadding: Dp = 4.dp

    // ── 容器（整条栏的玻璃） ────────────────────────────────────────────────

    /**
     * 容器模糊半径：整条栏的底面把身后的页面像素糊掉多少。
     *
     * 上游取 8dp（比 1.2.3 那版的 24dp 轻得多）——因为这一版的层次感由**滑块折射**给，
     * 底面糊太狠会把整页背景压成一块纯色，滑块折射出来的彩色也就没东西可衬。
     */
    val ContainerBlurRadius: Dp = 8.dp

    /** 容器折射带宽度：从圆角边缘往里多少距离内做位移（上游 24dp）。 */
    val ContainerRefractionHeight: Dp = 24.dp

    /** 容器折射位移量：边缘像素最多被推开多少（上游 24dp）。 */
    val ContainerRefractionAmount: Dp = 24.dp

    /**
     * 容器底色不透明度（主题色的 alpha 系数）—— 上游 `Color(0xFFFAFAFA).copy(0.4f)` / `0xFF121212`。
     *
     * 底色不降下来，采进来的背景会被自己盖住，那次整页离屏绘制就白花了。
     */
    const val ContainerColorAlpha: Float = 0.4f

    /** 按住整条栏时横向拉伸到 `pressedScale` 的倍率（上游 `78f / 56f`）。 */
    const val PressScaleFactor: Float = 78f / 56f

    /**
     * 按住时整条栏**横向**多出来的宽度（上游 `16.dp`）。
     *
     * 它加在宽度上、再换算成缩放比，所以窄屏不会变成铁板、宽屏不会变成橡皮筋。
     */
    val PressStretch: Dp = 16.dp

    // ── 滑块（液态透镜） ────────────────────────────────────────────────────

    /**
     * 滑块折射带宽度（静止时为 0，按下后乘上 pressProgress）。
     *
     * 静止时滑块**不折射**：它只是把身后那张染色录制层原样透出来；一按下才开始弯，
     * 于是「按下去有厚度」这件事由折射的**出现**表达，而不是由颜色变化表达。
     */
    val PillRefractionHeight: Dp = 10.dp

    /** 滑块折射位移量（按下时乘上 pressProgress）。 */
    val PillRefractionAmount: Dp = 14.dp

    /** 按下时被滑块的图标放大倍率（上游 `lerp(1f, 1.2f, pressProgress)`）。 */
    const val TabPressedScale: Float = 1.2f

    /** 拖动时整条栏跟随位移的最大值（上游 `4.dp * fraction.sign * EaseOut(abs(fraction))`）。 */
    val PanelShift: Dp = 4.dp

    /** 静止时压在滑块上的中性薄层 alpha（上游浅色用黑 0.1、深色用白 0.1）。 */
    const val PillScrimAlpha: Float = 0.1f

    /** 按下时追加的黑色薄层 alpha（上游 `0.03f * progress`）。 */
    const val PillPressScrimAlpha: Float = 0.03f

    /** 滑块内阴影半径（上游 `8.dp * pressProgress`）—— 「厚度」的向内那一半。 */
    val PillInnerShadowRadius: Dp = 8.dp

    // ── 物理 ────────────────────────────────────────────────────────────────

    /** 滑块位移弹簧的可见阈值。 */
    const val VisibilityThreshold: Float = 0.001f

    /** 按下形变的 X 阻尼比（上游 `spring(0.6f, 250f)`）—— 略欠阻尼，横向回弹更明显。 */
    const val ScaleXDampingRatio: Float = 0.6f

    /** 按下形变的 Y 阻尼比（上游 `spring(0.7f, 250f)`）—— 比 X 收得快，形变才像被拉长。 */
    const val ScaleYDampingRatio: Float = 0.7f

    /** 指针高光的面亮度基准（上游 AGSL 版 `Color.White.copy(0.08f * progress)`）。 */
    const val HighlightFillAlpha: Float = 0.08f

    /** 指针高光的圆斑亮度基准（上游 `Color.White.copy(0.15f * progress)`）。 */
    const val HighlightSpotAlpha: Float = 0.15f

    /** 指针高光半径系数（上游 AGSL 版 `size.minDimension * 1.5f`）。 */
    const val HighlightSpotRadiusFactor: Float = 1.5f
}
