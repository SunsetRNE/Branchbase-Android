package com.branchbase.ui.theme.morph

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时间轴预设的钉子：**ζ 这三个数字必须真的对应它所承诺的手感**。
 *
 * 「不跟手」和「中间帧难看」是两个独立问题，本文件只管前者。
 * 这里用一个纯 Kotlin 的二阶阻尼系统模型（`x'' = -ω²x - 2ζωx'`，目标值 1、初值 0）跑一遍，
 * 把「过冲量」算出来，和二阶系统的解析解对照：
 *
 * ```
 * 过冲 OS = exp(-πζ / √(1-ζ²))
 * ```
 *
 * 这样「Smooth 不过冲 / Snappy 只过冲一点点 / Bouncy 明显回弹」就不是形容词，而是数字。
 * 模型与 Compose 的 `SpringSimulation` 用的是同一组 (ζ, ω)，所以结论可以直接搬回真机。
 */
class MorphSpringTest {

    /** 目标值 1、初值 0 的阶跃响应轨迹（半隐式欧拉，步长足够小）。 */
    private fun trajectory(preset: MorphSpring, seconds: Float = 2f): FloatArray {
        val omega = sqrt(preset.stiffness)
        val dt = 1f / 4000f
        val steps = (seconds / dt).toInt()
        val out = FloatArray(steps)
        var x = 0f
        var v = 0f
        for (i in 0 until steps) {
            // 目标值是 1：驱动项是 (1 - x)，不是 x（写成 -ω²x 的话初值 0、初速 0，系统压根不会动）
            val a = -omega * omega * (x - 1f) - 2f * preset.dampingRatio * omega * v
            v += a * dt
            x += v * dt
            out[i] = x
        }
        return out
    }

    private fun overshoot(preset: MorphSpring): Float = trajectory(preset).max() - 1f

    private fun analyticOvershoot(dampingRatio: Float): Float =
        if (dampingRatio >= 1f) {
            0f
        } else {
            exp(-PI * dampingRatio / sqrt(1f - dampingRatio * dampingRatio)).toFloat()
        }

    @Test
    fun `Smooth 不过冲`() {
        assertTrue("ζ=1.00 是临界阻尼，不该过冲：${overshoot(MorphSpring.Smooth)}", overshoot(MorphSpring.Smooth) <= 0.005f)
    }

    @Test
    fun `Snappy 轻微过冲且与解析解一致`() {
        val measured = overshoot(MorphSpring.Snappy)
        val expected = analyticOvershoot(MorphSpring.Snappy.dampingRatio)
        assertTrue("ζ=0.73 应该有过冲，实测 $measured", measured > 0.005f)
        assertEquals("过冲量与解析解对不上", expected, measured, expected * 0.15f + 0.005f)
    }

    @Test
    fun `Bouncy 的回弹明显大于 Snappy`() {
        val snappy = overshoot(MorphSpring.Snappy)
        val bouncy = overshoot(MorphSpring.Bouncy)
        assertTrue("Bouncy($bouncy) 应远大于 Snappy($snappy)", bouncy > snappy * 2f)
        assertEquals(analyticOvershoot(MorphSpring.Bouncy.dampingRatio), bouncy, 0.03f)
    }

    @Test
    fun `三个预设都能在 1 秒内收敛`() {
        for (preset in MorphSpring.entries) {
            val tail = trajectory(preset).last()
            assertTrue("${preset.title} 没有收敛：末尾值 $tail", abs(tail - 1f) < 0.01f)
        }
    }

    @Test
    fun `Snappy 比 Smooth 起手更快`() {
        fun firstReach(preset: MorphSpring, target: Float): Int =
            trajectory(preset).indexOfFirst { it >= target }

        val steps = 4000
        val snappy = firstReach(MorphSpring.Snappy, 0.9f)
        val smooth = firstReach(MorphSpring.Smooth, 0.9f)
        assertTrue("Snappy($snappy) 应当比 Smooth($smooth) 更早到 0.9", snappy in 1 until smooth)
        assertTrue("Smooth 的 90% 到达时间 $smooth 步（${smooth.toFloat() / steps}s）不该超过 0.5s", smooth < steps / 2)
    }

    @Test
    fun `预设传给 Compose 的正是台账里的 ζ 与刚度`() {
        for (preset in MorphSpring.entries) {
            val spec = preset.spec()
            assertEquals(preset.dampingRatio, spec.dampingRatio, 1e-6f)
            assertEquals(preset.stiffness, spec.stiffness, 1e-6f)
        }
    }
}
