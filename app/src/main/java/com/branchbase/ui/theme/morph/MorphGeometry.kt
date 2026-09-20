package com.branchbase.ui.theme.morph

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 「路径插值形变」的几何内核：**纯 Kotlin，不含任何 Compose / Android 类型**。
 *
 * 这样切分的理由不是洁癖：整条形变管线里真正会出错的是数学（弧长采样、对应关系、相似变换），
 * 而这些东西一旦和 `Path` / `Canvas` 混在一起，就只能靠真机肉眼看 —— 现在是普通 JVM 单测。
 * 与 Compose 的接缝只有两处：入口 [MorphParsing]（`ImageVector` → [Subpath]）、
 * 出口 `MorphIcon`（点列 → `Path`）。
 *
 * ## 术语
 *
 * 中文口语里的「插帧」至少混着三件事，本文件只管其中一件，别混着说：
 *
 * | 中文说法 | 准确术语 | 插的是什么 |
 * |----------|----------|------------|
 * | 视频/AI 补帧 | frame interpolation（RIFE / FILM 那类） | 像素 |
 * | 补间动画 | tweening / property interpolation | 数值属性（位置、透明度、旋转、颜色） |
 * | **图标形变** | **shape tweening / path interpolation / morphing** | **路径顶点** |
 * | （传统动画） | in-betweening（原画师画关键帧、动画师补中间画） | 手画的 |
 *
 * 本文件实现的是**第三行**：路径插值形变（shape interpolation / path morphing）。
 * 文档里写「插帧」会跟视频补帧混淆，所以本项目统一写「形变」或「路径插值」。
 *
 * 管线的六步（每一步的失真点都写在对应函数的注释里）：
 *
 * | 步 | 这一步在干什么 | 准确说法 | 失真点 |
 * |----|----------------|----------|--------|
 * | 1 | `d` / IconNode → 三次贝塞尔子路径，记录 `closed` | 解析为 cubic subpaths | `A`/`Q`/`S` 要先转 `C`，转换本身有拟合误差（[MorphParsing]） |
 * | 2 | 不同 viewBox 重栅格到同一网格 | 坐标空间归一化（等同 `xMidYMid meet`） | 不做这步，缩放/偏移差会被下一步**误读成旋转** |
 * | 3 | 采样成 N 个按弧长等距的点，角点/端点锚为精确采样 | arc-length 重采样 | 重采样必然抹平细节；N 小→圆滑，N 大→抖动 |
 * | 4 | 质心对齐 + 相似变换 + 闭合环挑「切断点」 | 顶点对应（correspondence） | **所有难看的根源**：对应关系错了后面全错 |
 * | 5 | 顶点插值：极坐标或原始坐标 lerp | interpPolar / interpLinear | 直线 lerp 会「塌陷穿过」 |
 * | 6 | 采样点 → 折线 `d`（闭合补 `Z`） | 序列化 | 输出是折线不是贝塞尔，N 不够会看到「多边形感」 |
 *
 * 关键点：**第 4 步是「算出了错误的对应关系」，第 5 步只是把这个错误演出来**。
 * 所以换插值算法（linear ↔ polar）基本不解决问题 —— 换对应关系策略或换素材才解决。
 */

/** 网格内的一个点。单位是归一化网格，不是 dp、不是 px。 */
data class Pt(val x: Float, val y: Float) {
    operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
    operator fun times(k: Float) = Pt(x * k, y * k)

    fun distanceTo(o: Pt): Float = hypot(x - o.x, y - o.y)
}

fun lerpPt(a: Pt, b: Pt, t: Float): Pt = Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

/** 一条三次贝塞尔段（第 1 步的产物单位）。 */
data class Cubic(val p0: Pt, val c1: Pt, val c2: Pt, val p3: Pt)

/**
 * 子路径的绘制样式。
 *
 * 形变**只在样式同类时进行**：描边↔填充、或者描边宽度差一倍，视觉上都会像「插值崩了」，
 * 但那根本不是插值问题（成因表第 6 条）。判定在 [MorphGate]。
 */
data class SubpathStyle(val filled: Boolean, val strokeWidth: Float) {
    companion object {
        val Fill = SubpathStyle(filled = true, strokeWidth = 0f)
    }
}

