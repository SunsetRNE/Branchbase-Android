package com.branchbase.ui.log

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

/**
 * 「上一程是怎么结束的」上报。
 *
 * ## 为什么需要它（2026-09-27 真机闪退）
 *
 * 用户反馈「莫名其妙的闪退」，导出的日志包里**一行异常都没有**（39 条，ERROR 0 / WARN 0）：
 * 最后一行是 `09:12:24.205 L1 直出 repo-list:releases:…`（刚切到「发布」Tab），下一条就是
 * `09:12:28.890 启动 ▸ 日志初始化` —— 进程在中间那 4.7 秒里**直接没了**，而我们没有任何通道
 * 知道它是怎么没的：
 * 1. 进程被 `SIGKILL` / OOM 杀掉时，Java 层根本没有机会执行任何代码（写不进日志）；
 * 2. 未捕获异常虽然能写，但这个 App **从未安装** `Thread.setDefaultUncaughtExceptionHandler`；
 * 3. 容器里拿不到 logcat / tombstone（设备 shell 通道未授权），用户也不可能天天抓 bugreport。
 *
 * 系统其实**一直记着**：`ActivityManager.getHistoricalProcessExitReasons(pkg, 0, n)`（API 30+）保留最近
 * 若干次进程退出的**原因、时间、优先级、内存占用，以及崩溃/ANR 时的调用栈**（`getTraceInputStream()`）。
 * 这份记录是系统写的，不需要我们当时活着 —— 所以它既能补上「上一次已经发生过的闪退」，
 * 也能覆盖「进程被杀得一句话都来不及说」的那一类。
 *
 * ## 上报策略
 * - **不只是最新一条**：装新包时旧进程会以「包被更新」结束，那才是最新一条；只报最新一条会把
 *   真正的闪退挤掉。所以按时间**逐个**报「比上次报过的时间戳新」的异常退出（上限 [EXIT_REPORT_LIMIT] 条）。
 * - **跨进程去重**：上报到哪一刻记在 `log_runtime` 这份**独立** prefs 里（不是设置项 —— 它没有
 *   用户可见的语义，塞进 `branchbase` 命名空间会污染「设置键只在 SettingsKeys 里声明」的规矩）。
 * - **栈进日志时逐行补表头**（[logFileLines]）：日志格式契约是「一行一条、每行都有表头」，
 *   栈直接写进去会留下没有表头的裸行，`grep` 立刻就错行。
 */
internal const val EXIT_LOG_TAG = "异常退出"

/** 一次最多回溯上报几次异常退出：够覆盖「装新包杀旧进程」这一档，又不至于把陈年旧账全翻出来。 */
internal const val EXIT_REPORT_LIMIT = 3

/** 向系统一次要多少条历史退出记录（要得越多越过期，取 8 条足够覆盖上面那 [EXIT_REPORT_LIMIT] 条）。 */
internal const val EXIT_REPORT_HISTORY = 8

/** 栈最多留多少行。 */
internal const val EXIT_TRACE_MAX_LINES = 60

/** 栈最多留多少字符（native tombstone 动辄上百 KB，全写进去会把导出包撑爆）。 */
internal const val EXIT_TRACE_MAX_CHARS = 8000

/** 一次最多从系统那份 trace 里读多少字节：它是全局环形缓冲里的文件，可能很大，不值得整份读进来。 */
internal const val EXIT_TRACE_READ_MAX_BYTES = 64 * 1024

/**
 * 「已上报到哪个时刻」的落盘位置。
 *
 * 单独一份 prefs（而不是 `SettingsKeys.PREFS`）：这是**运行时状态**，不是设置项。
 */
private const val EXIT_PREFS = "log_runtime"
private const val KEY_EXIT_REPORTED_UPTO = "exit_reported_upto"

/**
 * 一次进程退出的**事实**：从 `ApplicationExitInfo` 抄出来的纯数据。
 *
 * 抽成 data class 是为了让「挑哪些上报 / 怎么写成日志正文」这两件事能在纯 JVM 单测里钉住 ——
 * `ApplicationExitInfo` 本身在单测里造不出来（final 类、无公开构造函数）。
 */
