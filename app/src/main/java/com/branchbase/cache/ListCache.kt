package com.branchbase.cache

/**
 * 仓库内「列表类页面」的缓存契约（代码树 / Issue / PR / 提交 / 工作流 / 发布）。
 *
 * 这些页面原先**完全不缓存**：每次切 tab 都重新请求，配合「每个请求新建 TLS 连接」
 * 就是「切一下 tab 等一秒」的主因。统一走这里之后：
 * - 进入页面先直出上次内容（[readStale]），再后台回源刷新（stale-while-revalidate）；
 * - 预加载（[RepoPrefetcher]）可以提前把常用 tab 的第一页写进同一批 key，切 tab 即秒开。
 *
 * TTL 见 `SearchCacheManager.ttlFor("仓库列表")` —— 列表变化比仓库信息频繁，取 5 分钟。
 */
object ListCache {

    const val TYPE = "仓库列表"

    // ── 页面标识（拼进 key，避免不同列表互相覆盖） ──
    const val PAGE_CODE = "code"
    const val PAGE_ISSUES = "issues"
    const val PAGE_PULLS = "pulls"
    const val PAGE_COMMITS = "commits"
    const val PAGE_WORKFLOWS = "workflows"
    const val PAGE_RELEASES = "releases"

    /**
     * 缓存键：`repo-list:{page}:{owner}/{repo}[:params]`
     * @param params 影响结果的参数（如代码树目录、分支、PR 的 base），必须参与键
     */
    fun key(owner: String, repo: String, page: String, params: String = ""): String =
        buildString {
            append("repo-list:").append(page).append(':').append(owner).append('/').append(repo)
            if (params.isNotEmpty()) append(':').append(params)
        }

    /** 代码树页的 params 约定（目录与分支都会影响结果，空值用空串占位）。 */
    fun codeParams(path: String, branch: String?): String = "$path|${branch.orEmpty()}"

    suspend fun read(manager: SearchCacheManager, key: String): String? = manager.get(key, TYPE)

    suspend fun readStale(manager: SearchCacheManager, key: String): String? = manager.getStale(key, TYPE)

    suspend fun write(manager: SearchCacheManager, key: String, json: String) {
        manager.put(key, TYPE, json)
    }
}
