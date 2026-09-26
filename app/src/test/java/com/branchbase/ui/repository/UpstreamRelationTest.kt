package com.branchbase.ui.repository

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「上游」判定的回归测试（`UpstreamRelation.kt`，1.1.5）。
 *
 * 为什么这些规则必须被钉住：六个状态里有四个是**会改变用户能做什么**的结论 ——
 * 判成「自持仓库」会**藏掉整枚出口**（判错的代价是用户找不到那把入口），
 * 判成「已私有化」会让用户以为自己没有权限（其实是网络没打通），
 * 判成「有上游」会让人去推一个已归档的仓库。所以：
 *
 * 1. 判定必须是纯函数、可回归（`UpstreamRules.detect`）；
 * 2. **不确定时必须落在「未知」**，而不是替用户挑一个看起来最像的结论。
 *
 * 用例里的 GitHub 语义来自官方文档《What happens to forks when a repository is
 * deleted or changes visibility》：父仓库被删 / 公共转私有 → 复刻**断开成独立网络**
 * （`parent` 随之消失）；`parent` 还在却读不到 → 只剩「没权限」。
 */
class UpstreamRelationTest {

    private fun info(
        fullName: String = "up/stream",
        archived: Boolean = false,
        isFork: Boolean = false,
        parentFullName: String? = null,
    ): RepoInfo = RepoInfo(
        fullName = fullName,
        name = fullName.substringAfter('/'),
        description = "",
        stars = 0,
        forks = 0,
        watchers = 0,
        license = null,
        defaultBranch = "main",
        ownerLogin = fullName.substringBefore('/'),
        isFork = isFork,
        parentFullName = parentFullName,
        archived = archived,
    )

    // ── 六态判定 ──

    @Test
    fun `不是复刻就是自持仓库且不画上游出口`() {
        val rel = UpstreamRules.detect(isFork = false, parentFullName = null)
        assertEquals(UpstreamState.SELF_OWNED, rel.state)
        assertNull(rel.fullName)
        // 这一条是本次改动的核心：自持仓库**不显示**上游相关的选项
        assertFalse(rel.showsEntry)
    }

    @Test
    fun `复刻且上游读得到就是有上游仓库`() {
        val rel = UpstreamRules.detect(
            isFork = true,
            parentFullName = "SunsetRNE/Branchbase-Android",
            probe = UpstreamProbe(info = info("SunsetRNE/Branchbase-Android"), attempted = true),
        )
        assertEquals(UpstreamState.AVAILABLE, rel.state)
        assertEquals("SunsetRNE/Branchbase-Android", rel.fullName)
        assertTrue(rel.showsEntry)
    }

    @Test
    fun `上游归档单独一态_只读`() {
        val rel = UpstreamRules.detect(
            isFork = true,
            parentFullName = "old/repo",
            probe = UpstreamProbe(info = info("old/repo", archived = true), attempted = true),
        )
        assertEquals(UpstreamState.ARCHIVED, rel.state)
        assertEquals("old/repo", rel.fullName)
    }

    @Test
    fun `复刻关系已断判为上游已删除`() {
        // GitHub 在父仓库被删、或公共转私有拆网时会把 parent 抹掉 —— 这时 fork 仍是 true
        val rel = UpstreamRules.detect(isFork = true, parentFullName = null)
        assertEquals(UpstreamState.DELETED, rel.state)
        assertNull(rel.fullName)
        assertTrue(rel.showsEntry)
    }

    @Test
    fun `关系还在却_404_就是上游已私有化`() {
        val rel = UpstreamRules.detect(
            isFork = true,
            parentFullName = "secret/repo",
            probe = UpstreamProbe(info = null, attempted = true, denied = true),
        )
        assertEquals(UpstreamState.PRIVATE, rel.state)
        assertEquals("secret/repo", rel.fullName)
    }

    @Test
    fun `仓库详情没读到时是未知_不发请求也不猜`() {
        val rel = UpstreamRules.detect(isFork = null, parentFullName = null)
        assertEquals(UpstreamState.UNKNOWN, rel.state)
        // 「未知」不许藏功能：探测不到不等于没有上游
        assertTrue(rel.showsEntry)
    }

    @Test
    fun `没有令牌时不探也不下私有化结论`() {
        val rel = UpstreamRules.detect(
            isFork = true,
            parentFullName = "secret/repo",
            probe = UpstreamProbe(info = null, attempted = false),
            hasToken = false,
        )
        assertEquals(UpstreamState.UNKNOWN, rel.state)
        assertEquals("secret/repo", rel.fullName)
    }

