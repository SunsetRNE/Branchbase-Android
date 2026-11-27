package com.branchbase.ui.repository

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Commit
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.R
import com.branchbase.cache.PageCache
import com.branchbase.cache.SearchCacheDatabase
import com.branchbase.cache.SearchCacheManager
import com.branchbase.core.RustBridge
import com.branchbase.ui.CardDrawer
import com.branchbase.ui.CardSegments
import com.branchbase.ui.TintChip
import com.branchbase.ui.TintTag
import com.branchbase.ui.cardSurface
import com.branchbase.ui.resolve
import com.branchbase.ui.navigation.BranchbaseLazyColumn
import com.branchbase.ui.theme.CodeSyntax
import com.branchbase.ui.theme.ElementMotion
import com.branchbase.ui.theme.Primer
import com.branchbase.ui.theme.TintRole
import com.branchbase.ui.theme.iconTap
import com.branchbase.ui.theme.textColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 单个文件默认展开的代码片段行数上限（超出需手动「展开全部」）。 */
private const val SNIPPET_PREVIEW_LINES = 120

/**
 * 分支对比页：显示两个分支的**代码片段差异**。
 *
 * 文件对比（Compare branches）：
 * - 选择器：一张 48dp 浮层卡（基准 / 互换 / 对比），不再是两个描边字段；
 * - 摘要：`比较 base ← head` + 三语义统计（领先 / 落后 / 文件数）；
 * - 提交：一张折叠卡，展开后逐行列出，不再占一整段无卡列表；
 * - 文件：每个差异一张浮层卡，文件头 36dp、diff 用共享的 [DiffLineRow] 紧凑档。
 *
 * GitHub 边界：单次最多 300 个文件；超大 diff 不返回 `patch`（显示「无代码片段」提示）。
 */
