package com.branchbase.ui.profile

import com.branchbase.R
import com.branchbase.ui.LocalizedText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 动态页事件流单测：「最近活动」的折叠口径 + events payload → 字段映射。
 *
 * 这两件事都必须钉住：
 *
 * 1. **折叠口径**决定「一屏显示多少条」。放宽（跨天 / 跨分支也合并）显示的就是**编造过的事实**
 *    ——「今天 3 次 + 昨天 5 次 = 8 次」；收紧（不合并）则退回真机上的原始观感：
 *    最近 30 条里 28 条是推送，一屏几乎一样的「推送到 main · <sha>」。
 * 2. **payload 映射**是外部契约，而且**与文档不一致**：实测 `GET /users/{login}/events/public`，
 *    PushEvent 的 payload 只有 `repository_id` / `push_id` / `ref` / `head` / `before`
 *    （没有 `size`、没有 `commits`）。所以「提交数」「提交信息」这两样**拿不到就是拿不到**，
 *    能拿到的真实字段（分支、短 sha、actor 头像、PR / issue 标题）一个都不能再丢。
 */
class ActivityFeedTest {

    /**
     * 钉住一条文案用的是**哪条资源、按什么顺序传参**。
     *
     * `detail` 是 [LocalizedText]（资源 ID + 参数），解析成具体语言要 Context，
     * 纯 JVM 单测里拿不到。但真正会写错的是「映射」而不是「排版」——
     * 用错资源、把分支和 sha 传反、漏掉次数，都是这一层能抓到的；
     * 排版与两种语言的占位符一致性由 `tools/i18n/check-i18n.py` 负责。
     */
    private fun assertDetail(expectedRes: Int, expectedArgs: List<Any>, actual: LocalizedText) {
        assertEquals("用错了资源", expectedRes, actual.res)
        assertEquals("参数顺序/个数不对", expectedArgs, actual.args)
    }

    // ── 折叠 ──────────────────────────────────────────────────────────────

    private fun push(
        repo: String = "SunsetRNE/Branchbase-Android",
        branch: String? = "main",
        head: String = "77d563e",
        at: Long = at(2026, 9, 15, 10),
    ) = ActivityEvent(
        type = "PushEvent",
        repo = repo,
        detail = LocalizedText(R.string.activity_push_branch_head, listOf(branch.orEmpty(), head)),
        createdAt = at,
        actor = "SunsetRNE",
        actorAvatar = "https://avatars.githubusercontent.com/u/178469479?v=4",
        branch = branch,
        head = head,
    )

    /** 本地时区的确定时刻（不用 `now - 24h`，避免跑在午夜附近时跨天而随机失败）。 */
    private fun at(y: Int, m: Int, d: Int, h: Int): Long = Calendar.getInstance().apply {
        clear()
        set(y, m - 1, d, h, 0, 0)
    }.timeInMillis

    @Test
    fun `同仓库同分支同一天的连续推送折叠成一行`() {
        val out = collapsePushes(
            listOf(
                push(head = "aaaaaaa", at = at(2026, 9, 15, 18)),
                push(head = "bbbbbbb", at = at(2026, 9, 15, 14)),
                push(head = "ccccccc", at = at(2026, 9, 15, 9)),
            ),
        )
        assertEquals("三次连续推送应折叠成一行", 1, out.size)
        assertEquals(3, out[0].pushCount)
        // 保留**最新一次**的 sha：events 按时间倒序，组内首条就是最新
        assertEquals("aaaaaaa", out[0].head)
        assertDetail(
            R.string.activity_push_branch_count_head,
            listOf("main", 3, "aaaaaaa"),
            out[0].detail,
        )
    }

    @Test
    fun `折叠不跨天`() {
        // 跨天合并会把「今天 3 次 + 昨天 5 次」写成「8 次」——那是在编造事实
        val out = collapsePushes(
            listOf(
                push(head = "aaaaaaa", at = at(2026, 9, 15, 10)),
                push(head = "bbbbbbb", at = at(2026, 9, 14, 23)),
            ),
        )
        assertEquals(2, out.size)
        assertTrue(out.all { it.pushCount == 1 })
    }

    @Test
    fun `折叠不跨分支`() {
        val out = collapsePushes(
            listOf(
                push(branch = "main", head = "aaaaaaa", at = at(2026, 9, 15, 12)),
                push(branch = "dev", head = "bbbbbbb", at = at(2026, 9, 15, 11)),
            ),
        )
        assertEquals(2, out.size)
    }

