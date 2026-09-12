package com.branchbase.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主题档位的单测。
 *
 * 三档（跟随系统 / 浅色 / 深色）里最容易写错的三件事：
 * 1. `SYSTEM` 必须**真的**跟随系统，而不是被当成浅色；
 * 2. 循环顺序要让「太阳图标」每次点击都有可预期的变化；
 * 3. 脏值要能容错（老版本没有这个键、或被手改过）。
 */
class ThemeModeTest {

    @Test
    fun `跟随系统时结果取决于系统`() {
        assertTrue(ThemeMode.SYSTEM.resolveDark(systemDark = true))
        assertFalse(ThemeMode.SYSTEM.resolveDark(systemDark = false))
    }

    @Test
    fun `强制档位忽略系统`() {
        assertFalse(ThemeMode.LIGHT.resolveDark(systemDark = true))
        assertTrue(ThemeMode.DARK.resolveDark(systemDark = false))
    }

    @Test
    fun `图标开关的循环顺序是系统到浅色到深色`() {
        assertEquals(ThemeMode.LIGHT, ThemeMode.SYSTEM.next())
        assertEquals(ThemeMode.DARK, ThemeMode.LIGHT.next())
        assertEquals(ThemeMode.SYSTEM, ThemeMode.DARK.next())
    }

    @Test
    fun `脏值与老配置回落到跟随系统`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromKey(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromKey(""))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromKey("天知道"))
        // 存的是 storageKey 而不是枚举名：将来改枚举名不会让已存配置失效
        assertEquals(ThemeMode.DARK, ThemeMode.fromKey("dark"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromKey("light"))
    }
}
