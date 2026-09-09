package com.branchbase.cache

import android.content.Context
import android.net.ConnectivityManager
import com.branchbase.core.RustBridge
import com.branchbase.ui.repository.encodePath
import com.branchbase.ui.repository.encodeRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.Collections

/**
 * 仓库页预加载器。
 *
 * 目标：把「进入仓库页后才开始发的请求」提前到**用户点击的那一刻**，甚至更早。
 * 配合 [PreloadStore] 的 stale-while-revalidate，页面打开时直接命中缓存。
 *
 * 设计约束：
 * - **不增加必然浪费的流量**：`OpenRepo` 场景预取的是用户马上要看的页面；
 *   投机性预取（其他 tab、列表卡片）只在「用户开启预加载」且「当前网络不计费」时做（见 [planPrefetch]）。
 * - **去重**：同一仓库 60s 内只预取一次，滚动列表/反复进出不会打爆网络。
 * - **永不阻塞 UI**：fire-and-forget，失败静默（预加载失败不影响正常加载路径）。
 */
object RepoPrefetcher {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 正在预取的仓库（`owner/repo`），避免并发重复。 */
    private val inFlight = Collections.synchronizedSet(mutableSetOf<String>())

    /** 最近预取过的仓库 → 时间戳（LRU 上限 128，60s 内不重复）。 */
    private val recent: MutableMap<String, Long> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Long>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 128
        },
    )

    private const val DEDUPE_WINDOW_MS = 60_000L

    /** 预加载开关（设置项，默认开）。 */
    fun enabled(context: Context): Boolean =
        context.getSharedPreferences("branchbase", Context.MODE_PRIVATE)
            .getBoolean("prefetch.enabled", true)

    /**
     * 当前网络是否计费（移动数据）。取不到状态时**按计费处理**（保守：不投机预取）。
     */
    fun metered(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.isActiveNetworkMetered
    }.getOrDefault(true)

    /**
     * 按场景触发预加载（立即返回，不阻塞调用方）。
     */
    fun prefetch(
        context: Context,
        reason: PrefetchReason,
        host: String,
        token: String,
        owner: String,
        repo: String,
        branch: String? = null,
    ) {
        if (owner.isBlank() || repo.isBlank()) return
        val key = "$owner/$repo"
        val now = System.currentTimeMillis()
        if ((now - (recent[key] ?: 0L)) < DEDUPE_WINDOW_MS) return
        if (!inFlight.add(key)) return
        recent[key] = now

        val appContext = context.applicationContext
        scope.launch {
            try {
                val manager = SearchCacheManager(SearchCacheDatabase.getInstance(appContext).searchCacheDao())
                val hint = branch?.takeIf { it.isNotBlank() }
                // 分支未知时先按已知分支判断；未知则视为「不新鲜」，交给 warmOverview 解析
                val fresh = hint != null && PreloadStore.isBundleFresh(manager, owner, repo, hint)
                val plan = planPrefetch(reason, metered(appContext), enabled(appContext), fresh)
                when {
                    plan.overview -> warmOverview(manager, host, token, owner, repo, hint)
                    // 列表卡片预热：只取最轻的仓库信息（点进去时项目页再补齐其余三件套）
                    plan.listWarm -> warmInfoOnly(manager, host, token, owner, repo)
                }
                if (plan.tabs) warmTabs(manager, host, token, owner, repo)
            } catch (e: Throwable) {
                // 预加载失败不影响正常加载路径
            } finally {
                inFlight.remove(key)
            }
        }
    }

    /** 列表可见 / 启动预热：只取仓库信息（最轻，供卡片与后续页面复用）。 */
    fun warmList(
        context: Context,
        host: String,
        token: String,
        entries: List<Pair<String, String>>,
    ) {
        if (!enabled(context) || metered(context)) return
        val appContext = context.applicationContext
        entries.take(PREFETCH_LIST_LIMIT).forEach { (owner, repo) ->
            if (owner.isBlank() || repo.isBlank()) return@forEach
            prefetch(appContext, PrefetchReason.ListVisible, host, token, owner, repo)
        }
    }

    // ── 内部：实际取数 ──

    /** 只预热仓库信息（列表卡片用，最轻的一个请求）。 */
    private suspend fun warmInfoOnly(
        manager: SearchCacheManager,
        host: String,
        token: String,
        owner: String,
        repo: String,
    ) {
        val key = PreloadStore.infoKey(owner, repo)
        if (manager.isFresh(key, PreloadStore.TYPE_INFO)) return
        RustBridge.getRepoInfo(host, token, owner, repo)
            ?.takeIf { !it.startsWith("ERROR:") }
            ?.let { manager.put(key, PreloadStore.TYPE_INFO, it) }
    }

    /** 仓库页四件套：信息（决定默认分支）→ README/语言/贡献者并行。 */
    private suspend fun warmOverview(
        manager: SearchCacheManager,
        host: String,
        token: String,
        owner: String,
        repo: String,
        branchHint: String?,
    ) {
        val infoKey = PreloadStore.infoKey(owner, repo)
        var branch = branchHint
        if (branch == null) {
            if (!manager.isFresh(infoKey, PreloadStore.TYPE_INFO)) {
                RustBridge.getRepoInfo(host, token, owner, repo)
                    ?.takeIf { !it.startsWith("ERROR:") }
                    ?.let { manager.put(infoKey, PreloadStore.TYPE_INFO, it) }
            }
            branch = defaultBranchOfCached(manager.getStale(infoKey, PreloadStore.TYPE_INFO))
                ?: branchHint
                ?: "main"
        }

        coroutineScope {
            val readme = async {
                val k = PreloadStore.readmeKey(owner, repo, branch)
                if (!manager.isFresh(k, PreloadStore.TYPE_README)) {
                    RustBridge.readmeHtml(host, token, owner, repo, branch)
                        ?.takeIf { !it.startsWith("ERROR:") }
                        ?.let { manager.put(k, PreloadStore.TYPE_README, it) }
                }
            }
            val langs = async {
                val k = PreloadStore.langKey(owner, repo)
                if (!manager.isFresh(k, PreloadStore.TYPE_LANG)) {
                    RustBridge.getRepoLanguages(host, token, owner, repo)
                        ?.takeIf { !it.startsWith("ERROR:") }
                        ?.let { manager.put(k, PreloadStore.TYPE_LANG, it) }
                }
            }
            val contribs = async {
                val k = PreloadStore.contribKey(owner, repo)
                if (!manager.isFresh(k, PreloadStore.TYPE_CONTRIB)) {
                    RustBridge.getRepoContributors(host, token, owner, repo)
                        ?.takeIf { !it.startsWith("ERROR:") }
                        ?.let { manager.put(k, PreloadStore.TYPE_CONTRIB, it) }
                }
            }
            readme.await()
            langs.await()
            contribs.await()
        }
    }

    /** 其他 tab 的第一页（默认分支下），键与各列表页一致。 */
    private suspend fun warmTabs(
        manager: SearchCacheManager,
        host: String,
        token: String,
        owner: String,
        repo: String,
    ) {
        val branch = defaultBranchOfCached(manager.getStale(PreloadStore.infoKey(owner, repo), PreloadStore.TYPE_INFO))
            ?: "main"
        val ref = encodeRef(branch)

        coroutineScope {
            // 代码树根目录
            async {
                val k = ListCache.key(owner, repo, ListCache.PAGE_CODE, ListCache.codeParams("", branch))
                if (!manager.isFresh(k, ListCache.TYPE)) {
                    RustBridge.getJson(host, token, "/repos/$owner/$repo/contents?ref=$ref")
                        ?.takeIf { !it.startsWith("ERROR:") }
                        ?.let { ListCache.write(manager, k, it) }
                }
            }
            async {
                val k = ListCache.key(owner, repo, ListCache.PAGE_ISSUES)
                if (!manager.isFresh(k, ListCache.TYPE)) {
                    RustBridge.getJson(host, token, "/repos/$owner/$repo/issues?state=all")
                        ?.takeIf { !it.startsWith("ERROR:") }
                        ?.let { ListCache.write(manager, k, it) }
                }
            }
            async {
                val k = ListCache.key(owner, repo, ListCache.PAGE_PULLS, branch)
                if (!manager.isFresh(k, ListCache.TYPE)) {
                    RustBridge.getJson(host, token, "/repos/$owner/$repo/pulls?state=all&base=$ref")
                        ?.takeIf { !it.startsWith("ERROR:") }
                        ?.let { ListCache.write(manager, k, it) }
                }
            }
            async {
                val k = ListCache.key(owner, repo, ListCache.PAGE_COMMITS, branch)
                if (!manager.isFresh(k, ListCache.TYPE)) {
                    RustBridge.getJson(host, token, "/repos/$owner/$repo/commits?sha=$ref")
                        ?.takeIf { !it.startsWith("ERROR:") }
                        ?.let { ListCache.write(manager, k, it) }
                }
            }
            async {
                val k = ListCache.key(owner, repo, ListCache.PAGE_RELEASES)
                if (!manager.isFresh(k, ListCache.TYPE)) {
                    RustBridge.getJson(host, token, "/repos/$owner/$repo/releases")
                        ?.takeIf { !it.startsWith("ERROR:") }
                        ?.let { ListCache.write(manager, k, it) }
                }
            }
        }
    }

    /** 从仓库信息 JSON 里取默认分支（复用 [defaultBranchOf]，避免 cache 依赖 ui 层模型）。 */
    private fun defaultBranchOfCached(infoJson: String?): String? = defaultBranchOf(infoJson)

    /** 代码树缓存键（供仓库页在切目录时复用同一约定）。 */
    fun codeKey(owner: String, repo: String, path: String, branch: String?): String =
        ListCache.key(owner, repo, ListCache.PAGE_CODE, ListCache.codeParams(path, branch))

    /** 目录 URL（与 `RepositoryCodeContent` 的构造保持一致）。 */
    fun codeUrl(owner: String, repo: String, path: String, branch: String?): String {
        val encoded = if (path.isEmpty()) "" else "/${encodePath(path)}"
        val ref = branch?.takeIf { it.isNotBlank() }?.let { b -> "?ref=${encodeRef(b)}" } ?: ""
        return "/repos/$owner/$repo/contents$encoded$ref"
    }
}
