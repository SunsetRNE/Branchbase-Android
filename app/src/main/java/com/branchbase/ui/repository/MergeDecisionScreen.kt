package com.branchbase.ui.repository

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.R
import com.branchbase.core.RustBridge
import com.branchbase.ui.decision.DecisionNote
import com.branchbase.ui.decision.DecisionOptionRow
import com.branchbase.ui.decision.DecisionScreenShell
import com.branchbase.ui.decision.FactCard
import com.branchbase.ui.decision.FactRow
import com.branchbase.ui.decision.OptionTag
import com.branchbase.ui.theme.Primer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本地合并的**决策页**（阶段 5 的 UI 那半）。
 *
 * ## 它为什么是一页而不是浮层里的一枚按钮
 *
 * 合并在 §6.1 的划分里是**有后果的动作**（会把对方的内容写进工作区、可能留下一堆冲突标记），
 * 所以它和提交 / 撤销 / 分支切换一样走决策页：先把事实摆出来，再让用户按下那一下。
 * 面板那一侧只给出口（`onMerge` 胶囊），**不自己执行**（`GitWorkbenchWiringTest` 扫全表）。
 *
 * ## 事实与决策分离（决策页四要素）
 *
 * - **事实**：合到哪个分支（当前 HEAD）、从哪个分支合、目标和我的领先落后、工作区干不干净、
 *   是不是浅克隆、是不是已经停在某次合并里；
 * - **选择**：合并哪个分支（分支清单，本地分支 + **只在远端**的分支 —— 后者引擎会先 fetch 一次，
 *   这正是 PR 冲突「拉到本地解决」那条路）；
 * - **后果**：会多出一个合并提交（已有提交一个字节都不改）；有冲突时会停在合并中，
 *   那时逐文件解决或整体放弃；
 * - **出路**：任何一个前置不成立时**按钮就是灰的，并在页面上说清为什么**（决策页的既有口径：
 *   不给注定失败的入口，也不让用户点下去才看到一句拒绝）。
 */
