package com.branchbase.imageviewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片查看器的手势边界单测。
 *
 * 这些值只靠手指在真机上划是测不出来的：放大到上限后还能不能继续放大、
 * 图片被拖到屏幕外、放大状态下误判成「下拉关闭」，都要靠这里钉住。
 */
class ImageViewerMathTest {

    @Test
    fun `缩放钳制在 1 倍到 8 倍之间`() {
        assertEquals(1f, ImageViewerMath.clampScale(0.3f), 1e-6f)
        assertEquals(8f, ImageViewerMath.clampScale(42f), 1e-6f)
        assertEquals(2f, ImageViewerMath.clampScale(2f), 1e-6f)
    }

    @Test
    fun `双击在 1 倍与 2_5 倍之间切换`() {
        assertEquals(2.5f, ImageViewerMath.doubleTapTarget(1f), 1e-6f)
        // 手势缩放几乎不会正好落在 1.0：容差内必须视为「当前是 1 倍」，否则双击放大不回来
        assertEquals(2.5f, ImageViewerMath.doubleTapTarget(1.02f), 1e-6f)
        assertEquals(1f, ImageViewerMath.doubleTapTarget(2.5f), 1e-6f)
        assertEquals(1f, ImageViewerMath.doubleTapTarget(1.4f), 1e-6f)
    }

    @Test
    fun `fit 尺寸按较小的那个比例缩放`() {
        // 宽图：受容器宽限制
        val (w1, h1) = ImageViewerMath.fitSize(2000f, 1000f, 1000f, 800f)
        assertEquals(1000f, w1, 1e-3f)
        assertEquals(500f, h1, 1e-3f)
        // 长图：受容器高限制
        val (w2, h2) = ImageViewerMath.fitSize(1000f, 2000f, 1000f, 800f)
        assertEquals(400f, w2, 1e-3f)
        assertEquals(800f, h2, 1e-3f)
    }

    @Test
    fun `尺寸未知时不产生 NaN 且禁止平移`() {
        assertEquals(0f to 0f, ImageViewerMath.fitSize(0f, 10f, 100f, 100f))
        assertEquals(0f to 0f, ImageViewerMath.fitSize(10f, 10f, 0f, 100f))
        assertEquals(0f, ImageViewerMath.maxOverflow(100f, 0f, 3f), 1e-6f)
        assertEquals(0f, ImageViewerMath.maxOverflow(0f, 100f, 3f), 1e-6f)
        assertFalse(ImageViewerMath.maxOverflow(100f, 100f, 1f).isNaN())
    }

    @Test
    fun `未放大时不许平移`() {
        assertEquals(0f, ImageViewerMath.maxOverflow(1000f, 400f, 1f), 1e-6f)
        assertEquals(0f, ImageViewerMath.clampTranslation(50f, 0f), 1e-6f)
        assertEquals(0f, ImageViewerMath.clampTranslation(-50f, 0f), 1e-6f)
    }

    @Test
    fun `放大后可平移但被限制在图片边缘`() {
        // fitted=800、容器=1000，2 倍：800*2-1000=600 → 每边 300
        assertEquals(300f, ImageViewerMath.maxOverflow(1000f, 800f, 2f), 1e-6f)
        assertEquals(300f, ImageViewerMath.clampTranslation(999f, 300f), 1e-6f)
        assertEquals(-300f, ImageViewerMath.clampTranslation(-999f, 300f), 1e-6f)
        assertEquals(120f, ImageViewerMath.clampTranslation(120f, 300f), 1e-6f)
        // 放大后仍未超出容器（长边受限的那一轴）→ 该轴不许平移
        assertEquals(0f, ImageViewerMath.maxOverflow(1000f, 400f, 2f), 1e-6f)
    }

    @Test
    fun `下拉关闭只在未放大时生效`() {
        assertFalse(ImageViewerMath.shouldDismissByDrag(50f, 1f, 1000f)) // 阈值 18% = 180
        assertTrue(ImageViewerMath.shouldDismissByDrag(200f, 1f, 1000f))
        assertFalse("放大状态下的下移是正常平移", ImageViewerMath.shouldDismissByDrag(400f, 2.5f, 1000f))
        assertFalse("容器高度未知时不关闭", ImageViewerMath.shouldDismissByDrag(400f, 1f, 0f))
        assertFalse("向上拖不关闭", ImageViewerMath.shouldDismissByDrag(-400f, 1f, 1000f))
    }

    @Test
    fun `下拉进度用于背景淡化`() {
        assertEquals(0f, ImageViewerMath.dismissProgress(0f, 1000f), 1e-6f)
        // 180 / (1000 * 0.18 * 2) = 0.5
        assertEquals(0.5f, ImageViewerMath.dismissProgress(180f, 1000f), 1e-3f)
        assertEquals(1f, ImageViewerMath.dismissProgress(9999f, 1000f), 1e-6f)
        assertEquals(0f, ImageViewerMath.dismissProgress(100f, 0f), 1e-6f)
    }

    @Test
    fun `倍率容差判断`() {
        assertTrue(ImageViewerMath.isAtUnitScale(1f))
        assertTrue(ImageViewerMath.isAtUnitScale(1.03f))
        assertFalse(ImageViewerMath.isAtUnitScale(1.2f))
    }
}
