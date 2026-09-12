package com.branchbase.ui.theme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip

/**
 * 图标点击目标：把点击反馈裁成**圆形**。
 *
 * ## 为什么需要它
 *
 * 直接给图标挂 `clickable`（如 `Modifier.size(24.dp)` + `clickable {}`）时，
 * 点击区就是那 24dp 方块，
 * 而默认 indication（水波纹）**跟随点击区且不做形状裁剪** —— 点下去会闪出一个方块，
 * 与图标本身的圆形语义不一致（用户反馈的「存在图标，但点击后渲染为正方形」）。
 *
 * 在 `clickable` 之前 `clip(CircleShape)` 即可让水波纹按圆形裁切，
 * 与 Material 的 `IconButton` 行为一致。
 *
 * ## 长按形态
 *
 * 「点一下」和「长按」都要圆形反馈时（如个人页头像：点击无操作、长按强制刷新），
 * 传 [onLongClick] 即可 —— 内部走 `combinedClickable`，clip 仍在点击节点之前。
 * **不要**自己在调用处写 `Modifier.combinedClickable(...)`：那样点击节点在裁剪之外，
 * 反馈会退回成正方形（本次修的就是这个）。
 *
 * ## 为什么不顺便放大触控区
 *
 * 放大到 48dp 会改变行高与间距（顶部栏、列表行、弹窗标题栏全都受影响），
 * 属于另一类改动，需要逐处确认版式。这里**只改反馈形状，不改任何尺寸**：
 * 布局与改动前逐像素一致，风险为零；触控区的问题单独评估。
 */
fun Modifier.iconTap(
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    // clip 在 clickable/combinedClickable **之前**：点击节点落在裁剪内部，水波纹才会被裁成圆形
    val circular = this.clip(CircleShape)
    return if (onLongClick == null) {
        circular.clickable(enabled = enabled, onClick = onClick)
    } else {
        circular.combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)
    }
}
