package com.branchbase.ui.navigation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun `顶层按返回是退出应用_而不是回登录首页`() {
        // 需求变更（用户反馈）：会话还在，回登录首页是与真实登录状态不符的死状态
        // （重启应用又直接回主界面）。顶层改为「再按一次退出应用」，
        // 见 TopLevelBack.kt 与 TopLevelBackTest。
        assertEquals(BackDisposition.ExitApp, backDisposition(0))
    }

    @Test
    fun `退场中的旧页必须放手_否则会吃掉紧接着的第二次返回键`() {
        // 现场：主界面顶层按返回触发顶层动作（外层开始播动画），
        // 用户在动画没播完时再按一次 —— 那一下曾被退场中的 MainScreen 吃掉，
        // 表现成「按了没反应，得再按一次」（双击退出直接失灵）。
        assertTrue(shouldHandleBack(enabled = true, pageActive = true))
        assertFalse("退场中的旧页不能抢返回键", shouldHandleBack(enabled = true, pageActive = false))
        // 自身条件不满足时，无论是否当前页都不抢
        assertFalse(shouldHandleBack(enabled = false, pageActive = true))
        assertFalse(shouldHandleBack(enabled = false, pageActive = false))
    }

    @Test
    fun `子页按返回仍然是先关页面`() {
        assertEquals(BackDisposition.ClosePage, backDisposition(1))
        assertEquals(BackDisposition.ClosePage, backDisposition(2))
        assertEquals(BackDisposition.ClosePage, backDisposition(3))
    }

    @Test
    fun `只有当前状态那一格算当前页`() {
        // 目标页 true、退场中的旧页 false —— 这是「退场旧页放手」的唯一判据
        assertTrue(pageIsCurrent("A", "A"))
        assertFalse("退场中的旧页不能算当前页", pageIsCurrent("A", "B"))
        assertFalse(pageIsCurrent(null, "A"))
        assertTrue(pageIsCurrent(null, null))
    }

    @Test
    fun `两个切换器都必须下发 LocalPageActive`() {
        // 回归钉子：PageSwitcher 曾经漏了下发（只有 TabSwitcher 有），于是文档 / 提交信息 /
        // shouldHandleBack 单测都写着「退场中的旧页放手」，但走 PageSwitcher 的页面
        // （主界面路由 / 仓库页十几个子页 / 个人页子页 / 登录流程步骤）拿到的恒为 true ——
        // 机制在最常用的那条路径上没生效。谁再把其中一个改回 `content = content`，这里立刻红。
        val file = File("src/main/java/com/branchbase/ui/navigation/PageTransitions.kt")
        assertTrue("找不到 PageTransitions.kt：${file.absolutePath}", file.exists())
        val source = file.readText()
        val dispatched = Regex("LocalPageActive provides pageIsCurrent\\(target, state\\)")
            .findAll(source).count()
        assertEquals("PageSwitcher 与 TabSwitcher 都要下发「是不是当前页」", 2, dispatched)
    }
}
