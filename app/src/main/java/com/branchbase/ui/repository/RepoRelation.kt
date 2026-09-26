package com.branchbase.ui.repository

import androidx.annotation.StringRes
import com.branchbase.R
import com.branchbase.ui.LocalizedText
import org.json.JSONArray
import org.json.JSONObject

/**
 * 仓库页三个按钮（星标 / 复刻 / 关注）的**判定规则**。
 *
 * ## 为什么单独一个文件
 *
 * 这三个按钮的形态取决于「当前登录用户与这个仓库的关系」，而关系有三个来源、
 * 精度不同（见 [RepoViewerRelation.Source]）：
 *
 * 1. **网页**（最准）：仓库页 `react-app.embeddedData` 里的 `payload.sidebarAbout`，
 *    直接给出网页版自己的判定结果 —— `star.viewerHasStarred`、`fork.canFork`、
 *    `fork.forkabilityError`、`watch.subscriptionType` / `subscribableThreadTypes`。
 *    一个请求拿到全部三者，且能区分「自己的仓库」与「组织禁用了复刻」。
 * 2. **GraphQL**（次准）：没有网页会话时的一次查询，给 `viewerHasStarred` /
 *    `viewerSubscription` / `forkingAllowed`。比发两个 REST 探测请求少一次往返。
 * 3. **本地**（兜底、零网络）：`owner == 当前登录名` 就能判出「自己的仓库」——
 *    这是复刻按钮形态的关键分支，**不需要等任何请求**，所以自持仓库不会出现按钮延迟。
 *
 * 所有函数都是纯函数（无 IO、无 Android 依赖），可直接单测 —— 判定规则必须可回归。
 */

/** 网页版 Watch 档位（与 github.com 的 `subscriptionType` 一一对应）。 */
enum class WatchLevel(
    /** 网页端点的取值，也是 `subscriptionType` 的取值。 */
    val id: String,
    /** 与网页版一致的原样文案（截图里就是这样）。 */
    val title: String,
    @StringRes val descRes: Int,
) {
    PARTICIPATING("none", "Participating and @mentions", R.string.watch_level_participating_desc),
    ALL_ACTIVITY("watching", "All Activity", R.string.watch_level_all_desc),
    IGNORE("ignoring", "Ignore", R.string.watch_level_ignore_desc),
    CUSTOM("custom", "Custom", R.string.watch_level_custom_desc);

    companion object {
        /** 未知/缺失一律按 GitHub 的默认档「参与 + @提及」处理。 */
        fun fromId(id: String?): WatchLevel =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: PARTICIPATING
    }
}

/**
 * Custom 档位下可勾选的事件类型（宽度来自仓库自身能力）。
 *
 * [enabled] 是网页给的判定：仓库没开 Discussions 时该项不可勾 —— 这正是
 * 「解析网页」比硬编码清单准的地方。
 */
data class WatchThreadType(
    val name: String,
    val enabled: Boolean,
    val subscribed: Boolean,
) {
    /** 网页版的英文标签（与 GitHub 一致，不做汉化 —— 用户看到的和网页端一致）。 */
    val label: String
        get() = when (name) {
            "Issue" -> "Issues"
            "PullRequest" -> "Pull requests"
            "Release" -> "Releases"
            "Discussion" -> "Discussions"
            "SecurityAlert" -> "Security alerts"
            else -> name
        }
}

/**
 * 当前登录用户与某个仓库的关系（三个按钮判定的唯一输入）。
 *
 * 字段全部可空：来源不同能拿到的粒度不同，**不要用 `false` 冒充「未知」** ——
 * 复刻按钮的形态会因此判反。
 */
