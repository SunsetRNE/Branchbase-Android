package com.branchbase.ui.log

import android.content.Context
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque

/** 日志类别（4 类，对齐 docs/logging-design.md） */
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

/** 文件持久化：把日志追加写入 branchbase.log */
private class FileAppender(dir: File) {
    val file = File(dir, "branchbase.log")

    init { dir.mkdirs() }

    fun append(entry: LogEntry) {
        try {
            val t = Instant.ofEpochMilli(entry.time).atZone(ZoneId.of("Asia/Shanghai")).format(fileTimeFmt)
            file.appendText("$t [${entry.category.label}] [${entry.tag}] ${entry.level.name} ${entry.message}\n")
        } catch (_: Exception) {
        }
    }
}