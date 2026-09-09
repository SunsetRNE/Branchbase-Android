package com.branchbase.ui.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地仓库「分支同步」纯逻辑单测。
 *
 * 覆盖三块决定同步行为的东西：
 * 1. 状态判定（未跟踪 / 分叉 / 领先 / 落后 / 已同步）——分叉必须优先于领先，
 *    否则会把「两边都有独有提交」误判成「只差一次推送」；
 * 2. 同步计划（哪些分支可以自动推送、哪个分支可以自动拉取）；
 * 3. 两个 native 输出的解析（字段名与 Rust `local_branches` / `remote_branches` 对齐）。
 */
class LocalBranchSyncModelsTest {

    private fun local(
        name: String,
        isHead: Boolean = false,
        upstream: String = "origin/$name",
        ahead: Int = 0,
        behind: Int = 0,
    ) = LocalBranchInfo(name, isHead, upstream, ahead, behind)

    // ── 状态判定 ──

    @Test
    fun `五种同步状态判定`() {
        assertEquals(SyncState.Synced, syncStateOf(local("main", isHead = true)))
        assertEquals(SyncState.Ahead, syncStateOf(local("main", ahead = 2)))
        assertEquals(SyncState.Behind, syncStateOf(local("main", behind = 3)))
        assertEquals(SyncState.Diverged, syncStateOf(local("main", ahead = 1, behind = 3)))
        assertEquals(SyncState.Untracked, syncStateOf(local("feat", upstream = "")))
    }

    @Test
    fun `分叉优先于领先与落后`() {
        // 两边都有独有提交时，既不能直接推送也不能直接快进
        val b = local("main", ahead = 1, behind = 1)
        assertEquals(SyncState.Diverged, syncStateOf(b))
    }

    @Test
    fun `未跟踪优先于领先计数`() {
        // 没有 upstream 时，ahead/behind 是无意义的（libgit2 给 0，但不能依赖）
        val b = local("feat", upstream = "", ahead = 5)
        assertEquals(SyncState.Untracked, syncStateOf(b))
    }

    @Test
    fun `状态标签中文`() {
        assertEquals("已同步", SyncState.Synced.label)
        assertEquals("可推送", SyncState.Ahead.label)
        assertEquals("可拉取", SyncState.Behind.label)
        assertEquals("分叉", SyncState.Diverged.label)
        assertEquals("未跟踪", SyncState.Untracked.label)
    }

    // ── 同步计划 ──

    @Test
    fun `只推送领先分支 只拉取当前落后分支`() {
        val plan = planSync(
            listOf(
                local("main", isHead = true, behind = 2),
                local("feat-a", ahead = 1),
                local("feat-b", ahead = 3),
                local("feat-c", behind = 1),   // 非当前分支落后：不自动拉取
                local("feat-d", ahead = 1, behind = 1),
                local("feat-e", upstream = ""),
            ),
        )

        assertEquals(listOf("feat-a", "feat-b"), plan.toPush)
        assertEquals("main", plan.toPull)
        assertEquals(listOf("feat-d"), plan.diverged)
        assertEquals(listOf("feat-e"), plan.untracked)
        assertFalse(plan.isEmpty)
    }

    @Test
    fun `没有可自动执行的动作时计划为空`() {
        val plan = planSync(
            listOf(
                local("main", isHead = true),
                local("feat-a", behind = 1),
                local("feat-b", ahead = 1, behind = 1),
            ),
        )

        assertTrue(plan.isEmpty)
        assertNull(plan.toPull)
        assertEquals(listOf("feat-b"), plan.diverged)
    }

    @Test
    fun `空列表计划为空`() {
        assertTrue(planSync(emptyList()).isEmpty)
    }

    // ── 解析 ──

    @Test
    fun `解析本地分支列表`() {
        val json = """
            [{"name":"main","is_head":true,"upstream":"origin/main","ahead":1,"behind":0},
             {"name":"feat","is_head":false,"upstream":"","ahead":0,"behind":0}]
        """.trimIndent()
        val list = parseLocalBranchInfos(json)

        assertEquals(2, list.size)
        assertTrue(list[0].isHead)
        assertEquals("origin/main", list[0].upstream)
        assertEquals(1, list[0].ahead)
        assertFalse(list[1].isHead)
        assertEquals("", list[1].upstream)
        assertEquals(SyncState.Untracked, syncStateOf(list[1]))
    }

    @Test
    fun `解析远端分支列表`() {
        val json = """
            [{"name":"main","local":"main","has_local":true,"ahead":0,"behind":2},
             {"name":"release","local":"","has_local":false,"ahead":0,"behind":0}]
        """.trimIndent()
        val list = parseRemoteBranchInfos(json)

        assertEquals(2, list.size)
        assertEquals("main", list[0].local)
        assertTrue(list[0].hasLocal)
        assertEquals(2, list[0].behind)
        assertFalse(list[1].hasLocal)
    }

    @Test
    fun `解析异常输入降级为空列表`() {
        assertEquals(emptyList<LocalBranchInfo>(), parseLocalBranchInfos(null))
        assertEquals(emptyList<LocalBranchInfo>(), parseLocalBranchInfos(""))
        assertEquals(emptyList<LocalBranchInfo>(), parseLocalBranchInfos("{ 不是数组"))
        assertEquals(emptyList<RemoteBranchInfo>(), parseRemoteBranchInfos(null))
        // 缺 name 的元素被跳过，不影响其它元素
        assertEquals(1, parseLocalBranchInfos("""[{"ahead":1},{"name":"ok"}]""").size)
    }

    @Test
    fun `远端独有分支过滤掉已跟踪与 HEAD`() {
        val remotes = listOf(
            RemoteBranchInfo("main", "main", hasLocal = true, ahead = 0, behind = 0),
            RemoteBranchInfo("release", "", hasLocal = false, ahead = 0, behind = 0),
            RemoteBranchInfo("HEAD", "", hasLocal = false, ahead = 0, behind = 0),
            RemoteBranchInfo("feature/x", "", hasLocal = false, ahead = 0, behind = 0),
        )

        assertEquals(listOf("release", "feature/x"), remoteOnlyBranches(remotes).map { it.name })
    }

    // ── 面板状态快照 ──

    @Test
    fun `本地仓库状态摘要与动作开关`() {
        val missing = LocalRepoGitState(exists = false)
        assertEquals("未拉取到本地", missing.summary())
        assertFalse(missing.needsPush)
        assertFalse(missing.needsPull)

        val dirty = LocalRepoGitState(exists = true, branch = "main", dirtyCount = 2)
        assertEquals("main · 改动 2", dirty.summary())
        assertFalse(dirty.needsPush)

        val ahead = LocalRepoGitState(exists = true, branch = "main", ahead = 3)
        assertEquals("main · 待推送 3", ahead.summary())
        assertTrue(ahead.needsPush)
        assertFalse(ahead.needsPull)

        val behind = LocalRepoGitState(exists = true, branch = "main", behind = 4)
        assertEquals("main · 待拉取 4", behind.summary())
        assertTrue(behind.needsPull)

        val diverged = LocalRepoGitState(exists = true, branch = "main", ahead = 1, behind = 2)
        assertEquals("main · 分叉 ↑1 ↓2", diverged.summary())
        assertTrue(diverged.diverged)
        // 分叉时既不能无脑推送也不能无脑拉取
        assertFalse(diverged.needsPull)

        val synced = LocalRepoGitState(exists = true, branch = "main")
        assertEquals("main · 已同步", synced.summary())
    }
}
