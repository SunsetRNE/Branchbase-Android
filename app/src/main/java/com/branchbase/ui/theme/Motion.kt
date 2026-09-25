package com.branchbase.ui.theme

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 元素级动效规格（与页面级的 `ui/navigation/PageTransitions.kt` 分工不同）。
 *
 * | 层级 | 文件 | 管什么 |
 * |------|------|--------|
 * | 页面 | `ui/navigation/PageTransitions.kt` | 整页之间的进出（层级推进 / 同级切换） |
 * | 元素 | 这里 | **同一页面内**元素的状态变化：选中态、出现/消失、图标形态切换、徽标 |
 *
 * ## 为什么元素级也要收成一处
 *
 * 元素级动效最容易变成「每人一套时长」：有的 150ms、有的 spring 弹一下、有的干脆没有，
 * 页面上就会同时存在好几种节奏。这里给出五个场景的唯一规格，调用方只挑语义、不定参数：
 *
 * | 场景 | 时长 | 做法 | 用在哪 |
 * |------|------|------|--------|
 * | [selectionColor] 选中态 | 180ms | 颜色渐变 | 导航栏选中项、筛选 chip、开关式按钮 |
 * | [revealEnter] 出现/消失 | 220ms | 纵向展开 + 淡入 | 多选工具栏、撤销条、折叠区、底部操作条 |
 * | [AnimatedStateIcon] 图标切换 | 200ms | 交叉淡入 + 缩放 | 筛选 ↔ 关闭、全选 ↔ 取消、手柄 ↔ 关闭 |
 * | [PlaceholderSwap] 骨架 → 内容 | 延迟 120ms + 骨架 120ms 退 / 内容 160ms 进 | 就地替换 + 尺寸动画（**不是** `Crossfade`，理由见那个函数的注释） | 整页 / 分区骨架、贡献墙 |
 * | `CountBadge` 徽标 | 220ms | 缩放淡入 | 底部导航未读数（见 `ui/navigation/NavigationBar.kt`） |
 *
 * 取舍：**都不做回弹（spring）**。这类元素在列表里反复出现，回弹在第一次看是「活泼」，
 * 第十次就是「拖沓」；tween 的稳定节奏更适合高频交互。
 */
object ElementMotion {

    /** 选中态颜色过渡。 */
    const val COLOR_MS = 180

    /** 横幅 / 面板 / 折叠区的展开收起。 */
    const val REVEAL_MS = 220

    /** 图标形态切换（交叉淡入 + 缩放）。 */
    const val ICON_MS = 200

    /** 徽标出现 / 消失。 */
    const val BADGE_MS = 220

    /** 图标切换时新图标的起始缩放（0.8 足够表达「换了一个」，又不会让图标飞出边界）。 */
    const val SWAP_SCALE = 0.8f

    /** 徽标出现时的起始缩放。 */
    const val BADGE_SCALE = 0.6f

    /** 骨架屏微光的单程时长。 */
    const val SHIMMER_MS = 700

    /** 气泡弹层（从锚点角落缩放 + 淡入）的时长。 */
    const val BUBBLE_MS = 180

    /** 气泡内多个条目逐条入场的间隔。 */
    const val STAGGER_MS = 28

    /** 按下反馈：缩到多少。 */
    const val PRESS_SCALE = 0.94f

    /** 按下反馈时长（要跟手，所以短）。 */
    const val PRESS_MS = 120

    /**
     * 「骨架 → 内容」替换：骨架**延迟**这么久才现身（见 [PlaceholderSwap]）。
     *
     * 缓存命中的页面常在 1~3 帧内就拿到数据；这段时间里闪一块灰再立刻换掉，
     * 读到的不是「加载完成」而是「闪了一下」—— 短于此值的加载干脆不显示骨架。
     *
     * **弹窗与浮层不适用这一条**（传 `skeletonDelayMs = 0`）：它们是用户主动点开的，
     * 取数必然要走一次引擎 / 网络，延迟只会先空一瞬（读成「掉下来一块空的」）。
     */
    const val PLACEHOLDER_DELAY_MS = 120L

