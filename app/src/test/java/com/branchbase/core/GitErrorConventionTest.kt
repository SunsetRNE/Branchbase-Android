package com.branchbase.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Git 写操作包装的**错误约定**钉子：`null` = 成功，非空 = 失败原因。
 *
 * 背景（真机上看得见的那种坏）：`gitResetSoft` / `gitResetHardRemote` / `gitAmend` 曾经返回
 * `Boolean`，把 native 的 `ERROR:…` 文本就地丢掉。决策页只能显示
 * 「XXX 失败（原因见日志）」—— 而**日志里根本没有那条原因**（谁都没记）。
 * 用户拿到的是「失败了，但不知道为什么」，也没法自己处理。
 *
 * 现在统一成可透传原因（与 `gitPullDetailed` / `gitPushDetailed` / `gitPushSetUpstream` 一致），
 * 并由 `failureMessage(action, reason)` 拼进反馈行。这个测试钉住归一化的三条边界：
 * 成功（空串）→ null、失败去掉前缀、超长截断。
 */
class GitErrorConventionTest {

    @Test
    fun `成功返回 null`() {
        assertNull("native 成功时返回空串，包装必须折成 null", engineErrorOrNull(""))
    }

    @Test
    fun `失败去掉 ERROR 前缀后透传原因`() {
        assertEquals("nff: 推送被拒（远端领先）", engineErrorOrNull("ERROR:nff: 推送被拒（远端领先）"))
        assertEquals("找不到 refs/remotes/origin/main", engineErrorOrNull("ERROR:找不到 refs/remotes/origin/main"))
    }

    @Test
    fun `超长原因截断到 300 字符`() {
        val long = "ERROR:" + "x".repeat(500)
        assertEquals(300, engineErrorOrNull(long)?.length)
    }

    @Test
    fun `不是 ERROR 开头的返回按成功处理`() {
        // native 的写操作成功返回空串；非空且不以 ERROR: 开头的返回属于「非预期但不算失败」，
        // 与旧 Boolean 实现的判定保持一致（!startsWith("ERROR:")），避免行为漂移
        assertNull(engineErrorOrNull("unexpected-but-not-an-error"))
    }
}
