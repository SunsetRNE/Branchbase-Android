package com.branchbase.ui.decision

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.ui.log.LogCategory
import com.branchbase.ui.log.Logger
import com.branchbase.core.RustBridge
import com.branchbase.ui.theme.Primer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ═══════════════════════════════════════════════════════════════════
// 同步与冲突域决策页（规格见 docs/specs/decision-pages-design.md §4）
//   · ForkDecisionScreen   P0-1 分叉决策
//   · GitifyRollbackScreen P1-4 Git 化回退
//   · UpstreamSetupScreen  P2-2 首次 push upstream
//   · UndoCommitScreen     P2-3 撤销误提交
// ═══════════════════════════════════════════════════════════════════

// ───────────────────────────────────────────────────────────────────
// 选项可用性预检（纯函数 · 单测见 SyncDecisionPrecheckTest）
//
// 为什么要有这一层：下面几条都是「点了以后引擎**必然**失败」的硬事实 ——
//   · `reset_soft` 走 `HEAD~1`（core/src/git/mod.rs:678-697）：HEAD 是初始提交时没有父提交；
//   · `reset_hard_to_remote` 要求 `refs/remotes/origin/{branch}` 已存在（core/src/git/mod.rs:698-717）。
// 以前页面不看这两个事实（`GitStatus.hasParent` / `hasRemoteRef`），一律「先让用户点，
// 失败后归因引擎」——把「能提前判定的原因」变成了「事后编造的归因」。
// 抽成纯函数后单测能钉住判定本身，页面也不再各写一套。
// ───────────────────────────────────────────────────────────────────

/** 撤销页「撤销并保留改动（reset --soft）」被拦下的原因；null = 可用。 */
internal fun resetSoftBlockReason(hasParent: Boolean): String? =
    if (hasParent) null else "这是仓库的第一个提交，没有可撤销的上一次提交（reset --soft HEAD~1 必然失败）"

/** 撤销页「撤销并丢弃改动（reset --hard origin/x）」被拦下的原因；null = 可用。 */
internal fun resetHardBlockReason(hasParent: Boolean, hasRemoteRef: Boolean, branch: String): String? {
    val b = branch.ifBlank { "main" }
    return when {
        !hasParent && !hasRemoteRef ->
            "这是仓库的第一个提交（撤销它等于清空本地历史），且本地还没有 origin/$b 的记录：先执行一次获取"
        !hasParent -> "这是仓库的第一个提交：撤销它等于清空本地历史，本页不提供这个选项"
        !hasRemoteRef -> "本地还没有 origin/$b 的记录（未获取过）：先执行一次获取"
        else -> null
    }
}

/** 分叉页「放弃本地提交（reset --hard origin/x）」被拦下的原因；null = 可用。 */
internal fun discardLocalBlockReason(hasRemoteRef: Boolean, branch: String): String? {
    val b = branch.ifBlank { "main" }
    return if (hasRemoteRef) null
    else "本地还没有 origin/$b 的记录（未获取过）：先执行一次获取，否则 reset --hard 找不到目标"
}

/**
 * 失败文案：**只写能判定的原因**；判定不了就写中性说法，把排查交给日志。
 * 不再把一切失败一律归因到引擎（旧文案就是那样归因的：编造，且把排查方向带偏）。
 */
internal fun failureMessage(action: String, reason: String? = null): String =
    // 注意 `${action}` 必须带花括号：`$action失败` 会被词法分析当成一个标识符（CJK 也是标识符字符）
    if (reason.isNullOrBlank()) "${action}失败（原因见日志）" else "${action}失败：$reason"

/**
 * 撤销页「revert 形态」的卡片标题。
 *
 * `unpushed` 为空**不等于**「已推送」：`repo_status` 的 unpushed 来自 `HEAD..上游` 的 revwalk，
 * 没有上游分支时它同样为空（core/src/git/mod.rs:666-676）。只在真正确知
 * 「有上游、且没有未推送提交」时才敢说「已推送」，否则标题就是在替用户下结论。
 */
