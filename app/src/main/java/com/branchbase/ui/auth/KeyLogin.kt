package com.branchbase.ui.auth

import org.json.JSONObject

/**
 * 密钥登录（PAT）的纯逻辑部分：权限清单、网页端链接、会话组装、返回目标。
 *
 * 单独成文件是为了**可单测**：这几项都是「换一行就可能悄悄坏掉」的契约 ——
 * 会话 JSON 的字段名必须与 `sessionInfo()` / `AccountStore.accessTokenOf()` 对得上，
 * 链接里预填的权限必须与 `OAuthCredentials.scopes` 一致。放进 ViewModel 就只能靠真机验证了。
 */

/**
 * 密钥需要勾选的权限（与 OAuth 申请的范围保持一致，见 [OAuthCredentials.scopes]）。
 *
 * 少勾 `read:user` 会导致登录校验直接失败；少勾 `repo` 则「能登录但提交/开 PR 报错」——
 * 后者最难排查，所以在介绍页里把这四项显式列出来。
 */
internal val KEY_LOGIN_SCOPES = listOf("repo", "read:user", "read:org", "notifications")

/**
 * 网页端「新建经典密钥」页面地址：**预填权限与用途**。
 *
 * 与其让用户自己去 Settings → Developer settings 里翻、再逐项猜要勾什么，
 * 不如一键打开已填好的页面 —— 这也是密钥登录最容易劝退人的一步。
 */
internal fun keyTokenCreateUrl(scopes: List<String> = KEY_LOGIN_SCOPES): String =
    "https://github.com/settings/tokens/new" +
        "?scopes=${scopes.joinToString(",")}" +
        "&description=Branchbase"

/**
 * 组装与 OAuth **同构**的会话 JSON。
 *
 * 结构必须与 `sessionInfo()`（读 `host` / `token.access_token` / `user.login`）和
 * `AccountStore.accessTokenOf()` 兼容，这样账号健康检查、多账号表、各业务页全都无需特判。
 */
internal fun buildKeySession(host: String, token: String, user: JSONObject): String = JSONObject()
    .put("host", host)
    .put("token", JSONObject().put("access_token", token))
    .put("user", user)
    .toString()

/**
 * 登录失败发生在**哪一步**。
 *
 * 失败页上的「重试」必须重做**失败的那一步**，而不是一律回欢迎页：
 * 用户刚走完介绍页、点完授权，把他扔回欢迎页等于让他从头再来一遍。
 * 所以失败原因与失败步骤一起进 [LoginState.Error]（payload 变了不算换页，
 * 见 `LoginFlow` 的 `contentKey = { it::class }`）。
 *
 * 这个枚举是 `public` 的，因为它是公开的 [LoginState.Error] 的构造参数类型；
 * `LoginRetry` / `retryFor` 只被 ViewModel 与单测使用，保持 `internal`。
 */
enum class LoginFailStep {
    /** 还没拉起浏览器：生成 PKCE / 拼授权 URL 失败（通常是引擎侧问题）。 */
    OAUTH_START,

    /** 已经在网页端授权完、但换取令牌失败（code 过期 / 被取消 / 网络）。 */
    OAUTH_EXCHANGE,

    /** 双重验证码格式不对。 */
    TWO_FACTOR,
}

/** 「重试」该重做的事（为什么要分开：见 [LoginFailStep]）。 */
internal enum class LoginRetry {
    /** 从头发起一次 OAuth（新 PKCE + 新授权 URL）。 */
    RESTART_OAUTH,

    /** 回到 2FA 输入页（口令是用户手上的东西，只需重输）。 */
    TWO_FACTOR,
}

/**
 * 失败步骤 → 重试动作（纯函数，[KeyLoginTest] 钉住）。
 *
 * 两档 OAuth 失败都走「重新发起」：`OAUTH_EXCHANGE` 的 code 是一次性的，
 * 拿同一个 code 重试必然再失败；`OAUTH_START` 则是连 URL 都没拿到。
 */
