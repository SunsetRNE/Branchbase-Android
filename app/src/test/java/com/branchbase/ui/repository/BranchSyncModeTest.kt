package com.branchbase.ui.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「同步方式三选一，哪些真的可选」单测。
 *
 * 需求：模式不该是一个永远可点的单选组 —— 比较预览早就把结论算出来了，界面却不管：
 * 「目标已是最新」时三种模式照样能选、主按钮照样能点；「目标有独有提交」时「仅快进」必然被
 * 服务端拒绝（目标不是源的祖先），用户点了才看到失败。
 *
 * 语义依据 `GET /compare/{base}...{head}`：`ahead_by` = 源领先目标的提交数，
 * `behind_by` = 目标独有的提交数（= 覆盖会丢掉的提交数）。
 *
 * 判定收进 `syncModeAvailability`（纯函数），三种预览结论各钉一例；
 * 再加一条「结果还没拿到」的兜底，确认它保持可点而不是全禁。
 */
class BranchSyncModeTest {

    private fun compare(ahead: Int, behind: Int) =
        CompareInfo(aheadBy = ahead, behindBy = behind, status = "whatever")

    @Test
    fun `比较结果还没拿到时不拦`() {
        val m = syncModeAvailability(null)
        assertTrue("离线 / 限流时页面不能整体点不动：合并要可用", m.merge)
        assertTrue("仅快进要可用", m.fastForward)
        assertTrue("覆盖要可用", m.overwrite)
        assertFalse("结果未知不等于「无需同步」", m.nothingToSync)
    }

    @Test
    fun `目标已是最新时三种模式全禁`() {
        val m = syncModeAvailability(compare(ahead = 0, behind = 0))
        assertFalse("没有任何领先提交，合并没有意义", m.merge)
        assertFalse("没有领先提交，快进没有意义", m.fastForward)
        assertFalse("没有领先提交，覆盖没有意义", m.overwrite)
        assertTrue("主按钮据此置灰，文案换成「目标已是最新，无需同步」", m.nothingToSync)
    }

    @Test
    fun `目标领先源时同样全禁`() {
        // ahead=0 且 behind>0：目标比源还新，同步方向上没有任何事可做
        val m = syncModeAvailability(compare(ahead = 0, behind = 3))
        assertTrue("同样是「无需同步」", m.nothingToSync)
    }

    @Test
    fun `目标没有独有提交时三种模式都可用`() {
        val m = syncModeAvailability(compare(ahead = 5, behind = 0))
        assertTrue(m.merge)
        assertTrue("behind=0 说明目标是源的祖先，快进成立", m.fastForward)
        assertTrue("没有独有提交可丢，覆盖也不危险", m.overwrite)
        assertFalse(m.nothingToSync)
    }

    @Test
    fun `目标有独有提交时只有仅快进被禁`() {
        val m = syncModeAvailability(compare(ahead = 5, behind = 2))
        assertTrue("合并照旧可用", m.merge)
        assertFalse("目标不是源的祖先，快进必被服务端拒绝（422）", m.fastForward)
        assertTrue("覆盖正是这个场景的出口（红色 + 二次确认）", m.overwrite)
        assertFalse("有内容可同步，主按钮不能置灰", m.nothingToSync)
    }
}
