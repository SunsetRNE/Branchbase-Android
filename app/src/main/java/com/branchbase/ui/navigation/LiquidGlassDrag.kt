package com.branchbase.ui.navigation

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastFirstOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 液态玻璃栏的**运动物理层** —— 从上游
 * [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) 的 catalog 示例
 * （`utils/DampedDragAnimation.kt`、`utils/DragGestureInspector.kt`、`utils/InteractiveHighlight.kt`）
 * 移植过来，**固定在上游 `65ab177`（tag `2.0.1`）那一版**。
 *
 * ## 为什么这一段要抄进来而不是调库
 *
 * 上游发布到 Maven 的只有 `io.github.kyant0:backdrop`（采样 + 效果），
 * `LiquidBottomTabs` / `LiquidBottomTab` / 这三个 utils **只存在于它的 catalog 示例工程里**，
 * 没有发布物。而「液态」的一半是材质（库给的），另一半是**运动**（这里给的）：
 * 按下时整条栏拉伸、滑块被速度压扁、松手回弹、拖动时面板整体位移 —— 少任何一样，
 * 它就只是一个会平移的圆，不是液态玻璃。
 *
 * ## 与原版的差异（只有一处，且是被动降级）
 *
 * 上游 `InteractiveHighlight` 用 `com.kyant.backdrop.RuntimeShader` 写了一段 AGSL
 * 画指针位置的高光团。**`backdrop` 1.0.6（本项目锁定的版本）没有导出 `RuntimeShader` /
 * `asComposeShader` / `isRuntimeShaderSupported`**（javap 实测：只有 `RuntimeShaderCache`、
 * `ShadersKt`），所以这里改用 Compose 自带的 `Brush.radialGradient` + `BlendMode.Plus`
 * 复现同一件事：以指针为圆心、`minDimension * 1.5` 为半径的加色高光。
 * 观感差异只在「衰减曲线」上（AGSL 版是 `smoothstep`，这版是线性的）——
 * 位置、半径、颜色、加色混合、按下/抬手同步都与原版一致。
 *
 * ## 不做的事
 *
 * 不引 `kotlin.time.Clock`（上游用它给速度跟踪取时间戳）：这层只服务 Android，
 * 直接用 [SystemClock.uptimeMillis]，省掉一个实验性 API 的 opt-in。
 */

/** 等一帧。上游在 `utils/Coroutines.kt` 里用 `expect/actual` 声明，这里就是它的 android 实现。 */
internal suspend fun awaitFrame() {
    withFrameNanos { }
}

/**
 * 拖动识别器：**按下即开始、抬手即结束**，不做 `touchSlop` 阈值等待。
 *
 * 为什么不用框架的 `detectDragGestures`：那个要等 `touchSlop` 才回调 `onDragStart`，
 * 于是「按下」与「开始拖」之间有一段空白 —— 而液态栏要的正是**按下的那一刻**就开始形变
 * （`press()` 拉长、面板位移），等 slop 过去再动就慢了半拍。
 *
 * 移植自上游 `utils/DragGestureInspector.kt`，逐行同构。
 */
internal suspend fun PointerInputScope.inspectDragGestures(
    onDragStart: (down: PointerInputChange) -> Unit = {},
    onDragEnd: (change: PointerInputChange) -> Unit = {},
    onDragCancel: () -> Unit = {},
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
) {
    awaitEachGesture {
        val initialDown = awaitFirstDown(false, PointerEventPass.Initial)

        val down = awaitFirstDown(false)
        val drag = initialDown

        onDragStart(down)
        onDrag(drag, Offset.Zero)
        val upEvent =
            drag(
                pointerId = drag.id,
                onDrag = { onDrag(it, it.positionChange()) },
            )
        if (upEvent == null) {
            onDragCancel()
        } else {
            onDragEnd(upEvent)
        }
    }
}

private suspend inline fun AwaitPointerEventScope.drag(
    pointerId: PointerId,
    onDrag: (PointerInputChange) -> Unit,
): PointerInputChange? {
    val isPointerUp = currentEvent.changes.fastFirstOrNull { it.id == pointerId }?.pressed != true
    if (isPointerUp) {
        return null
    }
    var pointer = pointerId
    while (true) {
        val change = awaitDragOrUp(pointer) ?: return null
        if (change.isConsumed) {
            return null
        }
        if (change.changedToUpIgnoreConsumed()) {
            return change
        }
        onDrag(change)
        pointer = change.id
    }
}