    @Test
    fun `探测没打通时不许说已私有化`() {
        // attempted = true 但既没拿到 info、也不是 404（超时 / 限流 / 无响应）
        val rel = UpstreamRules.detect(
            isFork = true,
            parentFullName = "some/repo",
            probe = UpstreamProbe(info = null, attempted = true, denied = false),
        )
        assertEquals(UpstreamState.UNKNOWN, rel.state)
    }

    @Test
    fun `有令牌但还没探时也是未知`() {
        val rel = UpstreamRules.detect(
            isFork = true,
            parentFullName = "some/repo",
            probe = UpstreamProbe(info = null, attempted = false),
        )
        assertEquals(UpstreamState.UNKNOWN, rel.state)
    }

    @Test
    fun `fork_为假但父仓库还在时仍按有上游处理`() {
        // 两个字段来自不同接口，理论上会打架；这时候宁可多给一次入口，也不要藏掉
        val rel = UpstreamRules.detect(
            isFork = false,
            parentFullName = "up/stream",
            probe = UpstreamProbe(info = info("up/stream"), attempted = true),
        )
        assertEquals(UpstreamState.AVAILABLE, rel.state)
    }

    // ── 输入合一与拆名 ──

    @Test
    fun `仓库详情优先于关系态`() {
        val fromInfo = info("a/b", isFork = true, parentFullName = "p/q")
        val (isFork, parent) = UpstreamRules.resolve(fromInfo, RepoViewerRelation(isFork = false))
        assertEquals(true, isFork)
        assertEquals("p/q", parent)
    }

    @Test
    fun `没有仓库详情时用关系态兜底`() {
        // GraphQL 一直在查 isFork / parent{nameWithOwner}（RepoActions.RELATION_QUERY），
        // 以前解析时被丢掉 —— 这一条钉住「补回来后真的用得上」
        val relation = RepoViewerRelation(isFork = true, parentFullName = "up/stream")
        val (isFork, parent) = UpstreamRules.resolve(null, relation)
        assertEquals(true, isFork)
        assertEquals("up/stream", parent)
    }

    @Test
    fun `拆全名只认两段`() {
        assertEquals("SunsetRNE" to "Branchbase-Android", UpstreamRules.splitFullName("SunsetRNE/Branchbase-Android"))
        assertNull(UpstreamRules.splitFullName("no-slash"))
        assertNull(UpstreamRules.splitFullName("a/b/c"))
        assertNull(UpstreamRules.splitFullName("/b"))
        assertNull(UpstreamRules.splitFullName(null))
        assertNull(UpstreamRules.splitFullName("  "))
    }

    // ── 解析：REST / GraphQL / 缓存三条通道都要把复刻关系带上 ──

    @Test
    fun `仓库详情解析带上归档位与复刻关系`() {
        val json = """
            {"full_name":"me/Branchbase-Android","name":"Branchbase-Android","fork":true,
             "archived":true,"parent":{"full_name":"SunsetRNE/Branchbase-Android"},
             "default_branch":"main","owner":{"login":"me","type":"User"}}
        """
        val parsed = parseRepoInfo(json)
        assertNotNull(parsed)
        assertTrue(parsed!!.isFork)
        assertEquals("SunsetRNE/Branchbase-Android", parsed.parentFullName)
        assertTrue(parsed.archived)
    }

    @Test
    fun `自持仓库的详情解析出_fork_为假`() {
        val json = """{"full_name":"SunsetRNE/Branchbase-Android","name":"Branchbase-Android","fork":false}"""
        val parsed = parseRepoInfo(json)
        assertNotNull(parsed)
        assertFalse(parsed!!.isFork)
        assertNull(parsed.parentFullName)
        assertFalse(parsed.archived)
    }

    @Test
    fun `GraphQL_关系态解析出复刻关系`() {
        val data = JSONObject(
            """{"repository":{"viewerHasStarred":false,"forkingAllowed":true,"isFork":true,
                "parent":{"nameWithOwner":"SunsetRNE/Branchbase-Android"},"watchers":{"totalCount":3}}}""",
        )
        val rel = RepoViewerRelation.fromGraphQL(data)
        assertNotNull(rel)
        assertEquals(true, rel!!.isFork)
        assertEquals("SunsetRNE/Branchbase-Android", rel.parentFullName)
    }

