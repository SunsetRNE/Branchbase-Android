package com.branchbase.ui.log

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 主线程卡顿守望：把「**主线程被占住多久、卡在哪一行**」记进日志。
 *
 * ## 为什么必须有它（[FrameWatch] 天生抓不到这一类）
 *
 * [FrameWatch] 监听的是系统下发的 `FrameMetrics` —— **一帧画完了才有回调**。而我们真实遇到的
 * 两次闪退正是这个盲区：切到「发布」Tab 之后那一帧**再也没画完**，进程在约 5 秒后静默消失，
 * 日志里最后一条还是切页前的那一帧（105.6ms），ERROR/WARN 计数 0、没有任何栈。
 * 「阻塞的那一帧」在 FrameWatch 的视角里等于**不存在** —— 于是我们手上只有症状，没有现场。
 *
 * ## 它怎么工作
 *
 * 后台守护线程每 [PROBE_MS] 往主线程 `Handler` 投一枚探针（`latch.countDown()`），然后带超时
 * 等回音：
 *
 * - 正常：毫秒级返回，代价是每秒一次空投递，**可忽略**；
 * - 异常：主线程 [BLOCK_MS] 没回 → 卡住了。此时**从守望线程**（不是主线程，主线程正卡着）
 *   把 `主线程卡住 ≥Nms · 页面「…」` 与**主线程当前的调用栈**写进日志。
 *
 * 写盘走 `LogManager` 的异步队列（`FileAppender` 自己的线程），所以**即使主线程一直卡到被系统杀掉，
 * 这几行也已经落盘** —— 下一次导出的日志包里就能看到「卡在哪一行」。这是它与
 * [ExitWatch][ExitReport]（读系统 `ApplicationExitInfo`，只能事后给原因码 + 系统留下的 trace）
 * 的分工：一个给**现场**，一个给**结论**。
 *
 * ## 边界（诚实写下来，免得日志被误读）
 *
 * - 「≥Nms」是**下界**：探针只能证明「到这为止还没回」，真实卡顿时长由恢复时的
 *   `主线程恢复（约卡了 Nms）` 那一行补全；
 * - 卡在 native（JNI / Rust core）里时，栈里看不到被调用的 native 函数内部帧，只能看到
 *   `nativeXxx` 这一层 —— 那正是 [ExitReport] 的 tombstone 可打印串要补的位；
 * - 不加任何设置开关：它只在「已经出问题」时写日志（正常时零输出），属于诊断地基而不是可选项。
 */
object HangWatch {

    /** 本仪表自己的 tag（写进日志与锚点表，见 `LogManager.lastUiMessage` 的排除用法）。 */
    internal const val MAIN_THREAD_LOG_TAG = "主线程"

    /** 探针间隔（ms）：每秒一次，正常时只是一次空投递。 */
    private const val PROBE_MS = 1_000L

    /** 探针超时（ms）：主线程这么久没回音 = 判定卡住。 */
    internal const val BLOCK_MS = 2_000L

    /** 同类日志最小间隔（ms）：一直卡着时不要每 5 秒刷一条同样的栈。 */
    private const val MIN_GAP_MS = 10_000L

    /** 栈最多记多少层：真机上 40 层足够认出手指头，再多的都是系统胶水帧。 */
    internal const val STACK_MAX_LINES = 40

    @Volatile private var installed = false

    @Volatile private var enabled = true

    /** 打开 / 关闭（单测与极端省电场景用；默认开）。 */
    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun isEnabled(): Boolean = enabled

