package com.branchbase.ui.repository

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 分支图标 —— 设计稿 `design/release-redesign/app.js:41` 的 `ICONS.branch`，坐标按 16×16 原样照抄。
 *
 * ## 为什么自己画，而不是继续用 Material 的 `AccountTree`
 *
 * 设计稿的图标规范（`design/release-redesign/README.md`，同一段里点名了「分支」）写的是
 * 图标全部换成自定义的内联 SVG，不用 emoji、也不混系统图标。而 `AccountTree` 和这一枚
 * **不是同一个形状**：前者是 24 网格的填充路径 + 三层树杈，后者是 16 网格、1.5 描边、
 * 两个圆环节点 + 一条竖直连线 + 一条带圆弧的支线。两者并排放在一行里，粗细与视觉重量
 * 对不上（这也是设计稿把图标单独列一条的原因）。
 *
 * ## 照抄的细节，别顺手改
 *
 * - **描边、不填充**：设计稿的 SVG 外壳是 `fill="none" stroke="currentColor" stroke-width="1.5"
 *   stroke-linecap="round" stroke-linejoin="round"`，两个「节点」是半径 1.7 的**圆环**，
 *   不是实心圆点。改成实心或多加几条线，放大到真机上就和设计稿对不上像素了。
 * - **色值走 tint**：这里 `stroke = SolidColor(Color.Black)` 只是占位 —— [ImageVector] 按
 *   `Icon(imageVector, tint = …)` 的约定会被 tint 覆盖，所以图标本身不该带颜色语义。
 * - **圆环用两段半圆弧画**（SVG 的 `<circle>` 在 `PathBuilder` 里没有现成节点）；
 *   端点是同一个点，但 `stroke-linecap = round` 把接缝盖住了，视觉上就是一个闭合圆。
 */
internal val ReleaseBranchIcon: ImageVector = ImageVector.Builder(
    name = "ReleaseBranch",
    defaultWidth = 16.dp,
    defaultHeight = 16.dp,
    viewportWidth = 16f,
    viewportHeight = 16f,
).path(
    stroke = SolidColor(Color.Black),
    strokeLineWidth = 1.5f,
    strokeLineCap = StrokeCap.Round,
    strokeLineJoin = StrokeJoin.Round,
) {
    // <circle cx="4.6" cy="4" r="1.7"/>：上面那个节点
    moveTo(2.9f, 4f)
    arcToRelative(1.7f, 1.7f, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = 3.4f, dy1 = 0f)
    arcToRelative(1.7f, 1.7f, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = -3.4f, dy1 = 0f)
    close()

    // <circle cx="4.6" cy="12" r="1.7"/>：下面那个节点
    moveTo(2.9f, 12f)
    arcToRelative(1.7f, 1.7f, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = 3.4f, dy1 = 0f)
    arcToRelative(1.7f, 1.7f, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = -3.4f, dy1 = 0f)
    close()

    // <path d="M4.6 5.7v4.6"/>：两个节点之间的连线
    moveTo(4.6f, 5.7f)
    verticalLineTo(10.3f)

    // <path d="M6.3 4h3.2A2.4 2.4 0 0 1 11.9 6.4v3.1"/>：从上面节点向右引出的分支
    moveTo(6.3f, 4f)
    horizontalLineTo(9.5f)
    arcToRelative(2.4f, 2.4f, 0f, isMoreThanHalf = false, isPositiveArc = true, dx1 = 2.4f, dy1 = 2.4f)
    verticalLineTo(9.5f)
}.build()
