package com.branchbase.ui.navigation

import androidx.compose.animation.AnimatedContent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
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
 * ## 两类动效，按语义选（不是按好看选）
 *
 * | 场景 | 动效 | 为什么 |
 * |------|------|--------|
 * | 有层级的推进 / 返回（[PageSwitcher]） | **只让一页动**：新页滑入 1/10 屏 + 淡入（220ms，两端零速 S 曲线，延迟 33ms 起播）；旧页**原地**淡出（100ms，线性） | 子页有前后关系，所以新页要有方向感；但**两个运动体同时动**（旧页反向滑出、曲线还一进一退）会让相对速度一直在变，眼睛读成「画面在晃」—— 现在屏幕上只有一个运动体 |
 * | 同级切换（[TabSwitcher]）与**首帧重的页**（[PageLevel.heavyFirstFrame]） | **fade-through**：旧页 110ms 淡净 → 新页延迟 110ms、180ms 淡入（两段**不重叠**） | 同级之间没有前后关系，横向滑动会暗示错误的层级；不重叠则是为了**同一时刻只有一页在画**（重叠期正是基线上最贵的那一笔） |
 *
 * ## 曲线（抖动的根源在这里，不在时长）
 *
 * **抖动的物理定义是速度突变（急动度）**，所以曲线只看一件事：**两端的速度连不连续**。
 *
 * | 曲线 | 起步速度 | 收尾速度 | 观感 |
 * |------|----------|----------|------|
 * | `LinearOutSlowInEasing`（`0, 0, 0.2, 1`，原先的进场） | **平均速度的 5 倍**（t=0 弹射） | 0 | 位移一短就像「抽一下」—— 用户说的「过度抖动感」 |
 * | `FastOutLinearInEasing`（`0.4, 0, 1, 1`，原先的退场） | 0 | **最大**（末帧还在加速） | 收尾砸停；用在透明度上就是「闪一下」 |
 * | [PageMotion.EnterEasing]（`0.25, 0, 0.15, 1`，现在） | 0 | 0 | 起步不弹射、收尾不砸停，中段比标准 S 曲线更早发力（不黏） |
 *
 * 另外两条与曲线配套的：
 * **① 起播门控 [PageMotion.ENTER_DELAY_MS]** —— 把目标页首帧的重活挪到动画开始之前，
 * 免得动画「刚动一下 → 定住 → 猛地跳过去」（那也是抖，但不是曲线的锅）；
 * **② 位移收到 1/10 屏** —— 位移越短，端点速度不连续越显眼（同样的曲线，1/4 屏像滑动、1/10 屏像抽搐）。
 *
 * ## 参数取舍（2026-09 按真机基线收敛过两轮）
 *
 * 基线见 [`docs/specs/frame-perf-design.md`](../../../../../../docs/specs/frame-perf-design.md)：
 * 一轮场景里慢帧 27 条、最慢 244ms，其中「动画」段最大 181.8ms、「绘制」段最大 70.4ms ——
 * 这两笔都是**重叠期两页同时在组合 / 绘制**的代价。
 *
 * - **进场 220ms / 退场淡出 100ms**：Material 3 的常用区间；退场更快，让「返回」更跟手；
 * - **退场只做淡化、不做位移**：见上表 —— 拿掉第二个运动体是这一版最有效的一步；
 * - **同级用 fade-through 而不是交叉淡化**：中段会有一瞬空白，这是有意的取舍 ——
 *   「一段明确的呼吸」比「两张半透明整页叠着」既清楚又便宜（同一时刻只有一页在画）。
 *   它也是 [NavigationShell] 能把导航栏放在切换器外面的前提；
 * - **重页降级成 fade-through，是「方向感换不卡」的显式取舍**：位移每帧要重新 place，
 *   重叠期还要多画一页，而重页的首帧本来就重（真机：进仓库详情 244ms 里 181.8ms 在重组）。
 *   台账判据（别凭感觉加）：**该路由在基线上 ≥3 条慢帧，且主段是「动画」或「绘制」**；
 *   「等待」型不标 —— 那是主线程被占，换过渡形式没有用（见 frame-perf-design.md §6）；
 * - **不自定义 sizeTransform**：两个页面都是全屏、尺寸不变，动画里没有内容可插值。
 *
 * ## 试过又退回去的（别再走一遍）
 *
 * **缩放 + 视差**（2026-09 中间那一版）：新页 94%→100%、旧页缩到 96% 且只走 60% 的距离。
 * 想法是做出「新页盖上来」的纵深，实际观感更晃 —— 全屏内容在位移中缩放会重采样（文字发虚、边缘游移），
 * 而两页速度不同又让相对运动更不稳定。**结论：整页位移期间不要叠加缩放。**
 */
