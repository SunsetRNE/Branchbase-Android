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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import org.json.JSONArray
import org.json.JSONObject

/** 两个分支的比较结果（`GET /compare/{base}...{head}`）。 */
private data class CompareInfo(val aheadBy: Int, val behindBy: Int, val status: String)

private fun parseCompare(json: String?): CompareInfo? {
    if (json.isNullOrBlank()) return null
    return runCatching {
        val o = JSONObject(json)
        CompareInfo(
            aheadBy = o.optInt("ahead_by", 0),
            behindBy = o.optInt("behind_by", 0),
            status = o.optString("status"),
        )
    }.getOrNull()
}

/** 分支名列表（`GET /repos/{o}/{r}/branches`）：单个条目异常时跳过，不影响其余分支。 */
private fun parseBranchNames(json: String?): List<String> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }
        }
    }.getOrDefault(emptyList())
}

/**
 * 分支同步（服务端合并，无需本地 clone）。
 *
 * 对齐「A 分支内容同步到 B 分支」的诉求，三种模式：
 * - **合并**：`POST /merges`，保留历史、生成合并提交；冲突时 GitHub 回 409
 * - **仅快进**：目标必须是源的祖先（behind>0 且 ahead=0），直接移动 ref；否则拒绝
 * - **覆盖**：强制把目标指向源（`force=true`），**会丢目标分支独有提交**，需二次确认
 */
