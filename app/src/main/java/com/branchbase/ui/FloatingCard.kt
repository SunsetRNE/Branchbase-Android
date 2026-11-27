package com.branchbase.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.branchbase.ui.theme.rememberPressFeedback

/**
 * 单行浮层小卡的圆角：**9dp**，比页面卡片的 12dp 小一档。
 *
 * 只有一行字高的卡用 12dp 会读成胶囊，9dp 才还是「一张卡」——这是 1.4.84
 * 「编辑资料」那颗小卡定下来的结论，「仓库」Tab 的搜索卡 / 筛选入口沿用。
 *
 * ## 阴影必须与列表卡同层（[CardElevation]）
 *
 * 1.4.88 第一版给这两枚小卡用了 3dp。真机上「头部 ↔ 列表」的交界处立刻读出一条**悬空灰带**：
 * 3dp 的阴影尾（约 5.5dp）够不到 8dp 外的第一张列表卡，于是阴影整条摊在空白上、下面还剩 2dp 纯白，
 * 而每张卡自己的顶边又是白压白（看不见）—— 全页只有这一处接缝「没有接住者」，读作断层。
 * 另外 3dp 与 4dp 的光晕半径差约 2dp，列表看起来比头部宽一点点，左右各出现一道台阶。
 * 现在两枚小卡走 [CardElevation]，卡距也收到与列表卡一致（6dp）：接缝的读法与「卡与卡之间」同构。
 */
val FloatCardShape: Shape = RoundedCornerShape(9.dp)

/**
 * 浮层小卡的一行高度：32dp。
 *
 * 旧搜索框是 36dp 的灰底（`Gray150` + 纯黑描边），没有卡片、也没有落点；换成浮层卡后
 * 阴影与圆角已经承担了「这是一块可操作的面」，高度就能再收 4dp，横向内边距从 12dp 收到 9dp。
 * 这一行省下的高度与留白全给下面的内容 —— 一屏能多看见一张卡。
 */
val FloatCardRowHeight: Dp = 32.dp

/**
 * 浮层卡片按钮：卡面（[cardSurface]：圆角 + 阴影 + 卡底）+ 按下缩放。
 *
 * 为什么不用 `Card(onClick = …)`：那要自己再接一遍 `interactionSource`，而按压缩放
 * 必须复用全 App 唯一的按压原语 [rememberPressFeedback]（0.94 / 120ms）——
 * 另写一份 tween 迟早会和图标、导航项、按钮的反馈分家（同 `docs/specs` 的动效约定）。
 *
 * 顺序有讲究：`graphicsLayer` 必须在 [cardSurface] **之前**。
 * 放后面就只缩内容：按下时卡片保持原大、字往里缩，读作「内容在抖」而不是「卡被按下去」；
 * 而 [cardSurface] 已经把自己的 shadow/clip/background 串好，插不进 `background`，所以只能前置缩放。
 *
 * 调用点（「仓库」Tab 的搜索卡 / 语言入口、个人页统计卡、搜索页的选项入口）共用同一份实现，
 * 不再各写一份缩放 tween。
 */
@Composable
fun FloatingCardButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = CardShape,
    elevation: Dp = CardElevation,
    contentPadding: PaddingValues,
    content: @Composable () -> Unit,
) {
    val press = rememberPressFeedback()
    Box(
        modifier
            .graphicsLayer {
                scaleX = press.scale.value
                scaleY = press.scale.value
            }
            .cardSurface(shape = shape, elevation = elevation)
            // indication 跟随 clickable 的节点：卡面已 clip，水波纹不会溢出圆角
            .clickable(
                interactionSource = press.interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) { content() }
}