@Composable
fun BranchCompareScreen(
    sessionJson: String,
    owner: String,
    repo: String,
    initialBase: String,
    initialHead: String,
    /** 当前是否本地仓库模式：只用来决定 0 文件卡片右侧那个入口是「提交模式」还是「本地仓库」。 */
    localCommitMode: Boolean,
    onOpenCommitMode: () -> Unit,
    onOpenLocalRepo: () -> Unit,
    onBack: () -> Unit,
    onOpenFile: (path: String) -> Unit,
) {
    val context = LocalContext.current
    val host = remember(sessionJson) {
        runCatching { org.json.JSONObject(sessionJson).optString("host", "github.com") }.getOrDefault("github.com")
    }
    val token = remember(sessionJson) {
        runCatching { org.json.JSONObject(sessionJson).optJSONObject("token")?.optString("access_token").orEmpty() }
            .getOrDefault("")
    }

    var branches by remember { mutableStateOf<List<String>>(emptyList()) }
    var base by remember(initialBase) { mutableStateOf(initialBase) }
    var head by remember(initialHead) { mutableStateOf(initialHead) }
    // 选择分支的抽屉目标（null = 抽屉关着）。两个选择器共用同一份抽屉，不再各挂一个 DropdownMenu。
    var picker by remember { mutableStateOf<BranchPickerTarget?>(null) }

    var result by remember { mutableStateOf<CompareResult?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showAllLines by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showCommits by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }

    // 分支名列表：与分支管理页共用 branchListKey（同一份服务端数据），进页面先直出再回源
    LaunchedEffect(owner, repo, reloadKey) {
        val manager = SearchCacheManager(SearchCacheDatabase.getInstance(context).searchCacheDao())
        val cacheKey = PageCache.branchListKey(owner, repo)
        // 「刷新」按钮同时刷新分支名列表：忽略缓存强制回源
        val force = reloadKey > 0

        PageCache.cachedFirst(manager, cacheKey, PageCache.TYPE_DETAIL, force)?.let { cached ->
            val names = withContext(Dispatchers.IO) { parseBranches(cached).map { b -> b.name } }
            if (names.isNotEmpty()) branches = names
        }
        PageCache.refresh(manager, cacheKey, PageCache.TYPE_DETAIL, force) {
            RustBridge.listBranches(host, token, owner, repo)
        }?.let { json ->
            val names = withContext(Dispatchers.IO) { parseBranches(json).map { b -> b.name } }
            if (names.isNotEmpty()) branches = names
        }
    }

    LaunchedEffect(owner, repo, base, head, reloadKey) {
        if (base.isBlank() || head.isBlank() || base == head) {
            result = null
            loading = false
            error = if (base == head) context.getString(R.string.error_choose_two_branches) else null
            return@LaunchedEffect
        }
        loading = true
        error = null
        expanded = emptySet()
        // 手动刷新：忽略缓存强制回源
        val force = reloadKey > 0
        val manager = SearchCacheManager(SearchCacheDatabase.getInstance(context).searchCacheDao())
        // 键含 base 与 head：切分支即换键，不会复用另一对分支的旧结果
        val cacheKey = PageCache.compareKey(owner, repo, base, head)
        // 「本次是否已拿到可展示数据」：直出或回源任一成功即为 true
        var shown = false

        // ① 先直出（含过期数据）：返回再进、切回看过的分支对都立即有内容
        PageCache.cachedFirst(manager, cacheKey, PageCache.TYPE_DETAIL, force)?.let { cached ->
            val cachedResult = withContext(Dispatchers.IO) { parseCompareResult(cached) }
            if (cachedResult != null) {
                result = cachedResult
                // 默认展开第一个文件，进入页面即可看到代码片段
                cachedResult.files.firstOrNull()?.let { expanded = setOf(it.filename) }
                shown = true
                loading = false
            }
        }

        // ② 回源刷新（缓存未过期时直接复用，不重复联网）
        val json = PageCache.refresh(manager, cacheKey, PageCache.TYPE_DETAIL, force) {
            RustBridge.compareBranches(host, token, owner, repo, base, head)
        }
        val parsed = withContext(Dispatchers.IO) { parseCompareResult(json) }
        if (parsed != null) {
            result = parsed
            shown = true
            // 默认展开第一个文件，进入页面即可看到代码片段
            parsed.files.firstOrNull()?.let { expanded = setOf(it.filename) }
        }
        // 与改造前一致：既无直出又回源失败 → 清空并给出原错误文案
        if (!shown) {
            result = null
            error = context.getString(R.string.error_compare_failed, base, head)
        }
        loading = false
    }

    Column(
        Modifier.fillMaxSize().background(Primer.BackgroundPrimary)
            .statusBarsPadding().navigationBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = Primer.IconPrimary,
                modifier = Modifier.size(24.dp).iconTap { onBack() },
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.label_branch_compare), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
                Text("$owner/$repo", fontSize = 11.sp, color = Primer.TextTertiary, maxLines = 1)
            }
            Text(
                stringResource(R.string.action_refresh), fontSize = 12.5.sp, color = Primer.Blue500,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(enabled = !loading) { reloadKey++ }
                    .padding(horizontal = 4.dp, vertical = 5.dp),
            )
        }

        // 选择器：一张 48dp 浮层卡（比较基准 ⇄ 对比分支）
        Row(
            Modifier.fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp)
                .heightIn(min = 48.dp)
                .cardSurface()
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BranchChip(
                label = stringResource(R.string.label_compare_base),
                value = base,
                onClick = { picker = BranchPickerTarget.Base },
                modifier = Modifier.weight(1f),
            )
            Box(Modifier.padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                BranchSwitchButton {
                    val t = base; base = head; head = t
                }
            }
            BranchChip(
                label = stringResource(R.string.action_compare_branches),
                value = head,
                onClick = { picker = BranchPickerTarget.Head },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(4.dp))

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Primer.Blue500)
            }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(error!!, fontSize = 13.sp, color = Primer.TextTertiary, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
            }
            result != null -> CompareBody(
                result = result!!,
                base = base,
                head = head,
                expanded = expanded,
                onToggleFile = { name ->
                    expanded = if (name in expanded) expanded - name else expanded + name
                },
                showAllLines = showAllLines,
                onShowAll = { name -> showAllLines = showAllLines + name },
                showCommits = showCommits,
                onToggleCommits = { showCommits = !showCommits },
                localCommitMode = localCommitMode,
                onOpenCommitMode = onOpenCommitMode,
                onOpenLocalRepo = onOpenLocalRepo,
                onOpenFile = onOpenFile,
            )
        }
    }

    // 分支选择抽屉：两个按钮拉起**同一个**抽屉；默认分段不同，列表内容相同。
    // 抽屉里的 CardSegments 负责切换「正在改比较基准还是对比分支」。
    picker?.let { target ->
        BranchPickerDrawer(
            segment = target,
            onSegmentChange = { picker = it },
            branches = branches,
            base = base,
            head = head,
            onPick = { name ->
                if (picker == BranchPickerTarget.Base) base = name else head = name
            },
            onDismiss = { picker = null },
        )
    }
}

