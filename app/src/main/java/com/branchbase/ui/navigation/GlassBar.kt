package com.branchbase.ui.navigation

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.branchbase.ui.theme.LocalIsDarkTheme
import com.branchbase.ui.theme.Primer
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** 胶囊形状：`50%` 圆角 —— 上游 catalog 用 `Capsule()`，这里用等价的内置形状，不引新依赖。 */
private val CapsuleShape = RoundedCornerShape(percent = 50)

/**
 * 玻璃栏里的一个导航项。
 *
 * [selected] 由调用方算好（不同页面的「选中」判据不一样：主界面比枚举、仓库页比页面、
 * 个人页比 Tab），玻璃栏只认这个布尔值，不掺和任何路由判断。
 */
@Immutable
data class GlassBarItem(
    val icon: ImageVector,
    @StringRes val labelRes: Int,
    val selected: Boolean,
    val onClick: () -> Unit,
)

/**
 * 按下时图标的放大系数 —— 由**录制层**读，不是由可见的那一层读。
 *
 * 为什么只给录制层：录制层被 `ColorFilter.tint(强调色)` 染过、又被滑块采样，
 * 于是「按下去图标变大」这件事只在**玻璃里面**发生 —— 可见的那一层要是也放大，
 * 就会顶到滑块外面去，穿帮。
 */
private val LocalGlassTabScale = staticCompositionLocalOf<() -> Float> { { 1f } }

/**
 * 悬浮液态玻璃胶囊 —— 三处底部导航共用的**同一份实现**（主界面 / 仓库页 / 个人页）。
 *
 * ## 这一版的来历：从「两档模糊」换成上游 catalog 的实现
 *
 * 1.2.3 的玻璃栏是自研形状 + 两种模糊半径（未选中 `blur(24dp)`、选中 `blur(2dp) + lens`）。
 * 现在换成上游 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)
 * catalog 里 `LiquidBottomTabs` / `LiquidBottomTab` 的结构（固定在上游 `65ab177` / tag `2.0.1`），
 * 理由有三条，都不是审美：
 *
 * 1. **选中态不再靠模糊档位**：滑块几乎不糊，它折射的是身后一张**被染成强调色**的录制层 ——
 *    于是选中项是「彩色 + 边缘色散」的，未选项是「去色磨砂」的。两个半径的差别变成了
 *    「有颜色 / 没颜色」的差别，在浅色和深色背景下都立得住；
 * 2. **材质齐了**：`vibrancy()`（增色）/ `Highlight`（边缘高光）/ `Shadow` + `InnerShadow`
 *    （厚度）都进了同一条链，厚度由**按下进度**驱动 —— 按下去玻璃变厚，这是旧版没有的；
 * 3. **运动齐了**：[DampedDragAnimation] 的四个弹簧（位移 / 按压 / 形变 / 速度）取代了旧版
 *    单个位移弹簧 —— 旧版只会「滑过去」，新版会「被拉长过去、甩动时先压扁再归位」。
 *
 * ## 三层结构（顺序是刚性的，换一换就不成立）
 *
 * | 层 | 节点 | 作用 |
 * |---|---|---|
 * | ① 容器 | 可见的 `Row` + `drawBackdrop` | 整条栏的玻璃：增色 + 磨砂 + 边缘折射 |
 * | ② 录制层 | `alpha(0f)` + `layerBackdrop(tabsBackdrop)` + `tint(强调色)` | 把「染色后的内容」录进第二个图层，屏幕上看不见 |
 * | ③ 滑块 | `drawBackdrop(combined(页面, ②))` | 采样**页面 + 染色层**合体，于是滑块里透出的是强调色图标 |
 *
 * ①画在②之前、③画在最后：③采样②（已录完），①的图标被③盖住 ——
 * 「滑块里是彩色图标、滑块外是灰色图标」正是这么来的。
 *
 * ## 降级
 *
 * `LocalBackdrop` 没有值（开关关着、或玻璃栏被单用）或系统低于 API 31（`blur` 是空操作）：
 * 不采样、不录制、不画滑块，只画主题色胶囊 + 选中项的实心圆 —— 与 1.2.3 之前的观感一致，
 * 而不是「透出一张清晰原图」。
 */