    /** 骨架自己淡入 / 淡出的时长（延迟期过后才用到）。 */
    const val PLACEHOLDER_FADE_MS = 140

    /** 替换时**骨架先退**的时长（短）：两态同时半透明会让灰块与文字糊在一起。 */
    const val PLACEHOLDER_OUT_MS = 120

    /** 替换时**内容再进**的时长（在骨架退干净之后起）。 */
    const val PLACEHOLDER_IN_MS = 160
}

/**
 * 骨架屏微光：透明度在 0.4~0.9 之间来回呼吸。
 *
 * 加载态是「什么都不动」还是「有节奏地动」，用户对同一段等待的主观时长差别很大 ——
 * 静态灰块看起来像卡死，呼吸的占位符看起来像「正在加载」。
 * 之前只有搜索页的骨架有这个效果，通知页是死的灰块，这里收成一处。
 */
@Composable
fun shimmerAlpha(): State<Float> = rememberInfiniteTransition(label = "shimmer").animateFloat(
    initialValue = 0.4f,
    targetValue = 0.9f,
    animationSpec = infiniteRepeatable(
        animation = tween(ElementMotion.SHIMMER_MS),
        repeatMode = RepeatMode.Reverse,
    ),
    label = "shimmerAlpha",
)

/**
 * 屏幕级 shimmer：**整屏骨架共用一条动画**。
 *
 * [shimmerAlpha] 的价值在于「有节奏地动」，但它有两个很容易踩的坑，都在使用方式上：
 *
 * 1. **不要在列表项里各调一次**。`items(6) { Skeleton() }` 里每个骨架各自
 *    `rememberInfiniteTransition` 就是 6 条无限动画、6 个动画时钟（相位还可能漂移）；
 * 2. **不要直接把值读进 `background(color.copy(alpha = it))`**。那是在**组合期**读状态，
 *    骨架每帧重组一次 —— 加载中的页面本来就是「动画 + 首次组合」最重的时候，
 *    再叠上每帧重组，就是「加载时/切页面时卡」的直接来源。
 *
 * 正确用法：屏幕级用 [ProvideShimmer] 包一层，占位块用 [skeletonBlock]
 * （alpha 在**绘制期**读，只失效绘制，不触发重组）。
 */
@Composable
fun ProvideShimmer(content: @Composable () -> Unit) {
    val alpha = shimmerAlpha()
    CompositionLocalProvider(LocalShimmerAlpha provides alpha, content = content)
}

/**
 * 当前屏幕的 shimmer 透明度（由 [ProvideShimmer] 下发）。
 *
 * 拿到的必须是 `State` 本身而不是 `Float`：值要留到**绘制期**再读（见 [skeletonBlock]），
 * 组合期读一次就等于把整块内容挂到了动画的每一帧上。
 */
val LocalShimmerAlpha = staticCompositionLocalOf<State<Float>> { mutableStateOf(1f) }

/**
 * 骨架占位块：用当前 shimmer 透明度画一个圆角矩形。
 *
 * 值在 [drawBehind] 里读 → 只失效**绘制**（不重组、不重新布局），
 * 这正是 `Motion.kt` 与 README 里「动画值尽量在 graphicsLayer / 绘制期消费」那条约定的落法。
 *
 * 颜色默认取 [Primer.Gray150]（中性填充 `neutralFillStrong`）。**别用 `Primer.Border` 当填充** ——
 * 那是描边色（见 `Color.kt` 的三条使用约束），深色下当填充用对比度也不够。
 */
@Composable
fun Modifier.skeletonBlock(
    cornerRadius: Dp = 4.dp,
    fill: Color = Primer.Gray150,
): Modifier {
    val alpha = LocalShimmerAlpha.current
    val radius = with(LocalDensity.current) { cornerRadius.toPx() }
    return drawBehind {
        drawRoundRect(
            color = fill.copy(alpha = alpha.value),
            cornerRadius = CornerRadius(radius, radius),
        )
    }
}

