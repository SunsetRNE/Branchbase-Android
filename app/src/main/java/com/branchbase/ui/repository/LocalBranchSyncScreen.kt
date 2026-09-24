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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import com.branchbase.R
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

/**
 * 分支上的一个可执行动作。
 *
 * [danger] 由**动作自己声明**，不由渲染层从 [label] 里猜：
 * 文案迟早要抽成资源，`label.contains("放弃")` 那时会在英文界面下永不成立 ——
 * 破坏性动作会悄悄退回普通蓝色，不崩溃、不报错，只是不再警示。
 */
private data class BranchAction(
    val label: String,
    val run: () -> Unit,
    val danger: Boolean = false,
)

@Composable
fun LocalBranchSyncScreen(
    dir: String,
    repoName: String,
    token: String,
    onBack: () -> Unit,
    onChanged: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var locals by remember { mutableStateOf<List<LocalBranchInfo>>(emptyList()) }
    var remotes by remember { mutableStateOf<List<RemoteBranchInfo>>(emptyList()) }
    var status by remember { mutableStateOf<GitStatus?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<Feedback?>(null) }
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
        if (fetchErr != null) feedback = Feedback(context.getString(R.string.error_refresh_remote_failed, fetchErr), ok = false)
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
            feedback = if (err == null) {
                Feedback(context.getString(R.string.toast_operation_succeeded, label), ok = true)
            } else {
                Feedback(context.getString(R.string.toast_operation_failed, label, err), ok = false)
            }
            if (err == null) {
                reloadKey++
                onChanged()
            }
        }
    }

    fun pushBranch(name: String) = run(context.getString(R.string.action_push_branch, name)) {
        withContext(Dispatchers.IO) { RustBridge.gitPushDetailed(dir, token, name) }
    }

    fun pullCurrent() {
        val branch = status?.branch ?: return
        run(context.getString(R.string.label_pull_branch, branch)) { withContext(Dispatchers.IO) { RustBridge.gitPullDetailed(dir, token) } }
    }

    fun switchAndPull(name: String) {
        scope.launch {
            busy = true
            feedback = null
            val co = withContext(Dispatchers.IO) { RustBridge.checkoutBranch(dir, name) }
            if (co != null) {
                busy = false
                feedback = Feedback(context.getString(R.string.error_switch_to_failed, name, co), ok = false)
                return@launch
            }
            val err = withContext(Dispatchers.IO) { RustBridge.gitPullDetailed(dir, token) }
            busy = false
            feedback = if (err == null) {
                Feedback(context.getString(R.string.toast_switched_and_pulled, name), ok = true)
            } else {
                Feedback(context.getString(R.string.error_pull_failed, name, err), ok = false)
            }
            reloadKey++
            onChanged()
        }
    }

    fun setUpstream(name: String) {
        val url = status?.remoteUrl?.takeIf { it.isNotBlank() }
        if (url == null) { feedback = Feedback(context.getString(R.string.error_no_remote_url), ok = false); return }
        run(context.getString(R.string.label_set_upstream_and_push, name)) {
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
                feedback = Feedback(context.getString(R.string.error_create_local_branch_failed, create), ok = false)
                return@launch
            }
            val url = status?.remoteUrl?.takeIf { it.isNotBlank() }
            val up = if (url != null) {
                withContext(Dispatchers.IO) { RustBridge.gitPushSetUpstream(dir, url, name, token) }
            } else null
            busy = false
            feedback = if (up == null) {
                Feedback(context.getString(R.string.toast_created_tracking, name, name), ok = true)
            } else {
                Feedback(context.getString(R.string.error_created_upstream_failed, name, up), ok = false)
            }
            reloadKey++
            onChanged()
        }
    }

    fun discardLocal(name: String) = run(context.getString(R.string.label_discard_align_remote, name)) {
        // gitResetHardRemote 返回 Boolean，统一成「null = 成功」的错误约定
        val ok = withContext(Dispatchers.IO) { RustBridge.gitResetHardRemote(dir, name) }
        if (ok) null else context.getString(R.string.error_reset_hard_failed)
    }

    fun syncAll() {
        val plan = planSync(locals)
        if (plan.isEmpty) { feedback = Feedback(context.getString(R.string.state_nothing_to_sync), ok = true); return }
        scope.launch {
            busy = true
            feedback = null
            val done = mutableListOf<String>()
            plan.toPush.forEach { name ->
                val err = withContext(Dispatchers.IO) { RustBridge.gitPushDetailed(dir, token, name) }
                done += if (err == null) context.getString(R.string.action_push_branch, name) else context.getString(R.string.error_push_branch_failed, name, err)
            }
            plan.toPull?.let { name ->
                val err = withContext(Dispatchers.IO) { RustBridge.gitPullDetailed(dir, token) }
                done += if (err == null) context.getString(R.string.label_pull, name) else context.getString(R.string.error_pull_branch_failed, name, err)
            }
            busy = false
            Logger.net("sync all ($repoName): ${done.joinToString("; ")}", "LocalGit")
            feedback = Feedback(done.joinToString(" · ").ifBlank { context.getString(R.string.state_nothing_to_sync) }, ok = true)
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
                Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = Primer.IconPrimary,
                modifier = Modifier.size(24.dp).iconTap { onBack() },
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.nav_branch_sync), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
                Text(repoName, fontSize = 11.sp, color = Primer.TextTertiary, maxLines = 1)
            }
            Text(
                stringResource(R.string.action_refresh_remote), fontSize = 12.5.sp, color = if (busy) Primer.TextTertiary else Primer.Blue500,
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
                            stringResource(R.string.label_current_branch, status?.branch ?: "—"),
                            fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace, color = Primer.TextPrimary,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            buildString {
                                append(stringResource(R.string.label_branch_counts, locals.size, remotes.size))
                                status?.let { st ->
                                    if (st.dirty.isNotEmpty()) append(stringResource(R.string.suffix_worktree_changes, st.dirty.size))
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
                        Text(stringResource(R.string.label_local_branches), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary, modifier = Modifier.weight(1f))
                        if (!plan.isEmpty) {
                            Text(
                                stringResource(R.string.action_sync_all),
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
                            stringResource(R.string.label_remote_only),
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
                                stringResource(R.string.action_create_and_track),
                                fontSize = 12.sp,
                                color = if (busy) Primer.TextTertiary else Primer.Blue500,
                                modifier = Modifier.clickable(enabled = !busy) { trackRemote(r.name) },
                            )
                        }
                    }
                }

                feedback?.let { fb ->
                    item {
                        Text(
                            fb.text,
                            fontSize = 12.sp,
                            // 语气由产生方给出（见 Feedback），不从文案里猜：
                            // 文案迟早要抽成资源，`contains("失败")` 在英文界面下永不成立
                            color = if (fb.ok) Primer.Green500 else Primer.Red500,
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
            title = { Text(stringResource(R.string.confirm_switch_and_pull_title, target), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary) },
            text = {
                Text(
                    stringResource(R.string.confirm_switch_and_pull_body, target),
                    fontSize = 12.sp, color = Primer.TextTertiary, lineHeight = 18.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmSwitchPull = null; switchAndPull(target) }) { Text(stringResource(R.string.action_switch_and_pull)) }
            },
            dismissButton = { TextButton(onClick = { confirmSwitchPull = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    confirmDiscard?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmDiscard = null },
            title = { Text(stringResource(R.string.confirm_discard_local_commits_title), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary) },
            text = {
                Text(
                    stringResource(R.string.confirm_discard_local_commits_body, target, target),
                    fontSize = 12.sp, color = Primer.TextTertiary, lineHeight = 18.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = null; discardLocal(target) }) {
                    Text(stringResource(R.string.action_discard_and_align), color = Primer.Red500)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = null }) { Text(stringResource(R.string.action_cancel)) } },
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
                    SyncState.Behind -> Primer.WarningText
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

        // 动作行。危险与否**由动作自己声明**（[BranchAction.danger]），
        // 不再靠 `label.contains("放弃")` 猜 —— 文案抽成资源后那个判断在英文界面下永不成立，
        // 表现会是「放弃本地」悄悄变成蓝色，而不是报错。
        val actions = mutableListOf<BranchAction>()
        when (state) {
            SyncState.Ahead -> actions += BranchAction(stringResource(R.string.action_push), onPush)
            SyncState.Behind -> if (branch.isHead) {
                actions += BranchAction(stringResource(R.string.action_pull), onPull)
            } else {
                actions += BranchAction(stringResource(R.string.action_switch_and_pull), onSwitchAndPull)
            }
            SyncState.Diverged -> if (branch.isHead) {
                actions += BranchAction(stringResource(R.string.action_discard_local), onDiscard, danger = true)
            } else {
                actions += BranchAction(stringResource(R.string.action_switch_and_pull), onSwitchAndPull)
            }
            SyncState.Untracked -> if (hasRemoteCounterpart) actions += BranchAction(stringResource(R.string.action_set_upstream_and_push), onSetUpstream)
            SyncState.Synced -> Unit
        }
        if (actions.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp, start = 19.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                actions.forEach { action ->
                    Text(
                        action.label,
                        fontSize = 12.sp,
                        color = when {
                            busy -> Primer.TextTertiary
                            action.danger -> Primer.Red500
                            else -> Primer.Blue500
                        },
                        modifier = Modifier.clickable(enabled = !busy) { action.run() },
                    )
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Primer.Gray100))
}
