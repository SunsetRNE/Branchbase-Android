package com.branchbase.ui.theme.morph

import androidx.compose.ui.graphics.vector.PathNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第 1 步「解析为 cubic subpaths」的钉子。
 *
 * 这一步的验收标准只有一条：**转换本身不能引入肉眼可见的误差**，否则后面所有步骤都在
 * 一个已经歪掉的输入上工作，而且现场看不出来（中间帧难看会被误判成插值算法的锅）。
 *
 * 三类转换的误差性质完全不同，分别钉：
 *
 * | 命令 | 转换 | 误差 |
 * |------|------|------|
 * | `Q`/`T` | 二次升三次 | **精确**（代数恒等），断言到 1e-3 |
 * | `L`/`H`/`V` | 退化成三次 | **精确**（控制点落在三等分点），断言到 1e-3 |
 * | `S` | 控制点取上一条 `C` 的反射 | **精确**（规则本身），断言到 1e-3 |
 * | `A` | 椭圆弧 → ≤90° 的分段三次逼近 | **有拟合误差**，理论量级 2.7e-4·r，断言到 0.1 个单位 |
 */
class MorphParsingTest {

    @Test
    fun `二次曲线升阶是精确的`() {
        val subpath = MorphParsing.parsePathData(
            listOf(PathNode.MoveTo(0f, 0f), PathNode.QuadTo(0f, 100f, 100f, 100f)),
        ).single()
        val cubic = subpath.segments.single()
        // 二次贝塞尔 t=0.5 = 0.25·P0 + 0.5·C + 0.25·P2 = (25, 75)
        assertTrue(pointOnCubic(cubic, 0.5f).approximately(Pt(25f, 75f), 1e-2f))
        // 升阶公式在 t=0.25 处也必须精确
        val quad = { t: Float ->
            val u = 1f - t
            Pt(u * u * 0f + 2 * u * t * 0f + t * t * 100f, u * u * 0f + 2 * u * t * 100f + t * t * 100f)
        }
        assertTrue(pointOnCubic(cubic, 0.25f).approximately(quad(0.25f), 1e-2f))
    }

    @Test
    fun `直线与横竖命令抬成退化三次贝塞尔`() {
        val subpath = MorphParsing.parsePathData(
            listOf(
                PathNode.MoveTo(10f, 10f),
                PathNode.HorizontalTo(20f),
                PathNode.VerticalTo(30f),
                PathNode.RelativeLineTo(-5f, 0f),
                PathNode.RelativeHorizontalTo(-5f),
                PathNode.RelativeVerticalTo(-5f),
                PathNode.Close,
            ),
        ).single()

        assertTrue("末尾 Z 必须被记成闭合", subpath.closed)
        // 五条显式段 + 一条回到起点的收尾段
        assertEquals(6, subpath.segments.size)
        assertEquals(Pt(10f, 10f), subpath.start)
        assertEquals(Pt(20f, 10f), subpath.segments[0].p3)
        assertEquals(Pt(20f, 30f), subpath.segments[1].p3)
        assertEquals(Pt(15f, 30f), subpath.segments[2].p3)
        assertEquals(Pt(10f, 30f), subpath.segments[3].p3)
        assertEquals(Pt(10f, 25f), subpath.segments[4].p3)
        // 收尾段把最后一个点接回起点，闭合环不能有缺口
        assertEquals(Pt(10f, 10f), subpath.segments[5].p3)
        // 直线抬成三次贝塞尔后，中点仍在线段上
        assertTrue(pointOnCubic(subpath.segments[0], 0.5f).approximately(Pt(15f, 10f), 1e-3f))
    }

    @Test
    fun `没有 Z 的子路径必须记成开放路径`() {
        val subpath = MorphParsing.parsePathData(
            listOf(PathNode.MoveTo(4f, 12f), PathNode.LineTo(12f, 4f), PathNode.LineTo(20f, 12f)),
        ).single()
        assertFalse("末尾没有 Z 就是开放路径 —— 拓扑判定全靠它", subpath.closed)
        assertEquals(2, subpath.segments.size)
    }

