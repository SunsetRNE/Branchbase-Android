package com.branchbase.ui.repository

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.R
import com.branchbase.cache.PageCache
import com.branchbase.cache.SearchCacheDatabase
import com.branchbase.cache.SearchCacheManager
import com.branchbase.core.RustBridge
import com.branchbase.core.engineErrorOrRaw
import com.branchbase.ui.FloatingCardButton
import com.branchbase.ui.TintChip
import com.branchbase.ui.TintTag
import com.branchbase.ui.cardSurface
import com.branchbase.ui.log.Logger
import com.branchbase.ui.navigation.BranchbaseLazyColumn
import com.branchbase.ui.theme.Primer
import com.branchbase.ui.theme.TintRole
import com.branchbase.ui.theme.iconTap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 分支管理页（服务端 API，不依赖本地 clone）。
 *
 * 分支管理与分支对比：
 * - 列表：默认分支 / 受保护分支有标记；每行可直接「对比」（默认分支 ← 该分支）
 * - 新建：指定来源分支（`GET git/ref` 取 sha → `POST git/refs`）
 * - 删除：默认分支与受保护分支不显示入口；删除前二次确认
 * - 设为默认：`PATCH /repos/{o}/{r}` 的 default_branch
 *
 * 权限：`canPush=false` 时隐藏新建/删除/设为默认（只保留只读的对比）。
 *
 * ## 1.4.97 的版面（`design/branch-pages-redesign`）
 *
 * 扁平行 → **浮层行卡**。三档状态用 `TintChip` + `TintTag` 的语义色：
 * 默认 `ACCENT` / 受保护 `WARNING` / 普通 `NEUTRAL_SUBTLE`。
 * 「设为默认 / 删除」从行尾三枚文字动作收进 `⋯`，把横向空间还给长分支名；
 * 写操作仍按 `canPush` 门控，删除对受保护分支仍不显示入口。
 */
