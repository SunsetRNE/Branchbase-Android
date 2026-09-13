package com.branchbase.ui.repository

/**
 * 「运行中的运行」怎么持续获取状态 —— **纯逻辑，可 JVM 单测**。
 *
 * ## 为什么轮询的是「任务状态」而不是「日志」
 *
 * 远端日志在 job 结束前**根本不存在**（`GET /actions/jobs/{id}/logs` 在此之前 404），
 * 而 GitHub 也没有长轮询 / SSE 语义 —— 挂住连接不会等到新内容（能实时滚动的只有网页版，
 * 走的是未公开的内部 websocket）。所以：
 *
 * - **能频繁拉的**只有 `GET /actions/runs/{id}/jobs`（几 KB JSON，带每个 job / step 的状态与时间戳）；
 * - **日志只在「它成为定稿」的那一刻抓一次** —— 即某个 job 的 status 变成 `completed`。
 *
 * 由此，「持续获取」的正确对象是**状态**，日志是状态变化的**产物**。
 *
 * ## 为什么不做后台轮询
 *
 * 「跑完知道」这件事已经由 GitHub 的服务端 webhook 通知兜住了（[RunPollPolicy] 不管通知）；
 * 后台轮询要么被 WorkManager 的 15 分钟下限钳住、要么得常年挂前台服务。
 * 页面内轮询 + **回到前台对齐一次**（[resumeShouldForceRefresh]）已经够用。
 */
object RunPollPolicy {

    /** 运行中的运行状态值（GitHub 的 `status` 字段）。 */
    const val RUNNING = "in_progress"

    // ── 自适应间隔 ──
    /** 前 [FAST_POLLS] 次：5s（刚点进来那两分钟，用户正盯着看）。 */
    const val FAST_MS = 5_000L
    /** 之后 [MID_POLLS] 次：15s。 */
    const val MID_MS = 15_000L
    /** 再往后：30s（CI 动辄十几分钟，一直 5s 纯属浪费）。 */
    const val SLOW_MS = 30_000L
    /** 兜底上限（后退步长封顶）。 */
    const val MAX_MS = 60_000L

    const val FAST_POLLS = 24
    const val MID_POLLS = 44

    /**
     * 计费网络下的**间隔下限**。
     *
     * 不停轮询：用户正开着这一页看，停了等于功能坏了；但把 5s 抬到 15s，
     * 一次 10 分钟的 run 从 ~1MB 压到 ~0.3MB。
     */
    const val METERED_FLOOR_MS = 15_000L

    /**
     * 第 [polls] 次轮询之后该等多久。
     *
     * @param metered 当前网络是否计费（移动数据）—— 只抬高**下限**，不停止轮询。
     */
    fun intervalMs(polls: Int, metered: Boolean = false): Long {
        val base = when {
            polls < FAST_POLLS -> FAST_MS
            polls < MID_POLLS -> MID_MS
            else -> SLOW_MS
        }
        return if (metered) maxOf(base, METERED_FLOOR_MS) else base
    }

    /**
     * 连续失败 [failures] 次后的退避：5 → 10 → 20 → 40 → 60（封顶）。
     *
     * 退避与「间隔」是两套：网络抖一下不该把轮询节奏整体拖慢，只该让这一次多等一会。
     */
    fun backoffMs(failures: Int): Long {
        if (failures <= 0) return 0L
        val shift = (failures - 1).coerceAtMost(6)
        return minOf(FAST_MS shl shift, MAX_MS)
    }

    /** 这次运行现在该不该轮询（只有「运行中 + 前台」才轮）。 */
    fun shouldPoll(runStatus: String?, foreground: Boolean): Boolean =
        foreground && runStatus == RUNNING

    /**
     * 该 job 的日志此刻是否**还没生成**。
     *
     * 远端的日志 blob 只有 job 结束后才存在 —— 所以「取不到」在 job 没结束时是**正常状态**，
     * 不是失败。调用方据此显示「运行中，结束后自动补上」，而不是「加载失败」。
     */
    fun isLogPending(jobStatus: String): Boolean = jobStatus != "completed"

    /**
     * 回到前台时是否该强制对齐一次。
     *
     * 首次进入页面由正常加载负责，不算「回到前台」—— 否则每次开页都会多打一次网络。
     */
    fun resumeShouldForceRefresh(seenStart: Boolean): Boolean = seenStart
}
