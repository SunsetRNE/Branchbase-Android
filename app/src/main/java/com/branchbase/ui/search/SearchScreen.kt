package com.branchbase.ui.search

import android.content.Context
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.branchbase.cache.SearchCacheDatabase
import com.branchbase.cache.SearchCacheManager
import com.branchbase.core.RustBridge
import com.branchbase.ui.log.Logger
import com.branchbase.ui.theme.CodeSyntax
import com.branchbase.ui.theme.Primer
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
    var query by remember { mutableStateOf("") }
    var selectedLanguage by remember { mutableStateOf<String?>(null) }
    var advancedValues by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var expandedFilter by remember { mutableStateOf<String?>(null) }
    var type by remember { mutableStateOf("仓库") }
    var typeMenu by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf("最佳匹配") }
    var sortKey by remember { mutableStateOf("") }
    var sortMenu by remember { mutableStateOf(false) }
    var showFilter by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<SearchItem>>(emptyList()) }
    var codeResults by remember { mutableStateOf<List<CodeResult>>(emptyList()) }
    var pullResults by remember { mutableStateOf<List<PullResult>>(emptyList()) }
    var commitResults by remember { mutableStateOf<List<CommitResult>>(emptyList()) }
    var topicResults by remember { mutableStateOf<List<TopicResult>>(emptyList()) }
    var total by remember { mutableStateOf(0L) }
    var searched by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }

    fun doSearch() {
        if (query.isBlank()) return
        val q = buildString {
            append(query)
            selectedLanguage?.let { append(" language:$it") }
            advancedValues.forEach { (name, value) ->
                val syntax = advancedFilters.firstOrNull { it.first == name }?.second ?: return@forEach
                append(" $syntax$value")
            }
            // Issues / 拉取请求 类型限定（复用 /search/issues，type:pr 区分）
            when (type) {
                "Issues" -> append(" type:issue")
                "拉取请求" -> append(" type:pr")
            }
        }
        // 缓存 key：仅「仓库」类型携带 sort（其余类型不支持排序，避免 sortKey 残留污染缓存 key）
        val cacheKey = if (type == "仓库") "$type|$q|$sortKey" else "$type|$q"

        // 统一解析并写入对应类型状态
        fun parseAndSet(json: String) {
            when (type) {
                "代码" -> { val p = parseCodeResults(json); codeResults = p.first; total = p.second }
                "拉取请求" -> { val p = parsePullResults(json); pullResults = p.first; total = p.second }
                "提交" -> { val p = parseCommitResults(json); commitResults = p.first; total = p.second }
                "主题" -> { val p = parseTopicResults(json); topicResults = p.first; total = p.second }
                else -> { val p = parseResults(json, type); results = p.first; total = p.second }
            }
        }

        loading = true
        searchError = null
        scope.launch {
            // 命中缓存：读本地数据库
            val cached = cacheManager.get(cacheKey, type)
            if (cached != null) {
                parseAndSet(cached)
                searched = true
                loading = false
                return@launch
            }

            // 未命中：拉远端并写缓存
            val json = when (type) {
                "代码" -> RustBridge.searchCode(host, token, q)
                "仓库" -> RustBridge.searchRepositories(host, token, q, sortKey)
                "用户" -> RustBridge.searchUsers(host, token, q)
                "Issues", "拉取请求" -> RustBridge.searchIssues(host, token, q)
                "提交" -> RustBridge.searchCommits(host, token, q)
                "主题" -> RustBridge.searchTopics(host, token, q)
                else -> null
            }
            Logger.net("搜索 $type: $q → ${if (json != null && !json.startsWith("ERROR:")) "200" else "失败"}", "search")
            if (json != null && !json.startsWith("ERROR:")) {
                // 先立即用拉取结果填充展示，再异步写入缓存（更新缓存与展示同源、同步发生）
                parseAndSet(json)
                cacheManager.put(cacheKey, type, json)
                searched = true
            } else {
                // 搜索失败（网络错误 / 速率限制 / 权限不足等），区别于「无结果」
                searched = true
                searchError = "搜索失败，请稍后重试（可能触发速率限制）"
            }
            loading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Primer.BackgroundPrimary)
            .statusBarsPadding(),
    ) {
        // 搜索框
        SearchTopBar(query = query, onQueryChange = { query = it }, onSearch = { doSearch() }, onBack = onBack)

        // 工具栏
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 类型下拉
            Box(Modifier.weight(1f)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth().height(40.dp)
                        .clip(RoundedCornerShape(8.dp)).background(Primer.BackgroundSecondary)
                        .clickable { typeMenu = true }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(type, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary, modifier = Modifier.weight(1f))
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = Primer.IconSecondary, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                    types.forEach { t ->
                        DropdownMenuItem(
                            text = { Text(t, fontSize = 13.sp) },
                            onClick = { type = t; typeMenu = false },
                        )
                    }
                }
            }
            // 排序下拉（仅仓库搜索支持排序，其余类型隐藏）
            if (type == "仓库") {
                Box {
                    Row(
                        modifier = Modifier
                            .height(40.dp)
                            .clip(RoundedCornerShape(8.dp)).background(Primer.BackgroundSecondary)
                            .clickable { sortMenu = true }.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(sort, fontSize = 12.sp, color = Primer.TextSecondary)
                        Spacer(Modifier.width(2.dp))
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = Primer.IconSecondary, modifier = Modifier.size(16.dp))
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        sortOptions.forEach { (key, label) ->
                            DropdownMenuItem(
                                text = { Text(label, fontSize = 13.sp) },
                                onClick = {
                                    sort = label; sortKey = key; sortMenu = false
                                    if (searched) doSearch()
                                },
                            )
                        }
                    }
                }
            }
            // 过滤按钮
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Primer.BackgroundSecondary)
                    .clickable { showFilter = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Tune, contentDescription = "过滤", tint = Primer.IconPrimary, modifier = Modifier.size(20.dp))
            }
        }

        // 结果
        if (loading) {
            SearchSkeleton()
        } else if (searchError != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(searchError!!, color = Primer.Red500)
            }
        } else if (!searched) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("输入关键词搜索", color = Primer.TextTertiary)
            }
        } else if (type == "代码") {
            if (codeResults.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("未找到匹配的代码", color = Primer.TextTertiary)
                }
            } else {
                Column {
                    Text("$total 个代码结果", fontSize = 12.sp, color = Primer.TextTertiary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    LazyColumn {
                        items(codeResults) { code -> CodeResultCard(code) }
                    }
                }
            }
        } else if (type == "拉取请求") {
            if (pullResults.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("未找到匹配的拉取请求", color = Primer.TextTertiary)
                }
            } else {
                Column {
                    Text("$total 个拉取请求结果", fontSize = 12.sp, color = Primer.TextTertiary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    LazyColumn {
                        items(pullResults) { pull -> PullCard(pull) }
                    }
                }
            }
        } else if (type == "提交") {
            if (commitResults.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("未找到匹配的提交", color = Primer.TextTertiary)
                }
            } else {
                Column {
                    Text("$total 个提交结果", fontSize = 12.sp, color = Primer.TextTertiary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    LazyColumn {
                        items(commitResults) { commit -> CommitCard(commit) }
                    }
                }
            }
        } else if (type == "主题") {
            if (topicResults.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("未找到匹配的主题", color = Primer.TextTertiary)
                }
            } else {
                Column {
                    Text("$total 个主题结果", fontSize = 12.sp, color = Primer.TextTertiary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    LazyColumn {
                        item { TopicCard(topicResults) }
                    }
                }
            }
        } else if (results.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("未找到匹配结果", color = Primer.TextTertiary)
            }
        } else {
            Column {
                Text("$total 个结果", fontSize = 12.sp, color = Primer.TextTertiary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                LazyColumn {
                    items(results) { item -> SearchItemCard(item) }
                }
            }
        }
    }

    if (showFilter) {
        FilterSheet(
            selectedLanguage = selectedLanguage,
            onSelectLanguage = { lang ->
                selectedLanguage = lang
                showFilter = false
                doSearch()
            },
            advancedValues = advancedValues,
            expandedFilter = expandedFilter,
            onToggleFilter = { name -> expandedFilter = if (expandedFilter == name) null else name },
            onApplyFilter = { name, value ->
                advancedValues = advancedValues + (name to value)
                expandedFilter = null
            },
            onDismiss = { showFilter = false },
        )
    }
}

