package com.branchbase.ui.theme.morph

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 第 1 步：`ImageVector`（本质是一串 `d` 命令）→ 三次贝塞尔子路径。
 *
 * ## 为什么非得先转成 `C`
 *
 * 形变的顶点对应要求两端是**同一种**几何表示。直线段和控制点混着插值是没有意义的
 * （一条 `H` 命令插到一条 `C` 命令上，插出来的既不是直线也不是贝塞尔）。
 * 所以先把 `L/H/V` 抬成退化三次贝塞尔、`Q/T` 升阶、`A` 转圆弧的三次逼近。
 *
 * **转换本身带拟合误差**（成因表里唯一一条「跟对应关系无关」的失真）：
 * - `Q → C` 是**精确**升阶，没有误差；
 * - `A → C` 是把椭圆弧按 ≤90° 切段再用三次贝塞尔逼近，单段最大径向误差约 `2.7e-4 · r`，
 *   在 1000 网格上（r ≤ 500）约 0.14 单位 —— 比展平容差（2 单位）小一个量级，可以忽略。
 *
 * ## 不支持的输入
 *
 * `VectorGroup` 上带旋转/缩放/平移/裁剪的图标，这里**不猜**：直接把整对图标判为「不做形变」
 * （见 [MorphParsing.unsupportedReason]）。Material 图标都把变换烘进了坐标，
 * 真出现带变换的图标，说明它本来就不适合顶点插值。
 */
internal object MorphParsing {

    /** 把 vector 解析成归一化网格（[MorphSampling.GRID]）上的子路径。 */
    fun subpaths(vector: ImageVector): List<Subpath> {
        val raw = ArrayList<Subpath>()
        collect(vector.root, raw)
        return raw.map { normalizeToGrid(it, vector) }
    }

    /**
     * 整对图标是否能进形变管线。
     *
     * 返回 null 表示可以；否则是给人看的原因（会原样出现在降级日志/测试断言里）。
     */
    fun unsupportedReason(vector: ImageVector): String? {
        val bad = ArrayList<String>()
        inspect(vector.root, bad)
        if (bad.isNotEmpty()) return "图标组带变换/裁剪：${bad.joinToString("；")}"
        if (vector.viewportWidth <= 0f || vector.viewportHeight <= 0f) {
            return "viewBox 非法：${vector.viewportWidth}×${vector.viewportHeight}"
        }
        return null
    }

    private fun collect(group: VectorGroup, out: MutableList<Subpath>) {
        for (node in group) {
            when (node) {
                is VectorPath -> {
                    val style = SubpathStyle(
                        filled = node.fill != null,
                        strokeWidth = if (node.stroke != null) node.strokeLineWidth else 0f,
                    )
                    out.addAll(parsePathData(node.pathData, style))
                }

                is VectorGroup -> collect(node, out)
            }
        }
    }

    private fun inspect(group: VectorGroup, out: MutableList<String>) {
        if (group.rotation != 0f || group.scaleX != 1f || group.scaleY != 1f ||
            group.translationX != 0f || group.translationY != 0f || group.clipPathData.isNotEmpty()
        ) {
            out.add(
                "group(${group.name}) rotation=${group.rotation} scale=${group.scaleX}×${group.scaleY} " +
                    "translation=${group.translationX},${group.translationY} clip=${group.clipPathData.size}",
            )
        }
        for (node in group) if (node is VectorGroup) inspect(node, out)
    }

    // -----------------------------------------------------------------------
    // 坐标空间归一化（第 2 步）
    // -----------------------------------------------------------------------

