package com.branchbase.ui.theme.morph

import androidx.compose.ui.graphics.vector.ImageVector
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 形变的「计划」：一对图标分析一次，之后每帧只做插值。
 *
 * ## 这一层在解决什么
 *
 * 成因表里的六条，第 4 步（顶点对应）占了大半：
 *
 * | # | 成因 | 术语 | 中间帧表现 | 谁来管 |
 * |---|------|------|------------|--------|
 * | 1 | 顶点数不等 | 点数不匹配 → 靠**重采样**兜底 | 细节丢失、圆滑化 | [MorphSampling]（兜底，只报告不拦） |
 * | 2 | 起点位置 / 绕向不一致 | start-point offset / winding direction | 整体**拧麻花**、翻卷 | [MorphAnalyzer.correspondenceOf] |
 * | 3 | 一个闭环一个开环 | closed vs open topology 不匹配 | 中途撕开或强行接上 | [MorphGate]（拦） |
 * | 4 | 质心、尺度差太大 | 相似变换参数（θ、lnσ）跨度过大 | 整体**漂移**、被误读成旋转 | [MorphMetrics]（量化）+ 验收预算 |
 * | 5 | 子路径数量不等 | subpath count mismatch | 中间帧凭空多线/并线 | [MorphGate]（拦） |
 * | 6 | 描边↔填充、描边宽度突变 | 不是插值问题 | 像插值崩了 | [MorphGate]（拦） |
 *
 * ## 两处刻意的取舍（与 morphicons 那类「跨图标库对齐」库不同）
 *
 * 1. **相似变换只用来算对应关系，不烘进渲染坐标**。把那套库的做法照搬过来，
 *    t=0 画出来的就不是原图标（被转了一个角、缩了一截）—— 对「把 Lucide 的图标对齐到
 *    Heroicons 的网格」是对的，对 UI 里的状态切换是错的：下拉箭头收起时必须原样朝下。
 *    本实现保证 `frame(0f)` 逐点等于 A、`frame(1f)` 的点集逐点等于 B（有单测钉住）。
 * 2. **对应关系按「顶点总移动量最小」挑，不按 Procrustes 残差最小挑**。
 *    残差最小解在镜像形态上会挑出「旋转 180°」的对应（残差近 0），听起来更优，
 *    但中间帧会拧；移动量最小解直接优化「眼睛看到的东西」—— 每个顶点少走一点，
 *    中间帧就少乱一点。残差仍然算，但只作为**诊断指标**（成因表第 4 条）。
 *    实测佐证：`KeyboardArrowDown ⇄ KeyboardArrowUp` 是**镜像**关系，残差 0.71（很大），
 *    但移动量 0.76、中间帧是干净的「翻过去」—— 拿残差当门控会把这对好素材误杀。
 *    另外加了一条**奥卡姆偏好**：旋转起点 / 翻转绕向要额外付 15% 的代价，
 *    这样对称图形（正多边形、圆）不会因为浮点噪声随机挑一个起点。
 */
internal object MorphGate {

    /** 单图标子路径数上限：超过这个数说明图标本身是插画，不是图标，形变没有意义。 */
    const val MAX_SUBPATHS = 8

    /**
     * 归一化顶点位移的安全上限。
     *
     * 单位是「图形自身的 RMS 半径」：1.0 表示平均每个顶点移动了一个自身半径那么远。
     * 两个已知配对实测 0.67 / 0.76（见 `MorphPairTest`），所以 1.5 留了约 2 倍余量 ——
     * 它只是一道**兜底**，真正决定「做不做形变」的是 [MorphPair.expectation] 里写死的那张清单。
     */
    const val MAX_TRAVEL = 1.5f

    internal const val OCCAM_PENALTY = 0.15f
}

/** 对应关系：B 的哪个顶点接 A 的第 i 个顶点。 */
data class Correspondence(val shift: Int, val flipped: Boolean, val travel: Float) {

