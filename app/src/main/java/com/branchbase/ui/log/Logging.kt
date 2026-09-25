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

/**
 * 单条日志。
 *
 * [seq] 是**进程内单调递增**的序号，只为一件事存在：给列表当 key。
 * 时间戳不能当 key —— 同一毫秒落两条**同文案**的日志是常态（缓存直出那几行成串地打，
 * 例如 `L1 直出（含过期）repo-info:…` 同一毫秒两条），而 `LazyColumn` 的 key 重复会直接崩：
 * `IllegalArgumentException: Key "…" was already used`。日志越多越容易撞上，
 * 表现出来就是「日志页加载的日志一多就闪退」（2026-09-23 真机反馈）。
 */
data class LogEntry(
    val seq: Long,
    val time: Long,
    val category: LogCategory,
    val level: LogLevel,
    val tag: String,
    val message: String,
)

/**
 * 日志列表的 item key（纯函数，便于单测）。
 *
 * 只用 [LogEntry.seq]：时间戳 + 文案在「同一毫秒 + 同一条文案」时会撞车，
 * 而那正是缓存日志的常态。改成序号之后 key 在**进程内**唯一（重启会从 1 重新开始，
 * 但列表也一起重建了，不会同屏出现两代）。
 */
internal fun logItemKey(e: LogEntry): Long = e.seq

/**
 * 日志管理器：内存环形缓冲（最近 N 条），线程安全。
 */
object LogManager {
    private const val MAX = 1000
    private val buffer = ArrayDeque<LogEntry>()
    @Volatile private var appender: FileAppender? = null

    fun init(context: Context) {
        if (appender == null) synchronized(this) {
            if (appender == null) {
                val created = FileAppender(context.getExternalFilesDir(null) ?: context.filesDir)
                appender = created
                // 落盘边界之前打过的日志补写一遍。
                //
                // 起因（2026-09-22 真机日志）：`init` 原本在 `MainActivity.onCreate` 里，而
                // `Application.onCreate` 阶段的日志**只进内存环形缓冲**（那时 `appender` 还是 null），
                // 导出走的是「文件优先」，于是那些行等于从没存在过 —— 铁证是 `NetworkWatch.install`
                // 每次启动都打一行基线，而整份 907 行日志里 `[Reach]` 只出现过 1 次
                // （那是 init 之后的网络跃迁）。`FileAppender` 构造函数里那句「清理历史日志」
                // 同样打在自己被赋值之前，一起丢。
                //
                // 缓冲本来就是「最新在前」（`addFirst`），倒过来写才是时间顺序。
                synchronized(buffer) { buffer.toList().asReversed() }.forEach { created.append(it) }
            }
        }
    }

    /** 进程内单调递增的序号（[LogEntry.seq]）：列表 key 靠它保证唯一。 */
    private val seq = java.util.concurrent.atomic.AtomicLong(0)

