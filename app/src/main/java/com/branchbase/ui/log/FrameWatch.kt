package com.branchbase.ui.log

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.FrameMetrics
import java.util.Locale

/**
 * 慢帧守望：把「这一帧画了多久、卡在哪一段、当时在哪个页面」记进日志。
 *
 * ## 为什么要有它（而不是继续用「开发者选项 → dumpsys gfxinfo」）
 *
 * `adb shell dumpsys gfxinfo <pkg> framestats` 能给出一模一样的分段，但它有三个硬伤：
 *
 * 1. **只保留最近 ~120 帧**（90Hz 上约 1.3 秒）—— 想抓「点开设置页那一下」得手速极快，
 *    人去点永远慢半拍，而那一次慢帧恰恰是唯一想看的样本；
 * 2. **取数要另开终端 / 分屏**，而且 dump 本身跑在**被测量进程的主线程**上
 *    （`ActivityThread.handleDumpGfxInfo` → `ThreadedRenderer.handleDumpGfxInfo`）——
 *    测一次就自己造一根柱子，样本被自己污染；
 * 3. 只能在连着电脑、或手敲命令时用，**进不了回归**：改完一段代码，没人会再去敲一遍。
 *
 * [Window.addOnFrameMetricsAvailableListener] 拿到的分段与 framestats 同源（系统每帧下发），
 * 但发生在应用自己进程里：窗口长度只受内存限制、不打扰被测量的那次交互、还能顺带记下页面。
 *
 * ## 记什么（低噪是硬要求）
 *
 * - 单帧 ≥ [SLOW_MS]：立刻记一条完整分段（同类日志限流 [MIN_GAP_MS]，防连击刷屏）；
 * - 每 [SUMMARY_MS]：窗口内出现过慢帧才记一条小结（次数 + 最慢 + 页面 + 超预算比例）。
 *
 * ## 分段名与 framestats 的对应
 *
 * | 这里 | FrameMetrics | dumpsys 列 |
 * |---|---|---|
 * | 等待 | `UNKNOWN_DELAY_DURATION` | `IntendedVsync → HandleInputStart`（主线程没空 / 漏 vsync） |
 * | 输入 | `INPUT_HANDLING_DURATION` | `HandleInputStart → AnimationStart` |
 * | 动画 | `ANIMATION_DURATION` | `AnimationStart → PerformTraversalsStart` |
 * | 布局 | `LAYOUT_MEASURE_DURATION` | `PerformTraversalsStart → DrawStart` |
 * | 绘制 | `DRAW_DURATION` | `DrawStart → SyncStart` |
 * | 上传 | `SYNC_DURATION` | `SyncStart → IssueDrawCommandsStart` |
 * | 下发 | `COMMAND_ISSUE_DURATION` | `IssueDrawCommandsStart → SwapBuffers` |
 * | 交换 | `SWAP_BUFFERS_DURATION` | `SwapBuffers → FrameCompleted` |
 *
 * 「等待」那一栏最大 = 帧根本没开始画，**主线程被别的工作占住了** —— 这是查「切页那一刻卡一下」
 * 时最该看的一栏（对应 GPU 条形图里那根青色巨柱）。
 */
object FrameWatch {

    /**
     * 慢帧日志自己的 tag。
     *
     * 它同时是 [LogManager.lastUiMessage] 的排除标记 —— 慢帧日志也是 UI 类日志，
     * 不排除的话下一条慢帧会把上一条的正文当成「页面」，套娃到看不清（真机日志出现过五层）。
     */
    internal const val TAG = "帧"

    /** 页面名最长保留多少字符：套娃 / 超长事件名都不该把一行日志撑成一小段散文。 */
    private const val PAGE_MAX = 60

    /** 一帧的预算（ms）：超过就是丢了 vsync（60Hz 的经典阈值）。 */
    private const val BUDGET_MS = 16.0

    /** 单帧 ≥ 这个值就立刻记一条：32ms ≈ 90Hz 的三帧，肉眼已经能看出顿。 */
    private const val SLOW_MS = 32.0

    /** 同类日志最小间隔（ms）。 */
    private const val MIN_GAP_MS = 400L

    /** 小结窗口（ms）。 */
    private const val SUMMARY_MS = 60_000L

    // ── 分段下标（与 METRICS 的取数顺序一一对应；单测按它构造样本） ──

    internal const val P_WAIT = 0
    internal const val P_INPUT = 1
    internal const val P_ANIM = 2
    internal const val P_LAYOUT = 3
    internal const val P_DRAW = 4
    internal const val P_SYNC = 5
    internal const val P_ISSUE = 6
    internal const val P_SWAP = 7
    internal const val P_TOTAL = 8

    private val METRICS = intArrayOf(
        FrameMetrics.UNKNOWN_DELAY_DURATION,
        FrameMetrics.INPUT_HANDLING_DURATION,
        FrameMetrics.ANIMATION_DURATION,
        FrameMetrics.LAYOUT_MEASURE_DURATION,
        FrameMetrics.DRAW_DURATION,
        FrameMetrics.SYNC_DURATION,
        FrameMetrics.COMMAND_ISSUE_DURATION,
        FrameMetrics.SWAP_BUFFERS_DURATION,
        FrameMetrics.TOTAL_DURATION,
    )

    private val LABELS = arrayOf("等待", "输入", "动画", "布局", "绘制", "上传", "下发", "交换")

    @Volatile private var installed = false