/** 对比页的分支选择目标：基准 / 对比。 */
private enum class BranchPickerTarget { Base, Head }

@Composable
private fun CompareBody(
    result: CompareResult,
    base: String,
    head: String,
    expanded: Set<String>,
    onToggleFile: (String) -> Unit,
    showAllLines: Set<String>,
    onShowAll: (String) -> Unit,
    showCommits: Boolean,
    onToggleCommits: () -> Unit,
    localCommitMode: Boolean,
    onOpenCommitMode: () -> Unit,
    onOpenLocalRepo: () -> Unit,
    onOpenFile: (String) -> Unit,
) {
    BranchbaseLazyColumn(Modifier.fillMaxSize()) {
        item { CompareSummaryCard(result, base, head) }

        // 提交列表（默认折叠）：一张卡，展开后卡内逐行
        if (result.commits.isNotEmpty()) {
            item {
                CompareCommitsCard(
                    commits = result.commits,
                    showCommits = showCommits,
                    onToggle = onToggleCommits,
                )
            }
        }

        // 文件差异
        if (result.files.isEmpty()) {
            item {
                if (result.identical) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.state_no_file_differences),
                            fontSize = 12.5.sp, color = Primer.TextTertiary, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                    }
                } else {
                    CompareNoFilesCard(
                        localCommitMode = localCommitMode,
                        onOpenCommitMode = onOpenCommitMode,
                        onOpenLocalRepo = onOpenLocalRepo,
                    )
                }
            }
        } else {
            items(result.files, key = { it.filename }) { file ->
                CompareFileCard(
                    file = file,
                    expanded = file.filename in expanded,
                    showAll = file.filename in showAllLines,
                    onToggle = { onToggleFile(file.filename) },
                    onShowAll = { onShowAll(file.filename) },
                    onOpenFile = { onOpenFile(file.filename) },
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

/**
 * 对比摘要卡：`比较 base ← head` + 三语义统计。
 *
 * 颜色只表类别：领先 `SUCCESS`、落后 `DANGER`、文件数 `ACCENT`；分叉用 `DONE` 小标签。
 * 两个分支内容一致时只给一句结论，不摆一排 0。
 */
@Composable
private fun CompareSummaryCard(result: CompareResult, base: String, head: String) {
    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .cardSurface()
            .padding(horizontal = 11.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.label_compare_arrow, base, head),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = Primer.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (result.diverged) {
                Spacer(Modifier.width(7.dp))
                TintTag(stringResource(R.string.state_diverged), TintRole.DONE)
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Primer.Gray200))
        Spacer(Modifier.height(6.dp))
        if (result.identical) {
            Text(
                stringResource(R.string.state_no_differences_branches),
                fontSize = 11.sp, color = Primer.TextSecondary,
            )
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CompareStat(
                    icon = Icons.Filled.ArrowUpward,
                    label = stringResource(R.string.label_compare_ahead),
                    value = result.aheadBy,
                    role = TintRole.SUCCESS,
                )
                Spacer(Modifier.width(13.dp))
                CompareStat(
                    icon = Icons.Filled.ArrowDownward,
                    label = stringResource(R.string.label_compare_behind),
                    value = result.behindBy,
                    role = TintRole.DANGER,
                )
                Spacer(Modifier.weight(1f))
                CompareStat(
                    icon = Icons.Filled.Description,
                    label = stringResource(R.string.label_compare_files_unit),
                    value = result.files.size,
                    role = TintRole.ACCENT,
                    valueFirst = true,
                )
            }
        }
    }
}

