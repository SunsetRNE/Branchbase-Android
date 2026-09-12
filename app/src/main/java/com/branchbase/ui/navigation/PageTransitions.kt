package com.branchbase.ui.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 页面切换动效的统一规格（全项目唯一真源）。
 *
 * ## 为什么现在需要它
 *
 * 在此之前全项目**没有任何页面切换动效**：Tab 切换、从首页进个人页 / 搜索 / 仓库详情、
 * 仓库页里十几个全屏子页、个人页的设置子页……全都是「状态一变内容直接换掉」，
 * 观感上就是「点一下，画面硬切」。这里把动效收成一处，页面只声明「我在第几层」。
 *
 * ## 两种动效，按语义选（不是按好看选）
 *
 * | 场景 | 动效 | 为什么 |
 * |------|------|--------|
 * | 有层级的推进 / 返回（[PageSwitcher]） | 水平滑动 1/4 屏 + 淡入淡出 | 子页有前后关系：前进从右进、返回向右出，符合 Android「打开 / 关闭」的心智 |
 * | 同级切换（[TabSwitcher]） | 淡入淡出 + 轻微上浮 | 同级之间没有前后关系，横向滑动会暗示错误的层级（「我是不是进了下一页？」） |
 *
 * ## 参数取舍
 *
 * - **位移只用 1/4 屏**：整屏推进要重绘整块内容，长列表和 WebView 正文页会明显掉帧；
 *   1/4 屏已经足够表达方向，代价接近纯淡入；
 * - **进场 260ms / 退场 200ms**：Material 3 的常用区间；退场更快一点，让「返回」更跟手；
 * - **不自定义 sizeTransform**：两个页面都是全屏、尺寸不变，动画里没有内容可插值。
 */
object PageMotion {

    /** 子页进场时长：方向滑动 + 淡入。 */
    const val ENTER_MS = 260

    /** 子页退场时长：比进场快，返回时更跟手。 */
    const val EXIT_MS = 200

    /** 位移距离 = 容器宽度的 1/4（见类注释里的取舍）。 */
    const val SLIDE_FRACTION = 0.25f

    /** 同级（Tab）切换时长。 */
    const val TAB_MS = 180

    /** 同级切换时新内容的起始上浮距离（容器高度的比例）。 */
    const val TAB_RISE_FRACTION = 0.02f
}

/**
 * 页面在导航里的**层级**：同一个切换器里，数字小的在外层（父级），大的在里层（子页）。
 *
 * 切换方向完全由层级决定：`target.depth > initial.depth` → 前进（从右滑入）；
 * 小于 → 返回（向右滑出）；相等 → 同级（淡入淡出）。
 * 这样页面自己不需要写任何「我是往前还是往后」的判断 —— 那是最容易写错、也最难维护的东西。
 */
interface PageLevel {
    val depth: Int
}

/**
 * 有层级的页面切换器：状态变了就按层级方向做位移动画。
 *
 * [state] 必须是**可比较的稳定值**（枚举 / data class），因为它同时承担两个职责：
 * 1. 决定渲染哪个页面；
 * 2. 在动画期间把**上一个状态**原样交给退场内容 —— 这一点很重要：
 *    如果用 `if (x != null)` 这种写法，状态一清空，退场中的页面就会立刻变成空白
 *    （内容先消失、外壳再淡出，看起来像闪烁）。`AnimatedContent` 帮我们保住了旧状态。
 */
@Composable
fun <S : PageLevel> PageSwitcher(
    state: S,
    modifier: Modifier = Modifier,
    label: String = "page",
    content: @Composable AnimatedContentScope.(S) -> Unit,
) {
    AnimatedContent(
        targetState = state,
        modifier = modifier,
        transitionSpec = {
            // 方向只由层级差决定；**同级不是「推进」**（例如仓库页里切 Tab），
            // 同级还做水平滑动会让人误以为进了下一页
            when (pageDirection(initialState.depth, targetState.depth)) {
                1 -> pageTransform(1)
                -1 -> pageTransform(-1)
                else -> levelTransform()
            }
        },
        label = label,
        content = content,
    )
}

