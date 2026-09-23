package com.branchbase.ui.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「列表接口的 assets 会短暂为空」这个坑的两条钉子。
 *
 * ## 现场（2026-09-23 三个 v1.0.73 beta 全中）
 *
 * 同一个 release，三条路径给出两种答案：
 *
 * | 路径 | 结果 |
 * |------|------|
 * | `GET /repos/{o}/{r}/releases`（列表）| `assets: []` |
 * | `GET /repos/{o}/{r}/releases/{id}`（单体）| 3 个 |
 * | `releases/expanded_assets/{tag}`（网页）| 3 行 |
 *
 * 窗口约 **1~2 小时**：发布后 1.1 小时的仍是空，2.3 小时的已恢复。
 * 而 **App 只读列表接口** —— 于是「刚发完版想立刻装」这个最常见的动作，
 * 恰好落在窗口里看到「没有附件」，只能去装上一版。附件本身一直是好的（可下载、有 sha256）。
 *
 * 所以 [releasesNeedingAssetBackfill] 是**唯一的补救点**：只对报空的那几条回源单体接口。
 * 这个文件钉住「挑谁」的规则 —— 挑错了要么补不上（漏挑），要么每次进页面都白打一串请求（多挑）。
 */
class ReleaseAssetBackfillTest {

    private fun asset(id: Long) = ReleaseAsset(
        id = id,
        name = "a$id.apk",
        size = 1L,
        downloadUrl = "https://example.invalid/a$id.apk",
        downloadCount = 0L,
    )

    private fun release(id: Long, assets: List<ReleaseAsset> = emptyList()) = ReleaseItem(
        id = id,
        tag = "v$id",
        name = "v$id",
        createdAt = "2026-09-23T00:00:00Z",
        assets = assets,
    )

    @Test
    fun `列表报空的才挑出来`() {
        val items = listOf(
            release(1),                 // 空 → 挑
            release(2, listOf(asset(21))),  // 有 → 不挑
            release(3),                 // 空 → 挑
        )
        assertEquals(listOf(1L, 3L), releasesNeedingAssetBackfill(items).map { it.id })
    }

    @Test
    fun `正常仓库一次都不多请求`() {
        // 所有 release 都带附件 —— 这是绝大多数情况，必须零额外请求
        val items = (1L..10L).map { release(it, listOf(asset(it * 10))) }
        assertTrue(releasesNeedingAssetBackfill(items).isEmpty())
    }

    @Test
    fun `id 为 0 的跳过`() {
        // 解析异常时 id 会落到默认值 0；放过去就会拼出 /releases/0 这种必然 404 的请求
        val items = listOf(release(0), release(7))
        assertEquals(listOf(7L), releasesNeedingAssetBackfill(items).map { it.id })
    }

    @Test
    fun `上限生效且保留最新在前`() {
        // 列表是 GitHub 给的新→旧序。有仓库整仓不传附件时，上限保证不会每个 release 都发一次请求
        val items = (1L..10L).map { release(it) }
        val picked = releasesNeedingAssetBackfill(items, limit = 3)
        assertEquals(listOf(1L, 2L, 3L), picked.map { it.id })
    }

    @Test
    fun `单体接口的对象能被解析`() {
        val json = """
            {"id":42,"tag_name":"v1.0.73","name":"Beta v1.0.73","published_at":"2026-09-23T08:48:08Z",
             "draft":false,"prerelease":true,"author":{"login":"SunsetRNE"},
             "assets":[{"id":9,"name":"x.apk","size":123,"browser_download_url":"https://e.invalid/x.apk","download_count":0}]}
        """.trimIndent()
        val one = parseSingleRelease(json)
        assertNotNull("单体接口返回的是对象，parseSingleRelease 必须能吃下", one)
        assertEquals(42L, one!!.id)
        assertEquals(1, one.assets.size)
        assertEquals("x.apk", one.assets.first().name)
    }

    @Test
    fun `取不到或报错时返回 null 而不是空壳`() {
        // 返回空壳会被上层当成「这个 release 确实没有附件」，从而覆盖掉原本的显示
        assertNull(parseSingleRelease(null))
        assertNull(parseSingleRelease(""))
        assertNull(parseSingleRelease("ERROR: network unreachable"))
        assertNull(parseSingleRelease("not json at all"))
    }
}