internal data class ExitFacts(
    val timestampMs: Long,
    val reason: Int,
    val importance: Int,
    val pid: Int,
    val processName: String,
    val description: String?,
    val pssKb: Long,
    val rssKb: Long,
    val trace: String?,
)

/**
 * 原因码 → 中文标签（纯函数）。
 *
 * 参数是 `ApplicationExitInfo.getReason()` 的**原始数值**（这里故意不引 `REASON_*` 常量：
 * 常量在单测里要依赖 android.jar，而这张表只回答「这个数字是什么意思」）。
 * 数值已对着 `platforms/android-35/android.jar` 的 `javap -constants` 核过。
 */
internal fun exitReasonLabel(reason: Int): String = when (reason) {
    0 -> "未知"
    1 -> "自己退出（exit / System.exit）"
    2 -> "被信号杀死"
    3 -> "系统低内存回收（LMK）"
    4 -> "Java/Kotlin 未捕获异常（闪退）"
    5 -> "native 崩溃（闪退）"
    6 -> "无响应（ANR）"
    7 -> "初始化失败"
    8 -> "运行时权限变更"
    9 -> "系统判定占用过高"
    10 -> "用户从最近任务划掉"
    11 -> "被强制停止"
    12 -> "依赖被卸载或停用"
    13 -> "其它"
    14 -> "被系统冻结（缓存进程）"
    15 -> "包状态变化"
    16 -> "包被更新（装了新版本）"
    else -> "未知原因码 $reason"
}

/**
 * 值得上报的退出：把「正常的、用户主动的」那几种排掉，剩下的都是我们想知道的。
 *
 * 排掉：`1` 自己退出、`8` 权限变更（用户授权时系统会杀掉进程再拉起，属正常）、
 * `10` 用户划掉、`11` 被强制停止、`12` 依赖被卸载、`14` 被冻结、`15`（包状态变化）与 `16`（装新包）。
 *
 * 保留里最要紧的是 `4/5`（闪退）、`6`（ANR）、`3`（被 LMK 杀）、`2`（被信号杀）、`13`（其它 ——
 * 系统归不了类的那一类往往最可疑）。`0` 未知也保留：总比漏掉强。
 */
internal fun isAbnormalExit(reason: Int): Boolean = when (reason) {
    1, 8, 10, 11, 12, 14, 15, 16 -> false
    else -> true
}

/**
 * 退出时的进程优先级 → 人话。
 *
 * 分档取自 `ActivityManager.RunningAppProcessInfo.IMPORTANCE_*`：前台 100、
 * 前台服务 125、可见 200、可感知 230、常驻服务 300/325、后台缓存 400、空进程 500、不要了 1000。
 * 它一起说明了「它是**在什么处境下**被杀的」—— 后台缓存态被杀大多是系统回收，前台被杀则是闪退。
 */
internal fun exitImportanceLabel(importance: Int): String = when {
    importance <= 125 -> "前台"
    importance <= 230 -> "可见"
    importance <= 325 -> "服务 / 可感知"
    importance <= 400 -> "后台缓存"
    else -> "空进程 / 系统不要了"
}

/**
 * 挑出**这次启动该上报**的退出记录（纯函数）。
 *
 * @param facts 本次读到的全部记录（系统给的顺序是新→旧，这里不依赖顺序）
 * @param reportedUpto 上次上报到的时间戳（`0` = 从没报过）
 * @return 该上报的记录，按时间**正序**（读日志的人从旧看到新）、最多 [limit] 条
 */
internal fun pickExitFacts(
    facts: List<ExitFacts>,
    reportedUpto: Long,
    limit: Int = EXIT_REPORT_LIMIT,
): List<ExitFacts> = facts
    .filter { it.timestampMs > reportedUpto && isAbnormalExit(it.reason) }
    // 取**最近的** limit 条（同一次启动里旧账翻太多会淹掉真正要紧的那条），再按时间正序排
    .sortedByDescending { it.timestampMs }
    .take(limit)
    .sortedBy { it.timestampMs }