@Composable
fun BranchManageScreen(
    sessionJson: String,
    owner: String,
    repo: String,
    defaultBranch: String,
    canPush: Boolean,
    onBack: () -> Unit,
    onOpenCompare: (base: String, head: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val host = remember(sessionJson) {
        runCatching { JSONObject(sessionJson).optString("host", "github.com") }.getOrDefault("github.com")
    }
    val token = remember(sessionJson) {
        runCatching { JSONObject(sessionJson).optJSONObject("token")?.optString("access_token").orEmpty() }
            .getOrDefault("")
    }

    var branches by remember { mutableStateOf<List<BranchItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<Feedback?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }

    // 当前默认分支（可在页内被「设为默认」改写，避免重新进页面才生效）
    var default by remember(defaultBranch) { mutableStateOf(defaultBranch) }

    // 新建分支弹窗
    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var baseMenu by remember { mutableStateOf(false) }
    var newBase by remember { mutableStateOf("") }

    // 删除确认
    var confirmDelete by remember { mutableStateOf<String?>(null) }

    /** 写操作（新建/删除/设为默认）成功后失效分支列表缓存，避免下次进页面看到改动前的列表。 */
    suspend fun invalidateBranchListCache() {
        SearchCacheManager(SearchCacheDatabase.getInstance(context).searchCacheDao())
            .delete(PageCache.branchListKey(owner, repo))
    }

    LaunchedEffect(owner, repo, reloadKey) {
        loading = true
        error = null
        // 手动刷新（reloadKey++）或写操作后的重载：忽略缓存，强制回源
        val force = reloadKey > 0
        val manager = SearchCacheManager(SearchCacheDatabase.getInstance(context).searchCacheDao())
        val cacheKey = PageCache.branchListKey(owner, repo)
        // 「本次是否已拿到可展示数据」：直出或回源任一成功即为 true
        var shown = false

        // ① 先直出（含过期数据）：从仓库页进列表、对比页返回再进都立即有内容
        PageCache.cachedFirst(manager, cacheKey, PageCache.TYPE_DETAIL, force)?.let { cached ->
            val parsed = withContext(Dispatchers.IO) { parseBranches(cached) }
            if (parsed.isNotEmpty()) {
                branches = parsed
                shown = true
                loading = false
            }
        }

        // ② 回源刷新（缓存未过期时直接复用，不重复联网）
        val json = PageCache.refresh(manager, cacheKey, PageCache.TYPE_DETAIL, force) {
            withContext(Dispatchers.IO) { RustBridge.listBranches(host, token, owner, repo) }
        }
        if (json != null) {
            val parsed = withContext(Dispatchers.IO) { parseBranches(json) }
            if (parsed.isNotEmpty()) {
                branches = parsed
                shown = true
            }
        }

        // 与改造前一致：没有可用缓存且回源也没拿到数据 → 清空并给出原错误文案
        if (!shown) {
            branches = emptyList()
            error = context.getString(R.string.state_no_branches_or_failed)
        }
        loading = false
    }

    fun createBranch(name: String, base: String) {
        if (name.isBlank()) { feedback = Feedback(context.getString(R.string.error_branch_name_required), ok = false); return }
        if (branches.any { it.name == name }) { feedback = Feedback(context.getString(R.string.error_branch_exists, name), ok = false); return }
        scope.launch {
            busy = true
            feedback = null
            val sha = withContext(Dispatchers.IO) {
                RustBridge.getRefSha(host, token, owner, repo, base.ifBlank { default })
            }
            if (sha == null) {
                busy = false
                feedback = Feedback(context.getString(R.string.error_cannot_read_source_branch, base.ifBlank { default }), ok = false)
                return@launch
            }
            val err = withContext(Dispatchers.IO) {
                RustBridge.createBranch(host, token, owner, repo, name, sha)
            }
            Logger.net("create branch $name from $base (sha=${sha.take(7)}) → ${err ?: "成功"}", "GitHubAPI")
            busy = false
            if (err == null) {
                feedback = Feedback(context.getString(R.string.state_branch_created, name), ok = true)
                invalidateBranchListCache()
                reloadKey++
            } else {
                feedback = Feedback(context.getString(R.string.error_creation_failed, context.engineErrorOrRaw(err)), ok = false)
            }
        }
    }

    fun deleteBranch(name: String) {
        scope.launch {
            busy = true
            feedback = null
            val err = withContext(Dispatchers.IO) { RustBridge.deleteBranch(host, token, owner, repo, name) }
            Logger.net("delete branch $name → ${err ?: "成功"}", "GitHubAPI")
            busy = false
            if (err == null) {
                feedback = Feedback(context.getString(R.string.state_branch_deleted, name), ok = true)
                invalidateBranchListCache()
                reloadKey++
            } else {
                feedback = Feedback(context.getString(R.string.error_delete_failed, context.engineErrorOrRaw(err)), ok = false)
            }
        }
    }

    fun setDefault(name: String) {
        scope.launch {
            busy = true
            feedback = null
            val err = withContext(Dispatchers.IO) {
                RustBridge.updateDefaultBranch(host, token, owner, repo, name)
            }
            Logger.net("set default branch $name → ${err ?: "成功"}", "GitHubAPI")
            busy = false
            if (err == null) {
                default = name
                // 页内直接改写 default（不重载），但缓存不能留旧列表
                invalidateBranchListCache()
                feedback = Feedback(context.getString(R.string.state_default_branch_changed, name), ok = true)
            } else {
                feedback = Feedback(context.getString(R.string.error_update_failed_err, context.engineErrorOrRaw(err)), ok = false)
            }
        }
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
                Text(stringResource(R.string.nav_branch_manage), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
                Text("$owner/$repo", fontSize = 11.sp, color = Primer.TextTertiary, maxLines = 1)
            }
            Icon(
                Icons.Filled.Refresh, stringResource(R.string.action_refresh), tint = Primer.IconPrimary,
                modifier = Modifier.size(22.dp).iconTap(enabled = !busy) { reloadKey++ },
            )
        }

        BranchCompareEntry(
            enabled = branches.size >= 2,
            hint = if (branches.size >= 2) {
                stringResource(R.string.note_compare_branches_hint)
            } else {
                stringResource(R.string.state_need_two_branches)
            },
            onClick = {
                branches.firstOrNull { it.name != default }?.let { onOpenCompare(default, it.name) }
            },
        )
        feedback?.let { fb ->
            Text(
                fb.text,
                fontSize = 12.sp,
                // 语气由产生方给出（见 Feedback），不从文案里猜：
                // 文案抽成资源后 `startsWith("已")` 在英文界面下永不成立
                color = if (fb.ok) Primer.Green500 else Primer.Red500,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Primer.Blue500)
            }
            error != null && branches.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(error!!, fontSize = 13.sp, color = Primer.TextTertiary)
            }
            else -> BranchbaseLazyColumn(Modifier.fillMaxSize()) {
                item { Spacer(Modifier.height(2.dp)) }
                item { BranchSectionHead(count = branches.size) }
                items(branches, key = { it.name }) { b ->
                    BranchManageRow(
                        branch = b,
                        isDefault = b.name == default,
                        canPush = canPush,
                        busy = busy,
                        onCompare = { onOpenCompare(default, b.name) },
                        onSetDefault = { setDefault(b.name) },
                        onDelete = { confirmDelete = b.name },
                    )
                }
                if (canPush) {
                    item {
                        NewBranchCard {
                            newBase = default
                            newName = ""
                            showCreate = true
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text(stringResource(R.string.action_new_branch), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary) },
            text = {
                Column {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.hint_branch_name), fontSize = 12.sp, color = Primer.TextTertiary) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.label_source_branch_name), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextSecondary)
                    Spacer(Modifier.height(6.dp))
                    Box {
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, Primer.Border, RoundedCornerShape(8.dp))
                                .clickable { baseMenu = true }
                                .padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                newBase.ifBlank { default },
                                fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                                color = Primer.TextPrimary, modifier = Modifier.weight(1f),
                            )
                            Text("▾", fontSize = 12.sp, color = Primer.TextTertiary)
                        }
                        DropdownMenu(expanded = baseMenu, onDismissRequest = { baseMenu = false }) {
                            branches.forEach { b ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            b.name, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                                            color = if (b.name == newBase) Primer.Blue500 else Primer.TextPrimary,
                                        )
                                    },
                                    onClick = { newBase = b.name; baseMenu = false },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newName.isNotBlank() && !busy,
                    onClick = {
                        val n = newName.trim()
                        val base = newBase.ifBlank { default }
                        showCreate = false
                        createBranch(n, base)
                    },
                ) { Text(stringResource(R.string.action_create), color = Primer.Blue500) }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    confirmDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.confirm_delete_branch, target), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary) },
            text = {
                Text(
                    stringResource(R.string.confirm_delete_remote_branch_body),
                    fontSize = 12.sp, color = Primer.TextTertiary, lineHeight = 18.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = null; deleteBranch(target) }) {
                    Text(stringResource(R.string.action_delete), color = Primer.Red500)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/**
 * 对比入口：一张 48dp 浮层卡（对齐 `design/branch-pages-redesign` 的 `entry`）。
 *
 * 分支不足两个时仍然显示这张卡，但**不可点** —— 给的是「为什么现在不能比」的说明，
 * 而不是一个点了没反应的死落点。
 */
@Composable
private fun BranchCompareEntry(
    enabled: Boolean,
    hint: String,
    onClick: () -> Unit,
) {
    if (enabled) {
        FloatingCardButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp).heightIn(min = 48.dp),
            contentPadding = PaddingValues(horizontal = 11.dp, vertical = 8.dp),
        ) {
            BranchCompareEntryContent(hint)
        }
    } else {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
                .heightIn(min = 48.dp)
                .cardSurface()
                .padding(horizontal = 11.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BranchCompareEntryContent(hint)
        }
    }
}

