package com.branchbase.ui.profile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「本地仓库」快捷入口的出现条件（2026-11-27 决策二）。
 *
 * 三条产品口径合成一个判据 [showLocalRepoShortcut]。钉在这里是因为它是**产品判断**、
 * 不是视觉参数：改坏了的表现是「说要本地管理，却找不到仓库入口」，
 * 或者反过来「点进去只有一条空页」。
 *
 * | 场景 | 期望 |
 * |---|---|
 * | 没开本地管理、盘上也没有仓库 | 不出现（①） |
 * | 本地管理开着 | 出现，哪怕还没拉过仓库（②） |
 * | 关掉了本地管理、但仓库还在 | 仍然出现（③：关模式不删仓库） |
 */
class LocalRepoShortcutTest {

    @Test
    fun `没开本地管理且没有仓库时不出现`() {
        assertFalse(showLocalRepoShortcut(null, 0))
        assertFalse(showLocalRepoShortcut(CommitMode.SINGLE_FILE, 0))
        assertFalse(showLocalRepoShortcut(CommitMode.MULTI_FILE, 0))
    }

    @Test
    fun `本地管理开着就出现_哪怕还没拉过仓库`() {
        // ②：入口本身就是「去拉一份」的路，空列表页里有＋拉取仓库
        assertTrue(showLocalRepoShortcut(CommitMode.LOCAL_REPO, 0))
        assertTrue(showLocalRepoShortcut(CommitMode.LOCAL_REPO, 3))
    }

    @Test
    fun `关掉本地管理但仓库还在时仍然出现`() {
        // ③：关模式不删仓库；不给入口等于把用户已经拉下来的副本藏起来
        assertTrue(showLocalRepoShortcut(CommitMode.SINGLE_FILE, 1))
        assertTrue(showLocalRepoShortcut(CommitMode.MULTI_FILE, 2))
        assertTrue(showLocalRepoShortcut(null, 1))
    }
}
