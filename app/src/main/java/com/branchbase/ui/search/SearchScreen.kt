package com.branchbase.ui.search

import android.content.Context
import androidx.annotation.StringRes
import android.content.Intent
import android.net.Uri
import com.branchbase.R
import com.branchbase.ui.repository.RepoDeepLink
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import com.branchbase.ui.navigation.BranchbaseLazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Commit
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.branchbase.ui.CardDrawer
import com.branchbase.ui.CardElevation
import com.branchbase.ui.CardSegments
import com.branchbase.ui.FloatCardRowHeight
import com.branchbase.ui.FloatCardShape
import com.branchbase.ui.FloatingCardButton
import com.branchbase.ui.LocalizedText
import com.branchbase.ui.TintChip
import com.branchbase.ui.cardSurface
import com.branchbase.ui.repository.RepoRelation
import com.branchbase.ui.repository.repoRelationOf
import com.branchbase.ui.resolve
import com.branchbase.ui.theme.ElementMotion
import com.branchbase.ui.theme.ProvideShimmer
import com.branchbase.ui.theme.skeletonBlock
import com.branchbase.ui.theme.morph.AnimatedMorphIcon
import com.branchbase.ui.theme.morph.MorphIcons
import com.branchbase.ui.theme.rememberPressFeedback
import com.branchbase.cache.SearchCacheDatabase
import com.branchbase.cache.SearchCacheManager
import com.branchbase.core.RustBridge
import com.branchbase.ui.log.Logger
import com.branchbase.ui.theme.iconTap
import com.branchbase.ui.theme.CodeSyntax
import com.branchbase.ui.theme.Primer
import com.branchbase.ui.theme.TintRole
import com.branchbase.ui.theme.color
import com.branchbase.ui.theme.textColor
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 搜索界面（参考 GitHub 网页版三层结构）。
 *
 * 搜索框 / 工具栏（类型下拉+排序下拉+过滤）/ 结果列表 / 过滤面板。
 */
