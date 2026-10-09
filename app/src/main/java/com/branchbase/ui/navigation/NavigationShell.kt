package com.branchbase.ui.navigation

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.branchbase.ui.theme.ElementMotion
import com.branchbase.ui.theme.Primer
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/** 悬浮槽位的横向留白（栏与屏幕左右边缘的距离）。 */
private val FloatingSlotHorizontal = 12.dp

/** 悬浮槽位的纵向留白（栏与内容区 / 手势条的距离）。 */
private val FloatingSlotVertical = 8.dp

/**
 * 悬浮栏在屏幕底部**实际占掉的高度**：栏高 + 上下留白（不含系统手势条）。
 *
 * 悬浮形态下栏是覆盖层（内容铺满、栏盖在底部），所以页面里贴底的浮动件
 * —— 消息页的筛选悬浮球、它上方的面板、多选条、撤销条 —— 必须自己让开这一段，
 * 否则会被栏压住（2026-10-08：消息页筛选球被悬浮导航栏遮挡）。
 *
 * 上下那 8dp 留白不是装饰：栏自己就带着它，页面只让到 `ContainerHeight` 仍会与栏贴脸。
 */
val FloatingBarReservedHeight: Dp = FloatingSlotVertical * 2 + GlassBackdrop.ContainerHeight

/**
 * 把 [FloatingBarReservedHeight] 下发给页面内容；**占位形态下是 0**
 * （栏已独占一行、内容区本来就在栏上方，页面不长）。页面一律读这个值，不要自己写死 80dp。
 */
val LocalFloatingBarReservedHeight = staticCompositionLocalOf { 0.dp }

/**
 * 带底部导航栏的页面骨架：**导航栏的槽位在这里，不在页面切换器里**。
 *
 * 全项目只有三处「带底部导航栏的骨架」（主界面 / 仓库页 / 个人页），都走这个壳子；
 * 壳子只负责三件事：内容区、导航栏槽位、两者的内边距契约。**导航栏长什么样不归它管** ——
 * [bar] 是调用方传进来的（普通底栏 / 仓库底栏 / 气泡栏 / 玻璃栏），换形态改的是
 * bar 的实现与参数，不是这个壳子，也不用动三处调用点。
 *
 * ## 两种形态：占位（默认）与悬浮（[floating]）
 *
 * | | 占位形态（`floating = false`） | 悬浮形态（`floating = true`） |
 * |---|---|---|
 * | 布局 | `Column`：内容区 + 栏各占一行 | `Box`：内容铺满，栏**盖**在底部 |
 * | 栏身后 | 没有内容（栏自己不透明地占满一行） | 是真实的页面像素 —— 玻璃能采到东西 |
 * | 内边距 | 回传 0（栏已让出位置） | **也是 0**（见下，这里刻意不留白） |
 *
 * 悬浮形态存在的**唯一理由是背景采样**：占位形态里栏身后空无一物，`RenderEffect` 也好、
 * `Modifier.blur` 也好，都只能模糊自己画出来的那点色块 —— 那不是玻璃，是一块半透明塑料。
 * 只有内容真的铺到栏下方，[LocalBackdrop] 那份图层里才有可采的像素。
 *
 * ## 悬浮形态为什么不给尾部留白（这条是采样成立的前提，不是省事）
 *
 * 三处调用点拿到 [PaddingValues] 后都写成 `Box(Modifier.fillMaxSize().padding(contentPadding))`
 * ——那是**容器级**内边距，会把页面整个顶到栏上方。于是栏身后的录制图层里什么都没有，
 * 玻璃采到的是一块纯色：花了一次整页离屏绘制，观感却和「不采样」一模一样。
 *
 * 所以这个形态回传 0：内容从栏下方穿过去，玻璃才有东西可糊。
 * 「滚到底时最后一条能不能从栏后面滚出来」是**各页自己列表的 `contentPadding`** 该管的事
 * （滚动容器的尾部留白不改变视口高度，内容照常从栏后经过），壳子替不了 —— 这是本形态的已知边界。
 *
 * ## 为什么栏必须待在切换器外面（这不是洁癖，是 bug）
 *
 * [PageSwitcher] / [TabSwitcher] 是 `AnimatedContent`，同级切换会播
 * `PageTransitions.lightTransform()`（今天是**纯交叉淡化**；2026-09 之前还带 2% 高的垂直位移）。
 * 栏如果长在切换器**里面**，切一次 Tab 就被动一次：当年那 2% 的位移让旧栏一边淡出一边上移、
 * 新栏从下方一边淡入一边归位，两栏同时在屏上错位叠着 —— 用户看到的就是「切页面时导航栏上下跳」。
 * 位移撤掉之后这条**规则仍然不变**，理由是另外两条：
 * 1. **页面切换只该动页面，不该动外壳**；
 * 2. 悬浮栏要求内容从栏下方穿过去（栏不占位），占位式的 `Scaffold.bottomBar` 做不到；
 *    半透明 / 模糊栏如果还在 `AnimatedContent` 里，父级淡入淡出还会和栏自己的 α 相乘，
 *    模糊层也会被每帧重建（掉帧 + 采样到不该采的东西）。
 *
 * ## 内边距契约
 *
 * [content] 拿到的 [PaddingValues] 由壳子算 —— **两种形态今天都是 0**，
 * 但调用点一律只允许 `Modifier.padding(contentPadding)`，不许自己写死栏高或凑数字：
 * 占位形态的 0 是「栏已独占一行」，悬浮形态的 0 是「内容必须从栏下穿过」（理由见上）。
 * 两者的**含义**不同，将来任一方要留白时，改的也只有壳子这一处。
 *
 * ## 栏的进出由自己负责
 *
 * `barVisible=false`（例如从 Tab 骨架进了全屏详情页）时栏用 [ElementMotion.REVEAL_MS] 收起，
 * 不再被整页动画拖着走；系统手势条的内边距由**栏自己**负责（M3 `NavigationBar` 自带
 * `NavigationBarDefaults.windowInsets`，自定义栏要自己 `navigationBarsPadding()`）。
 * 壳子只在**悬浮**形态下补那一条 —— 那时栏是覆盖层，没有任何人替它让位。
 * 占位形态一条都不补：M3 那条、以及 `RepoBottomBar` / `ProfileBubbleNavigationBar`
 * 自带的那些已经在各自栏里，壳子再加就是叠两层。
 */
