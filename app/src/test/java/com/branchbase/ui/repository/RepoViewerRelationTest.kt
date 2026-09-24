package com.branchbase.ui.repository

import org.json.JSONObject
import com.branchbase.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三个按钮的判定规则回归测试。
 *
 * 这些规则决定了「点下去会发生什么」，而且**每一处误判都会造成真实后果**
 * （复刻判错 = 对别人的仓库弹出「不能复刻自己的仓库」；星标判错 = 点一下给别人的仓库取消收藏）。
 * 所以规则必须是纯函数并被钉住。
 *
 * 用例里的 JSON 直接取自 github.com 仓库页真实的 `react-app.embeddedData`
 * （octocat/Hello-World 未登录态，字段名与嵌套结构一字未改）。
 */
class RepoViewerRelationTest {

    private val webJson = """
        {"payload":{"sidebarAbout":{
          "star":{"viewerHasStarred":true,"canStar":true},
          "fork":{"canFork":false,"forkabilityError":"own_repository"},
          "pin":{"canPin":false,"isPinned":false},
          "watch":{"canWatch":true,"watchData":{
            "repositoryId":"1296269",
            "repositoryName":"octocat/Hello-World",
            "watchersCount":1734,
            "subscriptionType":"custom",
            "subscribableThreadTypes":[
              {"name":"Issue","enabled":true,"subscribed":true},
              {"name":"PullRequest","enabled":true,"subscribed":false},
              {"name":"Release","enabled":true,"subscribed":true},
              {"name":"Discussion","enabled":false,"subscribed":false},
              {"name":"SecurityAlert","enabled":true,"subscribed":false}],
            "showLabelSubscriptions":false,"subscribedLabels":[]}}}},
          "title":"GitHub"}
    """.trimIndent()

    @Test
    fun web_relation_is_parsed_from_sidebar_about() {
        val r = RepoViewerRelation.fromWeb(JSONObject(webJson))!!
        assertTrue(r.starred)
        assertEquals(true, r.canStar)
        assertEquals(false, r.canFork)
        assertEquals("own_repository", r.forkError)
        assertEquals(true, r.canWatch)
        assertEquals(WatchLevel.CUSTOM, r.subscription)
        assertEquals(1734L, r.watchersCount)
        assertEquals("1296269", r.repositoryId)
        assertEquals(RepoViewerRelation.Source.WEB, r.source)
        assertEquals(5, r.threadTypes.size)
        // Discussions 未启用 —— 这是「解析网页」相对硬编码清单的收益
        assertFalse(r.threadTypes.first { it.name == "Discussion" }.enabled)
        assertTrue(r.threadTypes.first { it.name == "Issue" }.subscribed)
        assertEquals("Pull requests", r.threadTypes.first { it.name == "PullRequest" }.label)
    }

    /** 未登录的网页数据不能当判定输入用（`canStar=false` 是「没会话」而不是「不能星标」）。 */
    @Test
    fun anonymous_web_payload_is_recognisable() {
        val anon = """
            {"payload":{"sidebarAbout":{"star":{"viewerHasStarred":false,"canStar":false},
             "fork":{"canFork":false,"forkabilityError":"not_logged_in"},
             "watch":{"canWatch":false,"watchData":{"subscriptionType":"none","subscribableThreadTypes":[]}}}}}
        """.trimIndent()
        val r = RepoViewerRelation.fromWeb(JSONObject(anon))!!
        assertEquals(false, r.canStar)
        assertEquals("not_logged_in", r.forkError)
        assertTrue(r.threadTypes.isEmpty())
    }

    @Test
    fun missing_sidebar_about_yields_null() {
        assertNull(RepoViewerRelation.fromWeb(JSONObject("""{"payload":{}}""")))
    }

    @Test
    fun graphql_relation_maps_three_states() {
        fun of(state: String) = RepoViewerRelation.fromGraphQL(
            JSONObject("""{"repository":{"viewerHasStarred":true,"viewerSubscription":"$state",
                "forkingAllowed":true,"watchers":{"totalCount":12}}}"""),
        )!!
        assertEquals(WatchLevel.ALL_ACTIVITY, of("SUBSCRIBED").subscription)
        assertEquals(WatchLevel.IGNORE, of("IGNORED").subscription)
        // GraphQL 读不出 Custom，会退化成默认档 —— 这是已知降级
        assertEquals(WatchLevel.PARTICIPATING, of("UNSUBSCRIBED").subscription)
        assertEquals(12L, of("SUBSCRIBED").watchersCount)
    }

    // ── 复刻：持有者判定 ──