    /**
     * 把 A 的索引映射到 B 的索引。
     *
     * - 闭合环：起点可以整体旋转（[shift]），绕向可以翻转（[flipped]）；
     * - 开放折线：不能旋转（两端就是两端），只能翻转。
     */
    fun map(index: Int, count: Int, closed: Boolean): Int =
        mapIndex(index, count, closed, shift, flipped)

    /** 打分：移动量 + 奥卡姆偏好（见 [MorphPlan] 的说明）。 */
    internal fun score(count: Int, closed: Boolean): Float {
        val shiftFraction = if (!closed || count <= 0) 0f else minOf(shift, count - shift).toFloat() / count
        val complexity = shiftFraction + if (flipped) 1f else 0f
        return travel * (1f + MorphGate.OCCAM_PENALTY * complexity)
    }
}

/** 索引映射的自由函数版本：候选搜索里会被调用 O(N²) 次，不要每次都建对象。 */
internal fun mapIndex(index: Int, count: Int, closed: Boolean, shift: Int, flipped: Boolean): Int = when {
    count <= 0 -> 0
    closed && flipped -> (count - index + shift).mod(count)
    closed -> (index + shift).mod(count)
    flipped -> count - 1 - index
    else -> index
}

/** 形变质量的量化指标 —— **验收的依据，不是玄学**。
 *
 * 每一项都对应验收清单里的一条：`travel` 对应「中间帧会不会乱」，
 * `fitResidual`/`thetaDegrees`/`logScale`/`centroidDistance` 对应成因表第 4 条，
 * `segmentsA/B` 对应「数路径段数」。
 */
data class MorphMetrics(
    val subpathsA: Int,
    val subpathsB: Int,
    val segmentsA: Int,
    val segmentsB: Int,
    val samples: Int,
    val travel: Float,
    val fitResidual: Float,
    val thetaDegrees: Float,
    val logScale: Float,
    val centroidDistance: Float,
    val flipped: Boolean,
    val startShift: Int,
)

/** 判定结果。 */
sealed interface MorphVerdict {
    /** 可以做路径插值形变。 */
    data object Morphable : MorphVerdict

    /**
     * 不做形变，退回交叉过渡（`AnimatedStateIcon`）。原因写给人看（会进降级清单与测试断言）。
     *
     * 类型名刻意**不叫 `Crossfade`**：`ui/profile` 里那个同名组件是「`Box` 里叠层」的实现，
     * 全项目有一条源码级钉子（`CrossfadeLayoutTest`）按 `Crossfade(` 字面量扫调用点、
     * 要求 lambda 体第一行是 `Column(`。形变层的判定结果跟它没有关系，撞名只会让那条钉子误报。
     */
    data class Degrade(val reason: String) : MorphVerdict
}

/** 插值方式（成因表里第 5 步的两种）。 */
enum class MorphInterpolation {
    /** 原始坐标线性插值。默认：可预测，且不会因为质心偏离而绕圈。 */
    Linear,

    /**
     * 极坐标插值：绕质心的角度（走最短弧）+ **对数**半径。
     * 适合「绕质心转」的形态（blob 类）；质心偏移大的两个形状会被它拉出额外的旋转感，慎用。
     */
    Polar,
}

/** 一对可以（或明确不可以）互相形变的图标。 */
class MorphPair(
    /** 诊断名，进日志与测试断言。 */
    val name: String,
    val from: ImageVector,
    val to: ImageVector,
    /**
     * **明确写下来的预期**：这对图标做形变，还是明确不做（以及为什么）。
     *
     * 这是「极端组合清单」的落法：与其事后调阈值、猜某个残差数字，
     * 不如把「这对不做」写在代码里，再用单测把实测指标钉住 ——
     * 谁换了图标、把结构换坏了，测试当场红，而不是等用户看到中间帧拧麻花。
     */
    val expectation: MorphExpectation,
) {
    /** 分析结果（懒算 + 缓存：一对图标只分析一次）。 */
    val plan: MorphPlan by lazy { MorphAnalyzer.analyze(name, from, to) }

    val verdict: MorphVerdict get() = plan.verdict
}