@Composable
fun NavigationShell(
    bar: @Composable () -> Unit,
    barVisible: Boolean = true,
    modifier: Modifier = Modifier,
    containerColor: Color = Primer.BackgroundPrimary,
    floating: Boolean = false,
    content: @Composable (PaddingValues) -> Unit,
) {
    if (floating) {
        FloatingNavigationShell(
            bar = bar,
            barVisible = barVisible,
            modifier = modifier,
            containerColor = containerColor,
            content = content,
        )
        return
    }

    Column(modifier = modifier.fillMaxSize().background(containerColor)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            content(PaddingValues(0.dp))
        }

        // 进出用纵向展开/收起而不是位移：位移不改布局尺寸，栏会让出一块空白再"啪"地消失；
        // expandFrom / shrinkTowards 都取 Top —— 内容锚在栏的上沿，观感就是栏从底部升起、向下沉回。
        AnimatedVisibility(
            visible = barVisible,
            enter = expandVertically(
                animationSpec = tween(ElementMotion.REVEAL_MS),
                expandFrom = Alignment.Top,
            ) + fadeIn(tween(ElementMotion.REVEAL_MS)),
            exit = shrinkVertically(
                animationSpec = tween(ElementMotion.REVEAL_MS),
                shrinkTowards = Alignment.Top,
            ) + fadeOut(tween(ElementMotion.REVEAL_MS)),
        ) {
            // 悬浮/玻璃形态的统一承载槽：内容仍可从栏下方穿过，
            // 但导航栏距屏幕边缘与手势条由槽位集中声明，避免各实现重复补 inset。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = FloatingSlotHorizontal,
                        vertical = FloatingSlotVertical,
                    ),
                contentAlignment = Alignment.BottomCenter,
            ) {
                bar()
            }
        }
    }
}