/**
 * 切换方向：`+1` 前进 / `-1` 返回 / `0` 同级。
 *
 * 抽成纯函数是为了能单测 —— 方向写反（返回时往右进、前进时往左出）是动效里最隐蔽的 bug：
 * 看着「有动画」，但每一处方向都和用户的手指方向相反，用久了会莫名其妙地别扭。
 */
internal fun pageDirection(initialDepth: Int, targetDepth: Int): Int = when {
    targetDepth > initialDepth -> 1
    targetDepth < initialDepth -> -1
    else -> 0
}

/**
 * 返回键语义。
 *
 * 全 App 只有两档：
 * - [ClosePage]：更深的页面先关自己（子页 / 详情 / 多选态…）；
 * - [BackToWelcome]：**顶层（depth == 0）不退出 App**，而是回登录首页 ——
 *   在登录首页再按一次返回，才由系统默认行为彻底退出。
 *
 * 抽成纯函数是为了能单测：返回键串味（该关页面却退出、该回首页却关页面）只有在
 * 真机上连按才试得出来，回归时最难发现。
 */
enum class BackDisposition { ClosePage, BackToWelcome }

/** 由路由层级判断返回语义：顶层 → 回登录首页，其余 → 关页面。 */
fun backDisposition(depth: Int): BackDisposition =
    if (depth <= 0) BackDisposition.BackToWelcome else BackDisposition.ClosePage

/** 同级切换（底部 Tab / 同层页）：没有方向，只做淡入淡出 + 轻微上浮。 */
@Composable
fun <S> TabSwitcher(
    state: S,
    modifier: Modifier = Modifier,
    label: String = "tab",
    content: @Composable AnimatedContentScope.(S) -> Unit,
) {
    AnimatedContent(
        targetState = state,
        modifier = modifier,
        transitionSpec = { levelTransform() },
        label = label,
        content = content,
    )
}

/** 同级切换的进出组合（Tab 与 [PageSwitcher] 的同层分支共用）。 */
private fun levelTransform(): ContentTransform {
    val rise = { h: Int -> (h * PageMotion.TAB_RISE_FRACTION).toInt() }
    return (fadeIn(tween(PageMotion.TAB_MS)) + slideInVertically(tween(PageMotion.TAB_MS)) { rise(it) }) togetherWith
        (fadeOut(tween(PageMotion.TAB_MS)) + slideOutVertically(tween(PageMotion.TAB_MS)) { -rise(it) })
}

/**
 * 子页切换的进出组合。
 *
 * @param dir `+1` = 前进（新内容从右进、旧内容向左退）；`-1` = 返回（反过来）
 */
private fun pageTransform(dir: Int): ContentTransform {
    val enter = slideInHorizontally(tween(PageMotion.ENTER_MS)) { w ->
        dir * (w * PageMotion.SLIDE_FRACTION).toInt()
    } + fadeIn(tween(PageMotion.ENTER_MS))

    val exit = slideOutHorizontally(tween(PageMotion.EXIT_MS)) { w ->
        -dir * (w * PageMotion.SLIDE_FRACTION).toInt()
    } + fadeOut(tween(PageMotion.EXIT_MS))

    // 不自定义 sizeTransform：两个页面都是全屏，尺寸不会变（这版 Compose 里它也是 internal）
    return enter togetherWith exit
}

/** 供页面自行组合时复用（例如想给某个特殊页面单独定制动效）。 */
fun pageEnterTransition(dir: Int): EnterTransition = slideInHorizontally(tween(PageMotion.ENTER_MS)) { w ->
    dir * (w * PageMotion.SLIDE_FRACTION).toInt()
} + fadeIn(tween(PageMotion.ENTER_MS))

/** 与 [pageEnterTransition] 配对的退场。 */
fun pageExitTransition(dir: Int): ExitTransition = slideOutHorizontally(tween(PageMotion.EXIT_MS)) { w ->
    -dir * (w * PageMotion.SLIDE_FRACTION).toInt()
} + fadeOut(tween(PageMotion.EXIT_MS))
