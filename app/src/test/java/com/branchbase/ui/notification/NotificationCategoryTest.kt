package com.branchbase.ui.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「分类 × 本地覆盖」矩阵的钉子（`notifCategoryBase`，纯函数）。
 *
 * 为什么值得单独一个测试类：这段过滤原先写在 `NotificationScreen` 的组合体里
 * （`when (category)` 直接写在 @Composable 内），于是**没有出口可测**，
 * 三个分类各写一遍过滤，加一个本地覆盖就要改三处。它已经因此出过两次事故：
 *
 * 1. **已完成**的条目要从收件箱移出（`doneIds`）—— 漏掉某一类，同一条会同时出现在
 *    「未读」和「已完成」里；
 * 2. **已丢弃**的条目要在**四个**分类里都消失（`discardedIds`）—— 只在「已完成」里滤掉的话，
 *    丢弃完切到「全部」，它会从收件箱冒出来，用户看到的是「丢弃没生效」。
 *
 * 所以这里钉的是**矩阵**，不是某一个分支。
 */
class NotificationCategoryTest {

    private fun n(id: String, unread: Boolean = true, reason: String = "subscribed", type: String = "Issue") =
        notificationOf(
            id = id,
            unread = unread,
            reason = reason,
            subjectType = type,
            title = "t-$id",
            url = "https://api.github.com/repos/o/r/issues/1",
            latestCommentUrl = null,
            repoFullName = "o/r",
            updatedAtMs = 1_000L,
        )

    /** 收件箱：a 未读、b 已读、c 未读但已被 @（参与口径）。 */
    private val items = listOf(
        n("a"),
        n("b", unread = false),
        n("c", reason = "mention"),
        n("done-live"),   // 归档后仍在 items 里的那条（回源竞态）
        n("discarded-live"),
    )

    /** 归档（已完成）：done-1 与 discarded-done。 */
    private val doneItems = listOf(
        n("done-1", unread = false),
        n("done-live", unread = false),
        n("discarded-done", unread = false),
    )

    private val doneIds = setOf("done-1", "done-live")
    private val discardedIds = setOf("discarded-done", "discarded-live")

    private fun base(
        category: NotifCategory,
        participating: List<Notification>? = null,
    ) = notifCategoryBase(
        category = category,
        items = items,
        doneItems = doneItems,
        participatingItems = participating,
        doneIds = doneIds,
        discardedIds = discardedIds,
    ).map { it.id }

    @Test
    fun `全部不显示已完成与已丢弃`() {
        assertEquals(listOf("a", "b", "c"), base(NotifCategory.ALL))
    }

    @Test
    fun `未读不显示已完成与已丢弃`() {
        assertEquals(listOf("a", "c"), base(NotifCategory.UNREAD))
    }

    @Test
    fun `已完成保留归档但滤掉已丢弃`() {
        // done-1 是归档条目本身（保留）；discarded-done 被丢弃（滤掉）；
        // done-live 同 id 同时出现在 items 与归档里，只应出现一次（走归档这份）
        assertEquals(listOf("done-1", "done-live"), base(NotifCategory.DONE))
    }

    @Test
    fun `参与分类服务端口径同样过本地覆盖`() {
        // 服务端返回的参与列表里混进了已完成 / 已丢弃的条目 —— 一样不许出现
        val server = listOf(n("c", reason = "mention"), n("done-live"), n("discarded-live"), n("a"))
        assertEquals(listOf("c", "a"), base(NotifCategory.PARTICIPATING, participating = server))
    }

    @Test
    fun `参与分类退回客户端近似口径时也要过滤`() {
        // 还没拉到服务端列表（null）→ 近似口径：只看 reason，且同样要滤掉本地覆盖项
        val ids = base(NotifCategory.PARTICIPATING, participating = null)
        assertEquals(listOf("c"), ids)
        assertFalse("已完成 / 已丢弃的条目不许从近似口径漏回来", ids.any { it in doneIds || it in discardedIds })
    }

    @Test
    fun `未知动因不算参与但不吞掉条目`() {
        val odd = listOf(n("x", reason = "some_new_reason"))
        fun of(category: NotifCategory) = notifCategoryBase(
            category = category,
            items = odd,
            doneItems = emptyList(),
            participatingItems = null,
            doneIds = emptySet(),
            discardedIds = emptySet(),
        ).map { it.id }

        assertTrue("后端新增动因不该被当成参与", of(NotifCategory.PARTICIPATING).isEmpty())
        // 但它在「全部」里照旧出现 —— 未知动因不吞条目（同 `stateLabelResOrNull` 的口径）
        assertEquals(listOf("x"), of(NotifCategory.ALL))
    }
}