data class RepoViewerRelation(
    val starred: Boolean = false,
    val canStar: Boolean? = null,
    val canFork: Boolean? = null,
    val forkError: String? = null,
    val canWatch: Boolean? = null,
    val subscription: WatchLevel? = null,
    val threadTypes: List<WatchThreadType> = emptyList(),
    val watchersCount: Long? = null,
    /** 网页用的仓库 id（订阅写入必须带上它）。 */
    val repositoryId: String? = null,
    /**
     * 本仓库是不是复刻、复刻自谁（`owner/name`）。
     *
     * 两处都为 null = 这个来源没给复刻信息（网页那条路就不给）—— 上游判定会因此落在
     * [UpstreamState.UNKNOWN]，**不要用 `false` / 空串冒充**：`false` 会被判成
     * 「自持仓库」，界面会因此把上游出口整枚藏掉。
     */
    val isFork: Boolean? = null,
    val parentFullName: String? = null,
    val source: Source = Source.UNKNOWN,
) {
    enum class Source { WEB, GRAPHQL, UNKNOWN }

    /** 只覆盖「可以确定」的字段，避免后到的低精度数据把高精度结果冲掉。 */
    fun merge(lower: RepoViewerRelation): RepoViewerRelation = copy(
        starred = if (source == Source.UNKNOWN) lower.starred else starred,
        canStar = canStar ?: lower.canStar,
        canFork = canFork ?: lower.canFork,
        forkError = forkError ?: lower.forkError,
        canWatch = canWatch ?: lower.canWatch,
        subscription = subscription ?: lower.subscription,
        threadTypes = threadTypes.ifEmpty { lower.threadTypes },
        watchersCount = watchersCount ?: lower.watchersCount,
        repositoryId = repositoryId ?: lower.repositoryId,
        isFork = isFork ?: lower.isFork,
        parentFullName = parentFullName ?: lower.parentFullName,
    )

    /**
     * 缓存形态（[com.branchbase.cache.PreloadStore.TYPE_RELATION]）。
     *
     * 存归一化后的几个值，而不是原始网页 JSON：原始 `embeddedData` 有几 KB 且随
     * GitHub 改版漂移，存它等于把「网页结构」也缓存下来；这里只留判定需要的字段，
     * 解析不出来就当没缓存，不会崩。
     */
    fun toJson(): String = JSONObject().apply {
        put("starred", starred)
        canStar?.let { put("canStar", it) }
        canFork?.let { put("canFork", it) }
        forkError?.let { put("forkError", it) }
        canWatch?.let { put("canWatch", it) }
        subscription?.let { put("subscription", it.id) }
        watchersCount?.let { put("watchersCount", it) }
        repositoryId?.let { put("repositoryId", it) }
        isFork?.let { put("isFork", it) }
        parentFullName?.let { put("parentFullName", it) }
        put("source", source.name)
        put(
            "threadTypes",
            JSONArray().apply {
                threadTypes.forEach { t ->
                    put(JSONObject().put("name", t.name).put("enabled", t.enabled).put("subscribed", t.subscribed))
                }
            },
        )
    }.toString()

    companion object {
        /**
         * 读缓存；**格式不认识就返回 null**（由调用方回源），不要退化成「全默认值」。
         *
         * 这一点很关键：默认值意味着 `starred=false` —— 一个被截断、或别的版本写下的
         * 缓存会让「已星标」显示成「星标」，用户点下去等于**取消收藏**。
         * `source` 是本格式的版本标记（[toJson] 必写），拿它当识别依据。
         */
        fun fromCache(json: String): RepoViewerRelation? = runCatching {
            val o = JSONObject(json)
            if (!o.has("source")) return@runCatching null
            RepoViewerRelation(
                starred = o.optBoolean("starred", false),
                canStar = o.optBooleanOrNull("canStar"),
                canFork = o.optBooleanOrNull("canFork"),
                forkError = o.optString("forkError").takeIf { it.isNotBlank() },
                canWatch = o.optBooleanOrNull("canWatch"),
                subscription = o.optString("subscription").takeIf { it.isNotBlank() }?.let { WatchLevel.fromId(it) },
                threadTypes = parseThreadTypes(o.optJSONArray("threadTypes")),
                watchersCount = o.optLongOrNull("watchersCount"),
                repositoryId = o.optString("repositoryId").takeIf { it.isNotBlank() },
                isFork = o.optBooleanOrNull("isFork"),
                parentFullName = o.optString("parentFullName").takeIf { it.isNotBlank() },
                source = runCatching { Source.valueOf(o.optString("source")) }.getOrDefault(Source.UNKNOWN),
            )
        }.getOrNull()
        /** 网页未登录时的空壳（`not_logged_in` 只说明没会话，不是「不能做」）。 */
        fun fromWeb(json: JSONObject): RepoViewerRelation? {
            val about = json.optJSONObject("payload")
                ?.optJSONObject("sidebarAbout") ?: return null
            val star = about.optJSONObject("star")
            val fork = about.optJSONObject("fork")
            val watch = about.optJSONObject("watch")
            val data = watch?.optJSONObject("watchData")
            return RepoViewerRelation(
                starred = star?.optBoolean("viewerHasStarred", false) ?: false,
                canStar = star?.optBooleanOrNull("canStar"),
                canFork = fork?.optBooleanOrNull("canFork"),
                forkError = fork?.optString("forkabilityError")?.takeIf { it.isNotBlank() },
                canWatch = watch?.optBooleanOrNull("canWatch"),
                subscription = data?.optString("subscriptionType")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { WatchLevel.fromId(it) },
                threadTypes = parseThreadTypes(data?.optJSONArray("subscribableThreadTypes")),
                watchersCount = data?.optLongOrNull("watchersCount"),
                repositoryId = data?.optString("repositoryId")?.takeIf { it.isNotBlank() },
                source = Source.WEB,
            )
        }

        /**
         * GraphQL 回退：`repository{ viewerHasStarred viewerSubscription forkingAllowed … }`。
         *
         * 只在没有网页会话时使用 —— 它的粒度低于网页（没有 `forkabilityError`，
         * Custom 档位也读不出来，会被压成「参与 + @提及」）。
         */
        fun fromGraphQL(data: JSONObject): RepoViewerRelation? {
            val repo = data.optJSONObject("repository") ?: return null
            return RepoViewerRelation(
                starred = repo.optBoolean("viewerHasStarred", false),
                canFork = repo.optBooleanOrNull("forkingAllowed"),
                canWatch = true,
                subscription = repo.optString("viewerSubscription")
                    .takeIf { it.isNotBlank() }
                    ?.let(::watchLevelOfGraphQL),
                watchersCount = repo.optJSONObject("watchers")?.optLongOrNull("totalCount"),
                // 这两项 RELATION_QUERY 一直在查（RepoActions.RELATION_QUERY 里的 isFork /
                // parent{nameWithOwner}），只是以前没解析 —— 补上就白得复刻关系，不必多一次请求。
                isFork = repo.optBooleanOrNull("isFork"),
                parentFullName = repo.optJSONObject("parent")
                    ?.optString("nameWithOwner")
                    ?.takeIf { it.isNotBlank() },
                source = Source.GRAPHQL,
            )
        }
    }
}

