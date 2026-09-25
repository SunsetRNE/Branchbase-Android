package com.branchbase.ui.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「引用树」档的整理规则（`GitRefsModels.kt`）。
 *
 * 这三条都是**面板里看得见、单测里看不出来**的那种错：
 * 1. HEAD 不置顶 —— 面板只有 268dp、分支一多就得滚动，「我现在在哪」被滚出屏幕；
 * 2. 没配上游的分支被画成「已同步」（0/0 与「未跟踪」是两件事）；
 * 3. 「只在远端」的数错 —— 用户据此以为本地已经有一份。
 */
class GitRefsModelsTest {

    private fun local(
        name: String,
        isHead: Boolean = false,
        upstream: String = "",
        ahead: Int = 0,
        behind: Int = 0,
    ) = LocalBranchInfo(name = name, isHead = isHead, upstream = upstream, ahead = ahead, behind = behind)

    private fun remote(
        name: String,
        local: String = name,
        hasLocal: Boolean = true,
        ahead: Int = 0,
        behind: Int = 0,
    ) = RemoteBranchInfo(name = name, local = local, hasLocal = hasLocal, ahead = ahead, behind = behind)

    @Test
    fun `徽标只在真的领先或落后时出现`() {
        assertNull("已同步不画 ↑0 ↓0", refSyncBadge(0, 0))
        assertEquals("↑2", refSyncBadge(2, 0))
        assertEquals("↓3", refSyncBadge(0, 3))
        assertEquals("两边都有独有提交就都得画出来", "↑2 ↓3", refSyncBadge(2, 3))
    }

    @Test
    fun `HEAD 置顶，其余保持引擎给的顺序`() {
        val view = refsViewOf(
            locals = listOf(
                local("feature-a", upstream = "origin/feature-a"),
                local("main", isHead = true, upstream = "origin/main", ahead = 1),
                local("zebra", upstream = "origin/zebra"),
            ),
            remotes = emptyList(),
        )
        assertEquals(listOf("main", "feature-a", "zebra"), view.locals.map { it.name })
        assertTrue(view.locals.first().isHead)
        assertEquals("↑1", view.locals.first().badge)
    }

    @Test
    fun `没配上游的分支标成未跟踪，不是已同步`() {
        val view = refsViewOf(
            locals = listOf(local("scratch", ahead = 0, behind = 0, upstream = "")),
            remotes = emptyList(),
        )
        val row = view.locals.single()
        assertFalse("没有上游 ≠ 推完了", row.tracked)
        assertNull(row.badge)
    }

    @Test
    fun `只在远端的条数按 hasLocal 数，未跟踪不受影响`() {
        val view = refsViewOf(
            locals = listOf(local("main", isHead = true, upstream = "origin/main")),
            remotes = listOf(
                remote("main"),
                remote("dev", local = "", hasLocal = false),
                remote("release", local = "", hasLocal = false, behind = 4),
            ),
        )
        assertEquals(2, view.remoteOnly)
        assertEquals(3, view.remotes.size)
        assertEquals("↓4", view.remotes.first { it.name == "release" }.badge)
        assertFalse(view.isEmpty)
    }

    @Test
    fun `tag 解析：annotated 带全字段，轻量留空不编值`() {
        // 引擎（list_tags）的输出形状：扁平 native JSON
        val json = """[
          {"name":"nightly","sha":"aaa","annotated":false,"target_sha":"aaa","tagger":null,"message":""},
          {"name":"v1.0","sha":"tagobj","annotated":true,"target_sha":"bbb",
           "tagger":{"name":"Tagger","email":"t@example.com","time":"2026-09-25T04:41:23+08:00"},
           "message":"第一个版本\n第二行"}
        ]"""
        val tags = parseLocalTags(json)
        assertEquals(listOf("nightly", "v1.0"), tags.map { it.name })
        val light = tags[0]
        assertFalse(light.annotated)
        assertEquals("aaa", light.targetSha)
        assertEquals("轻量 tag 没有 tagger —— 不许编一个", "", light.taggerName)
        assertEquals("", light.message)

        val annotated = tags[1]
        assertTrue(annotated.annotated)
        assertEquals("tagobj", annotated.sha)
        assertEquals("bbb", annotated.targetSha)
        assertEquals("Tagger", annotated.taggerName)
        assertEquals("t@example.com", annotated.taggerEmail)
        assertTrue(annotated.taggerTime.contains('T'))
        // 列表里只放说明首行
        assertEquals("第一个版本", annotated.subject)
    }

    @Test
    fun `tag 解析容错：坏 JSON 与缺字段都退化成空或默认值`() {
        assertEquals(emptyList<GitTagRow>(), parseLocalTags(null))
        assertEquals(emptyList<GitTagRow>(), parseLocalTags("不是 JSON"))
        assertEquals(emptyList<GitTagRow>(), parseLocalTags("[{\"name\":\"\"}]"))
        // annotated 为 true 但 tagger 缺失：按「没有」处理（不崩、也不编值）
        val rows = parseLocalTags("""[{"name":"v9","sha":"x","annotated":true,"target_sha":"y","message":"m"}]""")
        assertEquals(1, rows.size)
        assertEquals("", rows[0].taggerName)
        assertEquals("m", rows[0].message)
    }

    @Test
    fun `只有 tag 的仓库不算空`() {
        val view = refsViewOf(
            locals = emptyList(),
            remotes = emptyList(),
            tags = parseLocalTags("""[{"name":"v1","sha":"a","annotated":false,"target_sha":"a"}]"""),
        )
        assertFalse("有 tag 就不能显示「这个仓库还没有任何引用」", view.isEmpty)
        assertEquals(1, view.tags.size)
    }

    @Test
    fun `两份列表都空才是空`() {
        assertTrue(refsViewOf(emptyList(), emptyList()).isEmpty)
        assertFalse(refsViewOf(listOf(local("main")), emptyList()).isEmpty)
        assertFalse(refsViewOf(emptyList(), listOf(remote("main", local = "", hasLocal = false))).isEmpty)
    }
}