    @Test
    fun own_repo_is_list_only_even_without_any_network_data() {
        // 这条是「自持仓库按钮不延迟」的根据：判定只需要 owner 与 login
        val d = RepoRelationRules.forkDecision(null, null, "octocat", "octocat")
        assertEquals(ForkMode.LIST_ONLY, d.mode)
    }

    @Test
    fun own_repo_judgement_is_case_insensitive() {
        assertEquals(ForkMode.LIST_ONLY, RepoRelationRules.forkDecision(null, null, "OctoCat", "octocat").mode)
    }

    @Test
    fun foreign_repo_opens_the_web_flow() {
        val d = RepoRelationRules.forkDecision(
            RepoViewerRelation(canFork = true, source = RepoViewerRelation.Source.WEB),
            null,
            "torvalds",
            "octocat",
        )
        assertEquals(ForkMode.DIALOG, d.mode)
    }

    @Test
    fun explicit_web_verdict_beats_local_guess() {
        // 网页说不能复刻且原因是 own_repository —— 即便 owner 与登录名不一致（改名等），也按网页走
        val d = RepoRelationRules.forkDecision(
            RepoViewerRelation(canFork = false, forkError = "own_repository"),
            null,
            "legacy-name",
            "octocat",
        )
        assertEquals(ForkMode.LIST_ONLY, d.mode)
    }

    @Test
    fun disabled_forking_reports_a_reason() {
        val d = RepoRelationRules.forkDecision(
            RepoViewerRelation(canFork = false, forkError = "forking_disabled"),
            null,
            "some-org",
            "octocat",
        )
        assertEquals(ForkMode.DISABLED, d.mode)
        // 原因现在是**待解析文案**（资源 ID）而不是字符串：界面切英文后这条 Toast 也得跟着变。
        // 钉资源 ID，不钉中文字面量 —— 后者每抽取一次就假红一次（i18n 规范 §九的踩坑表）。
        assertEquals(R.string.state_fork_disabled, d.reason?.res)
    }

    /** `allow_forking=false`（REST 字段）也要能拦住对话框。 */
    @Test
    fun rest_allow_forking_false_disables_the_button() {
        val info = repoFixture(allowForking = false)
        val d = RepoRelationRules.forkDecision(null, info, "some-org", "octocat")
        assertEquals(ForkMode.DISABLED, d.mode)
    }

    /**
     * 关系态还没到时，别人的仓库**默认开放**对话框；自持仓库则已经能判成列表 ——
     * 顺序反了就会出现「先弹别人的复刻框、再自己关掉」的闪烁。
     */
    @Test
    fun unknown_relation_defaults_to_dialog_for_foreign_repos() {
        assertEquals(ForkMode.DIALOG, RepoRelationRules.forkDecision(null, null, "torvalds", "octocat").mode)
    }

    // ── 星标双向态 ──

    @Test
    fun star_toggle_is_bidirectional() {
        assertTrue(RepoRelationRules.starredAfterToggle(false))
        assertFalse(RepoRelationRules.starredAfterToggle(true))
        assertEquals(1L, RepoRelationRules.starDelta(true))
        assertEquals(-1L, RepoRelationRules.starDelta(false))
    }

    // ── Watch 面板 ──

    @Test
    fun watch_level_ids_match_the_web_subscription_types() {
        assertEquals(WatchLevel.PARTICIPATING, WatchLevel.fromId("none"))
        assertEquals(WatchLevel.ALL_ACTIVITY, WatchLevel.fromId("watching"))
        assertEquals(WatchLevel.IGNORE, WatchLevel.fromId("ignoring"))
        assertEquals(WatchLevel.CUSTOM, WatchLevel.fromId("custom"))
        // 未知值不能把用户带到「忽略」这种危险档位
        assertEquals(WatchLevel.PARTICIPATING, WatchLevel.fromId("whatever"))
        assertEquals(WatchLevel.PARTICIPATING, WatchLevel.fromId(null))
    }

    @Test
    fun web_form_fields_mirror_the_website() {
        fun fields(level: WatchLevel, threads: List<String> = emptyList()) =
            RepoRelationRules.webSubscribeFields("1296269", level, threads)

        assertEquals(
            listOf("repository_id" to "1296269", "do" to "ignore", "thread_types[]" to ""),
            fields(WatchLevel.IGNORE),
        )
        assertEquals(
            listOf("repository_id" to "1296269", "do" to "subscribed", "thread_types[]" to ""),
            fields(WatchLevel.ALL_ACTIVITY),
        )
        // 「参与 + @提及」在网页上的取值是 included，不是 none
        assertEquals(
            listOf("repository_id" to "1296269", "do" to "included", "thread_types[]" to ""),
            fields(WatchLevel.PARTICIPATING),
        )
        assertEquals(
            listOf(
                "repository_id" to "1296269",
                "do" to "custom",
                "thread_types[]" to "Issue",
                "thread_types[]" to "Release",
            ),
            fields(WatchLevel.CUSTOM, listOf("Issue", "Release")),
        )
        // Custom 且一个都没勾 = 网页版自己的退化行为
        assertEquals(
            listOf("repository_id" to "1296269", "do" to "included", "thread_types[]" to ""),
            fields(WatchLevel.CUSTOM, emptyList()),
        )
    }

