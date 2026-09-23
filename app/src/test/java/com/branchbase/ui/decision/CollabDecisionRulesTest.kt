package com.branchbase.ui.decision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR 一条龙 + 仓库设置页的**纯判据**单测（`CollabScreens.kt` 末尾那组函数）。
 *
 * 为什么值得钉：这些函数决定「界面说了什么」和「实际做了什么」是否一致 ——
 * 算错了不会崩，只会让用户对着一句不成立的话点下不可逆的按钮：
 * - 空改动清单放行 → 开出一个 diff 为空的 PR（这次要消灭的假象）
 * - 分支重名没查 → 直接对着别人的分支提交
 * - 合并状态猜错 → 「已合并」的分支其实有独有提交，删了就找不回
 * - 统计缺字段当成 0 → 又变回写死的假数字
 */
class CollabDecisionRulesTest {

    // ───────────────────── branchNameError（第①步校验） ─────────────────────

    @Test
    fun 分支名校验_空与空格被拒() {
        assertEquals("分支名不能为空", branchNameError("", listOf("main")))
        assertEquals("分支名不能为空", branchNameError("   ", emptyList()))
        assertEquals("分支名不能含空格", branchNameError("patch 1", listOf("main")))
    }

    @Test
    fun 分支名校验_重名按传入清单判() {
        val existing = listOf("main", "patch-1", "release/1.x")
        assertEquals("分支 patch-1 已存在，请换一个名字", branchNameError("patch-1", existing))
        assertNull(branchNameError("patch-2", existing))
        // 大小写不同在 Git 里也是不同 ref —— 不替用户做「看起来像」的拦截
        assertNull(branchNameError("Patch-1", existing))
    }

    @Test
    fun 分支名校验_宿主没给清单时只校验格式() {
        // 关键：拿不到清单就必须放行到「不查重」而不是假装查过（界面文案同步写「本次不查重」）
        assertNull(branchNameError("patch-1", emptyList()))
    }

    // ───────────────────── commitBlockReason（第②步准入） ─────────────────────

    @Test
    fun 空清单必须拦住第二步() {
        val reason = commitBlockReason(emptyList(), "chore: 提交")
        assertEquals(NO_CHANGES_HINT, reason)
        assertTrue(reason!!.contains("代码"))
    }

    @Test
    fun 空提交信息也拦住() {
        assertEquals("请填写提交信息", commitBlockReason(listOf("a.txt"), "   "))
    }

    @Test
    fun 有文件有信息才放行() {
        assertNull(commitBlockReason(listOf("a.txt"), "feat: x"))
    }

    // ───────────────────── prTitleAfterCommit ─────────────────────

    @Test
    fun 标题没被手改时跟提交信息走() {
        assertEquals("feat: 新标题", prTitleAfterCommit("旧默认值", titleEdited = false, commitMessage = "feat: 新标题"))
    }

    @Test
    fun 标题被手改过就不动它() {
        assertEquals("我自己写的标题", prTitleAfterCommit("我自己写的标题", titleEdited = true, commitMessage = "feat: 新标题"))
    }

    // ───────────────────── contentResolutionOf（内容解析） ─────────────────────

    @Test
    fun 内容全齐才可提交() {
        val r = contentResolutionOf(listOf("a.txt" to "A", "b/c.txt" to ""))
        assertTrue(r is ContentResolution.Ready)
        // 空文件内容是合法内容（空串），不能当成「读不到」
        assertEquals(listOf("a.txt" to "A", "b/c.txt" to ""), (r as ContentResolution.Ready).files)
    }

    @Test
    fun 有一个读不到就整体不提交并点名() {
        val r = contentResolutionOf(listOf("a.txt" to "A", "b.txt" to null, "c.txt" to null))
        assertTrue(r is ContentResolution.Missing)
        assertEquals(listOf("b.txt", "c.txt"), (r as ContentResolution.Missing).paths)
    }

    @Test
    fun 空清单解析成空列表而不是崩溃() {
        val r = contentResolutionOf(emptyList())
        assertTrue(r is ContentResolution.Ready)
        assertTrue((r as ContentResolution.Ready).files.isEmpty())
    }

