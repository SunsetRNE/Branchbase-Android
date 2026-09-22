package com.branchbase.core

import java.util.concurrent.ConcurrentHashMap

/**
 * 「**最近用这个 token 成功过**」的进程内证据。
 *
 * ## 它解决什么
 *
 * 账号健康检查的结论是**粘的**：一次 `/user` 探测判出「令牌已失效」就写进账号状态，
 * 而重探只在两种时机发生 —— 用户手动点、或网络跃迁（见 `NetworkWatch`）。
 * 于是网络一直没变时，会出现最难看的一种状态：
 *
 * ```
 * GET /user (XK-Pro) → 令牌已失效        ← 启动时探出来的结论
 * GET /user/repos → 200（2 个仓库）      ← 同一场会话、同一个 token，正常
 * GET /notifications → 200（0 条）
 * POST /graphql → 11 次贡献
 * ```
 *
 * 应用明明能用，界面却挂着「令牌已失效」，用户会去重新登录 —— 白折腾，还以为是登录坏了。
 *
 * 这里在**每次成功的 API 调用**上记一笔（`RustBridge.getJson` 是所有请求的必经之路），
 * 健康检查在下「失效」这种重结论之前先看一眼：**同一个 token 刚刚成功过，就不可能是失效**。
 * 这种情况下按「无法连接」记（诚实：我们没能完成这次探测），而不是「令牌已失效」。
 *
 * ## 边界
 *
 * - **只在进程内**：重启后重新探一次（本来就该探）；
 * - **TTL 5 分钟**：够覆盖「启动探测误判 → 用户开始用 → 界面还挂着失效」这段窗口；
 *   再久就可能把「刚刚失效的令牌」用旧成功记录兜住；
 * - **键用 host + token 的哈希**：不把凭据原文当 map 键（万一被打印也不泄露），
 *   哈希碰撞的方向是安全的 —— 只会让「失效」降级成「无法连接」，不会反过来。
 */
object ApiEvidence {

    /** 证据有效期：见类注释里的取舍。 */
    const val TTL_MS: Long = 5 * 60 * 1000L

    private val lastSuccess = ConcurrentHashMap<String, Long>()

    /** 记一次成功（由 `RustBridge` 在拿到有效响应后调用）。 */
    fun noteSuccess(host: String, token: String, now: Long = System.currentTimeMillis()) {
        if (token.isBlank()) return
        lastSuccess[evidenceKey(host, token)] = now
    }

    /** 这个 token 最近是否成功过（判定「令牌已失效」之前的交叉验证）。 */
    fun sawSuccessRecently(host: String, token: String, now: Long = System.currentTimeMillis()): Boolean {
        if (token.isBlank()) return false
        val at = lastSuccess[evidenceKey(host, token)] ?: return false
        if (!evidenceFresh(now, at)) {
            lastSuccess.remove(evidenceKey(host, token))
            return false
        }
        return true
    }

    /** 供测试与「换账号」复位。 */
    fun clear() = lastSuccess.clear()
}

/** 证据键：host + token 哈希（理由见类注释）。 */
internal fun evidenceKey(host: String, token: String): String = "$host|${token.hashCode()}"

/** 证据是否还在有效期内（纯函数，便于单测）。 */
internal fun evidenceFresh(now: Long, at: Long, ttlMs: Long = ApiEvidence.TTL_MS): Boolean =
    now - at < ttlMs
