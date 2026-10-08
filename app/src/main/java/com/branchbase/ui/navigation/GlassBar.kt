package com.branchbase.ui.navigation

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.ui.theme.LocalIsDarkTheme
import com.branchbase.ui.theme.Primer
import com.branchbase.ui.theme.selectionColor
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import kotlin.math.max
import kotlin.math.min

/** 单个槽位（一个导航项，或末尾的手柄）的边长。 */
private val SlotSize = 44.dp

/** 槽位之间的间距。 */
private val SlotGap = 4.dp

/** 相邻两个槽位的距离（透镜按它换算位置与拉长量）。 */
private val SlotPitch = SlotSize + SlotGap

/** 胶囊内左右各留的余量。 */
private val BarPadding = 6.dp

/** 胶囊高度。 */
private val BarHeight = 56.dp

/** 胶囊形状：取半高，正好是胶囊形。 */
private val BarShape = RoundedCornerShape(28.dp)

/** 透镜形状：`50%` 圆角 —— 静止时是圆，滑动中被拉长时自动变成胶囊。 */
private val LensShape = RoundedCornerShape(percent = 50)

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
 * 悬浮玻璃胶囊 —— 三处底部导航共用的**同一份实现**（主界面 / 仓库页 / 个人页）。
 *
 * ## 为什么抽成一个组件
 *
 * 上一版只有主界面用玻璃，实现就长在 `GlassNavigationBar` 里。仓库页与个人页也要接
 * （跟着设置页那个开关一起切），再抄两遍意味着「两档模糊、折射参数、透镜拉长、API 降级」
 * 各写三份 —— 改一处观感要改三个文件，而且必然有两份会漏。所以这里收口成：
 *
 * ```
 * GlassBar(items = [...], trailing = { ⋮ 手柄 })    ← 形状、槽位、透镜、材质全在这
 * ```
 *
 * 调用方只管「有哪些项、哪个选中、点一下做什么」，**不碰**形状与材质。
 *
 * ## 布局：按槽位，不按权重
 *
 * 每个槽位都是固定的 [SlotSize]，宽高由槽位数算出（[barWidth]）。这样透镜的位置可以
 * **算**出来（`BarPadding + 槽位序号 × SlotPitch`），不需要去测每一项的坐标 —— 少一处
 * 「测了才有值、第一帧还没有」的抖动。代价是项数多时栏会变宽（仓库页 6 个槽 = 296dp），
 * 换来的是三处形态一致。
 *
 * ## 两档采样
 *
 * 选中项与未选中项用**同一份采样**，差别只有参数：底面 `blur(24dp)` 磨砂，
 * 选中项上那块 `blur(2dp) + lens(折射)` 的透镜几乎透明、边缘还会折 ——
 * 「选中透明、其余磨砂」这层对比不是靠颜色堆的，是靠**糊的多少**。
 *
 * ## 降级
 *
 * `LocalBackdrop` 没有值（开关关着、或玻璃栏被单用），或系统低于 API 31（`blur` 是空操作），
 * 就不采样：只画主题色胶囊 + 实心选中圆 —— 与 1.2.2 的观感一致，而不是「透出一张清晰原图」。
 */
