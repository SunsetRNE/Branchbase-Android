package com.branchbase.ui.decision

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.core.RustBridge
import com.branchbase.ui.theme.Primer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ═══════════════════════════════════════════════════════════════════
// 同步与冲突域决策页（对齐 design/decision-sync-prototype.html）
//   · ForkDecisionScreen   P0-1 分叉决策
//   · GitifyRollbackScreen P1-4 Git 化回退
//   · UpstreamSetupScreen  P2-2 首次 push upstream
//   · UndoCommitScreen     P2-3 撤销误提交
// ═══════════════════════════════════════════════════════════════════

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

    // 加载事实区
    androidx.compose.runtime.LaunchedEffect(repoDir) {
        loading = true
        status = withContext(Dispatchers.IO) { RustBridge.gitStatus(repoDir)?.let { parseGitStatus(it) } }
        if (status == null) loadError = "无法读取仓库状态（引擎不可用或目录无效）"
        loading = false
    }

    fun doResolve() {
        when (option) {
            0 -> onResolved("已保留本地提交 · 引导桌面解决")
            1 -> {
                if (!confirmed) { feedback = "请先勾选二次确认"; return }
                scope.launch {
                    busy = true
                    val branch = status?.branch ?: "main"
                    val ok = withContext(Dispatchers.IO) { RustBridge.gitResetHardRemote(repoDir, branch) }
                    busy = false
                    onResolved(if (ok) "已放弃本地提交" else "放弃失败（引擎不可用）")
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
                DecisionOptionRow(
                    title = "放弃本地提交",
                    desc = "reset --hard origin 后重试 pull。本地 ${status?.ahead ?: 0} 个未推送提交永久丢失。",
                    selected = option == 1,
                    tag = OptionTag.DANGER,
                    onSelect = { option = 1 },
                )
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
            enabled = !busy && !(option == 1 && !confirmed),
            colors = if (option == 1) ButtonDefaults.buttonColors(containerColor = Primer.Red500) else ButtonDefaults.buttonColors(containerColor = Primer.Green500),
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
    var option by remember { mutableStateOf(0) } // 0=设置并推送 1=仅本次 2=取消
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }

    androidx.compose.runtime.LaunchedEffect(repoDir) {
        val s = withContext(Dispatchers.IO) { RustBridge.gitStatus(repoDir)?.let { parseGitStatus(it) } }
        if (remoteUrl.isBlank()) {
            remoteUrl = s?.remoteUrl ?: "https://github.com/${repoName.removeSuffix(".git")}.git"
        }
        if (s != null && s.branch.isNotBlank()) upstreamBranch = s.branch
    }

    fun doPush() {
        if (option == 2) { onBack(); return }
        if (remoteUrl.isBlank()) { feedback = "请填写远端 URL"; return }
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                RustBridge.gitPushSetUpstream(repoDir, remoteUrl, upstreamBranch, token)
            }
            busy = false
            when (result) {
                null -> onResolved(if (option == 0) "已设置上游并推送" else "已推送（未记录上游）", false)
                "nff" -> onResolved(null, true)
                else -> feedback = "推送失败：${result ?: "未知错误"}"
            }
        }
    }

    DecisionScreenShell(
        title = "设置上游分支",
        subtitle = "$repoName · 首次推送",
        onBack = onBack,
        content = {
        DecisionNote("本地仓库由 git init 创建（非 clone），首次推送前需确定远端与上游分支。设置后写入 .git/config，无需重复设置。")

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
                DecisionOptionRow("设置上游并推送", "git push -u origin。以后直接 push/pull 即可，无需再选。", option == 0, OptionTag.RECOMMENDED) { option = 0 }
                DecisionOptionRow("仅本次推送", "不记录 upstream，下次仍会询问。", option == 1) { option = 1 }
                DecisionOptionRow("取消", "保持未推送状态，稍后再处理。", option == 2) { option = 2 }
            }
        }
        feedback?.let { FeedbackLine(it, error = true) }
        if (busy) FeedbackLine("推送中…")
    },
    bottom = {
        TextButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("取消") }
        Button(onClick = { doPush() }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("设置并推送") }
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
    var option by remember { mutableStateOf(0) } // 0=amend 1=soft 2=hard（未推送）；已推送时 0=revert 1=取消
    var confirmed by remember { mutableStateOf(false) }
    var newMessage by remember { mutableStateOf("") }
    var revertSha by remember { mutableStateOf("HEAD") }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }

    val unpushed = status?.unpushed.orEmpty()
    val hasUnpushed = unpushed.isNotEmpty()
    val head = unpushed.firstOrNull()

    androidx.compose.runtime.LaunchedEffect(repoDir) {
        val s = withContext(Dispatchers.IO) { RustBridge.gitStatus(repoDir)?.let { parseGitStatus(it) } }
        status = s
        newMessage = s?.unpushed?.firstOrNull()?.message ?: ""
    }

    fun authorName() = context.getSharedPreferences("branchbase", android.content.Context.MODE_PRIVATE)
        .getString("commit.author.name", "") ?: "Branchbase"

    fun authorEmail() = context.getSharedPreferences("branchbase", android.content.Context.MODE_PRIVATE)
        .getString("commit.author.email", "") ?: "branchbase@users.noreply.github.com"

    fun doUndo() {
        if (head == null) { feedback = "没有可操作的提交"; return }
        when {
            hasUnpushed && option == 0 -> { // amend
                if (newMessage.isBlank()) { feedback = "请输入新的提交信息"; return }
                scope.launch {
                    busy = true
                    val ok = withContext(Dispatchers.IO) { RustBridge.gitAmend(repoDir, newMessage) }
                    busy = false
                    onResolved(if (ok) "已修改提交信息" else "amend 失败（引擎不可用）")
                }
            }
            hasUnpushed && option == 1 -> scope.launch {
                busy = true
                val ok = withContext(Dispatchers.IO) { RustBridge.gitResetSoft(repoDir) }
                busy = false
                onResolved(if (ok) "已撤销提交 · 改动保留在工作区" else "撤销失败（引擎不可用）")
            }
            hasUnpushed && option == 2 -> {
                if (!confirmed) { feedback = "请先勾选二次确认"; return }
                scope.launch {
                    busy = true
                    val ok = withContext(Dispatchers.IO) { RustBridge.gitResetHardRemote(repoDir, status?.branch ?: "main") }
                    busy = false
                    onResolved(if (ok) "已撤销并丢弃改动" else "撤销失败（引擎不可用）")
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
                    onResolved(if (sha != null) "已创建 revert 提交" else "revert 失败（需工作区干净）")
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

        if (hasUnpushed) {
            FactCard("未推送提交 · 可自由改写") {
                Column {
                    DecisionOptionRow("修改提交信息（amend）", "仅改 message，内容不变，无任何风险。", option == 0, OptionTag.RECOMMENDED) { option = 0 }
                    DecisionOptionRow("撤销提交，保留改动（reset --soft）", "改动回到工作区（dirty），可重新编辑再提交。", option == 1) { option = 1 }
                    DecisionOptionRow("撤销并丢弃改动（reset --hard）", "该提交的改动永久丢失，需勾选二次确认。", option == 2, OptionTag.DANGER) { option = 2 }
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
            FactCard("已推送提交 · 不可改写历史") {
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
            enabled = !busy && !(hasUnpushed && option == 2 && !confirmed),
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
