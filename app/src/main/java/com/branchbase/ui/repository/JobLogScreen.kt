package com.branchbase.ui.repository

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.branchbase.R
import com.branchbase.cache.PageCache
import com.branchbase.cache.SearchCacheDatabase
import com.branchbase.cache.SearchCacheManager
import com.branchbase.core.RustBridge
import com.branchbase.joblogs.JobLogStore
import com.branchbase.joblogs.LogSegment
import com.branchbase.ui.theme.CodeSyntax
import com.branchbase.ui.theme.Primer
import com.branchbase.ui.theme.iconTap
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * **日志页**：作业日志唯一的去处。
 *
 * 改前有三种看法互相打架 —— 运行详情页卡片里一个 200 行 / 320dp 封顶的小窗、
 * 「完整日志」跳到另一个页面整段直出、（其实是同一份数据）。小窗已删，
 * 那个页面升级成本页：搜索命中跳转 / 仅错误·含警告过滤 / 分组折叠 / 复制分享。
 *
 * 两条来自 GitHub API 的事实决定了这里的形态（见 README「运行中的工作流」）：
 * 1. **运行中的 job 取不到日志**（远端 blob 结束后才生成）⇒ 显示「运行中，结束后自动出现」，
 *    而不是「加载失败」；
 * 2. 取数走 `:joblogs`（单飞 + 缓存 + 分段），本页只负责渲染。
 *
 * 渲染用 `LazyColumn` 逐行：改前把整段日志拼成一个 `Text`，几 MB 的日志整块测量、滚动会卡。
 */
