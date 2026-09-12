package com.branchbase.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 页面切换方向单测。
 *
 * 方向是动效里最隐蔽的一类 bug：动画照常播放，只是前进/返回的滑动方向与用户的手指方向相反，
 * 看久了会觉得「哪里不对」但说不出是哪。这里把方向规则钉死。
 */
class PageTransitionsTest {

    @Test
    fun `层级增加是前进`() {
        assertEquals(1, pageDirection(initialDepth = 0, targetDepth = 1))
        assertEquals(1, pageDirection(initialDepth = 1, targetDepth = 3))
    }

    @Test
    fun `层级减少是返回`() {
        assertEquals(-1, pageDirection(initialDepth = 2, targetDepth = 1))
        assertEquals(-1, pageDirection(initialDepth = 1, targetDepth = 0))
    }

    @Test
    fun `同级不位移`() {
        // 仓库页里切 Tab、同级页面互跳都属于这一类：应当淡入淡出，而不是横滑
        assertEquals(0, pageDirection(initialDepth = 0, targetDepth = 0))
        assertEquals(0, pageDirection(initialDepth = 2, targetDepth = 2))
    }

    @Test
    fun `顶层按返回是回登录首页_而不是关页面或退出`() {
        // 需求：主 Tab 页按返回 → 回登录首页（不退出 App）；在登录首页再按一次才退出。
        // 登录首页（Idle）本身没有 BackHandler，交给系统默认行为 = 退出，所以这里只钉「顶层」这一档。
        assertEquals(BackDisposition.BackToWelcome, backDisposition(0))
    }

    @Test
    fun `子页按返回仍然是先关页面`() {
        assertEquals(BackDisposition.ClosePage, backDisposition(1))
        assertEquals(BackDisposition.ClosePage, backDisposition(2))
        assertEquals(BackDisposition.ClosePage, backDisposition(3))
    }
}
