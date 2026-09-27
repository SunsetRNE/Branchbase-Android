package com.branchbase.ui.log

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「主线程卡住了」这份现场的钉子。
 *
 * 起因同 `ExitReportTest`：那两次闪退（切到「发布」Tab 后进程静默消失）在慢帧日志里**什么都没有** ——
 * `FrameWatch` 只在帧画完时才有回调，而卡住的那一帧永远画不完。于是多了一条不依赖帧完成、
 * 也不需要设备 shell 的通道：后台探针量主线程延迟，卡住时连**主线程的调用栈**一起写进日志。
 *
 * 能钉的都钉成纯函数（正文长什么样、什么时候该写、栈怎么截）；探测循环本身要 `Looper`，
 * 单测里造不出来，所以那层用源码级断言钉接线。
 */
class HangWatchTest {

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

    private fun frames(n: Int): List<String> = (1..n).map { "at com.branchbase.F$it.run(F$it.kt:$it)" }

    // ── ① 卡住的正文 ───────────────────────────────────────────────────

    @Test
    fun `卡住的正文带齐要素`() {
        val msg = hangMessage(2_503L, "切换到「发布」", frames(2))
        assertTrue("时长要在（这是判断「是不是那 5 秒」的唯一数字）：$msg", msg.contains("≥2503ms"))
        assertTrue("页面要在，否则不知道该去哪个 Tab 复现", msg.contains("页面「切换到「发布」」"))
        assertTrue("栈的层数要报出来", msg.contains("主线程栈 2 层："))
        assertTrue(msg.contains("at com.branchbase.F1.run(F1.kt:1)"))
        assertTrue(msg.contains("at com.branchbase.F2.run(F2.kt:2)"))
        assertFalse("有栈时不该再说取不到", msg.contains("取不到"))
    }

    @Test
    fun `探针只给下界_必须写明是下界`() {
        val msg = hangMessage(2_000L, null, frames(1))
        assertTrue("探针只能证明「到这会儿还没回」，不写明会被当成精确值", msg.contains("≥"))
        assertTrue(msg.contains("下界"))
    }

    @Test
    fun `没有页面就不写页面段`() {
        assertFalse(hangMessage(2_000L, null, frames(1)).contains("页面「"))
        assertFalse(hangMessage(2_000L, "   ", frames(1)).contains("页面「"))
    }

    @Test
    fun `栈取不到时给一句解释而不是留空白`() {
        val msg = hangMessage(2_400L, null, emptyList())
        assertEquals("正文就两行（表头行 + 解释行）", 2, msg.split('\n').size)
        assertTrue("blank 的栈会让人以为是日志坏了", msg.contains("主线程栈取不到"))
    }

    @Test
    fun `超长栈只留前四十层并交代余下`() {
        val msg = hangMessage(2_100L, null, frames(100))
        val lines = msg.split('\n')
        assertEquals(2 + 1 + HangWatch.STACK_MAX_LINES, lines.size)
        assertTrue("层数写总数（100），不能只写留下的 40", msg.contains("主线程栈 100 层："))
        assertTrue("丢了多少要交代", lines.last().contains("余下 60 层没记"))
    }

    @Test
    fun `trimStack 的边界`() {
        assertEquals(emptyList<String>(), trimStack(emptyList()))
        assertEquals("正好等于上限就原样返回", frames(40), trimStack(frames(40)))
        assertEquals(HangWatch.STACK_MAX_LINES + 1, trimStack(frames(41)).size)
        assertEquals("上限为 0 时不留层", emptyList<String>(), trimStack(frames(5), maxLines = 0))
    }

    // ── ② 什么时候该写（限流） ─────────────────────────────────────────

    @Test
    fun `低于阈值不写_到阈值就写`() {
        assertFalse("1999ms 只是慢，还没到卡住", HangWatch.shouldReport(1_999L, 10_000L, 0L, 0L))
        assertTrue(HangWatch.shouldReport(HangWatch.BLOCK_MS, 10_000L, 0L, 0L))
    }