@Composable
fun GlassBar(
    items: List<GlassBarItem>,
    modifier: Modifier = Modifier,
    showLabels: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val dark = LocalIsDarkTheme.current
    val surface = Primer.BackgroundSecondary
    val ink = Primer.TextPrimary

    // 采样层由 `NavigationShell` 的悬浮形态下发；再按 API 31 收一道（更低版本连模糊都没有）。
    // 类型是上游的 Backdrop：活采样（LayerBackdrop）与静止底（CanvasBackdrop）都走它，
    // 所以「正文页换成静止底」这件事在这一层完全不可见。
    val backdrop: Backdrop? = LocalBackdrop.current
        ?.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.S }
    val sampled = backdrop != null

    val slotCount = items.size + if (trailing != null) 1 else 0
    val barWidth = barWidth(slotCount)

    // 透镜落在哪个槽位：选中的项优先；**一项都没选中**时才落到手柄槽
    // （仓库页的「议题/流程/发布」等页面只存在于 ⋮ 菜单里，那时高亮该给手柄）。
    val selectedItem = items.indexOfFirst { it.selected }
    val lensSlot = if (selectedItem >= 0) selectedItem else (if (trailing != null) items.size else 0)

    // 液态：选中项一变就**滑**过去，滑动途中按 min/max 在两项之间拉长、到站收成圆。
    val lensIndex = remember { Animatable(lensSlot.toFloat(), visibilityThreshold = 0.001f) }
    LaunchedEffect(lensSlot, sampled) {
        if (!sampled) {
            // 没有采样层时透镜根本不存在，直接把位置对齐，别留一个看不见的动画在跑。
            lensIndex.snapTo(lensSlot.toFloat())
        } else {
            lensIndex.animateTo(
                targetValue = lensSlot.toFloat(),
                animationSpec = spring(
                    dampingRatio = GlassBackdrop.LensDampingRatio,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // ① 未选中态：磨砂玻璃。模糊、边缘折射高光、投影全由上游按圆角 SDF 算。
        if (backdrop != null) {
            Box(
                modifier = Modifier
                    .width(barWidth)
                    .height(BarHeight)
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { BarShape },
                        effects = { blur(GlassBackdrop.BlurRadius.toPx()) },
                    ),
            )
        }

        // ② 主题色玻璃：底色 + 顶部镜面高光。
        // 有采样时底色压到 55% —— 底色不降，采进来的背景会被自己盖住，那次离屏绘制就白花了。
        Box(
            modifier = Modifier
                .width(barWidth)
                .height(BarHeight)
                .clip(BarShape)
                .background(glassTint(surface, ink, dark, sampled))
                .background(glassSheen(dark)),
        )

        // ③ 液态透镜：选中项用同一份采样、几乎不糊 + 边缘折射 —— 「透明液态」的那一块。
        if (backdrop != null) {
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.TopStart) {
                val lo = min(lensIndex.value, lensSlot.toFloat())
                val hi = max(lensIndex.value, lensSlot.toFloat())
                Box(
                    modifier = Modifier
                        .offset(x = BarPadding + SlotPitch * lo, y = BarPadding)
                        .width(SlotSize + SlotPitch * (hi - lo))
                        .height(SlotSize)
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { LensShape },
                            effects = {
                                blur(GlassBackdrop.LensBlurRadius.toPx())
                                lens(
                                    refractionHeight = GlassBackdrop.RefractionHeight.toPx(),
                                    refractionAmount = GlassBackdrop.RefractionAmount.toPx(),
                                    depthEffect = GlassBackdrop.RefractionDepthEffect,
                                )
                            },
                        ),
                ) {
                    // 极淡的选中色：透镜本身是透明的，不留一点颜色就看不出「选中了哪个」。
                    Box(
                        Modifier
                            .matchParentSize()
                            .clip(LensShape)
                            .background(Primer.Blue500.copy(alpha = 0.12f)),
                    )
                    // 顶部镜面高光（边缘那圈由上游的 highlight 负责，这里只补面）。
                    Box(
                        Modifier
                            .matchParentSize()
                            .clip(LensShape)
                            .background(
                                Brush.verticalGradient(
                                    0f to Color.White.copy(alpha = if (dark) 0.14f else 0.30f),
                                    0.55f to Color.Transparent,
                                ),
                            ),
                    )
                }
            }
        }

        // ④ 槽位：导航项 + 可选的手柄
        Row(
            modifier = Modifier
                .clip(BarShape)
                .padding(BarPadding),
            horizontalArrangement = Arrangement.spacedBy(SlotGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                GlassBarSlot(item = item, showLabel = showLabels, sampled = sampled)
            }
            if (trailing != null) {
                Box(Modifier.size(SlotSize), contentAlignment = Alignment.Center) { trailing() }
            }
        }
    }
}

