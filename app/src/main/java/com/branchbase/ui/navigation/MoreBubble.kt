package com.branchbase.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.branchbase.R
import com.branchbase.ui.theme.ElementMotion
import com.branchbase.ui.theme.Primer
import com.branchbase.ui.theme.TintRole
import com.branchbase.ui.theme.bubbleEnter
import com.branchbase.ui.theme.bubbleExit
import com.branchbase.ui.theme.color
import com.branchbase.ui.theme.rememberPressFeedback
import com.branchbase.ui.theme.selectionColor

/** ⋮ 手柄直径。两种底栏（整宽 / 玻璃）共用 —— 48dp 栏高里上下各留 8dp。 */
private val HandleSize = 32.dp

/** 手柄里的横三点图标边长。 */
private val HandleIconSize = 18.dp

/** 弹层与手柄的间隙。 */
private val BubbleGap = 8.dp

/** 弹层距屏幕边缘的最小留白（贴边机型的右端不越界）。 */
private val BubbleScreenMargin = 8.dp

/** 弹层宽度：四五个短标签刚好一行，不再随内容横向跳动。 */
private val BubbleWidth = 196.dp

/**
 * More 气泡里的一项。
 *
 * [tint] 走 [TintRole]，**不写色值**：图标底是同色 12% 的方块，深浅主题自动跟着走。
 * [danger] 的项（登出）单独排在分隔线之后，文字用危险色 —— 距离本身就是防误触。
 */