/**
 * 选中态颜色：在 [on] / [off] 之间渐变，而不是硬切。
 *
 * 用法与 `if (selected) a else b` 完全一致，只是把「跳变」换成 180ms 的过渡：
 * ```
 * .background(selectionColor(selected, on = Primer.Blue500, off = Primer.Gray150))
 * ```
 */
@Composable
fun selectionColor(
    selected: Boolean,
    on: Color,
    off: Color = Color.Transparent,
    animationSpec: AnimationSpec<Color> = tween(ElementMotion.COLOR_MS),
): Color = animateColorAsState(
    targetValue = if (selected) on else off,
    animationSpec = animationSpec,
    label = "selectionColor",
).value

/** 出现 / 消失（横幅、面板、折叠区）的统一进场：纵向展开 + 淡入。 */
fun revealEnter(): EnterTransition =
    expandVertically(tween(ElementMotion.REVEAL_MS)) + fadeIn(tween(ElementMotion.REVEAL_MS))

/** 与 [revealEnter] 配对的退场。 */
fun revealExit(): ExitTransition =
    shrinkVertically(tween(ElementMotion.REVEAL_MS)) + fadeOut(tween(ElementMotion.REVEAL_MS))

/**
 * 「骨架 → 内容」的**就地替换**（整页骨架、分区骨架、贡献墙都用这一个）。
 *
 * ## 为什么要单独做一个原语，而不是继续用 `Crossfade`
 *
 * `Crossfade` 在这件事上有两个固有行为，真机上都会读成「不舒服」，而且都不是曲线能调的：
 *
 * 1. **容器尺寸在两态之间直接取大者** —— 内容一落地，容器高度**当帧**变成内容高度，
 *    下方内容被瞬间顶下去（骨架 4 行、内容 30 行的分区尤其明显）。
 *    这里改用 `AnimatedContent`：它自带 `sizeTransform`，高度变化是**动画**而不是跳变；
 * 2. **两态同时半透明、重叠着淡** —— 中段会看到「灰块与文字糊在一起」。
 *    这里让**骨架先退干净（[ElementMotion.PLACEHOLDER_OUT_MS]）、内容随后再进**
 *    （[ElementMotion.PLACEHOLDER_IN_MS]，延迟一个 OUT）—— 与页面级「退场淡出早收」同一个思路。
 *
 * 另外还有第三件事：**骨架延迟现身**（[ElementMotion.PLACEHOLDER_DELAY_MS]）。
 * 缓存命中常在 1~3 帧内就拿到数据，此时闪一块灰再立刻换掉，读到的是「闪了一下」而不是
 * 「加载完成」；短于此值的加载干脆不显示骨架（占位仍占着高度，所以版式不会塌一下再撑开）。
 *
 * [skeleton] 与 [content] 仍应尽量同尺寸（骨架的既有规矩）；不同尺寸也不会跳，只是会看到
 * 一段高度动画 —— 那是兜底，不是许可证。
 *
 * ## 弹窗 / 浮层：为什么要传 `skeletonDelayMs = 0`
 *
 * 延迟现身的理由是「缓存秒回时别闪一块灰」。但**弹窗与浮层没有缓存秒回这条路**：
 * 用户主动点开它，取数必然要走一次引擎或网络（几十到几百毫秒），
 * 于是这 120ms 只剩一个作用 —— 先画一块**空的**容器（面板/弹窗以最小高度出现），
 * 再让骨架淡进来，最后内容一到又被撑高。用户看到的正是「掉下来 → 填充 → 撑高」。
 *
 * 配套的另一半在调用方：**占位的形状与高度要跟内容一致**（[SkeletonRows] + 固定高度的内容区），
 * 否则延迟去掉了、撑高还在。
 */
