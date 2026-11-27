package com.branchbase.ui.search

import com.branchbase.ui.theme.TintRole

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 搜索结果的「类型 → 语义色」映射。
 *
 * 这个映射同时喂给左侧图标牌与顶部计数条：两处必须是同一个颜色，否则「绿线 + 蓝牌」
 * 读起来像两件不相干的东西；而且要与通知列表里同一类对象的角色一致
 * （issue = `SUCCESS` 绿、PR = `DONE` 紫、commit = `NEUTRAL`），
 * 否则同一个 issue 在通知页与搜索页会是两种颜色。
 *
 * 它是一张纯表，改错不会有编译错误、只会在真机上「颜色不对」——正是该用单测钉住的那类东西。
 */
class SearchTypeRoleTest {

    @Test
    fun `议题绿_PRP紫_提交中性_与通知列表同源`() {
        assertEquals(TintRole.SUCCESS, searchTypeRole(SearchType.ISSUES))
        assertEquals(TintRole.DONE, searchTypeRole(SearchType.PULLS))
        assertEquals(TintRole.NEUTRAL, searchTypeRole(SearchType.COMMITS))
    }

    @Test
    fun `主干类型统一主色蓝`() {
        listOf(SearchType.REPOS, SearchType.CODE, SearchType.USERS, SearchType.TOPICS).forEach { type ->
            assertEquals(type.name, TintRole.ACCENT, searchTypeRole(type))
        }
    }
}
