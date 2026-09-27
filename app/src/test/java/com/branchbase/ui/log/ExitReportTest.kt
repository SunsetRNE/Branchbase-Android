package com.branchbase.ui.log

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「上一程是怎么结束的」上报的钉子。
 *
 * 起因是 2026-09-27 那次**没有任何痕迹的闪退**：日志包里最后一行是「切到「发布」」，
 * 下一条就是四秒后的「启动」。进程死得连一句遗言都没留下，所以只能靠系统那份
 * `ApplicationExitInfo` 记录回头去问 —— 这条链路一旦被改坏，下次出事还是只能干瞪眼。
 *
 * 能钉的尽量钉成**纯函数**（原因码 → 人话、挑哪些上报、栈怎么截、正文长什么样），
 * 因为 `ApplicationExitInfo` 在单测里造不出来（final 类、无公开构造函数）；
 * 剩下那层薄薄的地基（读系统 API、落盘标记）用源码级断言钉住接线。
 */
class ExitReportTest {

    private fun source(path: String): String = File(path).readText()

    /**
     * 去掉 Kotlin 注释后的源码（同 `ui/profile/SettingsSpecTest`）。
     *
     * 「代码里不许出现某个写法」这类钉子，那个写法**恰好会在注释里被解释**
     * （`ExitReport.kt` 就明写了「不是 `SettingsKeys.PREFS`」）。扫原文会让「把坑写下来」
     * 这个好习惯变成假红，而假红比没有检查更坏 —— 它训练人忽略这套钉子。
     */
    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == '/' && src.startsWith("//", i) -> while (i < src.length && src[i] != '\n') i++
                c == '/' && src.startsWith("/*", i) -> {
                    var depth = 1
                    i += 2
                    while (i < src.length && depth > 0) {
                        when {
                            src.startsWith("/*", i) -> { depth++; i += 2 }
                            src.startsWith("*/", i) -> { depth--; i += 2 }
                            else -> i++
                        }
                    }
                }
                c == '"' -> {
                    out.append(c)
                    i++
                    while (i < src.length && src[i] != '"') {
                        if (src[i] == '\\') { out.append(src[i]); i++ }
                        if (i < src.length) { out.append(src[i]); i++ }
                    }
                    if (i < src.length) { out.append(src[i]); i++ }
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    private fun facts(
        ts: Long,
        reason: Int = 4,
        trace: String? = null,
        description: String? = null,
        processName: String = "com.branchbase",
        importance: Int = 100,
    ) = ExitFacts(
        timestampMs = ts,
        reason = reason,
        importance = importance,
        pid = 4242,
        processName = processName,
        description = description,
        pssKb = 120 * 1024,
        rssKb = 240 * 1024,
        trace = trace,
    )

    // ── ① 原因码 → 人话 ────────────────────────────────────────────────

    @Test
    fun `原因码 4 就是未捕获异常`() {
        assertTrue(exitReasonLabel(4).contains("未捕获异常"))
        assertTrue(exitReasonLabel(5).contains("native"))
        assertTrue(exitReasonLabel(6).contains("ANR"))
        assertTrue(exitReasonLabel(3).contains("低内存"))
    }

    @Test
    fun `已知原因码都有不重复的人话`() {
        val labels = (0..16).map { exitReasonLabel(it) }
        labels.forEach { assertTrue("原因码标签不能为空", it.isNotBlank()) }
        assertEquals("原因码标签不能互相撞车", labels.size, labels.distinct().size)
        labels.forEach {
            assertFalse("0~16 都是已知原因码，不该落到兜底文案：$it", it.startsWith("未知原因码"))
        }
    }

    @Test
    fun `没见过的原因码不会崩而且带着数字`() {
        assertTrue(exitReasonLabel(99).contains("99"))
        assertTrue(exitReasonLabel(-1).contains("-1"))
    }

    @Test
    fun `用户主动的退出不算异常`() {
        // 自己退出 / 权限变更（用户授权时系统杀进程再拉起）/ 划掉 / 强制停止 /
        // 依赖被卸载 / 被冻结 / 包状态变化 / 装了新版本
        listOf(1, 8, 10, 11, 12, 14, 15, 16).forEach {
            assertFalse("原因码 $it（${exitReasonLabel(it)}）不该上报", isAbnormalExit(it))
        }
    }

    @Test
    fun `闪退_ANR_被杀_被回收都算异常`() {
        listOf(0, 2, 3, 4, 5, 6, 7, 9, 13).forEach {
            assertTrue("原因码 $it（${exitReasonLabel(it)}）该上报", isAbnormalExit(it))
        }
    }

    // ── ② 挑哪些上报 ──────────────────────────────────────────────────

    @Test
    fun `只挑比上次上报更新的异常退出且按时间正序`() {
        val picked = pickExitFacts(
            listOf(
                facts(300, reason = 3),
                facts(200, reason = 10), // 用户划掉：不算异常
                facts(100, reason = 4),
                facts(50, reason = 4),   // 已经报过了
            ),
            reportedUpto = 60,
        )
        assertEquals(listOf(100L, 300L), picked.map { it.timestampMs })
    }

    @Test
    fun `装新包杀掉的旧进程不会挤掉真正的闪退`() {
        // 用户装上这版时，旧进程是以「包被更新」结束的 —— 它才是*最新*一条。
        // 只报最新一条的实现会在这里丢掉真正的闪退（4）。
        val picked = pickExitFacts(
            listOf(facts(200, reason = 16), facts(100, reason = 4)),
            reportedUpto = 0,
        )
        assertEquals(listOf(100L), picked.map { it.timestampMs })
        assertEquals(4, picked.single().reason)
    }

    @Test
    fun `回溯上限取最近几条`() {
        val all = (1..6).map { facts(it * 100L) }
        val picked = pickExitFacts(all, reportedUpto = 0, limit = 3)
        assertEquals(listOf(400L, 500L, 600L), picked.map { it.timestampMs })
    }

    @Test
    fun `全都报过就一条都不报`() {
        assertEquals(emptyList<ExitFacts>(), pickExitFacts(listOf(facts(100)), reportedUpto = 100))
        assertEquals(emptyList<ExitFacts>(), pickExitFacts(emptyList(), reportedUpto = 0))
    }

    // ── ③ 栈的截断与解码 ───────────────────────────────────────────────

    @Test
    fun `没有栈时正文会说明是系统没留`() {
        val message = exitReportMessage(facts(1_000L, trace = null))
        assertTrue(message.contains("没有留下栈"))
    }

    @Test
    fun `栈超长会截断并交代丢了多少行`() {
        val trace = (1..100).joinToString("\n") { "at com.branchbase.Frame$it(Frame$it.kt:$it)" }
        val lines = exitTraceLines(trace, maxLines = 60, maxChars = 100_000)
        assertEquals(61, lines.size) // 60 行正文 + 1 行交代
        assertTrue(lines.last().contains("还有 40 行没记"))
        assertTrue(lines.first().startsWith("at com.branchbase.Frame1"))
    }

    @Test
    fun `栈按字符数也会截断`() {
        val trace = (1..20).joinToString("\n") { "x".repeat(30) }
        val lines = exitTraceLines(trace, maxLines = 100, maxChars = 100)
        assertEquals(4, lines.size) // 3 行 * 30 字 = 90，第 4 行会超
        assertTrue(lines.last().contains("还有 17 行没记"))
    }

    @Test
    fun `一行都没有时不留空行`() {
        assertEquals(emptyList<String>(), exitTraceLines(null))
        assertEquals(emptyList<String>(), exitTraceLines("   \n  \n"))
    }

    @Test
    fun `第一行就超长时宁可截半行也不留白`() {
        val lines = exitTraceLines("y".repeat(500), maxLines = 60, maxChars = 100)
        assertEquals(1, lines.size)
        assertEquals(101, lines.single().length) // 100 字 + 省略号
    }

    @Test
    fun `文本栈直接按行读`() {
        val text = "java.lang.IllegalStateException: boom\n\tat com.branchbase.MainActivity.onCreate(MainActivity.kt:42)"
        assertEquals(text, decodeExitTrace(text.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `native tombstone 的 protobuf 抽成可打印串`() {
        // API 31+ 的 REASON_CRASH_NATIVE 给的是 protobuf（带头部的二进制），直接当文本写进日志
        // 就是一片乱码；tombstone 里最要紧的内容本来就是 ASCII 段。
        val tombstone = byteArrayOf(0x0A, 0x00, 0x1B.toByte()) +
            "signal 11 (SIGSEGV), code 1 (SEGV_MAPERR)".toByteArray() +
            byteArrayOf(0x00, 0x02) +
            "backtrace:".toByteArray() +
            byteArrayOf(0x00, 0x7F)
        val decoded = decodeExitTrace(tombstone)
        assertTrue(decoded.contains("signal 11 (SIGSEGV), code 1 (SEGV_MAPERR)"))
        assertTrue(decoded.contains("backtrace:"))
        assertFalse("不该把二进制原样写进日志", decoded.contains("\u0000"))
    }

    @Test
    fun `太短的可打印碎片会被丢掉`() {
        assertEquals(listOf("long enough run"), printableRuns(byteArrayOf(1, 2) + "long enough run".toByteArray() + byteArrayOf(0)))
    }

    // ── ④ 正文长什么样 ────────────────────────────────────────────────

    @Test
    fun `一条异常退出的正文是多行且带齐要素`() {
        val message = exitReportMessage(
            facts(
                ts = 1_756_000_000_000L,
                reason = 3,
                trace = null,
                description = "killed by lmk",
                processName = "com.branchbase",
                importance = 400,
            )
        )
        val lines = message.split('\n')
        assertTrue(lines.size >= 3)
        assertTrue("正文要有日期与时间", lines[0].contains("2025-") || lines[0].contains("2026-"))
        assertTrue(lines[0].contains("低内存"))
        assertTrue(lines[0].contains("原因码 3"))
        assertTrue(lines[1].contains("com.branchbase"))
        assertTrue(lines[1].contains("pid 4242"))
        assertTrue(lines[1].contains("后台缓存"))
        assertTrue(lines[1].contains("120MB"))
        assertTrue(lines.any { it.contains("killed by lmk") })
    }

    @Test
    fun `有栈时正文带上栈`() {
        val message = exitReportMessage(facts(1_000L, trace = "java.lang.RuntimeException: x\n\tat A.b(B.kt:1)"))
        assertTrue(message.contains("java.lang.RuntimeException: x"))
        assertTrue(message.contains("at A.b(B.kt:1)"))
        assertFalse(message.contains("没有留下栈"))
    }

    // ── ⑤ 多行正文进日志时的行格式 ──────────────────────────────────────

    @Test
    fun `多行正文在文件里逐行补表头`() {
        val entry = LogEntry(
            seq = 7,
            time = 1_756_000_000_000L,
            category = LogCategory.LOCAL_TASK,
            level = LogLevel.ERROR,
            tag = EXIT_LOG_TAG,
            message = exitReportMessage(facts(1_000L, trace = "java.lang.RuntimeException: x\n\tat A.b(B.kt:1)")),
        )
        val lines = logFileLines(entry)
        assertEquals("正文有几行，文件里就有几行", entry.message.split('\n').size, lines.size)
        val header = "[本地] [$EXIT_LOG_TAG] ERROR "
        lines.forEach { line ->
            assertTrue("每一行都要带表头，否则 grep 会错行：$line", line.contains(header))
        }
        assertEquals("复制出来的文本必须与文件里逐字节一致", logFileLines(entry).joinToString("\n"), logLine(entry))
    }

    // ── ⑥ 接线（源码级） ───────────────────────────────────────────────

    private val exitPath = "src/main/java/com/branchbase/ui/log/ExitReport.kt"
    private val loggingPath = "src/main/java/com/branchbase/ui/log/Logging.kt"

    @Test
    fun `上报只用一处_落盘标记不往设置里塞`() {
        val exit = source(exitPath)
        val code = stripComments(exit)
        assertTrue("退出上报要登记成日志锚点", exit.contains("internal const val EXIT_LOG_TAG = \"异常退出\""))
        assertTrue("标记要落自己的 prefs", exit.contains("private const val EXIT_PREFS = \"log_runtime\""))
        assertFalse("这不是设置项，不许塞进设置命名空间", code.contains("SettingsKeys"))
        assertTrue(code.contains("internal fun pickExitFacts("))
        assertTrue(code.contains("prefs.getLong(KEY_EXIT_REPORTED_UPTO, 0L)"))
        assertTrue("读完必须推进标记，否则每次启动都会重报", code.contains("prefs.edit().putLong(KEY_EXIT_REPORTED_UPTO"))
        assertTrue("API 30 以下没有这份记录", code.contains("Build.VERSION_CODES.R"))
    }

    @Test
    fun `锚点表里有异常退出`() {
        assertTrue(
            "没登记的话，导出包的 report.md 里就没有这个词，收到日志的人不知道该 grep 什么",
            LOG_ANCHORS.any { it.first == EXIT_LOG_TAG },
        )
    }

    @Test
    fun `写盘与复制共用一套行格式`() {
        val logging = source(loggingPath)
        assertTrue(logging.contains("internal fun logFileLines(e: LogEntry): List<String>"))
        assertTrue("写盘要逐行走 logFileLines，否则多行正文会留下没有表头的裸行", logging.contains("logFileLines(entry).forEach"))
        assertFalse("旧的单行拼接必须删掉，留两处迟早分家", logging.contains("private fun line(e: LogEntry)"))
    }

    @Test
    fun `应用装配时就把上一程的结局补进日志`() {
        val app = source("src/main/java/com/branchbase/BranchbaseApp.kt")
        assertTrue(app.contains("import com.branchbase.ui.log.ExitWatch"))
        assertTrue("必须在 LogManager.init 之后（写盘线程已就绪）", app.indexOf("ExitWatch.report(this)") > app.indexOf("LogManager.init(this)"))
        assertTrue(app.contains("Logger.startupOnce(\"exit-report\""))
    }
}
