package com.branchbase.ui.log

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「本进程闪退」这份现场的钉子。
 *
 * 起因是 2026-09-27 的真机日志：1.1.12 的异常退出上报确实逮住了闪退（`原因码 4 · Java/Kotlin
 * 未捕获异常`），但同一份日志的下一行写着「系统没有留下栈」—— 有原因、没现场，等于修不了。
 * 所以这一层不再问系统要栈，而是在进程死之前自己抓。
 *
 * 能钉的都钉成纯函数（正文长什么样、栈怎么截、多行正文怎么进文件）；`install()` 那层要
 * 真崩溃才会跑，单测里只能源码级断言钉接线（同 `HangWatchTest`）。
 */
class CrashCaptureTest {

    private fun source(path: String): String = File(path).readText()

    /** 去掉 Kotlin 注释后的源码（同 `ui/profile/SettingsSpecTest`）。 */
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
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    private fun boom(): Throwable = IllegalStateException("boom")

    // ── ① 正文 ────────────────────────────────────────────────────────

    @Test
    fun `正文写清哪个线程、什么异常、崩在哪一行`() {
        val text = crashMessage("main", boom())
        assertTrue("首行要自解释：这份日志会离开设备", text.startsWith("闪退 ▸ main 线程未捕获异常"))
        assertTrue("异常类型与消息要原样带上", text.contains("java.lang.IllegalStateException: boom"))
        assertTrue("最关键的是「崩在哪一行」", text.contains("CrashCaptureTest.boom"))
        assertTrue("要说明这份栈是本进程现场（系统那份可能没有）", text.contains("本进程现场"))
    }

    @Test
    fun `正文带上 Caused by 链`() {
        val text = crashMessage("main", RuntimeException("外层", java.io.IOException("内层")))
        assertTrue(text.contains("java.lang.RuntimeException: 外层"))
        assertTrue("根因常在 cause 里，链不能丢", text.contains("Caused by: java.io.IOException: 内层"))
    }

    @Test
    fun `非主线程崩了也要写清楚是哪个线程`() {
        val text = crashMessage("bb-log-writer", boom())
        assertTrue(text.startsWith("闪退 ▸ bb-log-writer 线程未捕获异常"))
    }

    // ── ② 截断 ────────────────────────────────────────────────────────

    @Test
    fun `超长栈被截断并交代余量`() {
        val lines = throwableLines(boom(), maxLines = 3)
        assertEquals("3 行栈 + 1 行余量交代", 4, lines.size)
        assertTrue("截断这件事必须看得见", lines.last().startsWith("…（余下 "))
        assertTrue(lines.last().endsWith(" 行没记）"))
    }

    @Test
    fun `上限为 0 时只交代余量`() {
        val lines = throwableLines(boom(), maxLines = 0)
        assertEquals(1, lines.size)
        assertTrue(lines.single().startsWith("…（余下 "))
    }

    @Test
    fun `栈行不带换行与空行残留`() {
        val lines = throwableLines(RuntimeException("外层\n第二行", boom()))
        assertTrue(lines.isNotEmpty())
        assertFalse("每行都得是完整一行，否则写盘后会变成没有表头的裸行", lines.any { it.contains('\n') || it.contains('\r') })
        assertFalse(lines.any { it.isBlank() })
    }

    // ── ③ 进日志 ──────────────────────────────────────────────────────

    @Test
    fun `entry 是本地类目 ERROR、tag 为闪退`() {
        val entry = crashEntry("main", boom(), nowMs = 1_772_000_000_000L)
        assertEquals(0L, entry.seq)
        assertEquals(1_772_000_000_000L, entry.time)
        assertEquals(LogCategory.LOCAL_TASK, entry.category)
        assertEquals(LogLevel.ERROR, entry.level)
        assertEquals("闪退", entry.tag)
        assertTrue(entry.message.startsWith("闪退 ▸ main 线程未捕获异常"))
    }

    @Test
    fun `写进文件后每行都带表头`() {
        val entry = crashEntry("main", boom(), nowMs = 1_772_000_000_000L)
        val lines = logFileLines(entry)
        val header = formatLogTime(1_772_000_000_000L) + " [本地] [闪退] ERROR "
        assertTrue("至少要有首行 + 若干栈行", lines.size > 2)
        assertTrue("导出包里 grep 闪退 必须命中每一行", lines.all { it.startsWith(header) })
    }

    // ── ④ 接线（源码级） ──────────────────────────────────────────────

    private val crashPath = "src/main/java/com/branchbase/ui/log/CrashCapture.kt"

    @Test
    fun `钩子必须转交上一个 handler`() {
        val src = source(crashPath)
        val code = stripComments(src)
        assertTrue("tag 要登记成常量，锚点表靠它", src.contains("internal const val CRASH_LOG_TAG = \"闪退\""))
        assertTrue(code.contains("Thread.setDefaultUncaughtExceptionHandler"))
        assertTrue(
            "不转交就等于把系统的闪退处理（logcat / 退出记录 / 弹窗）顶掉",
            code.contains("previous?.uncaughtException(thread, error)"),
        )
        assertTrue("抓现场失败绝不能把崩溃变成另一种崩溃", code.contains("runCatching { record(thread.name, error) }"))
        assertFalse("这不是设置项，不许塞进设置命名空间", code.contains("SettingsKeys"))
    }

    @Test
    fun `install 幂等且同步等落盘`() {
        val code = stripComments(source(crashPath))
        assertTrue("装两次会套成两层钩子，同一份栈会记两遍", code.contains("AtomicBoolean(false)"))
        assertTrue(code.contains("compareAndSet(false, true)"))
        assertTrue("崩溃线程马上要退出，必须同步等队列落盘", code.contains("LogManager.flush(FLUSH_MS)"))
        assertTrue("栈要靠 JVM 自己打（cause / suppressed 链都在这里）", code.contains("error.printStackTrace(PrintWriter(it))"))
    }

    @Test
    fun `启动装配里挂上崩溃捕获`() {
        val app = source("src/main/java/com/branchbase/BranchbaseApp.kt")
        assertTrue(app.contains("import com.branchbase.ui.log.CrashCapture"))
        assertTrue(app.contains("CrashCapture.install()"))
        assertTrue(
            "要挂在写盘就绪之后：早于它崩掉的进程连文件都没有",
            app.indexOf("CrashCapture.install()") > app.indexOf("LogManager.init(this)"),
        )
    }

    @Test
    fun `锚点表里有闪退`() {
        assertTrue(
            "没登记的话，导出包的 report.md 里就没有这个词，收到日志的人不知道该 grep 什么",
            LOG_ANCHORS.any { it.first == CrashCapture.CRASH_LOG_TAG },
        )
    }
}
