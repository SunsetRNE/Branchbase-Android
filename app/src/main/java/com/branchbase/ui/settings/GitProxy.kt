package com.branchbase.ui.settings

/**
 * Git 代理地址的**纯逻辑**：脱敏显示与格式校验。
 *
 * 单独抽出来是为了能单测 —— 这两件事都是「写错了不报错、真机上才发现」的类型：
 * - 脱敏漏了，代理 URL 里的用户名密码会直接显示在设置列表里（规范 §6.5）；
 * - 校验漏了，用户填错地址后 libgit2 只会静默失效，没有任何提示。
 */

/** 校验结论。[ok] 为 true 时 [message] 是说明；为 false 时是**可执行的错因**。 */
data class ProxyValidation(val ok: Boolean, val message: String)

/** 允许的代理协议（libgit2 支持这三种）。 */
private val PROXY_SCHEMES = listOf("http://", "https://", "socks5://")

/**
 * 校验代理地址（规范 §5.5：结果必须紧贴输入框显示）。
 *
 * 空串是**合法**的，含义是「不使用代理」—— 不是错误。
 */
internal fun validateGitProxy(raw: String): ProxyValidation {
    val s = raw.trim()
    if (s.isEmpty()) {
        return ProxyValidation(true, "留空表示不使用代理（libgit2 直连）。")
    }
    if (PROXY_SCHEMES.none { s.startsWith(it, ignoreCase = true) }) {
        return ProxyValidation(false, "地址要以 http:// 、https:// 或 socks5:// 开头。")
    }
    val hostPort = displayGitProxy(s)
    if (!hostPort.contains(':')) {
        return ProxyValidation(false, "缺少端口号（例如 :7890）。")
    }
    return ProxyValidation(true, "地址格式正确。将用于本地仓库的 clone / pull / push。")
}

/**
 * 值列与代理页上只显示 `host:port`（规范 §6.5）。
 *
 * 去掉三样东西：协议前缀、路径、以及 `user:pass@` 这段凭据 ——
 * 完整地址不显示在设置列表里。
 *
 * ```
 * "socks5://user:pass@proxy.corp:1080"  ->  "proxy.corp:1080"
 * "http://user:pass@proxy.corp:7890/x"  ->  "proxy.corp:7890"
 * "http://127.0.0.1:7890"               ->  "127.0.0.1:7890"
 * ```
 */
internal fun displayGitProxy(raw: String): String {
    val s = raw.trim()
    if (s.isEmpty()) return ""
    var rest = s.substringAfter("://", s)
    rest = rest.substringBefore('/')
    rest = rest.substringAfterLast('@')
    return rest
}