@Composable
fun PlaceholderSwap(
    loading: Boolean,
    modifier: Modifier = Modifier,
    /**
     * 骨架延迟现身的时长；[ElementMotion.PLACEHOLDER_DELAY_MS] 是页面级的默认值，
     * 弹窗 / 浮层传 `0`（理由见上）。
     */
    skeletonDelayMs: Long = ElementMotion.PLACEHOLDER_DELAY_MS,
    skeleton: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    // 骨架延迟现身：加载很快就结束时，它一辈子都是透明的
    var skeletonVisible by remember { mutableStateOf(false) }
    LaunchedEffect(loading, skeletonDelayMs) {
        if (loading) {
            if (skeletonDelayMs > 0) delay(skeletonDelayMs)
            skeletonVisible = true
        } else {
            skeletonVisible = false
        }
    }
    val skeletonAlpha by animateFloatAsState(
        targetValue = if (skeletonVisible) 1f else 0f,
        animationSpec = tween(ElementMotion.PLACEHOLDER_FADE_MS),
        label = "placeholder-alpha",
    )

    AnimatedContent(
        targetState = loading,
        modifier = modifier,
        transitionSpec = {
            fadeIn(
                tween(
                    ElementMotion.PLACEHOLDER_IN_MS,
                    delayMillis = ElementMotion.PLACEHOLDER_OUT_MS,
                    easing = FastOutSlowInEasing,
                ),
            ) togetherWith fadeOut(tween(ElementMotion.PLACEHOLDER_OUT_MS))
        },
        label = "placeholder-swap",
    ) { isLoading ->
        // ⚠️ `AnimatedContent` 的内容也在 Box 里：多子元素会互叠，所以统一套一层 Column
        // （与 `CrossfadeLayoutTest` 钉住的那条同一个坑，调用方不必自己记得）。
        Column(Modifier.fillMaxWidth()) {
            if (isLoading) {
                // 值在**绘制期**读（graphicsLayer），延迟窗口内它每帧变一次也不会触发重组
                Box(Modifier.graphicsLayer { alpha = skeletonAlpha }) { skeleton() }
            } else {
                content()
            }
        }
    }
}

/**
 * 一块**等高占位区**里能铺几行：`area` 高度的盒子，行高 `row` + 行距 `gap`，至少一行。
 *
 * 抽成纯函数是为了让它能进 JVM 单测：骨架最常见的写法是「填满这块区域」，
 * 而填不满（底下留一块空白，内容一到又撑开）或填过头（画到盒子外面）都是肉眼才看得出的错。
 * 参数用 `Int`（dp）而不是 `Dp`：这一条是纯算术，不该把 Compose 类型拖进单测。
 */
fun skeletonRowsFor(areaDp: Int, rowDp: Int, gapDp: Int = 0): Int {
    val step = rowDp + gapDp
    if (step <= 0) return 1
    return ((areaDp + gapDp) / step).coerceAtLeast(1)
}

/**
 * 列表骨架：`rows` 行、每行 `rowHeight` 高、行间 `gap` —— **行高照实**（与真实行一致）。
 *
 * 这是「占位与内容等高」这条规矩的落点：骨架的高度决定弹窗/面板的第一帧尺寸，
 * 行高对不上，内容一到就必然撑高（`git-mode-design.md` §3.5 的现场）。
 * [barHeight] 默认等于行高（整行一块灰）；想让骨架看得出「一行一行」时传一个更小的值。
 */
@Composable
fun SkeletonRows(
    rows: Int,
    rowHeight: Dp,
    modifier: Modifier = Modifier,
    gap: Dp = 0.dp,
    barHeight: Dp = rowHeight,
    horizontalPadding: Dp = 0.dp,
    corner: Dp = 6.dp,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
        repeat(rows.coerceAtLeast(0)) {
            SkeletonBar(
                height = barHeight,
                horizontalPadding = horizontalPadding,
                corner = corner,
            )
        }
    }
}

/** 骨架里的一条灰块（一行 / 一个标题）。 */
@Composable
fun SkeletonBar(
    height: Dp,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 0.dp,
    corner: Dp = 6.dp,
) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding)
            .height(height)
            .clip(RoundedCornerShape(corner))
            .background(Primer.Gray150),
    )
}