    /** 启动守望线程（幂等；可重复调用）。 */
    fun install() {
        if (installed) return
        installed = true
        Thread(::probeLoop, "bb-main-watch").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    private fun probeLoop() {
        val handler = Handler(Looper.getMainLooper())
        var lastAt = 0L
        var lastElapsed = 0L
        var stuckSince = 0L
        while (true) {
            val started = System.currentTimeMillis()
            val latch = CountDownLatch(1)
            handler.post { latch.countDown() }
            val answered = try {
                latch.await(BLOCK_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                return
            }
            val elapsed = System.currentTimeMillis() - started

            if (answered) {
                // 上一次判定卡住之后第一次拿到回音 = 主线程活了，把真实时长补上
                if (stuckSince > 0L) {
                    if (enabled) logRecovered(started - stuckSince)
                    stuckSince = 0L
                    lastAt = 0L
                    lastElapsed = 0L
                }
            } else if (enabled) {
                if (stuckSince == 0L) stuckSince = started
                val now = System.currentTimeMillis()
                if (shouldReport(elapsed, now, lastAt, lastElapsed)) {
                    lastAt = now
                    lastElapsed = elapsed
                    report(elapsed)
                }
            }

            try {
                Thread.sleep(PROBE_MS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    /**
     * 这一次超时该不该**写**下来（纯函数，便于单测）。
     *
     * 限流不能吞掉更严重的现场：一直卡着时每 [MIN_GAP_MS] 最多一条，但如果这一次测到的下界
     * **比上次记下的更长**，说明卡得更久了，也得记 —— 否则日志里会留下「只卡了 2 秒」的假象。
     */
    internal fun shouldReport(elapsedMs: Long, nowMs: Long, lastAtMs: Long, lastElapsedMs: Long): Boolean =
        elapsedMs >= BLOCK_MS && (nowMs - lastAtMs >= MIN_GAP_MS || elapsedMs > lastElapsedMs)

    private fun report(elapsedMs: Long) {
        val page = LogManager.lastUiMessage(excludeTag = MAIN_THREAD_LOG_TAG)
        LogManager.log(
            LogCategory.LOCAL_TASK, LogLevel.WARN, MAIN_THREAD_LOG_TAG,
            hangMessage(elapsedMs, page, mainThreadStack()),
        )
    }

    private fun logRecovered(stuckMs: Long) {
        val page = LogManager.lastUiMessage(excludeTag = MAIN_THREAD_LOG_TAG)
        LogManager.log(
            LogCategory.LOCAL_TASK, LogLevel.WARN, MAIN_THREAD_LOG_TAG,
            recoveredMessage(stuckMs, page),
        )
    }

}

/** 主线程当前的调用栈（在守望线程上取，安全；native 内部帧看不到，见类注释）。 */
internal fun mainThreadStack(): List<String> =
    Looper.getMainLooper().thread.stackTrace.map { it.toString() }

/**
 * 卡住那一刻的正文（纯函数，便于单测）。
 *
 * 多行是有意的：调用栈天然多行，而日志格式的契约是「一行一条、每行都带表头」——
 * 逐行补表头的活由 `logFileLines` 统一做（1.1.12 起），所以这里只管正文。
 */
internal fun hangMessage(
    elapsedMs: Long,
    page: String?,
    stack: List<String>,
    maxLines: Int = HangWatch.STACK_MAX_LINES,
): String {
    val sb = StringBuilder(256)
    sb.append("主线程卡住 ≥").append(elapsedMs).append("ms（后台每 1s 投一次探针，这次 ")
        .append(elapsedMs).append("ms 没回；「≥」是下界）")
    if (!page.isNullOrBlank()) sb.append(" · 页面「").append(page).append('」')
    val kept = trimStack(stack, maxLines)
    if (kept.isEmpty()) {
        sb.append('\n').append("主线程栈取不到（可能整个进程都卡在 native / 内核里，见异常退出上报）")
    } else {
        sb.append('\n').append("主线程栈 ").append(stack.size).append(" 层：")
        kept.forEach { sb.append('\n').append(it) }
    }
    return sb.toString()
}

/** 主线程恢复时的正文（纯函数，便于单测）：补上「到底卡了多久」。 */
internal fun recoveredMessage(stuckMs: Long, page: String?): String {
    val sb = StringBuilder(64)
    sb.append("主线程恢复：约卡了 ").append(stuckMs).append("ms 后继续（上一条的「≥」到此为止）")
    if (!page.isNullOrBlank()) sb.append(" · 页面「").append(page).append('」')
    return sb.toString()
}

/** 只留前 [maxLines] 层，末尾交代还有多少层没记（纯函数，便于单测）。 */
internal fun trimStack(frames: List<String>, maxLines: Int = HangWatch.STACK_MAX_LINES): List<String> {
    if (maxLines <= 0) return emptyList()
    if (frames.size <= maxLines) return frames
    return frames.take(maxLines) + "…（余下 ${frames.size - maxLines} 层没记）"
}
