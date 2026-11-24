package com.branchbase.ui.decision

import com.branchbase.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 决策页**选项可用性预检**的单测（被测纯函数在 `SyncDecisionScreens.kt` 顶部）。
 *
 * 为什么值得钉：这几个判定就是「用户点下去，引擎会不会**必然**失败」——
 *   · `reset_soft` 取 `parent_id(0)`：HEAD 是初始提交时没有父提交，必然报错
 *     （`core/src/git/mod.rs:678-697`）；
 *   · `reset_hard_to_remote` 要求 `refs/remotes/origin/{branch}` 已存在（`:698-717`），
 *     从没 fetch 过的仓库必然报错。
 * 判错了不会崩：只会让用户在「点了必失败」的选项上白撞一次，然后看到一句
 * 「引擎不可用」（旧文案）—— 把可提前判定的事实说成引擎故障。另一半是 [failureMessage]：
 * 钉住「判不出原因时只写中性说法」，不许再一律归因引擎。
 */
class SyncDecisionPrecheckTest {

    // ─────────────── reset --soft：初始提交没有父提交 ───────────────

    @Test
    fun reset_soft_有父提交时可用() {
        assertNull(resetSoftBlockReason(hasParent = true))
    }

    @Test
    fun reset_soft_初始提交时被拦下且原因写明第一个提交() {
        // 钉资源 ID + 参数，不钉会被搬走的字面量（i18n-migration §9）
        val reason = resetSoftBlockReason(hasParent = false)
        assertNotNull("初始提交必须被拦下（reset --soft HEAD~1 必然失败）", reason)
        assertEquals("要用「第一个提交」那一条原因", R.string.undo_blocked_initial, reason!!.res)
    }

    // ────────── reset --hard origin/x：需要远端 ref 已存在 ──────────

    @Test
    fun reset_hard_有父提交且有远端_ref_时可用() {
        assertNull(resetHardBlockReason(hasParent = true, hasRemoteRef = true, branch = "main"))
    }

    @Test
    fun reset_hard_缺远端_ref_时被拦下并指明是哪个_ref() {
        val reason = resetHardBlockReason(hasParent = true, hasRemoteRef = false, branch = "feature/x")
        assertNotNull(reason)
        assertEquals(R.string.undo_blocked_no_remote_ref, reason!!.res)
        // 分支名作为 %1$s 参数带上（句子里的 origin/%1$s 由资源决定）
        assertEquals(listOf("feature/x"), reason.args)
    }

    @Test
    fun reset_hard_初始提交时被拦下() {
        val reason = resetHardBlockReason(hasParent = false, hasRemoteRef = true, branch = "main")
        assertNotNull(reason)
        assertEquals(R.string.undo_blocked_initial_only, reason!!.res)
    }

    @Test
    fun reset_hard_两条原因同时成立时都要说出来() {
        val reason = resetHardBlockReason(hasParent = false, hasRemoteRef = false, branch = "main")
        assertNotNull(reason)
        // 两条同时成立时给「合起来」的那一条，并带上分支名
        assertEquals(R.string.undo_blocked_initial_and_no_ref, reason!!.res)
        assertEquals(listOf("main"), reason.args)
    }

    @Test
    fun 分支名为空时退回_main_detached_HEAD_的_branch_是空串() {
        val hard = resetHardBlockReason(hasParent = true, hasRemoteRef = false, branch = "")
        assertEquals("空分支名要退回 main", listOf("main"), hard!!.args)
        val discard = discardLocalBlockReason(hasRemoteRef = false, branch = "   ")
        assertEquals("纯空白分支名同样退回 main", listOf("main"), discard!!.args)
    }

    // ─────────────── 分叉页「放弃本地提交」 ───────────────

    @Test
    fun 放弃本地提交_有远端_ref_时可用() {
        assertNull(discardLocalBlockReason(hasRemoteRef = true, branch = "main"))
    }

    @Test
    fun 放弃本地提交_没有远端_ref_时被拦下且不给引擎归因() {
        val reason = discardLocalBlockReason(hasRemoteRef = false, branch = "main")
        assertNotNull(reason)
        assertEquals(R.string.discard_blocked_no_remote_ref, reason!!.res)
        assertEquals(listOf("main"), reason.args)
    }

    // ─────────────── 分叉页「合并远端」（1.1.1） ───────────────

    @Test
    fun 合并远端_干净工作区且不在合并中且不是浅克隆时可用() {
        assertNull(mergeRemoteBlockReason(dirtyCount = 0, merging = false, shallow = false, branch = "main"))
    }

