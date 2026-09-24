package com.branchbase.ui.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.branchbase.R
import org.junit.Assert.assertNotEquals

/**
 * PR 详情解析（`merged` / `mergeable`）与「合并入口」可见性规则单测。
 *
 * 这两件事是同一条链：详情页把 `PrMergeScreen` 接出来的**前提**就是这两个字段。
 *
 * ## 重点在 `mergeable` 的**三态**
 *
 * `GET /repos/{o}/{r}/pulls/{n}` 的 `mergeable` 有 `true` / `false` / `null` 三种取值，
 * 其中 `null` 表示 **GitHub 还在后台计算**（响应里就是 JSON null）。
 * `JSONObject.optBoolean("mergeable")` 会把 `null` 与「键不存在」一起吞成 `false` ——
 * 那就把「还没算出来」渲染成了「不可合并」，用户看到一句凭空捏造的拒绝理由。
 * 所以这里把三态分别钉住，并钉住入口规则**不把 null 当 false 藏起来**。
 *
 * 解析用真实响应的形状（字段名以 GitHub 实测为准），入参裁剪到本模型用到的字段。
 */
class PullDetailModelsTest {

    /** 可合并的开放 PR（裁剪过的真实响应形状）。 */
    private val openJson = """
        {
          "number": 42,
          "title": "接入合并页",
          "state": "open",
          "body": "正文",
          "created_at": "2026-09-24T10:00:00Z",
          "user": { "login": "wangyan" },
          "base": { "ref": "main" },
          "head": { "ref": "feat/merge-entry" },
          "merged": false,
          "mergeable": true,
          "mergeable_state": "clean"
        }
    """.trimIndent()

    // ── 解析 ──

    @Test
    fun `解析出 merged 与 mergeable 三态`() {
        val clean = parsePullDetail(openJson)
        assertNotNull(clean)
        clean!!
        assertEquals(42L, clean.number)
        assertFalse("merged=false 不能被当成 true", clean.merged)
        assertEquals(true, clean.mergeable)

        val dirty = parsePullDetail(openJson.replace("\"mergeable\": true", "\"mergeable\": false"))
        assertEquals(false, dirty!!.mergeable)

        val merged = parsePullDetail(
            openJson.replace("\"merged\": false", "\"merged\": true")
                .replace("\"mergeable\": true", "\"mergeable\": null")
                .replace("\"state\": \"open\"", "\"state\": \"closed\""),
        )
        assertTrue(merged!!.merged)
        assertNull("已合并的 PR 上 GitHub 会把 mergeable 置回 null", merged.mergeable)
    }

    @Test
    fun `mergeable 为 JSON null 时保持 null 而不是 false`() {
        // GitHub 还在算：这是「未知」，不是「不能合并」
        val d = parsePullDetail(openJson.replace("\"mergeable\": true", "\"mergeable\": null"))
        assertNotNull(d)
        assertNull("JSON null 必须解析成 null（optBoolean 会把它吞成 false）", d!!.mergeable)
        assertFalse("null 不能顺带把 merged 也带歪", d.merged)
    }

    @Test
    fun `缺字段时降级为未合并与未知`() {
        // 键整个不存在（例如老缓存里的详情）同样算「未知」
        val noKeys = parsePullDetail(
            """
            {"number":7,"title":"t","state":"open","body":"","created_at":"","user":{"login":"a"},
             "base":{"ref":"main"},"head":{"ref":"f"}}
            """.trimIndent(),
        )
        assertNotNull(noKeys)
        assertFalse(noKeys!!.merged)
        assertNull(noKeys.mergeable)

        // 其它字段照旧：解析器对缺字段降级而不是抛异常
        assertEquals("", parsePullDetail("""{"number":1,"state":"open"}""")!!.body)
        assertNull(parsePullDetail("""{"number":1,"state":"open"}""")!!.mergeable)
        assertNull("非法 JSON 仍返回 null（原有降级路径不变）", parsePullDetail("{oops"))
    }

