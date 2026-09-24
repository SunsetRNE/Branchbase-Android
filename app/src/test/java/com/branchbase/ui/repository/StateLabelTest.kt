package com.branchbase.ui.repository

import com.branchbase.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `stateLabelResOrNull` 的分类钉子。
 *
 * ## 为什么钉**资源 ID** 而不是文案
 *
 * 这个函数以前直接返回中文（`"开启"` / `"已关闭"` / `"已合并"` / `"草稿"`），
 * 于是 Issue/PR 列表里「星标者 / 分支 / 提交」都是中文，唯独状态是英文 —— 它压根没走资源。
 *
 * 改成返回 `Int?` 之后，测试断言资源 ID：既钉住「哪个状态走哪条分支」这个**语义**，
 * 又不会在改措辞、再抽取一次的时候假红。钉字面量的测试每抽取一次假红一次，
 * 见 `docs/specs/i18n-migration.md` 的坑表。
 *
 * ## 未知状态必须是 null
 *
 * 调用方 `stateLabelOf` 据此**原样透出**后端的原始值。后端新增 `queued` 这类状态时，
 * 列表里应该显示 `queued` 本身，而不是空一格、也不是猜成「开启」。
 */
class StateLabelTest {

    @Test
    fun `已知状态各自映射到自己的资源`() {
        assertEquals(R.string.state_open, stateLabelResOrNull("open"))
        assertEquals(R.string.state_closed, stateLabelResOrNull("closed"))
        assertEquals(R.string.state_merged, stateLabelResOrNull("merged"))
        assertEquals(R.string.state_draft, stateLabelResOrNull("draft"))
    }

    @Test
    fun `状态匹配不区分大小写`() {
        assertEquals(R.string.state_open, stateLabelResOrNull("OPEN"))
        assertEquals(R.string.state_merged, stateLabelResOrNull("Merged"))
        assertEquals(R.string.state_draft, stateLabelResOrNull("DrAfT"))
    }

    @Test
    fun `未知状态返回 null 交给调用方原样透出`() {
        assertNull(stateLabelResOrNull("queued"))
        assertNull(stateLabelResOrNull(""))
    }
}
