package com.branchbase.ui.auth

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「新增账号」流程的标记单测。
 *
 * 它修的是用户报的「点添加账号后回不去账号页」：原先那个按钮直接接 `onLogout`，
 * 于是点一下＝**把自己登出**（全局 `session` 被删、状态回欢迎页），而欢迎页刻意不拦返回键
 * （未登录时是「再按一次退出 App」）—— 既回不到账号页，按返回还可能直接退出应用。
 *
 * 这个标记只回答「当前登录界面是为了新增一个号，还是因为本来就没登录」。
 * 它**不是**登录态（登录态只有 `session` 键与 `LoginState`），因此两条收尾路径都必须成立，
 * 否则会把用户永久留在登录页。
 */
class AddAccountFlowTest {

    @After
    fun tearDown() {
        // 进程内单例不该在测试之间串味
        AddAccountFlow.resetForTest()
    }

    @Test
    fun `默认不在新增流程里`() {
        assertFalse("启动时不该处于新增流程", AddAccountFlow.active)
    }

    @Test
    fun `begin 之后进入新增流程`() {
        AddAccountFlow.begin()
        assertTrue(AddAccountFlow.active)
    }

    @Test
    fun `finish 之后退出新增流程_这是用户按返回的出口`() {
        AddAccountFlow.begin()
        AddAccountFlow.finish()
        assertFalse(AddAccountFlow.active)
    }

    @Test
    fun `finish 幂等_登录成功与用户返回可能几乎同时发生`() {
        AddAccountFlow.begin()
        AddAccountFlow.finish()
        AddAccountFlow.finish()
        assertFalse(AddAccountFlow.active)
    }

    @Test
    fun `没进过流程时 finish 也无害`() {
        AddAccountFlow.finish()
        assertFalse(AddAccountFlow.active)
    }

    @Test
    fun `可以反复进出_登完一个再登一个`() {
        AddAccountFlow.begin()
        AddAccountFlow.finish()
        AddAccountFlow.begin()
        assertTrue("第二次进入同样要生效", AddAccountFlow.active)
        AddAccountFlow.finish()
        assertFalse(AddAccountFlow.active)
    }

    @Test
    fun `activeState 与 active 同步_根布局读的是前者`() {
        // 根布局必须读 activeState（可观察），读普通 getter 不会订阅变化 →
        // 登录成功清标记那一下不触发重组，界面会卡在登录页
        AddAccountFlow.begin()
        assertTrue(AddAccountFlow.activeState.value)
        AddAccountFlow.finish()
        assertFalse(AddAccountFlow.activeState.value)
    }
}