internal fun revertFormTitle(hasStatus: Boolean, hasUpstream: Boolean): String = when {
    !hasStatus -> "推送状态未知 · 只提供 revert"
    !hasUpstream -> "无上游分支 · 只提供 revert（无法判断已推送）"
    else -> "已推送提交 · 不可改写历史"
}

/**
 * 不可用选项行：与 [DecisionOptionRow] 同一套视觉语言（同样的单选圆点 / 标题 / 描述），
 * 但**不可点击**，并用一行「为什么不能用」替代「点了再报错」。
 *
 * 为什么不直接用 [DecisionOptionRow]：它没有 `enabled` 参数（共用组件本轮不动），
 * 传一个注定失败的 onSelect 等于把误报留给用户去撞 —— 正是本次要修掉的毛病。
 */
@Composable
private fun DisabledOptionRow(title: String, desc: String, reason: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Primer.Gray100)
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Spacer(
            Modifier
                .padding(top = 1.dp)
                .size(18.dp)
                .clip(CircleShape)
                .border(2.dp, Primer.Border, CircleShape),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextTertiary)
                Text(
                    "不可用",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Primer.WarningText,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Primer.WarningSurface)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(desc, fontSize = 12.sp, color = Primer.TextTertiary, lineHeight = 18.sp)
            Spacer(Modifier.height(4.dp))
            Text(reason, fontSize = 12.sp, color = Primer.WarningText, lineHeight = 18.sp)
        }
    }
}

/**
 * ① 分叉决策页（P0-1）。
 * pull 非快进 / push 被拒时触发。事实区：分叉示意 + 未推送提交清单；
 * 选项：保留本地引导桌面（推荐）/ 放弃本地（危险二次确认）/ 取消。
 */