    @Test
    fun rest_subscription_body_never_expresses_custom() {
        assertEquals("""{"subscribed":true,"ignored":false}""", RepoRelationRules.restSubscriptionBody(WatchLevel.ALL_ACTIVITY))
        assertEquals("""{"subscribed":false,"ignored":true}""", RepoRelationRules.restSubscriptionBody(WatchLevel.IGNORE))
        // Custom 走 REST 会退化成默认档 —— 调用方必须拦住（见 RepoActions.setWatch）
        assertEquals("""{"subscribed":false,"ignored":false}""", RepoRelationRules.restSubscriptionBody(WatchLevel.CUSTOM))
    }

    // ── 关系态缓存 ──

    /** 缓存往返必须无损：漏一个字段就会让「已星标」在下次进入时变回「星标」。 */
    @Test
    fun relation_survives_a_cache_round_trip() {
        val original = RepoViewerRelation.fromWeb(JSONObject(webJson))!!
        val restored = RepoViewerRelation.fromCache(original.toJson())!!
        assertEquals(original.starred, restored.starred)
        assertEquals(original.canStar, restored.canStar)
        assertEquals(original.canFork, restored.canFork)
        assertEquals(original.forkError, restored.forkError)
        assertEquals(original.canWatch, restored.canWatch)
        assertEquals(original.subscription, restored.subscription)
        assertEquals(original.watchersCount, restored.watchersCount)
        assertEquals(original.repositoryId, restored.repositoryId)
        assertEquals(original.threadTypes, restored.threadTypes)
        assertEquals(RepoViewerRelation.Source.WEB, restored.source)
    }

    /** 「未知」与 false 不能互相污染：缓存里没有 canFork 时读出来必须是 null。 */
    @Test
    fun cache_keeps_unknown_fields_unknown() {
        val restored = RepoViewerRelation.fromCache(RepoViewerRelation(starred = true).toJson())!!
        assertTrue(restored.starred)
        assertNull(restored.canFork)
        assertNull(restored.subscription)
        assertTrue(restored.threadTypes.isEmpty())
    }

    @Test
    fun broken_cache_returns_null() {
        assertNull(RepoViewerRelation.fromCache("not json"))
        assertNull(RepoViewerRelation.fromCache("{}"))
    }

    // ── 列表失败文案 ──

    @Test
    fun list_error_403_explains_the_new_restriction() {
        val text = peopleListErrorText("star", "ERROR:HTTP 403: {\"message\":\"Forbidden\"}")
        assertTrue(text.contains("限制"))
        assertTrue(text.contains("星标者"))
    }

    @Test
    fun list_error_distinguishes_missing_and_rate_limit() {
        assertTrue(peopleListErrorText("watch", "ERROR:HTTP 404: Not Found").contains("不存在"))
        assertTrue(peopleListErrorText("star", "ERROR:HTTP 429: rate limit").contains("限流"))
        assertTrue(peopleListErrorText("fork", "ERROR:网络不可达").contains("复刻列表"))
    }

    // ── 仓库信息解析：复刻判定所需的三个字段 ──

    @Test
    fun repo_info_keeps_fork_capability_fields() {
        val info = parseRepoInfo(
            """{"full_name":"o/r","name":"r","stargazers_count":3,"forks_count":1,
                "subscribers_count":2,"default_branch":"main","owner":{"login":"o"},
                "allow_forking":false,"fork":true,"parent":{"full_name":"up/r"}}""",
        )!!
        assertFalse(info.allowForking)
        assertTrue(info.isFork)
        assertEquals("up/r", info.parentFullName)
    }

    /** 老缓存里没有这几个字段：不能把「字段缺失」误判成「禁止复刻」。 */
    @Test
    fun repo_info_defaults_to_forking_allowed_when_field_missing() {
        val info = parseRepoInfo("""{"full_name":"o/r","name":"r","owner":{"login":"o"}}""")!!
        assertTrue(info.allowForking)
        assertFalse(info.isFork)
        assertNull(info.parentFullName)
    }

    private fun repoFixture(allowForking: Boolean) = RepoInfo(
        fullName = "some-org/r",
        name = "r",
        description = "",
        stars = 0,
        forks = 0,
        watchers = 0,
        license = null,
        defaultBranch = "main",
        ownerLogin = "some-org",
        allowForking = allowForking,
    )
}
