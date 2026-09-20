package com.branchbase.ui.theme.morph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第 4 步「顶点对应（correspondence）」的钉子 —— **中间帧难看的所有根源都在这一步**。
 *
 * 这里不靠肉眼：三条几何事实各造一个最小例子，检查**恢复出来的对应关系是不是真的对应**，
 * 而不是「看起来差不多」。判据是同一句话：`a[i]` 必须映射到「几何上就是它」的那个 `b[j]`。
 *
 * | 场景 | 造法 | 期望 |
 * |------|------|------|
 * | 起点错位 | B 的采样序列是 A 的循环移位 | 移位被原样解出来，逐点重合 |
 * | 绕向相反 | B 是 A 的逆序遍历 | 解出 `flipped = true`，逐点重合 |
 * | 全对称 | 正多边形（所有起点几何等价） | 取起点 0、不翻转（奥卡姆偏好） |
 */
class MorphCorrespondenceTest {

    private fun sampled(points: List<Pt>, closed: Boolean = true) =
        SampledSubpath(points, closed, SubpathStyle.Fill, segments = points.size, corners = 0)

    /** 三条边长都不同的三角形：起点与绕向唯一可辨。 */
    private fun triangle(n: Int): List<Pt> =
        MorphSampling.resample(
            MorphSampling.flatten(MorphParsing.parsePathData(TestIcons.scaleneTriangle()).single()),
            n,
        )

    private fun rotated(points: List<Pt>, k: Int): List<Pt> =
        points.drop(k) + points.take(k)

    private fun assertMapsOntoPartner(a: List<Pt>, b: List<Pt>, correspondence: Correspondence) {
        for (i in a.indices) {
            val partner = b[correspondence.map(i, a.size, closed = true)]
            assertTrue(
                "第 $i 个顶点映射错了：$partner 与 ${a[i]} 不是同一个点（shift=${correspondence.shift} flip=${correspondence.flipped}）",
                partner.approximately(a[i], 1f),
            )
        }
    }

    @Test
    fun `起点错位被原样解出来`() {
        val n = 48
        val a = triangle(n)
        val shift = 7
        val b = rotated(a, shift)

        val correspondence = MorphAnalyzer.correspondenceOf(sampled(a), sampled(b))

        assertFalse("只是起点错位，不需要翻转绕向", correspondence.flipped)
        assertMapsOntoPartner(a, b, correspondence)
        assertEquals("移位数应被解成 -$shift（模 n）", (n - shift).mod(n), correspondence.shift)
    }

    @Test
    fun `绕向相反被识别并翻转`() {
        val n = 48
        val a = triangle(n)
        val b = a.reversed()

        val correspondence = MorphAnalyzer.correspondenceOf(sampled(a), sampled(b))

        assertTrue("逆序遍历必须解成 flipped", correspondence.flipped)
        assertMapsOntoPartner(a, b, correspondence)
    }

    @Test
    fun `等价候选之间取起点 0 而不是靠浮点噪声`() {
        val n = 48
        val polygon = MorphSampling.resample(
            MorphSampling.flatten(MorphParsing.parsePathData(TestIcons.regularPolygon(12, 9f)).single()),
            n,
        )
        // 正十二边形有 12 重对称：自己对自己的**所有**起点、两种绕向在几何上等价，
        // 移动量完全相同 → 只能靠奥卡姆偏好定胜负。取不到 0 就意味着「同一对图标
        // 每次分析的对应关系可能不同」，那是比难看更难查的问题（不可复现）。
        val a = sampled(polygon)
        val b = sampled(polygon.toList())
        repeat(5) {
            val correspondence = MorphAnalyzer.correspondenceOf(a, b)
            assertEquals("等价候选之间必须稳定取起点 0", 0, correspondence.shift)
            assertFalse(correspondence.flipped)
        }
    }

    @Test
    fun `起点真的偏了就得跟着偏_而不是被奥卡姆偏好按住`() {
        val n = 48
        val polygon = MorphSampling.resample(
            MorphSampling.flatten(MorphParsing.parsePathData(TestIcons.regularPolygon(12, 9f)).single()),
            n,
        )
        val b = rotated(polygon, 4)
        val correspondence = MorphAnalyzer.correspondenceOf(sampled(polygon), sampled(b))
        // 正十二边形每边 4 个采样点：起点沿边挪一格，对应关系必须跟着挪回去
        assertMapsOntoPartner(polygon, b, correspondence)
        assertEquals((n - 4).mod(n), correspondence.shift)
    }

    @Test
    fun `对应关系把移动量压到比朴素索引小一个量级`() {
        val n = 48
        val a = triangle(n)
        val b = rotated(a, 11)
        val normalizedA = normalize(a).points
        val normalizedB = normalize(b).points

        fun travel(shift: Int, flipped: Boolean): Float {
            var acc = 0f
            for (i in normalizedA.indices) {
                val j = mapIndex(i, n, true, shift, flipped)
                val dx = normalizedA[i].x - normalizedB[j].x
                val dy = normalizedA[i].y - normalizedB[j].y
                acc += dx * dx + dy * dy
            }
            return kotlin.math.sqrt(acc / n)
        }

        val naive = travel(0, false)
        val chosen = MorphAnalyzer.correspondenceOf(sampled(a), sampled(b))
        val best = travel(chosen.shift, chosen.flipped)

        assertTrue("朴素索引的移动量 $naive，对应关系解出来的 $best", best < naive / 5f)
        assertTrue("正确对应下应该几乎重合，实际 $best", best < 0.02f)
    }

    @Test
    fun `开放折线只允许翻转不允许旋转`() {
        val a = listOf(Pt(0f, 0f), Pt(10f, 0f), Pt(10f, 10f), Pt(20f, 10f))
        val b = a.reversed()
        val correspondence = MorphAnalyzer.correspondenceOf(sampled(a, closed = false), sampled(b, closed = false))

        assertTrue(correspondence.flipped)
        assertEquals("开放路径的 shift 必须是 0（两端就是两端）", 0, correspondence.shift)
        for (i in a.indices) {
            val j = correspondence.map(i, a.size, closed = false)
            assertTrue(b[j].approximately(a[i], 1e-3f))
        }
    }
}
