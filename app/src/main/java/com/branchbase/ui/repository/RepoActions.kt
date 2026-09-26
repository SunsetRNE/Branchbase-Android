package com.branchbase.ui.repository

import android.content.Context
import com.branchbase.cache.PreloadStore
import com.branchbase.cache.SearchCacheDatabase
import com.branchbase.cache.SearchCacheManager
import com.branchbase.core.GithubWebSession
import com.branchbase.core.RustBridge
import com.branchbase.ui.log.Logger
import org.json.JSONObject

/**
 * 星标 / 关注 / 复刻的**执行层**（网络与判定分离）。
 *
 * 判定在 [RepoRelationRules]（纯函数、可单测），这里只负责取数与写入，
 * 并统一把失败原因折叠成「null = 成功，非 null = 给用户看的一句话」。
 *
 * ## 三条写入通道，按优先级选
 *
 * | 能力 | 首选 | 回退 |
 * |---|---|---|
 * | 关系态读取 | 网页 `sidebarAbout`（最准，一次拿全） | GraphQL（一次查询） |
 * | 星标 / 复刻 | REST（`PUT /user/starred`、`POST /forks`） | — |
 * | 关注三档 | 网页订阅端点（与网页版同源） | REST `PUT /subscription` |
 * | 关注 Custom | 网页订阅端点（**唯一通道**） | 提示登录网页会话 |
 */
object RepoActions {

    /**
     * 关系态查询：一次拿齐星标 / 关注 / 复刻能力，外加 watchers 计数。
     *
     * 字段与网页版一一对应；`viewerSubscription` 读不出 Custom（GraphQL 只有三态），
     * 所以没有网页会话时 Custom 档位会退化成「参与 + @提及」—— 这是已知且可接受的降级，
     * 面板打开时会再尝试用网页补一次。
     */
    private const val RELATION_QUERY = """
        query(${'$'}owner:String!,${'$'}name:String!){
          repository(owner:${'$'}owner,name:${'$'}name){
            viewerHasStarred
            viewerSubscription
            forkingAllowed
            stargazerCount
            forkCount
            watchers{totalCount}
            isFork
            parent{nameWithOwner}
          }
        }
    """

    /**
     * 取关系态（带 5 分钟缓存）。
     *
     * 网页优先：它给的 `forkabilityError` 能区分「自己的仓库」与「组织禁用复刻」，
     * 而 GraphQL 的 `forkingAllowed` 把两者混成同一个 false。
     *
     * 缓存键带账号（见 `PreloadStore.relationKey`）：星标/关注是「谁在看」的属性，
     * 多账号切换时不能串味。命中缓存就不发请求 —— 这是「返回上一层再进来」不再
     * 重复判定的关键；[setStar] / [setWatch] 成功后会把新状态写回，不留自相矛盾的缓存。
     */
    suspend fun loadRelation(
        context: Context,
        host: String,
        token: String,
        owner: String,
        repo: String,
        account: String = "",
    ): RepoViewerRelation? {
        val manager = SearchCacheManager(SearchCacheDatabase.getInstance(context).searchCacheDao())
        val key = PreloadStore.relationKey(account, owner, repo)
        manager.get(key, PreloadStore.TYPE_RELATION)
            ?.let { RepoViewerRelation.fromCache(it) }
            ?.let { return it }

        val fresh = fetchRelation(context, host, token, owner, repo) ?: return null
        manager.put(key, PreloadStore.TYPE_RELATION, fresh.toJson())
        return fresh
    }

