package com.branchbase.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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
import com.branchbase.ui.theme.color
import com.branchbase.ui.theme.morePanelEnter
import com.branchbase.ui.theme.morePanelExit
import com.branchbase.ui.theme.rememberPressFeedback
import com.branchbase.ui.theme.morph.AnimatedMorphIcon
import com.branchbase.ui.theme.morph.MorphIcons

/** ⋮ 手柄直径。两种底栏（整宽 / 玻璃）共用 —— 48dp 栏高里上下各留 8dp。 */
private val HandleSize = 32.dp

/**
 * 手柄里的图标边长。
 *
 * 18dp 是给 `⋯` 的：三点只占 18×4 的高度。展开态换成 `✕`（占满 18×18）时同样大小会明显变重，
 * 所以两者共用一个略小的 17dp —— ✕ 不至于压过底栏的 Tab 图标，`⋯` 的视觉大小也几乎不变。
 */
private val HandleIconSize = 17.dp

/** 面板与屏幕左右边缘的间距（与底栏的 12dp 内容边距同一口径）。 */
private val PanelSideMargin = 12.dp

/** 面板底边与手柄顶边的间隙。 */
private val PanelBottomGap = 8.dp

/** 面板里一行的高度。比气泡时代的 40dp 略高 —— 现在是通宽列表，触摸目标可以更宽裕。 */
private val PanelRowHeight = 44.dp

/** 面板容器上下各留的 6dp（`Column` 的 vertical padding）。 */
private val PanelPadding = 6.dp

/** 危险项前面的那条分隔线占的高度：1dp 线 + 上下各 4dp。 */
private val PanelDividerRoom = 9.dp

/**
 * 面板的**内容高度**（dp）。用于算进场行程，**不是**布局约束 —— 真正的尺寸仍由内容决定。
 *
 * 行高固定 44dp、标签单行，所以这个值是确定的；拿它算行程，才能让面板**从栏线以下整块抬进来**
 * （而不是「已经在半空、只挪一下」）。
 */
private fun panelHeight(rowCount: Int, hasDanger: Boolean): Dp =
    PanelPadding * 2 + PanelRowHeight * rowCount + if (hasDanger) PanelDividerRoom else 0.dp

/**
 * 「更多」气泡里的一项。
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
 * 「更多」入口的**唯一实现**：⋮ 手柄 + 从底栏上方升起的通宽面板。
 *
 * ## 手柄为什么没有外框了
 *
 * 原先它是「`Gray150` 圆底 + 1dp `BorderEmphasis` 描边」，而 `BorderEmphasis` 在浅色板里是
 * **纯黑**、深色板里是 `#30363D` —— 同一个按钮在浅色下是黑框按钮、深色下几乎没有边。
 * 一个元素在两种主题下的「按钮感」差 10 倍，说明那圈描边**不是承载信息的元素**，是牌子的副产物；
 * 而它坐在一行没有容器的图标里，就成了整条底栏对比度最高的东西（用户反馈的原话：
 * 「最后一位带有那个大黑边」）。
 *
 * 现在的规则只有一条：**常态不画 chrome，展开才实心**。
 * - 收起：透明底 + `IconPrimary` 的 `⋯`（与三个 Tab 图标同一族），按下时浮一层 `Gray150`；
 * - 展开：`Blue500` 实心圆 + 白色 `✕`，比选中 Tab 的浅蓝药丸重一档 —— 它是唯一「正在作用」的东西；
 * - 高亮（当前页落在面板里的某一项）：图标转 `Blue500`，与 Tab 的选中态同一种语言。
 *
 * ## 为什么是「面板」而不是「气泡」、为什么是 `✕`
 *
 * 面板里是 5 个**去处**（星标/项目/任务/设置/登出），不是一串快捷动作 —— 通宽列表比 196dp 的
 * 小气泡更符合「这是一份目录」的读法，触摸目标也从 ≥26dp 变成 44dp 的行高。
 * 展开态用 `✕` 而不是「把三点转 90°」：转完还是 `MoreVert`，讲的仍是「更多」，
 * 而此刻用户需要知道的是「再点一下关掉」。
 *
 * `⋯ ⇄ ✕` 在台账里声明为**降级**（3 条子路径 vs 1 条，形变中间帧会凭空多线），
 * 所以走 `AnimatedMorphIcon` → `AnimatedStateIcon` 的 200ms 交叉淡入 + 缩放。
 *
 * ## 动效
 *
 * 进场是**带过冲的弹簧**（ζ=0.40）+ 短淡入，退场是不过冲的 tween —— 这是
 * `ui-design.md` §3「元素级不做回弹」的唯一显式例外，理由与边界写在那里。
 *
 * ## 交互
 *
 * [expanded] 由调用方持有（仓库页的返回键处理要用它）；组件只上报 [onExpandedChange]。
 * 收起动画播完才卸载 `Popup` —— 提前卸载会把退场的位移直接切掉。
 */
