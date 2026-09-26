package com.branchbase.ui.repository

import android.content.Context
import com.branchbase.cache.PageCache
import com.branchbase.cache.SearchCacheDatabase
import com.branchbase.cache.SearchCacheManager
import com.branchbase.core.RustBridge

/**
 * 提交图首页的取数路径与缓存（1.1.7）。
 *
 * ## 为什么单独拎出来
 *
 * 提交图有两个来源：本地引擎（快，但**浅克隆里读不出来** —— libgit2 的 revwalk 不按
 * `.git/shallow` 截断，走到缺掉的父提交就报 `object not found`）与 REST（读得到，但要等一次网络）。
 * 于是「本地已经有这个仓库」的用户反而最容易撞上慢的那条路：本地副本是浅克隆 → 提交图必然走 REST，
 * 而此前**没有任何缓存** —— 每次打开这一档、每次换档回来、每次 `refreshTick` 变化都重新发一次
 * `/commits`。观感就是「本地明明有副本，提交图还是慢」。
 *
 * 这里只做两件事：
 *
 * 1. **首页走 [PageCache]**（同一个 `owner/repo@branch` 一份 JSON）：新鲜时直接命中不再联网，
 *    过期时也能先直出再回源（[PageCache.cachedFirst] → [PageCache.refresh]）；
 * 2. 给宿主一个 [warm]：仓库页一打开就在后台把首页暖上（**只在本地副本是浅克隆时**值得暖），
 *    用户点开「提交图」档那一刻缓存已经在了。
 *
 * 缓存键与 URL 只有这里拼一份：面板取数与宿主预热各拼一次的话，键差一个字符就是
 * 「明明预热过却还是慢」—— 而且完全静默。
 *
 * 为什么不缓存本地来源：引擎就在本机，读一次比查缓存还快；缓存它只会让加深历史之后的
 * 第一屏继续显示加深前的那一份。
 */
object CommitGraphCache {

    /**
     * REST 首页的相对路径。与面板里的取数**必须**是同一个形状：
     * 带 `sha` 时按提交分页，不带时按分支（[branch] 为空则不带 `sha`，即默认分支）。
     */
    fun firstPagePath(owner: String, repo: String, branch: String, sha: String? = null): String =
        buildString {
            append("/repos/$owner/$repo/commits?per_page=")
            append(GRAPH_PAGE_SIZE)
            if (!sha.isNullOrBlank()) append("&sha=$sha")
            else if (branch.isNotBlank()) append("&sha=$branch")
        }

    /** 缓存键：一个仓库的一个分支一份（提交图这一档永远只看一个引用）。 */
    fun key(owner: String, repo: String, branch: String): String =
        PageCache.graphKey(owner, repo, branch)

    fun manager(context: Context): SearchCacheManager =
        SearchCacheManager(SearchCacheDatabase.getInstance(context).searchCacheDao())

    /** 缓存里那一份（**过期也返回**）：用来先出内容，别再让用户对着骨架等一次网络。 */
    suspend fun cached(
        manager: SearchCacheManager,
        owner: String,
        repo: String,
        branch: String,
    ): String? = PageCache.cachedFirst(manager, key(owner, repo, branch), PageCache.TYPE_DETAIL)

    /** 取一次并写回缓存；未过期直接返回缓存（不发请求）。失败返回 null，不覆盖已有缓存。 */
    suspend fun load(
        manager: SearchCacheManager,
        host: String,
        token: String,
        owner: String,
        repo: String,
        branch: String,
    ): String? = PageCache.refreshDetached(manager, key(owner, repo, branch), PageCache.TYPE_DETAIL) {
        RustBridge.getJson(host, token, firstPagePath(owner, repo, branch))
    }

    /**
     * 预热首页。宿主在「本地副本是浅克隆」时调 —— 那时提交图**注定**走 REST，
     * 先暖上就等于把那次网络提前到一个用户没在等的时刻。
     *
     * 参数不全（还没登录 / 仓库名还没就位）时直接返回：预热不是功能，缺了它页面照旧能取数。
     */
    suspend fun warm(
        context: Context,
        host: String,
        token: String,
        owner: String,
        repo: String,
        branch: String,
    ) {
        if (host.isBlank() || token.isBlank() || owner.isBlank() || repo.isBlank()) return
        load(manager(context), host, token, owner, repo, branch)
    }
}