@Composable
fun ForkDecisionScreen(
    repoName: String,
    repoDir: String,
    token: String,
    onBack: () -> Unit,
    onResolved: (message: String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<GitStatus?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var option by remember { mutableStateOf(0) } // 0=保留 1=放弃 2=取消
    var confirmed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }

    val branch = status?.branch?.takeIf { it.isNotBlank() } ?: "main"
    // 预检：`reset --hard origin/{branch}` 要求 refs/remotes/origin/{branch} 已存在
    // （core/src/git/mod.rs:698-705）—— 从没 fetch 过的仓库必然失败。所以先看 `hasRemoteRef`，
    // 没有就把「放弃本地提交」变灰并说明原因，而不是让用户点了才看到一句归因错误的话。
    // status 还没拿到（加载中 / 读取失败）时不预判，交给 loadError 行说明。
    val discardBlock = status?.let { discardLocalBlockReason(it.hasRemoteRef, branch) }

    // 加载事实区
    androidx.compose.runtime.LaunchedEffect(repoDir) {
        loading = true
        val s = withContext(Dispatchers.IO) { RustBridge.gitStatus(repoDir)?.let { parseGitStatus(it) } }
        status = s
        loadError = if (s == null) "无法读取仓库状态（目录无效或状态读取失败）" else null
        // 锚点：`决策页` —— 预检结论必须留痕：用户说「放弃本地点不动」时，日志能直接回答为什么
        Logger.local(
            "分叉页(${repoName})：status=${if (s == null) "读不到" else "ok"} branch=${s?.branch} " +
                "ahead=${s?.ahead} behind=${s?.behind} hasRemoteRef=${s?.hasRemoteRef} → " +
                (discardLocalBlockReason(s?.hasRemoteRef ?: false, s?.branch ?: "")?.let { "放弃本地被拦：$it" } ?: "放弃本地可用"),
            "决策页",
        )
        // 预检不通过时把选中项退回「保留本地」，避免停在「选项已灰、底部按钮还亮着」的半截状态
        if (s?.hasRemoteRef == false && option == 1) option = 0
        loading = false
    }

    fun doResolve() {
        when (option) {
            0 -> onResolved("已保留本地提交 · 引导桌面解决")
            1 -> {
                // 兜底（正常路径下该选项已禁用）：预检不通过就不执行，也不编造原因
                discardBlock?.let { feedback = it; return }
                if (!confirmed) { feedback = "请先勾选二次确认"; return }
                scope.launch {
                    busy = true
                    val ok = withContext(Dispatchers.IO) { RustBridge.gitResetHardRemote(repoDir, branch) }
                    busy = false
                    onResolved(if (ok) "已放弃本地提交" else failureMessage("放弃本地提交"))
                }
            }
            2 -> onBack()
        }
    }

    DecisionScreenShell(
        title = "同步失败 · 分叉",
        subtitle = repoName,
        onBack = onBack,
    content = {
        DecisionNote("本地与远端各领先对方，无法快进合并（non-fast-forward）。App 不做 merge/rebase（对齐 D11），请选择如何处理。")

        // 分叉示意
        FactCard("分叉示意") {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ForkNode(color = Primer.Green500, label = "本地 ${status?.branch ?: "main"}", count = "ahead ${status?.ahead ?: 0} · 未推送", modifier = Modifier.weight(1f))
                Box(Modifier.padding(horizontal = 8.dp)) {
                    Text("已分叉", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Primer.WarningText)
                }
                ForkNode(color = Primer.Blue500, label = "远端 origin", count = "ahead ${status?.behind ?: 0} · 他人新提交", modifier = Modifier.weight(1f))
            }
        }

        // 未推送提交清单
        FactCard("本地未推送提交（放弃将永久丢失）") {
            val ups = status?.unpushed.orEmpty()
            if (ups.isEmpty()) {
                FactRow("（无数据）", mono = true)
            } else {
                ups.forEach { c -> FactRow("${c.sha}  ${c.message}", mono = true) }
            }
        }

        // 处理方式
        FactCard("处理方式") {
            Column {
                DecisionOptionRow(
                    title = "保留本地提交，引导桌面解决",
                    desc = "本地工作区原样保留，App 不删除任何数据。请复制仓库路径到桌面端解决。",
                    selected = option == 0,
                    tag = OptionTag.RECOMMENDED,
                    onSelect = { option = 0 },
                )
                if (discardBlock == null) {
                    DecisionOptionRow(
                        title = "放弃本地提交",
                        desc = "reset --hard origin/$branch 后重试 pull。本地 ${status?.ahead ?: 0} 个未推送提交永久丢失。",
                        selected = option == 1,
                        tag = OptionTag.DANGER,
                        onSelect = { option = 1 },
                    )
                } else {
                    DisabledOptionRow(
                        title = "放弃本地提交",
                        desc = "reset --hard origin/$branch 后重试 pull。本地 ${status?.ahead ?: 0} 个未推送提交永久丢失。",
                        reason = discardBlock,
                    )
                }
                DecisionOptionRow(
                    title = "取消",
                    desc = "回到工作区，保持 ahead 状态，稍后可再处理。",
                    selected = option == 2,
                    onSelect = { option = 2 },
                )
            }
        }

        if (option == 1) {
            Spacer(Modifier.height(4.dp))
            DangerConfirmCard(
                description = "将丢弃本地 ${status?.ahead ?: 0} 个未推送提交，改动不可恢复。建议先到桌面端备份。",
                confirmLabel = "我确认放弃这些提交",
                confirmed = confirmed,
                onToggle = { confirmed = !confirmed },
            )
        }

        if (loading) FeedbackLine("加载仓库状态…")
        loadError?.let { FeedbackLine(it, error = true) }
        feedback?.let { FeedbackLine(it, error = true) }
        if (busy) FeedbackLine("执行中…")
    },
    bottom = {
        TextButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("取消") }
        Button(
            onClick = { doResolve() },
            enabled = !busy && !(option == 1 && (discardBlock != null || !confirmed)),
            colors = if (option == 1 && discardBlock == null) ButtonDefaults.buttonColors(containerColor = Primer.Red500) else ButtonDefaults.buttonColors(containerColor = Primer.Green500),
            modifier = Modifier.weight(1f),
        ) {
            Text(if (option == 1) "确认放弃本地提交" else if (option == 0) "保留并引导桌面" else "返回工作区", color = Color.White)
        }
    })
}