/**
 * 一条子路径：若干三次贝塞尔段 + 是否闭合 + 样式。
 *
 * `closed` 参与门控：一条闭合环对一条开放折线（例如「圆环 → 对勾」）在拓扑上就不成立，
 * 中间帧只能撕开或强行接上（成因表第 3 条）。
 */
data class Subpath(val segments: List<Cubic>, val closed: Boolean, val style: SubpathStyle)

/** 一条子路径的起笔点（第一个段的起点）。 */
internal val Subpath.start: Pt get() = segments.first().p0

// ---------------------------------------------------------------------------
// 第 3 步：展平 + 弧长重采样
// ---------------------------------------------------------------------------

/**
 * 展平后的折线 + 弧长索引。
 *
 * 贝塞尔本身没有「按弧长取点」的解析解，所以先把曲线展平成足够密的折线，
 * 再在折线上按弧长取点 —— 这是所有插值类库的通行做法，误差上限由 [MorphSampling.TOLERANCE] 控制。
 */
internal class Polyline(val points: List<Pt>, val closed: Boolean) {

    /** 到第 i 个点的累计弧长（`cumulative[0] == 0`）。 */
    val cumulative: FloatArray = FloatArray(points.size)

    /** 总弧长；闭合折线含收尾那一段。 */
    val length: Float

    init {
        var acc = 0f
        for (i in 1 until points.size) {
            acc += points[i - 1].distanceTo(points[i])
            cumulative[i] = acc
        }
        if (closed && points.size > 1) acc += points.last().distanceTo(points.first())
        length = acc
    }

    /** 弧长 `s` 处的点（`s` 会被夹到 `[0, length]`）。 */
    fun pointAt(s: Float): Pt {
        if (points.size == 1 || length <= 0f) return points.first()
        val target = s.coerceIn(0f, length)
        var lo = 0
        var hi = cumulative.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (cumulative[mid] < target) lo = mid + 1 else hi = mid
        }
        val i = max(1, lo)
        val segStart = cumulative[i - 1]
        val segLen = cumulative[i] - segStart
        val u = if (segLen <= 0f) 0f else (target - segStart) / segLen
        return lerpPt(points[i - 1], points[i], u)
    }

    /**
     * 角点的弧长位置。
     *
     * 角点 = 相邻两段的走向夹角超过 [MorphSampling.CORNER_DEGREES]。这些点必须**精确**落进采样结果，
     * 否则箭头/加减号的尖角会被「切削」成圆角（重采样把角抹平的第一现场）。
     *
     * 两处容易写错的地方：
     *
     * 1. **闭合环的起点也是一个顶点**，必须一起判（漏掉它，正方形只会数出三个角）；
     * 2. 去重不能按「相邻索引」去 —— 多边形每个顶点都是角，那样会把所有角合成一个。
     *    正确做法是按**弧长距离**聚类：展平会在一个尖角附近产生好几个点，
     *    它们之间的弧长差很小；真正的两个角不会挨得这么近。
     */
    fun cornerArcPositions(): List<Float> {
        val n = points.size
        if (n < 3) return emptyList()
        val hits = ArrayList<Pair<Float, Float>>()
        val from = if (closed) 0 else 1
        val until = if (closed) n else n - 1
        for (i in from until until) {
            val prev = points[(i - 1 + n) % n]
            val cur = points[i]
            val next = points[(i + 1) % n]
            val a = cur - prev
            val b = next - cur
            val la = hypot(a.x, a.y)
            val lb = hypot(b.x, b.y)
            if (la <= 1e-4f || lb <= 1e-4f) continue
            val dot = ((a.x * b.x + a.y * b.y) / (la * lb)).coerceIn(-1f, 1f)
            val turn = Math.toDegrees(kotlin.math.acos(dot).toDouble()).toFloat()
            if (turn >= MorphSampling.CORNER_DEGREES) hits.add(cumulative[i] to turn)
        }
        if (hits.isEmpty()) return emptyList()

        val sorted = hits.sortedBy { it.first }
        val merged = ArrayList<Float>()
        var i = 0
        while (i < sorted.size) {
            var j = i
            var bestArc = sorted[i].first
            var bestTurn = sorted[i].second
            while (j + 1 < sorted.size && sorted[j + 1].first - sorted[j].first <= MorphSampling.CORNER_MERGE_DISTANCE) {
                j++
                if (sorted[j].second > bestTurn) {
                    bestTurn = sorted[j].second
                    bestArc = sorted[j].first
                }
            }
            merged.add(bestArc)
            i = j + 1
        }
        return merged
    }
}