private suspend inline fun AwaitPointerEventScope.awaitDragOrUp(
    pointerId: PointerId,
): PointerInputChange? {
    var pointer = pointerId
    while (true) {
        val event = awaitPointerEvent()
        val dragEvent = event.changes.fastFirstOrNull { it.id == pointer } ?: return null
        if (dragEvent.changedToUpIgnoreConsumed()) {
            val otherDown = event.changes.fastFirstOrNull { it.pressed }
            if (otherDown == null) {
                return dragEvent
            } else {
                pointer = otherDown.id
            }
        } else {
            val hasDragged = dragEvent.previousPosition != dragEvent.position
            if (hasDragged) {
                return dragEvent
            }
        }
    }
}

/**
 * 滑块（液态透镜）的运动状态机 —— 上游 `utils/DampedDragAnimation.kt` 的移植。
 *
 * 它有**四个独立弹簧**，各管一件事，谁都替代不了谁：
 *
 * | 弹簧 | 管什么 | 少了会怎样 |
 * |---|---|---|
 * | `valueAnimation` | 滑块停在哪个槽位 | 点一下硬切过去，没有滑动 |
 * | `pressProgressAnimation` | 按下进度（折射 / 高光 / 阴影都乘它） | 按下去栏面纹丝不动 |
 * | `scaleX/YAnimation` | 按下时的拉伸（X/Y 阻尼比不同） | 形变是各向同性的缩放，像贴纸 |
 * | `velocityAnimation` | 拖动速度 | 甩动时滑块不会先压扁再归位 |
 *
 * `Animatable` 的 target 与 value 是两回事：`updateValue` 只改目标（拖动途中一直在改），
 * `animateToValue` 走 [MutatorMutex] 独占地把目标推到位 —— 后者专给「点击切换」用，
 * 免得点选与拖动两个源同时往同一个弹簧里写。
 */
internal class DampedDragAnimation(
    private val animationScope: CoroutineScope,
    val initialValue: Float,
    val valueRange: ClosedRange<Float>,
    val visibilityThreshold: Float,
    val initialScale: Float,
    val pressedScale: Float,
    val onDragStarted: DampedDragAnimation.(position: Offset) -> Unit,
    val onDragStopped: DampedDragAnimation.() -> Unit,
    val onDrag: DampedDragAnimation.(size: IntSize, dragAmount: Offset) -> Unit,
) {

    private val valueAnimationSpec = spring(1f, 1000f, visibilityThreshold)
    private val velocityAnimationSpec = spring(0.5f, 300f, visibilityThreshold * 10f)
    private val pressProgressAnimationSpec = spring(1f, 1000f, 0.001f)
    private val scaleXAnimationSpec = spring(GlassBackdrop.ScaleXDampingRatio, 250f, 0.001f)
    private val scaleYAnimationSpec = spring(GlassBackdrop.ScaleYDampingRatio, 250f, 0.001f)

    private val valueAnimation = Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation = Animatable(0f, 5f)
    private val pressProgressAnimation = Animatable(0f, 0.001f)
    private val scaleXAnimation = Animatable(initialScale, 0.001f)
    private val scaleYAnimation = Animatable(initialScale, 0.001f)

    private val mutatorMutex = MutatorMutex()

    private val velocityTracker = VelocityTracker()

    val value: Float get() = valueAnimation.value
    val progress: Float get() = (value - valueRange.start) / (valueRange.endInclusive - valueRange.start)
    val targetValue: Float get() = valueAnimation.targetValue
    val pressProgress: Float get() = pressProgressAnimation.value
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value
    val velocity: Float get() = velocityAnimation.value

    val modifier: Modifier = Modifier.pointerInput(Unit) {
        inspectDragGestures(
            onDragStart = { down ->
                onDragStarted(down.position)
                press()
            },
            onDragEnd = {
                onDragStopped()
                release()
            },
            onDragCancel = {
                onDragStopped()
                release()
            },
        ) { change, dragAmount ->
            onDrag(size, dragAmount)
        }
    }

    fun press() {
        velocityTracker.resetTracking()
        animationScope.launch {
            launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(pressedScale, scaleYAnimationSpec) }
        }
    }

    fun release() {
        animationScope.launch {
            awaitFrame()
            if (value != targetValue) {
                // 滑块还在滑：等它滑到目标附近再收形变，否则「抬手」与「到站」两段动画会打架，
                // 观感是滑块一边跑一边缩，像被线拽着。
                val threshold = (valueRange.endInclusive - valueRange.start) * 0.025f
                snapshotFlowOf(valueAnimation)
                    .filter { abs(it - valueAnimation.targetValue) < threshold }
                    .first()
            }
            launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(initialScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(initialScale, scaleYAnimationSpec) }
        }
    }

    fun updateValue(value: Float) {
        val targetValue = value.coerceIn(valueRange)
        animationScope.launch {
            launch { valueAnimation.animateTo(targetValue, valueAnimationSpec) { updateVelocity() } }
        }
    }

    fun animateToValue(value: Float) {
        animationScope.launch {
            mutatorMutex.mutate {
                press()
                val targetValue = value.coerceIn(valueRange)
                launch { valueAnimation.animateTo(targetValue, valueAnimationSpec) }
                if (velocity != 0f) {
                    launch { velocityAnimation.animateTo(0f, velocityAnimationSpec) }
                }
                release()
            }
        }
    }

    private fun updateVelocity() {
        velocityTracker.addPosition(
            SystemClock.uptimeMillis(),
            Offset(value, 0f),
        )
        val targetVelocity =
            velocityTracker.calculateVelocity().x / (valueRange.endInclusive - valueRange.start)
        animationScope.launch { velocityAnimation.animateTo(targetVelocity, velocityAnimationSpec) }
    }
}

