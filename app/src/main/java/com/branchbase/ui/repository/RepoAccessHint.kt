package com.branchbase.ui.repository

/**
 * 仓库打不开时，从引擎的错误串里读出**该怎么理解**（纯函数，可单测）。
 *
 * ## 为什么需要它
 *
 * GitHub 对**当前授权看不到的私有仓库**返回的是 **404**，而不是 403 —— 这是刻意设计
 * （否则 403 就等于确认「这个私有仓库存在」）。于是「仓库不存在」和「这是私有仓库、你没权限」
 * 在状态码上**完全一样**，只看 `HTTP 404: {...}` 这一串原始 JSON 谁也判断不了。
 *
 * 这里不做猜测，只把两种可能一起说清楚，并把「该怎么试」指出来。真正的出路（用访问令牌重试 /
 * 重新授权补 `repo` 权限 / 在浏览器打开）由调用方按 [RepoAccessHint] 给出的方向去呈现。
 *
 * 返回 null = 不是「打不开仓库」这一类错误（网络失败、限流、引擎不可用…），交给既有提示。
 */
internal fun repoAccessHint(error: String?): String? = when {
    error == null -> null
    error.contains("HTTP 404") ->
        "HTTP 404：GitHub 对无权限的私有仓库也返回 404 —— 可能是**私有仓库**（当前授权看不到），也可能仓库不存在或已改名"
    error.contains("HTTP 403") ->
        "HTTP 403：权限不足或被限流 —— 细粒度令牌缺 Contents 权限、组织 SSO 未授权、或触发了二级限流都会这样"
    else -> null
}

/**
 * 令牌**已授予** scopes 的判读结果（`x-oauth-scopes` 的原文 → 结论）。
 *
 * 这是唯一能把「私有仓库没权限」从「仓库不存在」里分出来的信号：
 * 404 本身有歧义（见 [repoAccessHint]），而 scope 是确定的事实。
 */
internal enum class ScopeVerdict {
    /**
     * 令牌报了 **`repo`** → 权限够，404 更可能是「不存在 / 改名 / 组织 SSO 未授权」。
     *
     * ⚠️ 只认裸 `repo`：`public_repo` 只覆盖公开仓库，**不算**够用（`"public_repo".contains("repo")` 为真，
     * 所以判定必须先 split 再逐项比较，见 [scopeVerdict]）。
     */
    HAS_REPO,

    /** 令牌没有任何 `repo` 类权限 → 私有仓库必然看不到。 */
    MISSING_REPO,

    /** 拿不到 scope 信息（细粒度令牌不报这个头 / 探测请求失败）—— 不能据此下结论。 */
    UNKNOWN,
}

/**
 * 把 `x-oauth-scopes` 原文判读成 [ScopeVerdict]（纯函数，可单测）。
 *
 * @param raw 引擎返回的头原文（`"repo, read:user"`）；`null` / 空串 = 拿不到。
 */
internal fun scopeVerdict(raw: String?): ScopeVerdict {
    val scopes = raw?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() } ?: emptyList()
    if (scopes.isEmpty()) return ScopeVerdict.UNKNOWN
    // `repo` 覆盖私有仓库；`public_repo` 只覆盖公开仓库，因此**不算**够用
    return if (scopes.contains("repo")) ScopeVerdict.HAS_REPO else ScopeVerdict.MISSING_REPO
}

/** 把判读结果写成给用户看的一句话（`null` = 不额外说明，保留 [repoAccessHint] 的原文）。 */
internal fun scopeVerdictLine(verdict: ScopeVerdict): String? = when (verdict) {
    ScopeVerdict.HAS_REPO ->
        "当前令牌**有** repo 权限，所以这多半不是权限问题：仓库可能不存在、已改名，或组织要求先授权 SSO。"
    ScopeVerdict.MISSING_REPO ->
        "当前令牌**没有** repo 权限 —— 私有仓库必然看不到，用它换一个带 repo 的令牌即可。"
    ScopeVerdict.UNKNOWN -> null
}