    /**
     * 只读关系态缓存（**含过期**）：进仓库页时先直出，别让按钮空着等一次往返。
     *
     * 现场（真机日志 2026-09-22，v1.0.65）：进仓库页 → `未命中 repo-relation` →
     * 「网页会话不可用或已过期，改用 GraphQL 判定」→ **~800ms 之后**才拿到结论
     * （`22:52:52.519 进入` → `22:52:53.320 判定`）。这段时间里星标 / Watch 的形态是空的，
     * 用户看到的就是「页面先渲染一遍、再重画一遍」。
     *
     * 为什么可以直出：这个键的 TTL 只有 5 分钟（见 `SearchCacheManager.ttlFor`），
     * 过期不代表错得离谱；调用方**紧接着会回源复核**并用新值覆盖（旧值只在复核失败时留下），
     * 所以「旧到把已星标显示成未星标」这个风险被压在「一次往返」之内 —— 与
     * 「宁可多显示一次旧值，也不要空着等半秒」是同一个取舍。
     */
    suspend fun cachedRelation(
        context: Context,
        owner: String,
        repo: String,
        account: String = "",
    ): RepoViewerRelation? {
        val manager = SearchCacheManager(SearchCacheDatabase.getInstance(context).searchCacheDao())
        return manager.getStale(PreloadStore.relationKey(account, owner, repo), PreloadStore.TYPE_RELATION)
            ?.let { RepoViewerRelation.fromCache(it) }
    }

    /** 写回关系态缓存：切换星标 / 关注后调用，保证界面状态与缓存状态一致。 */
    suspend fun cacheRelation(
        context: Context,
        owner: String,
        repo: String,
        account: String,
        relation: RepoViewerRelation,
    ) {
        runCatching {
            val manager = SearchCacheManager(SearchCacheDatabase.getInstance(context).searchCacheDao())
            manager.put(PreloadStore.relationKey(account, owner, repo), PreloadStore.TYPE_RELATION, relation.toJson())
        }
    }

    /** 真正回源：网页一次抓取拿全；没有网页会话时用一次 GraphQL。 */
    private suspend fun fetchRelation(
        context: Context,
        host: String,
        token: String,
        owner: String,
        repo: String,
    ): RepoViewerRelation? {
        val cookie = GithubWebSession.cookie(context, host)
        if (cookie.isNotBlank()) {
            val web = RustBridge.webRepoEmbedded(host, cookie, owner, repo)
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?.let { RepoViewerRelation.fromWeb(it) }
                // canStar=false 说明网页把我们当未登录（Cookie 过期），此时它给的
                // fork/watch 也都是游客视角 —— 不能拿来当判定输入
                ?.takeIf { it.canStar != false }
            if (web != null) return web
            Logger.net("网页会话不可用或已过期，改用 GraphQL 判定 $owner/$repo", "GitHubAPI")
        }
        val data = RustBridge.graphQL(host, token, RELATION_QUERY, """{"owner":"$owner","name":"$repo"}""")
            ?.takeIf { !it.startsWith("ERROR:") }
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return null
        return RepoViewerRelation.fromGraphQL(data)
    }

    /**
     * 上游关系（1.1.5，Git 模式）：自动探测本仓库在复刻网络里的上游，并给出它的状态。
     *
     * 一次调用做完三件事，调用方只拿结论：
     *
     * 1. **复刻关系**：宿主已经读到的 [info]（仓库详情）/ [relation]（关系态）直接用，
     *    两个都没有才自己读一次 `/repos/{owner}/{repo}` —— 仓库页本来就在读同一份数据，
     *    不该为了「有没有上游」再发一次；
     * 2. **上游状态**：有上游、且有令牌时探一次 `GET /repos/{parent}`。这里**必须**用
     *    [RustBridge.getJson] 而不是 `getRepoInfo`：前者把 HTTP 状态留在
     *    `ERROR:HTTP 404: …` 里，后者失败一律返回 null —— 而「404 = 复刻关系还在、
     *    却读不到」正是「上游已私有化」与「网络没打通」唯一的分界（见 [UpstreamState]）；
     * 3. **结论**：交给 [UpstreamRules.detect]（纯函数，规则可单测）。探测没打通一律
     *    收敛成「未知」，不拿它当结论。
     */
    suspend fun loadUpstream(
        host: String,
        token: String,
        owner: String,
        repo: String,
        info: RepoInfo? = null,
        relation: RepoViewerRelation? = null,
    ): UpstreamRelation {
        val self = info ?: parseRepoInfo(RustBridge.getJson(host, token, "/repos/$owner/$repo").orEmpty())
        val (isFork, parent) = UpstreamRules.resolve(self, relation)
        val parts = UpstreamRules.splitFullName(parent)
        val hasToken = token.isNotBlank()

        var attempted = false
        var denied = false
        var upstream: RepoInfo? = null
        if (parts != null && hasToken) {
            attempted = true
            val (parentOwner, parentName) = parts
            val raw = RustBridge.getJson(host, token, "/repos/$parentOwner/$parentName")
            denied = raw != null && raw.startsWith("ERROR:") && raw.contains("404")
            upstream = raw?.takeIf { !it.startsWith("ERROR:") }?.let { parseRepoInfo(it) }
        }

        val rel = UpstreamRules.detect(
            isFork = isFork,
            parentFullName = parent,
            probe = UpstreamProbe(info = upstream, attempted = attempted, denied = denied),
            hasToken = hasToken,
        )
        // 判定结果写日志：真机上「界面为什么没画那枚胶囊」只靠这一行能回答
        Logger.net(
            buildString {
                append("上游 ▸ $owner/$repo：${rel.state.name}")
                rel.fullName?.let { append(" · $it") }
                append(
                    when {
                        !hasToken -> "（无令牌，未探测）"
                        !attempted -> "（没有上游可探）"
                        denied -> "（上游 404：关系还在、读不到）"
                        upstream != null -> "（上游可读）"
                        else -> "（探测未打通，按未知处理）"
                    },
                )
            },
            "GitHubAPI",
        )
        return rel
    }

