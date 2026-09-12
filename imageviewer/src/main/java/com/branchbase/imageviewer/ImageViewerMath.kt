package com.branchbase.imageviewer

/**
 * 图片查看器的**纯计算**部分：缩放钳制 / 双击目标 / 平移边界 / 下拉关闭判定。
 *
 * 抽出来的原因与 [com.branchbase.translate] 的分片逻辑一样：手势回调里全是浮点与边界条件，
 * 「能放到多大」「把图拖出屏幕外」「图放大时误判成下拉关闭」这些必须能被 JVM 单测钉住 ——
 * 只靠手指在真机上划，回归时没人能复现。
 *
 * 全部用 Float 参数（不引 Compose 类型），因此单测不需要 Android 环境。
 */
object ImageViewerMath {

    /** 最小缩放 = 1：不缩到比「适应屏幕」更小（再小只会让图片缩在屏幕中间）。 */
    const val MIN_SCALE = 1f

    /** 最大缩放：手机上放大看截图里的文字，8 倍足够。 */
    const val MAX_SCALE = 8f

    /** 双击在 1x 与这个倍数之间切换。 */
    const val DOUBLE_TAP_SCALE = 2.5f

    /**
     * 「当前是否处于 1x」的容差。
     *
     * 手势缩放出来的值几乎不会正好是 1（1.0000001 / 0.9999），
     * 直接比大小会让「双击放大后双击缩不回去」「放大状态误触发下拉关闭」。
     */
    const val UNIT_EPSILON = 0.05f

    /** 下拉关闭的触发比例：手指下移超过容器高度的 18% 才算「要关掉」。 */
    const val DISMISS_DRAG_RATIO = 0.18f

    fun clampScale(scale: Float): Float = scale.coerceIn(MIN_SCALE, MAX_SCALE)

    fun isAtUnitScale(scale: Float): Boolean = scale <= MIN_SCALE + UNIT_EPSILON

    /** 双击后的目标缩放：接近 1x 就放大，否则回到 1x。 */
    fun doubleTapTarget(currentScale: Float): Float =
        if (isAtUnitScale(currentScale)) DOUBLE_TAP_SCALE else MIN_SCALE

    /**
     * 按 `ContentScale.Fit` 缩放后的图片尺寸（与画出来的尺寸一致，平移边界依赖它）。
     *
     * 容器或图片尺寸非法（0 / 负 / 未知）时返回 0，调用方据此禁用平移，而不是算出 NaN。
     */
    fun fitSize(
        imageWidth: Float,
        imageHeight: Float,
        containerWidth: Float,
        containerHeight: Float,
    ): Pair<Float, Float> {
        if (imageWidth <= 0f || imageHeight <= 0f || containerWidth <= 0f || containerHeight <= 0f) return 0f to 0f
        val ratio = minOf(containerWidth / imageWidth, containerHeight / imageHeight)
        return (imageWidth * ratio) to (imageHeight * ratio)
    }

    /**
     * 单轴平移上限（对称，正负都是它）。
     *
     * 放大 s 倍后图片比容器宽 `fitted*s - container`，超出的部分一半可以往左、一半往右，
     * 所以上限是 `(fitted*s - container) / 2`；不超出就是 0（未放大时不许平移）。
     */
    fun maxOverflow(container: Float, fitted: Float, scale: Float): Float {
        if (container <= 0f || fitted <= 0f) return 0f
        return ((fitted * scale - container) / 2f).coerceAtLeast(0f)
    }

    fun clampTranslation(value: Float, maxOverflow: Float): Float {
        if (maxOverflow <= 0f) return 0f
        return value.coerceIn(-maxOverflow, maxOverflow)
    }

    /**
     * 是否应该因「下拉」而关闭。
     *
     * 只在**未放大**时生效：放大状态下手指下移是正常平移，误判会导致「看图看到一半被关掉」。
     */
    fun shouldDismissByDrag(dragY: Float, scale: Float, containerHeight: Float): Boolean {
        if (containerHeight <= 0f || !isAtUnitScale(scale)) return false
        return dragY > containerHeight * DISMISS_DRAG_RATIO
    }

    /** 下拉过程中的背景淡化程度（0 = 不透明，1 = 全透明），给用户「松手就关」的反馈。 */
    fun dismissProgress(dragY: Float, containerHeight: Float): Float {
        if (containerHeight <= 0f) return 0f
        return (dragY / (containerHeight * DISMISS_DRAG_RATIO * 2f)).coerceIn(0f, 1f)
    }
}