/** GraphQL 的三态 → 网页档位。Custom 在 GraphQL 里读不出来，按默认档处理。 */
internal fun watchLevelOfGraphQL(state: String): WatchLevel = when (state.uppercase()) {
    "SUBSCRIBED" -> WatchLevel.ALL_ACTIVITY
    "IGNORED" -> WatchLevel.IGNORE
    else -> WatchLevel.PARTICIPATING
}

private fun parseThreadTypes(arr: JSONArray?): List<WatchThreadType> {
    if (arr == null) return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val name = o.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        WatchThreadType(
            name = name,
            enabled = o.optBoolean("enabled", true),
            subscribed = o.optBoolean("subscribed", false),
        )
    }
}

/** `optBoolean` 把「缺字段」和 `false` 混成一件事 —— 判定不能这么含糊。 */
internal fun JSONObject.optBooleanOrNull(key: String): Boolean? =
    if (has(key) && !isNull(key)) optBoolean(key) else null

internal fun JSONObject.optLongOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null

// ── 复刻按钮的形态判定 ──

/** 复刻按钮点下去该做什么。 */
enum class ForkMode {
    /** 他人仓库：走网页版流程（命名 / 重名校验 / 只复刻主分支）。 */
    DIALOG,

    /** 自己的仓库：网页版不允许复刻自己，按钮退化成「复刻列表」入口。 */
    LIST_ONLY,

    /** 明确被禁用（组织策略等）：按钮置灰并说明原因。 */
    DISABLED,
}

data class ForkDecision(val mode: ForkMode, val reason: LocalizedText? = null)

/**
 * 复刻按钮的判定。
 *
 * 顺序即优先级：**网页的明确结论 > 本地持有者判定 > REST 的 `allow_forking` > 默认开放**。
 * 本地持有者判定放在很前面是有意的：它零网络，所以即使仓库页还在回源，
 * 自持仓库的按钮形态也不会先错后改（这是「部分按钮延迟加载」的根因）。
 */
object RepoRelationRules {

    fun isOwner(owner: String, login: String): Boolean =
        owner.isNotBlank() && login.isNotBlank() && owner.equals(login, ignoreCase = true)

