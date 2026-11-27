package com.branchbase.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.branchbase.ui.theme.ElementMotion
import com.branchbase.ui.theme.Primer

/**
 * 浮层抽屉：**自绘**的「遮罩 + 贴底白面板」，全 App 的浮层选择器共用这一份容器。
 *
 * ## 为什么不用 Material3 的 `ModalBottomSheet`
 *
 * 1.4.89 / 1.4.90 两版都在调「收起」：先是直接 `drawerOpen = false`（当帧消失），
 * 再是 `hide()` + 等 `currentValue == Hidden` 才摘（能看到了，但**快到读不出来**）。
 * 根因在 M3 1.4 的实现：`ModalBottomSheet` 是 **Dialog**，`targetValue` 一变 Hidden 就回调
 * `onDismissRequest`，对话框的生死不归调用方管 —— 收起的时长与曲线也就调不动。
 *
 * 现在整个面板是自绘的：`Dialog(usePlatformDefaultWidth = false)` 只做**全屏容器**
 * （这样才盖得住状态栏与底栏），里面只有两样东西 —— 遮罩 + 一块 `AnimatedVisibility` 面板，
 * 升降都是 [ElementMotion.SHEET_MS]（240ms）的 tween，时长、曲线、何时摘对话框全在我们手里。
 *
 * 为什么不用 More 面板那条 ζ=0.40 的弹簧：那条回弹是「低频、锚点明确」的显式例外；
 * 这里是从底部升起的一个选择面板，回弹会读成「面板没停稳」，tween 的收尾更干净。
 *
 * ## 契约
 *
 * - [onDismiss] 在**退场真的播完**（`isIdle` 且目标为 false）之后才回调，调用方据此摘掉自己
 *   的 `drawerOpen`；提前摘 = 位移被切掉，也就是「闪一下」；
 * - 点击遮罩 / 返回键 / 面板里的显式动作都只把过渡态目标设为 false，**不直接消失**；
 * - [content] 拿到一个 `close()`：内容里「选完就走」的行用它触发退场（先落状态、再收起）；
 * - 面板本体的空白处**吞掉**点击：只有点在遮罩上才收起，点在面板里不该关掉它。
 *
 * 个人页的语言筛选抽屉与搜索页的「搜索选项」抽屉走的是同一份实现。
 */
@Composable
fun CardDrawer(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** 标题行右侧的动作（例如「完成」）。拿到 `close()`，自己决定什么时候触发退场。 */
    trailing: @Composable (close: () -> Unit) -> Unit = {},
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    // 首帧就把目标置 true：面板从屏幕下沿之外滑上来。之后不再写它 ——
    // 否则「收起到一半又被置回 true」会把退场打断。
    val state = remember { MutableTransitionState(false).apply { targetState = true } }
    // 退场真的播完（isIdle 且目标为 false）才让上层摘掉对话框。
    LaunchedEffect(state.isIdle) {
        if (state.isIdle && !state.targetState) onDismiss()
    }
    val close: () -> Unit = { state.targetState = false }
    // 遮罩只有「有/无」两态，但跟着面板一起走 240ms，不然面板还在升、背景已经定了
    val scrim by animateFloatAsState(
        targetValue = if (state.targetState) 1f else 0f,
        animationSpec = tween(ElementMotion.SHEET_MS),
        label = "card-drawer-scrim",
    )

    Dialog(
        onDismissRequest = close,
        // 默认宽度的对话框只有中间一条；这里要通屏的遮罩与贴底的面板
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize()) {
            // 遮罩：只负责「点一下收起」。用 pointerInput 而不是 clickable ——
            // 它没有文字，不该在无障碍树里多出一个「没有一个字的按钮」。
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f * scrim))
                    .pointerInput(Unit) { detectTapGestures { close() } },
            )
            AnimatedVisibility(
                visibleState = state,
                // 行程 = 面板自身高度：退场时整块落到屏幕下沿之外，读作「滑下去」而不是「淡掉」
                enter = slideInVertically(tween(ElementMotion.SHEET_MS)) { it } +
                    fadeIn(tween(ElementMotion.PANEL_FADE_IN_MS)),
                exit = slideOutVertically(tween(ElementMotion.SHEET_MS)) { it } +
                    fadeOut(tween(ElementMotion.SHEET_MS)),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                val panelShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
                Column(
                    modifier
                        .fillMaxWidth()
                        .shadow(elevation = 16.dp, shape = panelShape, clip = false)
                        .background(Primer.BackgroundPrimary, panelShape)
                        .pointerInput(Unit) { detectTapGestures { } }
                        .navigationBarsPadding()
                        .padding(horizontal = 14.dp)
                        .padding(top = 14.dp, bottom = 12.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            title,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Primer.TextPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        trailing(close)
                    }
                    Spacer(Modifier.height(10.dp))
                    content(close)
                }
            }
        }
    }
}
