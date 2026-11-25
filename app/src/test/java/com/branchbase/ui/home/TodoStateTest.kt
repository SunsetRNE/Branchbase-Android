package com.branchbase.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页「待处理」四态与折叠判定的单测。
 *
 * 这里钉的都是**用户能直接感知、但错了不会崩**的边界：
 * - 断网不能显示成「都处理完了」（那正是改造前「失败静默为 0」的毛病）；
 * - 三项都明确为 0 才算空 —— 拿 `null` 当 0 会让空态冒充失败，反过来也一样；
 * - 空态默认折叠，失败态**强制展开**（重试不能藏在折叠里）；
 * - 手动收起要记住（按账号），但失败态临时覆盖记忆。
 */
class TodoStateTest {

    @Test
    fun `加载优先于任何计数`() {
        assertEquals(TodoState.Loading, todoStateOf(loading = true, unread = 0, reviews = 0, assigned = 0))
        // 手上可能还有旧值：这一轮没结束就不该拿旧值下结论
        assertEquals(TodoState.Loading, todoStateOf(loading = true, unread = 3, reviews = null, assigned = 1))
    }

    @Test
    fun `三项都明确为 0 才是空`() {
        assertEquals(TodoState.Empty, todoStateOf(loading = false, unread = 0, reviews = 0, assigned = 0))
    }

    @Test
    fun `任一项大于 0 就是有内容`() {
        assertEquals(TodoState.Ready, todoStateOf(loading = false, unread = 1, reviews = 0, assigned = 0))
        assertEquals(TodoState.Ready, todoStateOf(loading = false, unread = 0, reviews = 2, assigned = 0))
        assertEquals(TodoState.Ready, todoStateOf(loading = false, unread = 0, reviews = 0, assigned = 3))
    }

    @Test
    fun `任一项未知且没有非零值时算失败`() {
        assertEquals(TodoState.Failed, todoStateOf(loading = false, unread = null, reviews = 0, assigned = 0))
        assertEquals(TodoState.Failed, todoStateOf(loading = false, unread = 0, reviews = null, assigned = null))
    }

    @Test
    fun `部分未知但已知项非零时仍是有内容`() {
        // 一路断网不该把整块变成「暂时取不到」；未知那行在界面上显示「—」
        assertEquals(TodoState.Ready, todoStateOf(loading = false, unread = null, reviews = 2, assigned = 0))
    }

    @Test
    fun `空态与加载中默认折叠`() {
        assertTrue(todoCollapsedByDefault(TodoState.Empty))
        // 首帧还不知道结果：先收起，否则空态用户每次启动都会「弹开一屏骨架再收拢」
        assertTrue(todoCollapsedByDefault(TodoState.Loading))
        assertFalse(todoCollapsedByDefault(TodoState.Ready))
        // 失败必须展开：重试是唯一的恢复入口
        assertFalse(todoCollapsedByDefault(TodoState.Failed))
    }

    @Test
    fun `记忆优先于状态默认`() {
        assertTrue(todoCollapsed(TodoState.Ready, remembered = true))
        assertFalse(todoCollapsed(TodoState.Empty, remembered = false))
    }

    @Test
    fun `失败态忽略记忆并强制展开`() {
        assertFalse(todoCollapsed(TodoState.Failed, remembered = true))
    }

    @Test
    fun `没有记忆时用状态默认`() {
        assertTrue(todoCollapsed(TodoState.Empty, remembered = null))
        assertTrue(todoCollapsed(TodoState.Loading, remembered = null))
        assertFalse(todoCollapsed(TodoState.Ready, remembered = null))
        assertFalse(todoCollapsed(TodoState.Failed, remembered = null))
    }

    @Test
    fun `用户把它留着展开时加载中不打断`() {
        // 记忆优先：用户手动展开过，这一轮取数就不该把它收起来
        assertFalse(todoCollapsed(TodoState.Loading, remembered = false))
        // 反过来，用户收起过，加载中也保持收起
        assertTrue(todoCollapsed(TodoState.Loading, remembered = true))
    }

    @Test
    fun `摘要只加已知项`() {
        // 未知按 0 计：摘要显示已知部分，不能反过来影响判空
        assertEquals(0, todoSummaryCount(null, null, null))
        assertEquals(3, todoSummaryCount(1, null, 2))
    }
}
