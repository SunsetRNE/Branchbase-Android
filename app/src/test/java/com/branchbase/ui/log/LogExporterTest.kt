package com.branchbase.ui.log

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志导出包里「纯的那一半」的单测：压缩包内容与文件名。
 *
 * 这两处出错的表现都很隐蔽：压缩包打不开（用户只看到「导出失败」）、
 * 中文日志变成乱码、或者两次导出互相覆盖 —— 都不会崩，只会让人怀疑整个功能。
 */
class LogExporterTest {

    /** 解包（测试自己解一遍，避免「写进去就算过」）。 */
    private fun unzip(bytes: ByteArray): Map<String, String> {
        val out = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                out[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return out
    }

    @Test
    fun `压缩包含日志条目且内容一致`() {
        val text = """
            10:38:38.448 [UI] [System] INFO App 启动
            10:38:41.435 [UI] [帧] INFO 慢帧 69.3ms（等待 0.5 / 绘制 44.7*） · 页面「进入消息页」
        """.trimIndent()

        val bytes = buildLogArchive(mapOf(LogExporter.LOG_ENTRY_NAME to text))
        val entries = unzip(bytes)

        assertEquals(1, entries.size)
        assertEquals("中文与换行必须原样往返（UTF-8）", text, entries[LogExporter.LOG_ENTRY_NAME])
    }

    @Test
    fun `多条目各自独立`() {
        val entries = unzip(
            buildLogArchive(linkedMapOf("a.log" to "AAA", "b.txt" to "BBB")),
        )
        assertEquals(mapOf("a.log" to "AAA", "b.txt" to "BBB"), entries)
    }

    @Test
    fun `空内容也能打出合法压缩包`() {
        val entries = unzip(buildLogArchive(mapOf(LogExporter.LOG_ENTRY_NAME to "")))
        assertEquals("", entries[LogExporter.LOG_ENTRY_NAME])
    }

    @Test
    fun `文件名带时间戳_两次导出不会互相覆盖`() {
        val a = zipFileName(1_700_000_000_000L)
        val b = zipFileName(1_700_000_001_000L)
        assertTrue("前缀要能一眼看出是什么：$a", a.startsWith("branchbase-logs-"))
        assertTrue("后缀是 zip：$a", a.endsWith(".zip"))
        assertNotEquals("相隔一秒不能同名", a, b)
        assertEquals("同一时刻必须同名（可重入）", a, zipFileName(1_700_000_000_000L))
    }
}