object PageMotion {

    /** 子页进场时长：方向滑动 + 淡入。 */
    const val ENTER_MS = 220

    /**
     * 退场**淡出**时长。
     *
     * 旧页现在是**原地淡出**（零位移，见 [pageTransform]），所以只需要这一段：
     * 淡干净就结束，不再陪着新页滑 —— 「只让一页动」是拿掉抖动感的关键一条。
     */
    const val EXIT_FADE_MS = 100

    /**
     * 位移距离 = 容器宽度的 1/10（见类注释里的取舍）。
     *
     * 2026-09 从 1/4 → 1/8 → 1/10 收了两轮：位移每帧都要重新 place 整页，
     * 而它要表达的只是「新页从右边盖上来」这一个意思 —— 越短越不容易被看成「画面在晃」。
     */
    const val SLIDE_FRACTION = 0.1f

    /**
     * **起播门控**：进场动画延迟这么久才起（2 帧 @60Hz）。
     *
     * 为什么需要它 —— 状态一变，`AnimatedContent` 立刻开始播进场，而**目标页的首次组合正好压在同一帧**：
     * 真机上那一帧能到 100~240ms（见 frame-perf-design.md §5），表现就是「动画刚动一下，画面定住，
     * 然后猛地跳过去」—— 用户对它的描述是**抖**。
     *
     * 加一个两帧的延迟，等于把这段时间挪到动画**开始之前**：目标页先把首帧组合完，
     * 动画一起步就是连续帧。延迟本身低于可感知阈值（2 帧 ≈ 33ms），代价只有 33ms 的起播等待。
     *
     * ⚠️ **进出两段都要加这个延迟**（只门进场的话，头两帧会看到「旧页先暗一下、新页才动」）——
     * 门控要门的是整段过渡。
     *
     * ⚠️ 延迟只能盖住「一两帧」级别的首帧开销；像仓库详情那种 200ms+ 的首帧，得靠页面**首帧瘦身**
     * （骨架先上、内容延后）或数据预取 —— 门控不是万能药，它是把抖动换成一段静止。
     */
    const val ENTER_DELAY_MS = 33

    /**
     * 同级（Tab）/ 重页（fade-through）的**前半段**：旧页淡净的时长。
     *
     * 这两档刻意**不做交叉淡化**（不让两页各半透明地同时在屏）：同一时刻只有一页在画，
     * 是「切 Tab / 进重页」最省的过渡形态。代价是中段有一瞬空白 —— 与 Material 3 的
     * fade through 同款做法：用「一段明确的呼吸」换掉「两张半透明整页叠着的糊图」。
     */
    const val FADE_OUT_MS = 110

    /** fade-through 的**后半段**：新页淡入时长，**延迟 [FADE_OUT_MS] 起** —— 与旧页不重叠。 */
    const val FADE_IN_MS = 180

    /**
     * 进场曲线：**两端零速的 S 曲线，中段比标准曲线更早到半程**。
     *
     * ## 为什么换掉 `LinearOutSlowInEasing`（cubic-bezier `0, 0, 0.2, 1`）
     *
     * 那条曲线的**起步速度是平均速度的 5 倍** —— 位移一短（1/10 屏），它看起来不是「滑进来」
     * 而是「抽一下」。用户的原话是「有种过度抖动感」，而抖动的物理定义就是**速度突变**（急动度）：
     * 端点速度不连续，眼睛就会读成「顿一下 / 抖一下」，与曲线好不好看无关。
     *
     * 这条曲线（cubic-bezier `0.25, 0, 0.15, 1`）的两端速度都是 **0**，中间段比
     * `FastOutSlowInEasing`（`0.4, 0, 0.2, 1`）更早发力 —— 既没有起步弹射，也不会「黏一拍」。
     *
     * 调参只有两个方向：**起手段**（第一个控制点的 x，越小越早发力）与**收尾段**
     * （第二个控制点的 x，越小越早收住）。改完必须真机看一遍，别只看数字。
     */
    val EnterEasing: Easing = CubicBezierEasing(0.25f, 0f, 0.15f, 1f)