@Composable
fun SearchScreen(
    sessionJson: String,
    onBack: () -> Unit,
    /**
     * 站内跳转（仓库 / issue / PR / 提交 / 文件）。
     *
     * 走的与「通知深链接」同一条路由（`RepoDeepLink` → `MainScreen` 的仓库路由），
     * 因此落点与返回栈行为完全一致，搜索结果页不需要自己拼导航。
     */
    onOpenInApp: (RepoDeepLink) -> Unit,
) {
    LaunchedEffect(Unit) { Logger.ui("进入搜索页", "Compose") }
    val token = runCatching { JSONObject(sessionJson).getJSONObject("token").optString("access_token") }.getOrNull() ?: ""
    val host = runCatching { JSONObject(sessionJson).optString("host", "github.com") }.getOrDefault("github.com")

    val context = LocalContext.current
    val cacheManager = remember {
        val db = SearchCacheDatabase.getInstance(context)
        SearchCacheManager(db.searchCacheDao())
    }

    val scope = rememberCoroutineScope()

    /**
     * 搜索词 / 类型 / 排序 / 筛选存在 ViewModel 里（不是 `remember`）：
     * 点进某个结果再返回时，搜索页会被销毁重建，放 `remember` 会全部归零。
     */
    // 选项记忆按账号落盘：结果受账号权限影响，检索条件也不该跟着账号串（同 searchCacheKey 的理由）
    val login = remember(sessionJson, context) {
        runCatching { JSONObject(sessionJson).getJSONObject("user").optString("login") }
            .getOrNull()?.takeIf { it.isNotBlank() && it != "null" }
            ?: com.branchbase.core.AccountStore.currentLogin(context)
    }
    val optionStore = remember(login) { SearchOptionStore(context, login) }
    // 工厂只在**首次创建**时读记忆：首帧就是上次的矩阵，不闪一帧默认值
    val vm: SearchViewModel = viewModel(
        factory = remember(optionStore) { SearchViewModel.factory(optionStore.load()) },
    )
    var query by vm.queryState
    var selectedLanguage by vm.languageState
    var advancedValues by vm.advancedState
    var type by vm.typeState
    var sortKey by vm.sortKeyState
    var searched by vm.searchedState

    // 纯界面态（抽屉是否展开）留在这里即可；抽屉里的分段选中/展开行是瞬时态，跟着抽屉生灭
    var drawerOpen by remember { mutableStateOf(false) }

    // 结果本身不进 ViewModel：返回时按缓存直出（命中即瞬时），避免在内存里留一份大对象
    var results by remember { mutableStateOf<List<SearchItem>>(emptyList()) }
    var codeResults by remember { mutableStateOf<List<CodeResult>>(emptyList()) }
    var pullResults by remember { mutableStateOf<List<PullResult>>(emptyList()) }
    var commitResults by remember { mutableStateOf<List<CommitResult>>(emptyList()) }
    var topicResults by remember { mutableStateOf<List<TopicResult>>(emptyList()) }
    var total by remember { mutableStateOf(0L) }
    // 分页：已拿到的页码 + 是否正在加载下一页（与首屏 loading 分开，底部只转小圈）
    var page by remember { mutableStateOf(1) }
    var loadingMore by remember { mutableStateOf(false) }
    // 返回页面时会自动重搜，先亮加载态，避免闪一帧「未找到结果」
    var loading by remember { mutableStateOf(vm.hasPendingSession()) }
    var searchError by remember { mutableStateOf<String?>(null) }

    /** 把当前四维矩阵写回记忆：抽屉里每改一项就落一次，进程随时被杀也不会丢。 */
    fun persistOptions() {
        optionStore.save(SearchOptions(type, sortKey, selectedLanguage, advancedValues))
    }

    /**
     * 发起一次搜索。
     *
     * [forceRefresh] = true 表示**用户显式按了搜索**（放大镜 / 键盘回车）：跳过缓存直接回源。
     * 缓存是给「返回页面 / 切回前台时直出」用的；显式动作若也吃缓存，用户只会看到界面原地不动，
     * 读起来就是「点了没反应」。抽屉关掉时的重搜走缓存（矩阵没变时没必要再打一次 GitHub）。
     */
    fun doSearch(forceRefresh: Boolean = false) {
        // ① 请求发起时把条件**快照**下来：协程恢复时用户可能已经改了类型/排序，
        //    那时再读 type/results 会把 A 类型的结果按 B 类型解析、写进 B 的状态桶
        val query0 = query
        if (query0.isBlank()) return
        val type0 = type
        val sortKey0 = sortKey
        val q = buildSearchQuery(query0, type0, selectedLanguage, advancedValues, advancedFilters)
        // 键必须含语言 / 高级筛选：用原文当键的话，只改这两维会命中同一条旧缓存，
        // 看起来就是「点完成没反应」
        val cacheKey = searchCacheKey(type0, query0, selectedLanguage, advancedValues, advancedFilters, sortKey0, login)

        // ② 统一解析并写入对应类型状态（按快照里的类型）
        fun parseAndSet(json: String) {
            when (type0) {
                SearchType.CODE -> { val p = parseCodeResults(json); codeResults = p.first; total = p.second }
                SearchType.PULLS -> { val p = parsePullResults(json); pullResults = p.first; total = p.second }
                SearchType.COMMITS -> { val p = parseCommitResults(json); commitResults = p.first; total = p.second }
                SearchType.TOPICS -> { val p = parseTopicResults(json); topicResults = p.first; total = p.second }
                else -> { val p = parseResults(json, type0, me = login); results = p.first; total = p.second }
            }
        }

        // ③ 仓库搜索：预加载前几个结果的详情（点进去直接命中缓存；计费网络自动跳过）
        fun warmRepos(found: List<SearchItem>) {
            if (type0 != SearchType.REPOS) return
            com.branchbase.cache.RepoPrefetcher.warmList(
                context = context,
                host = host,
                token = token,
                // 仓库结果的 title 就是 full_name（见 parseResults）
                entries = found.map {
                    it.title.substringBefore('/') to it.title.substringAfter('/', "")
                },
            )
        }

        // ④ 请求序号：快速连点 / 改条件后再搜时，旧响应后到就丢弃（否则会覆盖新结果、提前收掉转圈）
        val token0 = vm.nextSeq()
        page = 1
        loading = true
        searchError = null
        scope.launch {
            // 命中缓存：读本地数据库（显式搜索不回源缓存，见 doSearch 的 forceRefresh）
            val cached = if (forceRefresh) null else cacheManager.get(cacheKey, type0.cacheKey)
            if (cached != null) {
                if (!vm.isCurrent(token0)) return@launch
                parseAndSet(cached)
                searched = true
                loading = false
                warmRepos(results)
                return@launch
            }

            // 未命中：拉远端并写缓存
            val json = when (type0) {
                SearchType.CODE -> RustBridge.searchCode(host, token, q)
                SearchType.REPOS -> RustBridge.searchRepositories(host, token, q, sortKey)
                SearchType.USERS -> RustBridge.searchUsers(host, token, q)
                SearchType.ISSUES, SearchType.PULLS -> RustBridge.searchIssues(host, token, q)
                SearchType.COMMITS -> RustBridge.searchCommits(host, token, q)
                SearchType.TOPICS -> RustBridge.searchTopics(host, token, q)
            }
            // 回来时若不是最新一次搜索：整份结果丢弃（连 loading 都不要动，那是新请求的）
            if (!vm.isCurrent(token0)) return@launch

            Logger.net("搜索 ${type0.cacheKey}: $q → ${if (json != null && !json.startsWith("ERROR:")) "200" else "失败"}", "search")
            if (json != null && !json.startsWith("ERROR:")) {
                // 先立即用拉取结果填充展示，再异步写入缓存（更新缓存与展示同源、同步发生）
                parseAndSet(json)
                page = 1
                cacheManager.put(cacheKey, type0.cacheKey, json)
                searched = true
                warmRepos(results)
            } else {
                // 搜索失败（网络错误 / 速率限制 / 权限不足等），区别于「无结果」
                searched = true
                searchError = friendlySearchError(json).resolve(context)
            }
            loading = false
        }
    }

    // 从结果进详情再返回：搜索词与条件还在（ViewModel），这里按缓存直出结果
    LaunchedEffect(Unit) {
        if (vm.hasPendingSession()) doSearch()
    }

    /** 当前类型的已展示条数（分页推进与「已到底」判断共用）。 */
    fun shownCount(): Int = when (type) {
        SearchType.CODE -> codeResults.size
        SearchType.PULLS -> pullResults.size
        SearchType.COMMITS -> commitResults.size
        SearchType.TOPICS -> topicResults.size
        else -> results.size
    }

    /**
     * 解析并把新一页**追加**到对应列表（按 key 去重）。返回本页新增条数。
     *
     * 去重是必须的：GitHub 分页在数据变动时会出现同一项跨页重复（按 stars/updated 排序时尤其常见）。
     */
    fun appendPage(json: String, type0: SearchType): Int = when (type0) {
        SearchType.CODE -> {
            val p = parseCodeResults(json)
            val before = codeResults.size
            codeResults = mergePage(codeResults, p.first) { "${it.owner}/${it.repository}/${it.path}" }
            total = maxOf(total, p.second)
            codeResults.size - before
        }
        SearchType.PULLS -> {
            val p = parsePullResults(json)
            val before = pullResults.size
            pullResults = mergePage(pullResults, p.first) { "${it.repository}#${it.number}" }
            total = maxOf(total, p.second)
            pullResults.size - before
        }
        SearchType.COMMITS -> {
            val p = parseCommitResults(json)
            val before = commitResults.size
            commitResults = mergePage(commitResults, p.first) { it.sha }
            total = maxOf(total, p.second)
            commitResults.size - before
        }
        SearchType.TOPICS -> {
            val p = parseTopicResults(json)
            val before = topicResults.size
            topicResults = mergePage(topicResults, p.first) { it.name }
            total = maxOf(total, p.second)
            topicResults.size - before
        }
        else -> {
            val p = parseResults(json, type0, me = login)
            val before = results.size
            // 用定位信息当 key（标题会误伤不同仓库里的同名 issue，见 searchItemKey 说明）
            results = mergePage(results, p.first) { searchItemKey(it.target, it.title) }
            total = maxOf(total, p.second)
            results.size - before
        }
    }

    /**
     * 加载下一页（追加到现有列表）。
     *
     * ## 为什么不自动触发
     *
     * 搜索类接口的限额很紧（代码搜索约 10 次/分钟），滚到底自动翻页会让用户
     * 「只是滑两下」就撞上限流。这里做成**显式按钮**，并在按钮上写清「已显示 N / 共 M」，
     * 用户自己决定要不要继续 —— 与被限流时的提示（第 ⑤ 条）配套。
     *
     * ## 与首屏请求的关系
     *
     * 共用同一份条件快照与同一个请求序号：加载途中用户改了条件并重新搜索时，
     * 这一页的结果会因为序号过期而丢弃，不会把旧查询的第 2 页追加到新结果里。
     */
    fun loadMore() {
        if (loadingMore || loading) return
        val query0 = query
        val type0 = type
        val sortKey0 = sortKey
        if (query0.isBlank()) return
        val q = buildSearchQuery(query0, type0, selectedLanguage, advancedValues, advancedFilters)
        val next = nextPage(page, shownCount())
        val token0 = vm.nextSeq()

        loadingMore = true
        scope.launch {
            val json = when (type0) {
                SearchType.CODE -> RustBridge.searchCode(host, token, q, next)
                SearchType.REPOS -> RustBridge.searchRepositories(host, token, q, sortKey0, next)
                SearchType.USERS -> RustBridge.searchUsers(host, token, q, next)
                SearchType.ISSUES, SearchType.PULLS -> RustBridge.searchIssues(host, token, q, next)
                SearchType.COMMITS -> RustBridge.searchCommits(host, token, q, next)
                SearchType.TOPICS -> RustBridge.searchTopics(host, token, q, next)
                else -> null
            }
            loadingMore = false
            // 序号过期（期间有更新的搜索）：整份丢弃
            if (!vm.isCurrent(token0)) return@launch
            if (json == null || json.startsWith("ERROR:")) {
                searchError = friendlySearchError(json).resolve(context)
                // 翻到 GitHub 的 1000 条上限：把总量收敛到已展示数，底部按钮随之收掉，
                // 不然用户会对着一个永远失败的按钮反复点。
                if (searchResultCapReached(json)) total = shownCount().toLong()
                return@launch
            }
            val received = appendPage(json, type0)
            page = next
            // 服务端返回的 total 比总量小 / 本页为空 → 视为到底（避免按钮一直转）
            if (received <= 0) total = shownCount().toLong()
            Logger.net("搜索 ${type0.cacheKey} 第 $next 页：+$received 条", "search")
        }
    }

    /** 结果列表底部：加载更多 / 加载中 / 已到底（五种结果类型共用一份页脚）。 */
    fun LazyListScope.pagingFooter(shown: Int) {
        item(key = "paging-footer") {
            SearchPagingFooter(
                shown = shown,
                total = total,
                loadingMore = loadingMore,
                onLoadMore = { loadMore() },
            )
        }
    }

    /**
     * 结果点击派发：**站内有页面的走站内**（仓库 / issue / PR / 提交 / 文件），
     * 站内没有页面的（用户主页 / 主题页）交给浏览器。
     */
    fun openTarget(target: SearchTarget?) {
        when (target) {
            null -> Unit
            is SearchTarget.Web -> runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target.url)))
            }
            else -> target.toRepoDeepLink()?.let(onOpenInApp)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Primer.BackgroundPrimary)
            .statusBarsPadding(),
    ) {
        // 搜索框 + 选项入口：两枚同为 32dp 的浮层卡（与「仓库」Tab 的搜索/筛选一排同密度），
        // 三个语义图标分别落在「搜索（蓝）/ 筛选（紫）/ 清除（中性）」上
        SearchHeader(
            query = query,
            onQueryChange = { query = it },
            // 显式搜索（放大镜 / 回车）跳过缓存：用户按了就要一次真实请求，而不是原地不动
            onSearch = { doSearch(forceRefresh = true) },
            onBack = onBack,
            // 徽标只在**搜过一次之后**才跟着关键词出现：没搜过的选项只是草稿，不该先占位置
            badges = if (searched) {
                optionBadges(context, type, sortKey, selectedLanguage, advancedValues)
            } else {
                emptyList()
            },
            optionsActive = sortKey.isNotBlank() || selectedLanguage != null ||
                advancedValues.any { it.value.isNotBlank() },
            onOpenOptions = { drawerOpen = true },
        )

        // 结果
        if (loading) {
            SearchSkeleton()
        } else if (searchError != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(searchError!!, color = Primer.Red500)
            }
        } else if (!searched) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.hint_type_keyword), color = Primer.TextTertiary)
            }
        } else if (type == SearchType.CODE) {
            if (codeResults.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.state_no_code_results), color = Primer.TextTertiary)
                }
            } else {
                Column {
                    ResultCountBar(codeResults.size, total, R.string.label_code_results, searchTypeRole(type))
                    BranchbaseLazyColumn {
                        items(codeResults) { code -> CodeResultCard(code) { openTarget(code.target) } }
                        pagingFooter(codeResults.size)
                    }
                }
            }
        } else if (type == SearchType.PULLS) {
            if (pullResults.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.state_no_pull_results), color = Primer.TextTertiary)
                }
            } else {
                Column {
                    ResultCountBar(pullResults.size, total, R.string.label_pull_results, searchTypeRole(type))
                    BranchbaseLazyColumn {
                        items(pullResults) { pull -> PullCard(pull) { openTarget(pull.target) } }
                        pagingFooter(pullResults.size)
                    }
                }
            }
        } else if (type == SearchType.COMMITS) {
            if (commitResults.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.state_no_commit_results), color = Primer.TextTertiary)
                }
            } else {
                Column {
                    ResultCountBar(commitResults.size, total, R.string.label_commit_results, searchTypeRole(type))
                    BranchbaseLazyColumn {
                        items(commitResults) { commit -> CommitCard(commit) { openTarget(commit.target) } }
                        pagingFooter(commitResults.size)
                    }
                }
            }
        } else if (type == SearchType.TOPICS) {
            if (topicResults.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.state_no_topic_results), color = Primer.TextTertiary)
                }
            } else {
                Column {
                    ResultCountBar(topicResults.size, total, R.string.label_topic_results, searchTypeRole(type))
                    BranchbaseLazyColumn {
                        item { TopicCard(topicResults) { openTarget(it.target) } }
                        pagingFooter(topicResults.size)
                    }
                }
            }
        } else if (results.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.state_no_results), color = Primer.TextTertiary)
            }
        } else {
            Column {
                ResultCountBar(results.size, total, R.string.label_results, searchTypeRole(type))
                BranchbaseLazyColumn {
                    items(results) { item -> SearchItemCard(item, type) { openTarget(item.target) } }
                    pagingFooter(results.size)
                }
            }
        }
    }

    if (drawerOpen) {
        SearchOptionsSheet(
            type = type,
            onTypeChange = { type = it; persistOptions() },
            sortKey = sortKey,
            onSortChange = { sortKey = it; persistOptions() },
            language = selectedLanguage,
            onLanguageChange = { selectedLanguage = it; persistOptions() },
            advanced = advancedValues,
            onAdvancedChange = { name, value ->
                // 空值 = 这条高级筛选没用上，直接删键；留着空串会让徽标计数虚高
                advancedValues = if (value.isBlank()) advancedValues - name else advancedValues + (name to value)
                persistOptions()
            },
            onClearAdvanced = { advancedValues = emptyMap(); persistOptions() },
            onDismiss = {
                drawerOpen = false
                persistOptions()
                // 矩阵变了就按新条件重搜（缓存命中时是瞬时的）；没搜过就等用户自己按搜索
                if (searched && query.isNotBlank()) doSearch()
            },
        )
    }
}

