package com.branchbase.ui.theme.morph

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.branchbase.ui.theme.AnimatedStateIcon

/**
 * 形变的**渲染层**：把第 5、6 步的产物画出来。
 *
 * ## 为什么是绘制期而不是组合期
 *
 * 进度值通过 `progress: () -> Float`（**lambda**）传进来，并在 `Canvas` 的绘制作用域里读。
 * 这样每帧只失效**绘制**，不触发重组、不重新布局 —— 与 `ui/theme/Motion.kt` 里
 * `skeletonBlock` / `graphicsLayer` 那条约定的落法一致。
 * 反过来写（`MorphIcon(plan, progress: Float)` 传值）会让每帧重组一次整棵调用树。
 * 受控重载仍然保留（验收要把进度钉在 `t = 0.5` 上看），但它只该用在「不动的画面」上。
 *
 * ## 填充规则
 *
 * 输出按 **EvenOdd** 填充。原因是对应关系里绕向是一个自由变量（起点可以旋转、绕向可以翻转），
 * 而 NonZero 的填充结果**依赖绕向**：同一个图形的点集以相反方向遍历，NonZero 下可能从「挖洞」变成「填实」。
 * 图标里的洞（如放大镜的内圈）靠嵌套关系就能表达，EvenOdd 与绕向无关，是这里唯一自洽的选择。
 */
@Composable
fun MorphIcon(
    plan: MorphPlan,
    progress: () -> Float,
    tint: Color,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    interpolation: MorphInterpolation = MorphInterpolation.Linear,
) {
    val path = remember(plan) { Path() }
    Canvas(modifier = modifier.morphSemantics(contentDescription)) {
        val frame = plan.frame(progress(), interpolation)
        if (frame.isEmpty()) return@Canvas
        val scale = size.minDimension / MorphSampling.GRID
        val offsetX = (size.width - MorphSampling.GRID * scale) / 2f
        val offsetY = (size.height - MorphSampling.GRID * scale) / 2f
        path.rewind()
        for (subpath in frame) {
            val points = subpath.points
            for (i in points.indices) {
                val x = offsetX + points[i].x * scale
                val y = offsetY + points[i].y * scale
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            if (subpath.closed) path.close()
        }
        path.fillType = PathFillType.EvenOdd
        drawPath(path, tint)
    }
}

/**
 * **受控模式**：把进度钉死在某个值上（验收清单第 1 条「把中间帧钉住看」）。
 *
 * ```kotlin
 * // 审图用：0、0.5、1 各画一份并排看，比看动画准得多 ——
 * // 动画里 t=0.5 只存在 1/60 秒，肉眼根本抓不住
 * MorphIcon(MorphIcons.ChevronDownUp, progress = 0.5f, tint = …, modifier = Modifier.size(24.dp))
 * ```
 *
 * 配对不可形变时退化为「按 `progress` 择一显示」（不做交叉过渡 —— 受控模式是静态审图用的，
 * 不需要动画语义）。
 */
@Composable
fun MorphIcon(
    pair: MorphPair,
    progress: Float,
    tint: Color,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    interpolation: MorphInterpolation = MorphInterpolation.Linear,
) {
    if (pair.plan.morphable) {
        MorphIcon(pair.plan, { progress }, tint, contentDescription, modifier, interpolation)
    } else {
        Icon(
            imageVector = if (progress >= 0.5f) pair.to else pair.from,
            contentDescription = contentDescription,
            tint = tint,
            modifier = modifier,
        )
    }
}

/**
 * **弹簧驱动**：状态切换时把进度从 0 弹到 1（或反向），中间帧由顶点插值算出来。
 *
 * 用法与 `Icon(if (open) A else B)` 一样，只是把硬切换成了形变：
 * ```
 * AnimatedMorphIcon(
 *     pair = MorphIcons.ChevronDownUp,
 *     target = menuOpen,
 *     tint = Primer.IconSecondary,
 *     modifier = Modifier.size(18.dp),
 * )
 * ```
 *
 * ## 不可形变的配对怎么办
 *
 * **就地降级成既有的交叉过渡**（`AnimatedStateIcon`：200ms 交叉淡入 + 缩放），
 * 不报警、不崩、也不硬上 —— 「结构差异大的配对不做形变」是规格的一部分，
 * 清单写在 [MorphIcons] 里，由 `MorphPairTest` 钉住。
 *
 * ## 打断
 *
 * 进度走 `Animatable`：目标值一变，上一段动画被取消，新的从**当前值和当前速度**续着走。
 * 所以快速来回点击不会「跳回起点重播」，也不会残留速度导致跳变（验收清单第 5 条）。
 * 注意 `LaunchedEffect(target)` 的 key 只有 `target`：把 `spring` 也塞进 key
 * 会让「换预设」意外重播一次动画。
 */
@Composable
fun AnimatedMorphIcon(
    pair: MorphPair,
    target: Boolean,
    tint: Color,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    spring: MorphSpring = MorphSpring.Smooth,
    interpolation: MorphInterpolation = MorphInterpolation.Linear,
) {
    val plan = pair.plan
    if (!plan.morphable) {
        AnimatedStateIcon(
            icon = if (target) pair.to else pair.from,
            contentDescription = contentDescription,
            tint = tint,
            modifier = modifier,
        )
        return
    }
    val progress = remember(plan) { Animatable(if (target) 1f else 0f) }
    LaunchedEffect(target) {
        progress.animateTo(if (target) 1f else 0f, animationSpec = spring.spec())
    }
    MorphIcon(
        plan = plan,
        progress = { progress.value },
        tint = tint,
        contentDescription = contentDescription,
        modifier = modifier,
        interpolation = interpolation,
    )
}

/** 只在有描述时挂语义节点：`contentDescription = null` 表示「纯装饰」，不该进无障碍树。 */
private fun Modifier.morphSemantics(description: String?): Modifier =
    if (description == null) this else semantics { contentDescription = description }