    @Test
    fun `折叠不跨仓库`() {
        val out = collapsePushes(
            listOf(
                push(repo = "SunsetRNE/Branchbase-Android", head = "aaaaaaa", at = at(2026, 9, 15, 12)),
                push(repo = "SunsetRNE/other", head = "bbbbbbb", at = at(2026, 9, 15, 11)),
            ),
        )
        assertEquals(2, out.size)
    }

    @Test
    fun `中间夹其它事件就断开`() {
        val watch = ActivityEvent(
            type = "WatchEvent",
            repo = "SunsetRNE/Branchbase-Android",
            detail = LocalizedText(R.string.activity_starred_repo),
            createdAt = at(2026, 9, 15, 11),
        )
        val out = collapsePushes(
            listOf(
                push(head = "aaaaaaa", at = at(2026, 9, 15, 12)),
                watch,
                push(head = "bbbbbbb", at = at(2026, 9, 15, 10)),
            ),
        )
        assertEquals(3, out.size)
        assertEquals("WatchEvent", out[1].type)
    }

    @Test
    fun `单条推送保持原样`() {
        val out = collapsePushes(listOf(push(head = "77d563e", at = at(2026, 9, 15, 10))))
        assertEquals(1, out.size)
        assertEquals(1, out[0].pushCount)
        assertDetail(R.string.activity_push_branch_head, listOf("main", "77d563e"), out[0].detail)
    }

    @Test
    fun `没有分支信息时仍按仓库与天折叠`() {
        // ref 缺失时 branch 为 null：合并口径用 null 安全相等，否则又退回一屏重复行
        val out = collapsePushes(
            listOf(
                push(branch = null, head = "aaaaaaa", at = at(2026, 9, 15, 12)),
                push(branch = null, head = "bbbbbbb", at = at(2026, 9, 15, 11)),
            ),
        )
        assertEquals(1, out.size)
        assertEquals(2, out[0].pushCount)
        assertDetail(R.string.activity_push_times, listOf(2), out[0].detail)
    }

    // ── 顺序 ──────────────────────────────────────────────────────────────

    /**
     * 真机上抓到的顺序（`/users/{login}/events/public` 前三条）：06:38 / 06:31 / 06:43 ——
     * 接口按事件 id 而非 `created_at` 返回。**乱序是显示层两个问题的共同根因**：
     * 相对时间忽大忽小（「4 小时前」下面跟「1 天前」），以及折叠在错误的相邻项上分组。
     */
    private val outOfOrderJson = """
        [
          {"id": "a", "type": "PushEvent", "actor": {"login": "SunsetRNE"},
           "repo": {"name": "SunsetRNE/Branchbase-Android"},
           "payload": {"ref": "refs/heads/main", "head": "77d563edee7ec82a10d9d2f0320bc5b5da840a7f"},
           "created_at": "2026-09-15T06:38:39Z"},
          {"id": "b", "type": "PushEvent", "actor": {"login": "SunsetRNE"},
           "repo": {"name": "SunsetRNE/Branchbase-Android"},
           "payload": {"ref": "refs/heads/main", "head": "e00b6a63432d474ea69a9ca6c6450514975641a8"},
           "created_at": "2026-09-15T06:31:37Z"},
          {"id": "c", "type": "PushEvent", "actor": {"login": "SunsetRNE"},
           "repo": {"name": "SunsetRNE/Branchbase-Android"},
           "payload": {"ref": "refs/heads/main", "head": "85dc7f98b15e4f67d231e4219c3959175166bc13"},
           "created_at": "2026-09-15T06:43:34Z"}
        ]
    """.trimIndent()

    @Test
    fun `解析后按时间倒序_不沿用接口顺序`() {
        val out = parseEvents(outOfOrderJson)
        assertEquals(listOf("85dc7f9", "77d563e", "e00b6a6"), out.map { it.head })
        assertTrue(
            "时间必须单调不增",
            out.zipWithNext().all { (a, b) -> a.createdAt >= b.createdAt },
        )
    }