/**
 * 气泡弹层进场：**从锚点角落**缩放 + 淡入。
 *
 * 为什么不用 `DropdownMenu` 的默认动画：Material 的菜单是「从上边缘往下长」的，
 * 而这个气泡是从右下角手柄**向上**弹出的 —— 缩放原点在下边缘右下角才符合空间直觉
 * （看起来像从手柄里冒出来）。[origin] 默认右下角，锚点在其他角落时传对应的 [TransformOrigin]。
 */
fun bubbleEnter(origin: TransformOrigin = TransformOrigin(1f, 1f)): EnterTransition =
    scaleIn(tween(ElementMotion.BUBBLE_MS), initialScale = 0.86f, transformOrigin = origin) +
        fadeIn(tween(ElementMotion.BUBBLE_MS))

/** 与 [bubbleEnter] 配对的气泡退场（收回到锚点角落）。 */
fun bubbleExit(origin: TransformOrigin = TransformOrigin(1f, 1f)): ExitTransition =
    scaleOut(tween(ElementMotion.BUBBLE_MS), targetScale = 0.92f, transformOrigin = origin) +
        fadeOut(tween(ElementMotion.BUBBLE_MS))

/**
 * 按下反馈（按钮 / 图标 / 导航项）。
 *
 * 用法：把 [PressFeedback.interaction] 交给 `clickable`，缩放值**在 graphicsLayer 里读**
 * （只在绘制阶段消费，不触发每帧重组）：
 * ```
 * val press = rememberPressFeedback()
 * Modifier
 *     .graphicsLayer { scaleX = press.scale.value; scaleY = press.scale.value }
 *     .clickable(interactionSource = press.interaction, indication = LocalIndication.current, onClick = …)
 * ```
 * 注意顺序：`graphicsLayer` 要在 `clip/background` **之前**，否则只缩内容不缩底。
 */
class PressFeedback internal constructor(
    val interaction: MutableInteractionSource,
    val scale: State<Float>,
)

@Composable
fun rememberPressFeedback(pressedScale: Float = ElementMotion.PRESS_SCALE): PressFeedback {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale = animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = tween(ElementMotion.PRESS_MS),
        label = "press-scale",
    )
    return PressFeedback(interaction, scale)
}

/**
 * 「要点」呼吸高亮：透明度 0.55→1.0 + 轻微缩放来回摆动。
 *
 * 用在介绍页里强调**某一步的卖点**（例如密钥登录的「无需数字口令验证」）。
 * 与 [shimmerAlpha] 的区别：那个是给骨架占位用的低对比呼吸，这个是要「被看见」的强调。
 * 返回值只在 `graphicsLayer` 里读，不触发重组。
 */
@Composable
fun rememberPulse(
    minScale: Float = 1f,
    maxScale: Float = 1.06f,
    durationMs: Int = 900,
): State<Float> = rememberInfiniteTransition(label = "pulse").animateFloat(
    initialValue = minScale,
    targetValue = maxScale,
    animationSpec = infiniteRepeatable(
        animation = tween(durationMs, easing = FastOutSlowInEasing),
        repeatMode = RepeatMode.Reverse,
    ),
    label = "pulseScale",
)

/**
 * 图标形态切换（例：筛选 ↔ 关闭、全选 ↔ 取消）。
 *
 * 直接把 `Icon(if (open) A else B)` 换成这个：旧图标缩小淡出、新图标放大淡入，
 * 位置不变（两者尺寸一致），所以不会牵动周围布局。
 */
@Composable
fun AnimatedStateIcon(
    icon: ImageVector,
    contentDescription: String?,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = icon,
        modifier = modifier,
        transitionSpec = {
            (fadeIn(tween(ElementMotion.ICON_MS)) + scaleIn(tween(ElementMotion.ICON_MS), initialScale = ElementMotion.SWAP_SCALE)) togetherWith
                (fadeOut(tween(ElementMotion.ICON_MS)) + scaleOut(tween(ElementMotion.ICON_MS), targetScale = ElementMotion.SWAP_SCALE))
        },
        label = "state-icon",
    ) { ic ->
        Icon(ic, contentDescription = contentDescription, tint = tint)
    }
}
