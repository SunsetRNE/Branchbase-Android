package com.branchbase.joblogs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志分段解析的纯逻辑单测（从 :app 的 WorkflowModelsTest 迁入并补齐边界）。
 *
 * 数据形状按 GitHub 真实日志取证：每行行首是 ISO 时间戳，步骤之间用
 * `##[group]名字` / `##[endgroup]` 包起来。
 */
class JobLogParserTest {

    @Test
    fun `按 group 切分日志并去掉时间戳`() {
        val log = """
            2026-09-07T13:42:35.1234567Z ##[group]Set up job
            2026-09-07T13:42:35.2345678Z Runner name: foo
            2026-09-07T13:42:36.0000000Z ##[endgroup]
            2026-09-07T13:42:36.1000000Z ##[group]Run actions/checkout@v4
            2026-09-07T13:42:37.0000000Z with: fetch-depth: 0
            2026-09-07T13:42:38.0000000Z ##[endgroup]
            2026-09-07T13:42:39.0000000Z ##[group]Run tests
            2026-09-07T13:42:40.0000000Z FAIL src/a.kt
            2026-09-07T13:42:41.0000000Z ##[endgroup]
        """.trimIndent()

        val segs = splitJobLogBySteps(log)
        assertEquals(3, segs.size)
        assertEquals("Set up job", segs[0].title)
        assertEquals(listOf("Runner name: foo"), segs[0].lines)
        assertEquals("Run actions/checkout@v4", segs[1].title)
        assertEquals("FAIL src/a.kt", segs[2].lines.single())
        // 时间戳被剥掉
        assertTrue(segs.all { s -> s.lines.none { it.contains("2026-09-07T") } })
    }

    @Test
    fun `无 group 标记时整体一段 不丢内容`() {
        val log = "第一行\n第二行"
        val segs = splitJobLogBySteps(log)
        assertEquals(1, segs.size)
        assertEquals("全部日志", segs[0].title)
        assertEquals(listOf("第一行", "第二行"), segs[0].lines)
        assertTrue(splitJobLogBySteps("").isEmpty())
        assertTrue(splitJobLogBySteps("   \n  ").isEmpty())
    }

    @Test
    fun `没有 group 但正文非空时同样不丢内容`() {
        val segs = splitJobLogBySteps("2026-09-07T13:42:40.0000000Z hello")
        assertEquals(listOf("hello"), segs.single().lines)
    }

    @Test
    fun `CRLF 行尾不会带进分段`() {
        val segs = splitJobLogBySteps("##[group]Run tests\r\nFAIL\r\n##[endgroup]\r\n")
        assertEquals("Run tests", segs.single().title)
        assertEquals(listOf("FAIL"), segs.single().lines)
    }

    @Test
    fun `只剥行首时间戳 不动正文里的时间`() {
        assertEquals("hello", stripLogTimestamp("2026-09-07T13:42:40.1234567Z hello"))
        // 没有时间戳的行原样返回
        assertEquals("2026-09-07 13:42 用户可见", stripLogTimestamp("2026-09-07 13:42 用户可见"))
        assertEquals("", stripLogTimestamp(""))
    }

    @Test
    fun `标题带 Run 前缀时同样成段`() {
        // GitHub 的 run: 步骤 group 名常是「Run <命令行>」
        val segs = splitJobLogBySteps("##[group]Run ./gradlew test\nok")
        assertEquals("Run ./gradlew test", segs.single().title)
        assertEquals(listOf("ok"), segs.single().lines)
    }

    @Test
    fun `结束标记后的空行不再多出一段`() {
        // 把日志输出成文件时尾部常带一个换行，别再生成一段空内容
        val segs = splitJobLogBySteps("##[group]Run tests\nFAIL\n##[endgroup]\n")
        assertEquals(1, segs.size)
        assertEquals("Run tests", segs.single().title)
        assertEquals(listOf("FAIL"), segs.single().lines)
    }
}