@Composable
fun JobLogScreen(
    sessionJson: String,
    owner: String,
    repo: String,
    jobId: Long,
    initialStepNumber: Long?,
    logStore: JobLogStore,
    onBack: () -> Unit,
) {
    val (host, token, _) = sessionInfo(sessionJson)
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var steps by remember { mutableStateOf<List<JobStep>>(emptyList()) }
    var segments by remember { mutableStateOf<List<LogSegment>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var pending by remember { mutableStateOf(false) }   // job 还没结束 ⇒ 远端还没有日志
    var failed by remember { mutableStateOf(false) }
    var forceTick by remember { mutableStateOf(0) }

    var selectedStep by remember { mutableStateOf<Long?>(initialStepNumber) }
    var query by remember { mutableStateOf("") }
    var findOpen by remember { mutableStateOf(false) }
    var errOnly by remember { mutableStateOf(false) }
    var warnToo by remember { mutableStateOf(false) }
    var hitCursor by remember { mutableStateOf(0) }
    val collapsed = remember { mutableStateMapOf<Int, Boolean>() }
    var menuOpen by remember { mutableStateOf(false) }

    // 回到前台对齐一次：作业在后台跑完的话，这里就是日志「自动出现」的时机
    var seenStart by remember { mutableStateOf(false) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (RunPollPolicy.resumeShouldForceRefresh(seenStart)) forceTick++ else seenStart = true
        }
    }

    LaunchedEffect(owner, repo, jobId, forceTick) {
        loading = true
        failed = false
        pending = false
        val force = forceTick > 0
        val manager = SearchCacheManager(SearchCacheDatabase.getInstance(context).searchCacheDao())
        val jobKey = PageCache.jobKey(owner, repo, jobId)

        PageCache.cachedFirst(manager, jobKey, PageCache.TYPE_DETAIL, force)?.let { steps = parseJobSteps(it) }
        logStore.cached(jobId, force)?.let { segments = it.segments }
        if (steps.isNotEmpty() || segments.isNotEmpty()) loading = false

        coroutineScope {
            val stepsJob = async {
                PageCache.refresh(manager, jobKey, PageCache.TYPE_DETAIL, force) {
                    RustBridge.getJson(host, token, "/repos/$owner/$repo/actions/jobs/$jobId")
                }
            }
            val logJob = async { logStore.refresh(jobId, force) }
            stepsJob.await()?.let { steps = parseJobSteps(it) }
            when (val log = logJob.await()) {
                null -> if (segments.isEmpty() && steps.any { RunPollPolicy.isLogPending(it.status) }) pending = true
                else -> segments = log.segments
            }
        }
        loading = false
    }

    // 当前选中的步骤（用于顶部标题与 chips 高亮）
    val currentStep = steps.firstOrNull { it.number == selectedStep }

    // 全屏页（`barVisible = route is RepoRoute.Tab` ⇒ 这一层没有底部导航栏）：
    // 顶栏与日志列表都要**自己**避开系统栏，否则返回箭头压在状态栏下、日志最后几行压在手势条下。
    // `DetailScaffold` 就是干这个的，但这一页的顶栏带搜索框与步骤选择，没有走它。
    Column(
        Modifier
            .fillMaxSize()
            .background(Primer.BackgroundPrimary)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        DetailTopBar(
            title = buildString {
                append(stringResource(R.string.label_job_number, jobId))
                if (currentStep != null) append(" · ${currentStep.name}")
            },
            onBack = onBack,
            actions = {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = stringResource(R.string.action_search_logs),
                    tint = Primer.IconPrimary,
                    modifier = Modifier.size(20.dp).iconTap { findOpen = !findOpen },
                )
                Box {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.action_more),
                        tint = Primer.IconPrimary,
                        modifier = Modifier.size(20.dp).iconTap { menuOpen = true },
                    )
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_copy_step_log)) },
                            onClick = {
                                menuOpen = false
                                clipboard.setText(AnnotatedString(visibleLogText(segments, currentStep)))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_copy_full_log)) },
                            onClick = {
                                menuOpen = false
                                clipboard.setText(AnnotatedString(segments.joinToString("\n") { it.lines.joinToString("\n") }))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_open_in_browser)) },
                            onClick = { menuOpen = false },
                        )
                    }
                }
            },
        )

        when {
            loading -> DetailLoading()

            pending -> PendingLogNotice()

            failed -> DetailErrorRetry { forceTick++ }

            segments.isEmpty() -> DetailEmptyText(stringResource(R.string.state_job_no_output))

            else -> {
                StepChips(steps = steps, selected = selectedStep, onSelect = { selectedStep = it })

                if (findOpen) {
                    FindBar(
                        query = query,
                        onQueryChange = { query = it; hitCursor = 0 },
                        onClose = { findOpen = false; query = "" },
                    )
                }

                FilterBar(
                    errOnly = errOnly,
                    warnToo = warnToo,
                    onErrToggle = { errOnly = !errOnly; if (errOnly) warnToo = false },
                    onWarnToggle = { warnToo = !warnToo; if (warnToo) errOnly = false },
                )

                val visible = remember(segments, collapsed.keys.toSet(), errOnly, warnToo, query) {
                    buildLogItems(segments, collapsed.keys.toSet(), errOnly, warnToo, query)
                }
                val hits = remember(visible, query) {
                    if (query.isBlank()) emptyList() else visible.indices.filter { visible[it] is LogItem.Line && visible[it].matches(query) }
                }

                // 进入时若指定了步骤：滚到对应分组
                LaunchedEffect(selectedStep, visible.size) {
                    if (selectedStep == null || visible.isEmpty()) return@LaunchedEffect
                    val title = steps.firstOrNull { it.number == selectedStep }?.name ?: return@LaunchedEffect
                    val idx = visible.indexOfFirst { it is LogItem.Header && segmentMatchesStep(it.title, title) }
                    if (idx >= 0) listState.animateScrollToItem(idx)
                }

                Box(Modifier.weight(1f).background(CodeSyntax.CodeBg)) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(visible) { index, item ->
                            when (item) {
                                is LogItem.Header -> SegmentHeaderRow(
                                    title = item.title,
                                    count = item.count,
                                    collapsed = collapsed[item.segIndex] == true,
                                    onToggle = { collapsed[item.segIndex] = collapsed[item.segIndex] != true },
                                )

                                is LogItem.Line -> LogLineRow(
                                    item = item,
                                    hit = query.isNotBlank() && item.matches(query),
                                    current = hits.getOrNull(hitCursor) == index,
                                )
                            }
                        }
                        item { Spacer(Modifier.height(16.dp)) }
                    }
                }

                LogFootBar(
                    lines = visible.count { it is LogItem.Line },
                    hits = hits.size,
                    hitCursor = hitCursor,
                    onPrev = { hitCursor = if (hits.isEmpty()) 0 else (hitCursor - 1 + hits.size) % hits.size },
                    onNext = { hitCursor = if (hits.isEmpty()) 0 else (hitCursor + 1) % hits.size },
                    onJump = { hits.getOrNull(hitCursor)?.let { idx -> scope.launch { listState.animateScrollToItem(idx) } } },
                )
            }
        }
    }
}

// ── 日志行模型（渲染与搜索都基于它，过滤后仍保留原行号） ──

internal sealed interface LogItem {
    fun matches(q: String): Boolean
    data class Header(val segIndex: Int, val title: String, val count: Int) : LogItem {
        override fun matches(q: String) = title.contains(q, ignoreCase = true)
    }
    data class Line(val segIndex: Int, val lineNo: Int, val text: String, val level: LogLineLevel) : LogItem {
        override fun matches(q: String) = text.contains(q, ignoreCase = true)
    }
}

/** 按当前折叠 / 过滤 / 搜索条件把分段摊平成可渲染的列表。 */
internal fun buildLogItems(
    segments: List<LogSegment>,
    collapsed: Set<Int>,
    errOnly: Boolean,
    warnToo: Boolean,
    query: String,
): List<LogItem> {
    val out = mutableListOf<LogItem>()
    segments.forEachIndexed { segIndex, seg ->
        val lines = seg.lines.mapIndexed { i, raw -> LogItem.Line(segIndex, i + 1, raw, logLineLevel(raw)) }
        val kept = when {
            errOnly -> lines.filter { it.level == LogLineLevel.ERROR }
            warnToo -> lines.filter { it.level != LogLineLevel.NORMAL }
            else -> lines
        }
        // 过滤把整段都滤没了就不显示段头（否则满屏空标题）
        if (kept.isEmpty() && (errOnly || warnToo)) return@forEachIndexed
        out += LogItem.Header(segIndex, seg.title, kept.size)
        if (segIndex !in collapsed) out += kept
    }
    return out
}

