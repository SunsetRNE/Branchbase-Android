package com.branchbase.core

import android.content.Context
import com.branchbase.BuildConfig
import com.branchbase.ui.auth.OAuthCredentials
import com.branchbase.ui.log.Logger
import org.json.JSONObject

/**
 * 用 **refresh token** 换一枚新的 access token。
 *
 * ## 为什么需要它（1.1.6）
 *
 * GitHub App 的 user token 是有期限的（到期后 `/user` 一律回 `401 Bad credentials`），
 * 但它**同时给了一枚 refresh token**：这枚 401 与「令牌被吊销」在表面上完全一样，
 * 而结论却完全相反 —— 前者用户什么都不用做（甚至不该被打扰），后者必须重新登录。
 *
 * 真机上出现过的形态就是用户报的那句话：**「明确能够登录，但校验令牌异常」** ——
 * 唯一的续期入口在 `LoginViewModel.refreshSession`，它只在**进程启动时试一次**、
 * 失败还**静默返回**（无日志、不重试），并且账号健康检查（[AccountChecks]）拿到 401 后
 * 从不尝试续期。于是网络抖一下 / 启动那一秒超时，整段会话就挂着「令牌已失效」，
 * 用户被引导去重新登录 —— 而登录本身一点问题都没有。
 *
 * ## 这里只做三件事
 *
 * 1. 记录里有 refresh token → 换一枚新的（`POST /login/oauth/access_token`，`grant_type=refresh_token`）；
 * 2. 把新会话写回账号表（[AccountStore.updateSession]）并同步旧单账号键（当前账号时）；
 * 3. 把成功 / 失败都写进日志 —— 这一条以前是**黑洞**，日志里连「试过续期」都看不到。
 *
 * 判定与写回状态仍由 [AccountChecks] 负责（续期完还要重探一次 `/user` 才算数）。
 */
object AccountRenewal {

    /** 日志里原文截断长度（与 `AccountChecks` 的 RAW_MAX 同口径：够定位，不至于刷屏）。 */
    private const val RAW_MAX = 200

    /**
     * 尝试续期。成功返回**新的 access token**（同时已把会话写回账号表），失败返回 null。
     *
     * 绝不抛出：续期是「顺手救一下」，它失败不该让一次账号检查崩掉 —— 调用方拿到 null
     * 就照原结论走（并能在日志里看到为什么没救回来）。
     *
     * @param account 记录（用它自己的 host 与 session；PAT 没有 refresh token，直接短路）
     */
    suspend fun renew(context: Context, account: Account): String? {
        val refresh = AccountStore.refreshTokenOf(account.session)
        if (refresh.isBlank()) {
            // 这一条也要打：日志里连「为什么没续期」都查不到的话，下次还是只能靠猜
            Logger.net(
                "续期跳过：${account.login}@${account.host} 没有 refresh token（PAT 或旧会话），只能重新登录",
                "Account",
            )
            return null
        }

        val credentials = OAuthCredentials(host = account.host)
        val raw = RustBridge.refreshToken(
            clientId = credentials.clientId,
            clientSecret = BuildConfig.BRANCHBASE_CLIENT_SECRET,
            host = account.host,
            refreshToken = refresh,
        )
        if (raw.startsWith("ERROR:") || raw.isBlank()) {
            Logger.net(
                "POST /login/oauth/access_token（refresh_token）→ 失败：${raw.removePrefix("ERROR:").take(RAW_MAX)}",
                "Account",
            )
            return null
        }

        // GitHub 的续期响应就是一份 token JSON（`{"access_token":…,"refresh_token":…,"expires_in":…}`），
        // 形状与交换授权码时一致 —— 所以能直接换掉会话里的 token 段，user / host 全留着。
        val session = swapToken(account.session, raw)
        if (session == null) {
            Logger.net("续期响应装不进会话（形状不认识）：${raw.take(RAW_MAX)}", "Account")
            return null
        }
        val token = AccountStore.accessTokenOf(session)
        if (token.isBlank()) {
            Logger.net("续期响应里没有 access_token：${raw.take(RAW_MAX)}", "Account")
            return null
        }

        AccountStore.updateSession(context, account.id, session)
        // 当前账号必须同步旧键：LoginViewModel.init 启动时从那个键恢复登录态，
        // 不同步的话下次启动会把旧会话（连同已被消耗掉的 refresh token）写回来 —— 再也续不出来。
        AccountStore.syncLegacySessionOf(context, account.id)
        Logger.net(
            "POST /login/oauth/access_token（refresh_token）→ 200，新 tok=${AccountChecks.fingerprint(token)}" +
                "（旧 tok=${AccountChecks.fingerprint(account.token)}）",
            "Account",
        )
        return token
    }

    /**
     * 把续期拿到的 token JSON 装回会话（纯函数，有单测）。
     *
     * 形状：保留会话里的一切（`host` / `user` / `scopes`…），只替换 `token` 段 ——
     * 与 `LoginViewModel.refreshSession` 的成功分支同一套做法。
     *
     * 拿到的 JSON 里没有 `access_token` 就返回 null（宁可当作没续成，也不写一份空 token 进账号表）。
     */
    internal fun swapToken(session: String, tokenJson: String): String? {
        val token = runCatching { JSONObject(tokenJson) }.getOrNull() ?: return null
        if (token.optString("access_token").isBlank()) return null
        val root = runCatching { JSONObject(session) }.getOrNull() ?: JSONObject()
        return runCatching { root.put("token", token).toString() }.getOrNull()
    }
}
