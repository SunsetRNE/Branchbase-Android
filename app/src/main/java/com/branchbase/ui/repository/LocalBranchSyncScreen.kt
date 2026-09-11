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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.core.RustBridge
import com.branchbase.ui.decision.GitStatus
import com.branchbase.ui.decision.parseGitStatus
import com.branchbase.ui.log.Logger
import com.branchbase.ui.theme.iconTap
import com.branchbase.ui.theme.Primer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 本地仓库「分支同步」页（设置 → 本地仓库 → 某仓库 → 分支同步）。
 *
 * 补齐能力空缺：原实现只有「当前分支 pull / push」，
 * 没有 fetch、看不到远端独有分支，也分不清「未跟踪 / 可推送 / 可拉取 / 分叉」。
 *
 * 本页的同步逻辑（全部落在真实 git 语义上）：
 * 1. **进入即 fetch**（所有远端分支引用 + prune）——只刷新跟踪引用，
 *    不动工作区，因此能先看清每个分支相对远端的真实 ahead/behind；
 * 2. 按状态给出可执行动作：未跟踪 → 设为上游并推送；可推送 → 推送；可拉取 →
 *    当前分支直接拉取，其他分支需显式「切换并拉取」（libgit2 的 pull 只作用于 HEAD）；
 * 3. 分叉不自动处理：当前分支可「放弃本地、对齐远端」（reset --hard origin/x，二次确认）；
 * 4. 远端有、本地没有的分支 → 「创建并跟踪」（从 `origin/x` 建本地分支并设上游）。
 */