@Composable
fun GlassBar(
    items: List<GlassBarItem>,
    modifier: Modifier = Modifier,
    showLabels: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val dark = LocalIsDarkTheme.current
    val containerColor = Primer.BackgroundSecondary.copy(alpha = GlassBackdrop.ContainerColorAlpha)
    val accentColor = Primer.Blue500

    // 采样层由 `NavigationShell` 的悬浮形态下发；再按 API 31 收一道（更低版本连模糊都没有）。
    // 类型是上游的 Backdrop：活采样（LayerBackdrop）与静止底（CanvasBackdrop）都走它，
    // 所以「正文页换成静止底」这件事在这一层完全不可见。
    val backdrop: Backdrop? = LocalBackdrop.current
        ?.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.S }
    val sampled = backdrop != null

    val tabsCount = items.size
    // 末尾的手柄（⋮）也是一个槽位：整宽分格时它得占一格，否则项会被拉宽、滑块位置全错。
    // 但它**不是**滑块的目标 —— 滑块只在真正的导航项之间移动。
    val cellCount = (tabsCount + if (trailing != null) 1 else 0).coerceAtLeast(1)

    val selectedSlot = items.indexOfFirst { it.selected }.coerceAtLeast(0)
    val tabsBackdrop = rememberLayerBackdrop()
    val animationScope = rememberCoroutineScope()

    BoxWithConstraints(modifier, contentAlignment = Alignment.CenterStart) {
        val density = LocalDensity.current
        val tabWidth = with(density) {
            (constraints.maxWidth.toFloat() - (GlassBackdrop.BarPadding * 2).toPx()) / cellCount
        }
        // 拖动时整条栏跟着位移一点（上游的 `panelOffset`）：位移量与拖动比例同号、
        // 用 EaseOut 收口，越拖越推不动 —— 观感是「栏被手指拽着走，但拽不动」。
        val panelShiftAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density) {
            derivedStateOf {
                val fraction = (panelShiftAnimation.value / constraints.maxWidth).fastCoerceIn(-1f, 1f)
                with(density) {
                    GlassBackdrop.PanelShift.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr

        // 拖动回调在 remember 里，闭包会钉住建它时的宽度；把宽度过一道 state，
        // 旋转/分屏改宽之后拖动比例仍然对（上游在这一点上是钉死的）。
        val tabWidthState = rememberUpdatedState(tabWidth)

        // 这里必须用 `remember {}`（**不能**把 selectedSlot 当 key）：
        // 带 key 的话，外部选中项一变就会**换掉这个 state 对象**，而下面
        // `LaunchedEffect(drag) { snapshotFlow { currentSlot } … }` 是长活的 —— 它观察的是旧对象，
        // 旧对象此后不再变化 ⇒ 流永不重发 ⇒ `animateToValue` 不被调用 ⇒ **滑块停在上一个槽位不动**。
        // 滑块带着 pointerInput 压在旧槽位上，就把那一格的点按**吞掉**了。
        // 2026-10-08 设备复现：主 Tab → 消息 Tab 成功；再点主 Tab 完全没反应，
        // 应用日志里连一条页面切换记录都没有（点按被滑块吃掉，onClick 根本没触发）。
        var currentSlot by remember { mutableIntStateOf(selectedSlot) }

        val drag = remember(animationScope) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedSlot.toFloat(),
                valueRange = 0f..(tabsCount - 1).coerceAtLeast(0).toFloat(),
                visibilityThreshold = GlassBackdrop.VisibilityThreshold,
                initialScale = 1f,
                pressedScale = GlassBackdrop.PressScaleFactor,
                onDragStarted = {},
                onDragStopped = {
                    val targetSlot = targetValue
                        .fastRoundToInt()
                        .fastCoerceIn(0, (tabsCount - 1).coerceAtLeast(0))
                    currentSlot = targetSlot
                    animateToValue(targetSlot.toFloat())
                    animationScope.launch {
                        panelShiftAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                    }
                },
                onDrag = { _, dragAmount ->
                    val width = tabWidthState.value
                    updateValue(
                        (targetValue + dragAmount.x / width * if (isLtr) 1f else -1f)
                            .fastCoerceIn(0f, (tabsCount - 1).coerceAtLeast(0).toFloat()),
                    )
                    animationScope.launch {
                        panelShiftAnimation.snapTo(panelShiftAnimation.value + dragAmount.x)
                    }
                },
            )
        }

        // 外部改选中（点设置、深链、返回）→ 内部滑块跟上。
        LaunchedEffect(selectedSlot) {
            snapshotFlow { selectedSlot }
                .collectLatest { slot -> currentSlot = slot }
        }
        // 内部改选中（点击 / 拖动落位）→ 通知外部。`drop(1)` 跳过初值，
        // 再比对一次当前选中项：外部驱动的那次回流不该被当成「用户又点了一次」，
        // 否则同一次切换会回调两遍（页面被重设两次、日志多一条）。
        //
        // `latestSelectedSlot` 不是装饰：这个 Effect 的 key 是 `drag`，**不随**选中项变化重启，
        // 所以直接读 `selectedSlot` 读到的是「Effect 启动那一刻」的快照。拿快照当判据会出现：
        // 第 0 项 → 第 1 项（守卫 `1 != 0` 成立，切换成功）；再从第 1 项点回第 0 项时守卫算的是
        // `0 != 0` → 不成立 → **回调不发**，于是只有滑块滑回去、页面不动 ——
        // 也就是「虚拟屏里点回去没反应」（2026-10-08 复现）。
        val latestSelectedSlot = rememberUpdatedState(selectedSlot)
        LaunchedEffect(drag) {
            snapshotFlow { currentSlot }
                .drop(1)
                .collectLatest { slot ->
                    drag.animateToValue(slot.toFloat())
                    if (slot != latestSelectedSlot.value) {
                        items.getOrNull(slot)?.onClick?.invoke()
                    }
                }
        }

        val highlight = remember(animationScope) {
            InteractiveHighlight(
                animationScope = animationScope,
                position = { size, _ ->
                    Offset(
                        if (isLtr) {
                            (drag.value + 0.5f) * tabWidth + panelOffset
                        } else {
                            size.width - (drag.value + 0.5f) * tabWidth + panelOffset
                        },
                        size.height / 2f,
                    )
                },
            )
        }

        // ① 容器：整条栏的玻璃。有采样才接库，没有就退回主题色实心胶囊。
        val containerModifier: Modifier = if (backdrop != null) {
            Modifier.drawBackdrop(
                backdrop = backdrop,
                shape = { CapsuleShape },
                effects = {
                    vibrancy()
                    blur(GlassBackdrop.ContainerBlurRadius.toPx())
                    lens(
                        refractionHeight = GlassBackdrop.ContainerRefractionHeight.toPx(),
                        refractionAmount = GlassBackdrop.ContainerRefractionAmount.toPx(),
                    )
                },
                layerBlock = {
                    // 按住整条栏时横向拉伸（上游 `lerp(1f, 1f + 16dp / 宽度, pressProgress)`）：
                    // 拉伸量按**相对宽度**算，换台机器不会一个变成铁板一个变成橡皮筋。
                    val scale = lerp(
                        1f,
                        1f + GlassBackdrop.PressStretch.toPx() / size.width,
                        drag.pressProgress,
                    )
                    scaleX = scale
                    scaleY = scale
                },
                onDrawSurface = { drawRect(containerColor) },
            )
        } else {
            Modifier.clip(CapsuleShape).background(Primer.BackgroundSecondary)
        }

        Row(
            Modifier
                .graphicsLayer { translationX = panelOffset }
                .then(containerModifier)
                .then(if (sampled) highlight.modifier else Modifier)
                .height(GlassBackdrop.ContainerHeight)
                .fillMaxWidth()
                .padding(GlassBackdrop.BarPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassBarCells(items, showLabels, sampled, trailing)
        }

        // ② 录制层：把「染成强调色的内容」录进采样图层。屏幕上完全看不见（alpha(0f)），
        // 但它必须真的被画一遍，否则滑块采样到的是空的 —— 这是这一版最容易被删掉的一层。
        if (backdrop != null) {
            CompositionLocalProvider(
                LocalGlassTabScale provides {
                    lerp(1f, GlassBackdrop.TabPressedScale, drag.pressProgress)
                },
            ) {
                Row(
                    Modifier
                        .clearAndSetSemantics {}
                        .alpha(0f)
                        .layerBackdrop(tabsBackdrop)
                        .graphicsLayer { translationX = panelOffset }
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { CapsuleShape },
                            effects = {
                                val progress = drag.pressProgress
                                vibrancy()
                                blur(GlassBackdrop.ContainerBlurRadius.toPx())
                                lens(
                                    refractionHeight = GlassBackdrop.ContainerRefractionHeight.toPx() * progress,
                                    refractionAmount = GlassBackdrop.ContainerRefractionAmount.toPx() * progress,
                                )
                            },
                            highlight = { Highlight.Default.copy(alpha = drag.pressProgress) },
                            onDrawSurface = { drawRect(containerColor) },
                        )
                        .then(highlight.modifier)
                        .height(GlassBackdrop.PillHeight)
                        .fillMaxWidth()
                        .padding(horizontal = GlassBackdrop.BarPadding)
                        .graphicsLayer { colorFilter = ColorFilter.tint(accentColor) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 这一行必须用 `sampled = true`（=「有采样层」的**正常**样式），**不能**传 false：
                    // 传 false 会让单元格画成「降级样式」—— 实心 Primer.Blue500 底 + 白色图标；
                    // 而这一层紧接着会被 `ColorFilter.tint(accentColor)` 整片染色，白色图标因此变成
                    // 「强调色画在强调色上」→ 屏幕上看不见图标，滑块静止时就是一块**纯蓝方块**
                    // （2026-10-08 虚拟屏复现：静止态蓝色胶囊里没有房子图标）。
                    // 正确做法是只录**图标本身**（正常色），让外层的 tint 去上色。
                    GlassBarCells(items, showLabels, sampled = true, trailing = trailing)
                }
            }
        }

        // ③ 滑块：采样 `页面 × 染色层` 的合体图层，于是滑块里透出强调色图标 + 边缘色散。
        if (backdrop != null && tabsCount > 0) {
            Box(
                Modifier
                    .padding(horizontal = GlassBackdrop.BarPadding)
                    .graphicsLayer {
                        translationX = if (isLtr) {
                            drag.value * tabWidth + panelOffset
                        } else {
                            size.width - (drag.value + 1f) * tabWidth + panelOffset
                        }
                    }
                    // 手势挂在滑块上（上游同构）。代价要说清：滑块压在**选中格**之上，
                    // 所以「点当前已选中那一格」是无效的 —— 那本来就没什么可做。
                    // 真正要保证的是**滑块必须始终跟着真实选中项走**（见上面 `currentSlot` 的注释）：
                    // 一旦它落后一格，被吃掉的就是**另一格**的点按，表现就是「切过去回不来」。
                    .then(highlight.gestureModifier)
                    .then(drag.modifier)
                    .drawBackdrop(
                        backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                        shape = { CapsuleShape },
                        effects = {
                            val progress = drag.pressProgress
                            lens(
                                refractionHeight = GlassBackdrop.PillRefractionHeight.toPx() * progress,
                                refractionAmount = GlassBackdrop.PillRefractionAmount.toPx() * progress,
                                chromaticAberration = true,
                            )
                        },
                        highlight = { Highlight.Default.copy(alpha = drag.pressProgress) },
                        shadow = { Shadow(alpha = drag.pressProgress) },
                        innerShadow = {
                            InnerShadow(
                                radius = GlassBackdrop.PillInnerShadowRadius * drag.pressProgress,
                                alpha = drag.pressProgress,
                            )
                        },
                        layerBlock = {
                            // 速度先把滑块**横向压扁**再归位（X 吃的速度是 Y 的三倍）：
                            // 甩动时像一滴被拉开的液体，而不是一个硬块平移。
                            scaleX = drag.scaleX
                            scaleY = drag.scaleY
                            val velocity = drag.velocity / 10f
                            scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                            scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                        },
                        onDrawSurface = {
                            val progress = drag.pressProgress
                            drawRect(
                                if (dark) {
                                    Color.White.copy(alpha = GlassBackdrop.PillScrimAlpha)
                                } else {
                                    Color.Black.copy(alpha = GlassBackdrop.PillScrimAlpha)
                                },
                                alpha = 1f - progress,
                            )
                            drawRect(Color.Black.copy(alpha = GlassBackdrop.PillPressScrimAlpha * progress))
                        },
                    )
                    .height(GlassBackdrop.PillHeight)
                    .fillMaxWidth(1f / cellCount),
            )
        }
    }
}

