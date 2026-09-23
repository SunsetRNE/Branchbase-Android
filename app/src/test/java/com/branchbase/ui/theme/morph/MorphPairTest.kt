package com.branchbase.ui.theme.morph

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **真实图标**配对的实测钉子（不是合成夹具）。
 *
 * 这一组测试是整件事里最值钱的部分：它把「这对图标能不能形变、中间帧会不会崩」
 * 从**评审意见**变成了**可执行的数字**。图标库升个版本、有人换了个同义图标，
 * 这里当场红，而不是等用户在真机上看到中间帧拧麻花。
 *
 * 预算的来历（验收清单第 1、2、4 条）：
 *
 * | 指标 | 含义 | 预算 | 为什么是这个数 |
 * |------|------|------|----------------|
 * | `travel` | 归一化顶点位移（1.0 = 每个顶点走一个自身半径） | ≤ 1.0 | 两个已知配对的实测值是 0.6~0.8，1.0 表示「顶点最多走一个半径」，超过就该换素材了 |
 * | 中间帧包框 | t=0.5 时图形还剩多高 | > 2% 网格 | 塌成 0 就是「中间帧凭空消失一帧」的闪烁 |
 * | 端点 | t=0 / t=1 | 逐点相等 | 状态与图标必须对得上 |
 */
class MorphPairTest {

    @Test
    fun `台账里每一对的声明与实测一致`() {
        for (pair in MorphIcons.all) {
            val verdict = pair.plan.verdict
            when (val expectation = pair.expectation) {
                is MorphExpectation.Morph -> assertEquals(
                    "${pair.name} 声明可形变，实测却被拦下：$verdict",
                    MorphVerdict.Morphable,
                    verdict,
                )

                is MorphExpectation.Fallback -> {
                    val measured = (verdict as? MorphVerdict.Degrade)?.reason
                        ?: error("${pair.name} 声明降级，实测却是可形变")
                    // 约定：声明理由的「冒号前那半句」必须原样出现在实测理由里，
                    // 保证「写下的原因」和「代码真正判出来的原因」是同一个
                    val head = expectation.reason.substringBefore("：")
                    assertTrue("${pair.name} 的降级理由对不上：声明「$head」，实测「$measured」", measured.contains(head))
                }
            }
        }
    }

    @Test
    fun `分析只做一次_首次在预算内_第二次命中缓存`() {
        // ⚠️ 这里必须用**新建的** pair，不能用台账里的 `MorphIcons.PlusMinus`。
        //
        // 原因：`MorphPair.plan` 是 `by lazy`，而台账（`MorphIcons`）是**进程级单例** —— 整个测试套件
        // 在同一个 JVM 里跑（没有 `forkEvery`），本类另外 8 个用例都会遍历 `MorphIcons.all`，
        // 而本用例按 JUnit 的固定顺序是**最后一个**执行。也就是说：等它跑到时，台账里每一对的 plan
        // 早就算好了，量到的两次都是缓存命中。
        //
        // 实测（2026-09，n=2000）：预热后两次读取分别约 236ns / 194ns，`second < first` 只以
        // **65%** 的概率成立 —— 这是一个真实的 flaky（失败时报文形如「实际 0.000469ms」，
        // 那个量级正是缓存命中）。而新建 pair 是真正的首次：1.34ms vs 0.29µs（≈4600×），稳定成立。
        val pair = MorphPair(
            name = "test⇄probe",
            from = Icons.Filled.Add,
            to = Icons.Filled.Remove,
            expectation = MorphExpectation.Morph,
        )

        // 首次：真跑分析（解析 → 重采样 → O(N²) 对应关系搜索）。上限给得很松（100ms），
        // 目的是拦住复杂度的意外变化（比如把搜索写成 O(N³)），不是卡具体耗时。
        val t1 = System.nanoTime()
        val plan = pair.plan
        val firstMs = (System.nanoTime() - t1) / 1_000_000.0

        // 第二次：必须是缓存命中（`lazy` 的语义）。同一对的「只算一次」由这里的**同一实例**保证 ——
        // `===` 比计时更硬：重算一定会产生新对象。
        val t2 = System.nanoTime()
        val again = pair.plan
        val secondMs = (System.nanoTime() - t2) / 1_000_000.0

        assertTrue("首次分析耗时 ${firstMs}ms（预算 100ms）", firstMs < 100.0)
        assertTrue("缓存必须返回同一份计划", plan === again)
        assertTrue(
            "第二次必须是缓存命中：首次 ${firstMs}ms / 第二次 ${secondMs}ms",
            secondMs < firstMs,
        )
    }

    @Test
    fun `可形变配对的指标都在预算内`() {
        val morphable = MorphIcons.all.filter { it.expectation is MorphExpectation.Morph }
        assertTrue("台账里至少要有一对可形变的图标", morphable.isNotEmpty())

        for (pair in morphable) {
            val plan = pair.plan
            assertTrue("${pair.name} 顶点移动量 ${plan.metrics.travel} 超预算", plan.metrics.travel <= 1.0f)
            assertTrue("${pair.name} 采样点数 ${plan.metrics.samples} 过少", plan.metrics.samples >= MorphSampling.MIN_SAMPLES)
            assertEquals(
                "${pair.name} 的采样点数必须两端一致（索引对应的前提）",
                plan.samplesA.first().points.size,
                plan.samplesB.first().points.size,
            )
        }
    }