/** 搜索框 */
@Composable
private fun SearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Primer.IconPrimary, modifier = Modifier.size(24.dp).clickable { onBack() })
        Spacer(Modifier.width(4.dp))
        Box(
            modifier = Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(8.dp)).background(Primer.BackgroundSecondary).padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 14.sp, color = Primer.TextPrimary),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text("搜索或跳转", fontSize = 14.sp, color = Primer.TextTertiary)
                    }
                    inner()
                },
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Filled.Search, contentDescription = "搜索", tint = Primer.Blue500, modifier = Modifier.size(22.dp).clickable { onSearch() })
    }
}

/** 搜索加载骨架屏（shimmer 微光扫过） */
@Composable
private fun SearchSkeleton() {
    val transition = rememberInfiniteTransition()
    val alpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(animation = tween(700), repeatMode = RepeatMode.Reverse),
    )
    Column {
        repeat(5) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Primer.BackgroundSecondary)
                    .padding(14.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(0.55f)
                        .height(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Primer.Border.copy(alpha = alpha)),
                )
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Primer.Border.copy(alpha = alpha)),
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth(0.7f)
                        .height(12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Primer.Border.copy(alpha = alpha)),
                )
            }
        }
    }
}

/** 搜索结果卡片 */
@Composable
private fun SearchItemCard(item: SearchItem) {
    Column(
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Primer.BackgroundSecondary)
            .padding(14.dp),
    ) {
        Text(item.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Primer.Blue500)
        if (item.subtitle.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(item.subtitle, fontSize = 13.sp, color = Primer.TextSecondary)
        }
        if (item.meta != null) {
            Spacer(Modifier.height(8.dp))
            Text(item.meta, fontSize = 12.sp, color = Primer.TextTertiary)
        }
    }
}

