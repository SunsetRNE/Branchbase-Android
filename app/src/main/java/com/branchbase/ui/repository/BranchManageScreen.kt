package com.branchbase.ui.repository

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.CompareArrows
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.cache.PageCache
import com.branchbase.cache.SearchCacheDatabase
import com.branchbase.cache.SearchCacheManager
import com.branchbase.core.RustBridge
import com.branchbase.ui.log.Logger
import com.branchbase.ui.theme.iconTap
import com.branchbase.ui.theme.Primer
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
    var feedback by remember { mutableStateOf<String?>(null) }
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
            error = "暂无分支或加载失败"
        }
        loading = false
    }

    fun createBranch(name: String, base: String) {
        if (name.isBlank()) { feedback = "请输入分支名"; return }
        if (branches.any { it.name == name }) { feedback = "分支已存在：$name"; return }
        scope.launch {
            busy = true
            feedback = null
            val sha = withContext(Dispatchers.IO) {
                RustBridge.getRefSha(host, token, owner, repo, base.ifBlank { default })
            }
            if (sha == null) {
                busy = false
                feedback = "无法读取来源分支 ${base.ifBlank { default }} 的提交"
                return@launch
            }
            val err = withContext(Dispatchers.IO) {
                RustBridge.createBranch(host, token, owner, repo, name, sha)
            }
            Logger.net("create branch $name from $base (sha=${sha.take(7)}) → ${err ?: "成功"}", "GitHubAPI")
            busy = false
            if (err == null) {
                feedback = "已创建分支 $name"
                invalidateBranchListCache()
                reloadKey++
            } else {
                feedback = "创建失败：$err"
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
                feedback = "已删除分支 $name"
                invalidateBranchListCache()
                reloadKey++
            } else {
                feedback = "删除失败：$err"
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
                feedback = "默认分支已改为 $name"
            } else {
                feedback = "修改失败：$err"
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
                Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Primer.IconPrimary,
                modifier = Modifier.size(24.dp).iconTap { onBack() },
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("分支管理", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
                Text("$owner/$repo", fontSize = 11.sp, color = Primer.TextTertiary, maxLines = 1)
            }
            Icon(
                Icons.Filled.Refresh, "刷新", tint = Primer.IconPrimary,
                modifier = Modifier.size(22.dp).iconTap(enabled = !busy) { reloadKey++ },
            )
        }

        // 对比入口：默认分支 ← 当前选择的分支
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Primer.Gray150)
                .clickable(enabled = branches.size >= 2) {
                    val head = branches.firstOrNull { it.name != default }?.name ?: return@clickable
                    onOpenCompare(default, head)
                }
                .padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.CompareArrows, null, tint = Primer.Blue500,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("对比分支", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
                    Text(
                        if (branches.size >= 2) "查看两个分支的提交与代码片段差异" else "至少需要两个分支",
                        fontSize = 11.sp, color = Primer.TextTertiary,
                    )
                }
                Text("›", fontSize = 15.sp, color = Primer.TextTertiary)
            }
        }

        feedback?.let {
            Text(
                it,
                fontSize = 12.sp,
                color = if (it.startsWith("已") ) Primer.Green500 else Primer.Red500,
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
            else -> LazyColumn(Modifier.fillMaxSize()) {
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
                        Spacer(Modifier.height(8.dp))
                        Box(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .border(1.dp, Primer.Border, RoundedCornerShape(10.dp))
                                .clickable(enabled = !busy) {
                                    newBase = default
                                    newName = ""
                                    showCreate = true
                                }
                                .padding(vertical = 13.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("＋ 新建分支", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Primer.Blue500)
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("新建分支", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary) },
            text = {
                Column {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        placeholder = { Text("分支名，如 feature/login", fontSize = 12.sp, color = Primer.TextTertiary) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("来源分支", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextSecondary)
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
                ) { Text("创建", color = Primer.Blue500) }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("取消") } },
        )
    }

    confirmDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除分支 $target？", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary) },
            text = {
                Text(
                    "会删除远端分支，无法通过界面恢复。若该分支有未合并的提交，" +
                        "删除后这些提交将不再被分支引用。",
                    fontSize = 12.sp, color = Primer.TextTertiary, lineHeight = 18.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = null; deleteBranch(target) }) {
                    Text("删除", color = Primer.Red500)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消") } },
        )
    }
}

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
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.CallSplit, null, tint = Primer.IconSecondary,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    branch.name,
                    fontSize = 13.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (isDefault) FontWeight.SemiBold else FontWeight.Normal,
                    color = Primer.TextPrimary,
                )
                if (isDefault) {
                    Spacer(Modifier.width(6.dp))
                    Tag("默认", Primer.Blue500, Primer.Gray150)
                }
                if (branch.protected) {
                    Spacer(Modifier.width(6.dp))
                    Tag("受保护", Primer.TextSecondary, Primer.Gray150)
                }
            }
        }
        Text(
            "对比",
            fontSize = 12.sp,
            color = Primer.Blue500,
            modifier = Modifier.clickable(enabled = !busy && !isDefault) { onCompare() },
        )
        if (canPush) {
            Spacer(Modifier.width(14.dp))
            Text(
                "设为默认",
                fontSize = 12.sp,
                color = if (isDefault) Primer.TextTertiary else Primer.Blue500,
                modifier = Modifier.clickable(enabled = !busy && !isDefault) { onSetDefault() },
            )
            if (!isDefault) {
                Spacer(Modifier.width(14.dp))
                val deletable = !branch.protected
                Text(
                    "删除",
                    fontSize = 12.sp,
                    color = if (deletable) Primer.Red500 else Primer.TextTertiary,
                    modifier = Modifier.clickable(enabled = !busy && deletable) { onDelete() },
                )
            }
        }
    }
}

@Composable
private fun Tag(text: String, fg: Color, bg: Color) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(text, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = fg)
    }
}
