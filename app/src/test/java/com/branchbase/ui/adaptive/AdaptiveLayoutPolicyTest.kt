package com.branchbase.ui.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.unit.dp

class AdaptiveLayoutPolicyTest {

    private fun window(widthDp: Int) = WindowSpec(
        widthDp = widthDp,
        heightDp = 800,
        density = 2f,
        fontScale = 1f,
    )

    @Test
    fun `compact phone caps two-tab bar by cell width`() {
        val plan = AdaptiveLayoutPolicy.plan(window(360))
        val metrics = AdaptiveLayoutPolicy.metrics(plan.nav, itemCount = 2)

        // 2 × 96dp 格 + 上下 3dp 内边距：图标收到屏幕中间，不再隔着一整块屏幕
        assertEquals(198f, metrics.barWidth.value, 0.01f)
        assertEquals(96f, metrics.cellWidth.value, 0.01f)
        assertEquals(1.09f, metrics.compression, 0.01f)
        assertTrue(metrics.barWidth <= plan.nav.maxWidth)
    }

    @Test
    fun `compact phone keeps six tabs inside screen`() {
        val plan = AdaptiveLayoutPolicy.plan(window(360))
        val metrics = AdaptiveLayoutPolicy.metrics(plan.nav, itemCount = 6)

        // 槽位多到 72dp 以下：仍保整条栏可用，图标降到下限（底栏已无文字可隐藏）。
        assertTrue(metrics.cellWidth < 72.dp)
        assertEquals(plan.nav.maxWidth, metrics.barWidth)
        assertEquals(18f, metrics.iconSize.value, 0.01f)
        assertTrue(metrics.compression in 0.60f..1.25f)
    }

    @Test
    fun `tablet caps bar width and keeps cells from stretching`() {
        val plan = AdaptiveLayoutPolicy.plan(window(1280))
        val twoTabs = AdaptiveLayoutPolicy.metrics(plan.nav, itemCount = 2)
        val sixTabs = AdaptiveLayoutPolicy.metrics(plan.nav, itemCount = 6)

        assertTrue(twoTabs.barWidth <= plan.nav.maxWidth)
        assertEquals(246f, twoTabs.barWidth.value, 0.01f)
        // 1280dp 平板上 6 槽位最多只能用 560dp，而不是整宽等分。
        assertEquals(560f, sixTabs.barWidth.value, 0.01f)
        assertEquals(92.33f, sixTabs.cellWidth.value, 0.01f)
        assertTrue(sixTabs.iconSize <= 26.dp)
    }

    @Test
    fun `medium window uses capped bar and tablet content width`() {
        val plan = AdaptiveLayoutPolicy.plan(window(600))
        val metrics = AdaptiveLayoutPolicy.metrics(plan.nav, itemCount = 4)

        assertEquals(560f, plan.nav.maxWidth.value, 0.01f)
        assertEquals(486f, metrics.barWidth.value, 0.01f)
        assertEquals(720f, plan.content.maxWidth?.value ?: 0f, 0.01f)
        assertEquals(24f, plan.content.horizontalPadding.value, 0.01f)
    }

    @Test
    fun `compact content is unbounded and tablet content is readable width`() {
        assertNull(AdaptiveLayoutPolicy.plan(window(360)).content.maxWidth)
        assertEquals(840f, AdaptiveLayoutPolicy.plan(window(1280)).content.maxWidth?.value ?: 0f, 0.01f)
        assertEquals(32f, AdaptiveLayoutPolicy.plan(window(1280)).content.horizontalPadding.value, 0.01f)
    }

    @Test
    fun `portrait tablet uses narrower content than landscape`() {
        val portrait = AdaptiveLayoutPolicy.plan(WindowSpec(900, 1200, 2f, 1f))
        val landscape = AdaptiveLayoutPolicy.plan(WindowSpec(1280, 800, 2f, 1f))

        assertEquals(760f, portrait.content.maxWidth?.value ?: 0f, 0.01f)
        assertEquals(840f, landscape.content.maxWidth?.value ?: 0f, 0.01f)
        assertEquals(48f, portrait.nav.height.value, 0.01f)
    }

    @Test
    fun `phone landscape keeps the 48dp bar and only tightens its gap`() {
        val plan = AdaptiveLayoutPolicy.plan(WindowSpec(800, 360, 2f, 1f))

        // 宽度是 Medium（600–839），高度紧凑：栏高不再单独收窄（已经是 48dp 档），
        // 只把上下留白从 8dp 收到 4dp，给正文腾高度。
        assertEquals(48f, plan.nav.height.value, 0.01f)
        assertEquals(42f, plan.nav.pillHeight.value, 0.01f)
        assertEquals(4f, plan.nav.verticalSlot.value, 0.01f)
        assertEquals(720f, plan.content.maxWidth?.value ?: 0f, 0.01f)
    }

    @Test
    fun `all bottom bars share the same height`() {
        // 2026-11-25 的整体比例：原生栏 80 → 48（-2/5），玻璃栏 64 → 48（-1/4），
        // 个人页 / 仓库页整宽栏 60 / 56 → 48 —— 四者同高，页面之间切栏不再跳。
        val phones = listOf(360, 600, 800, 1280).map { AdaptiveLayoutPolicy.plan(window(it)) }
        phones.forEach { plan ->
            assertEquals(48f, plan.nav.height.value, 0.01f)
            assertEquals(42f, plan.nav.pillHeight.value, 0.01f)
        }
    }
}