@Composable
fun BranchSyncScreen(
    sessionJson: String,
    owner: String,
    repo: String,
    onBack: () -> Unit,
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

    var branches by remember { mutableStateOf<List<String>>(emptyList()) }
    var source by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var mode by remember { mutableIntStateOf(0) }
    var compare by remember { mutableStateOf<CompareInfo?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var confirmOverwrite by remember { mutableStateOf(false) }
    var sourceMenu by remember { mutableStateOf(false) }
    var targetMenu by remember { mutableStateOf(false) }
    // 同步成功自增：对比缓存已失效，用它重跑下面的比较预览，避免卡片留着同步前的领先/落后
    var syncTick by remember { mutableIntStateOf(0) }

    /** 自动选中默认源/目标；用户已选且选择仍有效时不覆盖。 */
    fun pickDefaults(list: List<String>) {
        if (list.isEmpty()) return
        if (source.isNotBlank() && target.isNotBlank() && source in list && target in list) return
        target = list.firstOrNull { it == "beta" } ?: list.first()
        source = list.firstOrNull { it == "main" } ?: list.last()
    }

    LaunchedEffect(owner, repo) {
        loading = true
        val manager = SearchCacheManager(SearchCacheDatabase.getInstance(context).searchCacheDao())
        // 与分支管理页 / 对比页共用 branchListKey（同一份服务端数据）
        val cacheKey = PageCache.branchListKey(owner, repo)
        var shown = false

        // ① 先直出（含过期数据）：进页面立即有分支可选，不用等联网
        PageCache.cachedFirst(manager, cacheKey, PageCache.TYPE_DETAIL)?.let { cached ->
            val list = withContext(Dispatchers.IO) { parseBranchNames(cached) }
            if (list.isNotEmpty()) {
                branches = list
                pickDefaults(list)
                shown = true
                loading = false
            }
        }

        // ② 回源刷新（缓存未过期时直接复用，不重复联网）
        PageCache.refresh(manager, cacheKey, PageCache.TYPE_DETAIL) {
            withContext(Dispatchers.IO) { RustBridge.listBranches(host, token, owner, repo) }
        }?.let { json ->
            val list = withContext(Dispatchers.IO) { parseBranchNames(json) }
            if (list.isNotEmpty()) {
                branches = list
                pickDefaults(list)
                shown = true
            }
        }

        // 与改造前一致：没拿到任何分支时列表保持为空（页面显示「请选择」）
        if (!shown) branches = emptyList()
        loading = false
    }

    // 源/目标变化时刷新比较结果（用于预览「领先/落后」）；同步成功后 syncTick 也会触发重跑
    LaunchedEffect(source, target, syncTick) {
        if (source.isBlank() || target.isBlank() || source == target) {
            compare = null
            return@LaunchedEffect
        }
        val manager = SearchCacheManager(SearchCacheDatabase.getInstance(context).searchCacheDao())
        // 键含 base(=目标) 与 head(=源)：切换任一分支即换键，不复用旧结果
        val cacheKey = PageCache.compareKey(owner, repo, target, source)
        var shown = false

        // ① 先直出：切回看过的一对分支立即显示预览
        PageCache.cachedFirst(manager, cacheKey, PageCache.TYPE_DETAIL)?.let { cached ->
            val cachedCompare = withContext(Dispatchers.IO) { parseCompare(cached) }
            if (cachedCompare != null) {
                compare = cachedCompare
                shown = true
            }
        }

        // ② 回源刷新（缓存未过期时直接复用，不重复联网）
        val json = PageCache.refresh(manager, cacheKey, PageCache.TYPE_DETAIL) {
            withContext(Dispatchers.IO) {
                RustBridge.compareBranches(host, token, owner, repo, target, source)
            }
        }
        val parsed = withContext(Dispatchers.IO) { parseCompare(json) }
        if (parsed != null) {
            compare = parsed
        } else if (!shown) {
            // 与改造前一致：直出与回源都没拿到 → 不显示预览
            compare = null
        }
    }

    fun doSync() {
        if (source.isBlank() || target.isBlank()) { feedback = "请选择源分支与目标分支"; return }
        if (source == target) { feedback = "源分支与目标分支不能相同"; return }
        // 本次同步固定的源/目标/模式：请求期间的改动不影响本次请求与缓存失效所用的键
        val src = source
        val dst = target
        val m = mode
        scope.launch {
            busy = true
            feedback = null
            val result = withContext(Dispatchers.IO) {
                when (m) {
                    0 -> RustBridge.mergeBranch(host, token, owner, repo, dst, src, "Merge $src into $dst")
                    1, 2 -> {
                        val sha = RustBridge.getRefSha(host, token, owner, repo, src)
                        if (sha == null) "无法读取 $src 的提交"
                        else RustBridge.updateRef(host, token, owner, repo, dst, sha, m == 2)
                    }
                    else -> null
                }
            }
            Logger.net("branch sync $src → $dst (mode=$m) → ${result ?: "成功"}", "GitHubAPI")
            busy = false
            if (result == null || result == "uptodate") {
                // 同步改变了这个方向的「领先/落后」：旧对比缓存立刻过时，删掉并重跑预览
                withContext(Dispatchers.IO) {
                    SearchCacheManager(SearchCacheDatabase.getInstance(context).searchCacheDao())
                        .delete(PageCache.compareKey(owner, repo, dst, src))
                }
                syncTick++
            }
            feedback = when {
                result == null -> "已同步：$src → $dst"
                result == "uptodate" -> "目标分支已是最新，无需同步"
                result.contains("409") || result.contains("conflict", true) ->
                    "存在冲突，需要人工解决：$result"
                else -> "同步失败：$result"
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(Primer.BackgroundPrimary)
            .statusBarsPadding().navigationBarsPadding(),
    ) {
        // 头部
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Primer.IconPrimary,
                modifier = Modifier.size(24.dp).iconTap { onBack() },
            )
            Spacer(Modifier.width(8.dp))
            Text("分支同步", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
            Spacer(Modifier.weight(1f))
            Text("$owner/$repo", fontSize = 12.sp, color = Primer.TextTertiary)
        }

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Primer.Blue500)
            }
            return
        }

        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            // 源分支
            BranchPicker(
                label = "源分支（内容来自这里）",
                value = source,
                branches = branches,
                expanded = sourceMenu,
                onToggle = { sourceMenu = !sourceMenu },
                onPick = { source = it; sourceMenu = false },
            )
            Spacer(Modifier.height(12.dp))
            // 目标分支
            BranchPicker(
                label = "目标分支（同步到这里）",
                value = target,
                branches = branches,
                expanded = targetMenu,
                onToggle = { targetMenu = !targetMenu },
                onPick = { target = it; targetMenu = false },
            )

            // 预览
            compare?.let { c ->
                Spacer(Modifier.height(12.dp))
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(Primer.Gray150).padding(12.dp),
                ) {
                    Text(
                        "$source 领先 $target ${c.aheadBy} 个提交" +
                            if (c.behindBy > 0) "，落后 ${c.behindBy} 个" else "",
                        fontSize = 12.sp, color = Primer.TextSecondary,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        when {
                            c.aheadBy == 0 -> "目标已是最新，无需同步"
                            c.behindBy == 0 -> "可快进（目标没有独有提交）"
                            else -> "目标有 ${c.behindBy} 个独有提交，只能合并或覆盖"
                        },
                        fontSize = 11.sp,
                        color = if (c.behindBy > 0) Primer.WarningText else Primer.TextTertiary,
                    )
                }
            }

            // 模式
            Spacer(Modifier.height(16.dp))
            Text("同步方式", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
            Spacer(Modifier.height(8.dp))
            ModeRow(0, "合并", "保留历史，生成合并提交；有冲突时会被拒绝", mode) { mode = it }
            ModeRow(1, "仅快进", "目标没有独有提交时直接移动；否则拒绝", mode) { mode = it }
            ModeRow(2, "覆盖", "强制指向源分支，会丢弃目标分支独有提交", mode, danger = true) { mode = it }

            feedback?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    it,
                    fontSize = 12.sp,
                    color = if (it.startsWith("已同步")) Primer.Green500 else Primer.Red500,
                    lineHeight = 17.sp,
                )
            }

            Spacer(Modifier.height(20.dp))
            Box(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (busy) Primer.Gray150 else if (mode == 2) Primer.Red500 else Primer.Green500)
                    .clickable(enabled = !busy) {
                        if (mode == 2) confirmOverwrite = true else doSync()
                    }
                    .padding(vertical = 13.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (busy) "同步中…" else "同步 $source → $target",
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    color = if (busy) Primer.TextTertiary else Color.White,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmOverwrite) {
        AlertDialog(
            onDismissRequest = { confirmOverwrite = false },
            title = { Text("确认覆盖 $target？", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary) },
            text = {
                Text(
                    "覆盖会把 $target 强制指向 $source 的最新提交，" +
                        "$target 上独有的提交将不再被分支引用（仍可通过 reflog 找回，但界面上看不到）。",
                    fontSize = 12.sp, color = Primer.TextTertiary, lineHeight = 18.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmOverwrite = false; doSync() }) { Text("覆盖", color = Primer.Red500) }
            },
            dismissButton = { TextButton(onClick = { confirmOverwrite = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun BranchPicker(
    label: String,
    value: String,
    branches: List<String>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onPick: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextSecondary)
        Spacer(Modifier.height(6.dp))
        Box {
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, Primer.Border, RoundedCornerShape(8.dp))
                    .clickable { onToggle() }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    value.ifBlank { "请选择" },
                    fontSize = 13.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Primer.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                Text("▾", fontSize = 12.sp, color = Primer.TextTertiary)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { onToggle() }) {
                branches.forEach { b ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                b,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                color = if (b == value) Primer.Blue500 else Primer.TextPrimary,
                            )
                        },
                        onClick = { onPick(b) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeRow(
    index: Int,
    title: String,
    desc: String,
    current: Int,
    danger: Boolean = false,
    onPick: (Int) -> Unit,
) {
    val selected = current == index
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable { onPick(index) }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.size(16.dp).clip(CircleShape)
                .border(2.dp, if (selected) (if (danger) Primer.Red500 else Primer.Blue500) else Primer.Border, CircleShape)
                .background(if (selected) (if (danger) Primer.Red500 else Primer.Blue500) else Color.Transparent),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (danger) Primer.Red500 else Primer.TextPrimary,
            )
            Text(desc, fontSize = 11.sp, color = Primer.TextTertiary, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