/**
 * 顶部一行：返回箭头 + 搜索卡 + 选项入口卡。
 *
 * 与「仓库」Tab 的头部（搜索卡 + 筛选入口）同构：两枚 32dp 的浮层小卡并排，
 * 左缘各有一枚 `TintChip`——裸图标夹在卡里会显得这张卡少了点什么。
 *
 * 三枚语义图标的落点：搜索（[TintRole.ACCENT] 蓝）、选项入口（[TintRole.DONE] 紫）、
 * 清除（[TintRole.NEUTRAL] 中性灰）。它们把「查找 / 收窄 / 退回去」三件事分开，
 * 不靠位置让用户猜。
 */
@Composable
private fun SearchHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onBack: () -> Unit,
    badges: List<SearchBadge>,
    optionsActive: Boolean,
    onOpenOptions: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.action_back),
            tint = Primer.IconPrimary,
            modifier = Modifier.size(24.dp).iconTap { onBack() },
        )
        SearchCard(
            query = query,
            onQueryChange = onQueryChange,
            onSearch = onSearch,
            badges = badges,
            modifier = Modifier.weight(1f),
        )
        OptionsEntry(active = optionsActive, onClick = onOpenOptions)
    }
}

/**
 * 搜索卡：**浮层卡 + 语义搜索图标牌 + 真输入框 + 选项徽标**。
 *
 * 徽标跟在用户输入的原文**后面**：只显示这套矩阵里真正设了值的维度，没设的不占位置
 * （「仓库」目标永远有值，所以搜过一次之后至少有一枚；排序 / 语言 / 高级筛选按需出现）。
 * 颜色按维度分（目标蓝 / 倾向紫 / 语言绿 / 高级橙），底色都取该色的 12% —— 与 [TintChip]
 * 同一条规则：底色永远跟着语义色走，不另配一套不透明的浅色。
 *
 * 徽标**与输入内容同一行**、钉在右侧图标（清除 / 搜索）前：卡高恒为 32dp，搜过一次也不会把
 * 头部撑高一层。徽标条最多占卡宽的一半、超出就横滑；某一维被清掉时它用
 * spring（ζ=0.72 / stiffness=420）收缩，剩下的徽标跟着往右滑到图标旁边 ——
 * 不在「最后一名徽标 ↔ 放大镜」之间留一条空带。关键词本身单行内滚动，同样不换行、不撑高。
 */
@Composable
private fun SearchCard(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    badges: List<SearchBadge>,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    // 卡高恒为 32dp：关键词单行内滚动，徽标条最多占卡宽一半（超出自己横滑）。
    BoxWithConstraints(
        modifier
            .cardSurface(shape = FloatCardShape, elevation = CardElevation)
            .padding(horizontal = 9.dp),
    ) {
        val badgeMaxWidth = maxWidth * 0.5f
        Row(Modifier.height(FloatCardRowHeight), verticalAlignment = Alignment.CenterVertically) {
            TintChip(Icons.Filled.Search, role = TintRole.ACCENT, size = 20.dp, corner = 6.dp, iconSize = 12.dp)
            Spacer(Modifier.width(7.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 13.sp, color = Primer.TextPrimary),
                cursorBrush = SolidColor(Primer.Blue500),
                // 与「仓库」Tab 一致：本地没有「提交」概念，回车只收键盘
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    onSearch()
                }),
                // 输入框吃满剩余宽度：它本身就是「点一下进输入态」的落点，
                // 徽标因此被顶到右侧图标旁边 —— 徽标不在位时不会在中间留下一条空白带。
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                stringResource(R.string.hint_search_or_jump),
                                fontSize = 13.sp,
                                color = Primer.TextTertiary,
                                maxLines = 1,
                            )
                        }
                        inner()
                    }
                },
            )
            BadgeStrip(badges = badges, maxWidth = badgeMaxWidth)
            if (query.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.action_clear_search),
                    tint = Primer.IconSecondary,
                    modifier = Modifier.size(15.dp).iconTap { onQueryChange("") },
                )
            }
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Filled.Search,
                contentDescription = stringResource(R.string.action_search),
                tint = Primer.Blue500,
                modifier = Modifier.size(18.dp).iconTap { onSearch() },
            )
        }
    }
}

/** 选项入口卡：图标牌用 [TintRole.DONE] 紫，与「挑选 / 归纳」那族同色。 */
@Composable
private fun OptionsEntry(active: Boolean, onClick: () -> Unit) {
    FloatingCardButton(
        onClick = onClick,
        shape = FloatCardShape,
        elevation = CardElevation,
        modifier = Modifier.height(FloatCardRowHeight),
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TintChip(Icons.Filled.FilterList, role = TintRole.DONE, size = 20.dp, corner = 6.dp, iconSize = 12.dp)
            Spacer(Modifier.width(5.dp))
            Text(
                stringResource(R.string.label_search_options),
                fontSize = 12.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                color = if (active) Primer.Blue500 else Primer.TextSecondary,
                maxLines = 1,
            )
        }
    }
}

/** 记忆里的一条维度值：徽标文案 + 语义色角色（底色 = 角色色 × 12%）。 */
private data class SearchBadge(val label: String, val role: TintRole)

/**
 * 把四维矩阵折成徽标：**只列真正生效的维度**。
 *
 * - 目标永远有值（不存在「不指定类型」的搜索），所以它总在；
 * - 倾向只在仓库搜索里生效（GitHub 只给仓库接口排序），别的类型不显示——留着会骗人；
 * - 语言、高级筛选按有没有值出现，高级筛选合并成一枚「高级 N」而不是把十来个键都铺开。
 */
private fun optionBadges(
    context: Context,
    type: SearchType,
    sortKey: String,
    language: String?,
    advanced: Map<String, String>,
): List<SearchBadge> = buildList {
    add(SearchBadge(context.getString(type.labelRes), TintRole.ACCENT))
    if (type == SearchType.REPOS && sortKey.isNotBlank()) {
        add(SearchBadge(context.getString(sortLabelRes(sortKey)), TintRole.DONE))
    }
    language?.takeIf { it.isNotBlank() }?.let { add(SearchBadge(it, TintRole.SUCCESS)) }
    val advancedCount = advanced.count { it.value.isNotBlank() }
    if (advancedCount > 0) {
        add(SearchBadge(context.getString(R.string.search_badge_advanced, advancedCount), TintRole.WARNING))
    }
}

/**
 * 徽标条：**每个语义维度一个固定槽位**（目标 / 倾向 / 语言 / 高级），空槽宽度为 0。
 *
 * 为什么不用「遍历当前 badges 列表」：列表一删，组合树当帧就被摘掉，`AnimatedVisibility`
 * 根本没机会播退场。槽位常驻、只切 `visible`，正在消失的那一枚才能缩完再让位。
 * 槽位按 [TintRole] 定位（一个维度最多一枚徽标），语义色顺序即显示顺序。
 *
 * 槽位自带前导 4dp：间距跟着卡片一起收窄/展开，不会在「上一枚 ↔ 下一枚」之间留下固定空隙。
 * 条子本身最多占卡宽一半、超出横滑（[maxWidth] 由卡片宽度算出来）。
 */