@Composable
private fun ForkNode(color: Color, label: String, count: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(44.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
            Text("⇄", color = Color.White, fontSize = 18.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
        Text(count, fontSize = 11.sp, color = Primer.TextTertiary, textAlign = TextAlign.Center)
    }
}

/**
 * ② Git 化回退页（P1-4）。反向路径：保留 .git（推荐，无害）/ 移除 .git / 删除整个仓库。
 */
@Composable
fun GitifyRollbackScreen(
    repoName: String,
    repoDir: String,
    onBack: () -> Unit,
    onDeletedRepo: () -> Unit,
    onResolved: (message: String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<GitStatus?>(null) }
    var option by remember { mutableStateOf(0) }
    var confirmed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }

    androidx.compose.runtime.LaunchedEffect(repoDir) {
        status = withContext(Dispatchers.IO) { RustBridge.gitStatus(repoDir)?.let { parseGitStatus(it) } }
        Logger.local(
            "Git 化回退页(${repoName})：status=${if (status == null) "读不到" else "ok"} " +
                "领先=${status?.ahead ?: 0} 改动=${status?.dirty?.size ?: 0}",
            "决策页",
        )
    }

    fun doResolve() {
        when (option) {
            0 -> onResolved("已保留 .git（偏好普通文件夹）")
            1 -> {
                if (!confirmed) { feedback = "请先勾选二次确认"; return }
                scope.launch {
                    busy = true
                    val ok = withContext(Dispatchers.IO) {
                        runCatching { java.io.File(repoDir, ".git").deleteRecursively() }.getOrDefault(false)
                    }
                    busy = false
                    onResolved(if (ok) "已移除 .git，文件保留" else "移除失败")
                }
            }
            2 -> onDeletedRepo()
        }
    }

    DecisionScreenShell(
        title = "关闭 Git 化",
        subtitle = "$repoName · 回退",
        onBack = onBack,
        content = {
        DecisionNote("「Git 化」是 ③ 的子开关（D10）。关闭后提交将走 App REST（①②），本地 .git 的处理方式请选择。")

        FactCard("仓库状态") {
            FactRow("领先远端", "${status?.ahead ?: 0} 个提交（未推送）", rightColor = if ((status?.ahead ?: 0) > 0) Primer.Red500 else Primer.TextTertiary)
            FactRow("工作区改动", "${status?.dirty?.size ?: 0} 个文件", rightColor = if ((status?.dirty?.size ?: 0) > 0) Primer.Red500 else Primer.TextTertiary)
        }

        FactCard("未推送提交") {
            val ups = status?.unpushed.orEmpty()
            if (ups.isEmpty()) FactRow("（无）", mono = true) else ups.forEach { c -> FactRow("${c.sha}  ${c.message}", mono = true) }
        }

        FactCard("回退方式") {
            Column {
                DecisionOptionRow("保留 .git 不变", "仅记录「偏好普通文件夹」，仓库与历史完好，可随时重新开启 Git 化。无损。", option == 0, OptionTag.RECOMMENDED) { option = 0 }
                DecisionOptionRow("移除 .git，保留文件", "工作树文件不变，但历史与 ${status?.ahead ?: 0} 个未推送提交永久丢失（需二次确认）。", option == 1, OptionTag.DANGER) { option = 1 }
                DecisionOptionRow("删除整个本地仓库", "跳转「删除本地仓库」确认（含未推送警告升级）。", option == 2, OptionTag.DANGER) { option = 2 }
            }
        }

        if (option == 1) {
            Spacer(Modifier.height(4.dp))
            DangerConfirmCard(
                description = "将永久丢失 ${status?.ahead ?: 0} 个未推送提交与全部历史。",
                confirmLabel = "我确认移除 .git",
                confirmed = confirmed,
                onToggle = { confirmed = !confirmed },
            )
        }
        feedback?.let { FeedbackLine(it, error = true) }
        if (busy) FeedbackLine("执行中…")
    },
    bottom = {
        TextButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("取消") }
        Button(
            onClick = { doResolve() },
            enabled = !busy && !(option == 1 && !confirmed),
            colors = ButtonDefaults.buttonColors(containerColor = if (option == 0) Primer.Green500 else Primer.Red500),
            modifier = Modifier.weight(1f),
        ) {
            Text("确定", color = Color.White)
        }
    })
}

