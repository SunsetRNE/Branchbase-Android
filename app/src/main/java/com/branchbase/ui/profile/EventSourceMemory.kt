package com.branchbase.ui.profile

/**
 * 「事件源不可用」的**进程内记忆**（默认 TTL 5 分钟）。
 *
 * ## 为什么需要它
 *
 * 动态页的事件流有两条腿：`/user/events`（认证用户自己的活动，含私有仓库）与
 * `/users/{login}/events`（公开活动）。页面**先走自己那条**，空了/失败了再回退到另一条。
 *
 * 问题是失败**不留痕**：`PageCache.refresh` 只在拿到非空、非 `ERROR:` 的响应时才写缓存，
 * 所以当 `/user/events` 长期不可用（权限、代理、令牌类型都可能），每进一次动态页都要
 * **先等一次注定失败的请求**，然后才回退到已经有缓存的那条 —— 真机日志里 `/user/events:1`
 * 连续三次都是「未命中」，而同期的 `/users/SunsetRNE/events:1` 在 L1/L2 命中。
 *
 * 这里把「这个源刚试过、不可用」记在内存里：TTL 内直接跳过，不再为它付一次往返。
 *
 * ## 边界
 *
 * - **只在进程内**：重启 App 会重新试一次（值得试 —— 权限可能刚被授上、代理可能刚连上）；
 * - **TTL 短**（5 分钟）：这是个「别重复踩」的缓存，不是「这个源永久坏了」的结论；
 * - 真拿到数据时 [clear]，标记立刻失效。
 *
 * 时钟可注入，判定因此能单测（`EventSourceMemoryTest`）。
 */
internal class EventSourceMemory(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    companion object {
        /** 默认记忆时长：够覆盖「来回切几次 Tab」，又不至于把一次偶发失败当成永久结论。 */
        const val DEFAULT_TTL_MS = 5 * 60 * 1000L
    }

    private val deadAt = mutableMapOf<String, Long>()

    /** 这个源是否还在「刚失败过」的窗口里。窗口过期会顺手清掉标记。 */
    fun isDead(key: String, now: Long = clock()): Boolean {
        val at = deadAt[key] ?: return false
        if (now - at >= ttlMs) {
            deadAt.remove(key)
            return false
        }
        return true
    }

    /** 记一次失败。同 key 重复标记只保留最近一次的时间。 */
    fun markDead(key: String, now: Long = clock()) {
        deadAt[key] = now
    }

    /** 这个源又能用了（真拿到数据）：立刻撤销标记。 */
    fun clear(key: String) {
        deadAt.remove(key)
    }

    /** 供测试与「换账号」这类场景整体复位。 */
    fun clearAll() {
        deadAt.clear()
    }
}

/**
 * 事件源记忆的进程级实例。
 *
 * 键的约定：`"$login|$path"` —— 带上账号，否则换账号后会把别人的失败当成自己的。
 */
internal val eventSourceMemory = EventSourceMemory()