/** 配对预期。 */
sealed interface MorphExpectation {
    /** 预期可形变。单测会同时校验实测指标在预算内（见 `MorphPairTest`）。 */
    data object Morph : MorphExpectation

    /** 预期降级为交叉过渡，理由是结构性的、写死的。 */
    data class Fallback(val reason: String) : MorphExpectation
}

/**
 * 一对图标的完整形变计划。不可变，可跨帧/跨重组复用。
 */
class MorphPlan internal constructor(
    val name: String,
    val samplesA: List<SampledSubpath>,
    val samplesB: List<SampledSubpath>,
    val correspondences: List<Correspondence>,
    val metrics: MorphMetrics,
    val verdict: MorphVerdict,
) {

    val morphable: Boolean get() = verdict is MorphVerdict.Morphable

    /**
     * 第 5、6 步：取进度 `t` 处的中间帧（点列形式，第 6 步的「序列化」在渲染层做）。
     *
     * 端点恒等是**硬保证**：`t <= 0` 时逐点等于 A 的采样、`t >= 1` 时逐点等于 B 的采样（仅遍历顺序可能按对应关系旋转过）。
     * 「反向播放是否对称」（验收第 2 条）也由这个结构保证：反向只会换对应关系的方向，点集不变。
     *
     * 每帧会分配 O(点数) 的小对象：这是有意的取舍 —— 值在**绘制期**消费（不触发重组），
     * 相对「每帧重组一次」的开销，这点分配可以忽略（见 `MorphIcon`）。
     */
    fun frame(t: Float, interpolation: MorphInterpolation = MorphInterpolation.Linear): List<SampledSubpath> {
        if (!morphable) return emptyList()
        val clamped = t.coerceIn(0f, 1f)
        return samplesA.indices.map { index ->
            val a = samplesA[index]
            val b = samplesB[index]
            val correspondence = correspondences[index]
            val count = a.points.size
            val points = ArrayList<Pt>(count)
            for (i in 0 until count) {
                val target = b.points[correspondence.map(i, count, a.closed)]
                when {
                    clamped <= 0f -> points.add(a.points[i])
                    clamped >= 1f -> points.add(target)
                    interpolation == MorphInterpolation.Polar -> points.add(
                        polarLerp(a.points[i], target, a.centroid, b.centroid, clamped),
                    )

                    else -> points.add(lerpPt(a.points[i], target, clamped))
                }
            }
            a.copy(points = points)
        }
    }

    companion object {
        internal fun rejected(name: String, reason: String, metrics: MorphMetrics) = MorphPlan(
            name = name,
            samplesA = emptyList(),
            samplesB = emptyList(),
            correspondences = emptyList(),
            metrics = metrics,
            verdict = MorphVerdict.Degrade(reason),
        )
    }
}

/** [SampledSubpath] 上补一个质心 —— 极坐标插值要用它。 */
private val SampledSubpath.centroid: Pt
    get() {
        if (points.isEmpty()) return Pt(0f, 0f)
        var sx = 0f
        var sy = 0f
        for (p in points) {
            sx += p.x
            sy += p.y
        }
        return Pt(sx / points.size, sy / points.size)
    }

/** 分析器：一对图标进，一份 [MorphPlan] 出。 */
internal object MorphAnalyzer {