@Composable
internal fun MergeDecisionScreen(
    repoDir: String,
    repoName: String,
    git: LocalRepoGitState,
    token: String,
    flow: MergeFlowState,
    onBack: () -> Unit,
    onFeedback: (String, Boolean) -> Unit,
    onChanged: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var options by remember(repoDir) { mutableStateOf<List<MergeBranchOption>?>(null) }
    var loadFailed by remember(repoDir) { mutableStateOf(false) }
    var reloadKey by remember(repoDir) { mutableIntStateOf(0) }
    var picked by remember(repoDir) { mutableStateOf<String?>(null) }
    // 浅克隆判定要在 IO 上做（读 `.git/shallow`）：组合期读文件是主线程的一次 stat
    val shallow by produceState(initialValue = false, repoDir) {
        value = withContext(Dispatchers.IO) { isShallowClone(repoDir) }
    }

    LaunchedEffect(repoDir, reloadKey) {
        loadFailed = false
        options = withContext(Dispatchers.IO) { loadMergeBranchOptions(repoDir) }
        if (options == null) loadFailed = true
        // 选中的分支在重新取数后可能已经不在了（比如它被删了）—— 别让按钮指向一个不存在的名字
        if (options?.none { it.name == picked } != false) picked = null
    }

    val list = options.orEmpty()
    val target = list.firstOrNull { it.name == picked }
    // 前置条件（每一条都对应引擎会拒绝的一种输入，见 `merge_branch` 的三条前置）
    val dirty = git.dirtyCount > 0
    val blocked = when {
        !git.exists -> stringResource(R.string.state_merge_blocked_no_local)
        git.merging -> stringResource(R.string.state_merge_blocked_already)
        dirty -> stringResource(R.string.state_merge_blocked_dirty, git.dirtyCount)
        shallow -> stringResource(R.string.state_merge_blocked_shallow)
        else -> null
    }
    val canRun = target != null && blocked == null && !flow.running

    DecisionScreenShell(
        title = stringResource(R.string.title_merge),
        subtitle = repoName,
        onBack = onBack,
        bottom = {
            TextButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.action_cancel))
            }
            TextButton(
                onClick = {
                    val t = target ?: return@TextButton
                    scope.runMerge(
                        context = context,
                        flow = flow,
                        repoDir = repoDir,
                        repoName = repoName,
                        target = t.name,
                        token = token,
                        onFeedback = onFeedback,
                        onChanged = onChanged,
                    )
                },
                enabled = canRun,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    if (flow.running) stringResource(R.string.state_merging) else stringResource(R.string.action_merge),
                    color = if (canRun) Primer.Blue500 else Primer.TextTertiary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
    ) {
        FactCard(stringResource(R.string.label_merge_facts)) {
            // 这一条用的是既有的 `label_current_branch`（本来就是「当前分支 %1$s」带参资源）
            FactRow(stringResource(R.string.label_current_branch, git.branch.ifBlank { "—" }), mono = true)
            FactRow(
                stringResource(R.string.label_merge_target),
                target?.let { if (it.isRemote) "origin/${it.name}" else it.name } ?: stringResource(R.string.state_merge_no_target),
                mono = true,
            )
            FactRow(
                stringResource(R.string.label_merge_target_state),
                target?.badge ?: stringResource(R.string.state_merge_target_synced),
                rightColor = if (target?.diverged == true) Primer.WarningText else Primer.TextTertiary,
            )
            FactRow(
                stringResource(R.string.label_git_workspace),
                if (dirty) stringResource(R.string.state_merge_dirty_count, git.dirtyCount)
                else stringResource(R.string.state_merge_workspace_clean),
            )
            if (target?.isRemote == true) {
                FactRow(stringResource(R.string.label_merge_will_fetch), stringResource(R.string.state_merge_will_fetch))
            }
        }

        if (blocked != null) {
            DecisionNote(blocked)
        }

        Text(
            stringResource(R.string.label_merge_pick_branch),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Primer.TextSecondary,
            modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp),
        )

        when {
            loadFailed -> {
                DecisionNote(stringResource(R.string.error_merge_branches_failed))
                TextButton(onClick = { reloadKey++ }) {
                    Text(stringResource(R.string.action_retry), color = Primer.Blue500, fontSize = 12.sp)
                }
            }
            list.isEmpty() -> DecisionNote(stringResource(R.string.state_merge_no_branches))
            else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                items(list, key = { (if (it.isRemote) "r:" else "l:") + it.name }) { option ->
                    DecisionOptionRow(
                        title = if (option.isRemote) "origin/${option.name}" else option.name,
                        desc = option.description(),
                        selected = option.name == picked,
                        tag = OptionTag.NONE,
                        onSelect = { if (!flow.running) picked = option.name },
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        DecisionNote(stringResource(R.string.note_merge_consequence))
    }
}

/**
 * 合并页的分支清单（**纯函数**，便于单测）。
 *
 * 规则三条，每条都对应一个真机上会看到的坏结果：
 * - **去掉当前 HEAD 分支**：把「合并自己」摆在第一个，点了只会得到「已包含对方」这种空动作；
 * - **本地分支优先，远端只在本地没有同名的才出现**：同一个分支出现两次（`main` 与 `origin/main`）
 *   会让人以为它们不是一回事；
 * - 本地没配上游的分支**照样列出来**（合并跟上游无关，它比的是两个提交）。
 */
internal fun mergeBranchOptionsOf(
    locals: List<LocalBranchInfo>,
    remotes: List<RemoteBranchInfo>,
): List<MergeBranchOption> {
    val head = locals.firstOrNull { it.isHead }?.name.orEmpty()
    val localNames = locals.map { it.name }.toSet()
    val localOptions = locals
        .filter { !it.isHead && it.name != head }
        .sortedBy { it.name }
        .map {
            MergeBranchOption(
                name = it.name,
                isRemote = false,
                badge = refSyncBadge(it.ahead, it.behind),
                diverged = it.ahead > 0 && it.behind > 0,
                upstream = it.upstream,
            )
        }
    val remoteOptions = remotes
        .filter { it.name != head && it.name !in localNames && it.name != "HEAD" }
        .sortedBy { it.name }
        .map {
            MergeBranchOption(
                name = it.name,
                isRemote = true,
                badge = refSyncBadge(it.ahead, it.behind),
                diverged = false,
                upstream = "",
            )
        }
    return localOptions + remoteOptions
}

/** 合并页里的一个可选分支。 */
internal data class MergeBranchOption(
    val name: String,
    /** 本地没有这个分支，只在 `origin` 上 —— 合并时引擎会先 fetch 一次。 */
    val isRemote: Boolean,
    /** 紧凑徽标（`↑2` / `↓3` / `↑2 ↓3`），已同步时为 null。 */
    val badge: String?,
    val diverged: Boolean,
    val upstream: String,
) {
    @Composable
    fun description(): String = when {
        isRemote -> stringResource(R.string.state_merge_remote_only)
        diverged -> stringResource(R.string.state_merge_diverged)
        upstream.isBlank() -> stringResource(R.string.state_merge_no_upstream)
        else -> upstream
    }
}

/** 读合并页要的分支清单；null = 读不到（仓库不存在 / 引擎不可用）。 */
internal suspend fun loadMergeBranchOptions(repoDir: String): List<MergeBranchOption>? {
    val localsJson = RustBridge.localBranches(repoDir) ?: return null
    val remotesJson = RustBridge.remoteBranches(repoDir) ?: return null
    return mergeBranchOptionsOf(parseLocalBranchInfos(localsJson), parseRemoteBranchInfos(remotesJson))
}