internal fun retryFor(step: LoginFailStep): LoginRetry = when (step) {
    LoginFailStep.OAUTH_START, LoginFailStep.OAUTH_EXCHANGE -> LoginRetry.RESTART_OAUTH
    LoginFailStep.TWO_FACTOR -> LoginRetry.TWO_FACTOR
}

/**
 * 登录流程里「返回上一步」的目标状态。
 *
 * 只有密钥填写页有上一级（它从密钥介绍页进来）；其余中间态都直接回欢迎页。
 * 欢迎页本身不在拦截范围内 —— 那一步交给系统默认行为（退出 App）。
 */
internal fun loginBackTarget(current: LoginState, addingAccount: Boolean = false): LoginState = when (current) {
    is LoginState.KeyInput -> LoginState.KeyIntro
    // 授权进度（授权中 / 换 token / 2FA）的「上一页」是**授权登录的流程要点页**，
    // 而不是欢迎页（2026-11-24 决策）：用户刚看过那一页才点的「开始授权」，
    // 退回去应当能看到同一套说明 + 重新开始，而不是被扔回两种方式的选择屏。
    // 新增账号流程仍回新增欢迎页（下面两支各自处理）。
    is LoginState.Authorizing, is LoginState.ExchangingToken, is LoginState.NeedTwoFactor ->
        if (addingAccount) LoginState.AddAccountWelcome else LoginState.OAuthIntro
    // 介绍页 / 出错页按返回 → 回欢迎页（未登录）或新增欢迎页（新增流程）。
    // 少了 `addingAccount` 这一支就会掉到未登录的 Idle 欢迎页：用户本来登录着，却看到一个
    // 「去登录」的界面，而且 Idle 不拦返回键 —— 再按一下直接退出 App。
    is LoginState.OAuthIntro, is LoginState.KeyIntro, is LoginState.Error,
    -> if (addingAccount) LoginState.AddAccountWelcome else LoginState.Idle
    // 「新增账号」的欢迎页在这里只交回 Idle，**真实会话由 [LoginViewModel.back] 走 endAddAccount 补上**；
    // 纯函数读不到 prefs，若在这里写 `LoggedIn("")` 会把一个空会话送进 LoggedInGate。
    is LoginState.AddAccountWelcome -> LoginState.Idle
    else -> LoginState.Idle
}

/**
 * OAuth 换 token 失败时，Rust 侧给的是**原始报文**（`GitHub 错误 (status): {"error":...}`）。
 * 直接贴给用户等于没说 —— 这里把它分成三类「能照着做的事」，其余原样保留。
 *
 * 为什么单独成纯函数：分类规则要能单测（文案与判定分开），而且错分一次就会把
 * 「凭据没配」说成「网络问题」，用户会一直重试。
 */
internal sealed interface OAuthFailure {
    /** 没注入 client_secret，或 OAuth App 的 secret 被重置 —— 换安装包/改配置，重试无用。 */
    data object ClientCredentials : OAuthFailure

    /** code 无效或已用过（只能用一次）—— 重新授权即可。 */
    data object BadCode : OAuthFailure

    /** 回调地址与 OAuth App 配置不一致。 */
    data object RedirectMismatch : OAuthFailure

    /** 其它（网络 / 未知）—— 原样展示，便于贴日志。 */
    data class Other(val raw: String) : OAuthFailure
}

internal fun classifyOAuthFailure(raw: String): OAuthFailure = when {
    raw.contains("incorrect_client_credentials") -> OAuthFailure.ClientCredentials
    raw.contains("bad_verification_code") || raw.contains("invalid_grant") -> OAuthFailure.BadCode
    raw.contains("redirect_uri_mismatch") -> OAuthFailure.RedirectMismatch
    else -> OAuthFailure.Other(raw)
}