@Composable
private fun BadgeStrip(badges: List<SearchBadge>, maxWidth: Dp) {
    val byRole = badges.associateBy { it.role }
    Row(
        Modifier
            .widthIn(max = maxWidth)
            // 标签多了就横滑：关键词与标签始终在同一行，卡不跟着长高
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BadgeSlot(byRole[TintRole.ACCENT])
        BadgeSlot(byRole[TintRole.DONE])
        BadgeSlot(byRole[TintRole.SUCCESS])
        BadgeSlot(byRole[TintRole.WARNING])
    }
}

/**
 * 单个维度槽位：展开时 `expandHorizontally` + `fadeIn`，收起时 `shrinkHorizontally` + `fadeOut`，
 * 尺寸与抽屉分段同一套弹簧（ζ=0.72 / stiffness=420）。
 *
 * 退场时调用方传进来的 [badge] 已经是 `null`，而 `AnimatedVisibility` 的出口内容还在组合里 ——
 * 用普通字段（**不是** Snapshot 状态）留住最后一次非空值：写它不会触发重组，
 * 动画首帧也不会因为「组合期写状态」多抖一轮；正在收窄的那一枚仍有内容可画。
 *
 * 从右侧锚定（`expandFrom` / `shrinkTowards` = [Alignment.End]）：整条徽标条靠右钉在
 * 图标前，收起时应当朝右边收，剩下的徽标同时被输入框的扩张推向右。
 */
@Composable
private fun BadgeSlot(badge: SearchBadge?) {
    val retained = remember { BadgeRetainer() }
    if (badge != null) retained.value = badge
    AnimatedVisibility(
        visible = badge != null,
        enter = fadeIn(tween(ElementMotion.PANEL_FADE_IN_MS)) + expandHorizontally(
            animationSpec = spring(dampingRatio = 0.72f, stiffness = 420f),
            expandFrom = Alignment.End,
        ),
        exit = fadeOut(tween(ElementMotion.PANEL_FADE_IN_MS)) + shrinkHorizontally(
            animationSpec = spring(dampingRatio = 0.72f, stiffness = 420f),
            shrinkTowards = Alignment.End,
        ),
    ) {
        val shown = retained.value ?: return@AnimatedVisibility
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(4.dp))
            OptionBadge(shown.label, shown.role)
        }
    }
}

/** [BadgeSlot] 的「最后一次非空值」盒子：故意不是 Snapshot 状态，见其注释。 */
private class BadgeRetainer {
    var value: SearchBadge? = null
}

/** 一枚选项徽标：语义色文字压在同色 12% 的圆角底上（与 [TintChip] 的底色规则同一个常量思路）。 */
@Composable
private fun OptionBadge(label: String, role: TintRole) {
    Text(
        label,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        color = role.textColor(),
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(role.color().copy(alpha = 0.12f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/**
 * 每种搜索结果的语义色：左侧图标牌与顶部计数条**同源**，一屏只出现蓝 / 绿 / 紫 / 中性四档。
 *
 * 绿 / 紫 / 中性不是随手挑的，而是沿用通知列表里同一类对象早就定下的角色
 * （`NotificationModels` 的 `TypeMeta`）：issue = `SUCCESS` 绿、PR = `DONE` 紫、
 * commit = `NEUTRAL` 中性；仓库 / 代码 / 用户 / 主题属于「去哪」的主干，统一 `ACCENT` 蓝。
 * 同一个 issue 在通知页与搜索页因此是同一个颜色，两处不会各说各的。
 */
internal fun searchTypeRole(type: SearchType): TintRole = when (type) {
    SearchType.REPOS, SearchType.CODE, SearchType.USERS -> TintRole.ACCENT
    SearchType.ISSUES -> TintRole.SUCCESS
    SearchType.PULLS -> TintRole.DONE
    SearchType.COMMITS -> TintRole.NEUTRAL
    SearchType.TOPICS -> TintRole.ACCENT
}

/**
 * 结果计数条（决策一）：**3dp 极细横条 + 语义色底**，替掉原来那行 12sp 灰字。
 *
 * 原来那行字的位置很尴尬：12sp 灰字夹在 32dp 搜索卡与结果卡之间，既不是标题也不是说明，
 * 每换一种类型还要重排一次高度。现在收成一条与页面同宽的细线：底色是该类结果的语义色
 * （与左侧图标牌同源，见 [searchTypeRole]），透明度沿用 `TintChip` 那条「同色 12%」的规则 ——
 * 于是它既是分隔线、也一眼看出「现在这屏是仓库还是代码」，还省下一条结果的高度。
 *
 * 数字没有消失：读屏仍念原来的整句（文案照旧由 `resultCountText` 生成，不另写一套），
 * 需要看的时候页脚还有「加载更多（已显示 30 / 共 196）」。
 */
@Composable
private fun ResultCountBar(shown: Int, total: Long, unitRes: Int, role: TintRole) {
    val label = resultCountText(total, shown, unitRes).resolve()
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(role.color().copy(alpha = 0.12f))
            .semantics { contentDescription = label },
    )
}

/**
 * 列表左端的图标位：**有头像就放头像**（仓库 = 开发者头像、用户 = 本人头像），
 * 没有头像或其它类型才退回语义图标牌。
 *
 * 形状与尺寸跟着图标牌走（26dp / 7dp 圆角），不另起一套圆形头像语言 ——
 * 同一列里一行圆一行方，行高会看起来不齐。宽度固定，标题因此从同一条竖线起排。
 */
@Composable
private fun SearchLeadingIcon(avatarUrl: String?, icon: ImageVector, role: TintRole) {
    if (avatarUrl != null) {
        AsyncImage(
            model = avatarUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Primer.Gray150),
        )
    } else {
        TintChip(icon, role, size = 26.dp, corner = 7.dp, iconSize = 15.dp)
    }
}

/**
 * 「我的」徽章：搜索接口不返回 permissions，只能判定「是不是我自己的仓库」，
 * 所以搜索列表里只给这一种关系上标记（协作 / 只读要在仓库详情页才准）。
 */
@Composable
private fun MineBadge() {
    Text(
        stringResource(R.string.label_mine),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = Primer.SuccessText,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Primer.SuccessSurface)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** 搜索加载骨架屏（shimmer 呼吸） */
@Composable
private fun SearchSkeleton() {
    // 微光规格收在 ui/theme/Motion.kt（通知页骨架用同一份）。
    // ProvideShimmer 包在 repeat 外面：整屏一条动画；占位块用 skeletonBlock，
    // alpha 在绘制期读 —— 以前是每个占位块 `background(color.copy(alpha = alpha))`，
    // 等于把 15 个占位块都挂到动画的每一帧上，加载时每帧重组一遍。
    ProvideShimmer {
        Column {
            // 骨架与结果卡同构：26dp 图标位 + 两行（标题 / 说明），卡距与内边距也照抄，
            // 数据到达时是「同尺寸换内容」，不会整屏跳一下
            repeat(6) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 3.dp)
                        .cardSurface()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(26.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .skeletonBlock(),
                    )
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Box(
                            Modifier
                                .fillMaxWidth(0.5f)
                                .height(11.dp)
                                .skeletonBlock(),
                        )
                        Spacer(Modifier.height(5.dp))
                        Box(
                            Modifier
                                .fillMaxWidth(0.85f)
                                .height(9.dp)
                                .skeletonBlock(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 搜索结果行卡（仓库 / 用户 / issue 共用，决策二）：**浮层白面 + 语义图标牌 + 高密度两行**。
 *
 * 容器与首页仓库卡同一套（`cardSurface()`：白面 + 4dp 阴影、12dp 圆角），力度再压一档：
 * 卡距 3dp（相邻两卡共 6dp，与首页一致）、内边距 h12·v8、标题 14sp、说明限 1 行 ——
 * 同屏可见条数从 3 条上下变成 5 条上下，这才是「列表」该有的密度。
 *
 * 左端图标位：仓库 / 用户放头像（仓库 = 开发者 `owner.avatar_url`，用户 = `avatar_url`），
 * 其余类型走 `TintChip` 语义色（issue 绿 / commit 中性 …，见 [searchTypeRole]）；
 * 说明行右侧挂 meta（仓库的「语言 · N 星」、issue 的 open/closed），不再单独占一行。
 */
@Composable
private fun SearchItemCard(item: SearchItem, type: SearchType, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp)
            .cardSurface()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchLeadingIcon(item.avatarUrl, typeIcon(type), searchTypeRole(type))
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Primer.Blue500,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (item.relation == RepoRelation.OWN) {
                    Spacer(Modifier.width(6.dp))
                    MineBadge()
                }
            }
            val meta = item.meta?.resolve()
            if (item.subtitle.isNotBlank() || meta != null) {
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.subtitle.isNotBlank()) {
                        Text(
                            item.subtitle,
                            fontSize = 11.5.sp,
                            lineHeight = 15.sp,
                            color = Primer.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    if (meta != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(meta, fontSize = 11.sp, color = Primer.TextTertiary, maxLines = 1)
                    }
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        Text("›", fontSize = 15.sp, color = Primer.TextTertiary)
    }
}

/**
 * 代码搜索结果卡（决策三）：**浮层白面 + 两行头 + 贴底代码块**。
 *
 * 重新设计的三处：
 * 1. 卡面统一成 `cardSurface()`（原来是 8dp 圆角 + 1dp 描边、卡里再套一块描边代码区），
 *    与仓库 / 用户卡同一种「浮起来」的语言；
 * 2. 头部压成两行 —— 仓库行（头像 + `owner/repo` + ›）与文件行（文件类型图标 + 文件名 +
 *    语言 / 匹配数胶囊），并去掉原来「▾ / ▸」两个点了没反应的假箭头：这张卡不能折叠，
 *    箭头是空承诺，点下去只会跳到文件页；
 * 3. 代码块贴着卡底（不再另起一遍内边距），行号列 24dp、11.5sp / 15sp 行高；同 sha 的其它
 *    路径与截断提示收进同一块底色，不再一段换一次背景。
 */
@Composable
private fun CodeResultCard(code: CodeResult, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp)
            .cardSurface()
            .clickable(onClick = onClick),
    ) {
        // ① 仓库行：头像（无则首字母兜底）+ owner/repo + 右侧 ›
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 10.dp, top = 7.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (code.avatarUrl != null) {
                AsyncImage(
                    model = code.avatarUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(18.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Primer.Gray150),
                )
            } else {
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Primer.Gray150),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(code.owner.take(1).uppercase(), fontSize = 9.sp, color = Primer.IconPrimary)
                }
            }
            Spacer(Modifier.width(6.dp))
            Text(
                code.repository,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Primer.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(4.dp))
            Text("›", fontSize = 14.sp, color = Primer.TextTertiary)
        }
        // ② 文件行：文件类型图标 + 文件名 + 语言 / 匹配数胶囊
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                fileIcon(code.path),
                contentDescription = null,
                tint = Primer.IconSecondary,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                code.fileName,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Primer.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (code.language != null) {
                Spacer(Modifier.width(6.dp))
                LanguagePill(code.language)
            }
            Spacer(Modifier.width(6.dp))
            MatchCountPill(code.matchCount)
        }
        // ③ 代码块 + 同 sha 的其它路径：整段压在 CodeBg 上，最后一行贴着卡底
        if (code.fragment.isNotBlank() || code.subPaths.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(CodeSyntax.CodeBg)
                    .padding(vertical = 5.dp),
            ) {
                if (code.fragment.isNotBlank()) CodeBlock(code.fragment, code.matches)
                code.subPaths.forEach { sub ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(fileIcon(sub), contentDescription = null, tint = Primer.Blue500, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            sub,
                            fontSize = 11.sp,
                            color = Primer.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (code.subPaths.size > 3) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text("⚠", fontSize = 10.sp, color = Primer.TextTertiary)
                        Spacer(Modifier.width(5.dp))
                        Text(stringResource(R.string.note_paths_truncated), fontSize = 10.5.sp, color = CodeSyntax.Comment)
                    }
                }
            }
        }
    }
}