    @Test
    fun `网页关系态不给复刻关系时留空而不是假`() {
        // 网页那条路（sidebarAbout）里没有 fork 关系 —— 留 null，判定会因此落在「未知」，
        // 而不是被 default false 判成「自持仓库」把出口藏掉
        val web = JSONObject(
            """{"payload":{"sidebarAbout":{"star":{"viewerHasStarred":true,"canStar":true},
                "fork":{"canFork":true}}}}""",
        )
        val rel = RepoViewerRelation.fromWeb(web)
        assertNotNull(rel)
        assertNull(rel!!.isFork)
        assertNull(rel.parentFullName)
        assertEquals(UpstreamState.UNKNOWN, UpstreamRules.detect(rel.isFork, rel.parentFullName).state)
    }

    @Test
    fun `关系态缓存往返保留复刻关系`() {
        val original = RepoViewerRelation(isFork = true, parentFullName = "up/stream")
        val back = RepoViewerRelation.fromCache(original.toJson())
        assertNotNull(back)
        assertEquals(true, back!!.isFork)
        assertEquals("up/stream", back.parentFullName)
    }

    @Test
    fun `关系态合并不把没探到的复刻关系冲掉`() {
        val high = RepoViewerRelation(isFork = true, parentFullName = "up/stream")
        val merged = high.merge(RepoViewerRelation(isFork = false))
        assertEquals(true, merged.isFork)
        assertEquals("up/stream", merged.parentFullName)
    }

    // ── 文案：六态各说各的 ──

    @Test
    fun `六个状态的标签与说明都不同句`() {
        val labels = UpstreamState.entries.map { upstreamStateLabelRes(it) }
        val notes = UpstreamState.entries.map { upstreamNoteRes(it) }
        assertEquals("标签不许两态共用一句", labels.size, labels.toSet().size)
        assertEquals("说明不许两态共用一句", notes.size, notes.toSet().size)
    }

    @Test
    fun `只有自持仓库藏出口_其余五态都留着`() {
        val hiding = UpstreamState.entries.filter { !UpstreamRelation(it).showsEntry }
        assertEquals(listOf(UpstreamState.SELF_OWNED), hiding)
    }

    // ── 工作台那行「未设置上游」：复用同一份判定，没有上游就不显示 ──

    @Test
    fun `自持仓库且没跟踪远端时_不画未设置上游那一行`() {
        // 自持仓库没有上游可设 —— 那句话落在那里是一句永远无法执行的催促
        assertFalse(
            showsUpstreamLine(
                hasUpstream = false,
                upstreamBranch = null,
                rel = UpstreamRelation(UpstreamState.SELF_OWNED),
            ),
        )
    }

    @Test
    fun `真有上游却还没跟踪时_照旧画那一行`() {
        // 这一句对它是**可执行的**提示：上游就在那儿（哪怕已归档 / 已私有化，也仍然要出现）
        listOf(
            UpstreamState.AVAILABLE,
            UpstreamState.ARCHIVED,
            UpstreamState.DELETED,
            UpstreamState.PRIVATE,
        ).forEach { state ->
            assertTrue(
                "$state 有上游，不许藏那一行",
                showsUpstreamLine(false, null, UpstreamRelation(state, "up/stream")),
            )
        }
    }

    @Test
    fun `还没探到上游时_不藏那一行`() {
        // 拿「不知道」当「没有」，会让一个真有上游的仓库失去那句唯一提示
        assertTrue(showsUpstreamLine(hasUpstream = false, upstreamBranch = null, rel = null))
        assertTrue(showsUpstreamLine(false, null, UpstreamRelation(UpstreamState.UNKNOWN)))
    }

    @Test
    fun `跟踪上了远端分支_自持仓库也照画那一行`() {
        // 这时要说的不是「你可以设上游」，而是「你跟踪的是 origin/main」这个事实（clone 自己的仓库就是这样）
        assertTrue(
            showsUpstreamLine(
                hasUpstream = true,
                upstreamBranch = "origin/main",
                rel = UpstreamRelation(UpstreamState.SELF_OWNED),
            ),
        )
    }

    @Test
    fun `hasUpstream 为真但分支名是空的_不算跟踪上了`() {
        // 快照可能只有 hasUpstream=true 而没有分支名 —— 空串 / 空白不许当「跟踪上了」，
        // 否则自持仓库会凭一个空名字把那一行画回来
        assertFalse(showsUpstreamLine(true, "", UpstreamRelation(UpstreamState.SELF_OWNED)))
        assertFalse(showsUpstreamLine(true, "   ", UpstreamRelation(UpstreamState.SELF_OWNED)))
        assertTrue(showsUpstreamLine(true, "   ", UpstreamRelation(UpstreamState.AVAILABLE)))
    }
}
