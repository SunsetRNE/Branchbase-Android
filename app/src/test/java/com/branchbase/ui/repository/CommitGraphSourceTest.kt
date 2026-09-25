package com.branchbase.ui.repository

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提交图**双来源**的钉子（阶段 4：「让提交图真的走本地」）。
 *
 * ## 为什么这几条必须钉
 *
 * 它们全都只表现成真机上的「图不对」，而每一种不对都长得像别的原因：
 *
 * 1. **择源**：[graphSourceOf] 把浅克隆排除在外。写反了的表现是「本地仓库存在 → 图里只有 1 条提交」，
 *    用户会以为仓库历史就这么点（clone 用的是 `depth(1)`，本地真的只有 HEAD）；
 * 2. **分页键**：本地按 `skip`、REST 按最老 sha。两种来源共用一个键，
 *    表现是「点了『加载更早』没反应」或「反复加载同一页」——前者像网络慢，后者看不出错；
 * 3. **本地 JSON 的解析**：引擎给的是**扁平**结构（不是 GitHub 那套嵌套），
 *    拿错解析函数会得到一张空图，而面板只会说「这个分支还没有提交」；
 * 4. **浅克隆判定**：读 `.git/shallow`。它是「加深成功后能不能翻回本地来源」的开关，
 *    判错就是「加深完了，图还是走 REST」。
 */
class CommitGraphSourceTest {

    // ── 择源 ──

    @Test
    fun `本地存在且不是浅克隆才走本地来源`() {
        assertEquals(GraphSource.LOCAL, graphSourceOf(localRepoExists = true, localShallow = false))
    }

    @Test
    fun `浅克隆必须留在 REST —— 本地只有 depth1 那一条`() {
        assertEquals(GraphSource.REST, graphSourceOf(localRepoExists = true, localShallow = true))
    }

    @Test
    fun `没有本地仓库走 REST`() {
        assertEquals(GraphSource.REST, graphSourceOf(localRepoExists = false, localShallow = false))
        // 「没有本地仓库却是浅克隆」是矛盾输入：仍然不许走本地（宁可多走一次网络，也不要一张空图）
        assertEquals(GraphSource.REST, graphSourceOf(localRepoExists = false, localShallow = true))
    }

    // ── 分页键 ──

    private fun commit(sha: String) = GraphCommit(
        fullSha = sha,
        parents = emptyList(),
        subject = "s",
        author = "a",
        date = "",
    )

    @Test
    fun `本地按 skip 续取：skip 等于已加载条数`() {
        assertEquals(GraphPage.Local(skip = 0), nextGraphPage(GraphSource.LOCAL, emptyList()))
        assertEquals(
            GraphPage.Local(skip = 2),
            nextGraphPage(GraphSource.LOCAL, listOf(commit("a"), commit("b"))),
        )
    }

    @Test
    fun `REST 按最老 sha 续取：首页不给 sha`() {
        assertEquals(GraphPage.Rest(sha = null), nextGraphPage(GraphSource.REST, emptyList()))
        assertEquals(
            GraphPage.Rest(sha = "b"),
            nextGraphPage(GraphSource.REST, listOf(commit("a"), commit("b"))),
        )
    }

    // ── 本地 JSON 的解析 ──

    @Test
    fun `本地扁平 JSON 解析出 parents 与字段`() {
        val json = """
            [
              {"sha":"c3","parents":["c2"],"subject":"第三个","author":"Carol","date":"2026-09-25T04:41:23+08:00"},
              {"sha":"c1","parents":[],"subject":"根","author":"Alice","date":""}
            ]
        """.trimIndent()
        val out = parseLocalGraphCommits(json)
        assertEquals(2, out.size)
        assertEquals("c3", out[0].fullSha)
        assertEquals(listOf("c2"), out[0].parents)
        assertEquals("第三个", out[0].subject)
        assertEquals("Carol", out[0].author)
        // 短的取前 7 位（列表左侧那一列）
        assertEquals("c3", out[0].shortSha)
        // 根提交没有父，不能编一个出来（编了就多一根断头线）
        assertEquals(emptyList<String>(), out[1].parents)
    }