    @Test
    fun 合并远端_工作区脏时被拦下并说清几处改动() {
        val reason = mergeRemoteBlockReason(dirtyCount = 3, merging = false, shallow = false, branch = "main")
        assertNotNull(reason)
        assertEquals(R.string.merge_blocked_dirty, reason!!.res)
        assertEquals(listOf(3, "main"), reason.args)
    }

    @Test
    fun 合并远端_停在别的合并里时先说清怎么收尾() {
        // 已经在合并中时工作区必然是脏的 —— 这一条必须排在「工作区脏」之前，
        // 否则用户会去提交那些冲突文件（而那时该做的是「提交合并」或「放弃合并」）
        val reason = mergeRemoteBlockReason(dirtyCount = 2, merging = true, shallow = false, branch = "main")
        assertNotNull(reason)
        assertEquals("在合并中必须命中「先收尾」那一条，而不是「工作区脏」", R.string.merge_blocked_merging, reason!!.res)
    }

    @Test
    fun 合并远端_浅克隆时指出去加深() {
        val reason = mergeRemoteBlockReason(dirtyCount = 0, merging = false, shallow = true, branch = "main")
        assertNotNull(reason)
        assertEquals(R.string.merge_blocked_shallow, reason!!.res)
    }

    @Test
    fun 合并远端_分支名为空时退回_main() {
        val reason = mergeRemoteBlockReason(dirtyCount = 0, merging = false, shallow = true, branch = "")
        assertEquals("空分支名要退回 main", listOf("main"), reason!!.args)
        val blank = mergeRemoteBlockReason(dirtyCount = 0, merging = false, shallow = true, branch = "   ")
        assertEquals("纯空白分支名同样退回 main", listOf("main"), blank!!.args)
    }

    // ─────────────── 未推送清单的截断（不许画成「一共就这些」） ───────────────

    @Test
    fun 未推送清单_截断的条数要算得出来且不为负() {
        // 引擎的 unpushed 最多 50 条，ahead 是全量
        assertEquals(0, unlistedUnpushedCount(ahead = 3, listed = 3))
        assertEquals(0, unlistedUnpushedCount(ahead = 50, listed = 50))
        assertEquals(150, unlistedUnpushedCount(ahead = 200, listed = 50))
        // 防御：清单比 ahead 还长（引擎换了口径 / 字段缺省）时不许显示负数
        assertEquals(0, unlistedUnpushedCount(ahead = 0, listed = 5))
    }

    // ─────────────── 失败文案：不许编造归因 ───────────────

    @Test
    fun 失败文案_判不出原因时只写中性说法() {
        val msg = failureMessage("放弃本地提交")
        // 「中性说法」= 不带原因的那一条资源；动作名作为参数带上
        assertEquals(R.string.error_action_failed, msg.res)
        assertEquals(listOf("放弃本地提交"), msg.args)
    }

    @Test
    fun 失败文案_能判定原因时把原因写进去() {
        val msg = failureMessage("撤销提交", "这是仓库的第一个提交，没有可撤销的上一次提交")
        assertEquals(R.string.error_action_failed_reason, msg.res)
        assertEquals(listOf("撤销提交", "这是仓库的第一个提交，没有可撤销的上一次提交"), msg.args)
    }

    @Test
    fun 失败文案_空白原因视为判不出() {
        assertEquals(R.string.error_action_failed, failureMessage("推送", "   ").res)
        assertEquals(R.string.error_action_failed, failureMessage("推送", "").res)
        assertEquals(R.string.error_action_failed, failureMessage("推送", null).res)
    }

    // ─────────────── revert 形态标题：不许把「不知道」说成「已推送」 ───────────────

    @Test
    fun revert_形态标题_有上游无未推送时才敢说已推送() {
        assertEquals(
            R.string.revert_title_pushed,
            revertFormTitle(hasStatus = true, hasUpstream = true).res,
        )
    }

    @Test
    fun revert_形态标题_没有上游时不下已推送的结论() {
        val title = revertFormTitle(hasStatus = true, hasUpstream = false)
        // 没有上游分支时不能复用「已推送」那一条，必须是「无法判断」那一条
        assertEquals(R.string.revert_title_no_upstream, title.res)
    }

    @Test
    fun revert_形态标题_读不到状态时标明状态未知() {
        val title = revertFormTitle(hasStatus = false, hasUpstream = false)
        assertEquals(R.string.revert_title_status_unknown, title.res)
    }
}
