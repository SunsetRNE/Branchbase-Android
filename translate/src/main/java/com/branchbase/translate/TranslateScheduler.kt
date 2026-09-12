package com.branchbase.translate

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 请求调度：**全局串行 + 退避重试 + 两级熔断（外加凭据熔断）**。
 *
 * ## 为什么必须全局串行
 *
 * MyMemory 匿名接口对并发极不友好：实测同时发 3 个请求就会开始返回限流错误，
 * 而限流的表现是「**整页都翻不出来**」——比慢几秒糟糕得多。因此所有后端调用
 * 都穿过同一把 [Mutex]（一个进程只有这一个调度器，见 [TranslateRuntime]）。
 * 换成 DeepSeek 之后串行依然是对的：它按 token 计费，并发只会让限流来得更快。
 *
 * 串行带来的排队延迟由页面侧承担：JS 按批（默认 3 段）请求、逐批插入译文，
 * 用户看到的是「译文一段段出现」，而不是「转圈几秒然后整页出现」。
 *
 * ## 退避重试
 *
 * 只对 [FailKind.NETWORK] 重试 [SchedulerConfig.maxRetries] 次，间隔
 * `base * 2^(n-1)` 指数退避（上限 [SchedulerConfig.maxBackoffMs]）。参数化 [sleep]
 * 是为了单测能零耗时地断言退避序列。
 *
 * ## 三种熔断（这才是「别让用户干等」的地方）
 *
 * 1. **额度熔断**（[FailKind.QUOTA]）：服务端说「额度用尽 / 余额不足 / 被限流」，
 *    继续发只会拿到同样的错误，还浪费剩余额度 → 停翻，直到用户主动重试（[reset]）；
 * 2. **凭据熔断**（[FailKind.AUTH]）：Key 没填或失效，请求全是 401。
 *    它和额度一样要停发（否则每段都白发一次，还会把「连续失败」计数打满），
 *    但状态单独标成 `auth`，页面提示「去设置里检查 API Key」而不是「等会儿再试」；
 * 3. **连续失败熔断**：非以上两类失败连续 [SchedulerConfig.maxConsecutiveFailures] 次
 *    （默认 3 次，即连续 3 段都失败）也暂停 —— 通常是断网，继续每段都试一遍纯属浪费。
 *
 * 三者都通过 [state] 暴露给页面脚本，页面据此把按钮切成「额度用尽 / Key 无效 / 翻译失败」，
 * 而不是默默什么都不发生（用户最讨厌的失败方式就是「没有反应」）。
 */
class TranslateScheduler(
    private val engine: TranslateEngine,
    private val config: SchedulerConfig = SchedulerConfig(),
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {

    private val gate = Mutex()

    @Volatile
    private var quotaBlocked = false

    @Volatile
    private var authFailed = false

    @Volatile
    private var consecutiveFailures = 0

    /** 熔断快照（页面脚本与设置页都会展示）。 */
    data class State(
        val quotaBlocked: Boolean,
        val authFailed: Boolean,
        val consecutiveFailures: Int,
        val paused: Boolean,
    ) {
        /** 是否还能继续翻译。 */
        val usable: Boolean get() = !quotaBlocked && !authFailed && !paused

        /** 传给页面脚本的状态字（再由 `report()` 原样推给原生的悬浮控件，见 `TranslatePageSnapshot`）。 */
        fun pageStatus(): String = when {
            authFailed -> "auth"
            quotaBlocked -> "quota"
            paused -> "paused"
            else -> "ok"
        }
    }

    fun state(): State = State(
        quotaBlocked = quotaBlocked,
        authFailed = authFailed,
        consecutiveFailures = consecutiveFailures,
        paused = consecutiveFailures >= config.maxConsecutiveFailures,
    )

    /** 用户主动重试 / 改了设置：清空熔断状态（是否真的恢复由下一次请求验证）。 */
    fun reset() {
        quotaBlocked = false
        authFailed = false
        consecutiveFailures = 0
    }

    /**
     * 发一次翻译（含重试与熔断判定）。
     *
     * 熔断期内直接返回失败，**不碰网络**。
     */
    suspend fun call(text: String, from: String, to: String): EngineResult {
        if (!state().usable) {
            return EngineResult.Fail(
                if (authFailed) FailKind.AUTH else if (quotaBlocked) FailKind.QUOTA else FailKind.NETWORK,
                when {
                    authFailed -> "API Key 无效或未配置，请到「设置 → 沉浸式翻译」检查"
                    quotaBlocked -> "翻译额度已用尽，请稍后再试"
                    else -> "连续多次翻译失败，已暂停本次翻译"
                },
            )
        }
        // 单出口写法：多个 `return@withLock` 返回不同子类型（Ok / Fail）时，
        // Kotlin 会把 lambda 的返回类型推断成交叉类型而编译失败，用局部变量 + break 更稳也更清楚
        return gate.withLock {
            var result: EngineResult = EngineResult.Fail(FailKind.NETWORK, "翻译已暂停")
            // 拿到闸门后再判一次：等待期间可能已经被前面的请求熔断
            if (state().usable) {
                var attempt = 0
                while (true) {
                    when (val r = engine.translate(text, from, to)) {
                        is EngineResult.Ok -> {
                            consecutiveFailures = 0
                            result = r
                            break
                        }

                        is EngineResult.Fail -> {
                            when (r.kind) {
                                // 额度用尽 / 余额不足：立刻停翻，剩下的额度不该浪费在必然失败的请求上
                                FailKind.QUOTA -> {
                                    quotaBlocked = true
                                    result = r
                                }
                                // Key 没填/失效：同样停发，但**不累计连续失败** ——
                                // 否则用户改完 Key 回来还要额外点一次「重试」才能清掉暂停态
                                FailKind.AUTH -> {
                                    authFailed = true
                                    result = r
                                }
                                else -> {
                                    consecutiveFailures++
                                    val retryable = r.kind == FailKind.NETWORK
                                    if (!retryable || attempt >= config.maxRetries) {
                                        result = r
                                    } else {
                                        attempt++
                                        sleep(backoffMs(attempt))
                                        continue
                                    }
                                }
                            }
                            break
                        }
                    }
                }
            }
            result
        }
    }

    /** 第 n 次重试前的等待时长（指数退避，封顶）。 */
    internal fun backoffMs(attempt: Int): Long {
        var ms = config.baseBackoffMs
        repeat(attempt - 1) { ms = (ms * 2).coerceAtMost(config.maxBackoffMs) }
        return ms.coerceAtMost(config.maxBackoffMs)
    }
}

/** 调度参数（集中在这里，便于单测与后续按设备性能调参）。 */
data class SchedulerConfig(
    /** 网络类失败的重试次数（不含首次）。 */
    val maxRetries: Int = 1,
    /** 首次退避基数。 */
    val baseBackoffMs: Long = 400,
    /** 退避上限。 */
    val maxBackoffMs: Long = 4_000,
    /** 连续失败多少次后暂停本次翻译。 */
    val maxConsecutiveFailures: Int = 3,
)
