package com.branchbase.ui.log

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志按天轮转（北京时间 00:00:00 换目录，旧的丢弃）。
 *
 * 轮转的边界只有一天一次，靠手点根本试不出来（真要试得等到半夜），所以判定与清理都抽成
 * 纯函数钉在这里：日期戳算错一小时，就会出现「半夜那批日志落到昨天目录、然后被当成历史删掉」。
 */
class LogRotationTest {

    private fun utcMs(iso: String): Long =
        java.time.Instant.parse(iso).toEpochMilli()

    @Test
    fun `日期戳按北京时间算，跨的是北京零点而不是 UTC 零点`() {
        // 2026-09-14 15:59:59Z = 北京时间 23:59:59 → 还是 14 号
        assertEquals("2026-09-14", logDayStamp(utcMs("2026-09-14T15:59:59Z")))
        // 2026-09-14 16:00:00Z = 北京时间 09-15 00:00:00 → 换到 15 号
        assertEquals("2026-09-15", logDayStamp(utcMs("2026-09-14T16:00:00Z")))
        // 北京时间 09-15 23:59:59 仍是 15 号
        assertEquals("2026-09-15", logDayStamp(utcMs("2026-09-15T15:59:59Z")))
    }

    @Test
    fun `日志文件落在当天的目录里，文件名保持 branchbase_log`() {
        val root = File("/tmp/whatever")
        val f = logFileFor(root, "2026-09-14")
        assertEquals(File(File(File(root, "logs"), "2026-09-14"), "branchbase.log"), f)
    }

    @Test
    fun `轮转时删掉除当天以外的目录`() {
        val root = File.createTempFile("bb-rot", "").let { it.delete(); it.mkdirs(); it }
        try {
            val logsRoot = File(root, "logs")
            val old = File(logsRoot, "2026-09-13")
            val today = File(logsRoot, "2026-09-14")
            old.mkdirs(); today.mkdirs()
            File(old, "branchbase.log").writeText("昨天的日志\n")
            File(today, "branchbase.log").writeText("今天的日志\n")

            val removed = cleanupOldLogDays(logsRoot, keep = "2026-09-14")

            assertEquals(1, removed)
            assertFalse("昨天的目录该被整个删掉", old.exists())
            assertTrue("当天目录必须留着", today.exists())
            assertEquals("今天的日志\n", File(today, "branchbase.log").readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