    // ───────────────────── branchReuseOf（建分支撞名） ─────────────────────

    @Test
    fun 分支撞名只在停在_base_时复用() {
        assertEquals(BranchReuse.ABSENT, branchReuseOf(null, "sha-base"))
        assertEquals(BranchReuse.SAME_AS_BASE, branchReuseOf("sha-base", "sha-base"))
        // 已含别的提交 → 不能悄悄往上叠
        assertEquals(BranchReuse.DIVERGED, branchReuseOf("sha-other", "sha-base"))
    }

    // ───────────────────── mergeStateOf / mergeStateLabel ─────────────────────

    @Test
    fun 合并状态_查不到就是查不到() {
        assertEquals(MergeState.Unknown, mergeStateOf(null))
        assertEquals("检查失败", mergeStateLabel(MergeState.Unknown))
        assertEquals("未检查", mergeStateLabel(MergeState.Unchecked))
    }

    @Test
    fun 合并状态_ahead_为零才算已合并() {
        assertEquals(MergeState.Merged, mergeStateOf(0))
        assertEquals("已合并", mergeStateLabel(MergeState.Merged))
        assertEquals(MergeState.Ahead(3), mergeStateOf(3))
        assertEquals("3 个独有提交", mergeStateLabel(MergeState.Ahead(3)))
        // 负数不该出现；真出现也只能说明「没有独有提交」，不能当成失败
        assertEquals(MergeState.Merged, mergeStateOf(-1))
    }

    @Test
    fun 解析_ahead_by_缺键不当成零() {
        assertEquals(0, parseAheadBy("""{"ahead_by":0,"behind_by":2,"status":"identical"}"""))
        assertEquals(5, parseAheadBy("""{"ahead_by":5}"""))
        // 缺键 = 未知：默认成 0 会把「不知道」显示成「已合并」
        assertNull(parseAheadBy("""{"behind_by":2}"""))
        assertNull(parseAheadBy("不是 JSON"))
        assertNull(parseAheadBy(""))
        assertNull(parseAheadBy(null))
    }

    @Test
    fun 要检查的分支排除默认分支并受上限约束() {
        val all = listOf("main", "a", "b", "c")
        assertEquals(listOf("a", "b"), branchesToCheck(all, "main", limit = 2))
        assertEquals(listOf("a", "b", "c"), branchesToCheck(all, "main", limit = 20))
        assertTrue(branchesToCheck(listOf("main"), "main", 20).isEmpty())
        assertEquals(MERGE_CHECK_LIMIT, 20)
    }

    // ───────────────────── repoStatsSummary / parseRepoStats ─────────────────────

    @Test
    fun 统计一个维度都没有时不显示这一行() {
        assertNull(repoStatsSummary(null))
        assertNull(repoStatsSummary(RepoStats()))
    }

    @Test
    fun 统计只显示真拿到手的维度() {
        assertEquals("⭐ 12 星标 · 🍴 3 复刻", repoStatsSummary(RepoStats(stars = 12, forks = 3)))
        assertEquals("128 提交", repoStatsSummary(RepoStats(commits = 128)))
        assertEquals(
            "⭐ 1 星标 · 🍴 2 复刻 · 3 提交 · 4 贡献者",
            repoStatsSummary(RepoStats(stars = 1, forks = 2, commits = 3, contributors = 4)),
        )
    }

    @Test
    fun 解析仓库统计_缺键是未知不是零() {
        val s = parseRepoStats("""{"full_name":"o/r","stargazers_count":12,"forks_count":3}""")
        assertEquals(12L, s?.stars)
        assertEquals(3L, s?.forks)
        assertNull(s?.commits)
        assertNull(s?.contributors)

        // 端点没给这两个键 → 维度留空（0 星标和「没拿到」是两件事）
        val empty = parseRepoStats("""{"full_name":"o/r"}""")
        assertNull(empty?.stars)
        assertNull(empty?.forks)

        assertNull(parseRepoStats("不是 JSON"))
        assertNull(parseRepoStats(""))
        assertNull(parseRepoStats(null))
    }
}
