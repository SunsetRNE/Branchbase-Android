package com.branchbase.ui.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批量失败的**回滚范围**单测（[bulkRollbackTargets]）。
 *
 * 背景（审计出的缺陷）：批量操作是「本地乐观更新 → 远端逐条串行写」，中途可能有几条失败。
 * 旧实现只要发现有失败就**整批回滚**，包括远端已经改成功的那些：
 * 用户看到「标记已读失败」，列表却整片退回未读；等下拉刷新，其中一部分又自己变回已读。
 * 这段窗口里本地与远端是不一致的 —— 而「回滚是为了跟远端一致」正是整批回滚的理由。
 *
 * 静音（[BulkOp.MUTE]）是另一个方向：它不改动任何本地可见状态，
 * 回滚它不但没有意义，还会顺带重写 [NotifArchive]。
 */
class NotificationBulkRollbackTest {

    private fun n(id: String) = notificationOf(
        id = id,
        unread = true,
        reason = "subscribed",
        subjectType = "Issue",
        title = "标题 $id",
        url = "https://api.github.com/repos/o/r/issues/1",
        latestCommentUrl = null,
        repoFullName = "o/r",
        updatedAtMs = 0L,
    )

    @Test
    fun `只回滚失败的条目`() {
        val targets = listOf(n("1"), n("2"), n("3"))
        // 2 失败、1 和 3 远端已经改成功 —— 它们必须留在已改状态
        assertEquals(listOf("2"), bulkRollbackTargets(targets, setOf("2"), BulkOp.READ).map { it.id })
    }

    @Test
    fun `整批失败时回滚集合等于整批`() {
        val targets = listOf(n("1"), n("2"), n("3"))
        assertEquals(
            listOf("1", "2", "3"),
            bulkRollbackTargets(targets, setOf("1", "2", "3"), BulkOp.READ).map { it.id },
        )
    }

    @Test
    fun `全部成功时没有任何条目需要回滚`() {
        val targets = listOf(n("1"), n("2"))
        assertTrue(bulkRollbackTargets(targets, emptySet(), BulkOp.READ).isEmpty())
        assertTrue(bulkRollbackTargets(targets, emptySet(), BulkOp.DONE).isEmpty())
    }

    @Test
    fun `静音没有可回滚的本地状态`() {
        // 静音只动远端，本地列表不隐藏、不变色；远端也没有可撤销的 subscribe 接口
        val targets = listOf(n("1"), n("2"))
        assertTrue(bulkRollbackTargets(targets, setOf("1", "2"), BulkOp.MUTE).isEmpty())
        assertTrue(bulkRollbackTargets(targets, setOf("1"), BulkOp.MUTE).isEmpty())
    }

    @Test
    fun `失败但不属于本批的 id 不产生幽灵条目`() {
        // 回滚只针对「本批真的发出去过的」条目，不能凭一个陌生 id 去动本地状态
        assertTrue(bulkRollbackTargets(listOf(n("1")), setOf("9"), BulkOp.READ).isEmpty())
    }

    @Test
    fun `空选中集合安全`() {
        assertTrue(bulkRollbackTargets(emptyList(), setOf("1"), BulkOp.READ).isEmpty())
        assertTrue(bulkRollbackTargets(emptyList(), emptySet(), BulkOp.DONE).isEmpty())
    }
}