@Composable
fun LocalBranchSyncScreen(
    dir: String,
    repoName: String,
    token: String,
    onBack: () -> Unit,
    onChanged: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var locals by remember { mutableStateOf<List<LocalBranchInfo>>(emptyList()) }
    var remotes by remember { mutableStateOf<List<RemoteBranchInfo>>(emptyList()) }
    var status by remember { mutableStateOf<GitStatus?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }

    var confirmSwitchPull by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf<String?>(null) }

    /** 刷新：先 fetch（可失败但继续读本地状态），再读分支与状态。 */
    suspend fun refresh(fetch: Boolean) {
        loading = true
        val fetchErr = if (fetch) {
            withContext(Dispatchers.IO) { RustBridge.fetchRemote(dir, token, prune = true) }
        } else null
        locals = withContext(Dispatchers.IO) { parseLocalBranchInfos(RustBridge.localBranches(dir)) }
        remotes = withContext(Dispatchers.IO) { parseRemoteBranchInfos(RustBridge.remoteBranches(dir)) }
        status = withContext(Dispatchers.IO) { RustBridge.gitStatus(dir)?.let { parseGitStatus(it) } }
        if (fetchErr != null) feedback = "刷新远端失败：$fetchErr"
        loading = false
    }

    LaunchedEffect(dir, reloadKey) { refresh(fetch = reloadKey == 0) }

    fun run(label: String, block: suspend () -> String?) {
        scope.launch {
            busy = true
            feedback = null
            val err = block()
            Logger.net("$label ($repoName) → ${err ?: "成功"}", "LocalGit")
            busy = false
            feedback = if (err == null) "$label 成功" else "$label 失败：$err"
            if (err == null) {
                reloadKey++
                onChanged()
            }
        }
    }

    fun pushBranch(name: String) = run("推送 $name") {
        withContext(Dispatchers.IO) { RustBridge.gitPushDetailed(dir, token, name) }
    }

    fun pullCurrent() {
        val branch = status?.branch ?: return
        run("拉取 $branch") { withContext(Dispatchers.IO) { RustBridge.gitPullDetailed(dir, token) } }
    }

    fun switchAndPull(name: String) {
        scope.launch {
            busy = true
            feedback = null
            val co = withContext(Dispatchers.IO) { RustBridge.checkoutBranch(dir, name) }
            if (co != null) {
                busy = false
                feedback = "切换到 $name 失败：$co"
                return@launch
            }
            val err = withContext(Dispatchers.IO) { RustBridge.gitPullDetailed(dir, token) }
            busy = false
            feedback = if (err == null) "已切换到 $name 并拉取" else "拉取 $name 失败：$err"
            reloadKey++
            onChanged()
        }
    }

    fun setUpstream(name: String) {
        val url = status?.remoteUrl?.takeIf { it.isNotBlank() }
        if (url == null) { feedback = "缺少远端地址，无法设置上游"; return }
        run("设置上游并推送 $name") {
            withContext(Dispatchers.IO) { RustBridge.gitPushSetUpstream(dir, url, name, token) }
        }
    }

    fun trackRemote(name: String) {
        scope.launch {
            busy = true
            feedback = null
            val create = withContext(Dispatchers.IO) { RustBridge.createBranchLocal(dir, name, "origin/$name") }
            if (create != null) {
                busy = false
                feedback = "创建本地分支失败：$create"
                return@launch
            }
            val url = status?.remoteUrl?.takeIf { it.isNotBlank() }
            val up = if (url != null) {
                withContext(Dispatchers.IO) { RustBridge.gitPushSetUpstream(dir, url, name, token) }
            } else null
            busy = false
            feedback = if (up == null) "已创建本地分支 $name 并跟踪 origin/$name" else "已创建 $name，但设置上游失败：$up"
            reloadKey++
            onChanged()
        }
    }

    fun discardLocal(name: String) = run("放弃本地、对齐远端 $name") {
        // gitResetHardRemote 返回 Boolean，统一成「null = 成功」的错误约定
        val ok = withContext(Dispatchers.IO) { RustBridge.gitResetHardRemote(dir, name) }
        if (ok) null else "reset --hard 失败"
    }

    fun syncAll() {
        val plan = planSync(locals)
        if (plan.isEmpty) { feedback = "没有需要同步的分支"; return }
        scope.launch {
            busy = true
            feedback = null
            val done = mutableListOf<String>()
            plan.toPush.forEach { name ->
                val err = withContext(Dispatchers.IO) { RustBridge.gitPushDetailed(dir, token, name) }
                done += if (err == null) "推送 $name" else "推送 $name 失败($err)"
            }
            plan.toPull?.let { name ->
                val err = withContext(Dispatchers.IO) { RustBridge.gitPullDetailed(dir, token) }
                done += if (err == null) "拉取 $name" else "拉取 $name 失败($err)"
            }
            busy = false
            Logger.net("sync all ($repoName): ${done.joinToString("; ")}", "LocalGit")
            feedback = done.joinToString(" · ").ifBlank { "没有需要同步的分支" }
            reloadKey++
            onChanged()
        }
    }

    val remoteOnly = remoteOnlyBranches(remotes)
    val plan = planSync(locals)

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
                Text("分支同步", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
                Text(repoName, fontSize = 11.sp, color = Primer.TextTertiary, maxLines = 1)
            }
            Text(
                "刷新远端", fontSize = 12.5.sp, color = if (busy) Primer.TextTertiary else Primer.Blue500,
                modifier = Modifier.clickable(enabled = !busy) {
                    scope.launch { refresh(fetch = true) }
                },
            )
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Primer.Blue500)
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                            .clip(RoundedCornerShape(8.dp)).background(Primer.Gray150).padding(12.dp),
                    ) {
                        Text(
                            "当前分支 ${status?.branch ?: "—"}",
                            fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace, color = Primer.TextPrimary,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            buildString {
                                append("${locals.size} 个本地分支 · ${remotes.size} 个远端分支")
                                status?.let { st ->
                                    if (st.dirty.isNotEmpty()) append(" · 工作区 ${st.dirty.size} 个改动")
                                }
                            },
                            fontSize = 11.sp, color = Primer.TextTertiary,
                        )
                    }
                }

                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("本地分支", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary, modifier = Modifier.weight(1f))
                        if (!plan.isEmpty) {
                            Text(
                                "一键同步",
                                fontSize = 12.5.sp, color = if (busy) Primer.TextTertiary else Primer.Blue500,
                                modifier = Modifier.clickable(enabled = !busy) { syncAll() },
                            )
                        }
                    }
                }

                items(locals, key = { "local-${it.name}" }) { b ->
                    LocalBranchRow(
                        branch = b,
                        busy = busy,
                        hasRemoteCounterpart = remotes.any { it.name == b.name },
                        onPush = { pushBranch(b.name) },
                        onPull = { pullCurrent() },
                        onSwitchAndPull = { confirmSwitchPull = b.name },
                        onSetUpstream = { setUpstream(b.name) },
                        onDiscard = { confirmDiscard = b.name },
                    )
                }

                if (remoteOnly.isNotEmpty()) {
                    item {
                        Text(
                            "远端有、本地没有",
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                    items(remoteOnly, key = { "remote-${it.name}" }) { r ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                r.name,
                                fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                                color = Primer.TextPrimary, modifier = Modifier.weight(1f),
                            )
                            Text(
                                "创建并跟踪",
                                fontSize = 12.sp,
                                color = if (busy) Primer.TextTertiary else Primer.Blue500,
                                modifier = Modifier.clickable(enabled = !busy) { trackRemote(r.name) },
                            )
                        }
                    }
                }

                feedback?.let { msg ->
                    item {
                        Text(
                            msg,
                            fontSize = 12.sp,
                            color = if (msg.contains("失败")) Primer.Red500 else Primer.Green500,
                            lineHeight = 17.sp,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    confirmSwitchPull?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmSwitchPull = null },
            title = { Text("切换到 $target 并拉取？", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary) },
            text = {
                Text(
                    "libgit2 的 pull 只作用于当前分支，因此同步「$target」需要先切换过去。\n" +
                        "工作区有未提交改动时切换可能被拒绝（不会隐式 stash）。",
                    fontSize = 12.sp, color = Primer.TextTertiary, lineHeight = 18.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmSwitchPull = null; switchAndPull(target) }) { Text("切换并拉取") }
            },
            dismissButton = { TextButton(onClick = { confirmSwitchPull = null }) { Text("取消") } },
        )
    }

    confirmDiscard?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmDiscard = null },
            title = { Text("放弃本地提交？", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary) },
            text = {
                Text(
                    "会把 $target 强制对齐到 origin/$target，本地独有的提交将不再被分支引用" +
                        "（仍可通过 reflog 找回，但界面上看不到）。",
                    fontSize = 12.sp, color = Primer.TextTertiary, lineHeight = 18.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = null; discardLocal(target) }) {
                    Text("放弃并对齐", color = Primer.Red500)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun LocalBranchRow(
    branch: LocalBranchInfo,
    busy: Boolean,
    hasRemoteCounterpart: Boolean,
    onPush: () -> Unit,
    onPull: () -> Unit,
    onSwitchAndPull: () -> Unit,
    onSetUpstream: () -> Unit,
    onDiscard: () -> Unit,
) {
    val state = syncStateOf(branch)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (branch.isHead) "●" else "○",
                fontSize = 11.sp,
                color = if (branch.isHead) Primer.Green500 else Primer.TextTertiary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                branch.name,
                fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                fontWeight = if (branch.isHead) FontWeight.SemiBold else FontWeight.Normal,
                color = Primer.TextPrimary, modifier = Modifier.weight(1f), maxLines = 1,
            )
            Text(
                state.label,
                fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold,
                color = when (state) {
                    SyncState.Synced -> Primer.Green500
                    SyncState.Ahead -> Primer.Blue500
                    SyncState.Behind -> Color(0xFF9A6700)
                    SyncState.Diverged -> Primer.Red500
                    SyncState.Untracked -> Primer.TextTertiary
                },
            )
        }
        val meta = buildString {
            if (branch.upstream.isNotBlank()) append(branch.upstream)
            if (branch.ahead > 0) { if (isNotEmpty()) append(" · "); append("↑${branch.ahead}") }
            if (branch.behind > 0) { if (isNotEmpty()) append(" · "); append("↓${branch.behind}") }
        }
        if (meta.isNotBlank()) {
            Text(meta, fontSize = 10.5.sp, color = Primer.TextTertiary, modifier = Modifier.padding(top = 2.dp, start = 19.dp))
        }

        // 动作行
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        when (state) {
            SyncState.Ahead -> actions += "推送" to onPush
            SyncState.Behind -> if (branch.isHead) {
                actions += "拉取" to onPull
            } else {
                actions += "切换并拉取" to onSwitchAndPull
            }
            SyncState.Diverged -> if (branch.isHead) {
                actions += "放弃本地" to onDiscard
            } else {
                actions += "切换并拉取" to onSwitchAndPull
            }
            SyncState.Untracked -> if (hasRemoteCounterpart) actions += "设为上游并推送" to onSetUpstream
            SyncState.Synced -> Unit
        }
        if (actions.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp, start = 19.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                actions.forEach { (label, action) ->
                    Text(
                        label,
                        fontSize = 12.sp,
                        color = if (busy) Primer.TextTertiary else if (label.contains("放弃")) Primer.Red500 else Primer.Blue500,
                        modifier = Modifier.clickable(enabled = !busy) { action() },
                    )
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Primer.Gray100))
}
