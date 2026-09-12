package com.branchbase.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 「上岛」扩展注册表与默认装配清单的单测。
 *
 * 只测**注册语义**（覆盖 / 注销 / 整体替换 / 顺序）：派发路径要真的 `NotificationCompat.Builder`，
 * 属于真机范畴；这里保证的是「装配不会重复、不会互相顶掉」。
 */
class DownloadIslandExtensionsTest {

    private class FakeIsland(
        override val id: String,
        override val vendor: IslandVendor = IslandVendor.CUSTOM,
    ) : DownloadIslandExtension

    @Before
    fun reset() {
        DownloadIslandExtensions.clear()
    }

    @Test
    fun `同 id 覆盖而不是重复注册`() {
        assertTrue(DownloadIslandExtensions.install(FakeIsland("a")))
        assertTrue(DownloadIslandExtensions.install(FakeIsland("a")))
        assertEquals(1, DownloadIslandExtensions.all().size)
    }

    @Test
    fun `注销只移除指定 id`() {
        DownloadIslandExtensions.install(FakeIsland("a"))
        DownloadIslandExtensions.install(FakeIsland("b"))
        assertTrue(DownloadIslandExtensions.uninstall("a"))
        assertFalse(DownloadIslandExtensions.uninstall("a"))
        assertEquals(listOf("b"), DownloadIslandExtensions.all().map { it.id })
    }

    @Test
    fun `整体替换用于 install 时覆盖配置`() {
        DownloadIslandExtensions.install(FakeIsland("old"))
        DownloadIslandExtensions.replaceAll(listOf(FakeIsland("x"), FakeIsland("y")))
        assertEquals(listOf("x", "y"), DownloadIslandExtensions.all().map { it.id })
    }

    @Test
    fun `默认装配覆盖三家厂商且 id 唯一`() {
        val defaults = VendorIslandExtensions.defaults()
        assertEquals(
            setOf(IslandVendor.GOOGLE, IslandVendor.OPPO, IslandVendor.XIAOMI),
            defaults.map { it.vendor }.toSet(),
        )
        assertEquals(defaults.size, defaults.map { it.id }.toSet().size)
    }

    @Test
    fun `未注册任何扩展时注册表为空`() {
        assertTrue(DownloadIslandExtensions.all().isEmpty())
    }
}