    fun forkDecision(
        relation: RepoViewerRelation?,
        info: RepoInfo?,
        owner: String,
        login: String,
    ): ForkDecision {
        val ownRepo = isOwner(owner, login)
        val canFork = relation?.canFork ?: info?.allowForking
        val err = relation?.forkError
        return when {
            canFork == true -> ForkDecision(ForkMode.DIALOG)

            // 自己的仓库：网页给的就是 LIST_ONLY 语义（复刻列表），不是「禁用」
            canFork == false && (ownRepo || err == "own_repository") ->
                ForkDecision(ForkMode.LIST_ONLY)

            canFork == false -> ForkDecision(ForkMode.DISABLED, forkErrorText(err))

            // 关系态还没到：本地持有者判定立刻给出正确形态，不留一个「先错后对」的按钮
            ownRepo -> ForkDecision(ForkMode.LIST_ONLY)

            info != null && !info.allowForking -> ForkDecision(ForkMode.DISABLED, null)

            else -> ForkDecision(ForkMode.DIALOG)
        }
    }

    /** 星标点击后的目标态（乐观更新的依据）。 */
    fun starredAfterToggle(current: Boolean): Boolean = !current

    /** 星标计数在切换后的增量。 */
    fun starDelta(after: Boolean): Long = if (after) 1L else -1L

    /** 未登录时三个按钮都不该给出「可写」的假象。 */
    fun canWrite(login: String): Boolean = login.isNotBlank()

    /**
     * 网页端点的表单字段（与 github.com 的 `notifications/subscribe` 完全一致）。
     *
     * 照抄网页版的三处细节，少一处就会被判为伪造请求或写错档位：
     * - `do=included` 才是「参与 + @提及」（不是 `do=none`）；
     * - 非 Custom 档位也要带一个**空**的 `thread_types[]`；
     * - Custom 且一个都没勾时，网页版同样退化成 `do=included`。
     */
    fun webSubscribeFields(
        repositoryId: String,
        level: WatchLevel,
        threadTypes: List<String> = emptyList(),
    ): List<Pair<String, String>> = buildList {
        add("repository_id" to repositoryId)
        when (level) {
            WatchLevel.IGNORE -> {
                add("do" to "ignore")
                add("thread_types[]" to "")
            }

            WatchLevel.ALL_ACTIVITY -> {
                add("do" to "subscribed")
                add("thread_types[]" to "")
            }

            WatchLevel.CUSTOM -> if (threadTypes.isEmpty()) {
                add("do" to "included")
                add("thread_types[]" to "")
            } else {
                add("do" to "custom")
                threadTypes.forEach { add("thread_types[]" to it) }
            }

            WatchLevel.PARTICIPATING -> {
                add("do" to "included")
                add("thread_types[]" to "")
            }
        }
    }

    /** REST 订阅端点（`PUT /repos/{o}/{r}/subscription`）的 body —— 只覆盖三档。 */
    fun restSubscriptionBody(level: WatchLevel): String = when (level) {
        WatchLevel.ALL_ACTIVITY -> """{"subscribed":true,"ignored":false}"""
        WatchLevel.IGNORE -> """{"subscribed":false,"ignored":true}"""
        else -> """{"subscribed":false,"ignored":false}"""
    }

    /**
     * 网页 `forkabilityError` → 给用户看的原因。
     *
     * 返回 [LocalizedText] 而不是 `String`：这段文案会进 Toast（`RepositoryScreen`），
     * 写死中文的话英文界面下弹的仍是中文。**未知 code 原样透出**（`LocalizedText(raw = …)`）——
     * 后端新增一个原因时不能被吞掉，也不能猜一个词顶上（同 `stateLabelResOrNull` 的口径）。
     */
    private fun forkErrorText(code: String?): LocalizedText? = when (code) {
        null, "" -> null
        "not_logged_in" -> LocalizedText(R.string.fork_error_not_logged_in)
        "own_repository" -> LocalizedText(R.string.fork_error_own_repository)
        "forking_disabled" -> LocalizedText(R.string.state_fork_disabled)
        "already_forked" -> LocalizedText(R.string.fork_error_already_forked)
        else -> LocalizedText(raw = code)
    }
}

/** 网页订阅写入的路径（相对 github.com）。 */
fun repoSubscribeUrl(owner: String, repo: String): String = "/$owner/$repo/notifications/subscribe"

/** 网页会话中打开关注者列表的路径（API 受限时的兜底）。 */
fun repoWatchersWebUrl(owner: String, repo: String): String = "/$owner/$repo/watching"