    fun log(category: LogCategory, level: LogLevel, tag: String, message: String) {
        val entry = LogEntry(seq.incrementAndGet(), System.currentTimeMillis(), category, level, tag, message)
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
 * 「启动阶段标记**每个进程只打一次**」的闸门（[Logger.startupOnce]）。
 *
 * ## 为什么不能靠「是不是首次组合」来判断
 *
 * 启动标记是给慢帧当注脚用的（注脚 = 最近一条 UI 类日志），所以它必须**只属于启动**。
 * 第一版把它门控在 `resumeTick == 1`（`rememberPageResumeTick` 的初值），
 * 前提是「页面不会被重建」—— 真机日志（1.0.67）证明这个前提不成立：
 *
 * ```
 * 23:37:14.307 启动 ▸ 首页首帧取数   ← 启动那一次（进程开始于 23:37:14）
 * 23:39:10.834 启动 ▸ 首页首帧取数   ← +116s，用户正在仓库页；同一进程里又打了一次
 * ```
 *
 * `resumeTick` 是 `remember` 出来的，页面一被重建它就从 1 重新开始，于是标记跟着复活，
 * 把这之后几帧的慢帧注脚全改成「启动 ▸ …」—— 而 `frame-baseline.py` 的 `^启动` 场景桶
 * 会把它们算成启动帧：**报表看着正常，桶是错的**。
 *
 * 进程级的「打过没有」不受页面生命周期影响，是这件事唯一可靠的判据。
 */
object StartupMarks {
    private val printed = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** 本进程第一次用这个 key 调用时返回 true。 */
    fun firstTime(key: String): Boolean = printed.add(key)
}

/**
 * **关键路径的日志锚点**（tag → 它记录什么）。
 *
 * 存在的理由：没有真机走查时，「用户说某个操作不对」只能靠日志定位 —— 所以给每条容易出问题的
 * 新路径固定一个 tag，出问题时 `grep` 这一个词就能看到完整链路。**导出包里的 `report.md`
 * 会把这张表一起带上**，收到日志的人不必先读代码就知道该搜什么。
 *
 * 约定：
 * - tag 必须是**稳定的中文短词**（改 tag 等于改契约，会让旧日志对不上这张表）；
 * - 只记「发生了什么 + 关键参数 + 结果」，**绝不记凭据**（token / PAT / 密码一律不许进日志）；
 * - 一处动作一条，不要在重组（recomposition）里打 —— 会刷屏（见各调用点的 `LaunchedEffect` / 点击回调）。
 *
 * 钉子：`LogAnchorsTest` **双向**查 —— 表里的每个 tag 都要在源码里被用过（表不会腐烂），
 * 源码里每个 `*LOG_TAG` 常量也都要在表里（新增路径不许忘记登记；`合并` 就这样漏过一次）。
 */
internal val LOG_ANCHORS: List<Pair<String, String>> = listOf(
    "PR一条龙" to "开 PR：待提交文件数、建分支 / 提交 / 开 PR 的每一步与失败原因",
    "PR合并" to "合并：PR 号、策略（squash/merge/rebase）、结果、删分支结果",
    "敏感扫描" to "提交前扫描：命中条数；扫描不可用时被拦下的提交",
    "决策页" to "预检拦下（没有远端 ref / 第一个提交 / 统计取不到 / 已合并检查）",
    "私有仓库" to "仓库打不开（404/403）时的判定与用户选择的出路",
    "草稿" to "草稿落盘与「远端已变化」判定",
    "Git工作台" to "Git 面板：进 / 退档与返回退档、动作点击（记稳定的 action.key）、三档取数条数与失败原因、深链接与设置列表的进入",
    "合并" to "本地合并：合的是哪个分支、四条出口的结果、冲突文件数与「仓库停在合并中」、放弃合并",
    "代码页文件树" to "代码页每次列目录：项数、目录数与渲染顺序（前 6 项名字）—— 排序规则（文件夹优先 / `.` 开头最前 / A→Z）是否生效看这一条",
)

/**
 * 便捷日志 API（门面）。
 */
object Logger {    fun ui(message: String, tag: String = "Compose") =
        LogManager.log(LogCategory.UI_RENDER, LogLevel.INFO, tag, message)

    fun net(message: String, tag: String = "GitHubAPI") =
        LogManager.log(LogCategory.NETWORK, LogLevel.INFO, tag, message)

    fun remote(message: String, tag: String = "") =
        LogManager.log(LogCategory.REMOTE_EXEC, LogLevel.INFO, tag, message)

    fun local(message: String, tag: String = "") =
        LogManager.log(LogCategory.LOCAL_TASK, LogLevel.INFO, tag, message)

    /**
     * 启动阶段的 UI 类日志，**每个进程只打一次**（见 [StartupMarks]）。
     *
     * 页面被重建时不会重复打 —— 重复打会让「启动」这个场景桶混进交互段的帧。
     */
    fun startupOnce(key: String, message: String, tag: String = "启动"): Unit {
        if (StartupMarks.firstTime(key)) ui(message, tag)
    }

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
 * 清掉 1.0.41 之前留在 `files/` 根下的那份日志。
 *
 * 旧版本不带轮转（`FileAppender` 一直往 `<root>/branchbase.log` 追加），所以从旧版升上来的
 * 机器上会留着一个**孤儿文件** —— 新路径是 `logs/<日期>/branchbase.log`，而启动时的清理只扫
 * `logs/` 下的目录，扫不到它，于是它既不会被轮转也不会被清掉（用户说的「旧版历史日志」）。
 *
 * 兼容处理：**不在就跳过**（新装的机器从来不会有它）；删除失败也不抛 —— 那只是几 KB 的
 * 孤儿文件，下次启动还会再试一次，绝不该因此影响启动。
 */
internal fun cleanupLegacyLogFile(root: File): Boolean {
    val legacy = File(root, "branchbase.log")
    return legacy.isFile && legacy.delete()
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
        val removedDays = cleanupOldLogDays(File(root, LOG_ROOT), today)
        // 旧版（1.0.40 及以前）把日志直接写在 files/ 根下，不带轮转：升上来的机器上会留一个
        // 孤儿文件。它不在 logs/ 下，上面的清理扫不到，所以单独清一次（没有就跳过）。
        val removedLegacy = cleanupLegacyLogFile(root)
        if (removedDays > 0 || removedLegacy) {
            LogManager.log(
                LogCategory.LOCAL_TASK, LogLevel.INFO, "日志",
                "清理历史日志：目录 $removedDays 天" +
                    (if (removedLegacy) " + 旧版遗留文件 1 个" else "") +
                    "（只保留当天 $today）",
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