package com.branchbase.ui.navigation

import androidx.compose.animation.AnimatedContent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
 * - **进场 300ms / 退场 220ms**：Material 3 的常用区间；退场更快一点，让「返回」更跟手；
 * - **两条曲线不一样，这是「丝滑」的主要来源**：进场用减速曲线（[PageMotion.EnterEasing]，
 *   起步快、收尾缓），退场用加速曲线（[PageMotion.ExitEasing]，越走越快）。
 *   两段都用同一条对称曲线（`tween` 的默认 `FastOutSlowInEasing`）时，动画**起步**那一拍是慢的，
 *   手指已经离开屏幕、画面却「黏」着不动，观感就是不够跟手 —— 而这正是页面切换最常见的抱怨。
 *   这套「进减速 / 出加速」与设计稿里的 `cubic-bezier(0.2, 0.8, 0.2, 1)` 同一个取向
 *   （见 `design/messages-redesign/style.css` 的 `--ease`）；
 * - **退场的「淡出」比「滑动」早收**：滑动走满 [PageMotion.EXIT_MS]，
 *   但透明度在 [PageMotion.EXIT_FADE_MS] 内就到 0。两个页面在这段重叠期如果各是半透明，
 *   中段会叠成一张「两张都看得见、又都看不清」的糊图（长列表页尤其明显）；
 *   让旧页先退干净，重叠期就只剩新页在动，画面始终是清楚的；
 * - **不自定义 sizeTransform**：两个页面都是全屏、尺寸不变，动画里没有内容可插值。
 */
object PageMotion {

    /** 子页进场时长：方向滑动 + 淡入。 */
    const val ENTER_MS = 300

    /** 子页退场时长（滑动走满这段）：比进场快，返回时更跟手。 */
    const val EXIT_MS = 220

    /**
     * 退场**淡出**单独早收（比 [EXIT_MS] 短）。
     *
     * 滑动与淡出不必同长：让旧页先淡干净、再慢慢滑出去，重叠期就不会出现两张半透明页面叠着的糊图。
     */
    const val EXIT_FADE_MS = 130

    /** 位移距离 = 容器宽度的 1/4（见类注释里的取舍）。 */
    const val SLIDE_FRACTION = 0.25f

    /** 同级（Tab）切换时长。 */
    const val TAB_MS = 220

    /** 同级切换的退场淡出时长（同样比滑动早收，理由见 [EXIT_FADE_MS]）。 */
    const val TAB_FADE_OUT_MS = 140

    /** 同级切换时新内容的起始上浮距离（容器高度的比例）。 */
    const val TAB_RISE_FRACTION = 0.02f

    /**
     * 进场曲线：**减速**（起步快、收尾缓）。
     *
     * 页面「进来」是把用户的意图落到实处，起步就要跟上手指；用对称曲线会在起步处黏一拍。
     */
    val EnterEasing: Easing = LinearOutSlowInEasing