    @Test
    fun `乱序的事件流折叠后仍聚成一串`() {
        // 不排序的话，这三条会各自成行（乱序相邻项不满足「同一天」以外的全部条件时更碎）
        val out = collapsePushes(parseEvents(outOfOrderJson))
        assertEquals(1, out.size)
        assertEquals(3, out[0].pushCount)
        assertEquals("最新一条（06:43）的 sha 要留在组内", "85dc7f9", out[0].head)
        assertDetail(
            R.string.activity_push_branch_count_head,
            listOf("main", 3, "85dc7f9"),
            out[0].detail,
        )
    }

    // ── payload 映射 ──────────────────────────────────────────────────────

    @Test
    fun `解析推送事件_取到触发者_分支与短 sha`() {
        // 下面是实测抓到的真实 payload 形状（PushEvent 被裁剪：没有 size / commits）
        val json = """
            [
              {
                "id": "1",
                "type": "PushEvent",
                "actor": {
                  "id": 178469479,
                  "login": "SunsetRNE",
                  "avatar_url": "https://avatars.githubusercontent.com/u/178469479?v=4"
                },
                "repo": {"id": 1354689271, "name": "SunsetRNE/Branchbase-Android"},
                "payload": {
                  "repository_id": 1354689271,
                  "push_id": 43619328364,
                  "ref": "refs/heads/main",
                  "head": "77d563edee7ec82a10d9d2f0320bc5b5da840a7f",
                  "before": "e00b6a63432d474ea69a9ca6c6450514975641a8"
                },
                "created_at": "2026-09-15T06:43:35Z"
              }
            ]
        """.trimIndent()

        val e = parseEvents(json).single()
        assertEquals("触发者登录名要从 actor.login 取", "SunsetRNE", e.actor)
        assertEquals("https://avatars.githubusercontent.com/u/178469479?v=4", e.actorAvatar)
        assertEquals("main", e.branch)
        assertEquals("77d563e", e.head)
        assertDetail(R.string.activity_push_branch_head, listOf("main", "77d563e"), e.detail)
        assertTrue("created_at 必须解析成功", e.createdAt > 0L)
        assertNull("推送没有真实对象标题，不该编一个出来", e.title)
    }

    @Test
    fun `解析拉取请求事件_取到真实标题与编号`() {
        val json = """
            [
              {
                "id": "2",
                "type": "PullRequestEvent",
                "actor": {"login": "SunsetRNE", "avatar_url": "https://avatars.githubusercontent.com/u/1?v=4"},
                "repo": {"name": "SunsetRNE/Branchbase-Android"},
                "payload": {
                  "action": "opened",
                  "number": 42,
                  "pull_request": {"number": 42, "title": "feat(profile): 骨架屏"}
                },
                "created_at": "2026-09-15T05:00:00Z"
              }
            ]
        """.trimIndent()

        val e = parseEvents(json).single()
        // 动作词是**嵌套**的 LocalizedText：解析时先出动作、再进整句，英文语序才对
        assertDetail(
            R.string.activity_pull_request_action,
            listOf(LocalizedText(R.string.event_action_opened), 42),
            e.detail,
        )
        assertEquals("feat(profile): 骨架屏", e.title)
    }

    @Test
    fun `解析星标事件_没有标题也没有分支`() {
        val json = """
            [
              {
                "id": "3",
                "type": "WatchEvent",
                "actor": {"login": "kaisar945", "avatar_url": "https://avatars.githubusercontent.com/u/2?v=4"},
                "repo": {"name": "kaisar945/Xposed-GodMode"},
                "payload": {"action": "started"},
                "created_at": "2026-09-15T04:00:00Z"
              }
            ]
        """.trimIndent()

        val e = parseEvents(json).single()
        assertEquals("kaisar945", e.actor)
        assertDetail(R.string.activity_starred_repo, emptyList(), e.detail)
        assertNull(e.title)
        assertNull(e.branch)
    }

    @Test
    fun `分页重叠的事件按 id 去重`() {
        val one = """
            {"id": "9", "type": "WatchEvent", "actor": {"login": "a"},
             "repo": {"name": "a/b"}, "payload": {}, "created_at": "2026-09-15T04:00:00Z"}
        """.trimIndent()
        val json = "[$one, $one]"
        assertEquals(1, parseEvents(json).size)
    }

    @Test
    fun `错误串与空串都解析成空列表`() {
        assertTrue(parseEvents("ERROR: 401").isEmpty())
        assertTrue(parseEvents("").isEmpty())
        assertTrue(parseEvents(null).isEmpty())
    }
}
