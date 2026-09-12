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
 * 登录流程里「返回上一步」的目标状态。
 *
 * 只有密钥填写页有上一级（它从密钥介绍页进来）；其余中间态都直接回欢迎页。
 * 欢迎页本身不在拦截范围内 —— 那一步交给系统默认行为（退出 App）。
 */
internal fun loginBackTarget(current: LoginState): LoginState = when (current) {
    is LoginState.KeyInput -> LoginState.KeyIntro
    else -> LoginState.Idle
}
