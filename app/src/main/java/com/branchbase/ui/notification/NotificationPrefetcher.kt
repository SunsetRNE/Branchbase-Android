package com.branchbase.ui.notification

import android.content.Context
import com.branchbase.cache.PageCache
import com.branchbase.cache.PrefetchReason
import com.branchbase.cache.SearchCacheDatabase
import com.branchbase.cache.SearchCacheManager
import com.branchbase.cache.networkMetered
import com.branchbase.cache.planPrefetch
import com.branchbase.cache.prefetchEnabled
import com.branchbase.core.RustBridge
import com.branchbase.ui.log.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 消息页预加载器（需求 ③）。
 *
 * ## 要解决的问题
 *
 * 改造前是「两条腿各拉一次同一份数据」：
 * - 首页为了算未读数请求 `GET /notifications?per_page=100`，**只用来数长度**；
 * - 进消息页又请求 `GET /notifications?per_page=50&all=true`，首帧只能给骨架屏。
 *
 * 现在合成一条：首页渲染阶段就把**首屏那一份**取回来，写进 `PageCache`（落盘）
 * 与 [NotifSnapshot]（进程内存、已解析），于是
 * **进消息页 = 0 次网络 + 0 帧骨架**，首页的未读数也直接来自同一份数据。
 *
 * ## 约束
 *
 * - **不违背「计费网络不投机预取」**：只有 `planPrefetch(AppStart, …).notifications == true`
 *   （用户开了预加载开关 **且** 当前网络不计费）才会执行；
 * - **不阻塞首页**：调用方用 [warmUpAsync] fire-and-forget；[warmUp] 是挂起版本，
 *   首页在已有 `coroutineScope` 里 `launch` 它即可，与其它 4 路并行；
 * - **去重**：45s 窗口 + 单飞标志，来回切 Tab 不会连着打网络；
 * - **失败静默**：任何异常都不上抛，页面照旧走自己的加载路径。
 */
object NotificationPrefetcher {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 单飞：同一时刻只允许一批预取在跑。 */
    private val inFlight = AtomicBoolean(false)

    @Volatile
    private var lastAtMs = 0L

    /** 同一账号 45s 内不重复预取（快照 TTL 2 分钟，这里更短是为了「手动刷新后快速回到首页」也能更新）。 */
    private const val DEDUPE_WINDOW_MS = 45_000L

    /**
     * 首页阶段预取的预览条数。
     * 每条预览都是一次额外 HTTP，GitHub 的二级速率限制对突发很敏感 —— 宁少勿多。
     */
    private const val PREVIEW_LIMIT = 8

    /**
     * fire-and-forget 版本：给首页/启动路径用，立即返回、不阻塞渲染。
     */
    fun warmUpAsync(context: Context, host: String, token: String) {
        if (host.isBlank() || token.isBlank()) return
        val app = context.applicationContext
        scope.launch { runCatching { warmUp(app, host, token) } }
    }

    /**
     * 预取首屏通知（挂起版）。
     *
     * @param force 手动刷新 / 明确要求联网时置 true（跳过策略判定与去重窗口，但仍然单飞）
     * @return 未读条数；**被策略跳过或失败返回 null**（调用方必须准备回退路径）
     */
    suspend fun warmUp(
        context: Context,
        host: String,
        token: String,
        force: Boolean = false,
    ): Int? {
        if (host.isBlank() || token.isBlank()) return null
        val app = context.applicationContext

        if (!force) {
            // 快照还新鲜：直接用，既不发请求也不动缓存
            if (NotifSnapshot.isFresh()) return NotifSnapshot.unreadCount()

            val plan = planPrefetch(
                reason = PrefetchReason.AppStart,
                metered = networkMetered(app),
                userOptIn = prefetchEnabled(app),
                overviewFresh = true,
            )
            if (!plan.notifications) return null

            val now = System.currentTimeMillis()
            if (now - lastAtMs < DEDUPE_WINDOW_MS) return null
            if (!inFlight.compareAndSet(false, true)) return null
            lastAtMs = now
        } else if (!inFlight.compareAndSet(false, true)) {
            return null
        }

        return try {
            fetch(app, host, token, force)
        } catch (e: Throwable) {
            Logger.net("通知预取失败：${e.message}", "GitHubAPI")
            null
        } finally {
            inFlight.set(false)
        }
    }

    private suspend fun fetch(app: Context, host: String, token: String, force: Boolean): Int? {
        val path = notifListPath(participating = false)
        val manager = SearchCacheManager(SearchCacheDatabase.getInstance(app).searchCacheDao())
        val json = PageCache.refresh(
            manager = manager,
            key = PageCache.notificationKey(path),
            type = PageCache.TYPE_NOTIFICATION,
            force = force,
        ) {
            withContext(Dispatchers.IO) { RustBridge.getJson(host, token, path) }
        } ?: return null

        // 已「完成」的会话只存在于本地归档：远端仍返回它们（GitHub 没有 done 列表），
        // 这里必须剔除，否则首页徽标会把已完成的会话重新算成未读。
        val doneIds = NotifArchive.entries(app).filter { it.isDone }.map { it.id }.toSet()
        val items = NotifReadStore.apply(app, parseNotifications(json)).filterNot { it.id in doneIds }
        NotifSnapshot.update(items)
        Logger.net("预取 /notifications → ${items.size} 条（未读 ${items.count { it.unread }}）", "GitHubAPI")

        // 首屏预览：只取未读的 Issue/PR，失败静默（预览是纯增强信息）
        runCatching {
            val targets = items.filter {
                it.unread &&
                    (it.subjectType == "Issue" || it.subjectType == "PullRequest") &&
                    !it.latestCommentUrl.isNullOrBlank()
            }.take(PREVIEW_LIMIT)
            if (targets.isNotEmpty()) {
                val urlById = targets.associate { it.id to it.latestCommentUrl }
                prefetchPreviews(
                    context = app,
                    host = host,
                    token = token,
                    threadIds = targets.map { it.id },
                    commentUrlOf = { id -> urlById[id] },
                ) { p -> NotifSnapshot.putPreview(p) }
            }
        }.onFailure { Logger.net("通知预览预取失败：${it.message}", "GitHubAPI") }

        return items.count { it.unread }
    }
}
