package com.branchbase.ui.notification

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 刷选 / 区间选择的「渲染顺序 ↔ 渲染行」映射。
 *
 * 背景（审计出的缺陷）：分组布局下 LazyColumn 的 item 是**分组容器**，
 * 而刷选用的顺序表里只有通知 id —— 指针只能拿到容器 key，于是三种分组布局下
 * 「长按拖动刷选」永远匹配不上（划过即选中失效，无任何报错）。
 */
class NotificationRenderOrderTest {

    private fun n(id: String, repo: String, url: String = "https://api.github.com/repos/$repo/issues/1") =
        notificationOf(
            id = id,
            unread = true,
            reason = "subscribed",
            subjectType = "Issue",
            title = "标题 $id",
            url = url,
            latestCommentUrl = null,
            repoFullName = repo,
            updatedAtMs = 1L,
        )

    private val list = listOf(
        n("1", "a/b"),
        n("2", "c/d"),
        n("3", "a/b"),
        n("4", "c/d", url = "https://api.github.com/repos/c/d/issues/9"),
    )

    @Test
    fun `平铺布局的渲染行 key 就是通知 id`() {
        val map = renderKeyToIds(list, NotifLayout.FLAT)
        assertEquals(listOf("1"), map["1"])
        assertEquals(listOf("4"), map["4"])
        assertEquals(listOf("1", "2", "3", "4"), renderOrderIds(list, NotifLayout.FLAT))
    }

    @Test
    fun `按仓库分组的容器 key 映射回组内 id`() {
        val map = renderKeyToIds(list, NotifLayout.GROUP_BY_REPO)
        assertEquals(listOf("1", "3"), map["repo:a/b"])
        assertEquals(listOf("2", "4"), map["repo:c/d"])
    }

    @Test
    fun `按主题合并的容器 key 映射回同主题的 id`() {
        val map = renderKeyToIds(list, NotifLayout.MERGE_BY_THREAD)
        // 1 与 3 同仓库同主题（默认 url 相同）；2 与 4 主题不同
        assertEquals(listOf("1", "3"), map["thread:https://api.github.com/repos/a/b/issues/1"])
        assertEquals(listOf("2"), map["thread:https://api.github.com/repos/c/d/issues/1"])
        assertEquals(listOf("4"), map["thread:https://api.github.com/repos/c/d/issues/9"])
    }

    @Test
    fun `两级折叠按最外层仓库容器的 key 映射`() {
        val map = renderKeyToIds(list, NotifLayout.TWO_LEVEL)
        assertEquals(listOf("1", "3"), map["l2repo:a/b"])
        assertEquals(listOf("2", "4"), map["l2repo:c/d"])
    }

    @Test
    fun `渲染顺序等于各容器内 id 依次拼接`() {
        for (layout in NotifLayout.entries) {
            val order = renderOrderIds(list, layout)
            val map = renderKeyToIds(list, layout)
            // 按「容器在顺序表里首次出现」的次序拼接，应与顺序表完全一致
            val rebuilt = buildList {
                val seen = mutableSetOf<String>()
                order.forEach { id ->
                    val key = map.entries.firstOrNull { id in it.value }?.key ?: return@forEach
                    if (seen.add(key)) addAll(map.getValue(key))
                }
            }
            assertEquals("layout=$layout", order, rebuilt)
        }
    }

    @Test
    fun `容器的 id 集合与顺序表互不遗漏`() {
        for (layout in NotifLayout.entries) {
            val order = renderOrderIds(list, layout)
            val ids = renderKeyToIds(list, layout).values.flatten()
            assertEquals("layout=$layout", order.toSet(), ids.toSet())
            assertEquals("layout=$layout", order.size, ids.size)
        }
    }
}
