package com.branchbase.ui.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 进度弹窗里那两处纯计算的钉子（`CloneProgressDialog.kt`）。
 *
 * 弹窗本身要真机才看得见，但「分子/分母怎么写」是纯函数 —— 而它恰好是最容易写错、
 * 错了又最难发现的地方：分母未知时写成 `0`，界面上就是一句 `142/0`。
 */
class CloneProgressDialogTest {

    @Test
    fun `总数已知时给 已收 比 总数`() {
        assertEquals("142/380", cloneCountText(142, 380))
    }

    @Test
    fun `总数未知时只给分子`() {
        // 引擎在「远端没报总数」时给 0：这里编个分母就是假信息
        assertEquals("142", cloneCountText(142, 0))
        assertEquals("0", cloneCountText(0, 0))
    }
}
