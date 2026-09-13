package com.branchbase.ui.repository

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.branchbase.cache.PageCache
import com.branchbase.cache.SearchCacheDatabase
import com.branchbase.cache.SearchCacheManager
import com.branchbase.core.RustBridge
import com.branchbase.joblogs.JobLogCache
import com.branchbase.joblogs.JobLogSource
import com.branchbase.joblogs.JobLogStore

/**
 * `:joblogs` 的**接线层**：模块不认识任何 App 类型，所有注入都收在这一个文件里。
 *
 * 拆开看只有两件事：
 * 1. 日志来源 —— 一条 REST 地址 + 把 `RustBridge` 的两种失败（null / `ERROR:` 前缀）
 *    折叠成模块唯一认识的失败信号（null）；
 * 2. 缓存适配 —— 把模块的两段式缓存契约（先直出 / 回源）原样落到 `PageCache` 上，
 *    于是 TTL、过期可直出、失败不覆盖旧值这些既有行为一个都不变。
 *
 * 删模块时连同本文件一起删（见 settings.gradle.kts 的 `:joblogs` 注释）。
 */

/** `PageCache` → `:joblogs` 的缓存契约（类型文件桶，与改动前同一个缓存键与 TTL）。 */
fun pageCacheJobLogCache(manager: SearchCacheManager): JobLogCache = object : JobLogCache {

    override suspend fun cached(key: String, force: Boolean): String? =
        PageCache.cachedFirst(manager, key, PageCache.TYPE_FILE, force)

    override suspend fun refresh(key: String, force: Boolean, fetch: suspend () -> String?): String? =
        PageCache.refresh(manager, key, PageCache.TYPE_FILE, force, fetch)
}

/**
 * 新建一个作业日志 store。
 *
 * **在 [RepositoryScreen] 层调用一次**（而不是两个页面各建一个）：这样运行详情页与
 * Job 详情页共用同一份内存（已切好的分段）与同一张在飞请求表 —— 页面之间来回切
 * 不会重复下载、也不会重复切段。
 */
@Composable
fun rememberJobLogStore(sessionJson: String, owner: String, repo: String): JobLogStore {
    val context = LocalContext.current
    return remember(sessionJson, owner, repo) {
        val (host, token, _) = sessionInfo(sessionJson)
        val manager = SearchCacheManager(SearchCacheDatabase.getInstance(context).searchCacheDao())
        JobLogStore(
            source = JobLogSource { jobId ->
                RustBridge.getJson(host, token, "/repos/$owner/$repo/actions/jobs/$jobId/logs")
                    ?.takeIf { !it.startsWith("ERROR:") }
            },
            cache = pageCacheJobLogCache(manager),
            keyOf = { jobId -> PageCache.jobLogKey(owner, repo, jobId) },
        )
    }
}
