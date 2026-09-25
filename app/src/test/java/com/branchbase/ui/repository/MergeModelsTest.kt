package com.branchbase.ui.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 合并与冲突的**数据层**钉子（阶段 5 的 UI 那半）。
 *
 * 这一族全是「引擎说了什么 → 界面该说什么」之间的翻译，写错的样子都只有真机上点一次才看得出来：
 * 出口认错一个 → 「合并成功」与「停在冲突里」被混成一件事；
 * 冲突类型映射漏一个 → 界面上出现英文枚举名；
 * 「还剩几个」算错 → 明明还有冲突却让人提交合并（引擎会拒绝，但用户已经困惑了）。
 */
class MergeModelsTest {

    // ── merge_branch 的出口 ──

    @Test
    fun `四条出口各认各的，失败保留引擎原话`() {
        assertEquals(
            MergeOutcome.UpToDate,
            parseMergeOutcome("""{"outcome":"up_to_date","branch":"main","head_sha":"a","message":"","conflicts":[]}"""),
        )
        assertEquals(
            MergeOutcome.FastForward("abc"),
            parseMergeOutcome("""{"outcome":"fast_forward","branch":"f","head_sha":"abc","message":"","conflicts":[]}"""),
        )
        assertEquals(
            MergeOutcome.Merged("def", "Merge branch 'f' into main"),
            parseMergeOutcome(
                """{"outcome":"merged","branch":"f","head_sha":"def","message":"Merge branch 'f' into main","conflicts":[]}""",
            ),
        )
        val conflict = parseMergeOutcome(
            """{"outcome":"conflict","branch":"f","head_sha":"aaa","message":"Merge branch 'f'","conflicts":["a.txt","b.txt"]}""",
        )
        assertEquals(MergeOutcome.Conflict("f", "Merge branch 'f'", listOf("a.txt", "b.txt")), conflict)

        // `ERROR:` 前缀**不能吞**：失败原因是界面必须如实显示的一句话
        val failed = parseMergeOutcome("ERROR: 工作区有未提交改动，无法合并")
        assertEquals(MergeOutcome.Failed("工作区有未提交改动，无法合并"), failed)
        // 解析不出来同样落到一条出口（上层只处理一种形状），但不装作成功：
        // reason = null 表示「引擎回了一句看不懂的」，界面用**自己的资源文案**（这一层没有 Context）
        assertEquals(MergeOutcome.Failed(null), parseMergeOutcome("不是 JSON"))
        assertEquals(MergeOutcome.Failed(null), parseMergeOutcome("""{"outcome":"???","branch":"x"}"""))
    }

    // ── merge_state ──

    @Test
    fun `合并状态解析出未解决的清单与类型`() {
        val state = parseMergeState(
            """
            {"merging":true,"branch":"main","head_sha":"h","merge_head_sha":"m",
             "message":"Merge branch 'f'","conflicts":[{"path":"a.txt","kind":"both_modified"},
             {"path":"b.bin","kind":"deleted_by_them"},{"path":"c.txt","kind":"没见过的类型"}]}
            """.trimIndent(),
        )!!
        assertTrue(state.merging)
        assertEquals("main", state.branch)
        assertEquals("m", state.mergeHeadSha)
        assertEquals("Merge branch 'f'", state.message)
        assertEquals(3, state.conflicts.size)
        assertEquals(MergeConflictKind.BOTH_MODIFIED, state.conflicts[0].kind)
        assertEquals(MergeConflictKind.DELETED_BY_THEM, state.conflicts[1].kind)
        // 没见过的类型**不编一个近似的**：这正是「引擎加了新 kind、界面还是旧的」那种情况
        assertEquals(MergeConflictKind.UNKNOWN, state.conflicts[2].kind)

        assertNull(parseMergeState(null))
        assertNull(parseMergeState("  "))
        assertNull(parseMergeState("不是 JSON"))
    }

    @Test
    fun `还剩几个与能不能提交合并`() {
        val merging = parseMergeState("""{"merging":true,"conflicts":[{"path":"a","kind":"both_modified"}]}""")
        assertEquals(1, unresolvedCount(merging))
        // 还有冲突：**不许**让「提交合并」可点（引擎会拒绝，但那时用户已经困惑了）
        assertFalse(canContinueMerge(merging))

        val resolved = parseMergeState("""{"merging":true,"conflicts":[]}""")
        assertEquals(0, unresolvedCount(resolved))
        assertTrue(canContinueMerge(resolved))

        // 不在合并中：同样不许提交（否则会拿到一句「当前没有进行中的合并」）
        val clean = parseMergeState("""{"merging":false,"conflicts":[]}""")
        assertFalse(canContinueMerge(clean))
        assertFalse(canContinueMerge(null))
        assertEquals(0, unresolvedCount(null))
    }

    // ── analyze_conflicts ──

