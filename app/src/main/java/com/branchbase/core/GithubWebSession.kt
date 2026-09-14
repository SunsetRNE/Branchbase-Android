package com.branchbase.core

import android.content.Context
import android.webkit.CookieManager

/**
 * GitHub **网页会话**（github.com 的浏览器 Cookie）。
 *
 * ## 存在的理由
 *
 * 仓库级「自定义通知」只有网页端有：REST 的 `PUT /repos/{o}/{r}/subscription` 只有
 * `subscribed` / `ignored` 两个布尔，GraphQL 的 `SubscriptionState` 只有三态，
 * 都表达不了「只收 Issues + Releases」这种组合。
 *
 * 网页版的内部端点（`POST /{owner}/{repo}/notifications/subscribe`）需要浏览器会话，
 * 而 **OAuth token 到不了那里** —— 实测 github.com 的 HTML 与内部端点只认 Cookie，
 * 带 `Authorization: token/bearer/Basic` 一律被 302 到 `/login`。
 * 所以这里保存一份由内嵌 WebView 登录后导出的 Cookie，交给 Rust 侧发网页请求。
 *
 * ## 边界
 *
 * - Cookie 只用于 github.com 的网页端点，**不替代 API token**（两者并行，互不影响）；
 * - 会话可能随时过期（改密码、登出、GitHub 主动失效）—— 写入失败时提示重新登录，
 *   绝不要把「会话过期」报成「操作被拒绝」；
 * - 与 [AccountStore] 一样落在 `branchbase` 首选项里，按 host 隔离。
 */
object GithubWebSession {

    private const val PREFS = "branchbase"
    private const val KEY_COOKIE = "web_cookie:"
    private const val KEY_LOGIN = "web_login:"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 网页会话的 Cookie 串（未登录返回空串）。 */
    fun cookie(context: Context, host: String = "github.com"): String =
        prefs(context).getString(KEY_COOKIE + host, null).orEmpty()

    /** 保存时记录的登录名（仅用于显示与失效判断）。 */
    fun login(context: Context, host: String = "github.com"): String =
        prefs(context).getString(KEY_LOGIN + host, null).orEmpty()

    fun has(context: Context, host: String = "github.com"): Boolean =
        cookie(context, host).contains("user_session=")

    fun save(context: Context, host: String, cookie: String, login: String) {
        prefs(context).edit()
            .putString(KEY_COOKIE + host, cookie)
            .putString(KEY_LOGIN + host, login)
            .apply()
    }

    /**
     * 丢弃网页会话。
     *
     * 同时清掉 WebView 的 Cookie：否则下次打开登录页时仍是旧会话，
     * 用户会看到「已经登录了却提示会话失效」的死循环。
     */
    fun clear(context: Context, host: String = "github.com") {
        prefs(context).edit().remove(KEY_COOKIE + host).remove(KEY_LOGIN + host).apply()
        runCatching {
            val manager = CookieManager.getInstance()
            manager.removeAllCookies(null)
            manager.flush()
        }
    }

    /**
     * 从 WebView 的 Cookie 管理器导出当前会话并落盘。
     *
     * @return 保存成功时返回 Cookie 串；未登录（没有 `user_session`）返回 null
     */
    fun capture(context: Context, host: String, login: String): String? {
        val manager = CookieManager.getInstance()
        // `_octo` 是页面 JS 通过 document.cookie 写进去的（网页端点的防伪校验会看它），
        // 只 flush 不 setAcceptCookie 时它在部分机型上还没落盘。
        manager.setAcceptCookie(true)
        manager.flush()
        val cookie = manager.getCookie("https://$host").orEmpty()
        if (!cookie.contains("user_session=")) return null
        save(context, host, cookie, login)
        return cookie
    }
}
