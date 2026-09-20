package com.branchbase.ui.theme.morph

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.unit.dp

/**
 * 形变单测的公共夹具。
 *
 * 这里刻意**只用路径数据**（`List<PathNode>`）构造图标，不依赖 material-icons 的具体取值 ——
 * 结构性判定（子路径数、闭合性、描边/填充）要能用最小可读的例子说清楚。
 * 真实图标的实测指标在 [MorphPairTest] 里钉。
 */
internal object TestIcons {

    fun vector(
        vararg paths: List<PathNode>,
        viewport: Float = 24f,
        strokeWidth: Float = 0f,
        /** 带 group 变换的图标（用于验证「不猜」这条降级规则）。 */
        groupScale: Float = 1f,
    ): ImageVector {
        val builder = ImageVector.Builder(
            name = "test",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = viewport,
            viewportHeight = viewport,
        )
        val stroke = strokeWidth > 0f
        if (groupScale != 1f) {
            builder.addGroup(
                name = "scaled",
                scaleX = groupScale,
                scaleY = groupScale,
            )
        }
        paths.forEach { pathData ->
            builder.addPath(
                pathData = pathData,
                fill = if (stroke) null else SolidColor(Color.Black),
                stroke = if (stroke) SolidColor(Color.Black) else null,
                strokeLineWidth = strokeWidth,
            )
        }
        return builder.build()
    }

    /** 与 viewport 无关的正方形（用来验证坐标空间归一化）。 */
    fun squarePath(size: Float): List<PathNode> = listOf(
        PathNode.MoveTo(0f, 0f),
        PathNode.LineTo(size, 0f),
        PathNode.LineTo(size, size),
        PathNode.LineTo(0f, size),
        PathNode.Close,
    )

    /** 正 n 边形（对称图形：所有起点在几何上等价）。 */
    fun regularPolygon(n: Int, radius: Float, centerX: Float = 12f, centerY: Float = 12f): List<PathNode> {
        val nodes = ArrayList<PathNode>()
        for (i in 0 until n) {
            val angle = 2.0 * Math.PI * i / n
            val x = (centerX + radius * Math.cos(angle)).toFloat()
            val y = (centerY + radius * Math.sin(angle)).toFloat()
            if (i == 0) nodes.add(PathNode.MoveTo(x, y)) else nodes.add(PathNode.LineTo(x, y))
        }
        nodes.add(PathNode.Close)
        return nodes
    }

    /** 不对称的闭合三角形：三个角、三条边长都不同 —— 起点/绕向唯一可辨。 */
    fun scaleneTriangle(): List<PathNode> = listOf(
        PathNode.MoveTo(4f, 4f),
        PathNode.LineTo(20f, 7f),
        PathNode.LineTo(9f, 19f),
        PathNode.Close,
    )

    /** 开放折线（用来验证拓扑不匹配必须被拦）。 */
    fun openPolyline(): List<PathNode> = listOf(
        PathNode.MoveTo(4f, 12f),
        PathNode.LineTo(12f, 4f),
        PathNode.LineTo(20f, 12f),
    )
}

/** 三次贝塞尔求值（只给单测用）。 */
internal fun pointOnCubic(c: Cubic, t: Float): Pt {
    val u = 1f - t
    val a = u * u * u
    val b = 3f * u * u * t
    val d = 3f * u * t * t
    val e = t * t * t
    return Pt(
        a * c.p0.x + b * c.c1.x + d * c.c2.x + e * c.p3.x,
        a * c.p0.y + b * c.c1.y + d * c.c2.y + e * c.p3.y,
    )
}

/** 点集比较（顺序无关）：对应关系旋转过起点时，端点一致性只能按集合比。 */
internal fun assertSamePointSet(expected: List<Pt>, actual: List<Pt>, tolerance: Float = 1e-2f) {
    if (expected.size != actual.size) {
        throw AssertionError("点数不一致：期望 ${expected.size}，实际 ${actual.size}")
    }
    val remaining = actual.toMutableList()
    for (p in expected) {
        val hit = remaining.indexOfFirst { it.approximately(p, tolerance) }
        if (hit < 0) throw AssertionError("点集不一致：多出/缺失 $p")
        remaining.removeAt(hit)
    }
}