@Composable
private fun BranchCompareEntryContent(hint: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TintChip(Icons.AutoMirrored.Filled.CompareArrows, TintRole.ACCENT)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.action_compare_branches),
                fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary,
            )
            Spacer(Modifier.height(1.dp))
            Text(
                hint,
                fontSize = 10.5.sp, color = Primer.TextTertiary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.Filled.ChevronRight, null, tint = Primer.TextTertiary, modifier = Modifier.size(14.dp))
    }
}

/** 分支列表的区块头：`分支 · N` + 右侧「远端」。不占一张卡，不染色。 */
@Composable
private fun BranchSectionHead(count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.label_branch_section), fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary)
        Spacer(Modifier.width(5.dp))
        Box(
            Modifier.clip(RoundedCornerShape(5.dp)).background(Primer.Gray150).padding(horizontal = 5.dp, vertical = 1.dp),
        ) {
            Text(count.toString(), fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = Primer.TextTertiary)
        }
        Spacer(Modifier.weight(1f))
        Text(stringResource(R.string.label_branch_remote), fontSize = 9.5.sp, color = Primer.TextTertiary)
    }
}

/**
 * 一条分支：**38dp 浮层行卡**。
 *
 * - 行首 [TintChip] 的三档语义：默认 `ACCENT` / 受保护 `WARNING` / 普通 `NEUTRAL_SUBTLE`；
 * - 名称后的 [TintTag] 只说状态，不再给整行上色；
 * - 行尾只留「对比」；写操作收进 `⋯` 的 `DropdownMenu`（`canPush` 门控、受保护分支无删除）。
 */
