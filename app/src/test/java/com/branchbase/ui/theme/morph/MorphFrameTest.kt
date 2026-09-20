package com.branchbase.ui.theme.morph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第 5、6 步的**不变量**：端点恒等、反向对称、进度夹紧。
 *
 * 这三条是「形变」能不能当动效用的前提：
 *
 * - **端点恒等**（`frame(0)` 逐点等于 A、`frame(1)` 的点集等于 B）：不满足的话，
 *   按钮收起时箭头就不是原来那个朝下的箭头 —— 状态与图标对不上，比不动画还糟；
 * - **反向对称**（`frame(A→B, t)` 与 `frame(B→A, 1-t)` 是同一组点）：不对称基本就是
 *   起点或绕向没对齐（验收清单第 2 条）；
 * - **进度夹紧**：弹簧在过冲时会短暂越过 1（ζ<1 时必然发生），越界必须被夹住而不是外推 ——
 *   外推会让 `lerp` 把形状翻到反向去。
 */
class MorphFrameTest {

    private val square = TestIcons.vector(TestIcons.squarePath(20f))
    private val smaller = TestIcons.vector(TestIcons.squarePath(12f))

    @Test
    fun `两端逐点恒等`() {
        val plan = MorphAnalyzer.analyze("square", square, smaller)
        assertTrue(plan.morphable)

        val start = plan.frame(0f)
        assertEquals(plan.samplesA.size, start.size)
        start.forEachIndexed { i, subpath ->
            assertEquals("t=0 必须逐点等于 A", plan.samplesA[i].points, subpath.points)
        }

        val end = plan.frame(1f)
        end.forEachIndexed { i, subpath ->
            assertSamePointSet(plan.samplesB[i].points, subpath.points)
        }
    }

    @Test
    fun `反向播放与正向对称`() {
        val forward = MorphAnalyzer.analyze("a→b", square, smaller)
        val backward = MorphAnalyzer.analyze("b→a", smaller, square)
        assertTrue(forward.morphable && backward.morphable)

        for (t in listOf(0.15f, 0.5f, 0.85f)) {
            val f = forward.frame(t)
            val r = backward.frame(1f - t)
            assertEquals(f.size, r.size)
            f.indices.forEach { i -> assertSamePointSet(f[i].points, r[i].points, tolerance = 2f) }
        }
    }

    @Test
    fun `进度越界被夹紧而不是外推`() {
        val plan = MorphAnalyzer.analyze("square", square, smaller)
        val atZero = plan.frame(0f)
        val atOne = plan.frame(1f)

        plan.frame(-3f).forEachIndexed { i, s -> assertEquals(atZero[i].points, s.points) }
        plan.frame(2.5f).forEachIndexed { i, s -> assertSamePointSet(atOne[i].points, s.points) }
    }

    @Test
    fun `极坐标插值同样满足端点恒等`() {
        val plan = MorphAnalyzer.analyze("square", square, smaller)
        plan.frame(0f, MorphInterpolation.Polar).forEachIndexed { i, s ->
            assertEquals(plan.samplesA[i].points, s.points)
        }
        plan.frame(1f, MorphInterpolation.Polar).forEachIndexed { i, s ->
            assertSamePointSet(plan.samplesB[i].points, s.points)
        }
    }

    @Test
    fun `中间帧在两端的包框之间而不是塌掉`() {
        val plan = MorphAnalyzer.analyze("square", square, smaller)
        val height0 = bboxHeight(plan.frame(0f).first().points)
        val heightMid = bboxHeight(plan.frame(0.5f).first().points)
        val height1 = bboxHeight(plan.frame(1f).first().points)

        assertTrue("t=0.5 的包框 $heightMid 应落在 $height1 与 $height0 之间", heightMid in height1..height0)
        assertTrue("中间帧不该塌成一条线", heightMid > height1 * 0.9f)
    }

    @Test
    fun `不可形变的配对不产出中间帧`() {
        val plan = MorphAnalyzer.analyze(
            "square",
            square,
            TestIcons.vector(TestIcons.squarePath(20f), TestIcons.squarePath(10f)),
        )
        assertTrue(plan.frame(0.5f).isEmpty())
        assertTrue(plan.frame(0f).isEmpty())
    }
}