/**
 * 悬浮形态：内容铺满 + 栏盖在底部 + 整页内容录进 [androidx.compose.ui.graphics.layer.GraphicsLayer]
 * 供栏采样。
 *
 * 三个顺序是**刚性**的，换一换就出问题：
 * 1. 录制发生在内容自己的绘制阶段、回放紧跟其后 —— 屏幕上的页面与录到的图层是同一帧，
 *    不像「截屏再糊」那样慢一帧（快速滚动时玻璃里会透出上一帧的画面）；
 * 2. 栏在内容**之后**绘制 —— 否则它采样到的是这一帧还没录完的图层；
 * 3. 录制挂在**铺满全屏**的那一层上，[content] 拿到的内边距是 0 ——
 *    录制层一旦被内边距缩到栏的上沿，玻璃就采不到自己身后的那块像素（采到的是空的）。
 *
 * 录制与采样都由上游 `io.github.kyant0:backdrop` 承担（见 [LocalBackdrop] 的说明）：
 * 本壳子只负责「给哪块内容挂录制、什么时候挂、把图层交给谁」。
 */
@Composable
private fun FloatingNavigationShell(
    bar: @Composable () -> Unit,
    barVisible: Boolean,
    modifier: Modifier,
    containerColor: Color,
    content: @Composable (PaddingValues) -> Unit,
) {
    val liveBackdrop = rememberLayerBackdrop()
    // 静止底：正文 WebView 在屏时用它顶上（见 [LiveBackdropGate]）——
    // 材质（高光 / 投影 / 折射 / 模糊）照旧走 `drawBackdrop`，只是身后换成主题底色。
    // 用 `remember(containerColor)` 把 lambda 钉住，否则每次重组都会新建一个 Backdrop。
    val flatBackdrop = rememberCanvasBackdrop(
        remember(containerColor) { { drawRect(color = containerColor) } },
    )

    // 录制只在「栏可见 **且** 没有 interop 正文在屏 **且** 这个平台真能把采样糊掉」时才挂：
    // 录制是一次额外的整页离屏绘制（内容每帧被画两遍），栏收了还录就是拿滚动帧率换一个
    // 没人看的图层；而 API 31 以下 `blur` 是空操作（`GlassBar` 那边也不会去采样），录了也没人用。
    val live = LiveBackdropGate.allowed
    val record = live && barVisible && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    // 栏占掉多高：**量**出来的，不是推出来的 —— 栏自己带着 `navigationBarsPadding()`，
    // 手势条高度、分屏、横屏都会改这个数，量一次就永远对。首帧还没量到时退回名义值
    // [FloatingBarReservedHeight]（栏高 + 上下留白），所以第一帧不会跳。
    var barHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val reservedBottom = if (!barVisible) {
        0.dp
    } else {
        with(density) { if (barHeightPx > 0) barHeightPx.toDp() else FloatingBarReservedHeight }
    }

    Box(modifier = modifier.fillMaxSize().background(containerColor)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (record) Modifier.layerBackdrop(liveBackdrop) else Modifier),
        ) {
            // 内容照旧铺到栏下方（见文件头），但把「栏占掉多高」下发给页面：
            // 贴底浮动件（筛选球等）按它抬起，列表尾部留白也按它算。
            CompositionLocalProvider(
                LocalFloatingBarReservedHeight provides reservedBottom,
            ) {
                // 刻意回传 0：内容必须铺到栏下方，录制图层里才有可采的像素（见文件头）。
                content(PaddingValues(0.dp))
            }
        }

        AnimatedVisibility(
            visible = barVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(ElementMotion.REVEAL_MS)),
            exit = fadeOut(tween(ElementMotion.REVEAL_MS)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    // 量出「栏到底占了多少」：含手势条内边距 + 上下留白 + 栏高，
                    // 这个数经 `LocalFloatingBarReservedHeight` 下发给页面（见上）。
                    .onSizeChanged { barHeightPx = it.height }
                    .padding(
                        horizontal = FloatingSlotHorizontal,
                        vertical = FloatingSlotVertical,
                    ),
                contentAlignment = Alignment.BottomCenter,
            ) {
                CompositionLocalProvider(
                    LocalBackdrop provides if (live) liveBackdrop else flatBackdrop,
                ) {
                    bar()
                }
            }
        }
    }
}