/**
 * 代码片段块：片段相对行号 + 语法高亮 + 匹配词黄底。
 *
 * 底色由外层卡统一铺（`CodeBg`），这里只排版：行号列 24dp、11.5sp / 15sp 行高 ——
 * 同一段 7 行片段从约 150dp 压到约 120dp，且长行折行后行距仍然稳定。
 */
@Composable
private fun CodeBlock(fragment: String, matches: List<MatchRange>) {
    val lines = fragment.split('\n')
    Column(Modifier.fillMaxWidth()) {
        var offset = 0
        lines.forEachIndexed { idx, line ->
            // 把落在本行的匹配词转成相对本行的偏移
            val local = matches.mapNotNull { m ->
                val start = m.start - offset
                val end = m.end - offset
                if (start >= 0 && end <= line.length && start < end) MatchRange(m.text, start, end) else null
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
                Text(
                    (idx + 1).toString(),
                    color = CodeSyntax.LineNo,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(24.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    buildCodeText(line, local),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp,
                    color = Primer.TextPrimary,
                )
            }
            offset += line.length + 1
        }
    }
}

/**
 * 语言胶囊（语言品牌色点 + 语言名）。
 *
 * 底色用中性填充（`Gray150`）而不是一圈描边：卡面本身已经是浮层，
 * 再叠一圈 1dp 线会显脏；胶囊只负责「这一格是数据」，不抢卡片的轮廓。
 */
@Composable
private fun LanguagePill(language: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Primer.Gray150)
            .padding(horizontal = 6.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(langColor(language)))
        Spacer(Modifier.width(4.dp))
        Text(language, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextSecondary, maxLines = 1)
    }
}

/** 匹配数胶囊（与语言胶囊同形同密度）。 */
@Composable
private fun MatchCountPill(count: Int) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Primer.Gray150)
            .padding(horizontal = 6.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 中性填充色而不是描边色：`Primer.Border` 按 Color.kt 的约定只做 stroke
        Box(Modifier.size(7.dp).clip(CircleShape).background(Primer.Gray300))
        Spacer(Modifier.width(4.dp))
        Text(
            stringResource(R.string.label_match_count, count),
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = Primer.TextSecondary,
            maxLines = 1,
        )
    }
}

/**
 * 拉取请求结果卡（决策二：与仓库 / 用户卡同一套浮层行卡 + 语义图标牌）。
 *
 * 状态（open / merged / closed）用小徽章挂在标题前，颜色只取既有角色
 * （`DONE` 紫 / `SuccessText` 绿 / `DANGER` 红），点进去先看到状态再看标题。
 */
@Composable
private fun PullCard(pull: PullResult, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp)
            .cardSurface()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TintChip(typeIcon(SearchType.PULLS), searchTypeRole(SearchType.PULLS), size = 26.dp, corner = 7.dp, iconSize = 15.dp)
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(pull)
                Spacer(Modifier.width(6.dp))
                Text(
                    pull.title,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Primer.Blue500,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "#${pull.number} · ${pull.repository}",
                fontSize = 11.sp,
                color = Primer.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(6.dp))
        Text("›", fontSize = 15.sp, color = Primer.TextTertiary)
    }
}

/**
 * 状态徽章（open/merged/closed）。
 *
 * `Open` 早就用了主题角色（`SuccessText`），只有 merged / closed 还写死浅色取值 ——
 * 现在三者都走角色（`Purple500` = `done`、`Red500` = `danger`）。高密度下一并压到 10sp / h6·v1。
 */
@Composable
private fun StatusBadge(pull: PullResult) {
    val (text, color) = when {
        pull.merged -> "Merged" to Primer.Purple500
        pull.state == "open" -> "Open" to Primer.SuccessText
        else -> "Closed" to Primer.Red500
    }
    Text(
        text,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color.White,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(color).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/**
 * 提交结果卡（决策二复用）：浮层行卡 + 中性图标牌 + 提交信息首行 + 「sha · 作者 · 仓库」一行。
 *
 * 提交正文可能是多行：列表里只取**首行**。原来整段直接塞进卡里，一条带正文的提交
 * 能把卡撑到三四行高，列表节奏全断；点进去在详情页能看全，列表不必替它展开。
 */
@Composable
private fun CommitCard(commit: CommitResult, onClick: () -> Unit) {
    val authorRepo = listOf(commit.author, commit.repository)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp)
            .cardSurface()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TintChip(typeIcon(SearchType.COMMITS), searchTypeRole(SearchType.COMMITS), size = 26.dp, corner = 7.dp, iconSize = 15.dp)
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                commit.message.substringBefore('\n'),
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = Primer.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    commit.sha.take(7),
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Primer.TextSecondary,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Primer.Gray150)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    authorRepo,
                    fontSize = 11.sp,
                    color = Primer.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Text("›", fontSize = 15.sp, color = Primer.TextTertiary)
    }
}

/**
 * 主题结果卡（决策二复用）：整组主题一张浮层卡，内部 FlowRow 按 6dp 间距铺满。
 *
 * 主题本身就是一个词，卡片粒度仍是「一组」而不是「一条」—— 每个主题一张卡会把
 * 一屏切成十几个小方块，反而更难扫。
 */
