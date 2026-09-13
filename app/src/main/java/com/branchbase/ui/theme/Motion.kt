package com.branchbase.ui.theme

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
 * 页面上就会同时存在好几种节奏。这里给出四个场景的唯一规格，调用方只挑语义、不定参数：
 *
 * | 场景 | 时长 | 做法 | 用在哪 |
 * |------|------|------|--------|
 * | [selectionColor] 选中态 | 180ms | 颜色渐变 | 导航栏选中项、筛选 chip、开关式按钮 |
 * | [revealEnter] 出现/消失 | 220ms | 纵向展开 + 淡入 | 多选工具栏、撤销条、折叠区、底部操作条 |
 * | [AnimatedStateIcon] 图标切换 | 200ms | 交叉淡入 + 缩放 | 筛选 ↔ 关闭、全选 ↔ 取消、手柄 ↔ 关闭 |
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