@Immutable
data class MoreBubbleItem(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val tint: TintRole,
    val danger: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * 「更多」入口的**唯一实现**：⋮ 手柄 + 从手柄上方弹出的气泡。
 *
 * ## 为什么收到一份
 *
 * 以前个人页有自研的 `Popup` 气泡（彩色图标底 + 逐条错峰入场），仓库页却还是 Material
 * `DropdownMenu`（容器色、行高、图标底全走默认值）—— 同一个 App 里两个「更多」长得不一样，
 * 而且仓库页那份读的还是 Material 基线色。现在两处都接这一个组件：
 * 手柄尺寸、悬停/展开态、气泡容器、按钮行、分隔线与动效只有一处真源。
 *
 * ## 按钮行的渲染规格
 *
 * - 容器：白底（`Primer.BackgroundPrimary`）+ 1dp `Primer.Border` + 16dp 圆角 + 12dp 阴影，
 *   与 `docs/specs/ui-design.md` §1「弹层」一致；
 * - 行：40dp 高，左侧 26dp 同色 12% 圆角方块 + 15dp 图标，右侧 13.5sp/Medium 文案；
 * - 按下整行缩到 0.97（比手柄的 0.94 轻），松手回弹；危险项文字用 `Primer.Red500`。
 *
 * ## 交互
 *
 * [expanded] 由调用方持有（仓库页的返回键处理要用它）；组件只上报 [onExpandedChange]。
 * 收起动画播完才卸载 Popup —— 提前卸载会把退场的缩放/淡出直接切掉。
 */
@Composable
fun MoreBubbleHandle(
    expanded: Boolean,
    items: List<MoreBubbleItem>,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** 当前页就落在气泡里的某一项（仓库页 PR / 提交 / 设置）：手柄图标跟着主蓝，给个「我在这」的锚点。 */
    highlighted: Boolean = false,
) {
    val popupState = remember { MutableTransitionState(expanded) }
    // 外部收起（切页、返回键）时把过渡态同步过来；只在真的不一致时写，避免和点击打架。
    LaunchedEffect(expanded) {
        if (popupState.targetState != expanded) popupState.targetState = expanded
    }

    val press = rememberPressFeedback()
    val rotation = animateFloatAsState(
        targetValue = if (popupState.targetState) 90f else 0f,
        animationSpec = tween(ElementMotion.ICON_MS),
        label = "more-bubble-rotation",
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // ⋮ 手柄：圆底 + 1dp 描边，展开时主蓝实心。按钮与气泡的锚点都是这个节点。
        Box(
            modifier = Modifier
                .size(HandleSize)
                .graphicsLayer {
                    scaleX = press.scale.value
                    scaleY = press.scale.value
                }
                .clip(CircleShape)
                .border(
                    width = 1.dp,
                    color = selectionColor(
                        selected = expanded,
                        on = Primer.Blue500,
                        off = if (highlighted) Primer.Blue500 else Primer.BorderEmphasis,
                    ),
                    shape = CircleShape,
                )
                .background(
                    selectionColor(expanded, on = Primer.Blue500, off = Primer.Gray150),
                    CircleShape,
                )
                .clickable(
                    interactionSource = press.interaction,
                    indication = LocalIndication.current,
                    role = Role.Button,
                ) { onExpandedChange(!expanded) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.MoreHoriz,
                contentDescription = stringResource(
                    if (expanded) R.string.action_collapse_more_menu else R.string.action_more,
                ),
                tint = when {
                    expanded -> Color.White
                    highlighted -> Primer.Blue500
                    else -> Primer.IconPrimary
                },
                modifier = Modifier
                    .size(HandleIconSize)
                    .graphicsLayer { rotationZ = rotation.value },
            )
        }

        // 只有「已展开或正在收起」时才挂载：收起动画播完（isIdle）即卸载，不留透明窗口吃点击。
        if (popupState.currentState || popupState.targetState) {
            val gapPx = with(LocalDensity.current) { BubbleGap.roundToPx() }
            val marginPx = with(LocalDensity.current) { BubbleScreenMargin.roundToPx() }
            Popup(
                popupPositionProvider = remember(gapPx, marginPx) { moreBubblePosition(gapPx, marginPx) },
                onDismissRequest = {
                    popupState.targetState = false
                    if (expanded) onExpandedChange(false)
                },
                properties = PopupProperties(focusable = true),
            ) {
                AnimatedVisibility(
                    visibleState = popupState,
                    enter = bubbleEnter(),
                    exit = bubbleExit(),
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Primer.BackgroundPrimary,
                        border = BorderStroke(1.dp, Primer.Border),
                        shadowElevation = 12.dp,
                        modifier = Modifier.width(BubbleWidth),
                    ) {
                        Column(Modifier.padding(vertical = 6.dp)) {
                            val regular = items.filter { !it.danger }
                            val danger = items.filter { it.danger }
                            val ordered = regular + danger
                            ordered.forEachIndexed { index, item ->
                                if (index == regular.size && danger.isNotEmpty()) {
                                    // 分隔线 + 危险项：与上面「去哪」的条目分开，避免误触
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 4.dp)
                                            .height(1.dp)
                                            .background(Primer.Gray150),
                                    )
                                }
                                AnimatedVisibility(
                                    visible = popupState.currentState,
                                    enter = fadeIn(
                                        tween(ElementMotion.BUBBLE_MS, delayMillis = index * ElementMotion.STAGGER_MS),
                                    ) + slideInVertically(
                                        tween(ElementMotion.BUBBLE_MS, delayMillis = index * ElementMotion.STAGGER_MS),
                                    ) { it / 3 },
                                    exit = fadeOut(tween(90)),
                                ) {
                                    MoreBubbleRow(
                                        item = item,
                                        onClick = {
                                            popupState.targetState = false
                                            if (expanded) onExpandedChange(false)
                                            item.onClick()
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 气泡定位：**右对齐手柄、底边贴在手柄上方**。
 *
 * 锚点就是手柄（[Popup] 的父布局只有手柄大）。左右按 8dp 安全边距夹一下：
 * 手柄贴屏幕右端时气泡向左溢出，不会被窗口裁掉。
 */
private fun moreBubblePosition(gapPx: Int, marginPx: Int): PopupPositionProvider = object : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx)
        val x = (anchorBounds.right - popupContentSize.width).coerceIn(marginPx, maxX)
        val y = (anchorBounds.top - popupContentSize.height - gapPx).coerceAtLeast(marginPx)
        return IntOffset(x, y)
    }
}

/**
 * 气泡里的一行按钮：同色图标底 + 标签（危险项整行用红）。
 *
 * 按下时整行缩到 0.97 —— 行级元素幅度比手柄（0.94）小一点才不会显得在抖。
 */
@Composable
private fun MoreBubbleRow(
    item: MoreBubbleItem,
    onClick: () -> Unit,
) {
    val tint = item.tint.color()
    val press = rememberPressFeedback(pressedScale = 0.97f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .graphicsLayer {
                scaleX = press.scale.value
                scaleY = press.scale.value
            }
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = press.interaction,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(tint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(item.icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(item.labelRes),
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
            color = if (item.danger) Primer.Red500 else Primer.TextPrimary,
        )
    }
}
