package com.branchbase.cache

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * 页面级缓存契约（详情页 / 文件内容 / 通知 / 个人主页）。
 *
 * 背景：仓库内 6 个列表页与项目页此前已接入缓存，但**详情类页面仍然是「每次打开都联网」**：
 * Issue/PR/提交详情、工作流运行历史与 run 详情、文件内容、分支列表/对比、工作流 YAML、
 * 通知列表、首页计数、个人主页贡献日历。返回上一层再进去就重新拉一遍，观感上就是「加载慢」。
 *
 * 这里把「键 + TTL + 直出/回源」统一成两个入口，页面只需三行：
 * ```
 * val cached = PageCache.cachedFirst(manager, key, force)   // ① 先直出（含过期）
 * if (cached != null) { apply(parse(cached)); loading = false }
 * PageCache.refresh(manager, key, force) { fetch() }?.let { apply(parse(it)) }  // ② 回源并写回
 * ```
 *
 * 约定：
 * - `force = true`（手动刷新）时跳过直出、且忽略新鲜度，保证「刷新」真的回源；
 * - 回源失败返回 null，**不覆盖**已有缓存（旧数据仍可用）；
 * - 所有键都带页面前缀，避免不同页面同 key 互相覆盖（`search_cache` 主键是 key）。
 */
object PageCache {

    // ── 类型（与 SearchCacheManager.ttlFor 对齐） ──
    const val TYPE_DETAIL = "页面详情"
    const val TYPE_HOME = "首页"
    const val TYPE_FILE = "文件内容"
    const val TYPE_NOTIFICATION = "通知"
    const val TYPE_PROFILE = "个人主页"

    // ── 键 ──
    fun issueKey(owner: String, repo: String, number: Long) = "detail:issue:$owner/$repo#$number"
    fun issueCommentsKey(owner: String, repo: String, number: Long) = "detail:issue-comments:$owner/$repo#$number"

    /** Issue 时间线（评论 + 事件混排，`GET /issues/{n}/timeline`）。 */
    fun issueTimelineKey(owner: String, repo: String, number: Long) = "detail:issue-timeline:$owner/$repo#$number"
    fun pullKey(owner: String, repo: String, number: Long) = "detail:pull:$owner/$repo#$number"
    fun pullFilesKey(owner: String, repo: String, number: Long) = "detail:pull-files:$owner/$repo#$number"
    fun commitKey(owner: String, repo: String, sha: String) = "detail:commit:$owner/$repo@$sha"
    fun releaseKey(owner: String, repo: String, tag: String) = "detail:release:$owner/$repo@$tag"
    /** 某工作流的运行历史列表（分支筛选也算不同结果）。 */
    fun runsKey(owner: String, repo: String, workflowId: Long, branch: String) =
        "detail:runs:$owner/$repo#$workflowId@$branch"

    fun runKey(owner: String, repo: String, runId: Long) = "detail:run:$owner/$repo#$runId"
    fun jobKey(owner: String, repo: String, jobId: Long) = "detail:job:$owner/$repo#$jobId"
    fun runJobsKey(owner: String, repo: String, runId: Long) = "detail:run-jobs:$owner/$repo#$runId"
    fun runArtifactsKey(owner: String, repo: String, runId: Long) = "detail:run-artifacts:$owner/$repo#$runId"
    fun jobLogKey(owner: String, repo: String, jobId: Long) = "detail:job-log:$owner/$repo#$jobId"

    /** 文件内容（blob）：同一文件反复打开时直接命中。 */
    fun fileKey(owner: String, repo: String, path: String, ref: String) =
        "file:$owner/$repo@$ref:$path"

    /** 分支列表（服务端 API 版本，与 `PreloadStore.branchKey` 的仓库页分支缓存区分）。 */
    fun branchListKey(owner: String, repo: String) = "branch-list:$owner/$repo"

    /** 分支对比结果（base...head）。 */
    fun compareKey(owner: String, repo: String, base: String, head: String) =
        "compare:$owner/$repo:$base...$head"

    /** 工作流 YAML 文件（操作抽屉与触发页都要读）。 */
    fun workflowFileKey(owner: String, repo: String, path: String) = "wf-file:$owner/$repo:$path"

    /** 通知列表：`path` 即查询串（如 `/notifications?all=false`）。 */
    fun notificationKey(path: String) = "notifications:$path"

    /** 首页 / 个人主页的轻量计数与列表。 */
    fun homeKey(name: String) = "home:$name"
    fun profileKey(login: String, name: String) = "profile:$login:$name"

    // ── 读写 ──

    /**
     * 先直出：命中「过期也能用」的缓存时返回内容，供页面立即渲染。
     * `force = true`（手动刷新）时返回 null，强制走回源。
     */
    suspend fun cachedFirst(
        manager: SearchCacheManager,
        key: String,
        type: String,
        force: Boolean = false,
    ): String? = if (force) null else manager.getStale(key, type)

    /**
     * 回源刷新：命中未过期缓存则直接返回（不重复联网）；否则请求并写回。
     * 请求失败返回 null，且不写入缓存。
     */
    suspend fun refresh(
        manager: SearchCacheManager,
        key: String,
        type: String,
        force: Boolean = false,
        fetch: suspend () -> String?,
    ): String? {
        if (!force) {
            manager.get(key, type)?.let { return it }
        }
        val json = fetch()?.takeIf { it.isNotBlank() && !it.startsWith("ERROR:") } ?: return null
        manager.put(key, type, json)
        return json
    }

    /** 只写缓存（用于「先渲染再异步写」的场景，如搜索页）。 */
    suspend fun put(manager: SearchCacheManager, key: String, type: String, json: String) {
        manager.put(key, type, json)
    }

    /**
     * 回源刷新，但**页面提前离开也不作废**（暖缓存）。
     *
     * 与 [refresh] 的唯一差别：整段跑在 [NonCancellable] 里。
     *
     * ## 为什么「取消」在这里是反的
     *
     * 页面级取数都挂在 `LaunchedEffect` 上，离开页面即取消 —— 对**渲染**是对的
     * （结果没用了），但对**缓存**是错的：用户离开不代表这份数据不要了，
     * 它恰恰是下一次进页面最该直接直出的那份。
     *
     * 现场（真机日志 2026-09-22，v1.0.64）：进个人主页 → `/user/repos` 开始回源
     * → 用户 2 秒内点进某个仓库 → `LaunchedEffect` 取消 → `put` 从没执行。
     * 日志里 6 次 `未命中 profile:…:repos` 有 **2 次之后没有任何结果行**
     * （连 `loadRepos` 自己那句 `GET /user/repos → …` 都没有，因为它在取消点之后）。
     * 代价是「进主页 → 立刻点仓库」这条最常见的路径**永远暖不了缓存**。
     *
     * ## 边界
     *
     * - 只盖住「取数 + 落缓存」，耗时上限就是这一次请求（[fetch] 自己该挂超时）；
     * - 返回值仍可能因为调用方已取消而在下一个挂起点被丢掉 —— 那没关系，
     *   缓存已经写进去了，**这一趟的目的就是它**。
     */
    suspend fun refreshDetached(
        manager: SearchCacheManager,
        key: String,
        type: String,
        force: Boolean = false,
        fetch: suspend () -> String?,
    ): String? = withContext(NonCancellable) { refresh(manager, key, type, force, fetch) }
}