@Composable
fun MoreBubbleHandle(
    expanded: Boolean,
    items: List<MoreBubbleItem>,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** 当前页就落在面板里的某一项（仓库页 PR / 提交 / 设置）：手柄图标跟着主蓝，给个「我在这」的锚点。 */
    highlighted: Boolean = false,
) {
    val popupState = remember { MutableTransitionState(expanded) }
    // 外部收起（切页、返回键）时把过渡态同步过来；只在真的不一致时写，避免和点击打架。
    LaunchedEffect(expanded) {
        if (popupState.targetState != expanded) popupState.targetState = expanded
    }

    val press = rememberPressFeedback()
    val pressed by press.interaction.collectIsPressedAsState()

    // 三种底色合成一条颜色动画：常态透明、按下浮灰、展开实心蓝。
    // 用 `animateColorAsState` 而不是 `selectionColor`：后者只有「选中/未选中」两态。
    val fill by animateColorAsState(
        targetValue = when {
            popupState.targetState -> Primer.Blue500
            pressed -> Primer.Gray150
            else -> Color.Transparent
        },
        animationSpec = tween(ElementMotion.COLOR_MS),
        label = "more-handle-fill",
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // ⋮ 手柄：无框、无常态底色；按钮与面板的锚点都是这个节点。
        Box(
            modifier = Modifier
                .size(HandleSize)
                .graphicsLayer {
                    scaleX = press.scale.value
                    scaleY = press.scale.value
                }
                .clip(CircleShape)
                .background(fill, CircleShape)
                .clickable(
                    interactionSource = press.interaction,
                    indication = LocalIndication.current,
                    role = Role.Button,
                ) { onExpandedChange(!expanded) },
            contentAlignment = Alignment.Center,
        ) {
            AnimatedMorphIcon(
                pair = MorphIcons.MoreOpen,
                target = popupState.targetState,
                tint = when {
                    popupState.targetState -> Color.White
                    highlighted -> Primer.Blue500
                    else -> Primer.IconPrimary
                },
                contentDescription = stringResource(
                    if (expanded) R.string.action_collapse_more_menu else R.string.action_more,
                ),
                modifier = Modifier.size(HandleIconSize),
            )
        }

        // 只有「已展开或正在收起」时才挂载：收起动画播完（isIdle）即卸载，不留透明窗口吃点击。
        if (popupState.currentState || popupState.targetState) {
            val containerWidth = LocalWindowInfo.current.containerSize.width
            val density = LocalDensity.current
            // 面板宽度 = 窗口宽 - 左右边距。拿不到窗口尺寸时（预览/极端情况）退回 280dp，
            // 不退回「按内容撑开」—— 那会让 5 行长度不一的标签把宽度撑得忽宽忽窄。
            val panelWidth = with(density) { containerWidth.toDp() } - PanelSideMargin * 2
            val widthDp = if (panelWidth > 280.dp) panelWidth else 280.dp

            // 行程 = 内容高 + 底缝：面板在 t=0 时**整块落在窗口底边以下**，再抬进来 ——
            // 这才是「从底栏拉开」，而不是「已经在半空、只挪一下」。
            // 上方再留 30% 内容高的空白：ζ=0.40 的过冲约 25% 行程，没有这块余量，
            // 过冲会把面板顶部顶出弹窗窗口裁掉（看起来像「闪了一下」）。
            val contentHeight = panelHeight(items.size, items.any { it.danger })
            val travel = contentHeight + PanelBottomGap
            val springRoom = contentHeight * 0.30f

            val travelPx = with(density) { travel.roundToPx() }
            val enter = remember(travelPx) { morePanelEnter(travelPx) }
            val exit = remember(travelPx) { morePanelExit(travelPx) }

            // 收起只有一处逻辑：点空白、点外部、返回键、选中条目都走它。
            val dismiss: () -> Unit = {
                popupState.targetState = false
                if (expanded) onExpandedChange(false)
            }

            Popup(
                popupPositionProvider = remember { morePanelPosition() },
                onDismissRequest = dismiss,
                properties = PopupProperties(focusable = true),
            ) {
                // 左右留边距给阴影，顶部留出弹簧过冲的余量，底部留出「贴栏 8dp」的间隙 ——
                // 面板底边因此落在手柄顶边上方 8dp，与旧气泡同一条线。
                Box(modifier = Modifier.width(widthDp + PanelSideMargin * 2)) {
                    // 抽屉**之外**的点击层：不画任何东西，只负责「点一下收起」。
                    //
                    // 为什么需要它：弹窗窗口比面板大一圈（左右阴影边距 + 顶部吃过冲的留白），
                    // 这些区域在窗口**以内**，系统的 outside-touch 不会触发 —— 不铺这一层，
                    // 面板上方那条看不见的留白就是「点了没反应」的死区。
                    // 它垫在面板**下面**：面板自己的点击不该穿透到这里。
                    // 用 pointerInput 而不是 clickable：这一层没有视觉，不该在无障碍树里
                    // 多出一个「没有一个字的按钮」；触摸能收到就够了。
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .pointerInput(Unit) { detectTapGestures { dismiss() } },
                    )
                    Box(
                        modifier = Modifier.padding(
                            start = PanelSideMargin,
                            end = PanelSideMargin,
                            top = springRoom,
                            bottom = PanelBottomGap,
                        ),
                    ) {
                        AnimatedVisibility(
                            visibleState = popupState,
                            enter = enter,
                            exit = exit,
                        ) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = Primer.BackgroundPrimary,
                                border = BorderStroke(1.dp, Primer.Border),
                                shadowElevation = 12.dp,
                                // 面板本体的空白处（行间隙、内边距）**吞掉**点击：
                                // 只有「抽屉之外」才收起，点在抽屉里不该关掉它。
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .pointerInput(Unit) { detectTapGestures { } },
                            ) {
                                // 这里的 PanelPadding 必须与 [panelHeight] 里的同源（行程按它算）
                                Column(Modifier.padding(vertical = PanelPadding)) {
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
                                        MoreBubbleRow(
                                            item = item,
                                            onClick = {
                                                dismiss()
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
}

/**
 * 面板定位：**贴住底栏、左右与屏幕对齐**。
 *
 * 窗口内容就是「整屏宽 + 上下留白」，所以 x 恒为 0；纵向让**面板底边**贴在手柄顶边上方 8dp ——
 * 那个 8dp 由内容底部的 [PanelBottomGap] 留白给，所以这里只需要把整个窗口底边对齐到锚点顶部。
 *
 * 面板是通宽的，没有「贴边机型会溢出」的问题（旧气泡 196dp 右对齐手柄时才要夹一次安全边距）。
 */
private fun morePanelPosition(): PopupPositionProvider = object : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset(0, anchorBounds.top - popupContentSize.height)
}

/**
 * 面板里的一行按钮：同色图标底 + 标签（危险项整行用红）。
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
            .height(PanelRowHeight)
            .padding(horizontal = 6.dp)
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
            .padding(horizontal = 8.dp),
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
