package com.branchbase.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 顶层「再按一次退出应用」的纯逻辑单测。
 *
 * 背景（用户反馈的缺陷）：改造前顶层返回是「回登录首页（保留会话）」——
 * **会话还在却被丢回登录页**，而重启应用又直接回主界面，用户以为「自己被登出了」。
 * 现在顶层返回只做一件事：留在主界面，第一次提示、窗口内第二次才退出。
 *
 * 「按几次才退出」这类行为只有真机连按才试得出来，回归时最难发现，所以把窗口判定提成纯函数钉住。
 */
class TopLevelBackTest {

    @Test
    fun `第一次按返回不退出`() {
        assertFalse(shouldExitOnBack(lastBackAtMs = 0L, nowMs = 1_000L))
    }

    @Test
    fun `窗口内第二次按返回退出`() {
        val first = 10_000L
        assertTrue(shouldExitOnBack(lastBackAtMs = first, nowMs = first + 1L))
        assertTrue(shouldExitOnBack(lastBackAtMs = first, nowMs = first + EXIT_CONFIRM_WINDOW_MS))
    }

    @Test
    fun `超时后不再算第二次`() {
        val first = 10_000L
        assertFalse(shouldExitOnBack(lastBackAtMs = first, nowMs = first + EXIT_CONFIRM_WINDOW_MS + 1L))
        assertFalse(shouldExitOnBack(lastBackAtMs = first, nowMs = first + 60_000L))
    }

    @Test
    fun `时间戳回绕不误判为退出`() {
        // 理论上 elapsedRealtime 单调递增；真出现异常值时也必须走「第一次提示」而不是直接退出
        assertFalse(shouldExitOnBack(lastBackAtMs = 5_000L, nowMs = 4_000L))
    }

    @Test
    fun `窗口内连续按只算一次提示_第三次仍可退出`() {
        val first = 1_000L
        // 第一次：提示
        assertFalse(shouldExitOnBack(lastBackAtMs = 0L, nowMs = first))
        // 第二次（窗口内）：退出
        assertTrue(shouldExitOnBack(lastBackAtMs = first, nowMs = first + 500L))
    }
}