/** 当前选中步骤那一段的原文（「复制当前步骤日志」用）。 */
internal fun visibleLogText(segments: List<LogSegment>, step: JobStep?): String {
    if (step == null) return segments.joinToString("\n") { it.lines.joinToString("\n") }
    val seg = segments.firstOrNull { segmentMatchesStep(it.title, step.name) } ?: return ""
    return seg.lines.joinToString("\n")
}

// ── 小组件 ──

@Composable
private fun PendingLogNotice() {
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Primer.Orange500))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.state_job_running), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.note_job_log_pending),
            fontSize = 12.sp,
            color = Primer.TextTertiary,
            lineHeight = 18.sp,
        )
    }
}

@Composable
private fun StepChips(steps: List<JobStep>, selected: Long?, onSelect: (Long) -> Unit) {
    if (steps.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .background(Primer.BackgroundPrimary).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        steps.forEach { step ->
            val on = step.number == selected
            Row(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (on) Primer.InfoSurfaceSoft else Primer.BackgroundPrimary)
                    .border(1.dp, if (on) Primer.Blue500 else Primer.Border, RoundedCornerShape(999.dp))
                    .clickable { onSelect(step.number) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(runDotColor(step.status, step.conclusion)))
                Spacer(Modifier.width(6.dp))
                Text(
                    "${step.number}. ${step.name}",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (on) Primer.AccentText else Primer.TextSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun FindBar(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Primer.BackgroundPrimary)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            placeholder = { Text(stringResource(R.string.hint_search_log), fontSize = 12.5.sp) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Primer.Gray100,
                unfocusedContainerColor = Primer.Gray100,
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Filled.Close,
            contentDescription = stringResource(R.string.action_close_search),
            tint = Primer.IconSecondary,
            modifier = Modifier.size(18.dp).iconTap { onClose() },
        )
    }
}

@Composable
private fun FilterBar(
    errOnly: Boolean,
    warnToo: Boolean,
    onErrToggle: () -> Unit,
    onWarnToggle: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(Primer.BackgroundPrimary)
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(stringResource(R.string.filter_errors_only), errOnly, onErrToggle)
        FilterChip(stringResource(R.string.filter_include_warnings), warnToo, onWarnToggle)
    }
}

@Composable
private fun FilterChip(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (on) Primer.InfoSurfaceSoft else Primer.BackgroundPrimary)
            .border(1.dp, if (on) Primer.Blue500 else Primer.Border, RoundedCornerShape(999.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (on) Primer.AccentText else Primer.TextSecondary,
        )
    }
}

@Composable
private fun SegmentHeaderRow(title: String, count: Int, collapsed: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Primer.Gray150).clickable { onToggle() }
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (collapsed) "▸" else "▾", fontSize = 10.sp, color = Primer.TextSecondary)
        Spacer(Modifier.width(6.dp))
        Text(
            title,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            color = Primer.TextSecondary,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(pluralStringResource(R.plurals.label_line_count, count, count), fontSize = 10.5.sp, color = Primer.TextTertiary)
    }
}

@Composable
private fun LogLineRow(item: LogItem.Line, hit: Boolean, current: Boolean) {
    val bg = when {
        item.level == LogLineLevel.ERROR -> Primer.Red100
        item.level == LogLineLevel.WARNING -> Primer.WarningSurface
        hit -> CodeSyntax.MatchBg
        else -> androidx.compose.ui.graphics.Color.Transparent
    }
    Row(
        Modifier.fillMaxWidth().background(bg).padding(horizontal = 8.dp),
    ) {
        Text(
            item.lineNo.toString(),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = Primer.TextTertiary,
            modifier = Modifier.width(34.dp),
        )
        Text(
            item.text.ifEmpty { " " },
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = if (current) Primer.AccentText else Primer.TextPrimary,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun LogFootBar(
    lines: Int,
    hits: Int,
    hitCursor: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onJump: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(Primer.BackgroundPrimary)
            .border(1.dp, Primer.Gray150)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (hits > 0) stringResource(R.string.label_match_position, hitCursor + 1, hits) else stringResource(R.string.label_lines_count_alt, lines),
            fontSize = 11.5.sp,
            color = Primer.TextTertiary,
            modifier = Modifier.weight(1f),
        )
        if (hits > 0) {
            Text("↑", fontSize = 13.sp, color = Primer.Link, modifier = Modifier.iconTap { onPrev() }.padding(horizontal = 8.dp))
            Text("↓", fontSize = 13.sp, color = Primer.Link, modifier = Modifier.iconTap { onNext() }.padding(horizontal = 8.dp))
            Text(stringResource(R.string.action_locate), fontSize = 11.5.sp, color = Primer.Link, modifier = Modifier.iconTap { onJump() }.padding(horizontal = 4.dp))
        }
        if (lines > 40) {
            Text(stringResource(R.string.action_jump_latest), fontSize = 11.5.sp, color = Primer.Link, modifier = Modifier.iconTap { onJump() })
        }
    }
}
