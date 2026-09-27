package com.branchbase.ui.log

import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 进程内兜底：**未捕获异常**当场把栈写进日志。
 *
 * ## 为什么系统那条通道不够（2026-09-27 真机证据）
 *
 * 1.1.12 上了「上一程是怎么结束的」（`ExitReport.kt`，读系统 `ApplicationExitInfo`），它确实
 * 答对了一半 —— 用户复现两次，日志里明明白白写着：
 *
 * ```
 * 10:51:37.651 [本地] [异常退出] ERROR 上次异常退出：10:51:35.210 · Java/Kotlin 未捕获异常（闪退）（原因码 4）
 * 10:51:37.651 [本地] [异常退出] ERROR 系统描述 crash
 * 10:51:37.651 [本地] [异常退出] ERROR （系统没有留下栈：被 LMK 杀 / 被信号杀这类退出只有原因，没有调用栈）
 * ```
 *
 * 也就是说：**知道是 Java 闪退，但栈是空的** —— 这台 OnePlus / Android 16 上
 * `getTraceInputStream()` 没给出 Java 崩溃的栈（那是系统 dropbox 里的一条记录，各家 ROM 保留策略不一）。
 * 有原因没现场，等于还是修不了。
 *
 * ## 所以在这里自己抓
 *
 * `Thread.setDefaultUncaughtExceptionHandler` 的钩子在**进程死之前**、且**在崩溃线程上**执行：
 * 把 `Throwable` 打成文本、进内存缓冲、再**同步等写盘完成**（`LogManager.flush`，有界等待），
 * 之后才把控制权交还给上一个 handler（系统的 `KillApplicationHandler`）——
 * 系统的闪退记录、logcat、退出原因码那一套照旧工作，这条只是在它之外多留一份**带栈的现场**。
 *
 * ## 顺序与幂等
 *
 * - 必须**链式调用**上一个 handler（`previous?.uncaughtException`），否则等于把系统的闪退处理顶掉；
 * - `install()` 幂等：装两次会把钩子套成两层，日志里同一份栈出现两次；
 * - 挂在 `BranchbaseApp.onCreate` 里、`LogManager.init` 之后：早于写盘就绪的崩溃只能进内存缓冲
 *   （`LogManager.init` 补写缓冲是既有行为，但进程一死缓冲也就没了 —— 那种情况谁也救不了）。
 *
 * ## 与另外两条通道的分工
 *
 * | 通道 | 回答 | 局限 |
 * |---|---|---|
 * | 本文件（`闪退`） | 崩在**哪一行**（本进程现场，最准） | 只有 Java/Kotlin 未捕获异常；native abort 不走这里 |
 * | `ExitReport`（`异常退出`） | 上一程**为什么**结束（原因码 / LMK / ANR / 时间 / 内存） | 栈要看 ROM 给不给（这台就不给） |
 * | `HangWatch`（`主线程`） | 卡在**哪一行**（主线程栈，进程还活着） | 只覆盖「卡住」，不覆盖「已经死了」 |
 */
object CrashCapture {

    /** 日志 tag：导出后 `grep 闪退` 就能找到现场。 */
    internal const val CRASH_LOG_TAG = "闪退"

    /**
     * 一次最多记多少行栈。
     *
     * 崩溃现场要的是「谁抛的、崩在哪一行」，不是全量：Compose / 协程的栈动辄几百行，
     * 全写进去会把这批真正的信息淹掉（导出包也会跟着变胖）。超出的部分交代一行余量。
     */
    internal const val STACK_MAX_LINES = 80

    /** 同步等落盘的时长：崩溃线程马上要退出，但队列（后台守护线程）通常几毫秒就写完。 */
    private const val FLUSH_MS = 1_500L

    private val installed = AtomicBoolean(false)

    fun install() {
        if (!installed.compareAndSet(false, true)) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // 抓现场这件事**绝不许**把崩溃本身搞成另一种崩溃：失败就算了，照样转交系统。
            runCatching { record(thread.name, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun record(threadName: String, error: Throwable) {
        val entry = crashEntry(threadName, error, System.currentTimeMillis())
        LogManager.log(entry.category, entry.level, entry.tag, entry.message)
        // 异步队列不一定来得及（进程马上就要被系统结束）：这里同步等它落盘，超时也返回。
        LogManager.flush(FLUSH_MS)
    }
}

/**
 * 一条「闪退」现场日志（纯函数，便于单测）。
 *
 * `seq` 给 0：序号只用于日志页列表的 item key（[logItemKey]），而这条是**直接进缓冲与文件**的
 * —— `LogManager.log` 会自己分配序号，这里构造的 entry 只用来取正文。
 */
internal fun crashEntry(
    threadName: String,
    error: Throwable,
    nowMs: Long,
    maxLines: Int = CrashCapture.STACK_MAX_LINES,
): LogEntry = LogEntry(
    seq = 0L,
    time = nowMs,
    category = LogCategory.LOCAL_TASK,
    level = LogLevel.ERROR,
    tag = CrashCapture.CRASH_LOG_TAG,
    message = crashMessage(threadName, error, maxLines),
)

/**
 * 闪退现场的正文（多行；写盘时由 [logFileLines] 逐行补表头）。
 *
 * 首行必须**自解释**：这份日志会离开设备（导出包发给别人看），单看一行也要能知道
 * 「这是本进程崩了、是哪个线程、栈是这一程的而不是系统事后追认的」。
 */
internal fun crashMessage(
    threadName: String,
    error: Throwable,
    maxLines: Int = CrashCapture.STACK_MAX_LINES,
): String {
    val stack = throwableLines(error, maxLines)
    return buildString {
        append("闪退 ▸ ").append(threadName).append(" 线程未捕获异常（进程即将被系统结束）")
        append("\n")
        // 「本进程现场」这句是给读日志的人定心的：系统那份退出记录里可能只有原因、没有栈。
        append("本进程现场（系统那份退出记录里可能没有栈）：")
        stack.forEach { line ->
            append("\n").append(line)
        }
    }
}

/**
 * `Throwable` → 若干行文本（纯函数）。
 *
 * 借 `printStackTrace` 而不是手工拼：它会按 JVM 的规矩把 `Caused by` / `Suppressed` /
 * `… N more` 链一并打全（自己拼很容易漏掉 cause 链，而**根因常常就在 cause 里**）。
 *
 * 超过 [maxLines] 时截断，并留一行交代余量 —— 截断这件事必须看得见，不然读日志的人会以为栈就这些。
 */
internal fun throwableLines(error: Throwable, maxLines: Int = CrashCapture.STACK_MAX_LINES): List<String> {
    val text = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
    val lines = text.split('\n').map { it.trimEnd('\r') }.filter { it.isNotBlank() }
    if (lines.size <= maxLines) return lines
    return lines.take(maxLines) + "…（余下 ${lines.size - maxLines} 行没记）"
}