    @Test
    fun `合并提交的多父一个都不能丢`() {
        // 少了第二父，泳道布局就画不出那条分支线（图退化成一条直线）
        val json = """[{"sha":"m","parents":["p1","p2"],"subject":"merge","author":"A","date":""}]"""
        assertEquals(listOf("p1", "p2"), parseLocalGraphCommits(json)[0].parents)
    }

    @Test
    fun `解析不出来给空列表而不是抛：空仓库与坏 JSON 同一个口径`() {
        assertEquals(emptyList<GraphCommit>(), parseLocalGraphCommits(null))
        assertEquals(emptyList<GraphCommit>(), parseLocalGraphCommits(""))
        assertEquals(emptyList<GraphCommit>(), parseLocalGraphCommits("不是 JSON"))
        // 空数组 = 「还没有提交」（正常状态），调用方按那句渲染
        assertEquals(emptyList<GraphCommit>(), parseLocalGraphCommits("[]"))
        // 没有 sha 的条目直接丢：图上以 sha 为键（LazyColumn 的 key、交互都以它为准）
        assertEquals(emptyList<GraphCommit>(), parseLocalGraphCommits("""[{"subject":"没有 sha"}]"""))
    }

    @Test
    fun `两份解析各认各的形状，不互相兼容`() {
        // 两个解析器**故意不互相兼容**：本地那份只认扁平字段，REST 那份只认嵌套。
        // 把 REST 的响应体喂给本地解析器，能拿到 sha，但 `parents` / `subject` 都是空 ——
        // 「能解析」不等于「解析对了」，偷偷兼容两套形状才是更坏的事：字段口径的差异
        // 会在下一次改接口时静默爆掉（图里少一条泳道、标题全空，而没人想到是解析器）。
        val rest = """[{"sha":"c1","commit":{"message":"标题","parents":[{"sha":"p"}],"author":{"name":"A","date":"d"}}}]"""
        val fromLocalParser = parseLocalGraphCommits(rest)
        assertEquals(1, fromLocalParser.size)
        assertEquals("c1", fromLocalParser[0].fullSha)
        assertEquals(emptyList<String>(), fromLocalParser[0].parents)
        assertEquals("", fromLocalParser[0].subject)

        val local = """[{"sha":"c3","parents":["c2"],"subject":"扁平","author":"Carol","date":"d"}]"""
        val fromRestParser = parseGraphCommits(local)
        assertEquals(1, fromRestParser.size)
        assertEquals("c3", fromRestParser[0].fullSha)
        assertEquals(emptyList<String>(), fromRestParser[0].parents)
        assertEquals("", fromRestParser[0].subject)

        // 对照组：各自的形状能拿到完整字段 —— 否则上面四条会因为「谁都解析不出来」而全绿
        assertEquals("扁平", parseLocalGraphCommits(local)[0].subject)
        assertEquals(listOf("c2"), parseLocalGraphCommits(local)[0].parents)
        assertEquals(listOf("p"), parseGraphCommits(rest)[0].parents)
        assertEquals("标题", parseGraphCommits(rest)[0].subject)
    }

    // ── 浅克隆判定（真读文件：它是「加深成功后翻不翻来源」的开关） ──

    @Test
    fun `浅克隆按 git shallow 文件判定`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "bb-shallow-${System.nanoTime()}")
        assertTrue(dir.mkdirs())
        try {
            assertFalse("没有 .git/shallow 就不是浅克隆", isShallowClone(dir.absolutePath))
            File(dir, ".git").mkdirs()
            assertFalse("只有 .git 目录还不算", isShallowClone(dir.absolutePath))
            File(dir, ".git/shallow").writeText("0000000000000000000000000000000000000000\n")
            assertTrue("浅边界文件在 → 浅克隆", isShallowClone(dir.absolutePath))
            // 加深成功时 libgit2 会删掉这个文件（`git_repository__shallow_roots_write`），
            // 于是判定翻回来 —— 这条正是「加深完图换回本地来源」的机关
            File(dir, ".git/shallow").delete()
            assertFalse(isShallowClone(dir.absolutePath))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `空目录不当作浅克隆`() {
        assertFalse(isShallowClone(""))
    }
}