    /** 星标 / 取消星标（`PUT|DELETE /user/starred/{o}/{r}`）。null = 成功。 */
    suspend fun setStar(
        host: String,
        token: String,
        owner: String,
        repo: String,
        starred: Boolean,
    ): String? = if (starred) {
        RustBridge.putJson(host, token, "/user/starred/$owner/$repo", "")
            .also { Logger.net("PUT /user/starred/$owner/$repo → ${if (it == null) "204" else it}", "GitHubAPI") }
    } else {
        RustBridge.deleteJson(host, token, "/user/starred/$owner/$repo")
            .also { Logger.net("DELETE /user/starred/$owner/$repo → ${if (it == null) "204" else it}", "GitHubAPI") }
    }

    /**
     * 设置 Watch 档位。null = 成功。
     *
     * 有网页会话时**四档都走网页端点**：与网页版完全同源（REST 表达不了 Custom，
     * 而且 REST 与网页端对「参与 + @提及」的写法不同，混用容易出现两处状态不一致）。
     */
    suspend fun setWatch(
        context: Context,
        host: String,
        token: String,
        owner: String,
        repo: String,
        relation: RepoViewerRelation?,
        level: WatchLevel,
        threadTypes: List<String> = emptyList(),
    ): String? {
        val cookie = GithubWebSession.cookie(context, host)
        // 网页端点必须带 repository_id：关系态若是 GraphQL 来的就没有它
        // （GraphQL 不回这个 id）—— 此时就地为这一次写入补一次页面抓取，
        // 而不是把「已登录」的用户误报成「需要先登录」。
        val repositoryId = relation?.repositoryId
            ?: if (cookie.isNotBlank()) fetchRelation(context, host, token, owner, repo)?.repositoryId else null
        if (cookie.isNotBlank() && repositoryId != null) {
            val fields = RepoRelationRules.webSubscribeFields(repositoryId, level, threadTypes)
            val body = RustBridge.webPostForm(
                host = host,
                cookie = cookie,
                pagePath = "/$owner/$repo",
                postPath = repoSubscribeUrl(owner, repo),
                fields = fields,
            )
            return when {
                body == null -> "网页无响应"
                body.startsWith("ERROR:") -> body.removePrefix("ERROR:")
                else -> runCatching { JSONObject(body) }.getOrNull()?.let { o ->
                    when {
                        o.optBoolean("ok", false) -> null
                        o.has("error") -> webSubscribeError(o.optString("error"))
                        else -> "写入失败"
                    }
                } ?: "写入失败"
            }
        }
        if (level == WatchLevel.CUSTOM) return "自定义通知需要先登录 GitHub 网页会话"
        return RustBridge.putJson(host, token, "/repos/$owner/$repo/subscription", RepoRelationRules.restSubscriptionBody(level))
    }

    /** 网页端点返回的错误码 → 提示语（与网页版自己的处理保持一致）。 */
    private fun webSubscribeError(code: String): String = when (code) {
        "limit_exceeded" -> "通知订阅数量已达上限"
        "forbidden" -> "没有权限修改该仓库的通知设置"
        "" -> "写入失败"
        else -> code
    }