/** 一个子路径采样完之后的形态：等点数点列 + 元信息（给门控与验收用）。 */
data class SampledSubpath(
    val points: List<Pt>,
    val closed: Boolean,
    val style: SubpathStyle,
    /** 原始段数（`M`/`C` 段数）——两端不一致时靠重采样兜底，但要报告出来。 */
    val segments: Int,
    /** 角点个数。 */
    val corners: Int,
)

internal object MorphSampling {

    /** 归一化网格边长。所有坐标都在 `[0, GRID]` 内，跟原始 viewBox 无关。 */
    const val GRID = 1000f

    /**
     * 展平容差（网格单位）：控制点离弦的最大偏离。
     * 1000 的网格上 2 个单位 = 24dp 图标上的 0.05px，肉眼与 0.5x 截图都分辨不出。
     */
    const val TOLERANCE = 2f

    /** 角点判定：走向夹角超过这个度数就算角。 */
    const val CORNER_DEGREES = 30f

    /**
     * 角点去重的弧长距离：比这更近的两个「角」是同一个角被展开平成了好几个点。
     * 取展平容差的 4 倍（= 0.8% 网格），比这更近的两个角在 24dp 图标上本来也分不出来。
     */
    const val CORNER_MERGE_DISTANCE = TOLERANCE * 4f

    /** 每个子路径的采样点数下限 / 上限（上限同时是「别把抖动引进来」的护栏）。 */
    const val MIN_SAMPLES = 24
    const val MAX_SAMPLES = 96

    /**
     * 采样点数：由**两端**的段数共同决定，两端必须取同一个 N，否则索引对应无从谈起。
     *
     * N 的取向（对应验收清单第 4 条「缩放看」）：N 小 → 中间帧圆滑、细节丢；
     * N 大 → 细节在、但采样点抖动会被放大成可见的高频抖动。
     */
    fun sampleCount(aSegments: Int, bSegments: Int, aCorners: Int, bCorners: Int): Int {
        val base = max(aSegments, bSegments) * 6
        val needed = max(base, max(aCorners, bCorners) + 4)
        return needed.coerceIn(MIN_SAMPLES, MAX_SAMPLES)
    }

    /** 把一条子路径展平成折线（含首尾点）。 */
    fun flatten(subpath: Subpath): Polyline {
        val raw = ArrayList<Pt>()
        for (seg in subpath.segments) {
            if (raw.isEmpty()) raw.add(seg.p0)
            flattenCubic(seg, raw, 0)
        }
        if (raw.isEmpty()) raw.add(Pt(0f, 0f))

        // 去掉零长段。两个来源，都会真的把结果算错：
        // 1. `Z` 之后补的那条收尾段让**闭合环的末尾与起点重合** —— 留着的话，
        //    「起点也是个角」会被零向量挤掉（正方形只数出 3 个角），
        //    而起点正是第 4 步要挑的那个「切断点」；
        // 2. 展平在尖角附近可能产出重合点，那里没有方向，夹角判定会退化成 0 或 NaN。
        val points = ArrayList<Pt>(raw.size)
        for (p in raw) {
            if (points.isEmpty() || p.distanceTo(points.last()) > 1e-4f) points.add(p)
        }
        if (subpath.closed) {
            while (points.size > 1 && points.last().distanceTo(points.first()) <= 1e-3f) {
                points.removeAt(points.size - 1)
            }
        }
        return Polyline(points, subpath.closed)
    }

    private fun flattenCubic(c: Cubic, out: MutableList<Pt>, depth: Int) {
        if (depth >= 12 || isFlat(c)) {
            out.add(c.p3)
            return
        }
        val (l, r) = splitCubic(c)
        flattenCubic(l, out, depth + 1)
        flattenCubic(r, out, depth + 1)
    }