    fun analyze(name: String, from: ImageVector, to: ImageVector): MorphPlan {
        val zero = MorphMetrics(0, 0, 0, 0, 0, 0f, 0f, 0f, 0f, 0f, false, 0)

        // 0. 不支持的输入：带 group 变换 / 裁剪的图标（不猜，直接降级）
        MorphParsing.unsupportedReason(from)?.let { return MorphPlan.rejected(name, "源图标$it", zero) }
        MorphParsing.unsupportedReason(to)?.let { return MorphPlan.rejected(name, "目标图标$it", zero) }

        val rawA = MorphParsing.subpaths(from)
        val rawB = MorphParsing.subpaths(to)
        val segmentsA = rawA.sumOf { it.segments.size }
        val segmentsB = rawB.sumOf { it.segments.size }
        val base = zero.copy(
            subpathsA = rawA.size,
            subpathsB = rawB.size,
            segmentsA = segmentsA,
            segmentsB = segmentsB,
        )

        // 1. 空图标：没有可形变的路径（先判 —— 理由比「数量不等 0 vs 1」直接）
        if (rawA.isEmpty() || rawB.isEmpty()) {
            return MorphPlan.rejected(name, "源图标没有可形变的路径", base)
        }
        // 2. 子路径数量（成因表第 5 条）：数量不等时索引配对本身就无意义
        if (rawA.size != rawB.size) {
            return MorphPlan.rejected(
                name,
                "子路径数量不等：${rawA.size} vs ${rawB.size}（中间帧会凭空多线/并线）",
                base,
            )
        }
        if (rawA.size > MorphGate.MAX_SUBPATHS) {
            return MorphPlan.rejected(name, "子路径过多：${rawA.size} > ${MorphGate.MAX_SUBPATHS}", base)
        }

        // 3. 逐条子路径的结构检查：闭合拓扑（第 3 条）与描边/填充（第 6 条）
        rawA.forEachIndexed { i, a ->
            val b = rawB[i]
            if (a.closed != b.closed) {
                return MorphPlan.rejected(
                    name,
                    "第 ${i + 1} 条子路径闭合性不一致：${if (a.closed) "闭合" else "开放"} vs ${if (b.closed) "闭合" else "开放"}（中间帧会撕开）",
                    base,
                )
            }
            if (a.style.filled != b.style.filled) {
                return MorphPlan.rejected(
                    name,
                    "第 ${i + 1} 条子路径填充/描边不一致（这不是插值问题）",
                    base,
                )
            }
            val wide = maxOf(a.style.strokeWidth, b.style.strokeWidth)
            val narrow = minOf(a.style.strokeWidth, b.style.strokeWidth)
            if (wide > 0f && (narrow <= 0f || wide / narrow > 2f)) {
                return MorphPlan.rejected(
                    name,
                    "第 ${i + 1} 条子路径描边宽度差过大：$narrow vs $wide（这不是插值问题）",
                    base,
                )
            }
        }

        // 3. 展平 → 角点统计 → 取同一个 N → 重采样（第 3 步）
        val flatsA = rawA.map { MorphSampling.flatten(it) }
        val flatsB = rawB.map { MorphSampling.flatten(it) }
        val cornersA = flatsA.map { it.cornerArcPositions() }
        val cornersB = flatsB.map { it.cornerArcPositions() }
        val samples = (0 until rawA.size).maxOf { i ->
            MorphSampling.sampleCount(
                rawA[i].segments.size,
                rawB[i].segments.size,
                cornersA[i].size,
                cornersB[i].size,
            )
        }
        val samplesA = (0 until rawA.size).map { i ->
            SampledSubpath(
                points = MorphSampling.resample(flatsA[i], samples),
                closed = rawA[i].closed,
                style = rawA[i].style,
                segments = rawA[i].segments.size,
                corners = cornersA[i].size,
            )
        }
        val samplesB = (0 until rawB.size).map { i ->
            SampledSubpath(
                points = MorphSampling.resample(flatsB[i], samples),
                closed = rawB[i].closed,
                style = rawB[i].style,
                segments = rawB[i].segments.size,
                corners = cornersB[i].size,
            )
        }

        // 4. 逐条求对应关系（第 4 步）：起点 / 绕向 / 移动量 / 相似变换诊断量
        val correspondences = ArrayList<Correspondence>(rawA.size)
        var thetaWeighted = 0f
        var logScaleWeighted = 0f
        var residualWeighted = 0f
        var centroidWeighted = 0f
        for (i in samplesA.indices) {
            val a = samplesA[i]
            val b = samplesB[i]
            val correspondence = correspondenceOf(a, b)
            correspondences.add(correspondence)

            val na = normalize(a.points)
            val nb = normalize(b.points)
            val mapped = normalize(a.points.indices.map { nb.points[correspondence.map(it, a.points.size, a.closed)] })
            val fit = procrustes(na.points, mapped.points)
            thetaWeighted += fit.theta
            logScaleWeighted += ln(if (nb.rms <= 1e-5f) 1f else na.rms / nb.rms)
            residualWeighted += fit.residual
            centroidWeighted += na.centroid.distanceTo(nb.centroid)
        }
        val subpathCount = samplesA.size
        val metrics = base.copy(
            samples = samples,
            travel = correspondences.map { it.travel }.average().toFloat(),
            fitResidual = residualWeighted / subpathCount,
            thetaDegrees = Math.toDegrees((thetaWeighted / subpathCount).toDouble()).toFloat(),
            logScale = logScaleWeighted / subpathCount,
            centroidDistance = centroidWeighted / subpathCount,
            flipped = correspondences.any { it.flipped },
            startShift = correspondences.maxOf { it.shift },
        )

        // 5. 兜底安全网（结构都过了、但顶点要走的距离离谱）
        if (metrics.travel > MorphGate.MAX_TRAVEL) {
            return MorphPlan.rejected(
                name,
                "顶点移动量过大：${metrics.travel} > ${MorphGate.MAX_TRAVEL}（中间帧会糊）",
                metrics,
            )
        }

        return MorphPlan(
            name = name,
            samplesA = samplesA,
            samplesB = samplesB,
            correspondences = correspondences,
            metrics = metrics,
            verdict = MorphVerdict.Morphable,
        )
    }