/** 一枚摘要统计：图标 + 文案 + 数字，全部取同一 `TintRole` 的文字色。 */
@Composable
private fun CompareStat(
    icon: ImageVector,
    label: String,
    value: Int,
    role: TintRole,
    valueFirst: Boolean = false,
) {
    val ink = role.textColor()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(12.dp))
        if (valueFirst) {
            Text(value.toString(), fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = ink, maxLines = 1)
            Text(label, fontSize = 9.5.sp, color = ink, maxLines = 1)
        } else {
            Text(label, fontSize = 9.5.sp, color = ink, maxLines = 1)
            Text(value.toString(), fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = ink, maxLines = 1)
        }
    }
}

/** 提交卡：折叠时一行 36dp，展开后卡内逐行；换分支时由调用方的 `showCommits` 保留。 */
@Composable
private fun CompareCommitsCard(
    commits: List<CompareCommit>,
    showCommits: Boolean,
    onToggle: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .cardSurface(),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = 36.dp)
                .clickable { onToggle() }
                .padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TintChip(Icons.Filled.Commit, TintRole.NEUTRAL_SUBTLE, size = 20.dp, corner = 6.dp, iconSize = 12.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.label_compare_commits, commits.size),
                fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (showCommits) stringResource(R.string.action_collapse) else stringResource(R.string.action_expand),
                fontSize = 10.5.sp, color = Primer.Blue500,
            )
            Spacer(Modifier.width(3.dp))
            Icon(
                Icons.Filled.ChevronRight, null, tint = Primer.Blue500,
                modifier = Modifier.size(12.dp).rotate(if (showCommits) 90f else 0f),
            )
        }
        AnimatedVisibility(
            visible = showCommits,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Primer.Gray200))
                commits.forEach { c -> CompareCommitRow(c) }
            }
        }
    }
}

@Composable
private fun CompareCommitRow(c: CompareCommit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            c.shortSha, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace,
            color = Primer.Blue500, modifier = Modifier.width(50.dp),
        )
        Text(
            c.subject.ifBlank { stringResource(R.string.state_no_commit_message) },
            fontSize = 10.5.sp, color = Primer.TextPrimary,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(6.dp))
        Text(c.date, fontSize = 9.sp, color = Primer.TextTertiary)
    }
}

/**
 * 一个文件的差异卡：**文件头 36dp + 可展开的 diff**。
 *
 * 文件头把「状态标签 / 文件名 / 目录 / +N -M」压进一行（目录降为次行），
 * diff 走共享 [DiffLineRow] 的紧凑档；整张卡由 `cardSurface()` 统一裁切。
 */