    /**
     * 退场曲线：**线性**。
     *
     * 旧页现在只做一件事 —— 原地淡出。而透明度是**没有速度感**的量：
     * 用加速曲线（原先是 `FastOutLinearInEasing`）只会让最后可见的那一两帧掉得特别快，
     * 看起来像「闪一下」；线性最稳，也最容易解释「为什么这里不需要缓动」。
     *
     * 注意：这条曲线**只用于淡化**。真要让某个元素位移着退场（如未来的特殊页面），
     * 它需要的是和 [EnterEasing] 同源的两端零速 S 曲线，而不是这一条。
     */
    val ExitEasing: Easing = LinearEasing
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

    /**
     * 这一页的**首帧重不重**：重页不给方向位移，只做短淡化（见 [transitionKindFor]）。
     *
     * 为什么标在路由上、而不是集中一张「重型页清单」：三个路由（`MainRoute` / `RepoRoute` /
     * `ProfileRoute`）都是各自文件里的 `private sealed interface`，集中清单既拿不到类型、
     * 又会随重构悄悄失效；标在路由上则**改路由的人一定会看到它**。
     *
     * ## 什么时候标（判据，别凭感觉）
     *
     * 该路由在真机基线上 **≥3 条慢帧，且主段是「动画」或「绘制」**（见
     * `docs/specs/frame-perf-design.md` §6）。「等待」型**不标** —— 那是主线程被占，
     * 换过渡形式一点用都没有。当前台账：仓库详情（244ms，动画主导）、设置页（7 条，绘制主导）。
     */
    val heavyFirstFrame: Boolean get() = false
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
            // 同级还做水平滑动会让人误以为进了下一页。
            // 重页优先降级为淡化（[PageLevel.heavyFirstFrame]）—— 这条判定要先于方向。
            when (transitionKindFor(targetState.heavyFirstFrame, initialState.depth, targetState.depth)) {
                TransitionKind.Forward -> pageTransform(1)
                TransitionKind.Back -> pageTransform(-1)
                TransitionKind.Light -> lightTransform()
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

/** 这一格该播哪套过渡。 */
internal enum class TransitionKind { Forward, Back, Light }

/**
 * 选过渡：**重页优先降级，其次才看方向**（纯函数，便于单测）。
 *
 * 顺序不能反。反了的话，重页（[PageLevel.heavyFirstFrame]）在「推进」时照样会拿到位移动画 ——
 * 而那正是要避免的组合：位移每帧重新 place + 重叠期多画一页，压在最重的那一帧上。
 *
 * 抽成纯函数而不是写在 `transitionSpec` 里：`transitionSpec` 是 `@Composable` 作用域内的 lambda，
 * 没法直接单测；而「重页必须降级」这条一旦失守，观感上只会表现为「那几个页面还是卡」，
 * 很难反查到是这里。
 */
internal fun transitionKindFor(heavy: Boolean, initialDepth: Int, targetDepth: Int): TransitionKind =
    if (heavy) {
        TransitionKind.Light
    } else {
        when (pageDirection(initialDepth, targetDepth)) {
            1 -> TransitionKind.Forward
            -1 -> TransitionKind.Back
            else -> TransitionKind.Light
        }
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

/** 同级切换（底部 Tab / 同层页）：没有方向，只做**纯交叉淡化**（2026-09 去掉了 2% 上浮）。 */
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
        transitionSpec = { lightTransform() },
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
 * 轻过渡：**fade-through**（旧页淡净 → 新页再进，两段不重叠）。
 *
 * 同级切换与重页降级共用这一套。它比交叉淡化多花一点时间（[PageMotion.FADE_OUT_MS] +
 * [PageMotion.FADE_IN_MS]），换来的是**同一时刻只有一页在画** —— 这正是基线上最贵的那笔开销
 * （重叠期两页同时在组合 / 绘制，见 `docs/specs/frame-perf-design.md` §5）。
 * 中段那一瞬空白是有意的：它比「两张都看得见、又都看不清」的糊图清楚得多。
 *
 * 2026-09 的形态变更：从「交叉淡化 + 2% 上浮」改成 fade-through ——
 * 上浮那 2% 每帧要重新 place 整页，而它想表达的语义（「内容被换掉了」）现在由时序表达。
 */
private fun lightTransform(): ContentTransform {
    val enter = fadeIn(
        tween(
            PageMotion.FADE_IN_MS,
            delayMillis = PageMotion.FADE_OUT_MS,
            easing = PageMotion.EnterEasing,
        ),
    )
    val exit = fadeOut(tween(PageMotion.FADE_OUT_MS, easing = PageMotion.ExitEasing))
    return enter togetherWith exit
}

/**
 * 子页切换的进出组合：**只让一页动** —— 新页滑入 + 淡入，旧页**原地**淡出。
 *
 * ## 为什么旧页不再位移（2026-09 改）
 *
 * 原先是两页一起动（旧页反向滑出）。两页同时动、曲线还一进一退时，**相对速度一直在变**，
 * 眼睛读到的不是「一页推进」而是「画面在晃」—— 用户的原话是「有种过度抖动感」。
 * 现在旧页零位移：屏幕上只有一个运动体，抖动感失去了来源。
 * 代价是它不再有「两层错开」的纵深（那一版试过视差 + 缩放，反而更晃，见 frame-perf-design.md §6.1）。
 *
 * ## 曲线是这一版的主角（见 [PageMotion.EnterEasing]）
 *
 * 进场曲线两端速度都是 0：起步不弹射、收尾不砸停。抖动的物理定义就是速度突变，
 * 而旧曲线的起步速度是平均速度的 5 倍 —— 位移收到 1/10 屏之后，那一下看起来就是「抽」。
 *
 * [PageMotion.ENTER_DELAY_MS] 是配套的**起播门控**：把目标页首帧的重活挪到动画开始之前。
 *
 * @param dir `+1` = 前进（新内容从右进）；`-1` = 返回（从左侧回来）
 */
private fun pageTransform(dir: Int): ContentTransform {
    // ⚠️ 三处 `tween(...)` 都**就地内联**、不提取成 val：位移要 `FiniteAnimationSpec<IntOffset>`，
    // 透明度要 `FiniteAnimationSpec<Float>` —— 提取成一个 val 就只能二选一，另一处报类型不匹配；
    // 就地写则各自按形参类型推断，不需要显式泛型。
    val enter = slideInHorizontally(
        tween(PageMotion.ENTER_MS, delayMillis = PageMotion.ENTER_DELAY_MS, easing = PageMotion.EnterEasing),
    ) { w ->
        dir * (w * PageMotion.SLIDE_FRACTION).toInt()
    } + fadeIn(
        tween(PageMotion.ENTER_MS, delayMillis = PageMotion.ENTER_DELAY_MS, easing = PageMotion.EnterEasing),
    )

    // 旧页只淡出：零位移、线性、100ms 内干净。
    // **同样延迟** [PageMotion.ENTER_DELAY_MS]：否则头两帧会看到「旧页先暗一下、新页才动」——
    // 门控要门的是整段过渡，只门一半等于把重活留在了另一半上。
    val exit = fadeOut(
        tween(PageMotion.EXIT_FADE_MS, delayMillis = PageMotion.ENTER_DELAY_MS, easing = PageMotion.ExitEasing),
    )

    // 不自定义 sizeTransform：两个页面都是全屏，尺寸不会变（这版 Compose 里它也是 internal）
    return enter togetherWith exit
}

/** 供页面自行组合时复用（例如想给某个特殊页面单独定制动效）；形态与 [pageTransform] 一致。 */
fun pageEnterTransition(dir: Int): EnterTransition =
    slideInHorizontally(
        tween(PageMotion.ENTER_MS, delayMillis = PageMotion.ENTER_DELAY_MS, easing = PageMotion.EnterEasing),
    ) { w ->
        dir * (w * PageMotion.SLIDE_FRACTION).toInt()
    } + fadeIn(
        tween(PageMotion.ENTER_MS, delayMillis = PageMotion.ENTER_DELAY_MS, easing = PageMotion.EnterEasing),
    )

/** 与 [pageEnterTransition] 配对的退场：原地淡出（与 [pageTransform] 的旧页行为一致，同样受门控延迟）。 */
fun pageExitTransition(dir: Int): ExitTransition =
    fadeOut(
        tween(PageMotion.EXIT_FADE_MS, delayMillis = PageMotion.ENTER_DELAY_MS, easing = PageMotion.ExitEasing),
    )
