package com.branchbase.cache

/**
 * 预加载资源存储库（preload store）。
 *
 * 统一管理「仓库页一次要用到的 4 类资源」的缓存键与批量读写，底层复用
 * [SearchCacheManager]（Room `search_cache`：TTL + LRU + 过期清理）。这里补三件事：
 *
 * 1. **键名集中管理** —— 各处手拼字符串曾导致 README 缓存永不命中（key 少拼了分支）；
 * 2. **批量读** —— 一次拿齐整页数据，命中即直出，不用等 4 个请求依次返回；
 * 3. **允许读过期数据**（stale-while-revalidate）—— 先渲染上次内容，再后台刷新，
 *    把「打开仓库页」的感知等待从网络往返降到 0。
 */
object PreloadStore {

    // ── 资源类型（与 SearchCacheManager.ttlFor 的 key 对齐） ──
    const val TYPE_INFO = "仓库信息"
    const val TYPE_README = "README"
    const val TYPE_LANG = "仓库语言"
    const val TYPE_CONTRIB = "仓库贡献者"
    const val TYPE_BRANCH = "分支"

    // ── 缓存键（唯一来源，禁止在别处手拼） ──
    fun infoKey(owner: String, repo: String) = "repo-info:$owner/$repo"
    fun langKey(owner: String, repo: String) = "repo-lang:$owner/$repo"
    fun contribKey(owner: String, repo: String) = "repo-contrib:$owner/$repo"
    fun branchKey(owner: String, repo: String) = "$owner/$repo"

    /**
     * README 的缓存键**必须**带解析后的真实分支（而不是调用方传进来的 null/空串），
     * 否则默认分支首次加载写入 `owner/repo@`、随后读取拼成 `owner/repo@main`，永远不命中。
     */
    fun readmeKey(owner: String, repo: String, branch: String) = "$owner/$repo@$branch"

    /** 仓库页首屏所需的四类资源（任一为 null = 该块缺数据）。 */
    data class RepoBundle(
        val info: String?,
        val readme: String?,
        val languages: String?,
        val contributors: String?,
    ) {
        /** 能否直接渲染页面（没有仓库信息就没法画头部）。 */
        val usable: Boolean get() = info != null

        /** 已就绪的资源数（用于进度提示 / 单测断言）。 */
        val readyCount: Int
            get() = listOf(info, readme, languages, contributors).count { it != null }

        companion object {
            val EMPTY = RepoBundle(null, null, null, null)
        }
    }

    /** 读「未过期」的整页资源（TTL 内才算命中）。 */
    suspend fun readBundle(
        manager: SearchCacheManager,
        owner: String,
        repo: String,
        branch: String,
    ): RepoBundle = RepoBundle(
        info = manager.get(infoKey(owner, repo), TYPE_INFO),
        readme = manager.get(readmeKey(owner, repo, branch), TYPE_README),
        languages = manager.get(langKey(owner, repo), TYPE_LANG),
        contributors = manager.get(contribKey(owner, repo), TYPE_CONTRIB),
    )

    /**
     * 读整页资源但**忽略 TTL**：用于先直出旧数据再回源刷新。
     * 调用方必须随后触发一次真实的网络加载（[refreshBundle] 或页面自身的回源逻辑）。
     */
    suspend fun readStaleBundle(
        manager: SearchCacheManager,
        owner: String,
        repo: String,
        branch: String,
    ): RepoBundle = RepoBundle(
        info = manager.getStale(infoKey(owner, repo), TYPE_INFO),
        readme = manager.getStale(readmeKey(owner, repo, branch), TYPE_README),
        languages = manager.getStale(langKey(owner, repo), TYPE_LANG),
        contributors = manager.getStale(contribKey(owner, repo), TYPE_CONTRIB),
    )

    /** 整页资源是否都还在 TTL 内（决定进入仓库页时要不要再发一轮请求）。 */
    suspend fun isBundleFresh(
        manager: SearchCacheManager,
        owner: String,
        repo: String,
        branch: String,
    ): Boolean = manager.isFresh(infoKey(owner, repo), TYPE_INFO) &&
        manager.isFresh(readmeKey(owner, repo, branch), TYPE_README) &&
        manager.isFresh(langKey(owner, repo), TYPE_LANG) &&
        manager.isFresh(contribKey(owner, repo), TYPE_CONTRIB)
}

/**
 * 从仓库信息 JSON 里取默认分支（预加载与页面加载共用）。
 *
 * 放在 cache 层是为了不让 cache 依赖 ui 层的 `parseRepoInfo`；只读一个字段，用 org.json 足够。
 */
fun defaultBranchOf(infoJson: String?): String? = runCatching {
    org.json.JSONObject(infoJson!!).optString("default_branch").takeIf { it.isNotBlank() }
}.getOrNull()
