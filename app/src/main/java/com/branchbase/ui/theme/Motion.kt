package com.branchbase.ui.theme

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
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
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

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