/**
 * 系统留下的那份 trace 的**原始字节** → 可读行（纯函数）。
 *
 * 两种形态：
 * - **文本**（Java 崩溃栈 / ANR traces）：没有 NUL 字节，直接按 UTF-8 读。
 * - **native tombstone**（API 31+ 的 `REASON_CRASH_NATIVE` 给的是 **protobuf**）：直接当文本写进
 *   日志是一片乱码。只抽可打印串 —— tombstone 里最要紧的那几样（`signal 11 (SIGSEGV)`、
 *   崩溃地址、`backtrace:` 后面那一串符号）本来就是 ASCII 可打印段。
 */
internal fun decodeExitTrace(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""
    if (bytes.none { it == 0.toByte() }) return bytes.toString(Charsets.UTF_8)
    return printableRuns(bytes).joinToString("\n")
}

/**
 * 从字节流里抽可打印串（`0x20`~`0x7E`，长度 ≥ [minLen]），按出现顺序返回。
 *
 * 上界取 [EXIT_TRACE_MAX_CHARS] 的两倍：留给 [exitTraceLines] 去截，这里只保证不把内存撑大。
 */
internal fun printableRuns(bytes: ByteArray, minLen: Int = 6): List<String> {
    val out = mutableListOf<String>()
    var total = 0
    val sb = StringBuilder()
    fun flush() {
        if (sb.length >= minLen) {
            out += sb.toString()
            total += sb.length
        }
        sb.setLength(0)
    }
    for (b in bytes) {
        if (total >= EXIT_TRACE_MAX_CHARS * 2) break
        if (b in 0x20..0x7E) sb.append(b.toInt().toChar()) else flush()
    }
    flush()
    return out
}

/**
 * 栈的整理（纯函数）：按行截断、按字符截断，被丢掉的量在末尾交代清楚。
 *
 * 不截断不行：native 崩溃的 tombstone 上百 KB，全塞进日志既撑爆导出包，
 * 也让「最近 1000 条」的内存环形缓冲被一条日志挤掉一大截。
 */
internal fun exitTraceLines(
    trace: String?,
    maxLines: Int = EXIT_TRACE_MAX_LINES,
    maxChars: Int = EXIT_TRACE_MAX_CHARS,
): List<String> {
    val text = trace?.trim().orEmpty()
    if (text.isEmpty()) return emptyList()
    val out = mutableListOf<String>()
    var used = 0
    var dropped = 0
    text.split('\n').forEach { raw ->
        val line = raw.trimEnd()
        if (out.size >= maxLines || used + line.length > maxChars) {
            dropped++
            return@forEach
        }
        out += line
        used += line.length
    }
    if (out.isEmpty()) {
        // 第一行就超限（没有换行的巨行）：宁可截半行，也别一条都不留
        val head = text.take(maxChars)
        out += if (head.length < text.length) "$head…" else head
        dropped = 0
    }
    if (dropped > 0) out += "…（还有 $dropped 行没记：只留前 $maxLines 行 / $maxChars 字）"
    return out
}

/**
 * 一次异常退出的日志正文（**多行**，由 [logFileLines] 逐行补表头）。
 */
internal fun exitReportMessage(f: ExitFacts): String {
    val lines = mutableListOf<String>()
    lines += "上次异常退出：${logDayStamp(f.timestampMs)} ${formatLogTime(f.timestampMs)}" +
        " · ${exitReasonLabel(f.reason)}（原因码 ${f.reason}）"
    val where = buildString {
        append("进程 ").append(f.processName.ifBlank { "（无名）" })
        append(" pid ").append(f.pid)
        append(" · 退出时 ").append(exitImportanceLabel(f.importance)).append("（${f.importance}）")
        if (f.pssKb > 0) append(" · 内存 PSS ").append(f.pssKb / 1024).append("MB")
        if (f.rssKb > 0) append(" / RSS ").append(f.rssKb / 1024).append("MB")
    }
    lines += where
    if (!f.description.isNullOrBlank()) lines += "系统描述 ${f.description}"
    val trace = exitTraceLines(f.trace)
    if (trace.isEmpty()) {
        lines += "（系统没有留下栈：被 LMK 杀 / 被信号杀这类退出只有原因，没有调用栈）"
    } else {
        lines += "栈 ${trace.size} 行："
        lines += trace
    }
    return lines.joinToString("\n")
}