/** 代码搜索结果卡片（对标 GitHub 网页：仓库路径 + 文件路径胶囊 + 代码片段 + 次级路径 + 折叠提示） */
@Composable
private fun CodeResultCard(code: CodeResult) {
    Column(
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, CodeSyntax.CardBorder, RoundedCornerShape(8.dp)),
    ) {
        // ① 仓库路径行：头像（Coil 加载，无则首字母兜底）+ owner/repo + 下拉箭头
        Row(
            modifier = Modifier.fillMaxWidth().background(CodeSyntax.CodeBg).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (code.avatarUrl != null) {
                AsyncImage(
                    model = code.avatarUrl,
                    contentDescription = code.owner,
                    modifier = Modifier.size(20.dp).clip(CircleShape),
                )
            } else {
                Box(Modifier.size(20.dp).clip(CircleShape).background(Primer.Border), contentAlignment = Alignment.Center) {
                    Text(code.owner.take(1).uppercase(), fontSize = 9.sp, color = Primer.IconPrimary)
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(code.repository, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = Primer.IconSecondary, modifier = Modifier.size(14.dp))
        }
        // ② 文件路径行：展开箭头 + 文件名 + 语言胶囊 + Matches 胶囊
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Primer.IconSecondary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(code.fileName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
            Spacer(Modifier.weight(1f))
            if (code.language != null) {
                LanguagePill(code.language)
                Spacer(Modifier.width(6.dp))
            }
            MatchCountPill(code.matchCount)
        }
        // ③ 代码片段块（片段相对行号 + 语法高亮 + 匹配词黄底）
        CodeBlock(code.fragment, code.matches)
        // ④ 次级文件路径（同 sha 分组）
        if (code.subPaths.isNotEmpty()) {
            Column(Modifier.fillMaxWidth()) {
                code.subPaths.forEach { sub ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 40.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(fileIcon(sub), contentDescription = null, tint = Primer.Blue500, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(sub, fontSize = 12.sp, color = Primer.TextSecondary)
                    }
                }
            }
        }
        // ⑤ 折叠提示
        if (code.subPaths.size > 3) {
            Row(
                Modifier.fillMaxWidth().background(CodeSyntax.CodeBg).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text("⚠", fontSize = 11.sp, color = Primer.TextTertiary)
                Spacer(Modifier.width(6.dp))
                Text("仅显示包含此内容的部分文件路径。优化搜索以查看更多。", fontSize = 11.sp, color = CodeSyntax.Comment)
            }
        }
    }
}

/** 代码片段块：片段相对行号 + 语法高亮 + 匹配词黄底 */
@Composable
private fun CodeBlock(fragment: String, matches: List<MatchRange>) {
    val lines = fragment.split('\n')
    Column(Modifier.fillMaxWidth().background(CodeSyntax.CodeBg).padding(vertical = 6.dp)) {
        var offset = 0
        lines.forEachIndexed { idx, line ->
            // 把落在本行的匹配词转成相对本行的偏移
            val local = matches.mapNotNull { m ->
                val start = m.start - offset
                val end = m.end - offset
                if (start >= 0 && end <= line.length && start < end) MatchRange(m.text, start, end) else null
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Text(
                    (idx + 1).toString(),
                    color = CodeSyntax.LineNo,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(34.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    buildCodeText(line, local),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = Primer.TextPrimary,
                )
            }
            offset += line.length + 1
        }
    }
}

/** 语言胶囊（语言色点 + 语言名） */
@Composable
private fun LanguagePill(language: String) {
    Row(
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, CodeSyntax.CardBorder, RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(langColor(language)))
        Spacer(Modifier.width(5.dp))
        Text(language, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextSecondary)
    }
}

/** 匹配数胶囊 */
@Composable
private fun MatchCountPill(count: Int) {
    Row(
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, CodeSyntax.CardBorder, RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Primer.Border))
        Spacer(Modifier.width(5.dp))
        Text("Matches: $count", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextSecondary)
    }
}