    private fun isFlat(c: Cubic): Boolean =
        distanceToLine(c.c1, c.p0, c.p3) <= TOLERANCE && distanceToLine(c.c2, c.p0, c.p3) <= TOLERANCE

    private fun distanceToLine(p: Pt, a: Pt, b: Pt): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = hypot(dx, dy)
        if (len <= 1e-5f) return p.distanceTo(a)
        return abs(dy * (p.x - a.x) - dx * (p.y - a.y)) / len
    }

    private fun splitCubic(c: Cubic): Pair<Cubic, Cubic> {
        val p01 = lerpPt(c.p0, c.c1, 0.5f)
        val p12 = lerpPt(c.c1, c.c2, 0.5f)
        val p23 = lerpPt(c.c2, c.p3, 0.5f)
        val p012 = lerpPt(p01, p12, 0.5f)
        val p123 = lerpPt(p12, p23, 0.5f)
        val mid = lerpPt(p012, p123, 0.5f)
        return Cubic(c.p0, p01, p012, mid) to Cubic(mid, p123, p23, c.p3)
    }

    /**
     * 第 3 步本体：按弧长等距重采样成 `n` 个点，并把角点锚成精确采样。
     *
     * - 闭合环：`n` 个点均匀分布在一圈上，索引 0 就是「切断点」（起笔点）；
     * - 开放折线：`n` 个点**包含两端**（`i / (n-1)`），端点不能被采样丢掉。
     *
     * 角点锚定用的是「把最近的采样点挪到角上」而不是「额外插一个点」：
     * 后者会让两端的点数不再相等，而点数是索引对应的前提。
     */
    fun resample(polyline: Polyline, n: Int): List<Pt> {
        val out = ArrayList<Pt>(n)
        if (polyline.length <= 0f || polyline.points.size == 1) {
            repeat(n) { out.add(polyline.points.first()) }
            return out
        }
        val divisor = if (polyline.closed) n.toFloat() else (n - 1).toFloat()
        for (i in 0 until n) out.add(polyline.pointAt(polyline.length * i / divisor))

        val corners = polyline.cornerArcPositions()
        if (corners.isEmpty()) return out
        val used = HashSet<Int>()
        for (cornerS in corners) {
            val exact = polyline.pointAt(cornerS)
            var bestIndex = -1
            var bestDistance = Float.MAX_VALUE
            for (i in 0 until n) {
                if (i in used) continue
                val d = out[i].distanceTo(exact)
                if (d < bestDistance) {
                    bestDistance = d
                    bestIndex = i
                }
            }
            if (bestIndex >= 0) {
                out[bestIndex] = exact
                used.add(bestIndex)
            }
        }
        return out
    }
}

// ---------------------------------------------------------------------------
// 第 4 步的几何工具：绕向、质心、相似变换
// ---------------------------------------------------------------------------

/**
 * 相似变换的正规化结果（第 2 步的收尾 + 第 4 步的输入）。
 *
 * [scale] 是「把图形缩放到单位 RMS 半径」的那个系数，[centroid] 是质心。
 * 正规化只用于**求对应关系**（这一步要比的是形状，不是坐标），
 * **不参与渲染** —— 否则 t=0 画出来的就不是原来那个图标了（见 [MorphPlan] 的说明）。
 */
internal class Normalized(val points: List<Pt>, val centroid: Pt, val rms: Float)

internal fun normalize(points: List<Pt>): Normalized {
    if (points.isEmpty()) return Normalized(points, Pt(0f, 0f), 1f)
    var sx = 0f
    var sy = 0f
    for (p in points) {
        sx += p.x
        sy += p.y
    }
    val c = Pt(sx / points.size, sy / points.size)
    var acc = 0f
    for (p in points) {
        val dx = p.x - c.x
        val dy = p.y - c.y
        acc += dx * dx + dy * dy
    }
    val rms = sqrt(acc / points.size)
    val k = if (rms <= 1e-5f) 1f else 1f / rms
    return Normalized(points.map { Pt((it.x - c.x) * k, (it.y - c.y) * k) }, c, rms)
}