/**
 * `snapshotFlow { animatable.value }` 的简写 —— 只为让 [DampedDragAnimation.release] 保持可读。
 * 上游在这里直接写 `snapshotFlow { valueAnimation.value }`，语义一字不差。
 */
private fun snapshotFlowOf(animation: Animatable<Float, *>) =
    androidx.compose.runtime.snapshotFlow { animation.value }

/**
 * 指针高光：按住时在**手指那一处**加一团白光，并随手指移动。
 *
 * 上游用 AGSL `RuntimeShader` 画（`smoothstep` 衰减）；这里见文件头的说明，改用
 * `Brush.radialGradient` + `BlendMode.Plus` —— 同一件事的 Compose 原生写法。
 *
 * 两个修饰符分工不同，**不能合并**：
 * - [modifier] 挂在**被照亮的表面**上（栏 / 录制层），负责画；
 * - [gestureModifier] 挂在**接收手势**的节点上（滑块），负责追踪手指位置。
 *   画在手势节点上的话，滑块一移动高光就跟着自己跑，位置全错。
 */
internal class InteractiveHighlight(
    val animationScope: CoroutineScope,
    val position: (size: Size, offset: Offset) -> Offset = { _, offset -> offset },
) {

    private val pressProgressAnimationSpec = spring(0.5f, 300f, 0.001f)
    private val positionAnimationSpec = spring(0.5f, 300f, Offset.VisibilityThreshold)

    private val pressProgressAnimation = Animatable(0f, 0.001f)
    private val positionAnimation =
        Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)

    private var startPosition = Offset.Zero
    val pressProgress: Float get() = pressProgressAnimation.value
    val offset: Offset get() = positionAnimation.value - startPosition

    val modifier: Modifier =
        Modifier.drawWithContent {
            val progress = pressProgressAnimation.value
            if (progress > 0f) {
                val center = position(size, positionAnimation.value)
                drawRect(
                    Color.White.copy(alpha = GlassBackdrop.HighlightFillAlpha * progress),
                    blendMode = BlendMode.Plus,
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = GlassBackdrop.HighlightSpotAlpha * progress),
                            Color.Transparent,
                        ),
                        center = Offset(
                            center.x.fastCoerceIn(0f, size.width),
                            center.y.fastCoerceIn(0f, size.height),
                        ),
                        radius = size.minDimension * GlassBackdrop.HighlightSpotRadiusFactor,
                    ),
                    blendMode = BlendMode.Plus,
                )
            }

            drawContent()
        }

    val gestureModifier: Modifier =
        Modifier.pointerInput(animationScope) {
            inspectDragGestures(
                onDragStart = { down ->
                    startPosition = down.position
                    animationScope.launch {
                        launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
                        launch { positionAnimation.snapTo(startPosition) }
                    }
                },
                onDragEnd = {
                    animationScope.launch {
                        launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
                        launch { positionAnimation.animateTo(startPosition, positionAnimationSpec) }
                    }
                },
                onDragCancel = {
                    animationScope.launch {
                        launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
                        launch { positionAnimation.animateTo(startPosition, positionAnimationSpec) }
                    }
                },
            ) { change, _ ->
                animationScope.launch { positionAnimation.snapTo(change.position) }
            }
        }
}