@Composable
private fun TopicCard(topics: List<TopicResult>, onTopicClick: (TopicResult) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp)
            .cardSurface()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            topics.forEach { topic ->
                Text(
                    topic.name,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Primer.Blue500,
                    maxLines = 1,
                    // clip 在 clickable 之前：点击反馈跟随圆角；点一下去官方主题页
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Primer.Gray150)
                        .clickable { onTopicClick(topic) }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** 根据文件扩展名返回文件类型图标 */
private fun fileIcon(path: String): ImageVector {
    val ext = path.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "md", "markdown", "txt", "rst" -> Icons.Filled.Description
        "json", "xml", "yaml", "yml", "toml" -> Icons.Filled.DataObject
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
}

/** 构建带语法高亮的代码文本（匹配词 + 注释 + 字符串 + 关键字 + 函数 + 数字，对标 GitHub light theme） */
@Composable
private fun buildCodeText(fragment: String, matches: List<MatchRange>): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        val sorted = matches.sortedBy { it.start }
        var matchIdx = 0
        while (i < fragment.length) {
            // 匹配词高亮（优先级最高，黄底文字保持原色）
            if (matchIdx < sorted.size && sorted[matchIdx].start == i) {
                val m = sorted[matchIdx]
                val start = m.start.coerceIn(0, fragment.length)
                val end = m.end.coerceIn(start, fragment.length)
                pushStyle(SpanStyle(background = CodeSyntax.MatchBg))
                append(fragment.substring(start, end))
                pop()
                i = end
                matchIdx++
                continue
            }
            val c = fragment[i]
            // 行注释（灰斜体）
            if (c == '/' && i + 1 < fragment.length && fragment[i + 1] == '/') {
                val nl = fragment.indexOf('\n', i)
                val end = if (nl == -1) fragment.length else nl
                pushStyle(SpanStyle(color = CodeSyntax.Comment, fontStyle = FontStyle.Italic))
                append(fragment.substring(i, end))
                pop()
                i = end
                continue
            }
            // 字符串（蓝）
            if (c == '"' || c == '\'') {
                val end = findStringEnd(fragment, i, c)
                pushStyle(SpanStyle(color = CodeSyntax.StringLit))
                append(fragment.substring(i, end))
                pop()
                i = end
                continue
            }
            // 数字（蓝）
            if (c.isDigit()) {
                var j = i
                while (j < fragment.length && (fragment[j].isDigit() || fragment[j] == '.')) j++
                pushStyle(SpanStyle(color = CodeSyntax.Number))
                append(fragment.substring(i, j))
                pop()
                i = j
                continue
            }
            // 标识符：函数（紫）/ 关键字（红）
            if (c.isLetter() || c == '_') {
                var j = i
                while (j < fragment.length && (fragment[j].isLetterOrDigit() || fragment[j] == '_')) j++
                val word = fragment.substring(i, j)
                val isFn = j < fragment.length && fragment[j] == '('
                when {
                    isFn -> {
                        pushStyle(SpanStyle(color = CodeSyntax.Function))
                        append(word)
                        pop()
                    }
                    word in codeKeywords -> {
                        pushStyle(SpanStyle(color = CodeSyntax.Keyword, fontWeight = FontWeight.SemiBold))
                        append(word)
                        pop()
                    }
                    else -> append(word)
                }
                i = j
                continue
            }
            append(c)
            i++
        }
    }
}

private fun findStringEnd(s: String, start: Int, quote: Char): Int {
    var i = start + 1
    while (i < s.length) {
        if (s[i] == '\\') { i += 2; continue }
        if (s[i] == quote) return i + 1
        i++
    }
    return s.length
}

private val codeKeywords = setOf(
    "fun", "class", "def", "if", "else", "elif", "for", "while", "return", "import", "from",
    "val", "var", "const", "let", "function", "public", "private", "protected", "static",
    "void", "int", "string", "boolean", "async", "await", "new", "this", "null", "true", "false",
    "struct", "impl", "fn", "pub", "use", "type", "interface", "enum", "case", "switch", "break", "continue",
)

/** 抽屉里的四个分段：目标 / 倾向 / 语言 / 更多。声明顺序即分段顺序。 */
private enum class SearchOptionsTab { TARGET, SORT, LANGUAGE, MORE }

/**
 * 搜索选项抽屉：**四维矩阵集中在一处**。
 *
 * ## 为什么把三个下拉/按钮收进抽屉
 *
 * 改前是「类型下拉 + 排序下拉 + 过滤按钮」三件并排：排序只在仓库类型下出现，
 * 语言与高级筛选又藏在另一个手板里，用户要凑一套条件得先开 A 再开 B；
 * 而且每次改一项都整排按钮重新排版（排序那格忽隐忽现）。
 *
 * 现在顶部只留一枚「选项」入口卡，四维条件全在抽屉里按分段归类：
 * 改哪一维就切哪一段，改了不关抽屉（可以连着拨几项），关抽屉时一次性按新矩阵重搜。
 * 抽屉容器、分段控件与行卡都复用「仓库」Tab 的那一套（[CardDrawer] / [CardSegments]）。
 */