    /**
     * 第 4 步本体：在「绕向 × 起点」的候选里挑一个。
     *
     * 打分见 [Correspondence.score]：**最小顶点移动量** + 奥卡姆偏好。
     * 一定先把两端各自正规化（质心对到原点、RMS 半径对到 1）再比 ——
     * 否则「谁画得大一点」会直接变成移动量，把真正的对应关系淹掉。
     */
    internal fun correspondenceOf(a: SampledSubpath, b: SampledSubpath): Correspondence {
        val count = minOf(a.points.size, b.points.size)
        val na = normalize(a.points.take(count)).points
        val nb = normalize(b.points.take(count)).points
        var best: Correspondence? = null
        var bestScore = Float.MAX_VALUE
        val shifts = if (a.closed) 0 until count else 0 until 1
        for (shift in shifts) {
            for (flipped in FLIP_CANDIDATES) {
                val candidate = Correspondence(shift, flipped, travel(na, nb, shift, flipped, a.closed))
                val score = candidate.score(count, a.closed)
                if (score < bestScore) {
                    bestScore = score
                    best = candidate
                }
            }
        }
        return best ?: Correspondence(0, false, 0f)
    }

    private val FLIP_CANDIDATES = booleanArrayOf(false, true)

    private fun travel(a: List<Pt>, b: List<Pt>, shift: Int, flipped: Boolean, closed: Boolean): Float {
        if (a.isEmpty()) return 0f
        var acc = 0f
        for (i in a.indices) {
            // 候选搜索里这一步会被调用 O(N²) 次：用自由函数 mapIndex，不要每次建对象
            val j = mapIndex(i, b.size, closed, shift, flipped)
            val dx = a[i].x - b[j].x
            val dy = a[i].y - b[j].y
            acc += dx * dx + dy * dy
        }
        return sqrt(acc / a.size)
    }
}

/** 供单测/诊断：两点是否在容差内重合。 */
internal fun Pt.approximately(other: Pt, tolerance: Float = 1e-3f): Boolean =
    abs(x - other.x) <= tolerance && abs(y - other.y) <= tolerance
