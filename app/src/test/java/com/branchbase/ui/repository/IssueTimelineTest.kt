package com.branchbase.ui.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue 详情 / 时间线解析单测（需求 ④ 的数据层）。
 *
 * 重点不在「字段能读出来」，而在**降级路径**：
 * - 时间线里未知事件必须被安静丢掉，而不是渲染出一行「某事件」；
 * - `state_reason` 缺失时不能把「已关闭」误标成「不计划实施」；
 * - 标签颜色是 6 位十六进制，浅色标签上的文字必须转成深色（否则白字看不见）。
 */
class IssueTimelineTest {

    @Test
    fun `详情解析出标签颜色_指派_里程碑与状态原因`() {
        val json = """
            {"number":129,"title":"标题","state":"closed","state_reason":"not_planned",
             "body":"正文","created_at":"2026-09-08T00:00:00Z","comments":12,
             "user":{"login":"wangyan","avatar_url":"https://x/a.png"},
             "labels":[{"name":"设计","color":"8250df"},{"name":"P2","color":"bf8700"}],
             "assignees":[{"login":"linzhi"},{"login":"chris"}],
             "milestone":{"title":"v1.0.14"}}
        """.trimIndent()
        val d = parseIssueDetail(json)
        assertNotNull(d)
        d!!
        assertEquals(129L, d.number)
        assertEquals("closed", d.state)
        assertEquals("not_planned", d.stateReason)
        assertFalse(d.isOpen)
        assertEquals(2, d.labels.size)
        assertEquals("8250df", d.labels[0].colorHex)
        assertEquals(listOf("linzhi", "chris"), d.assignees)
        assertEquals("v1.0.14", d.milestone)
        assertEquals(12, d.commentsCount)
        assertEquals("wangyan", d.author)
    }

    @Test
    fun `标签颜色按亮度决定文字色`() {
        // 深色底 → 白字；浅色底 → 深字（GitHub 网页版同款规则）
        val dark = LabelChip("dark", "8250df")
        val light = LabelChip("light", "d4f7d4")
        assertEquals(androidx.compose.ui.graphics.Color.White, dark.onColor)
        assertFalse(light.onColor == androidx.compose.ui.graphics.Color.White)
    }

    @Test
    fun `关闭原因缺失时不臆造为不计划实施`() {
        val d = parseIssueDetail("""{"number":1,"title":"t","state":"closed","body":"","labels":[]}""")
        assertNotNull(d)
        assertEquals(null, d!!.stateReason)
    }

    @Test
    fun `时间线把评论与事件混排且忽略未知事件`() {
        val json = """
            [
              {"event":"labeled","actor":{"login":"wangyan"},"created_at":"2026-09-08T01:00:00Z",
               "label":{"name":"设计"}},
              {"event":"commented","id":11,"body":"同意","created_at":"2026-09-08T02:00:00Z",
               "user":{"login":"linzhi"},"author_association":"MEMBER",
               "reactions":{"+1":2,"total_count":2}},
              {"event":"milestoned","actor":{"login":"wangyan"},"created_at":"2026-09-08T03:00:00Z",
               "milestone":{"title":"v1.0.14"}},
              {"event":"head_ref_deleted","actor":{"login":"x"},"created_at":"2026-09-08T04:00:00Z"},
              {"event":"closed","actor":{"login":"wangyan"},"created_at":"2026-09-08T05:00:00Z",
               "state_reason":"completed"}
            ]
        """.trimIndent()
        val entries = parseIssueTimeline(json, issueAuthor = "wangyan")
        assertEquals("未知事件应被丢弃", 4, entries.size)
        assertTrue(entries[0] is TimelineEntry.Event)
        assertTrue(entries[1] is TimelineEntry.Comment)
        assertTrue(entries[2] is TimelineEntry.Event)
        assertTrue(entries[3] is TimelineEntry.Event)

        val comment = (entries[1] as TimelineEntry.Comment).comment
        assertEquals("linzhi", comment.author)
        assertEquals("MEMBER", comment.authorAssociation)
        assertEquals("Member", comment.badge)
        assertEquals(1, comment.reactions.size)
        assertEquals(2, comment.reactions[0].count)
        assertEquals("👍", comment.reactions[0].emoji)

        val closed = entries[3] as TimelineEntry.Event
        assertTrue(closed.text.contains("已完成"))
    }

    @Test
    fun `主帖作者的评论带作者徽章且优先级高于组织身份`() {
        val json = """
            [{"event":"commented","id":1,"body":"hi","created_at":"2026-09-08T02:00:00Z",
              "user":{"login":"wangyan"},"author_association":"OWNER"}]
        """.trimIndent()
        val c = (parseIssueTimeline(json, issueAuthor = "wangyan").first() as TimelineEntry.Comment).comment
        assertEquals("作者", c.badge)
    }

    @Test
    fun `没有事件的降级路径只产出评论`() {
        val json = """
            [{"id":1,"body":"a","created_at":"2026-09-08T02:00:00Z","user":{"login":"x"}},
             {"id":2,"body":"b","created_at":"2026-09-08T03:00:00Z","user":{"login":"y"}}]
        """.trimIndent()
        val comments = parseComments(json)
        assertEquals(2, comments.size)
        assertEquals("a", comments[0].body)
        assertTrue(comments[0].reactions.isEmpty())
    }

    @Test
    fun `时间线为空时返回空列表而不是抛异常`() {
        assertTrue(parseIssueTimeline("").isEmpty())
        assertTrue(parseIssueTimeline("ERROR: 404").isEmpty())
        assertTrue(parseComments("not json").isEmpty())
    }
}
