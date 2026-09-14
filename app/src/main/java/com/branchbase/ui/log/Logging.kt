package com.branchbase.ui.log

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
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

    /**
     * 最近一条 **UI 类**日志（不拷贝整个缓冲，只从表头取）。
     *
     * 给 [FrameWatch] 当「这一帧发生在哪个页面」的注脚用：页面进入/切换都会打一条 UI 日志
     * （「进入设置页」「切换到「仓库」」…），所以慢帧记下来时带上它，就能直接看出
     * **是哪次导航引起的**，而不用另外维护一份「当前页面」状态（那种状态一定会和实际路由走散）。
     *
     * [excludeTag] 用来把**慢帧自己打的日志**排掉：它也是 UI 类日志，不排掉的话下一条慢帧
     * 会把上一条慢帧的正文当成「页面」，越套越深（真机日志里出现过五层套娃）。
     *
     * 表头 = 最新（`addFirst`），所以正常情况下第一个元素就命中。
     */
    fun lastUiMessage(excludeTag: String? = null): String? = synchronized(buffer) {
        buffer.firstOrNull { it.category == LogCategory.UI_RENDER && it.tag != excludeTag }?.message
    }

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

/** 日志根目录（外部私有目录下）：`logs/<北京时间日期>/branchbase.log`。 */
private const val LOG_ROOT = "logs"

/** 日志文件名。**按天换目录、文件名固定** —— 导出与文档里说的仍是 `branchbase.log`。 */
private const val LOG_FILE_NAME = "branchbase.log"

/**
 * 日志轮转的日期戳（**北京时间**，`yyyy-MM-dd`）。
 *
 * 轮转边界是北京时间每天 `00:00:00`：跨过零点后的第一批日志落进新目录。
 * 抽成纯函数是为了能单测（`LogRotationTest`）。
 */
internal fun logDayStamp(nowMs: Long): String =
    Instant.ofEpochMilli(nowMs).atZone(fileZone).toLocalDate().toString()

/** 某一天的日志文件：`<root>/logs/<day>/branchbase.log`。 */
internal fun logFileFor(root: File, day: String): File =
    File(File(File(root, LOG_ROOT), day), LOG_FILE_NAME)

/**
 * 删掉除 [keep] 以外的历史日志目录。
 *
 * 需求就是「**只留当天**」：旧的一律丢弃（不归档、不压缩、不问），所以这里直接递归删除。
 * 只在「开新的一天」与「App 启动」两处调用，**不在写盘路径上**。
 *
 * @return 删掉的目录数（调用方可以据此记一行「清理了 N 天历史」）
 */
internal fun cleanupOldLogDays(logsRoot: File, keep: String): Int {
    val children = logsRoot.listFiles() ?: return 0
    var removed = 0
    for (child in children) {
        if (child.isDirectory && child.name != keep && child.deleteRecursively()) removed++
    }
    return removed
}

/**
 * 以**追加**方式打开日志文件。
 *
 * 必须是 append 而不是截断写：`file.bufferedWriter()` 走的是 `File.outputStream()`，
 * 那是**截断模式** —— 每批积压落盘都会把之前的行整片冲掉，磁盘上永远只剩最近一批
 * （实测常常只有一行）。而「设置 → 日志 → 导出 .log」读的正是这个文件
 * （`LogScreen.kt:121` 的 `logFile()?.readText()`），于是导出的日志几乎是空的。
 *
 * 抽成一个函数是为了让这条语义能被纯 JVM 单测钉住（见 `LogFileAppendTest`）。
 */
internal fun openLogFileForAppend(file: File): BufferedWriter =
    FileOutputStream(file, /* append = */ true).bufferedWriter()

/**
 * 文件持久化：把日志追加写入 `logs/<北京时间日期>/branchbase.log`。
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
 * ## 按天轮转（北京时间 00:00:00 换目录）
 *
 * 一天一个目录、**旧的一律丢弃**：跨零点后的第一批日志落进新目录，同时把历史目录删掉。
 * 这样日志既不会无限长（`log-redesign` 文档里那条「磁盘没有上限」），导出也不会一次吐出
 * 几个月的量 —— 代价是**只剩当天**，需要跨天对比就得当天取走。
 *
 * 内存环形缓冲（[LogManager.all]，日志页列表用）不受影响，仍是同步写入的。
 */
private class FileAppender(private val root: File) {

    /**
     * 当前正在写的文件。**跨天会换**，所以是 `var`（写盘线程改、[LogManager.logFile] 读）。
     *
     * 路径：`<root>/logs/<北京时间日期>/branchbase.log`。
     */
    @Volatile var file: File

    private val queue = ArrayBlockingQueue<LogEntry>(APPEND_QUEUE_MAX)

    /** 还在队列里没落盘的条数，供 [flush] 等待。 */
    private val pending = AtomicInteger(0)

    /** [file] 对应的日期戳，判断要不要轮转。只有写盘线程读写。 */
    private var day: String

    init {
        val today = logDayStamp(System.currentTimeMillis())
        day = today
        file = logFileFor(root, today)
        file.parentFile?.mkdirs()
        // 启动就清历史：需求是「只留当天」，所以昨天以前的一律丢弃
        val removed = cleanupOldLogDays(File(root, LOG_ROOT), today)
        if (removed > 0) {
            LogManager.log(
                LogCategory.LOCAL_TASK, LogLevel.INFO, "日志",
                "清理历史日志目录 $removed 天（只保留当天 $today）",
            )
        }
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
                val first = queue.take()
                // 跨天就换目录（并清掉历史）—— 轮转发生在**写盘线程**，不碰调用方
                rotateIfNeeded(first.time)
                var entry: LogEntry? = first
                // 追加写（**不是** `file.bufferedWriter()`：那是截断模式，会把历史冲掉，
                // 见 openLogFileForAppend 的注释）
                openLogFileForAppend(file).use { out ->
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

    /**
     * 跨过北京时间零点就换到新一天的目录，并把历史目录删掉。
     *
     * 判定用**这一批第一条日志的时间**（而不是墙钟 `now`）：补写积压时也该落在它原本那一天。
     */
    private fun rotateIfNeeded(nowMs: Long) {
        val target = logDayStamp(nowMs)
        if (target == day) return
        day = target
        file = logFileFor(root, target)
        file.parentFile?.mkdirs()
        cleanupOldLogDays(File(root, LOG_ROOT), target)
    }

    private fun line(e: LogEntry): String =
        Instant.ofEpochMilli(e.time).atZone(fileZone).format(fileTimeFmt) +
            " [${e.category.label}] [${e.tag}] ${e.level.name} ${e.message}"
}