@Composable
private fun SearchOptionsSheet(
    type: SearchType,
    onTypeChange: (SearchType) -> Unit,
    sortKey: String,
    onSortChange: (String) -> Unit,
    language: String?,
    onLanguageChange: (String?) -> Unit,
    advanced: Map<String, String>,
    onAdvancedChange: (String, String) -> Unit,
    onClearAdvanced: () -> Unit,
    onDismiss: () -> Unit,
) {
    var tab by remember { mutableStateOf(SearchOptionsTab.TARGET) }
    var expandedFilter by remember { mutableStateOf<String?>(null) }

    CardDrawer(
        title = stringResource(R.string.title_search_options),
        onDismiss = onDismiss,
        trailing = { close ->
            FloatingCardButton(
                onClick = close,
                shape = FloatCardShape,
                elevation = 2.dp,
                modifier = Modifier.height(28.dp),
                contentPadding = PaddingValues(horizontal = 10.dp),
            ) {
                Text(
                    stringResource(R.string.action_done),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Primer.Blue500,
                )
            }
        },
    ) { _ ->
        // 打开时停在「目标」段：这是每次搜索都绕不开的一维，其余三维是按需的
        CardSegments(
            labels = listOf(
                stringResource(R.string.search_options_target),
                stringResource(R.string.search_options_sort),
                stringResource(R.string.label_language),
                stringResource(R.string.search_options_more),
            ),
            selectedIndex = tab.ordinal,
            onSelect = { index ->
                tab = SearchOptionsTab.entries[index]
                expandedFilter = null
            },
        )
        Spacer(Modifier.height(8.dp))
        // 兜底滚动：高级筛选展开后可能超过一屏，面板不能长到顶掉标题与分段控件
        Box(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            AnimatedContent(
                targetState = tab,
                // 分段之间是「同一张清单换了内容」，不是翻页：只做淡入 + 极短的纵向位移，
                // 与滑动的胶囊一起读成「换了一格」，而不是「列表被整块替换」
                transitionSpec = {
                    (fadeIn(tween(160)) + slideInVertically { it / 10 }) togetherWith fadeOut(tween(120))
                },
                label = "search-options-rows",
            ) { current ->
                Column(Modifier.fillMaxWidth()) {
                    when (current) {
                        SearchOptionsTab.TARGET -> SearchType.entries.forEach { option ->
                            OptionRow(
                                label = stringResource(option.labelRes),
                                selected = option == type,
                                onClick = { onTypeChange(option) },
                                leading = {
                                    TintChip(
                                        typeIcon(option),
                                        role = if (option == type) TintRole.ACCENT else TintRole.NEUTRAL_SUBTLE,
                                        size = 22.dp,
                                        corner = 7.dp,
                                        iconSize = 13.dp,
                                    )
                                },
                            )
                        }

                        SearchOptionsTab.SORT -> {
                            if (type != SearchType.REPOS) {
                                // 说清「为什么现在改了也不生效」，而不是把分段藏起来 —— 藏起来用户会以为排序丢了
                                Text(
                                    stringResource(R.string.search_sort_repos_only),
                                    fontSize = 12.sp,
                                    color = Primer.TextTertiary,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                                )
                            }
                            sortOptions.forEach { (key, labelRes) ->
                                OptionRow(
                                    label = stringResource(labelRes),
                                    selected = key == sortKey,
                                    onClick = { onSortChange(key) },
                                    leading = {
                                        TintChip(
                                            Icons.AutoMirrored.Filled.Sort,
                                            role = if (key == sortKey) TintRole.ACCENT else TintRole.NEUTRAL_SUBTLE,
                                            size = 22.dp,
                                            corner = 7.dp,
                                            iconSize = 13.dp,
                                        )
                                    },
                                )
                            }
                        }

                        SearchOptionsTab.LANGUAGE -> {
                            OptionRow(
                                label = stringResource(R.string.label_all_languages),
                                selected = language == null,
                                onClick = { onLanguageChange(null) },
                                leading = {
                                    TintChip(
                                        Icons.Filled.Clear,
                                        role = if (language == null) TintRole.ACCENT else TintRole.NEUTRAL_SUBTLE,
                                        size = 22.dp,
                                        corner = 7.dp,
                                        iconSize = 13.dp,
                                    )
                                },
                            )
                            languages.forEach { (lang, langColor) ->
                                OptionRow(
                                    label = lang,
                                    selected = language == lang,
                                    onClick = { onLanguageChange(lang) },
                                    leading = { LanguageDot(langColor) },
                                )
                            }
                        }

                        SearchOptionsTab.MORE -> {
                            if (advanced.any { it.value.isNotBlank() }) {
                                OptionRow(
                                    label = stringResource(R.string.action_clear_advanced),
                                    selected = false,
                                    onClick = onClearAdvanced,
                                    leading = {
                                        TintChip(
                                            Icons.Filled.Clear,
                                            role = TintRole.NEUTRAL_SUBTLE,
                                            size = 22.dp,
                                            corner = 7.dp,
                                            iconSize = 13.dp,
                                        )
                                    },
                                )
                            }
                            advancedFilters.forEach { (name, syntax) ->
                                AdvancedOptionRow(
                                    label = stringResource(advancedFilterLabelRes(name)),
                                    syntax = syntax,
                                    value = advanced[name]?.takeIf { it.isNotBlank() },
                                    expanded = expandedFilter == name,
                                    onToggle = { expandedFilter = if (expandedFilter == name) null else name },
                                    onApply = { value ->
                                        onAdvancedChange(name, value)
                                        expandedFilter = null
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 抽屉里的一行：浮层卡 + 图标牌 + 名称 + 选中勾。 */
@Composable
private fun OptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
    secondary: String? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .cardSurface(shape = RoundedCornerShape(10.dp), elevation = 2.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) Primer.Blue500 else Primer.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (secondary != null) {
                Text(secondary, fontSize = 11.sp, color = Primer.TextTertiary, maxLines = 1)
            }
        }
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = Primer.Blue500, modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * 语言色点：**与 [TintChip] 同构** —— 同色 12% 的 22dp 圆角底 + 中间一颗实心点。
 *
 * 语言色是数据（每种语言一个色值），不属于 [TintRole]，所以不能走 TintChip；
 * 但底色 / 圆角 / 尺寸都照它的规则来，抽屉里看起来仍是同一种「图标牌」。
 */
@Composable
private fun LanguageDot(color: Color) {
    Box(
        Modifier.size(22.dp).clip(RoundedCornerShape(7.dp)).background(color.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(color))
    }
}

/**
 * 高级筛选一行：卡片本体点一下展开/收起输入框（加号⇄减号路径插值形变），
 * 展开后在卡下方补一条输入行 —— 与旧过滤手板同一套交互，只是外面换成了抽屉里的行卡。
 */
@Composable
private fun AdvancedOptionRow(
    label: String,
    syntax: String,
    value: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onApply: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .cardSurface(shape = RoundedCornerShape(10.dp), elevation = 2.dp)
                .clickable(onClick = onToggle)
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(22.dp).clip(RoundedCornerShape(7.dp)).background(Primer.Gray150),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedMorphIcon(
                    pair = MorphIcons.PlusMinus,
                    target = expanded,
                    tint = if (value != null) Primer.Blue500 else Primer.IconSecondary,
                    modifier = Modifier.size(14.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (value != null) {
                    stringResource(R.string.label_name_value, label, value)
                } else {
                    stringResource(R.string.label_name_syntax, label, syntax)
                },
                fontSize = 13.sp,
                color = if (value != null) Primer.Blue500 else Primer.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (value != null) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = Primer.Blue500, modifier = Modifier.size(16.dp))
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(160)),
            exit = shrinkVertically(tween(120)),
        ) {
            var input by remember(label, expanded) { mutableStateOf(value ?: "") }
            Row(
                Modifier.fillMaxWidth().padding(start = 28.dp, top = 6.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Primer.Gray150)
                        .border(1.dp, Primer.Border.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 13.sp, color = Primer.TextPrimary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.action_apply),
                    fontSize = 13.sp,
                    color = Primer.Blue500,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { onApply(input) },
                )
            }
        }
    }
}

/** 搜索目标 → 图标（抽屉里每行左缘的图标牌）。 */
private fun typeIcon(type: SearchType): ImageVector = when (type) {
    SearchType.CODE -> Icons.Filled.Code
    SearchType.REPOS -> Icons.Filled.Folder
    SearchType.ISSUES -> Icons.Filled.BugReport
    SearchType.PULLS -> Icons.Filled.Merge
    SearchType.USERS -> Icons.Filled.Person
    SearchType.COMMITS -> Icons.Filled.Commit
    SearchType.TOPICS -> Icons.Filled.Tag
}

private fun parseResults(json: String, type: SearchType, me: String): Pair<List<SearchItem>, Long> {
    return runCatching {
        val obj = JSONObject(json)
        val total = obj.optLong("total_count")
        val arr = obj.optJSONArray("items")
        val items = (0 until (arr?.length() ?: 0)).map { i ->
            val it = arr!!.getJSONObject(i)
            when (type) {
                SearchType.REPOS -> {
                    // full_name = owner/repo：既是展示标题，也是站内跳转目标
                    val fullName = it.optString("full_name")
                    val pair = ownerRepoOf(fullName)
                    SearchItem(
                        title = fullName,
                        subtitle = it.optString("description").orEmpty(),
                        meta = it.optString("language").takeIf { l -> l.isNotBlank() }
                            ?.let { lang ->
                                LocalizedText(
                                    R.string.search_repo_meta,
                                    listOf(lang, formatCount(it.optLong("stargazers_count"))),
                                )
                            },
                        target = pair?.let { (o, r) -> SearchTarget.Repo(o, r) },
                        // 搜索接口不返回 permissions：只能判定「是不是我自己的」
                        relation = pair?.let { (o, _): Pair<String, String> ->
                            repoRelationOf(o, me, isPrivate = it.optBoolean("private", false))
                        },
                        // 仓库卡左端放开发者头像（搜索接口的 owner 对象里就有，不用再打一次 API）
                        avatarUrl = it.optJSONObject("owner")?.optString("avatar_url")?.takeIf { u -> u.isNotBlank() },
                    )
                }
                SearchType.USERS -> SearchItem(
                    title = it.optString("login"),
                    subtitle = it.optString("html_url").orEmpty(),
                    // 站内没有他人主页，交给浏览器
                    target = SearchTarget.Web(it.optString("html_url")),
                    avatarUrl = it.optString("avatar_url").takeIf { u -> u.isNotBlank() },
                )
                SearchType.ISSUES -> {
                    // repository_url（API 地址）+ number 才是跳转所需信息；html_url 只用于展示
                    val pair = ownerRepoOf(it.optString("repository_url"))
                    val number = it.optLong("number")
                    SearchItem(
                        title = it.optString("title"),
                        // 副标题放「owner/repo」而不是整条 html_url：URL 一行永远被截在
                        // 「https://github.com/ow…」，同一行里换来的仓库名反而读不到
                        subtitle = pair?.let { (o, r) -> "$o/$r" } ?: it.optString("html_url").orEmpty(),
                        // state 是 GitHub 的判别值（open / closed），没有对应资源就原样透出
                        meta = LocalizedText(raw = it.optString("state")),
                        target = pair?.takeIf { _ -> number > 0 }
                            ?.let { (o, r) -> SearchTarget.Issue(o, r, number) },
                    )
                }
                else -> SearchItem(title = "", subtitle = "")
            }
        }
        items to total
    }.getOrDefault(emptyList<SearchItem>() to 0L)
}

private fun parseCodeResults(json: String): Pair<List<CodeResult>, Long> {
    return runCatching {
        val obj = JSONObject(json)
        val total = obj.optLong("total_count")
        val arr = obj.optJSONArray("items")
        val raw = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
            val it = arr!!.getJSONObject(i)
            val repoObj = it.optJSONObject("repository")
            val repo = repoObj?.optString("full_name") ?: ""
            val owner = repoObj?.optJSONObject("owner")?.optString("login") ?: repo.substringBefore('/')
            val path = it.optString("path")
            val language = repoObj?.optString("language")?.takeIf { l -> l.isNotBlank() }
            val avatarUrl = repoObj?.optJSONObject("owner")?.optString("avatar_url")
            val sha = it.optString("sha").takeIf { s -> s.isNotBlank() }
            val textMatches = it.optJSONArray("text_matches")
            // 选择代码内容的匹配（property 不是 path 的，通常才是代码片段）
            val firstMatch = (0 until (textMatches?.length() ?: 0))
                .map { j -> textMatches!!.getJSONObject(j) }
                .firstOrNull { o -> o.optString("property") != "path" }
                ?: textMatches?.optJSONObject(0)
            val fragment = firstMatch?.optString("fragment") ?: ""
            val matches = firstMatch?.optJSONArray("matches")?.let { m ->
                (0 until m.length()).mapNotNull { j ->
                    val matchObj = m.getJSONObject(j)
                    val text = matchObj.optString("text")
                    val indices = matchObj.optJSONArray("indices")
                    val start = indices?.optInt(0) ?: -1
                    val end = indices?.optInt(1) ?: -1
                    if (start >= 0 && end >= 0) MatchRange(text, start, end) else null
                }
            } ?: emptyList()
            CodeResult(
                owner = owner,
                repository = repo,
                path = path,
                // 代码结果点进去直接落到「文件查看页」（深链接 path）
                target = if (owner.isNotBlank() && repo.isNotBlank() && path.isNotBlank()) {
                    SearchTarget.File(owner, repo, path)
                } else {
                    null
                },
                fileName = path.substringAfterLast('/'),
                avatarUrl = avatarUrl,
                language = language,
                sha = sha,
                fragment = fragment,
                matches = matches,
                matchCount = matches.map { it.text }.distinct().size,
                subPaths = emptyList(),
            )
        }
        // 按 sha 分组：相同内容文件只保留第一个，其余折叠为次级路径（去重）
        val bySha = raw.groupBy { it.sha }
        val items = raw.mapNotNull { item ->
            if (item.sha == null) return@mapNotNull item
            val group = bySha[item.sha].orEmpty()
            val first = group.minByOrNull { it.path }
            if (first != item) return@mapNotNull null
            item.copy(subPaths = group.map { it.path }.filter { it != item.path })
        }
        items to total
    }.getOrDefault(emptyList<CodeResult>() to 0L)
}

private fun parsePullResults(json: String): Pair<List<PullResult>, Long> {
    return runCatching {
        val obj = JSONObject(json)
        val total = obj.optLong("total_count")
        val arr = obj.optJSONArray("items")
        val items = (0 until (arr?.length() ?: 0)).map { i ->
            val it = arr!!.getJSONObject(i)
            val state = it.optString("state")
            val merged = it.optJSONObject("pull_request")?.optString("merged_at")?.isNotBlank() == true
            val repo = it.optString("repository_url").split('/').takeLast(2).joinToString("/")
            PullResult(
                target = ownerRepoOf(it.optString("repository_url"))?.let { (o, r) ->
                    SearchTarget.PullRequest(o, r, it.optLong("number"))
                },
                number = it.optInt("number"),
                title = it.optString("title"),
                state = state,
                merged = merged,
                repository = repo,
            )
        }
        items to total
    }.getOrDefault(emptyList<PullResult>() to 0L)
}

private fun parseCommitResults(json: String): Pair<List<CommitResult>, Long> {
    return runCatching {
        val obj = JSONObject(json)
        val total = obj.optLong("total_count")
        val arr = obj.optJSONArray("items")
        val items = (0 until (arr?.length() ?: 0)).map { i ->
            val it = arr!!.getJSONObject(i)
            val commit = it.optJSONObject("commit")
            val repo = it.optJSONObject("repository")?.optString("full_name") ?: ""
            CommitResult(
                target = ownerRepoOf(repo)?.let { (o, r) ->
                    SearchTarget.Commit(o, r, it.optString("sha"))
                },
                sha = it.optString("sha"),
                message = commit?.optString("message") ?: "",
                author = commit?.optJSONObject("author")?.optString("name")
                    ?: it.optJSONObject("author")?.optString("login") ?: "",
                repository = repo,
            )
        }
        items to total
    }.getOrDefault(emptyList<CommitResult>() to 0L)
}

private fun parseTopicResults(json: String): Pair<List<TopicResult>, Long> {
    return runCatching {
        val obj = JSONObject(json)
        val total = obj.optLong("total_count")
        val arr = obj.optJSONArray("items")
        val items = (0 until (arr?.length() ?: 0)).map { i ->
            val it = arr!!.getJSONObject(i)
            TopicResult(name = it.optString("name"))
        }
        items to total
    }.getOrDefault(emptyList<TopicResult>() to 0L)
}

private fun formatCount(n: Long): String = when {
    n >= 1000 -> "%.1fk".format(n / 1000.0)
    else -> n.toString()
}

private fun langColor(lang: String?): Color = when (lang) {
    "Kotlin" -> Color(0xFFA97BFF)
    "Rust" -> Color(0xFFDEA584)
    "Java" -> Color(0xFFB07219)
    "Python" -> Color(0xFF3572A5)
    "JavaScript" -> Color(0xFFF1E05A)
    "TypeScript" -> Color(0xFF3178C6)
    "Go" -> Color(0xFF00ADD8)
    "Swift" -> Color(0xFFF05138)
    "C++" -> Color(0xFFF34B7D)
    "C" -> Color(0xFF555555)
    "PHP" -> Color(0xFF4F5D95)
    "Markdown" -> Color(0xFF083FA1)
    "Text" -> Color(0xFF8B949E)
    "HTML" -> Color(0xFFE34C26)
    "CSS" -> Color(0xFF663399)
    "Shell" -> Color(0xFF89E051)
    else -> Color(0xFF8B949E)
}

private data class SearchItem(
    val title: String,
    val subtitle: String,
    val meta: LocalizedText? = null,
    /** 这一行要去哪（解析阶段就定下来，卡片点击直接派发）。 */
    val target: SearchTarget? = null,
    /** 仓库结果专用：与当前账号的关系（我的 / 协作 / 他人）。 */
    val relation: RepoRelation? = null,
    /**
     * 左端图标位用的头像：仓库取 `owner.avatar_url`（开发者头像），用户取 `avatar_url`。
     * 其它类型为 null —— issue / PR / commit / topic 没有「属于这条结果的开发者」这回事。
     */
    val avatarUrl: String? = null,
)

private data class CodeResult(
    val owner: String,
    val repository: String,
    val path: String,
    val target: SearchTarget? = null,
    val fileName: String,
    val avatarUrl: String? = null,
    val language: String? = null,
    val sha: String? = null,
    val fragment: String,
    val matches: List<MatchRange>,
    val matchCount: Int,
    val subPaths: List<String>,
)

private data class MatchRange(val text: String, val start: Int, val end: Int)

private data class PullResult(
    val number: Int,
    val title: String,
    val state: String,
    val merged: Boolean,
    val repository: String,
    val target: SearchTarget? = null,
)

private data class CommitResult(
    val sha: String,
    val message: String,
    val author: String,
    val repository: String,
    val target: SearchTarget? = null,
)

private data class TopicResult(
    val name: String,
) {
    val target: SearchTarget get() = SearchTarget.Web(topicWebUrl(name))
}

private val sortOptions = listOf(
    "" to R.string.search_sort_best,
    "stars" to R.string.search_sort_stars,
    "forks" to R.string.search_sort_forks,
    "updated" to R.string.search_sort_updated,
)

/** 排序 key → 显示名；未知 key 退回「最佳匹配」（不把内部 key 显示给用户）。 */
@StringRes
private fun sortLabelRes(key: String): Int =
    sortOptions.firstOrNull { it.first == key }?.second ?: R.string.search_sort_best

private val languages = listOf(
    "JavaScript" to Color(0xFFF1E05A),
    "Python" to Color(0xFF3572A5),
    "TypeScript" to Color(0xFF3178C6),
    "Java" to Color(0xFFB07219),
    "Kotlin" to Color(0xFFA97BFF),
    "Go" to Color(0xFF00ADD8),
    "Rust" to Color(0xFFDEA584),
    "C++" to Color(0xFFF34B7D),
    "PHP" to Color(0xFF4F5D95),
)

/**
 * 高级筛选：**稳定 key → GitHub 查询语法**。
 *
 * key 是 ASCII 常量而不是显示名 —— `advancedValues` / `expandedFilter` 都按 key 存，
 * 显示名改成资源后，切语言不会改变「已经填好的筛选值」，查询串也不会跟着变。
 */
private val advancedFilters = listOf(
    "owner" to "user:/org:",
    "size" to "size:",
    "followers" to "followers:",
    "forks" to "forks:",
    "stars" to "stars:",
    "created" to "created:",
    "pushed" to "pushed:",
    "topic" to "topic:",
    "license" to "license:",
    "archived" to "archived:",
    "visibility" to "is:public/private",
)

@StringRes
private fun advancedFilterLabelRes(key: String): Int = when (key) {
    "owner" -> R.string.advanced_filter_owner
    "size" -> R.string.advanced_filter_size
    "followers" -> R.string.advanced_filter_followers
    "forks" -> R.string.advanced_filter_forks
    "stars" -> R.string.advanced_filter_stars
    "created" -> R.string.advanced_filter_created
    "pushed" -> R.string.advanced_filter_pushed
    "topic" -> R.string.advanced_filter_topic
    "license" -> R.string.advanced_filter_license
    "archived" -> R.string.advanced_filter_archived
    else -> R.string.advanced_filter_visibility
}

/**
 * 结果列表底部的分页控件。
 *
 * 三态：
 * - 还有下一页 → 「加载更多（已显示 N / 共 M）」按钮；
 * - 正在加载 → 小转圈 + 「加载中…」；
 * - 已到底（或本页就装下了全部）→ 一行「已到底」灰字，让用户知道不用再下拉。
 */
@Composable
private fun SearchPagingFooter(
    shown: Int,
    total: Long,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
) {
    Box(
        Modifier.fillMaxWidth().padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            loadingMore -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = Primer.Blue500,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.state_loading_ellipsis), fontSize = 12.5.sp, color = Primer.TextTertiary)
            }

            PagingState(page = 1, total = total, shown = shown).hasMore -> {
                // 按下反馈：原来只有水波纹，按下去的那 100ms 里按钮本身是死的（没有中间帧）。
                val press = rememberPressFeedback()
                Text(
                    loadMoreText(shown, total).resolve(),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Primer.Blue500,
                    modifier = Modifier
                        .graphicsLayer {
                            scaleX = press.scale.value
                            scaleY = press.scale.value
                        }
                        .clip(RoundedCornerShape(16.dp))
                        .background(Primer.InfoSurface)
                        .clickable(interactionSource = press.interaction, indication = LocalIndication.current, onClick = onLoadMore)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }

            else -> Text(stringResource(R.string.state_end_of_results), fontSize = 12.sp, color = Primer.TextTertiary)
        }
    }
}