/**
 * 一行里的所有槽位：导航项 + 可选手柄。
 *
 * 两个 Row（可见的那一层与录制的那一层）**必须用同一份内容**，否则滑块里透出的
 * 会和屏幕上显示的错位一格。
 */
@Composable
private fun RowScope.GlassBarCells(
    items: List<GlassBarItem>,
    showLabels: Boolean,
    sampled: Boolean,
    trailing: (@Composable () -> Unit)?,
) {
    items.forEach { item ->
        GlassBarCell(item = item, showLabels = showLabels, sampled = sampled)
    }
    if (trailing != null) {
        Box(
            modifier = Modifier.weight(1f).fillMaxHeight().padding(2.dp),
            contentAlignment = Alignment.Center,
        ) { trailing() }
    }
}

/** 一个导航项：图标（+ 可选文字）+ 点击；选中态在没有采样时才用实心圆表达。 */
@Composable
private fun RowScope.GlassBarCell(
    item: GlassBarItem,
    showLabels: Boolean,
    sampled: Boolean,
) {
    val scale = LocalGlassTabScale.current
    val label = stringResource(item.labelRes)
    val solidSelection = item.selected && !sampled

    Column(
        modifier = Modifier
            .clip(CapsuleShape)
            // 自己画高光与形变，所以不要水波纹（上游同样传 null/null）。
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Tab,
                onClick = item.onClick,
            )
            .fillMaxHeight()
            .weight(1f)
            .graphicsLayer {
                val current = scale()
                scaleX = current
                scaleY = current
            }
            .background(if (solidSelection) Primer.Blue500 else Color.Transparent, CapsuleShape),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            item.icon,
            contentDescription = label,
            // 有滑块时，选中态由**滑块折射出来的染色内容**表达；这里再涂一层强调色会打架。
            tint = if (solidSelection) Color.White else Primer.IconPrimary,
            modifier = Modifier.size(if (showLabels) 22.dp else 24.dp),
        )
        if (showLabels) {
            Text(
                label,
                fontSize = 10.sp,
                color = if (solidSelection) Color.White else Primer.TextTertiary,
                fontWeight = if (item.selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
        }
    }
}