/**
 * ③ 首次 push 上游设置页（P2-2）。
 */
@Composable
fun UpstreamSetupScreen(
    repoName: String,
    repoDir: String,
    token: String,
    onBack: () -> Unit,
    onResolved: (message: String?, fork: Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var remoteUrl by remember { mutableStateOf("") }
    var upstreamBranch by remember { mutableStateOf("main") }
    var option by remember { mutableStateOf(0) } // 0=推送并设为上游 1=取消
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }

    androidx.compose.runtime.LaunchedEffect(repoDir) {
        val s = withContext(Dispatchers.IO) { RustBridge.gitStatus(repoDir)?.let { parseGitStatus(it) } }
        if (remoteUrl.isBlank()) {
            remoteUrl = s?.remoteUrl ?: "https://github.com/${repoName.removeSuffix(".git")}.git"
        }
        if (s != null && s.branch.isNotBlank()) upstreamBranch = s.branch
        Logger.local(
            "上游页(${repoName})：status=${if (s == null) "读不到" else "ok"} 分支=$upstreamBranch " +
                "远端=${if (remoteUrl.isBlank()) "（待填）" else remoteUrl}",
            "决策页",
        )
    }

    fun doPush() {
        if (option == 1) { onBack(); return }
        if (remoteUrl.isBlank()) { feedback = "请填写远端 URL"; return }
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                RustBridge.gitPushSetUpstream(repoDir, remoteUrl, upstreamBranch, token)
            }
            busy = false
            when (result) {
                // 引擎的 push_set_upstream 只有一条路径：推完**总是** set_upstream（core/src/git/mod.rs:858-863）。
                // 所以不存在「推送成功但没记录 upstream」这个状态可报 —— 页面也不再提供这个做不到的选项。
                null -> onResolved("已推送并设为上游分支 origin/$upstreamBranch", false)
                "nff" -> onResolved(null, true)
                else -> feedback = "推送失败：$result"
            }
        }
    }

    DecisionScreenShell(
        title = "设置上游分支",
        subtitle = "$repoName · 首次推送",
        onBack = onBack,
        content = {
        DecisionNote("本地仓库由 git init 创建（非 clone），首次推送前需确定远端与上游分支。设置后写入 .git/config，无需重复设置。本应用只有这一条推送路径：引擎总会把该分支设为上游。")

        FactCard("远端配置") {
            Column(Modifier.padding(12.dp)) {
                Text("远端 URL", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextSecondary)
                OutlinedTextField(
                    value = remoteUrl,
                    onValueChange = { remoteUrl = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    singleLine = true,
                )
                Text("上游分支名", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextSecondary, modifier = Modifier.padding(top = 10.dp))
                OutlinedTextField(
                    value = upstreamBranch,
                    onValueChange = { upstreamBranch = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    singleLine = true,
                )
            }
        }

        FactCard("推送方式") {
            Column {
                DecisionOptionRow(
                    "推送并设为上游分支",
                    "git push -u origin $upstreamBranch：本应用总会把该分支设为上游（引擎没有「仅本次推送、不记录 upstream」这条路），以后直接 push/pull 即可。",
                    option == 0,
                    OptionTag.RECOMMENDED,
                ) { option = 0 }
                DecisionOptionRow("取消", "保持未推送状态，稍后再处理。", option == 1) { option = 1 }
            }
        }
        feedback?.let { FeedbackLine(it, error = true) }
        if (busy) FeedbackLine("推送中…")
    },
    bottom = {
        TextButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("取消") }
        Button(onClick = { doPush() }, enabled = !busy, modifier = Modifier.weight(1f)) {
            Text(if (option == 1) "返回工作区" else "推送并设为上游")
        }
    })
}

