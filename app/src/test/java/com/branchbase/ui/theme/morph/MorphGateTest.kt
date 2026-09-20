package com.branchbase.ui.theme.morph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 形变门控的钉子：**成因表里的六条，逐条都要有可判定的结果**。
 *
 * 结构性的问题（子路径数、闭合性、描边/填充）必须有客观对错，不能靠调阈值；
 * 「好不好看」那部分留给 `MorphPairTest` 的显式清单与预算。
 *
 * 另外钉一条**不变量**：`Morphable` ⇒ `travel <= MAX_TRAVEL`。
 * 这是兜底安全网，任何新增配对都不能绕过它。
 */
class MorphGateTest {

    private fun analyze(a: androidx.compose.ui.graphics.vector.ImageVector, b: androidx.compose.ui.graphics.vector.ImageVector) =
        MorphAnalyzer.analyze("test", a, b)

    private fun reasonOf(plan: MorphPlan): String =
        (plan.verdict as? MorphVerdict.Degrade)?.reason ?: error("期望降级，实际是 ${plan.verdict}")

    @Test
    fun `子路径数量不等必须降级`() {
        val plan = analyze(
            TestIcons.vector(TestIcons.squarePath(20f)),
            TestIcons.vector(TestIcons.squarePath(20f), TestIcons.squarePath(10f)),
        )
        assertTrue(reasonOf(plan).contains("子路径数量不等"))
        assertTrue("降级后不该产出任何中间帧", plan.frame(0.5f).isEmpty())
    }

    @Test
    fun `闭合性不一致必须降级`() {
        val plan = analyze(
            TestIcons.vector(TestIcons.squarePath(20f)),
            TestIcons.vector(TestIcons.openPolyline()),
        )
        assertTrue("闭合环配开放折线会在中间帧撕开：" + reasonOf(plan), reasonOf(plan).contains("闭合性不一致"))
    }

    @Test
    fun `填充与描边混搭必须降级`() {
        val plan = analyze(
            TestIcons.vector(TestIcons.squarePath(20f)),
            TestIcons.vector(TestIcons.squarePath(20f), strokeWidth = 1f),
        )
        assertTrue(reasonOf(plan).contains("填充/描边不一致"))
    }

    @Test
    fun `描边宽度差一倍以上必须降级`() {
        val plan = analyze(
            TestIcons.vector(TestIcons.squarePath(20f), strokeWidth = 1f),
            TestIcons.vector(TestIcons.squarePath(20f), strokeWidth = 4f),
        )
        assertTrue(reasonOf(plan).contains("描边宽度差过大"))
    }

    @Test
    fun `带 group 变换的图标不参与形变`() {
        val plan = analyze(
            TestIcons.vector(TestIcons.squarePath(20f), groupScale = 0.5f),
            TestIcons.vector(TestIcons.squarePath(20f)),
        )
        assertTrue(reasonOf(plan).contains("变换"))
    }

    @Test
    fun `空图标不参与形变`() {
        val plan = analyze(TestIcons.vector(), TestIcons.vector(TestIcons.squarePath(20f)))
        assertTrue(reasonOf(plan).contains("没有可形变的路径"))
    }

    @Test
    fun `同类不同尺寸的图形可以形变`() {
        val plan = analyze(
            TestIcons.vector(TestIcons.squarePath(20f)),
            TestIcons.vector(TestIcons.squarePath(12f)),
        )
        assertEquals(MorphVerdict.Morphable, plan.verdict)
        // 相似变换（尺度/平移）不该变成「顶点要走的距离」：正规化之后它们本来就该重合
        assertTrue("尺度差被算进了移动量：${plan.metrics.travel}", plan.metrics.travel < 0.05f)
        // 正方形有 4 重对称：起点旋转 90° 的候选同样完美，必须靠奥卡姆偏好取 0
        assertEquals("对称图形的等价候选取起点 0", 0, plan.metrics.startShift)
        assertTrue("起点对齐不该需要翻转绕向", !plan.metrics.flipped)
    }

    @Test
    fun `安全网不变量：任何判为可形变的配对移动量都在上限内`() {
        val shapes = listOf(
            TestIcons.vector(TestIcons.squarePath(20f)),
            TestIcons.vector(TestIcons.regularPolygon(12, 9f)),
            TestIcons.vector(TestIcons.regularPolygon(5, 10f)),
            TestIcons.vector(TestIcons.scaleneTriangle()),
            TestIcons.vector(TestIcons.regularPolygon(3, 10f)),
        )
        for (a in shapes) {
            for (b in shapes) {
                val plan = analyze(a, b)
                if (plan.morphable) {
                    assertTrue(
                        "判定可形变但移动量 ${plan.metrics.travel} 超过上限 ${MorphGate.MAX_TRAVEL}",
                        plan.metrics.travel <= MorphGate.MAX_TRAVEL,
                    )
                } else {
                    assertTrue("降级必须给出理由", reasonOf(plan).isNotBlank())
                }
            }
        }
    }

    @Test
    fun `结构全过但形跨度大的配对_要么被安全网拦下要么被如实记录`() {
        // 造一对「结构全过、但顶点要走过整个图形」的极端组合：正方形 vs 它的尖角星形版本
        val square = TestIcons.vector(TestIcons.squarePath(20f))
        val star = TestIcons.vector(
            listOf(
                androidx.compose.ui.graphics.vector.PathNode.MoveTo(12f, 2f),
                androidx.compose.ui.graphics.vector.PathNode.LineTo(14f, 12f),
                androidx.compose.ui.graphics.vector.PathNode.LineTo(22f, 8f),
                androidx.compose.ui.graphics.vector.PathNode.LineTo(13f, 14f),
                androidx.compose.ui.graphics.vector.PathNode.LineTo(20f, 20f),
                androidx.compose.ui.graphics.vector.PathNode.LineTo(12f, 15f),
                androidx.compose.ui.graphics.vector.PathNode.LineTo(5f, 22f),
                androidx.compose.ui.graphics.vector.PathNode.LineTo(10f, 13f),
                androidx.compose.ui.graphics.vector.PathNode.LineTo(2f, 10f),
                androidx.compose.ui.graphics.vector.PathNode.Close,
            ),
        )
        val plan = analyze(square, star)
        // 不论判定如何，都要满足不变量；且这类形状跨度必须体现在指标里被记录下来
        if (plan.morphable) {
            assertTrue(plan.metrics.travel <= MorphGate.MAX_TRAVEL)
        } else {
            assertTrue(reasonOf(plan).contains("移动量过大"))
        }
        assertTrue("这类配对必须被指标记录下来：${plan.metrics.travel}", plan.metrics.travel > 0.2f)
    }
}