    /**
     * 第 2 步：不同 viewBox 重栅格到同一网格，语义等同 SVG 的 `xMidYMid meet`
     * （等比缩放 + 居中，不变形）。
     *
     * 这一步不是「顺手对齐一下」：**不做它，缩放/偏移差会被下一步误读成旋转**。
     * 举例：Lucide 是 24 网格、Heroicons solid 是 20 网格，同一个 20×20 的方块在两套里
     * 坐标数值差 20%；直接拿去求对应关系，Procrustes 会把「差了 20% 的尺度」解成一个角度，
     * 于是挑出一个错误的起笔点，中间帧整体拧麻花 —— 后面每一步都是在把这个错误演出来。
     */
    private fun normalizeToGrid(subpath: Subpath, vector: ImageVector): Subpath {
        val vw = vector.viewportWidth
        val vh = vector.viewportHeight
        val scale = MorphSampling.GRID / max(vw, vh)
        val dx = (MorphSampling.GRID - vw * scale) / 2f
        val dy = (MorphSampling.GRID - vh * scale) / 2f
        fun map(p: Pt) = Pt(p.x * scale + dx, p.y * scale + dy)
        return Subpath(
            segments = subpath.segments.map { Cubic(map(it.p0), map(it.c1), map(it.c2), map(it.p3)) },
            closed = subpath.closed,
            style = subpath.style,
        )
    }

    // -----------------------------------------------------------------------
    // `d` 命令 → 三次贝塞尔
    // -----------------------------------------------------------------------