    @Test
    fun `S 的控制点是上一条 C 的反射`() {
        val subpath = MorphParsing.parsePathData(
            listOf(
                PathNode.MoveTo(0f, 0f),
                PathNode.CurveTo(10f, 0f, 20f, 10f, 30f, 10f),
                PathNode.ReflectiveCurveTo(40f, 20f, 50f, 20f),
            ),
        ).single()
        // 上一条的控制点 c2 = (20,10)，关于当前点 (30,10) 反射 → (40,10)
        assertEquals(Pt(40f, 10f), subpath.segments[1].c1)
        assertEquals(Pt(50f, 20f), subpath.segments[1].p3)
    }

    @Test
    fun `T 的控制点是上一条 Q 的反射`() {
        val subpath = MorphParsing.parsePathData(
            listOf(
                PathNode.MoveTo(0f, 0f),
                PathNode.QuadTo(10f, 10f, 20f, 0f),
                PathNode.ReflectiveQuadTo(40f, 0f),
            ),
        ).single()
        // 上一条的控制点 (10,10)，关于当前点 (20,0) 反射 → (30,-10)
        val second = subpath.segments[1]
        // 二次升三次后 c1 落在 P0→控制点 的 2/3 处：P0=(20,0)、控制点=(30,-10)
        assertEquals(20f + 2f / 3f * 10f, second.c1.x, 1e-3f)
        assertEquals(0f + 2f / 3f * -10f, second.c1.y, 1e-3f)
    }

    @Test
    fun `椭圆弧转三次贝塞尔在 90 度内误差可忽略`() {
        // M 0,0 A 100,100 0 0 1 100,100 —— 圆心 (0,100)、半径 100 的四分之一圆
        val subpath = MorphParsing.parsePathData(
            listOf(PathNode.MoveTo(0f, 0f), PathNode.ArcTo(100f, 100f, 0f, false, true, 100f, 100f)),
        ).single()
        assertEquals("90 度应切 1 段", 1, subpath.segments.size)
        val mid = pointOnCubic(subpath.segments.single(), 0.5f)
        // 圆弧中点：圆心 + r·(cos-45°, sin-45°) = (70.71, 29.29)
        assertTrue("圆弧中点偏离过大：$mid", mid.approximately(Pt(70.71f, 29.29f), 0.1f))
        assertEquals(Pt(100f, 100f), subpath.segments.single().p3)
    }

    @Test
    fun `大弧按 90 度切段且端点精确`() {
        // 半圆：|Δθ| = 180° → 2 段；端点必须精确落在 (20,0)
        val subpath = MorphParsing.parsePathData(
            listOf(
                PathNode.MoveTo(0f, 0f),
                PathNode.RelativeArcTo(10f, 10f, 0f, false, true, 20f, 0f),
            ),
        ).single()
        assertEquals(2, subpath.segments.size)
        assertEquals(Pt(0f, 0f), subpath.segments.first().p0)
        assertEquals(Pt(20f, 0f), subpath.segments.last().p3)
        // 两段之间必须严丝合缝（否则展平后会出现假的角点）
        assertEquals(subpath.segments[0].p3, subpath.segments[1].p0)
    }

    @Test
    fun `坐标空间归一化把不同 viewBox 摆到同一网格`() {
        val small = MorphParsing.subpaths(TestIcons.vector(TestIcons.squarePath(20f), viewport = 20f))
        val large = MorphParsing.subpaths(TestIcons.vector(TestIcons.squarePath(24f), viewport = 24f))
        val a = small.single()
        val b = large.single()
        // 20 网格里的 (0,0)-(20,20) 与 24 网格里的 (0,0)-(24,24) 是同一个图形
        assertTrue(a.start.approximately(b.start, 1e-2f))
        assertTrue(a.segments[1].p3.approximately(b.segments[1].p3, 1e-2f))
        // 等比缩放 + 居中：图形占满网格的较小边
        assertEquals(MorphSampling.GRID, a.segments[1].p3.x, 1e-2f)
    }

    @Test
    fun `带 group 变换的图标不猜_直接判不支持`() {
        val scaled = TestIcons.vector(TestIcons.squarePath(12f), groupScale = 0.5f)
        val reason = MorphParsing.unsupportedReason(scaled)
        assertTrue("带变换的图标必须被判为不支持，实际：$reason", reason != null && reason.contains("变换"))
    }
}
