package com.branchbase.ui.main

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主界面「你刚才在哪」的寄存点单测。
 *
 * 背景（用户反馈）：「设置 → 账号管理 → 添加账号」进去后按返回，落在了**个人页主页**，
 * 而不是刚才的账号管理页。原因是「添加账号」的登录页整屏接管时，`state` 一变
 * `LoggedInGate → MainScreen` 整棵子树被销毁，返回时重建、导航状态全丢。
 *
 * 这里钉住的是**消费语义**：寄存点读一次就要空。少了这条，用户下次正常启动还会被拽回
 * 「账号管理」—— 那只是把「落点不对」换了个方向。
 */
class MainNavMemoryTest {

    @After
    fun tearDown() {
        MainNavMemory.resetForTest()
    }

    @Test
    fun `默认没有寄存点`() {
        assertNull(MainNavMemory.consume())
    }

    @Test
    fun `记下之后能取回同样的落点`() {
        val route = MainNavMemory.Route(
            tab = MainNavMemory.MainTab.HOME,
            onProfile = true,
            profileSubPage = "Accounts",
        )
        MainNavMemory.remember(route)
        assertEquals(route, MainNavMemory.consume())
    }

    @Test
    fun `消费一次即清空_否则下次启动会被拽回旧页面`() {
        MainNavMemory.remember(MainNavMemory.Route(onProfile = true, profileSubPage = "Accounts"))
        assertEquals("Accounts", MainNavMemory.consume()?.profileSubPage)
        assertNull("第二次必须取不到 —— 寄存点是一次性的", MainNavMemory.consume())
        assertNull(MainNavMemory.pending)
    }

    @Test
    fun `默认落点是首页且不在个人页`() {
        val route = MainNavMemory.Route()
        assertEquals(MainNavMemory.MainTab.HOME, route.tab)
        assertEquals(false, route.onProfile)
        assertNull(route.profileSubPage)
    }

    @Test
    fun `后写覆盖先写_只保留最近一次落点`() {
        MainNavMemory.remember(MainNavMemory.Route(onProfile = false))
        MainNavMemory.remember(MainNavMemory.Route(onProfile = true, profileSubPage = "Log"))
        val got = MainNavMemory.consume()
        assertEquals(true, got?.onProfile)
        assertEquals("Log", got?.profileSubPage)
    }

    @Test
    fun `可以主动清空_例如目标页面已不存在`() {
        MainNavMemory.remember(MainNavMemory.Route(onProfile = true, profileSubPage = "Accounts"))
        MainNavMemory.clear()
        assertNull(MainNavMemory.consume())
    }

    @Test
    fun `两个 Tab 都能记`() {
        MainNavMemory.remember(MainNavMemory.Route(tab = MainNavMemory.MainTab.MESSAGES))
        assertEquals(MainNavMemory.MainTab.MESSAGES, MainNavMemory.consume()?.tab)
        MainNavMemory.remember(MainNavMemory.Route(tab = MainNavMemory.MainTab.HOME))
        assertEquals(MainNavMemory.MainTab.HOME, MainNavMemory.consume()?.tab)
    }

    @Test
    fun `子页用名字而不是枚举存放_宿主不必依赖 profile 模块的私有枚举`() {
        // profileSubPage 是 String：MainScreen 只做 name → SubPage 的转换，不 import 那个枚举
        val route = MainNavMemory.Route(onProfile = true, profileSubPage = "RepoCredentials")
        assertTrue(route.profileSubPage is String)
    }
}