    /** 公开给单测：把一串 [PathNode] 解析成子路径（不做网格归一化）。 */
    fun parsePathData(pathData: List<PathNode>, style: SubpathStyle = SubpathStyle.Fill): List<Subpath> {
        val result = ArrayList<Subpath>()
        var segments = ArrayList<Cubic>()
        var start = Pt(0f, 0f)
        var current = Pt(0f, 0f)
        var lastCubicControl: Pt? = null
        var lastQuadControl: Pt? = null
        var previousWasCubic = false
        var previousWasQuad = false

        fun flush(closed: Boolean) {
            if (segments.isNotEmpty()) {
                result.add(Subpath(segments, closed, style))
                segments = ArrayList()
            }
        }

        fun lineTo(p: Pt) {
            segments.add(cubicOfLine(current, p))
            current = p
        }

        fun closeSubpath() {
            if (segments.isNotEmpty()) {
                if (current.distanceTo(start) > 1e-4f) lineTo(start)
                flush(closed = true)
            }
            current = start
        }

        for (node in pathData) {
            var cubic = false
            var quad = false
            when (node) {
                is PathNode.MoveTo -> {
                    flush(closed = false)
                    start = Pt(node.x, node.y)
                    current = start
                }

                is PathNode.RelativeMoveTo -> {
                    flush(closed = false)
                    start = Pt(current.x + node.dx, current.y + node.dy)
                    current = start
                }

                is PathNode.LineTo -> lineTo(Pt(node.x, node.y))

                is PathNode.RelativeLineTo -> lineTo(Pt(current.x + node.dx, current.y + node.dy))

                is PathNode.HorizontalTo -> lineTo(Pt(node.x, current.y))

                is PathNode.RelativeHorizontalTo -> lineTo(Pt(current.x + node.dx, current.y))

                is PathNode.VerticalTo -> lineTo(Pt(current.x, node.y))

                is PathNode.RelativeVerticalTo -> lineTo(Pt(current.x, current.y + node.dy))

                is PathNode.CurveTo -> {
                    segments.add(Cubic(current, Pt(node.x1, node.y1), Pt(node.x2, node.y2), Pt(node.x3, node.y3)))
                    current = Pt(node.x3, node.y3)
                    lastCubicControl = Pt(node.x2, node.y2)
                    cubic = true
                }

                is PathNode.RelativeCurveTo -> {
                    val c1 = Pt(current.x + node.dx1, current.y + node.dy1)
                    val c2 = Pt(current.x + node.dx2, current.y + node.dy2)
                    val end = Pt(current.x + node.dx3, current.y + node.dy3)
                    segments.add(Cubic(current, c1, c2, end))
                    current = end
                    lastCubicControl = c2
                    cubic = true
                }

                is PathNode.ReflectiveCurveTo -> {
                    val c1 = if (previousWasCubic && lastCubicControl != null) reflect(lastCubicControl, current) else current
                    segments.add(Cubic(current, c1, Pt(node.x1, node.y1), Pt(node.x2, node.y2)))
                    current = Pt(node.x2, node.y2)
                    lastCubicControl = Pt(node.x1, node.y1)
                    cubic = true
                }

                is PathNode.RelativeReflectiveCurveTo -> {
                    val c1 = if (previousWasCubic && lastCubicControl != null) reflect(lastCubicControl, current) else current
                    val c2 = Pt(current.x + node.dx1, current.y + node.dy1)
                    val end = Pt(current.x + node.dx2, current.y + node.dy2)
                    segments.add(Cubic(current, c1, c2, end))
                    current = end
                    lastCubicControl = c2
                    cubic = true
                }

                is PathNode.QuadTo -> {
                    val ctrl = Pt(node.x1, node.y1)
                    segments.add(cubicOfQuad(current, ctrl, Pt(node.x2, node.y2)))
                    current = Pt(node.x2, node.y2)
                    lastQuadControl = ctrl
                    quad = true
                }

                is PathNode.RelativeQuadTo -> {
                    val ctrl = Pt(current.x + node.dx1, current.y + node.dy1)
                    val end = Pt(current.x + node.dx2, current.y + node.dy2)
                    segments.add(cubicOfQuad(current, ctrl, end))
                    current = end
                    lastQuadControl = ctrl
                    quad = true
                }

                is PathNode.ReflectiveQuadTo -> {
                    val ctrl = if (previousWasQuad && lastQuadControl != null) reflect(lastQuadControl, current) else current
                    val end = Pt(node.x, node.y)
                    segments.add(cubicOfQuad(current, ctrl, end))
                    current = end
                    lastQuadControl = ctrl
                    quad = true
                }

                is PathNode.RelativeReflectiveQuadTo -> {
                    val ctrl = if (previousWasQuad && lastQuadControl != null) reflect(lastQuadControl, current) else current
                    val end = Pt(current.x + node.dx, current.y + node.dy)
                    segments.add(cubicOfQuad(current, ctrl, end))
                    current = end
                    lastQuadControl = ctrl
                    quad = true
                }

                is PathNode.ArcTo -> {
                    val cubics = arcToCubics(
                        from = current,
                        rxIn = node.horizontalEllipseRadius,
                        ryIn = node.verticalEllipseRadius,
                        xRotationDegrees = node.theta,
                        largeArc = node.isMoreThanHalf,
                        sweep = node.isPositiveArc,
                        to = Pt(node.arcStartX, node.arcStartY),
                    )
                    segments.addAll(cubics)
                    current = Pt(node.arcStartX, node.arcStartY)
                }

                is PathNode.RelativeArcTo -> {
                    val end = Pt(current.x + node.arcStartDx, current.y + node.arcStartDy)
                    segments.addAll(
                        arcToCubics(
                            from = current,
                            rxIn = node.horizontalEllipseRadius,
                            ryIn = node.verticalEllipseRadius,
                            xRotationDegrees = node.theta,
                            largeArc = node.isMoreThanHalf,
                            sweep = node.isPositiveArc,
                            to = end,
                        ),
                    )
                    current = end
                }

                is PathNode.Close -> closeSubpath()
            }
            previousWasCubic = cubic
            previousWasQuad = quad
        }
        // 末尾没有 Z 的子路径是开放路径（例如一条折线），照实记下来 —— 拓扑不匹配要靠它拦
        flush(closed = false)
        return result
    }

    private fun reflect(control: Pt, around: Pt) = Pt(2f * around.x - control.x, 2f * around.y - control.y)

    private fun cubicOfLine(from: Pt, to: Pt) = Cubic(
        from,
        lerpPt(from, to, 1f / 3f),
        lerpPt(from, to, 2f / 3f),
        to,
    )

    /** 二次 → 三次是**精确**升阶（不是逼近）。 */
    private fun cubicOfQuad(from: Pt, control: Pt, to: Pt): Cubic = Cubic(
        p0 = from,
        c1 = Pt(from.x + 2f / 3f * (control.x - from.x), from.y + 2f / 3f * (control.y - from.y)),
        c2 = Pt(to.x + 2f / 3f * (control.x - to.x), to.y + 2f / 3f * (control.y - to.y)),
        p3 = to,
    )