/**
 * ④ 撤销误提交页（P2-3 · 双形态）。
 * 未推送：amend / reset --soft / reset --hard（危险）；已推送：仅 revert。
 */
@Composable
fun UndoCommitScreen(
    repoName: String,
    repoDir: String,
    onBack: () -> Unit,
    onResolved: (message: String?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<GitStatus?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var option by remember { mutableStateOf(0) } // 0=amend 1=soft 2=hard（未推送）；已推送时 0=revert 1=取消
    var confirmed by remember { mutableStateOf(false) }
    var newMessage by remember { mutableStateOf("") }
    var revertSha by remember { mutableStateOf("HEAD") }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }

    val unpushed = status?.unpushed.orEmpty()
    val hasUnpushed = unpushed.isNotEmpty()
    val head = unpushed.firstOrNull()
    val branch = status?.branch?.takeIf { it.isNotBlank() } ?: "main"
    // 预检（纯函数见文件顶部）：两条都是「点了必然失败」，所以先禁用 + 说明原因。
    //   · 初始提交（hasParent=false）：reset --soft 走 HEAD~1 → parent_id(0) 直接失败；
    //   · 没有 refs/remotes/origin/{branch}（hasRemoteRef=false）：reset --hard origin/x 找不到目标。
    // status 没拿到（读取失败）时不预判，交给 loadError 行说明。
    val softBlock = status?.let { resetSoftBlockReason(it.hasParent) }
    val hardBlock = status?.let { resetHardBlockReason(it.hasParent, it.hasRemoteRef, branch) }
    val firstCommit = status?.hasParent == false

    androidx.compose.runtime.LaunchedEffect(repoDir) {
        val s = withContext(Dispatchers.IO) { RustBridge.gitStatus(repoDir)?.let { parseGitStatus(it) } }
        status = s
        loadError = if (s == null) "无法读取仓库状态（目录无效或状态读取失败）" else null
        newMessage = s?.unpushed?.firstOrNull()?.message ?: ""
        // 预检不通过时把选中项退回 amend：否则会停在「选中的那一行已变灰、底部按钮也点不动」的无解状态
        if (s != null) {
            val b = s.branch.takeIf { it.isNotBlank() } ?: "main"
            val softBlocked = resetSoftBlockReason(s.hasParent) != null
            val hardBlocked = resetHardBlockReason(s.hasParent, s.hasRemoteRef, b) != null
            if ((option == 1 && softBlocked) || (option == 2 && hardBlocked)) option = 0
            Logger.local(
                "撤销页(${repoName})：分支=$b 未推送=${s.unpushed.size} hasParent=${s.hasParent} " +
                    "hasRemoteRef=${s.hasRemoteRef} → " +
                    listOfNotNull(
                        resetSoftBlockReason(s.hasParent)?.let { "撤销保留改动被拦：$it" },
                        resetHardBlockReason(s.hasParent, s.hasRemoteRef, b)?.let { "撤销并丢弃被拦：$it" },
                    ).ifEmpty { listOf("两个撤销动作可用") }.joinToString("；"),
                "决策页",
            )
        } else {
            Logger.warn(LogCategory.LOCAL_TASK, "决策页", "撤销页(${repoName})：读不到仓库状态（${loadError ?: "未知原因"}）")
        }
    }

    fun authorName() = context.getSharedPreferences("branchbase", android.content.Context.MODE_PRIVATE)
        .getString("commit.author.name", "") ?: "Branchbase"

    fun authorEmail() = context.getSharedPreferences("branchbase", android.content.Context.MODE_PRIVATE)
        .getString("commit.author.email", "") ?: "branchbase@users.noreply.github.com"

    fun doUndo() {
        // 「没有可操作的提交」只在未推送形态下成立（那一形态的目标来自 unpushed 清单）。
        // revert 形态的目标来自输入框（默认 HEAD），以前这条守卫把它一并拦掉，
        // 于是「创建 revert 提交」永远只回一句「没有可操作的提交」——报的不是真实原因。
        if (hasUnpushed && head == null) { feedback = "没有可操作的提交"; return }
        when {
            hasUnpushed && option == 0 -> { // amend
                if (newMessage.isBlank()) { feedback = "请输入新的提交信息"; return }
                scope.launch {
                    busy = true
                    val ok = withContext(Dispatchers.IO) { RustBridge.gitAmend(repoDir, newMessage) }
                    busy = false
                    onResolved(if (ok) "已修改提交信息" else failureMessage("修改提交信息"))
                }
            }
            hasUnpushed && option == 1 -> {
                // 兜底（正常路径下该选项已禁用）：预检不通过就不执行，也不编造原因
                softBlock?.let { feedback = it; return }
                scope.launch {
                    busy = true
                    val ok = withContext(Dispatchers.IO) { RustBridge.gitResetSoft(repoDir) }
                    busy = false
                    onResolved(if (ok) "已撤销提交 · 改动保留在工作区" else failureMessage("撤销提交"))
                }
            }
            hasUnpushed && option == 2 -> {
                hardBlock?.let { feedback = it; return }
                if (!confirmed) { feedback = "请先勾选二次确认"; return }
                scope.launch {
                    busy = true
                    val ok = withContext(Dispatchers.IO) { RustBridge.gitResetHardRemote(repoDir, branch) }
                    busy = false
                    onResolved(if (ok) "已撤销并丢弃改动" else failureMessage("撤销并丢弃改动"))
                }
            }
            !hasUnpushed && option == 0 -> { // revert
                if (revertSha.isBlank()) { feedback = "请输入提交 sha（或 HEAD）"; return }
                val message = "Revert \"${head?.message ?: revertSha}\""
                scope.launch {
                    busy = true
                    val sha = withContext(Dispatchers.IO) {
                        RustBridge.gitRevert(repoDir, revertSha.trim(), message, authorName(), authorEmail())
                    }
                    busy = false
                    // gitRevert 把所有失败折叠成 null，页面分不出具体是哪一种：只列常见原因，不编造单一归因
                    onResolved(
                        if (sha != null) "已创建 revert 提交"
                        else "revert 失败（原因见日志 · 常见：工作区有未提交改动 / sha 无效）"
                    )
                }
            }
            else -> onBack()
        }
    }

    DecisionScreenShell(
        title = "撤销提交",
        subtitle = "$repoName · 长按触发",
        onBack = onBack,
        content = {
        head?.let { DecisionNote("目标提交 ${it.sha} · ${it.message}") }
        // 初始提交：先把原因说清楚，而不是等用户点了再报一句归因错误的话
        if (firstCommit) {
            DecisionNote("这是仓库的第一个提交：没有可撤销的上一次提交（reset --soft 必然失败；reset --hard 会清空本地历史，本页也不提供）。仍可修改提交信息。")
        }
        loadError?.let { FeedbackLine(it, error = true) }

        if (hasUnpushed) {
            FactCard("未推送提交 · 可自由改写") {
                Column {
                    DecisionOptionRow("修改提交信息（amend）", "重写这次提交的 message（树内容不变），仅用于未推送提交。", option == 0, OptionTag.RECOMMENDED) { option = 0 }
                    if (softBlock == null) {
                        DecisionOptionRow("撤销提交，保留改动（reset --soft）", "改动回到工作区（dirty），可重新编辑再提交。", option == 1) { option = 1 }
                    } else {
                        DisabledOptionRow("撤销提交，保留改动（reset --soft）", "改动回到工作区（dirty），可重新编辑再提交。", softBlock)
                    }
                    if (hardBlock == null) {
                        DecisionOptionRow("撤销并丢弃改动（reset --hard）", "该提交的改动永久丢失，需勾选二次确认。", option == 2, OptionTag.DANGER) { option = 2 }
                    } else {
                        DisabledOptionRow("撤销并丢弃改动（reset --hard）", "该提交的改动永久丢失，需勾选二次确认。", hardBlock)
                    }
                }
            }
            if (option == 0) {
                FactCard("新的提交信息") {
                    Column(Modifier.padding(12.dp)) {
                        OutlinedTextField(
                            value = newMessage,
                            onValueChange = { newMessage = it },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                            placeholder = { Text("简要描述…", fontSize = 13.sp, color = Primer.TextTertiary) },
                        )
                        Spacer(Modifier.height(8.dp))
                        // amend 的事实说明：引擎重建 HEAD 提交时**刻意沿用**原 author/committer 的姓名与时间
                        // （core/src/git/mod.rs:730-743）—— 不写明的话，用户会以为时间戳也跟着改了。
                        Text(
                            "amend 会重建这次提交（提交 sha 变化），但作者与提交时间沿用原提交 —— 刻意设计，不改时间戳。",
                            fontSize = 12.sp,
                            color = Primer.TextTertiary,
                            lineHeight = 18.sp,
                        )
                    }
                }
            }
            if (option == 2) {
                Spacer(Modifier.height(4.dp))
                DangerConfirmCard(
                    description = "该提交的改动将永久丢失（${head?.sha ?: "HEAD"}）。",
                    confirmLabel = "我确认丢弃这些改动",
                    confirmed = confirmed,
                    onToggle = { confirmed = !confirmed },
                )
            }
        } else {
            // 这个形态由「unpushed 是否为空」选出，而 unpushed 来自 HEAD..上游 的 revwalk：
            // 没有上游分支时它同样为空，此时**并不知道**哪些提交已推送 —— 标题不能替用户下结论。
            FactCard(revertFormTitle(hasStatus = status != null, hasUpstream = status?.hasUpstream == true)) {
                Column {
                    DecisionOptionRow("创建 revert 提交", "新提交反向撤销该提交改动，历史保留（对齐 D11 边界）。", option == 0, OptionTag.RECOMMENDED) { option = 0 }
                    DecisionOptionRow("取消", "不做任何操作。", option == 1) { option = 1 }
                }
                if (option == 0) {
                    Column(Modifier.padding(12.dp)) {
                        OutlinedTextField(
                            value = revertSha,
                            onValueChange = { revertSha = it },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                            singleLine = true,
                            label = { Text("提交 sha（或 HEAD）", fontSize = 11.sp) },
                        )
                    }
                }
                DecisionNote("说明：已推送提交不能 reset/amend（会破坏他人已拉取的历史），只提供 revert。")
            }
        }
        feedback?.let { FeedbackLine(it, error = true) }
        if (busy) FeedbackLine("执行中…")
    },
    bottom = {
        TextButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("取消") }
        Button(
            onClick = { doUndo() },
            // 预检拦下的选项在选项行里已禁用；这里再兜一层「status 晚到」时选中项失效的情况
            enabled = !busy &&
                !(hasUnpushed && option == 1 && softBlock != null) &&
                !(hasUnpushed && option == 2 && (hardBlock != null || !confirmed)),
            colors = ButtonDefaults.buttonColors(containerColor = if (hasUnpushed && option == 2) Primer.Red500 else Primer.Green500),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                when {
                    hasUnpushed && option == 0 -> "修改提交信息"
                    hasUnpushed && option == 1 -> "撤销并保留改动"
                    hasUnpushed -> "撤销并丢弃改动"
                    option == 0 -> "创建 revert 提交"
                    else -> "取消"
                },
                color = Color.White,
            )
        }
    })
}

/** 决策页内反馈行。 */
@Composable
internal fun FeedbackLine(text: String, error: Boolean = false) {
    Text(
        text,
        fontSize = 12.sp,
        color = if (error) Primer.Red500 else Primer.TextTertiary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}
