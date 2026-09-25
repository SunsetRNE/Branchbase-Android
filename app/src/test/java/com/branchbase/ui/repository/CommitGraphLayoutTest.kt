package com.branchbase.ui.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提交图泳道布局的钉子（`CommitGraphLayout`）。
 *
 * 布局是纯函数，但它错起来的样子**全在画面上**：线断头、串道、泳道数突然跳一下 ——
 * 真机上只能靠肉眼盯，回归时最容易漏。所以把设计稿 §6 列的那批用例在这里钉死，
 * 尤其两条：
 *
 * - **父不在窗口里（分页截断）必须画终止符**，不许画成断头线（那会让人以为历史就到这里）；
 * - **同输入同输出**（幂等）—— 列表刷新时重算，抖动一下就是整屏线在跳。
 */
class CommitGraphLayoutTest {

    private fun c(sha: String, vararg parents: String) = GraphCommit(
        fullSha = sha,
        parents = parents.toList(),
        subject = "subject-$sha",
        author = "author",
        date = "2026-09-25T00:00:00Z",
    )

    private fun commitRows(commits: List<GraphCommit>, dirty: Int? = null): List<GraphCommitRow> =
        CommitGraphLayout.layout(commits, dirty).filterIsInstance<GraphRow.Commit>().map { it.row }

    @Test
    fun `线性历史全程一条泳道`() {
        val rows = commitRows(listOf(c("c3", "c2"), c("c2", "c1"), c("c1")))
        assertEquals(listOf(0, 0, 0), rows.map { it.lane })
        assertEquals(listOf(1, 1, 1), rows.map { it.laneCount })
    }

    @Test
    fun `分叉的第二父开一条新泳道`() {
        // c3 同时是 c2 与 c1 的子（c2 也以 c1 为父）——真实的分叉结构：
        // 第二父 c1 占右侧新泳道，c2 接完 c1 之后左泳道释放、c1 回到最左
        val rows = commitRows(listOf(c("c3", "c2", "c1"), c("c2", "c1"), c("c1")))
        assertEquals(0, rows[0].lane)
        assertEquals(2, rows[0].laneCount)
        assertEquals(0, rows[2].lane)
        assertEquals(1, rows[2].laneCount)
    }

    @Test
    fun `两条分支汇合后泳道回收`() {
        val rows = commitRows(
            listOf(
                c("m", "a", "b"),   // 合并提交：a 继承泳道 0，b 开泳道 1
                c("b", "root"),
                c("a", "root"),
                c("root"),
            ),
        )
        assertEquals(2, rows[0].laneCount)
        // root 认领最左的等待它的泳道；另一条泳道随后回收
        assertEquals(0, rows[3].lane)
        assertEquals(1, rows[3].laneCount)
    }

    @Test
    fun `父不在窗口里标记 dangling 而不是断头线`() {
        // 分页只取了最新的一条，它的父还没加载
        val rows = commitRows(listOf(c("newest", "not-loaded-yet")))
        assertTrue("父不在窗口里必须标 dangling", rows[0].dangling)
        assertTrue(
            "dangling 的边也要画出来（画终止符），不许什么都不画",
            rows[0].edges.any { it.fromNode && it.dangling },
        )
    }

    @Test
    fun `根提交不算 dangling`() {
        val rows = commitRows(listOf(c("root")))
        assertFalse("没有父提交是历史的真实起点，不是截断", rows[0].dangling)
    }

    @Test
    fun `octopus 三个父占三条泳道`() {
        val rows = commitRows(listOf(c("m", "p1", "p2", "p3"), c("p1"), c("p2"), c("p3")))
        assertEquals(3, rows[0].laneCount)
        assertEquals(3, rows[0].edges.count { it.fromNode })
    }

    @Test
    fun `虚节点只在有改动时出现且排在最上`() {
        val withDirty = CommitGraphLayout.layout(listOf(c("c1")), workingTreeDirty = 3)
        assertTrue("有改动时最上一行是虚节点", withDirty.first() is GraphRow.WorkingTree)
        assertEquals(3, (withDirty.first() as GraphRow.WorkingTree).dirtyCount)
        assertEquals("虚节点只占一条泳道", 1, withDirty.first().laneCount)

        assertTrue(
            "工作区干净时不画虚节点（否则 HEAD 之上永远挂着一个空节点）",
            CommitGraphLayout.layout(listOf(c("c1")), workingTreeDirty = 0).none { it is GraphRow.WorkingTree },
        )
        assertTrue(
            "没传工作区状态时也不画",
            CommitGraphLayout.layout(listOf(c("c1"))).none { it is GraphRow.WorkingTree },
        )
    }

    @Test
    fun `空仓库只有虚节点（或什么都没有）`() {
        assertTrue(CommitGraphLayout.layout(emptyList()).isEmpty())
        val onlyDirty = CommitGraphLayout.layout(emptyList(), workingTreeDirty = 2)
        assertEquals(1, onlyDirty.size)
        assertTrue(onlyDirty.first() is GraphRow.WorkingTree)
    }

    @Test
    fun `同输入同输出`() {
        val commits = listOf(c("m", "a", "b"), c("b", "root"), c("a", "root"), c("root"))
        assertEquals(
            CommitGraphLayout.layout(commits, workingTreeDirty = 1),
            CommitGraphLayout.layout(commits, workingTreeDirty = 1),
        )
    }

    @Test
    fun `解析保留 parents —— 图与提交列表用的是两份解析`() {
        val json = """
            [{"sha":"aaa1111","commit":{"message":"subject\n\nbody","author":{"name":"N","date":"2026-09-25T00:00:00Z"},
              "parents":[{"sha":"bbb2222"},{"sha":"ccc3333"}]}}]
        """.trimIndent()
        val one = parseGraphCommits(json).single()
        assertEquals("aaa1111", one.fullSha)
        assertEquals(listOf("bbb2222", "ccc3333"), one.parents)
        assertEquals("只取标题行，不带正文", "subject", one.subject)
        assertEquals("N", one.author)
    }

    @Test
    fun `解析坏 JSON 返回空表而不是抛`() {
        assertTrue(parseGraphCommits(null).isEmpty())
        assertTrue(parseGraphCommits("").isEmpty())
        assertTrue(parseGraphCommits("not json").isEmpty())
        assertTrue(parseGraphCommits("[{\"commit\":{}}]").isEmpty())
    }
}