/** 拉取请求结果卡片（状态徽章 + 标题 + 编号 + 仓库） */
@Composable
private fun PullCard(pull: PullResult) {
    Column(
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Primer.BackgroundSecondary)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusBadge(pull)
            Spacer(Modifier.width(8.dp))
            Text(pull.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Primer.Blue500)
        }
        Spacer(Modifier.height(8.dp))
        Text("#${pull.number} · ${pull.repository}", fontSize = 12.sp, color = Primer.TextTertiary)
    }
}

/** 状态徽章（open/merged/closed） */
@Composable
private fun StatusBadge(pull: PullResult) {
    val (text, color) = when {
        pull.merged -> "Merged" to Color(0xFF8250DF)
        pull.state == "open" -> "Open" to Color(0xFF1A7F37)
        else -> "Closed" to Color(0xFFCF222E)
    }
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color.White,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(color).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** 提交结果卡片（提交信息 + sha + 作者 + 仓库） */
@Composable
private fun CommitCard(commit: CommitResult) {
    Column(
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Primer.BackgroundSecondary)
            .padding(14.dp),
    ) {
        Text(commit.message, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clip(RoundedCornerShape(4.dp)).background(Primer.Gray150).padding(horizontal = 6.dp, vertical = 1.dp)) {
                Text(commit.sha.take(7), fontSize = 12.sp, color = Primer.TextSecondary, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.width(8.dp))
            Text(commit.author, fontSize = 12.sp, color = Primer.TextTertiary)
            Spacer(Modifier.width(8.dp))
            Text(commit.repository, fontSize = 12.sp, color = Primer.TextTertiary)
        }
    }
}