/**
 * 读系统记录并写日志。
 *
 * 调用点只有一个：`BranchbaseApp.onCreate` 里 `LogManager.init` 之后（写盘线程已就绪、
 * 而且这是启动路径上唯一一个「早于任何业务代码」的稳定插入点）。
 *
 * 级别给 `ERROR`：导出的 `report.md` 会统计 ERROR 条数，用户看到「ERROR 1」就知道这次导出里
 * 有事故可看，不用逐行翻。
 */
internal object ExitWatch {

    fun report(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        // 只查自己的包（查别人的包要 REAL_GET_TASKS，而且这是「我的进程怎么死的」这件事，
        // 别的包与我们无关）；pid 传 0 = 本包的所有进程。
        val infos = runCatching {
            manager.getHistoricalProcessExitReasons(context.packageName, 0, EXIT_REPORT_HISTORY)
        }.getOrNull() ?: return
        val facts = infos.map { it.toFacts() }
        val prefs = context.applicationContext.getSharedPreferences(EXIT_PREFS, Context.MODE_PRIVATE)
        val reportedUpto = prefs.getLong(KEY_EXIT_REPORTED_UPTO, 0L)
        pickExitFacts(facts, reportedUpto).forEach { f ->
            LogManager.log(LogCategory.LOCAL_TASK, LogLevel.ERROR, EXIT_LOG_TAG, exitReportMessage(f))
        }
        // 标记推到「现在」而不是「最后一条记录的时间」：这次启动之前的所有退出都已经处理过，
        // 之后的退出必然带着更大的时间戳（那属于下一个进程），不会被这次的上报吞掉。
        prefs.edit().putLong(KEY_EXIT_REPORTED_UPTO, System.currentTimeMillis()).apply()
    }

    /**
     * 只抄需要的那几样，并且**立刻**把栈读出来：`getTraceInputStream()` 会抛 `IOException`，
     * 而读失败（系统已回收 / 被别家崩溃覆盖）不该让上报整体失败 —— 原因为主，栈是加分项。
     *
     * `pss` / `rss` 的单位是 **kB**（AOSP `ApplicationExitInfo.getPss` 的注释：「in kB」），
     * 所以这里存的已经是 kB，打印时才换算成 MB。
     */
    private fun ApplicationExitInfo.toFacts(): ExitFacts = ExitFacts(
        timestampMs = timestamp,
        reason = reason,
        importance = importance,
        pid = pid,
        processName = processName.orEmpty(),
        description = description,
        pssKb = runCatching { pss }.getOrDefault(0L),
        rssKb = runCatching { rss }.getOrDefault(0L),
        trace = runCatching { readTraceCapped() }.getOrNull(),
    )

    /** 读系统留的 trace，最多 [EXIT_TRACE_READ_MAX_BYTES] 字节（超出部分本来也会被截掉）。 */
    private fun ApplicationExitInfo.readTraceCapped(): String? {
        val stream = traceInputStream ?: return null
        val bytes = stream.use { input ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8 * 1024)
            while (buffer.size() < EXIT_TRACE_READ_MAX_BYTES) {
                val read = input.read(chunk)
                if (read <= 0) break
                buffer.write(chunk, 0, minOf(read, EXIT_TRACE_READ_MAX_BYTES - buffer.size()))
            }
            buffer.toByteArray()
        }
        return decodeExitTrace(bytes)
    }
}
