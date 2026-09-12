package com.branchbase.ui.navigation

import androidx.compose.animation.AnimatedContent
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
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
 * 这一格内容**是不是当前页**（纯函数，供两个切换器共用，也便于单测）。
 *
 * 只有与「当前目标状态」相等的那一格才是当前页：`AnimatedContent` 把它正在播放退场动画的
 * 旧页也留在组合树里，旧页拿到的必须是 `false`，否则它的 `PageBackHandler` 会继续抢返回键。
 *
 * ## 为什么单独提出来：PageSwitcher 曾经漏了下发
 *
 * 文档、提交信息与 [shouldHandleBack] 的单测都写着「退场中的旧页放手」，但只有 [TabSwitcher]
 * 真的下发了 [LocalPageActive] —— 走 [PageSwitcher] 的那些页面（主界面四条路由、仓库页十几个
 * 子页、个人页子页、登录流程步骤）拿到的恒为默认 `true`，机制在最常用的那条路径上没生效。
 * 现在两个切换器都调这个函数；[PageTransitionsTest] 里有源码级钉子盯着它们别再分家。
 */
internal fun pageIsCurrent(target: Any?, current: Any?): Boolean = target == current

/**
 * 有层级的页面切换器：状态变了就按层级方向做位移动画。
 *
 * [state] 必须是**可比较的稳定值**（枚举 / data class），因为它同时承担两个职责：
 * 1. 决定渲染哪个页面；
 * 2. 在动画期间把**上一个状态**原样交给退场内容 —— 这一点很重要：
 *    如果用 `if (x != null)` 这种写法，状态一清空，退场中的页面就会立刻变成空白
 *    （内容先消失、外壳再淡出，看起来像闪烁）。`AnimatedContent` 帮我们保住了旧状态。
 *
 * 3. 给每一格内容下发 [LocalPageActive]（见 [pageIsCurrent]）：**退场中的旧页一律放手**，
 *    否则用户「连按两次返回」的第二次会被旧页吃掉（顶层双击退出直接失灵）。
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
    ) { target ->
        CompositionLocalProvider(LocalPageActive provides pageIsCurrent(target, state)) {
            content(target)
        }
    }
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
 * 这一格内容**是不是当前页**（由 [PageSwitcher] / [TabSwitcher] 自动下发）。
 *
 * ## 为什么需要它（返回键被「退场中的旧页」吃掉的根因）
 *
 * `AnimatedContent` 在切换过程中会把**旧页继续留在组合树里**播完退场动画（200~260ms）。
 * 旧页里的 `BackHandler` 也随之仍然注册且 enabled —— Compose 的返回键是「最后注册且启用者胜」，
 * 于是用户「连按两次返回」时的**第二次会被退场中的旧页吃掉**，表现为「按了没反应，得再按一次」。
 *
 * 典型现场：主界面顶层按返回 → 触发「再按一次退出」的第一次（外层 `PageSwitcher` 开始播动画），
 * 用户在动画还没播完时再按一次想退出 App —— 这一下被退场中的 `MainScreen` 吃掉，
 * 于是「再按一次才彻底退出」变成「按两次都没退出」。
 *
 * 修法：页面级返回键统一用 [PageBackHandler]，它会把 [LocalPageActive] 与自身条件取与 ——
 * **只有当前页能抢返回键**，退场中的旧页一律放手，事件自然落到正确的下一层（或系统默认退出）。
 */
val LocalPageActive = staticCompositionLocalOf { true }

/**
 * 页面级返回键（**所有页面都该用它，而不是裸 `BackHandler`**）。
 *
 * `enabled` 只描述「这一页内部有没有要关的东西」（如子页是否打开、是否多选态），
 * 「是不是当前页」由 [LocalPageActive] 统一叠加，调用方不需要关心。
 */
@Composable
fun PageBackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    BackHandler(enabled = shouldHandleBack(enabled, LocalPageActive.current), onBack = onBack)
}

/**
 * 这一页该不该抢返回键（纯函数，便于单测）。
 *
 * 「按几次才退出」这类行为只有真机连按才试得出来，回归时最难发现，
 * 所以把判定从 composable 里提出来钉住：**退场中的旧页（pageActive=false）永远放手**。
 */
fun shouldHandleBack(enabled: Boolean, pageActive: Boolean): Boolean = enabled && pageActive

/**
 * 返回键语义。
 *
 * 全 App 只有两档：
 * - [ClosePage]：更深的页面先关自己（子页 / 详情 / 多选态…）；
 * - [ExitApp]：**顶层（depth == 0）不再是「回登录首页」**，而是交给
 *   [rememberTopLevelBackAction] 做「再按一次退出应用」（见那个文件的注释：
 *   会话还在却被丢回登录页，是和真实登录状态不符的死状态）。
 *
 * 抽成纯函数是为了能单测：返回键串味（该关页面却退出、该回首页却关页面）只有在
 * 真机上连按才试得出来，回归时最难发现。
 */
enum class BackDisposition { ClosePage, ExitApp }

/** 由路由层级判断返回语义：顶层 → 退出应用（双击确认），其余 → 关页面。 */
fun backDisposition(depth: Int): BackDisposition =
    if (depth <= 0) BackDisposition.ExitApp else BackDisposition.ClosePage

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
    ) { target ->
        CompositionLocalProvider(LocalPageActive provides pageIsCurrent(target, state)) {
            content(target)
        }
    }
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