    /**
     * Custom 档位的事件清单（仓库没开 Discussions 时该项不可勾）。
     *
     * 只在用户点开 Custom 时才拉：这是**未登录也能读**的公开信息，
     * 没必要为了它让每次进仓库都多一个请求。有网页会话时顺带拿到当前勾选。
     */
    suspend fun loadWatchThreadTypes(
        context: Context,
        host: String,
        owner: String,
        repo: String,
    ): List<WatchThreadType> {
        val cookie = GithubWebSession.cookie(context, host)
        val embedded = RustBridge.webRepoEmbedded(host, cookie, owner, repo) ?: return emptyList()
        val json = runCatching { JSONObject(embedded) }.getOrNull() ?: return emptyList()
        return RepoViewerRelation.fromWeb(json)?.threadTypes.orEmpty()
    }

    /** 复刻目标的候选账户：当前用户 + 有建库权限的组织。 */
    suspend fun forkTargets(host: String, token: String, login: String): List<String> {
        if (login.isBlank()) return emptyList()
        val orgs = RustBridge.getJson(host, token, "/user/orgs?per_page=100")
            ?.takeIf { !it.startsWith("ERROR:") }
            ?.let { runCatching { org.json.JSONArray(it) }.getOrNull() }
            ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("login")?.takeIf { s -> s.isNotBlank() } } }
            .orEmpty()
        return listOf(login) + orgs
    }

    /**
     * 目标账户下是否已存在同名仓库（网页版复刻弹窗的实时重名校验）。
     *
     * 三态：true 已存在 / false 可用 / null 校验失败（网络）。**null 不能当 false 用** ——
     * 否则一断网就允许提交一个必然 422 的请求。
     */
    suspend fun repoExists(host: String, token: String, owner: String, name: String): Boolean? {
        if (owner.isBlank() || name.isBlank()) return null
        val res = RustBridge.getJson(host, token, "/repos/$owner/$name")
        return when {
            res == null -> null
            res.startsWith("ERROR:") -> if (res.contains("404")) false else null
            else -> true
        }
    }

    /** 复刻结果：成功给新仓库全名，失败给原因。 */
    data class ForkResult(val fullName: String? = null, val error: String? = null)

    /**
     * 创建复刻（`POST /repos/{o}/{r}/forks`）。
     *
     * `default_branch_only` 是网页版复刻弹窗里的「只复刻默认分支」勾选；
     * `organization` 为空串表示复刻到个人账户。
     */
    suspend fun createFork(
        host: String,
        token: String,
        owner: String,
        repo: String,
        organization: String,
        name: String,
        defaultBranchOnly: Boolean,
    ): ForkResult {
        val body = JSONObject()
            .put("name", name)
            .put("default_branch_only", defaultBranchOnly)
            .apply { if (organization.isNotBlank()) put("organization", organization) }
            .toString()
        val res = RustBridge.postJson(host, token, "/repos/$owner/$repo/forks", body)
            ?: return ForkResult(error = "无响应")
        if (res.startsWith("ERROR:")) {
            val msg = res.removePrefix("ERROR:").let { raw ->
                // 422 的响应体里有 GitHub 的原话（重名、被禁用等），比状态码有用
                Regex("\"message\"\\s*:\\s*\"([^\"]+)\"").find(raw)?.groupValues?.get(1) ?: raw
            }
            Logger.net("POST /repos/$owner/$repo/forks 失败：$msg", "GitHubAPI")
            return ForkResult(error = msg.take(160))
        }
        val full = runCatching { JSONObject(res).optString("full_name") }.getOrNull().orEmpty()
        Logger.net("POST /repos/$owner/$repo/forks → ${full.ifBlank { "202" }}", "GitHubAPI")
        return ForkResult(fullName = full.ifBlank { null })
    }

    /** 网页版通知设置地址（面板底部的「Watch settings」）。 */
    fun watchSettingsUrl(host: String): String =
        if (host.isBlank() || host == "github.com") "https://github.com/settings/notifications"
        else "https://$host/settings/notifications"
}