    /**
     * 椭圆弧 → 三次贝塞尔（SVG 规范 F.6.5 的端点参数化转中心参数化）。
     *
     * 按 ≤90° 切段：单段三次贝塞尔逼近圆弧的最大径向误差约 `2.7e-4 · r`，
     * 切到 90° 是误差与段数的常用折中；不切直接一段逼近，大弧会明显「鼓出来」。
     */
    internal fun arcToCubics(
        from: Pt,
        rxIn: Float,
        ryIn: Float,
        xRotationDegrees: Float,
        largeArc: Boolean,
        sweep: Boolean,
        to: Pt,
    ): List<Cubic> {
        var rx = abs(rxIn.toDouble())
        var ry = abs(ryIn.toDouble())
        if (rx <= 1e-6 || ry <= 1e-6) return listOf(cubicOfLine(from, to))
        if (abs(from.x - to.x) < 1e-6f && abs(from.y - to.y) < 1e-6f) return emptyList()

        val phi = Math.toRadians(xRotationDegrees.toDouble())
        val cosPhi = cos(phi)
        val sinPhi = sin(phi)
        val halfDx = (from.x - to.x) / 2.0
        val halfDy = (from.y - to.y) / 2.0
        val x1p = cosPhi * halfDx + sinPhi * halfDy
        val y1p = -sinPhi * halfDx + cosPhi * halfDy

        // 半径过小 → 按规范等比放大到「恰好够连接两点」
        val lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if (lambda > 1.0) {
            val k = sqrt(lambda)
            rx *= k
            ry *= k
        }

        val numerator = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val denominator = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        val sign = if (largeArc != sweep) 1.0 else -1.0
        val coefficient = if (denominator <= 0.0) 0.0 else sign * sqrt(max(0.0, numerator / denominator))
        val cxp = coefficient * rx * y1p / ry
        val cyp = -coefficient * ry * x1p / rx
        val cx = cosPhi * cxp - sinPhi * cyp + (from.x + to.x) / 2.0
        val cy = sinPhi * cxp + cosPhi * cyp + (from.y + to.y) / 2.0

        val startAngle = atan2((y1p - cyp) / ry, (x1p - cxp) / rx)
        val endAngle = atan2((-y1p - cyp) / ry, (-x1p - cxp) / rx)
        var delta = endAngle - startAngle
        if (!sweep && delta > 0) delta -= 2 * Math.PI
        if (sweep && delta < 0) delta += 2 * Math.PI

        val count = max(1, ceil(abs(delta) / (Math.PI / 2)).toInt())
        val step = delta / count
        val alpha = 4.0 / 3.0 * tan(step / 4.0)

        fun point(theta: Double): Pt {
            val ex = rx * cos(theta)
            val ey = ry * sin(theta)
            return Pt((cosPhi * ex - sinPhi * ey + cx).toFloat(), (sinPhi * ex + cosPhi * ey + cy).toFloat())
        }

        fun derivative(theta: Double): Pair<Double, Double> {
            val dxe = -rx * sin(theta)
            val dye = ry * cos(theta)
            return (cosPhi * dxe - sinPhi * dye) to (sinPhi * dxe + cosPhi * dye)
        }

        val out = ArrayList<Cubic>(count)
        var theta = startAngle
        var previous = from
        for (i in 0 until count) {
            val next = theta + step
            val p1 = point(theta)
            val p2 = point(next)
            val (d1x, d1y) = derivative(theta)
            val (d2x, d2y) = derivative(next)
            out.add(
                Cubic(
                    p0 = if (i == 0) from else previous,
                    c1 = Pt((p1.x + alpha * d1x).toFloat(), (p1.y + alpha * d1y).toFloat()),
                    c2 = Pt((p2.x - alpha * d2x).toFloat(), (p2.y - alpha * d2y).toFloat()),
                    p3 = if (i == count - 1) to else p2,
                ),
            )
            previous = if (i == count - 1) to else p2
            theta = next
        }
        return out
    }

    /** 供单测/诊断：点到点的近似直线距离（避免测试里再写一遍 hypot）。 */
    internal fun distance(a: Pt, b: Pt): Float = hypot(a.x - b.x, a.y - b.y)
}