    @Test
    fun `可形变配对的端点与中间帧成立`() {
        for (pair in MorphIcons.all.filter { it.expectation is MorphExpectation.Morph }) {
            val plan = pair.plan
            // 端点：t=0 逐点等于 A；t=1 的点集等于 B
            plan.frame(0f).forEachIndexed { i, s -> assertEquals(plan.samplesA[i].points, s.points) }
            plan.frame(1f).forEachIndexed { i, s -> assertSamePointSet(plan.samplesB[i].points, s.points) }

            // 中间帧：不能塌掉（塌掉 = 中间有一帧图标「消失」）
            for (t in listOf(0.25f, 0.5f, 0.75f)) {
                val height = plan.frame(t).maxOf { bboxHeight(it.points) }
                assertTrue(
                    "${pair.name} 在 t=$t 的中间帧高度只剩 $height（网格 ${MorphSampling.GRID}）",
                    height > MorphSampling.GRID * 0.02f,
                )
            }
        }
    }

    @Test
    fun `下拉箭头是同一套画法的镜像_所以能形变`() {
        val plan = MorphIcons.ChevronDownUp.plan
        assertEquals(MorphVerdict.Morphable, plan.verdict)
        // 两边都是 1 条闭合子路径、6 段：这正是「跟设计师提需求」时要求的那组条件
        assertEquals(1, plan.samplesA.size)
        assertEquals(6, plan.metrics.segmentsA)
        assertEquals(plan.metrics.segmentsA, plan.metrics.segmentsB)
        assertTrue(plan.samplesA.single().closed && plan.samplesB.single().closed)
    }

    @Test
    fun `箭头是镜像关系_所以残差大也不能拿来当门控`() {
        val plan = MorphIcons.ChevronDownUp.plan
        // 旋转 + 等比缩放对不上镜像，残差自然很大；可它的中间帧是干净的「翻过去」。
        // 这条断言把「残差只做诊断、不做门控」这个取舍钉在数字上：
        // 哪天有人把门控改回「残差超阈值就降级」，这里不会红，但 MorphIcons 的台账会少一对 ——
        // 所以真正防回归的是上面那条 Morphable 断言。
        assertTrue("镜像关系的残差本来就大：${plan.metrics.fitResidual}", plan.metrics.fitResidual > 0.5f)
        assertTrue("但顶点移动量必须在预算内：${plan.metrics.travel}", plan.metrics.travel <= 1.0f)
    }

    @Test
    fun `下拉箭头的中间帧是「翻过去」而不是「拧过去」`() {
        val plan = MorphIcons.ChevronDownUp.plan
        val height0 = bboxHeight(plan.frame(0f).single().points)
        val heightMid = bboxHeight(plan.frame(0.5f).single().points)
        val height1 = bboxHeight(plan.frame(1f).single().points)

        // 翻过去：中点最扁；拧过去（旋转式对应）则中点的包框反而更大、形状自交
        assertTrue("t=0.5 应该比两端都扁：$height0 / $heightMid / $height1", heightMid < height0 * 0.5f && heightMid < height1 * 0.5f)
        assertTrue("中间帧不能塌成 0 高度（会闪一下）", heightMid > 1f)
    }

    @Test
    fun `加号与减号的点数不齐靠重采样兜底`() {
        val plan = MorphIcons.PlusMinus.plan
        assertEquals(MorphVerdict.Morphable, plan.verdict)
        // 成因表第 1 条：段数不等（12 vs 4）不是拦路虎，重采样把两端补成同一个点数
        assertTrue("夹具前提变了：${plan.metrics.segmentsA} vs ${plan.metrics.segmentsB}", plan.metrics.segmentsA != plan.metrics.segmentsB)
        assertEquals(plan.samplesA.single().points.size, plan.samplesB.single().points.size)
        // 竖杠收短、横杠留下：中间帧的高度应该明显小于加号、不小于减号
        val height0 = bboxHeight(plan.frame(0f).single().points)
        val heightMid = bboxHeight(plan.frame(0.5f).single().points)
        val height1 = bboxHeight(plan.frame(1f).single().points)
        assertTrue("$height1 <= $heightMid <= $height0", heightMid in height1..height0)
    }

    @Test
    fun `过滤按钮的配对被打回交叉过渡`() {
        val plan = MorphIcons.FilterOpen.plan
        val reason = (plan.verdict as MorphVerdict.Degrade).reason
        assertTrue(reason, reason.contains("子路径数量不等"))
        assertEquals("Tune 是 3 条推子 = 6 条子路径（线 + 圆点）", 6, plan.metrics.subpathsA)
        assertEquals("Close 是 1 条子路径", 1, plan.metrics.subpathsB)
        assertTrue("降级后不该产出中间帧", plan.frame(0.5f).isEmpty())
    }
}
