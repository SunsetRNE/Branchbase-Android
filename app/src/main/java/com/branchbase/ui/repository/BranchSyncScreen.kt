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
import com.branchbase.core.RustBridge
import com.branchbase.ui.log.Logger
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

    LaunchedEffect(owner, repo) {
        loading = true
        branches = withContext(Dispatchers.IO) {
            RustBridge.listBranches(host, token, owner, repo)?.let { json ->
                runCatching {
                    val arr = JSONArray(json)
                    (0 until arr.length()).mapNotNull { i ->
                        arr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }
                    }
                }.getOrDefault(emptyList())
            } ?: emptyList()
        }
        if (branches.isNotEmpty()) {
            target = branches.firstOrNull { it == "beta" } ?: branches.first()
            source = branches.firstOrNull { it == "main" } ?: branches.last()
        }
        loading = false
    }

    // 源/目标变化时刷新比较结果（用于预览「领先/落后」）
    LaunchedEffect(source, target) {
        if (source.isBlank() || target.isBlank() || source == target) {
            compare = null
            return@LaunchedEffect
        }
        compare = withContext(Dispatchers.IO) {
            parseCompare(RustBridge.compareBranches(host, token, owner, repo, target, source))
        }
    }

    fun doSync() {
        if (source.isBlank() || target.isBlank()) { feedback = "请选择源分支与目标分支"; return }
        if (source == target) { feedback = "源分支与目标分支不能相同"; return }
        scope.launch {
            busy = true
            feedback = null
            val result = withContext(Dispatchers.IO) {
                when (mode) {
                    0 -> RustBridge.mergeBranch(host, token, owner, repo, target, source, "Merge $source into $target")
                    1, 2 -> {
                        val sha = RustBridge.getRefSha(host, token, owner, repo, source)
                        if (sha == null) "无法读取 $source 的提交"
                        else RustBridge.updateRef(host, token, owner, repo, target, sha, mode == 2)
                    }
                    else -> null
                }
            }
            Logger.net("branch sync $source → $target (mode=$mode) → ${result ?: "成功"}", "GitHubAPI")
            busy = false
            feedback = when {
                result == null -> "已同步：$source → $target"
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
                modifier = Modifier.size(24.dp).clickable { onBack() },
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
                        color = if (c.behindBy > 0) Color(0xFF9A6700) else Primer.TextTertiary,
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