@Composable
private fun CompareFileCard(
    file: CompareFile,
    expanded: Boolean,
    showAll: Boolean,
    onToggle: () -> Unit,
    onShowAll: () -> Unit,
    onOpenFile: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .cardSurface(),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = 36.dp)
                .clickable { onToggle() }
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.ChevronRight, null, tint = Primer.TextTertiary,
                modifier = Modifier.size(11.dp).rotate(if (expanded) 90f else 0f),
            )
            Spacer(Modifier.width(6.dp))
            CompareStatusTag(file)
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    file.name,
                    fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = Primer.TextPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (file.dir.isNotEmpty()) {
                    Spacer(Modifier.height(1.dp))
                    Text(
                        file.dir, fontSize = 9.sp, color = Primer.TextTertiary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            Text(
                "+${file.additions}", fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                color = Primer.SuccessText,
            )
            Spacer(Modifier.width(5.dp))
            Text(
                "-${file.deletions}", fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                color = Primer.DangerText,
            )
        }

        if (expanded) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Primer.Gray200))
            if (!file.hasPatch) {
                Text(
                    stringResource(R.string.note_diff_too_large),
                    fontSize = 11.sp, color = Primer.TextTertiary, lineHeight = 17.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            } else {
                // 渲染走共享的 DiffLineRow（DiffLines.kt）：本地 diff 页画的是同一份东西
                val lines = remember(file.patch) { parseUnifiedDiff(file.patch) }
                val shown = if (showAll) lines else lines.take(SNIPPET_PREVIEW_LINES)
                Column(Modifier.fillMaxWidth().background(CodeSyntax.CodeBg)) {
                    shown.forEach { line -> DiffLineRow(line, compact = true) }
                    if (!showAll && lines.size > shown.size) {
                        Box(
                            Modifier.fillMaxWidth().clickable { onShowAll() }.padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(R.string.action_expand_remaining_lines, lines.size - shown.size),
                                fontSize = 11.sp, color = Primer.Blue500,
                            )
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    stringResource(R.string.action_open_file),
                    fontSize = 10.5.sp, color = Primer.Blue500,
                    modifier = Modifier.clip(RoundedCornerShape(5.dp)).clickable { onOpenFile() }
                        .padding(horizontal = 2.dp, vertical = 3.dp),
                )
            }
        }
    }
}

/** 文件状态：新增 `SUCCESS` / 删除 `DANGER` / 重命名 `DONE` / 其余中性。 */
@Composable
private fun CompareStatusTag(file: CompareFile) {
    val role = when (file.status) {
        "added" -> TintRole.SUCCESS
        "removed" -> TintRole.DANGER
        "renamed" -> TintRole.DONE
        else -> TintRole.NEUTRAL_SUBTLE
    }
    val icon = when (file.status) {
        "added" -> Icons.Filled.Add
        "removed" -> Icons.Filled.Remove
        "renamed" -> Icons.Filled.SwapHoriz
        else -> Icons.Filled.Edit
    }
    TintTag(file.statusLabel.resolve(), role, icon = icon)
}

/**
 * 0 文件时的提示卡：**左侧是新文案，右侧是随「提交模式」变化的入口**。
 *
 * 远端（单文件 / 多文件 / 未配置）→「提交模式」；本地仓库 →「本地仓库」。
 * 两个入口都跳到设置里的对应页面，而不是在这里重写一份配置。
 */
@Composable
private fun CompareNoFilesCard(
    localCommitMode: Boolean,
    onOpenCommitMode: () -> Unit,
    onOpenLocalRepo: () -> Unit,
) {
    val local = localCommitMode
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .cardSurface()
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.note_compare_file_limit),
            fontSize = 12.sp, lineHeight = 17.sp, color = Primer.TextSecondary,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        CompareNoFilesEntry(
            label = stringResource(if (local) R.string.nav_local_repo else R.string.nav_commit_mode),
            onClick = { if (local) onOpenLocalRepo() else onOpenCommitMode() },
        )
    }
}

/** 0 文件卡右侧的入口：静止 ACCENT 12%，按下加深到 20%（与选择器同一套「可点」语义）。 */
@Composable
private fun CompareNoFilesEntry(label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fill by animateColorAsState(
        targetValue = if (pressed) Primer.Blue500.copy(alpha = 0.20f) else Primer.Blue500.copy(alpha = 0.12f),
        animationSpec = tween(ElementMotion.PRESS_MS),
        label = "compare-no-files-entry-press",
    )
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(fill)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = Primer.AccentText,
            maxLines = 1,
        )
    }
}

