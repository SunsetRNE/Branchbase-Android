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

    // ── 索引（report.md） ──

    private val app = ExportAppInfo(
        engineeringVersion = "1.0.71",
        standardVersion = "1.0.71-20260923-0512-abcdef0",
        buildTime = "2026-09-23 05:12",
        gitHash = "abcdef0",
        debug = true,
    )

    private fun entry(
        seq: Long,
        time: Long,
        category: LogCategory = LogCategory.UI_RENDER,
        level: LogLevel = LogLevel.INFO,
        tag: String = "Compose",
        message: String = "进入设置页",
    ) = LogEntry(seq = seq, time = time, category = category, level = level, tag = tag, message = message)

    private fun sampleEntries(): List<LogEntry> = listOf(
        entry(1, 1_700_000_000_000L, tag = DeviceProfile.TAG, message = "机型 OnePlus PJD110 · Android 16 · 120Hz"),
        entry(2, 1_700_000_001_000L, tag = "启动", message = "App 启动"),
        entry(3, 1_700_000_002_000L, category = LogCategory.NETWORK, tag = "GitHubAPI", message = "GET /user → 200"),
        entry(4, 1_700_000_003_000L, level = LogLevel.WARN, tag = "帧", message = "慢帧 41.2ms"),
        entry(5, 1_700_000_004_000L, level = LogLevel.ERROR, category = LogCategory.LOCAL_TASK, tag = "LocalGit", message = "clone 失败"),
    )

    @Test
    fun `索引给出概览_条数_级别_类别与时间范围`() {
        val report = buildLogReport(sampleEntries(), 1_700_000_010_000L, app)

        assertTrue("要有标题", report.contains("# Branchbase 日志包 · 索引"))
        assertTrue("要有版本信息", report.contains("1.0.71"))
        assertTrue("要有标准版本号", report.contains("1.0.71-20260923-0512-abcdef0"))
        assertTrue("条数要含级别拆分", report.contains("| 条数 | 5（ERROR 1 / WARN 1） |"))
        assertTrue("类别要逐个列出", report.contains("UI 3") && report.contains("网络 1") && report.contains("本地 1"))
        assertTrue("时间范围按北京时间", report.contains("时间范围"))
    }

    @Test
    fun `索引带上设备档案_没有时明确说明`() {
        val withDevice = buildLogReport(sampleEntries(), 1_700_000_010_000L, app)
        assertTrue("设备档案要原样摘出来", withDevice.contains("机型 OnePlus PJD110"))

        val noDevice = buildLogReport(
            sampleEntries().filterNot { it.tag == DeviceProfile.TAG },
            1_700_000_010_000L,
            app,
        )
        assertTrue("缺档案要说明原因，不能留空白", noDevice.contains("没有设备档案"))
    }

    @Test
    fun `索引带锚点词典_每个锚点都有解释`() {
        val report = buildLogReport(sampleEntries(), 1_700_000_010_000L, app)
        assertTrue("要有锚点小节", report.contains("## 6. 关键路径锚点"))
        LOG_ANCHORS.forEach { (tag, _) ->
            assertTrue("锚点表缺 $tag", report.contains("`$tag`"))
        }
    }

    @Test
    fun `空日志也能生成合法索引`() {
        val report = buildLogReport(emptyList(), 1_700_000_010_000L, app)
        assertTrue(report.contains("| 条数 | 0（ERROR 0 / WARN 0） |"))
        assertTrue("范围要写「空」而不是崩", report.contains("| 时间范围 | （空） |"))
    }

    @Test
    fun `一个包两份文件_索引在前原始日志在后`() {
        val logText = "10:38:38.448 [UI] [System] INFO App 启动"
        val bytes = buildLogArchive(
            linkedMapOf(
                LogExporter.REPORT_ENTRY_NAME to buildLogReport(sampleEntries(), 1_700_000_010_000L, app),
                LogExporter.LOG_ENTRY_NAME to logText,
            ),
        )
        val entries = unzip(bytes)

        assertEquals("就是两份：索引 + 原始日志", 2, entries.size)
        assertEquals("原始日志必须原样", logText, entries[LogExporter.LOG_ENTRY_NAME])
        assertTrue("索引要说明包里有哪两份", entries.getValue(LogExporter.REPORT_ENTRY_NAME).contains("branchbase.log"))
        assertEquals(
            "顺序：索引在前（人先看到）",
            listOf(LogExporter.REPORT_ENTRY_NAME, LogExporter.LOG_ENTRY_NAME),
            entries.keys.toList(),
        )
    }
}