    /**
     * 退场曲线：**加速**（起步慢、越走越快）。
     *
     * 页面「离开」是让位，越走越快才像干脆地退开；用减速曲线尾巴会拖。
     */
    val ExitEasing: Easing = FastOutLinearInEasing
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
 *
 * [contentKey] 默认「状态本身」，也就是**状态值一变就当成换了一页**。带 payload 的路由如果
 * payload 会在同一页内变（例：登录流程的 `Authorizing(url, verifier)` / `Error(message)`），
 * 应传 `{ it::class }` 之类的稳定身份，否则同页刷新会白播一次换页动画。
 */
@Composable
fun <S : PageLevel> PageSwitcher(
    state: S,
    modifier: Modifier = Modifier,
    label: String = "page",
    contentKey: (S) -> Any? = { it },
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
        contentKey = contentKey,
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
    contentKey: (S) -> Any? = { it },
    content: @Composable AnimatedContentScope.(S) -> Unit,
) {
    // 同级页来回切时，退场动画一结束 `AnimatedContent` 就把旧内容移出组合树 ——
    // 里面 `rememberSaveable` 的东西（列表滚动位置、筛选、展开态…）跟着一起丢，
    // 表现成「切走再切回来，列表回到顶部」。用同一个 holder 按目的地存住它们：
    // 动画照旧「切一次播一次」，但切回来还是原来的位置。
    //
    // 只给 TabSwitcher 加：这里的 key 都是枚举 / 整数（`NavDestination`、`RepoPage`、
    // `ProfileTab`、步骤号），能被 Bundle 序列化；`PageSwitcher` 的路由 key 是带 payload 的
    // data class（如 `MainRoute.Repo(RepoDeepLink)`），不是所有都能存，强行加会在存盘时炸。
    val stateHolder = rememberSaveableStateHolder()
    AnimatedContent(
        targetState = state,
        modifier = modifier,
        transitionSpec = { levelTransform() },
        contentKey = contentKey,
        label = label,
    ) { target ->
        stateHolder.SaveableStateProvider(target as Any) {
            CompositionLocalProvider(LocalPageActive provides pageIsCurrent(target, state)) {
                content(target)
            }
        }
    }
}

/**
 * 同级切换的进出组合（Tab 与 [PageSwitcher] 的同层分支共用）。
 *
 * 进出两段共用 [PageMotion.EnterEasing]：同级切换没有「方向」，只有一个「内容被换掉了」的
 * 提示，这里要的是**稳定的一次呼吸**，不该出现「新内容比旧内容先到位」的时序差。
 * 退场淡出同样早收（[PageMotion.TAB_FADE_OUT_MS]），避免两张半透明的整页叠在中段。
 */
private fun levelTransform(): ContentTransform {
    val rise = { h: Int -> (h * PageMotion.TAB_RISE_FRACTION).toInt() }
    val enter = fadeIn(tween(PageMotion.TAB_MS, easing = PageMotion.EnterEasing)) +
        slideInVertically(tween(PageMotion.TAB_MS, easing = PageMotion.EnterEasing)) { rise(it) }
    val exit = fadeOut(tween(PageMotion.TAB_FADE_OUT_MS, easing = PageMotion.ExitEasing)) +
        slideOutVertically(tween(PageMotion.TAB_MS, easing = PageMotion.ExitEasing)) { -rise(it) }
    return enter togetherWith exit
}

/**
 * 子页切换的进出组合。
 *
 * @param dir `+1` = 前进（新内容从右进、旧内容向左退）；`-1` = 返回（反过来）
 */
private fun pageTransform(dir: Int): ContentTransform {
    val enter = slideInHorizontally(tween(PageMotion.ENTER_MS, easing = PageMotion.EnterEasing)) { w ->
        dir * (w * PageMotion.SLIDE_FRACTION).toInt()
    } + fadeIn(tween(PageMotion.ENTER_MS, easing = PageMotion.EnterEasing))

    // 滑动走满 EXIT_MS，淡出只走 EXIT_FADE_MS：旧页先退干净，重叠期画面才不糊。
    val exit = slideOutHorizontally(tween(PageMotion.EXIT_MS, easing = PageMotion.ExitEasing)) { w ->
        -dir * (w * PageMotion.SLIDE_FRACTION).toInt()
    } + fadeOut(tween(PageMotion.EXIT_FADE_MS, easing = PageMotion.ExitEasing))

    // 不自定义 sizeTransform：两个页面都是全屏，尺寸不会变（这版 Compose 里它也是 internal）
    return enter togetherWith exit
}

/** 供页面自行组合时复用（例如想给某个特殊页面单独定制动效）。 */
fun pageEnterTransition(dir: Int): EnterTransition = slideInHorizontally(
    tween(PageMotion.ENTER_MS, easing = PageMotion.EnterEasing),
) { w ->
    dir * (w * PageMotion.SLIDE_FRACTION).toInt()
} + fadeIn(tween(PageMotion.ENTER_MS, easing = PageMotion.EnterEasing))

/** 与 [pageEnterTransition] 配对的退场。 */
fun pageExitTransition(dir: Int): ExitTransition = slideOutHorizontally(
    tween(PageMotion.EXIT_MS, easing = PageMotion.ExitEasing),
) { w ->
    -dir * (w * PageMotion.SLIDE_FRACTION).toInt()
} + fadeOut(tween(PageMotion.EXIT_FADE_MS, easing = PageMotion.ExitEasing))
