package com.branchbase.ui.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val metrics = AdaptiveLayoutPolicy.metrics(plan.nav, itemCount = 2, wantsLabels = false)

        assertEquals(200f, metrics.barWidth.value, 0.01f)
        assertEquals(96f, metrics.cellWidth.value, 0.01f)
        assertEquals(1.09f, metrics.compression, 0.01f)
        assertTrue(metrics.barWidth <= plan.nav.maxWidth)
        assertFalse(metrics.showLabels)
    }

    @Test
    fun `compact phone keeps six tabs inside screen and drops labels`() {
        val plan = AdaptiveLayoutPolicy.plan(window(360))
        val metrics = AdaptiveLayoutPolicy.metrics(plan.nav, itemCount = 6, wantsLabels = true)

        // 槽位多到 72dp 以下：仍保整条栏可用，但隐藏文字、图标降到下限。
        assertTrue(metrics.cellWidth < 72.dp)
        assertFalse(metrics.showLabels)
        assertEquals(plan.nav.maxWidth, metrics.barWidth)
        assertEquals(18f, metrics.iconSize.value, 0.01f)
        assertTrue(metrics.compression in 0.60f..1.25f)
    }

    @Test
    fun `tablet caps bar width and keeps cells from stretching`() {
        val plan = AdaptiveLayoutPolicy.plan(window(1280))
        val twoTabs = AdaptiveLayoutPolicy.metrics(plan.nav, itemCount = 2, wantsLabels = false)
        val sixTabs = AdaptiveLayoutPolicy.metrics(plan.nav, itemCount = 6, wantsLabels = true)

        assertTrue(twoTabs.barWidth <= plan.nav.maxWidth)
        assertEquals(248f, twoTabs.barWidth.value, 0.01f)
        // 1280dp 平板上 6 槽位最多只能用 560dp，而不是整宽等分。
        assertEquals(560f, sixTabs.barWidth.value, 0.01f)
        assertEquals(92f, sixTabs.cellWidth.value, 0.01f)
        assertTrue(sixTabs.showLabels)
        assertTrue(sixTabs.iconSize <= 26.dp)
    }

    @Test
    fun `medium window uses capped bar and tablet content width`() {
        val plan = AdaptiveLayoutPolicy.plan(window(600))
        val metrics = AdaptiveLayoutPolicy.metrics(plan.nav, itemCount = 4, wantsLabels = true)

        assertEquals(560f, plan.nav.maxWidth.value, 0.01f)
        assertEquals(488f, metrics.barWidth.value, 0.01f)
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
        assertEquals(64f, portrait.nav.height.value, 0.01f)
    }

    @Test
    fun `phone landscape shrinks the floating bar`() {
        val plan = AdaptiveLayoutPolicy.plan(WindowSpec(800, 360, 2f, 1f))

        // 宽度是 Medium（600–839），高度紧凑：栏高/滑块/上下留白一起收窄。
        assertEquals(56f, plan.nav.height.value, 0.01f)
        assertEquals(48f, plan.nav.pillHeight.value, 0.01f)
        assertEquals(4f, plan.nav.verticalSlot.value, 0.01f)
        assertEquals(720f, plan.content.maxWidth?.value ?: 0f, 0.01f)
    }
}
