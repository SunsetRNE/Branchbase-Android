package com.branchbase.ui.decision

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
        val reason = resetSoftBlockReason(hasParent = false)
        assertNotNull("初始提交必须被拦下（reset --soft HEAD~1 必然失败）", reason)
        assertTrue("要说清「第一个提交」这个事实：$reason", reason!!.contains("第一个提交"))
        assertFalse("不许归因引擎：$reason", reason.contains("引擎不可用"))
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
        assertTrue("要指明缺哪个 ref：$reason", reason!!.contains("origin/feature/x"))
        assertTrue("要给出下一步（先获取）：$reason", reason.contains("获取"))
        assertFalse("不许归因引擎：$reason", reason.contains("引擎不可用"))
    }

    @Test
    fun reset_hard_初始提交时被拦下() {
        val reason = resetHardBlockReason(hasParent = false, hasRemoteRef = true, branch = "main")
        assertNotNull(reason)
        assertTrue("要说清「第一个提交」：$reason", reason!!.contains("第一个提交"))
    }

    @Test
    fun reset_hard_两条原因同时成立时都要说出来() {
        val reason = resetHardBlockReason(hasParent = false, hasRemoteRef = false, branch = "main")
        assertNotNull(reason)
        assertTrue("第一条（初始提交）：$reason", reason!!.contains("第一个提交"))
        assertTrue("第二条（缺远端 ref）：$reason", reason.contains("origin/main"))
    }

    @Test
    fun 分支名为空时退回_main_detached_HEAD_的_branch_是空串() {
        val hard = resetHardBlockReason(hasParent = true, hasRemoteRef = false, branch = "")
        assertTrue("空分支名要退回 main：$hard", hard!!.contains("origin/main"))
        val discard = discardLocalBlockReason(hasRemoteRef = false, branch = "   ")
        assertTrue("纯空白分支名同样退回 main：$discard", discard!!.contains("origin/main"))
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
        assertTrue("要指明缺哪个 ref：$reason", reason!!.contains("origin/main"))
        assertTrue("要给出下一步（先获取）：$reason", reason.contains("获取"))
        assertFalse("不许归因引擎：$reason", reason.contains("引擎不可用"))
    }

    // ─────────────── 失败文案：不许编造归因 ───────────────

    @Test
    fun 失败文案_判不出原因时只写中性说法() {
        val msg = failureMessage("放弃本地提交")
        assertEquals("放弃本地提交失败（原因见日志）", msg)
        assertFalse("不许再归因引擎：$msg", msg.contains("引擎不可用"))
    }

    @Test
    fun 失败文案_能判定原因时把原因写进去() {
        val msg = failureMessage("撤销提交", "这是仓库的第一个提交，没有可撤销的上一次提交")
        assertTrue("要带上是哪个动作：$msg", msg.startsWith("撤销提交失败："))
        assertTrue("要带上原因：$msg", msg.contains("第一个提交"))
        assertFalse("不许归因引擎：$msg", msg.contains("引擎不可用"))
    }

    @Test
    fun 失败文案_空白原因视为判不出() {
        assertEquals("推送失败（原因见日志）", failureMessage("推送", "   "))
        assertEquals("推送失败（原因见日志）", failureMessage("推送", ""))
        assertEquals("推送失败（原因见日志）", failureMessage("推送", null))
    }

    // ─────────────── revert 形态标题：不许把「不知道」说成「已推送」 ───────────────

    @Test
    fun revert_形态标题_有上游无未推送时才敢说已推送() {
        assertEquals(
            "已推送提交 · 不可改写历史",
            revertFormTitle(hasStatus = true, hasUpstream = true),
        )
    }

    @Test
    fun revert_形态标题_没有上游时不下已推送的结论() {
        val title = revertFormTitle(hasStatus = true, hasUpstream = false)
        assertFalse("没有上游分支时说「已推送」就是误报：$title", title.contains("已推送提交"))
        assertTrue("要说清只是无法判断：$title", title.contains("无法判断"))
    }

    @Test
    fun revert_形态标题_读不到状态时标明状态未知() {
        val title = revertFormTitle(hasStatus = false, hasUpstream = false)
        assertTrue("读不到状态要标明未知：$title", title.contains("未知"))
        assertFalse("不许替用户下结论：$title", title.contains("已推送提交"))
    }
}
