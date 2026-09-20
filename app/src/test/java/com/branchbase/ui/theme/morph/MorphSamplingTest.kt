package com.branchbase.ui.theme.morph

import androidx.compose.ui.graphics.vector.PathNode
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第 3 步「弧长等距重采样」的钉子。
 *
 * 这一步有两个互相拉扯的目标，测试把两条都钉住：
 *
 * 1. **等距**（不然中间帧会「一冲一冲」地走）—— 用**没有角点**的圆来验，避免角点锚定的干扰；
 * 2. **角点精确**（不然箭头/加减号的尖角会被切圆 —— 重采样把角抹平的第一现场）—— 用方形来验。
 *
 * 还有一条底线：重采样是近似，**近似程度必须可测**（对着原始折线量最大偏离）。
 * 注意所有长度都在**归一化网格**（[MorphSampling.GRID] = 1000）上量：
 * [MorphSampling.TOLERANCE] 是按网格标定的，拿 24 单位的原始坐标去套会差 40 倍。
 */
class MorphSamplingTest {

    private fun raw(nodes: List<PathNode>): Subpath = MorphParsing.parsePathData(nodes).single()

    /** 走完整管线（含坐标空间归一化）的子路径。 */
    private fun onGrid(nodes: List<PathNode>): Subpath =
        MorphParsing.subpaths(TestIcons.vector(nodes)).single()

    private fun radius(centerX: Float, centerY: Float, r: Float): List<PathNode> {
        val k = 0.5523f * r
        return listOf(
            PathNode.MoveTo(centerX + r, centerY),
            PathNode.CurveTo(centerX + r, centerY + k, centerX + k, centerY + r, centerX, centerY + r),
            PathNode.CurveTo(centerX - k, centerY + r, centerX - r, centerY + k, centerX - r, centerY),
            PathNode.CurveTo(centerX - r, centerY - k, centerX - k, centerY - r, centerX, centerY - r),
            PathNode.CurveTo(centerX + k, centerY - r, centerX + r, centerY - k, centerX + r, centerY),
            PathNode.Close,
        )
    }

    @Test
    fun `开放折线重采样保留两端且等距`() {
        val polyline = MorphSampling.flatten(raw(listOf(PathNode.MoveTo(0f, 0f), PathNode.LineTo(100f, 0f))))
        val n = 24
        val points = MorphSampling.resample(polyline, n)

        assertEquals(n, points.size)
        assertEquals("起点必须精确保留", 0f, points.first().x, 1e-3f)
        assertEquals("终点必须精确保留", 100f, points.last().x, 1e-3f)
        for (i in 1 until n) {
            assertEquals("等距被破坏：第 $i 段", 100f / (n - 1), points[i].x - points[i - 1].x, 1e-2f)
        }
    }

    @Test
    fun `无角点的闭合环按弧长严格等距`() {
        val circle = onGrid(radius(12f, 12f, 9f))
        val n = 48
        val points = MorphSampling.resample(MorphSampling.flatten(circle), n)
        val spacing = MorphSampling.flatten(circle).length / n
        for (i in 0 until n) {
            val gap = points[i].distanceTo(points[(i + 1) % n])
            assertTrue("第 $i 段间距 $gap 与理论值 $spacing 偏差过大", abs(gap - spacing) <= spacing * 0.02f)
        }
    }

    @Test
    fun `闭合环角点被锚成精确采样`() {
        // 正方形：四个角必须原样出现在采样结果里，不能被抹成圆角
        val square = onGrid(TestIcons.squarePath(20f))
        val points = MorphSampling.resample(MorphSampling.flatten(square), 24)
        val scale = MorphSampling.GRID / 24f
        val corners = listOf(Pt(0f, 0f), Pt(20f, 0f), Pt(20f, 20f), Pt(0f, 20f)).map { Pt(it.x * scale, it.y * scale) }
        for (corner in corners) {
            assertTrue(
                "角点 $corner 没有被精确保留，最近采样点距离 ${points.minOf { it.distanceTo(corner) }}",
                points.any { it.approximately(corner, 1e-2f) },
            )
        }
    }

    @Test
    fun `重采样对原折线的偏离在采样间距内`() {
        val blob = onGrid(
            listOf(
                PathNode.MoveTo(2f, 12f),
                PathNode.CurveTo(2f, 2f, 22f, 6f, 22f, 12f),
                PathNode.CurveTo(22f, 18f, 2f, 22f, 2f, 12f),
                PathNode.Close,
            ),
        )
        val flatten = MorphSampling.flatten(blob)
        val n = 48
        val points = MorphSampling.resample(flatten, n)
        val spacing = flatten.length / n

        var worst = 0f
        for (p in flatten.points) worst = maxOf(worst, points.minOf { it.distanceTo(p) })
        // 等距采样下，任意点到最近采样点的距离是「间距的一半」量级；
        // 角点锚定会让局部间距不均，所以给 1.5 倍余量。
        assertTrue("最大偏离 $worst 超过间距 $spacing 的 1.5 倍（会出现多边形感）", worst <= spacing * 1.5f)
    }

    @Test
    fun `采样点数由两端共同决定并夹在上下限内`() {
        assertEquals("简单图形取下限", MorphSampling.MIN_SAMPLES, MorphSampling.sampleCount(1, 1, 0, 0))
        assertEquals("段数多的一侧说话", MorphSampling.sampleCount(2, 12, 0, 0), MorphSampling.sampleCount(12, 2, 0, 0))
        assertEquals("上限护栏", MorphSampling.MAX_SAMPLES, MorphSampling.sampleCount(400, 400, 0, 0))
        assertEquals("角点比段数多时按角点数抬", 40, MorphSampling.sampleCount(1, 1, 36, 4))
        assertTrue(
            "点数不够装角点会被抹平",
            MorphSampling.sampleCount(1, 1, 36, 4) > 36,
        )
    }

    @Test
    fun `展平误差不超过容差量级`() {
        val circle = onGrid(radius(12f, 12f, 10f))
        val flatten = MorphSampling.flatten(circle)
        val center = Pt(MorphSampling.GRID / 2f, MorphSampling.GRID / 2f)
        val expectedRadius = 10f / 24f * MorphSampling.GRID
        var worst = 0f
        for (p in flatten.points) worst = maxOf(worst, abs(p.distanceTo(center) - expectedRadius))
        // 容差 2 个网格单位 = 24dp 图标上的 0.05px：展平后不该有肉眼可见的棱角
        assertTrue("展平后的圆偏离理想圆 $worst（容差 ${MorphSampling.TOLERANCE}）", worst <= MorphSampling.TOLERANCE)
    }

    @Test
    fun `角点判定不吃掉光滑曲线`() {
        val circle = onGrid(radius(12f, 12f, 9f))
        assertTrue(
            "圆上不该出现角点（否则每帧都会在假角上「弹」一下）",
            MorphSampling.flatten(circle).cornerArcPositions().isEmpty(),
        )
        val square = onGrid(TestIcons.squarePath(20f))
        assertEquals("正方形的四个角都要被判出来", 4, MorphSampling.flatten(square).cornerArcPositions().size)
    }
}