/** 一个导航项：图标（+ 可选文字）+ 点击；选中态在没有采样时才用实心圆表达。 */
@Composable
private fun GlassBarSlot(
    item: GlassBarItem,
    showLabel: Boolean,
    sampled: Boolean,
) {
    val label = stringResource(item.labelRes)
    Column(
        modifier = Modifier
            .size(SlotSize)
            .clip(CircleShape)
            // 有透镜时，选中态由**透镜本身**表达；再叠一层实心蓝会把透明玻璃盖死。
            .background(selectionColor(item.selected && !sampled, on = Primer.Blue500))
            .clickable(onClick = item.onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            item.icon,
            contentDescription = label,
            tint = selectionColor(
                item.selected,
                // 透明透镜上不能再用白图标：底面可能是浅色页面，白图标直接消失。
                on = if (sampled) Primer.Blue500 else Color.White,
                off = Primer.IconPrimary,
            ),
            modifier = Modifier.size(if (showLabel) 20.dp else 22.dp),
        )
        if (showLabel) {
            Spacer(Modifier.height(2.dp))
            Text(
                label,
                fontSize = 9.5.sp,
                color = selectionColor(item.selected, on = Primer.Blue500, off = Primer.TextTertiary),
                fontWeight = if (item.selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
        }
    }
}

/**
 * 胶囊宽度：槽位 + 间距 + 内边距。
 *
 * 采样层、玻璃层、透镜、按钮四处都从这里取 —— 手写第二遍数字，改项数时就会错位。
 */
private fun barWidth(slotCount: Int): Dp =
    SlotSize * slotCount + SlotGap * (slotCount - 1).coerceAtLeast(0) + BarPadding * 2

/**
 * 玻璃底色（半透明）—— 「玻璃什么颜色」的**唯一真源**。
 *
 * 参数只取两个主题角色（[surface] = `BackgroundSecondary`、[ink] = `TextPrimary`）加深浅标志：
 * - **浅色**：白玻璃（顶部最亮）+ [surface] 收底 + [ink] 压一层极淡的下沿暗色（厚度感）；
 * - **深色**：以 [surface]（`#161B22`）为主体，白只以极低的 alpha 参与 ——
 *   固定高 alpha 的白叠在深底上，正是一条亮带的来源。
 *
 * [sampled] = 栏身后有真实可采的页面像素：底色整体压到 55%。这不是审美选择 ——
 * 底色不降，采进来的背景会被它盖住，等于花了一次整页离屏绘制却什么也看不见。
 */
private fun glassTint(surface: Color, ink: Color, dark: Boolean, sampled: Boolean): Brush {
    val body = if (sampled) 0.55f else 1f
    fun a(alpha: Float): Float = alpha * body

    return if (dark) {
        Brush.verticalGradient(
            listOf(
                surface.copy(alpha = a(0.92f)),
                surface.copy(alpha = a(0.72f)),
                surface.copy(alpha = a(0.80f)),
                Color.Black.copy(alpha = a(0.34f)),
            ),
        )
    } else {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = a(0.70f)),
                Color.White.copy(alpha = a(0.50f)),
                surface.copy(alpha = a(0.34f)),
                ink.copy(alpha = a(0.10f)),
            ),
        )
    }
}

/**
 * 顶部镜面高光：只覆盖上半部分，模拟「光从上方来」。
 *
 * 与 [glassTint] 分开是因为职责不同：底色跟着主题色板走，高光是**材质**（与身后、
 * 与色板都无关），所以它只在深浅之间分档，不乘 [glassTint] 的 `body` 系数。
 */
private fun glassSheen(dark: Boolean): Brush = Brush.verticalGradient(
    0f to Color.White.copy(alpha = if (dark) 0.10f else 0.55f),
    0.52f to Color.Transparent,
)
