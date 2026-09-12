package com.branchbase.ui.search

import com.branchbase.ui.repository.RepoDeepLink

/**
 * 搜索结果的**跳转目标**（在解析阶段就带出来，卡片点击直接用）。
 *
 * ## 为什么要有这个类型
 *
 * 之前搜索结果卡片只有展示字段（标题/副标题/一行 meta），**没有任何跳转信息** ——
 * 于是卡片连 `clickable` 都没法加：仓库项只知道 `full_name` 但没人用它，
 * issue/PR 项连 owner/repo/编号都没解析出来。结果就是「搜到了，点了一下，没反应」。
 *
 * 现在把「这行结果要去哪」在解析时定下来，UI 只负责派发：
 * - 站内有页面的（仓库 / issue / PR / 提交 / 文件）→ [toRepoDeepLink] → 走 App 内路由；
 * - 站内没有页面的（用户主页 / 主题页）→ [Web]，用系统浏览器打开。
 */
sealed interface SearchTarget {

    data class Repo(val owner: String, val repo: String) : SearchTarget

    data class Issue(val owner: String, val repo: String, val number: Long) : SearchTarget

    data class PullRequest(val owner: String, val repo: String, val number: Long) : SearchTarget

    data class Commit(val owner: String, val repo: String, val sha: String) : SearchTarget

    /** 代码搜索结果：直接落到文件查看页。 */
    data class File(val owner: String, val repo: String, val path: String) : SearchTarget

    /** 站内无对应页面：交给浏览器。 */
    data class Web(val url: String) : SearchTarget
}

/**
 * 从 `full_name`（`owner/repo`）或仓库 API 地址（`https://api.github.com/repos/owner/repo`）拆出 owner/repo。
 *
 * 兼容两种形态是因为不同搜索接口给的不一样：仓库搜索给 `full_name`，
 * issue 搜索给 `repository_url`（API 地址）。纯函数，便于单测。
 */
internal fun ownerRepoOf(value: String?): Pair<String, String>? {
    val raw = value?.trim().orEmpty()
    if (raw.isBlank()) return null
    // API 地址形态：取 /repos/ 之后的最后两段（避免误伤带子路径的地址）
    val path = if (raw.startsWith("http")) {
        val idx = raw.indexOf("/repos/")
        if (idx < 0) return null
        raw.substring(idx + "/repos/".length)
    } else {
        raw
    }.trim('/').substringBefore('?')
    val parts = path.split('/').filter { it.isNotBlank() }
    if (parts.size < 2) return null
    return parts[0] to parts[1]
}

/** 主题页地址（主题没有站内页面，用浏览器打开官方主题页）。 */
internal fun topicWebUrl(name: String): String =
    "https://github.com/topics/" + name.trim().removePrefix("#")

/**
 * 搜索结果行的**去重键**（分页合并用，纯函数便于单测）。
 *
 * ## 为什么不能只用标题
 *
 * 拉第 2 页时要按 key 去重（GitHub 分页会跨页重复同一项）。若拿标题当 key，
 * 两个不同仓库里的同名 issue（「Fix typo」「Bump version」这类标题极常见）会被当成同一条而**悄悄丢掉一条** ——
 * 这是「少显示结果」而不是「多显示重复」的缺陷，用户根本看不出来。
 * 所以优先用 [SearchTarget] 的定位信息（owner/repo/编号/sha/路径），只有拿不到目标时才退回标题。
 */
internal fun searchItemKey(target: SearchTarget?, title: String): String = when (target) {
    is SearchTarget.Repo -> "repo:${target.owner}/${target.repo}"
    is SearchTarget.Issue -> "issue:${target.owner}/${target.repo}#${target.number}"
    is SearchTarget.PullRequest -> "pr:${target.owner}/${target.repo}#${target.number}"
    is SearchTarget.Commit -> "commit:${target.owner}/${target.repo}@${target.sha}"
    is SearchTarget.File -> "file:${target.owner}/${target.repo}/${target.path}"
    is SearchTarget.Web -> "web:${target.url}"
    null -> "title:$title"
}

/**
 * 站内目标 → 仓库深链接；站内没有页面的返回 null（调用方改用浏览器）。
 *
 * 与通知深链接走的是**同一条路由**（`RepoDeepLink` → `MainScreen` 的仓库路由），
 * 所以搜索结果点进去的落点、返回栈行为与从通知进入完全一致。
 */
internal fun SearchTarget.toRepoDeepLink(): RepoDeepLink? = when (this) {
    is SearchTarget.Repo -> RepoDeepLink(owner, repo)
    is SearchTarget.Issue -> RepoDeepLink(owner, repo, issueNumber = number)
    is SearchTarget.PullRequest -> RepoDeepLink(owner, repo, pullNumber = number)
    is SearchTarget.Commit -> RepoDeepLink(owner, repo, commitSha = sha)
    is SearchTarget.File -> RepoDeepLink(owner, repo, path = path)
    is SearchTarget.Web -> null
}
