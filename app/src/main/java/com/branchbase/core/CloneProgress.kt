package com.branchbase.core

import org.json.JSONObject

/**
 * clone 进度：Rust 侧快照（`core/src/git/progress.rs`）的 Kotlin 视图。
 *
 * ## 为什么进度从引擎来，而不是 UI 自己估
 *
 * 「拉取仓库」在手机上可能跑几十秒（浅 clone 一个中等仓库也有几百个对象）。
 * 旧实现只在列表上方挂一行「正在克隆…」——**它在不在动、动到哪一步了，用户完全看不到**，
 * 于是一个正常的慢 clone 与一次已经卡死的 clone 长得一模一样。
 *
 * 引擎侧本来就知道这些数字（libgit2 的 `transfer_progress` / checkout 回调），
 * 只是从没往上传。这里不猜、不插值：**没有数字就不给百分比**
 * （进度条走不确定态），而不是拿一个假百分比糊弄。
 *
 * ## 百分比口径（阶段加权，纯函数，可 JVM 单测）
 *
 * | 阶段 | 区间 | 依据 |
 * |---|---|---|
 * | 连接远端 / 准备 | 不确定 | 握手阶段没有可用的分母 |
 * | 接收对象 | 5% → 70% | `received / total` |
 * | 解析增量 | 70% → 85% | `indexed / total` |
 * | 检出文件 | 85% → 99% | `checkoutDone / checkoutTotal` |
 * | 写入引用（收尾） | 99% | 只剩最后的 ref / HEAD |
 * | 完成 | 100% | — |
 *
 * 五个区间**首尾相接、只增不减**：libgit2 的阶段本身是单向的（收 → 解 → 检出 → 收尾），
 * 所以进度条不会来回跳。远端不报总数（`total == 0`）时对应阶段返回 `null`。
 *
 * 检出上界刻意是 **99 而不是 100**：检出刚满时引擎的马达还没停 —— 接下来还要写
 * `.git/HEAD` 与分支引用，那一段属于 `finalize`。若检出直接画到 100%，收尾阶段就会
 * 从 100 退回 99（单测 `百分比随阶段单调不减` 钉的就是这一条）。
 */
data class CloneProgress(
    val phase: Phase,
    /** 已接收对象数。 */
    val received: Int,
    /** 对象总数（0 = 远端没报，进度不可知）。 */
    val total: Int,
    /** 已解析（入库）对象数。 */
    val indexed: Int,
    /** 已接收字节数。 */
    val bytes: Long,
    /** 已检出文件数。 */
    val checkoutDone: Int,
    /** 待检出文件数（0 = 还没进检出阶段）。 */
    val checkoutTotal: Int,
) {

    /** 阶段（与 `core/src/git/progress.rs` 的 `phase` 字符串一一对应）。 */
    enum class Phase {
        /** 没有 clone 在跑。 */
        IDLE,
        /** 已开始、还没拿到对象计数（握手 / 协商）。 */
        CONNECT,
        /** 正在接收对象。 */
        RECEIVE,
        /** 对象收齐、正在解增量。 */
        RESOLVE,
        /** 正在检出工作区。 */
        CHECKOUT,
        /** 检出完毕、正在写引用 / HEAD。 */
        FINALIZE,
        /** 成功结束。 */
        DONE,
        /** 失败结束（失败原因由 clone 的返回值给出，不在进度里）。 */
        FAILED,

        /** 认不出的阶段（引擎升级、字段改名）：UI 退回「正在拉取…」而不是崩或乱猜。 */
        UNKNOWN,
    }

    /** 进度条百分比 0..100；`null` = 进度不可知（进度条走不确定态）。 */
    val percent: Int?
        get() = when (phase) {
            Phase.CONNECT, Phase.IDLE, Phase.UNKNOWN, Phase.FAILED -> null
            Phase.RECEIVE -> ramp(5, 70, received, total)
            Phase.RESOLVE -> ramp(70, 85, indexed, total)
            // 检出画到 99 为止：100% 留给「写完引用」那一刻，否则收尾阶段会从 100 退回 99
            Phase.CHECKOUT -> ramp(85, 99, checkoutDone, checkoutTotal)
            Phase.FINALIZE -> 99
            Phase.DONE -> 100
        }

    /** 是否还有后续数据会来（用于决定弹窗上的动作是「取消」还是「关闭」）。 */
    val running: Boolean
        get() = phase != Phase.DONE && phase != Phase.FAILED && phase != Phase.IDLE

    private fun ramp(from: Int, to: Int, done: Int, total: Int): Int? {
        if (total <= 0) return null
        val ratio = done.coerceIn(0, total).toDouble() / total
        return (from + (to - from) * ratio).toInt().coerceIn(from, to)
    }

    companion object {

        /**
         * 解析引擎给的一行 JSON。
         *
         * 失败返回 `null`（**不是**一个「0% 的进度」）：调用方据此保持上一次的界面状态，
         * 而不是把进度条打回原点 —— 那会让人以为 clone 重新开始了。
         */
        fun parse(json: String): CloneProgress? = runCatching {
            val o = JSONObject(json)
            CloneProgress(
                phase = phaseOf(o.optString("phase")),
                received = o.optInt("received"),
                total = o.optInt("total"),
                indexed = o.optInt("indexed"),
                bytes = o.optLong("bytes"),
                checkoutDone = o.optInt("checkoutDone"),
                checkoutTotal = o.optInt("checkoutTotal"),
            )
        }.getOrNull()

        fun phaseOf(raw: String): Phase = when (raw) {
            "idle" -> Phase.IDLE
            "connect" -> Phase.CONNECT
            "receive" -> Phase.RECEIVE
            "resolve" -> Phase.RESOLVE
            "checkout" -> Phase.CHECKOUT
            "finalize" -> Phase.FINALIZE
            "done" -> Phase.DONE
            "failed" -> Phase.FAILED
            else -> Phase.UNKNOWN
        }
    }
}