    @Test
    fun `两个新字段带默认值不影响既有构造点`() {
        // 手写详情（测试 / 预览）不传新字段也应当编得过，且默认值是「未合并 + 未知」
        val d = PullDetail(
            number = 1L, title = "t", state = "open", body = "", author = "a",
            createdAt = "", baseRef = "main", headRef = "f",
        )
        assertFalse(d.merged)
        assertNull(d.mergeable)
    }

    // ── 入口可见性规则 ──

    @Test
    fun `只有 open 且未合并的 PR 才露出入口`() {
        assertEquals(PullMergeEntry.Enabled, pullMergeEntry("open", false, true, "f", "main"))
        // GitHub 还在算（null）→ 照常可点：不替服务端下结论
        assertEquals(PullMergeEntry.Enabled, pullMergeEntry("open", false, null, "f", "main"))
        // 已合并 / 已关闭 → 没有可合并的东西，藏掉
        assertEquals(PullMergeEntry.Hidden, pullMergeEntry("closed", true, null, "f", "main"))
        assertEquals(PullMergeEntry.Hidden, pullMergeEntry("open", true, true, "f", "main"))
        assertEquals(PullMergeEntry.Hidden, pullMergeEntry("closed", false, true, "f", "main"))
    }

    @Test
    fun `mergeable 为 false 时置灰而不是藏起来`() {
        assertEquals(
            "不可自动合并的 PR 仍要露出入口（置灰），否则用户不知道发生了什么",
            PullMergeEntry.Disabled,
            pullMergeEntry("open", false, false, "f", "main"),
        )
        // 置灰态必须给得出原因
        // 断言资源 ID（`pullMergeHintRes`）：文案已资源化，改译文不该让这条红
        assertEquals(
            R.string.merge_hint_conflict,
            pullMergeHintRes(mergeable = false, headRef = "f", baseRef = "main"),
        )
    }

    @Test
    fun `mergeable 为 null 时的提示是还在算而不是拒绝`() {
        // 「还在算」与「不可合并」必须是**两个不同的资源**（语义完全不同，不能复用一条文案）
        val hint = pullMergeHintRes(mergeable = null, headRef = "f", baseRef = "main")
        assertEquals(R.string.merge_hint_calculating, hint)
        assertNotEquals(R.string.merge_hint_conflict, hint)
        assertNull("可合并时不加多余文案", pullMergeHintRes(mergeable = true, headRef = "f", baseRef = "main"))
    }

    @Test
    fun `分支名缺失时置灰并说明原因`() {
        assertEquals(PullMergeEntry.Disabled, pullMergeEntry("open", false, true, "", "main"))
        assertEquals(PullMergeEntry.Disabled, pullMergeEntry("open", false, true, "f", ""))
        assertEquals(
            R.string.merge_hint_missing_branch,
            pullMergeHintRes(mergeable = true, headRef = "", baseRef = "main"),
        )
    }

    @Test
    fun `JSON 到入口规则贯通（已合并的响应藏掉入口）`() {
        // 从真实响应一路走到规则：合并成功后回源拿到的那份 JSON 不该再露出入口
        val openEntry = parsePullDetail(openJson)!!.let {
            pullMergeEntry(it.state, it.merged, it.mergeable, it.headRef, it.baseRef)
        }
        assertEquals(PullMergeEntry.Enabled, openEntry)

        val afterMerge = parsePullDetail(
            openJson.replace("\"merged\": false", "\"merged\": true")
                .replace("\"state\": \"open\"", "\"state\": \"closed\"")
                .replace("\"mergeable\": true", "\"mergeable\": null"),
        )!!
        assertEquals(
            PullMergeEntry.Hidden,
            pullMergeEntry(afterMerge.state, afterMerge.merged, afterMerge.mergeable, afterMerge.headRef, afterMerge.baseRef),
        )
    }
}