    @Test
    fun `预解析解析出三方内容与差异`() {
        val analysis = parseMergeAnalysis(
            """
            {"files":[{"path":"a.txt","kind":"both_modified","binary":false,
              "ours_sha":"o","theirs_sha":"t","base_sha":"b",
              "ours_size":5,"theirs_size":7,"base_size":5,
              "patch":"@@ -1 +1 @@\n-ours\n+theirs\n","truncated":false,
              "ours":"ours\n","theirs":"theirs\n","worktree":"<<<<<<< HEAD\nours\n=======\ntheirs\n>>>>>>> f\n",
              "content_truncated":false}],"truncated":false}
            """.trimIndent(),
        )!!
        val f = analysis.detailOf("a.txt")!!
        assertEquals(MergeConflictKind.BOTH_MODIFIED, f.kind)
        assertEquals(5, f.oursSize)
        assertEquals("ours\n", f.ours)
        // 手工编辑的初值是**工作区那份（带标记）** —— 这就是它存在的理由
        assertTrue(f.worktree.contains("<<<<<<<"))
        assertFalse(f.contentTruncated)
        assertNull(analysis.detailOf("不存在.txt"))

        // 二进制：patch 为空，界面据此说「请选一边」而不是画一个空 diff
        val bin = parseMergeAnalysis("""{"files":[{"path":"a.bin","kind":"both_modified","binary":true}]}""")!!
        assertTrue(bin.files[0].binary)
        assertEquals("", bin.files[0].patch)

        // 老 `.so` 没有内容字段：缺了就是空串（界面如实说读不到，而不是把空串画成内容）
        val old = parseMergeAnalysis("""{"files":[{"path":"a.txt","kind":"both_modified"}]}""")!!
        assertEquals("", old.files[0].ours)
        assertEquals("", old.files[0].worktree)
        assertNull(parseMergeAnalysis(null))
    }

    // ── 分支清单（合并决策页的选项） ──

    @Test
    fun `分支清单去掉当前分支，本地优先、远端只补本地没有的`() {
        val locals = listOf(
            LocalBranchInfo("main", isHead = true, upstream = "origin/main", ahead = 0, behind = 0),
            LocalBranchInfo("feature", isHead = false, upstream = "origin/feature", ahead = 2, behind = 0),
            LocalBranchInfo("wip", isHead = false, upstream = "", ahead = 0, behind = 0),
        )
        val remotes = listOf(
            RemoteBranchInfo("main", "main", hasLocal = true, ahead = 0, behind = 0),
            RemoteBranchInfo("feature", "feature", hasLocal = true, ahead = 0, behind = 0),
            RemoteBranchInfo("someone-else", "", hasLocal = false, ahead = 0, behind = 0),
        )
        val options = mergeBranchOptionsOf(locals, remotes)
        // 当前分支不进清单（合并自己是空动作）；本地在前、按名字排
        assertEquals(listOf("feature", "wip", "someone-else"), options.map { it.name })
        assertEquals("↑2", options[0].badge)
        assertNull("未跟踪的分支照样能合（合的是提交，不是上游）", options[1].badge)
        assertEquals("", options[1].upstream)
        // 「只在远端」的那一条：本地没有同名分支才出现
        assertTrue(options[2].isRemote)
        assertEquals(1, options.count { it.isRemote })
        assertTrue(options.none { it.name == "main" })
    }

    @Test
    fun `分叉的分支要被标出来，且远端清单里不出现 HEAD`() {
        val locals = listOf(
            LocalBranchInfo("main", isHead = true, upstream = "origin/main", ahead = 0, behind = 0),
            LocalBranchInfo("diverged", isHead = false, upstream = "origin/diverged", ahead = 3, behind = 4),
        )
        val options = mergeBranchOptionsOf(locals, listOf(RemoteBranchInfo("HEAD", "", false, 0, 0)))
        assertEquals(1, options.size)
        assertTrue(options[0].diverged)
        assertEquals("↑3 ↓4", options[0].badge)
    }

    // ── 预选（入口 ②：PR 冲突「拉到本地解决」给的是 PR 的 head） ──

    @Test
    fun `预选的分支已在清单里时不重复补一条`() {
        val locals = listOf(LocalBranchInfo("main", isHead = true, upstream = "origin/main", ahead = 0, behind = 0))
        val remotes = listOf(RemoteBranchInfo("feature", "feature", hasLocal = false, ahead = 0, behind = 0))
        val options = mergeBranchOptionsOf(locals, remotes, preferred = "feature")
        assertEquals("已经在清单里，不许再补一条重的", listOf("feature"), options.map { it.name })
        assertTrue(options[0].isRemote)
    }

    @Test
    fun `预选的分支本地与远端都没有时补一条只在远端的`() {
        // 这是入口 ② 的常态：PR 的 head 从没 fetch 过，本地清单里根本没有它。
        // 不补的话，用户点「拉到本地解决」打开的合并页里没有那条分支可选 ——
        // 而引擎的 merge_branch 本来就会自己 fetch 一次
        val locals = listOf(LocalBranchInfo("main", isHead = true, upstream = "origin/main", ahead = 0, behind = 0))
        val remotes = listOf(RemoteBranchInfo("other", "", hasLocal = false, ahead = 0, behind = 0))
        val options = mergeBranchOptionsOf(locals, remotes, preferred = "pr-head")
        assertEquals(listOf("pr-head", "other"), options.map { it.name })
        assertTrue("补出来的那一条按「只在远端」处理（引擎会先 fetch）", options[0].isRemote)
        assertEquals("", options[0].upstream)
        assertNull(options[0].badge)
    }

    @Test
    fun `预选等于当前分支或为空时不补`() {
        val locals = listOf(LocalBranchInfo("main", isHead = true, upstream = "origin/main", ahead = 0, behind = 0), LocalBranchInfo("wip", isHead = false, upstream = "", ahead = 0, behind = 0))
        // 预选 = 当前分支：合自己必然是「已包含对方」，补出来只会是一个空动作
        assertEquals(listOf("wip"), mergeBranchOptionsOf(locals, emptyList(), preferred = "main").map { it.name })
        assertEquals(listOf("wip"), mergeBranchOptionsOf(locals, emptyList(), preferred = "  ").map { it.name })
        assertEquals(listOf("wip"), mergeBranchOptionsOf(locals, emptyList(), preferred = null).map { it.name })
    }
}
