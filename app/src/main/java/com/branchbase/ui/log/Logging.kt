package com.branchbase.ui.log

import android.content.Context
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/** 日志类别（4 类：UI / 网络 / 远端 / 本地） */
enum class LogCategory(val label: String) {
    UI_RENDER("UI"),
    NETWORK("网络"),
    REMOTE_EXEC("远端"),
    LOCAL_TASK("本地"),
}

/** 日志级别 */
enum class LogLevel {
    DEBUG, INFO, WARN, ERROR,
}

/** 单条日志 */
data class LogEntry(
    val time: Long,
    val category: LogCategory,
    val level: LogLevel,
    val tag: String,
    val message: String,
)

/**
 * 日志管理器：内存环形缓冲（最近 N 条），线程安全。
 */
object LogManager {
    private const val MAX = 1000
    private val buffer = ArrayDeque<LogEntry>()
    @Volatile private var appender: FileAppender? = null

    fun init(context: Context) {
        if (appender == null) synchronized(this) {
            if (appender == null) appender = FileAppender(context.getExternalFilesDir(null) ?: context.filesDir)
        }
    }

    fun log(category: LogCategory, level: LogLevel, tag: String, message: String) {
        val entry = LogEntry(System.currentTimeMillis(), category, level, tag, message)
        synchronized(buffer) {
            buffer.addFirst(entry)
            while (buffer.size > MAX) buffer.removeLast()
        }
        appender?.append(entry)
    }

    fun all(): List<LogEntry> = synchronized(buffer) { buffer.toList() }

    fun clear() = synchronized(buffer) { buffer.clear() }

    /** 读日志文件前调一次：把队列里还没落盘的行刷下去（有界等待，超时即返回）。 */
    fun flush() = appender?.flush()

    fun logFile(): File? = appender?.file
}

/**
 * 便捷日志 API（门面）。
 */
object Logger {
    fun ui(message: String, tag: String = "Compose") =
        LogManager.log(LogCategory.UI_RENDER, LogLevel.INFO, tag, message)

    fun net(message: String, tag: String = "GitHubAPI") =
        LogManager.log(LogCategory.NETWORK, LogLevel.INFO, tag, message)

    fun remote(message: String, tag: String = "") =
        LogManager.log(LogCategory.REMOTE_EXEC, LogLevel.INFO, tag, message)

    fun local(message: String, tag: String = "") =
        LogManager.log(LogCategory.LOCAL_TASK, LogLevel.INFO, tag, message)

    fun debug(category: LogCategory, tag: String, message: String) =
        LogManager.log(category, LogLevel.DEBUG, tag, message)

    fun warn(category: LogCategory, tag: String, message: String) =
        LogManager.log(category, LogLevel.WARN, tag, message)

    fun error(category: LogCategory, tag: String, message: String) =
        LogManager.log(category, LogLevel.ERROR, tag, message)
}

private val fileTimeFmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

/** 时区查一次就够：原先每写一行都 `ZoneId.of("Asia/Shanghai")` 一次。 */
private val fileZone: ZoneId = ZoneId.of("Asia/Shanghai")

/** 写盘队列上限：日志**永远不许**阻塞业务线程，满了就丢最旧的。 */
private const val APPEND_QUEUE_MAX = 512

/**
 * 文件持久化：把日志追加写入 branchbase.log。
 *
 * ## 为什么必须异步（这不是优化，是修 bug）
 *
 * 原实现是 `file.appendText(...)` —— **每条日志一次「打开 + 写 + 关闭」**，而且就在
 * `LogManager.log` 的调用线程上。而 `LogManager.log` 的调用方大量在主线程：
 * 导航回调（`Logger.ui("打开「设置」")`）、`LaunchedEffect`、网络回调……
 * 日志文件又落在 `getExternalFilesDir()`，Android 11+ 对外部存储走 FUSE，
 * 单次 open/write/close 的代价足以吃掉好几帧 —— 表现就是「每次切页面都有一两根巨柱」，
 * 而且**与构建类型无关**（release 包上一样出现）。
 *
 * 现在 `append` 只入队；写盘交给一个后台守护线程，一次 `take` 醒来后把积压整批写掉再关文件：
 * 日志是突发式的，这样既有批量写的效率，也不会长期占着文件句柄。
 *
 * 内存环形缓冲（[LogManager.all]，日志页列表用）不受影响，仍是同步写入的。
 */
private class FileAppender(dir: File) {
    val file = File(dir, "branchbase.log")

    private val queue = ArrayBlockingQueue<LogEntry>(APPEND_QUEUE_MAX)

    /** 还在队列里没落盘的条数，供 [flush] 等待。 */
    private val pending = AtomicInteger(0)

    init {
        dir.mkdirs()
        Thread(::drainLoop, "bb-log-writer").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    /** 只入队，**绝不碰磁盘**（调用方常常是主线程）。 */
    fun append(entry: LogEntry) {
        if (!queue.offer(entry)) {
            queue.poll()
            queue.offer(entry)
        }
        pending.incrementAndGet()
    }

    /** 导出 / 读文件前把积压刷下去（有界等待，超时就返回，不拖住调用方）。 */
    fun flush(timeoutMs: Long = 300) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (pending.get() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
    }

    private fun drainLoop() {
        while (true) {
            try {
                var entry: LogEntry? = queue.take()
                file.bufferedWriter().use { out ->
                    while (entry != null) {
                        out.append(line(entry)).append('\n')
                        pending.decrementAndGet()
                        entry = queue.poll()
                    }
                }
            } catch (_: InterruptedException) {
                return
            } catch (_: Exception) {
                // 写盘失败不能把 App 带走：丢掉这一批继续循环
                pending.set(0)
            }
        }
    }

    private fun line(e: LogEntry): String =
        Instant.ofEpochMilli(e.time).atZone(fileZone).format(fileTimeFmt) +
            " [${e.category.label}] [${e.tag}] ${e.level.name} ${e.message}"
}