@Composable
private fun BranchChip(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 红框里那块不再裸着：整个字段（标签 + 值）常驻 ACCENT 8% 底，按下 16%。
    // 底色直接表「可切换」，而不是等用户按下去才看到一层灰。
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fill by animateColorAsState(
        targetValue = if (pressed) Primer.Blue500.copy(alpha = 0.16f) else Primer.Blue500.copy(alpha = 0.08f),
        animationSpec = tween(ElementMotion.PRESS_MS),
        label = "branch-chip-press",
    )
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(fill)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(label, fontSize = 9.sp, lineHeight = 11.sp, color = Primer.TextTertiary)
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                value.ifBlank { stringResource(R.string.state_please_choose) },
                fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                color = Primer.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(3.dp))
            Text("▾", fontSize = 9.sp, color = Primer.TextTertiary)
        }
    }
}

/** 「切换」按钮：替代原来的上下箭头圆钮；文字比图标更直白，底色与两个选择器同一套 ACCENT。 */
@Composable
private fun BranchSwitchButton(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fill by animateColorAsState(
        targetValue = if (pressed) Primer.Blue500.copy(alpha = 0.20f) else Primer.Blue500.copy(alpha = 0.12f),
        animationSpec = tween(ElementMotion.PRESS_MS),
        label = "branch-switch-press",
    )
    Row(
        Modifier
            .heightIn(min = 28.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(fill)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.action_switch),
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = Primer.AccentText,
            maxLines = 1,
        )
    }
}

/**
 * 分支选择抽屉：**自绘 [CardDrawer] + 可滚动 + 每行一张浮层卡**。
 *
 * 不再用 `DropdownMenu`：分支名一多（截图里 14 条），Material 菜单会变成一张顶到屏幕
 * 上沿的白板，长名还会折行；抽屉有固定最大高度、能滚，卡片之间的 2dp 阴影也把「一行一行」
 * 读出来。选中的那一行用 `ACCENT` 牌 + 蓝字 + 勾。
 */
@Composable
private fun BranchPickerDrawer(
    segment: BranchPickerTarget,
    onSegmentChange: (BranchPickerTarget) -> Unit,
    branches: List<String>,
    base: String,
    head: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val selected = if (segment == BranchPickerTarget.Base) base else head
    CardDrawer(title = stringResource(R.string.label_branch_section), onDismiss = onDismiss) { close ->
        // 两个选择器拉起同一个抽屉；分段控件决定「现在改的是哪一侧」，列表内容不变。
        CardSegments(
            labels = listOf(
                stringResource(R.string.label_compare_base),
                stringResource(R.string.action_compare_branches),
            ),
            selectedIndex = segment.ordinal,
            onSelect = { onSegmentChange(BranchPickerTarget.entries[it]) },
        )
        Spacer(Modifier.height(8.dp))
        // 分支可能上百条：用 LazyColumn 只组合可见行，而不是把整份列表一次性铺进 Column。
        // 高度按「一行 42dp / 两行长名 52dp」取个上限：少时抽屉贴内容，多时内部滚动。
        val listHeight = (branches.size.coerceIn(1, 10) * 52).coerceAtMost(420).dp
        LazyColumn(Modifier.fillMaxWidth().height(listHeight)) {
            items(branches, key = { it }) { b ->
                BranchPickerRow(
                    name = b,
                    selected = b == selected,
                    onClick = { onPick(b); close() },
                )
            }
        }
    }
}

@Composable
private fun BranchPickerRow(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .padding(vertical = 3.dp)
            .cardSurface(shape = RoundedCornerShape(10.dp), elevation = 2.dp)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TintChip(
            Icons.AutoMirrored.Filled.CallSplit,
            role = if (selected) TintRole.ACCENT else TintRole.NEUTRAL_SUBTLE,
            size = 22.dp,
            corner = 7.dp,
            iconSize = 13.dp,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            name,
            fontSize = 12.5.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Primer.Blue500 else Primer.TextPrimary,
            // 截图里 dependabot/… 这类长名在旧 DropdownMenu 里是折行的；抽屉里保留两行，
            // 至少能让用户分清前缀相同的分支；再长才省略。
            maxLines = 2,
            lineHeight = 16.sp,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = Primer.Blue500, modifier = Modifier.size(16.dp))
        }
    }
}