/** 主题结果卡片（chip 网格） */
@Composable
private fun TopicCard(topics: List<TopicResult>) {
    Column(
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Primer.BackgroundSecondary)
            .padding(14.dp),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            topics.forEach { topic ->
                Text(
                    topic.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Primer.Blue500,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Primer.Gray150).padding(horizontal = 14.dp, vertical = 6.dp),
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

/** 过滤面板 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheet(
    selectedLanguage: String?,
    onSelectLanguage: (String) -> Unit,
    advancedValues: Map<String, String>,
    expandedFilter: String?,
    onToggleFilter: (String) -> Unit,
    onApplyFilter: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text("筛选", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary)
            Spacer(Modifier.height(12.dp))
            Text("语言", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
            Spacer(Modifier.height(6.dp))
            languages.forEach { (lang, color) ->
                val selected = selectedLanguage == lang
                Row(
                    Modifier.fillMaxWidth().clickable { onSelectLanguage(lang) }.padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(color))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        lang,
                        fontSize = 14.sp,
                        color = if (selected) Primer.Blue500 else Primer.TextPrimary,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Spacer(Modifier.weight(1f))
                    if (selected) {
                        Text("✓", color = Primer.Blue500, fontSize = 14.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("高级", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
            advancedFilters.forEach { (name, syntax) ->
                val expanded = expandedFilter == name
                val value = advancedValues[name]
                Column {
                    Row(
                        Modifier.fillMaxWidth().clickable { onToggleFilter(name) }.padding(vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(20.dp).clip(CircleShape).background(Primer.BackgroundPrimary), contentAlignment = Alignment.Center) {
                            Text(if (expanded) "−" else "+", fontSize = 14.sp, color = Primer.IconSecondary)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (value != null) "$name：$value" else "$name（$syntax）",
                            fontSize = 13.sp,
                            color = if (value != null) Primer.Blue500 else Primer.TextSecondary,
                        )
                    }
                    if (expanded) {
                        var input by remember(name) { mutableStateOf(value ?: "") }
                        Row(
                            Modifier.fillMaxWidth().padding(start = 28.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(8.dp)).background(Primer.BackgroundPrimary).padding(horizontal = 10.dp),
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
                                "应用",
                                fontSize = 13.sp,
                                color = Primer.Blue500,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable { onApplyFilter(name, input) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun parseResults(json: String, type: String): Pair<List<SearchItem>, Long> {
    return runCatching {
        val obj = JSONObject(json)
        val total = obj.optLong("total_count")
        val arr = obj.optJSONArray("items")
        val items = (0 until (arr?.length() ?: 0)).map { i ->
            val it = arr!!.getJSONObject(i)
            when (type) {
                "仓库" -> SearchItem(
                    title = it.optString("full_name"),
                    subtitle = it.optString("description").orEmpty(),
                    meta = it.optString("language").takeIf { l -> l.isNotBlank() }
                        ?.let { lang -> "$lang · ${formatCount(it.optLong("stargazers_count"))} 星" },
                )
                "用户" -> SearchItem(
                    title = it.optString("login"),
                    subtitle = it.optString("html_url").orEmpty(),
                )
                "Issues" -> SearchItem(
                    title = it.optString("title"),
                    subtitle = it.optString("html_url").orEmpty(),
                    meta = it.optString("state"),
                )
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
    val meta: String? = null,
)

private data class CodeResult(
    val owner: String,
    val repository: String,
    val path: String,
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
)

private data class CommitResult(
    val sha: String,
    val message: String,
    val author: String,
    val repository: String,
)

private data class TopicResult(
    val name: String,
)

private val types = listOf("代码", "仓库", "Issues", "拉取请求", "用户", "提交", "主题")

private val sortOptions = listOf(
    "" to "最佳匹配",
    "stars" to "最多星标",
    "forks" to "最多 Fork",
    "updated" to "最近更新",
)

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

private val advancedFilters = listOf(
    "所有者" to "user:/org:",
    "大小" to "size:",
    "关注者" to "followers:",
    "Fork 数" to "forks:",
    "星标数" to "stars:",
    "创建时间" to "created:",
    "推送时间" to "pushed:",
    "主题" to "topic:",
    "许可证" to "license:",
    "已归档" to "archived:",
    "可见性" to "is:public/private",
)