/** 有向面积（鞋带公式）。符号即绕向：屏幕坐标 y 向下，所以正号在这里表示「顺时针」。 */
internal fun signedArea(points: List<Pt>): Float {
    if (points.size < 3) return 0f
    var acc = 0f
    for (i in points.indices) {
        val a = points[i]
        val b = points[(i + 1) % points.size]
        acc += a.x * b.y - b.x * a.y
    }
    return acc / 2f
}

/**
 * 最优相似变换（旋转 θ + 等比缩放 σ + 平移）与拟合残差 —— Procrustes 的闭式解。
 *
 * 输入必须是**已正规化**（质心 0、RMS 半径 1）的两组等长点列，此时
 * `θ = atan2(Σ a×b, Σ a·b)`、`σ = Σ a·b / Σ|a|²` 一步到位。
 *
 * 这个函数在本项目里的用途有两处，都**不是**用来把源图形转过去的：
 *
 * 1. **诊断**：θ / lnσ / 残差是「质心、尺度差太大」（成因表第 4 条）的量化依据；
 * 2. **验收**：残差是「顶点对应本身就是错的」的可测代理，写进单测的预算里。
 *
 * 为什么不把 θ/σ 烘进源图形：那会让 t=0 画出来的**不是原图标**（被转了一个角、缩了一截），
 * 对 morphicons 那种「跨图标库对齐」是合适的，对 UI 里的状态切换是错的 ——
 * 下拉箭头在收起时必须原样朝下。取舍写在 `docs/specs/morph-design.md`。
 */
internal class Procrustes(val theta: Float, val sigma: Float, val residual: Float)

internal fun procrustes(a: List<Pt>, b: List<Pt>): Procrustes {
    var dot = 0f
    var cross = 0f
    var normA = 0f
    for (i in a.indices) {
        dot += a[i].x * b[i].x + a[i].y * b[i].y
        cross += a[i].x * b[i].y - a[i].y * b[i].x
        normA += a[i].x * a[i].x + a[i].y * a[i].y
    }
    val theta = atan2(cross, dot)
    val sigma = if (normA <= 1e-6f) 1f else sqrt(dot * dot + cross * cross) / normA
    val cosT = cos(theta) * sigma
    val sinT = sin(theta) * sigma
    var err = 0f
    var base = 0f
    for (i in a.indices) {
        val rx = a[i].x * cosT - a[i].y * sinT
        val ry = a[i].x * sinT + a[i].y * cosT
        val dx = rx - b[i].x
        val dy = ry - b[i].y
        err += dx * dx + dy * dy
        base += b[i].x * b[i].x + b[i].y * b[i].y
    }
    return Procrustes(theta, sigma, if (base <= 1e-6f) 0f else sqrt(err / base))
}

/** 极坐标插值：绕质心的角度 + 对数半径。见 [MorphInterpolation]。 */
internal fun polarLerp(a: Pt, b: Pt, ca: Pt, cb: Pt, t: Float): Pt {
    val c = lerpPt(ca, cb, t)
    val ra = max(a.distanceTo(ca), 1e-3f)
    val rb = max(b.distanceTo(cb), 1e-3f)
    val ta = atan2(a.y - ca.y, a.x - ca.x)
    val tb = atan2(b.y - cb.y, b.x - cb.x)
    // 走最短弧：不加这一步，跨 ±π 的顶点会绕一整圈（看上去像整体翻卷）
    var delta = tb - ta
    while (delta > Math.PI) delta -= (2 * Math.PI).toFloat()
    while (delta < -Math.PI) delta += (2 * Math.PI).toFloat()
    val angle = ta + delta * t
    val radius = exp(ln(ra) + (ln(rb) - ln(ra)) * t)
    return Pt(c.x + radius * cos(angle), c.y + radius * sin(angle))
}

/** 取两条点列的最小外接框高度 —— 验收里判「中间帧是不是塌成一条线」用。 */
internal fun bboxHeight(points: List<Pt>): Float {
    var lo = Float.MAX_VALUE
    var hi = -Float.MAX_VALUE
    for (p in points) {
        lo = min(lo, p.y)
        hi = max(hi, p.y)
    }
    return if (lo > hi) 0f else hi - lo
}