    /**
     * 仪表是否在工作。
     *
     * **默认值来自编译通道**（`BuildConfig.FRAME_WATCH_DEFAULT`：Beta 开、正式版关），
     * 由 `MainActivity` 启动时同步一次、设置页的开关随时改（见 `SettingsKeys.FRAME_WATCH`）。
     *
     * 关掉时监听器仍然挂着，但回调**第一行就返回** —— 一次 `getMetric` 都不做，
     * 每帧只多一次空调用（挂/摘监听器要在主线程且要记住同一个实例，远比这个贵）。
     */
    @Volatile private var enabled = false

    /** 打开 / 关闭仪表（设置页与启动路径各调一次）。 */
    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun isEnabled(): Boolean = enabled

    // 小结窗口内的累计（只在主线程读写）
    private var windowStart = 0L
    private var frames = 0
    private var overBudget = 0
    private var slowCount = 0
    private var worstMs = 0.0
    private var worstPage: String? = null
    private var lastLoggedAt = 0L
    private var lastLoggedTotal = 0.0

    /** 注册慢帧监听（主线程调用；重复调用只有第一次生效）。 */
    fun install(activity: Activity) {
        if (installed) return
        installed = true
        windowStart = System.currentTimeMillis()
        activity.window.addOnFrameMetricsAvailableListener({ _, metrics, dropped ->
            onFrame(metrics, dropped)
        }, Handler(Looper.getMainLooper()))
    }

    /**
     * 这一帧该不该**单独**记一条明细（纯函数，便于单测）。
     *
     * 限流不能吞掉更慢的帧：真机日志出现过「小结写着最慢 240.9ms，明细里最慢只有 179.8ms」——
     * 那 240.9ms 的两帧挨得太近，被 [MIN_GAP_MS] 限流吃掉了，而**最慢的那几帧恰恰最不该丢**。
     * 规则：距上次记录够久就记；否则**只要这一帧比上次记下的还慢**也记 ——
     * 于是明细里的慢帧是单调升级的，最慢的一定留得下，同时天然防刷屏。
     */
    internal fun shouldLogFrame(totalMs: Double, nowMs: Long, lastAtMs: Long, lastTotalMs: Double): Boolean =
        totalMs >= SLOW_MS && (nowMs - lastAtMs >= MIN_GAP_MS || totalMs > lastTotalMs)

    private fun onFrame(metrics: FrameMetrics, dropped: Int) {
        if (!enabled) return

        // 首帧（窗口第一次绘制）的 TOTAL_DURATION 含建窗时间，天然是慢帧，不算
        if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return

        val parts = DoubleArray(METRICS.size)
        for (i in METRICS.indices) parts[i] = metrics.getMetric(METRICS[i]) / 1_000_000.0
        val total = parts[P_TOTAL]

        frames++
        if (total >= BUDGET_MS) overBudget++
        if (total >= SLOW_MS) {
            slowCount++
            if (total > worstMs) {
                worstMs = total
                worstPage = LogManager.lastUiMessage(excludeTag = TAG)
            }
        }

        val now = System.currentTimeMillis()
        if (shouldLogFrame(total, now, lastLoggedAt, lastLoggedTotal)) {
            lastLoggedAt = now
            lastLoggedTotal = total
            Logger.ui(formatSlowFrame(parts, dropped, LogManager.lastUiMessage(excludeTag = TAG)), TAG)
        }
        if (now - windowStart >= SUMMARY_MS) {
            if (slowCount > 0) {
                Logger.ui(
                    formatSummary(now - windowStart, worstMs, worstPage, slowCount, overBudget, frames),
                    TAG,
                )
            }
            windowStart = now
            frames = 0
            overBudget = 0
            slowCount = 0
            worstMs = 0.0
            worstPage = null
            lastLoggedTotal = 0.0
        }
    }

    /**
     * 单帧明细（纯函数，便于单测）。
     *
     * @param parts 各分段 ms，下标见 `P_*`（[P_TOTAL] 是总计）
     * @param dropped 监听器漏掉的帧数（回调来不及消费时系统给的值）
     * @param page 这一帧发生时最近一条 UI 日志（页面名 / 动作名）
     */
    internal fun formatSlowFrame(parts: DoubleArray, dropped: Int, page: String?): String {
        // 最长的那一段打星号：一眼就能看出这一帧是「主线程没空」还是「画得慢」
        var big = P_WAIT
        for (i in LABELS.indices) if (parts[i] > parts[big]) big = i

        val sb = StringBuilder(128)
        sb.append("慢帧 ").append(ms(parts[P_TOTAL])).append("ms（")
        LABELS.forEachIndexed { i, label ->
            if (i > 0) sb.append(" / ")
            sb.append(label).append(' ').append(ms(parts[i]))
            if (i == big) sb.append('*')
        }
        sb.append("）")
        if (!page.isNullOrBlank()) sb.append(" · 页面「").append(page.take(PAGE_MAX)).append("」")
        if (dropped > 0) sb.append(" · 漏采 ").append(dropped)
        return sb.toString()
    }

    /** 每分钟小结（纯函数，便于单测）。 */
    internal fun formatSummary(
        windowMs: Long,
        worstMs: Double,
        worstPage: String?,
        slowCount: Int,
        overBudget: Int,
        frames: Int,
    ): String {
        val sb = StringBuilder(96)
        sb.append("近 ").append(windowMs / 1000).append("s 慢帧 ")
            .append(slowCount).append(" 次（最慢 ").append(ms(worstMs)).append("ms")
        if (worstPage != null) sb.append("，").append(worstPage)
        sb.append("）；超 16ms ").append(overBudget).append('/').append(frames).append(" 帧")
        return sb.toString()
    }

    private fun ms(v: Double): String = String.format(Locale.US, "%.1f", v)
}