@Composable
private fun BranchManageRow(
    branch: BranchItem,
    isDefault: Boolean,
    canPush: Boolean,
    busy: Boolean,
    onCompare: () -> Unit,
    onSetDefault: () -> Unit,
    onDelete: () -> Unit,
) {
    val role = when {
        isDefault -> TintRole.ACCENT
        branch.protected -> TintRole.WARNING
        else -> TintRole.NEUTRAL_SUBTLE
    }
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .heightIn(min = 38.dp)
            .cardSurface()
            .padding(start = 9.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TintChip(
            Icons.AutoMirrored.Filled.CallSplit,
            role,
            size = 20.dp,
            corner = 6.dp,
            iconSize = 12.dp,
        )
        Spacer(Modifier.width(8.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                branch.name,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (isDefault) FontWeight.SemiBold else FontWeight.Normal,
                color = Primer.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // fill = false：短名只占自己的宽度，默认 / 受保护标签紧跟在名字后面；
                // 长名仍可被压缩并省略（标签是 unweighted，先量宽度）。
                modifier = Modifier.weight(1f, fill = false),
            )
            if (isDefault) {
                Spacer(Modifier.width(5.dp))
                TintTag(stringResource(R.string.label_default), TintRole.ACCENT)
            }
            if (branch.protected) {
                Spacer(Modifier.width(5.dp))
                TintTag(stringResource(R.string.state_protected), TintRole.WARNING, icon = Icons.Filled.Lock)
            }
        }
        if (!isDefault) {
            Spacer(Modifier.width(2.dp))
            Text(
                stringResource(R.string.action_compare),
                fontSize = 11.sp,
                color = Primer.Blue500,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = !busy, onClick = onCompare)
                    .padding(horizontal = 3.dp, vertical = 5.dp),
            )
            if (canPush) {
                Box {
                    Box(
                        Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).iconTap(enabled = !busy) { menuOpen = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.MoreVert,
                            stringResource(R.string.label_more_actions),
                            tint = Primer.TextTertiary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_set_default), fontSize = 12.sp, color = Primer.TextPrimary) },
                            leadingIcon = {
                                Icon(Icons.Filled.Check, null, tint = Primer.IconSecondary, modifier = Modifier.size(16.dp))
                            },
                            onClick = { menuOpen = false; onSetDefault() },
                        )
                        if (!branch.protected) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_delete), fontSize = 12.sp, color = Primer.DangerText) },
                                leadingIcon = {
                                    Icon(Icons.Filled.Delete, null, tint = Primer.DangerText, modifier = Modifier.size(16.dp))
                                },
                                onClick = { menuOpen = false; onDelete() },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 新建分支：一张 38dp 浮层卡，替代原来占满整行的描边按钮。 */
@Composable
private fun NewBranchCard(onClick: () -> Unit) {
    FloatingCardButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 7.dp).heightIn(min = 38.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Add, null, tint = Primer.Blue500, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                stringResource(R.string.action_new_branch),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = Primer.Blue500,
            )
        }
    }
}