    @Test
    fun `十秒内不重复_但更严重的必须写`() {
        val last = 10_000L
        assertFalse("刚写过同样的下界，别刷屏", HangWatch.shouldReport(2_200L, last + 5_000L, last, 2_200L))
        assertTrue(
            "这次测到的下界比上次更长，说明卡得更久 —— 吞掉它会在日志里留下「只卡了 2 秒」的假象",
            HangWatch.shouldReport(4_500L, last + 5_000L, last, 2_200L),
        )
        assertTrue("超过最小间隔就可以再写一条", HangWatch.shouldReport(2_100L, last + 10_000L, last, 2_100L))
    }

    // ── ③ 恢复（把「≥」补成真实时长） ──────────────────────────────────

    @Test
    fun `恢复时补上真实卡顿时长`() {
        val msg = recoveredMessage(5_200L, "切换到「发布」")
        assertTrue(msg.contains("约卡了 5200ms"))
        assertTrue(msg.contains("页面「切换到「发布」」"))
        assertFalse(recoveredMessage(5_200L, null).contains("页面「"))
    }

    // ── ④ 正文进日志的行格式（多行必须逐行带表头） ──────────────────────

    @Test
    fun `卡住现场进日志后每行都带表头`() {
        val message = hangMessage(2_500L, "切换到「发布」", frames(3))
        val entry = LogEntry(
            seq = 9,
            time = 1_756_000_000_000L,
            category = LogCategory.LOCAL_TASK,
            level = LogLevel.WARN,
            tag = HangWatch.MAIN_THREAD_LOG_TAG,
            message = message,
        )
        val lines = logFileLines(entry)
        assertEquals("正文有几行，文件里就有几行", message.split('\n').size, lines.size)
        lines.forEach { line ->
            assertTrue("每一行都要带表头，否则按 tag grep 会漏掉栈：$line", line.contains("[本地] [主线程] WARN "))
        }
    }

    // ── ⑤ 接线（源码级） ───────────────────────────────────────────────

    private val hangPath = "src/main/java/com/branchbase/ui/log/HangWatch.kt"

    @Test
    fun `探测循环的接线不许被改坏`() {
        val src = source(hangPath)
        val code = stripComments(src)
        assertTrue("tag 要登记成常量，锚点表靠它", src.contains("internal const val MAIN_THREAD_LOG_TAG = \"主线程\""))
        assertTrue("必须是守护线程，绝不能拖住进程退出", code.contains("Thread(::probeLoop, \"bb-main-watch\")"))
        assertTrue(code.contains("isDaemon = true"))
        assertTrue("探针是往主线程投递 + 带超时等回音", code.contains("handler.post { latch.countDown() }"))
        assertTrue(code.contains("latch.await(BLOCK_MS, TimeUnit.MILLISECONDS)"))
        assertTrue("栈要在守望线程上从主线程现取", code.contains("Looper.getMainLooper().thread.stackTrace"))
        assertTrue("写日志要带页面注脚", code.contains("LogManager.lastUiMessage(excludeTag = MAIN_THREAD_LOG_TAG)"))
        assertTrue("卡住是 WARN，本地的类目（不混进 UI 页面归因）", code.contains("LogLevel.WARN, MAIN_THREAD_LOG_TAG"))
        assertFalse("这不是设置项，不许塞进设置命名空间", code.contains("SettingsKeys"))
    }

    @Test
    fun `锚点表里有主线程`() {
        assertTrue(
            "没登记的话，导出包的 report.md 里就没有这个词，收到日志的人不知道该 grep 什么",
            LOG_ANCHORS.any { it.first == HangWatch.MAIN_THREAD_LOG_TAG },
        )
    }

    @Test
    fun `启动时就挂上主线程守望`() {
        val activity = source("src/main/java/com/branchbase/MainActivity.kt")
        assertTrue(activity.contains("import com.branchbase.ui.log.HangWatch"))
        assertTrue(
            "要挂在慢帧守望之后（同一段启动路径，先有帧数据再有卡住现场）",
            activity.indexOf("HangWatch.install()") > activity.indexOf("FrameWatch.install(this)"),
        )
    }
}
