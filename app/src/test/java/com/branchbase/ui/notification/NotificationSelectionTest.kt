package com.branchbase.ui.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多选集合的纯逻辑单测（[effectiveSelection] / [isAllVisibleSelected] / [toggledAllSelection] /
 * [groupSelectedState]）。
 *
 * 背景（审计出的缺陷）：选中集合与可见集合会各自变化 —— 换分类 / 类型 / 时间范围、下拉刷新、
 * 「完成」归档都会换掉一批可见条目。旧实现直接用 `selectedIds.size >= visible.size` 判断
 * 「是否已全选」，并用原始 `selectedIds` 执行批量操作，于是在这些边界上：
 * - 选中集合混进看不见的 id 时，「全选」按钮的语义会反转；
 * - 批量已读 / 完成 / 静音会作用到用户屏幕上根本看不到的条目。
 */
class NotificationSelectionTest {

    @Test
    fun `有效选中集合是选中与可见的交集`() {
        assertEquals(setOf("b"), effectiveSelection(setOf("a", "b"), setOf("b", "c")))
    }

    @Test
    fun `看不见的选中项不参与计数与批量操作`() {
        // a 已被筛选掉 / 已归档：批量操作不能带上它
        assertEquals(emptySet<String>(), effectiveSelection(setOf("a"), setOf("b", "c")))
        assertEquals(setOf("c"), effectiveSelection(setOf("a", "c"), setOf("b", "c")))
    }

    @Test
    fun `空集合输入安全`() {
        assertEquals(emptySet<String>(), effectiveSelection(emptySet(), emptySet()))
        assertEquals(emptySet<String>(), effectiveSelection(emptySet(), setOf("a")))
        assertEquals(emptySet<String>(), effectiveSelection(setOf("a"), emptySet()))
    }

    @Test
    fun `全选判断要求可见集合非空`() {
        assertFalse(isAllVisibleSelected(setOf("a"), emptySet()))
        assertFalse(isAllVisibleSelected(emptySet(), emptySet()))
    }

    @Test
    fun `全选判断不看选中集合里多出来的不可见 id`() {
        // 选中集合里混着看不见的 x：只要可见的三条都在，就仍然是「已全选」
        assertTrue(isAllVisibleSelected(setOf("a", "b", "c", "x"), setOf("a", "b", "c")))
    }

    @Test
    fun `少一条就不是全选`() {
        assertFalse(isAllVisibleSelected(setOf("a", "b"), setOf("a", "b", "c")))
    }

    @Test
    fun `旧实现的大小比较会误判而新判断不会`() {
        // 旧写法：selected.size(3) >= visible.size(3) → 判成「已全选」，但 a 其实没选
        val selected = setOf("b", "c", "x")
        val visible = setOf("a", "b", "c")
        assertFalse(isAllVisibleSelected(selected, visible))
    }

    @Test
    fun `全选与全不选来回切换`() {
        val visible = setOf("a", "b", "c")
        assertEquals(visible, toggledAllSelection(setOf("a"), visible))
        assertEquals(emptySet<String>(), toggledAllSelection(visible, visible))
    }

    @Test
    fun `没有可见条目时全选产出空集合`() {
        assertEquals(emptySet<String>(), toggledAllSelection(setOf("a"), emptySet()))
    }

    @Test
    fun `分组头三态`() {
        val ids = listOf("1", "2", "3")
        assertEquals(GroupSelectState.NONE, groupSelectedState(ids, emptySet()))
        assertEquals(GroupSelectState.MIXED, groupSelectedState(ids, setOf("2")))
        assertEquals(GroupSelectState.ALL, groupSelectedState(ids, setOf("1", "2", "3")))
        // 分组外的选中项不影响本组判定
        assertEquals(GroupSelectState.ALL, groupSelectedState(ids, setOf("1", "2", "3", "9")))
    }

    @Test
    fun `分组头空列表是未选而不是全选`() {
        assertEquals(GroupSelectState.NONE, groupSelectedState(emptyList(), emptySet()))
    }

    @Test
    fun `半选态能被独立识别`() {
        // 「半选」必须有独立信号：否则用户无法判断点下去是补齐全组还是清空全组
        assertEquals(GroupSelectState.MIXED, groupSelectedState(listOf("1", "2"), setOf("1")))
        assertEquals(GroupSelectState.MIXED, groupSelectedState(listOf("1", "2"), setOf("2")))
    }
}
