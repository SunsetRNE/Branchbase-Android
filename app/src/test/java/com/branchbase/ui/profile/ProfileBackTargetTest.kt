package com.branchbase.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 个人页返回键目标单测。
 *
 * 缺陷：在「设置 → 关于」页按系统返回键，直接跳回了个人主页 ——
 * 而页面左上角的返回箭头是回「设置」。同一个返回意图，两条路径给出两个结果。
 *
 * 根因是返回键写死了 `subPage = null`，没有区分「一级子页」与「设置的下级页」。
 * 这里把目标钉住：**层级 ≥ 2 的下级页先回设置，一级子页才回主页**。
 */
class ProfileBackTargetTest {

    @Test
    fun `设置的下级页按返回回设置页`() {
        listOf(
            SubPage.LocalRepo, SubPage.About, SubPage.Log,
            SubPage.NotificationSettings, SubPage.Translate, SubPage.Accounts, SubPage.CommitMode,
        ).forEach { page ->
            assertEquals("$page 的下级页应回设置页", SubPage.Settings, profileBackTarget(page))
        }
    }

    @Test
    fun `一级子页按返回回个人主页`() {
        listOf(
            SubPage.Stars, SubPage.Projects, SubPage.Tasks, SubPage.EditProfile, SubPage.Settings,
        ).forEach { page ->
            assertNull("$page 是个人页的一级子页，应回主页", profileBackTarget(page))
        }
    }

    @Test
    fun `没有子页时不消费`() {
        assertNull(profileBackTarget(null))
    }

    @Test
    fun `层级只分两档_且与返回目标同源`() {
        // 动效方向（PageSwitcher 按层级差判定）与返回键目标必须从同一个函数取值，
        // 否则两处各写一份 when，改一处漏一处。
        SubPage.entries.forEach { page ->
            val depth = subPageDepth(page)
            if (depth >= 2) {
                assertEquals("层级 $depth 的页面必须有个能回去的上一层", SubPage.Settings, profileBackTarget(page))
            } else {
                assertNull("一级子页的上一层是个人主页（null）", profileBackTarget(page))
            }
        }
    }
}
