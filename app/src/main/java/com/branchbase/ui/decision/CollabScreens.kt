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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.core.RustBridge
import com.branchbase.ui.theme.Primer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ═══════════════════════════════════════════════════════════════════
// 协作与仓库管理域决策页（对齐 design/decision-collab-prototype.html）
//   · PrOnestopScreen        P1-1 PR 一条龙（三步）
//   · RepoSettingScreen      P1-2 仓库设置页群
//   · DeleteRepoWarningScreen P1-3 删除本地仓库升级警告
//   · PatInputScreen         P0-5 私有仓库 PAT 输入
//   · PrMergeScreen          P1-5 PR 合并策略
//   · OfflineConflictScreen  P2-4 离线编辑同步冲突
// ═══════════════════════════════════════════════════════════════════

/**
 * ① PR 一条龙（P1-1 · 三步：新建分支 → 提交 → 开 PR）。
 * 开 PR 目前展示完整 UI 与决策，提交执行预留（Git Data API 待接，见 decision-pages-gap.md §4.5）。
 */
@Composable
fun PrOnestopScreen(
    owner: String,
    repo: String,
    baseBranch: String,
    commitMessage: String,
    changedFiles: List<String>,
    onBack: () -> Unit,
    onCreated: (message: String?) -> Unit,
) {
    var step by remember { mutableStateOf(0) }
    var branchName by remember { mutableStateOf("patch-1") }
    var title by remember { mutableStateOf(commitMessage) }
    var description by remember { mutableStateOf("## 变更内容\n- 待补充\n\n## 测试\n- [ ] 已验证") }
    var draftPr by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }

    fun next() {
        when (step) {
            0 -> if (branchName.isBlank() || branchName.contains(' ')) feedback = "分支名不能为空或含空格" else { feedback = null; step = 1 }
            1 -> { feedback = null; step = 2 }
            2 -> onCreated("PR 已创建 · $branchName → $baseBranch（Git Data API 待接）")
        }
    }

    DecisionScreenShell(
        title = "开 PR · 一条龙",
        subtitle = "$owner/$repo · 步骤 ${step + 1}/3",
        onBack = onBack,
    content = {
        when (step) {
            0 -> {
                DecisionNote("将改动放入新分支并提交，随后基于该分支开 PR。当前分支 $baseBranch（保护分支，禁止直接推送）。")
                FactCard("分支命名") {
                    Column(Modifier.padding(12.dp)) {
                        Text("新分支名", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextSecondary)
                        OutlinedTextField(
                            value = branchName,
                            onValueChange = { branchName = it },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                            singleLine = true,
                        )
                        Text("基于（base）", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextSecondary, modifier = Modifier.padding(top = 10.dp))
                        OutlinedTextField(
                            value = baseBranch,
                            onValueChange = {},
                            enabled = false,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                            singleLine = true,
                        )
                    }
                }
                DecisionNote("分支名自动校验：不含空格 / 不与现有分支冲突 / 不命中保护分支命名规则。")
            }
            1 -> {
                DecisionNote("勾选本次提交文件（对齐 P0-2 暂存勾选），提交信息将作为 PR 默认标题。")
                FactCard("变更文件") {
                    Column {
                        changedFiles.forEach { f -> FactRow(f, mono = true) }
                    }
                }
                FactCard("提交信息") {
                    Column(Modifier.padding(12.dp)) {
                        OutlinedTextField(
                            value = commitMessage,
                            onValueChange = {},
                            enabled = false,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                        )
                    }
                }
            }
            else -> {
                FactCard("目标") {
                    FactRow("base", "$baseBranch（合并到）")
                    FactRow("head", "$branchName（改动源）")
                }
                FactCard("PR 信息") {
                    Column(Modifier.padding(12.dp)) {
                        Text("标题", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextSecondary)
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                            singleLine = true,
                        )
                        Text("描述（模板预填）", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextSecondary, modifier = Modifier.padding(top = 10.dp))
                        OutlinedTextField(
                            value = description,
                            onValueChange = { description = it },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                        )
                    }
                }
                FactCard("选项") {
                    Row(
                        Modifier.fillMaxWidth().clickable { draftPr = !draftPr }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("草稿 PR", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
                            Text("暂不通知审阅者，可随时转为正式", fontSize = 11.5.sp, color = Primer.TextTertiary)
                        }
                        Switch(checked = draftPr, onCheckedChange = { draftPr = it }, colors = SwitchDefaults.colors(checkedTrackColor = Primer.Green500))
                    }
                }
            }
        }
        feedback?.let { FeedbackLine(it, error = true) }
    },
    bottom = {
        TextButton(onClick = { if (step == 0) onBack() else step-- }, modifier = Modifier.weight(1f)) { Text(if (step == 0) "取消" else "上一步") }
        Button(onClick = { next() }, modifier = Modifier.weight(1f)) {
            Text(if (step == 2) (if (draftPr) "创建草稿 PR" else "创建 PR") else "下一步")
        }
    })
}

/**
 * ② 仓库设置页群（P1-2 · 替换 repository-prototype ⑨ 占位）。
 */
@Composable
fun RepoSettingScreen(
    repoName: String,
    branches: List<String>,
    defaultBranch: String,
    onBack: () -> Unit,
    onDeleteRepo: () -> Unit,
    onFeedback: (String) -> Unit,
) {
    var selectedDefault by remember { mutableStateOf(defaultBranch) }
    var mergedBranches by remember { mutableStateOf(setOf("patch-1")) } // 演示：已合并标记

    DecisionScreenShell(
        title = "仓库设置",
        subtitle = "$repoName · ⋮ 气泡进入",
        onBack = onBack,
        content = {
        FactCard("通用") {
            Column {
                Text("默认分支", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextSecondary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp))
                branches.forEach { b ->
                    Row(
                        Modifier.fillMaxWidth().clickable { selectedDefault = b; onFeedback("默认分支已切换为 $b（可撤销）") }.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(b, fontSize = 12.sp, color = if (b == selectedDefault) Primer.Blue500 else Primer.TextSecondary, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                        if (b == selectedDefault) Text("✓ 当前", fontSize = 11.sp, color = Primer.Blue500)
                    }
                }
            }
            DecisionNote("变更默认分支影响：README/PR 默认 base、clone 初始分支、未指定分支的 API 请求。")
        }

        FactCard("分支管理") {
            Column {
                branches.forEach { b ->
                    val merged = b in mergedBranches
                    FactRow(b, if (merged) "已合并" else "未合并 · 删除警告", rightColor = if (merged) Primer.Green500 else Primer.Red500, mono = true)
                }
            }
            DecisionNote("删除「未合并」分支需勾选确认：该分支的改动将无法找回。")
        }

        FactCard("危险操作区") {
            Column {
                FactRow("删除仓库", "需输入仓库名", rightColor = Primer.Red500)
            }
            DecisionNote("删除前展示挽留 stats：⭐ 12 星标 · 🍴 3 复刻 · 128 提交 · 4 贡献者（只读展示，不拦截）。")
        }
    },
    bottom = {
        TextButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("取消") }
        Button(onClick = onDeleteRepo, colors = ButtonDefaults.buttonColors(containerColor = Primer.Red500), modifier = Modifier.weight(1f)) { Text("删除仓库", color = Color.White) }
    })
}

/**
 * ③ 删除本地仓库「未推送提交」升级警告（P1-3）。
 */
@Composable
fun DeleteRepoWarningScreen(
    repoName: String,
    unpushed: List<UnpushedCommit>,
    onBack: () -> Unit,
    onPushFirst: () -> Unit,
    onDelete: () -> Unit,
) {
    var option by remember { mutableStateOf(0) } // 0=先推送 1=仍要删除
    var confirmed by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }

    DecisionScreenShell(
        title = "删除本地仓库",
        subtitle = "$repoName · ⚠ 升级警告",
        onBack = onBack,
        content = {
        DecisionNote("该仓库有 ${unpushed.size} 个未推送提交，删除后这些提交将永久丢失，无法恢复。")

        FactCard("未推送提交") {
            Column {
                unpushed.forEach { c -> FactRow("${c.sha}  ${c.message}", mono = true) }
            }
        }

        FactCard("处理方式") {
            Column {
                DecisionOptionRow("先推送再删", "跳转 push 流程，成功后自动回到删除确认。", option == 0, OptionTag.RECOMMENDED) { option = 0 }
                DecisionOptionRow("仍要删除", "勾选下方确认后启用删除按钮。", option == 1, OptionTag.DANGER) { option = 1 }
            }
        }

        if (option == 1) {
            Spacer(Modifier.height(4.dp))
            DangerConfirmCard(
                description = "我确认放弃这 ${unpushed.size} 个未推送提交，并永久删除本地仓库。",
                confirmLabel = "我已确认（勾选开启）",
                confirmed = confirmed,
                onToggle = { confirmed = !confirmed },
            )
        }
        feedback?.let { FeedbackLine(it, error = true) }
    },
    bottom = {
        TextButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("取消") }
        Button(
            onClick = { if (option == 0) onPushFirst() else if (confirmed) onDelete() else feedback = "请先勾选确认" },
            enabled = !(option == 1 && !confirmed),
            colors = ButtonDefaults.buttonColors(containerColor = if (option == 0) Primer.Green500 else Primer.Red500),
            modifier = Modifier.weight(1f),
        ) {
            Text(if (option == 0) "先推送再删" else "仍要删除", color = Color.White)
        }
    })
}

/**
 * ④ 私有仓库 PAT 输入（P0-5 · 仅内存，不写日志/不落盘）。
 */
@Composable
fun PatInputScreen(
    onBack: () -> Unit,
    onConfirm: (token: String) -> Unit,
) {
    var token by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }

    DecisionScreenShell(
        title = "需要访问令牌",
        subtitle = "认证失败 · 私有仓库",
        onBack = onBack,
        content = {
        DecisionNote("该仓库为私有，当前 OAuth 授权不含 repo scope，需要 PAT（Personal Access Token）或重新授权。")

        FactCard("令牌") {
            Column(Modifier.padding(12.dp)) {
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("ghp_…（repo scope）", fontSize = 13.sp, color = Primer.TextTertiary) },
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        Text(if (visible) "隐藏" else "显示", fontSize = 12.sp, color = Primer.Blue500, modifier = Modifier.clickable { visible = !visible })
                    },
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    singleLine = true,
                )
                Text("仅内存缓存 · 不写日志 · 不上传服务器 · 退出登录即清除", fontSize = 11.sp, color = Primer.TextTertiary, modifier = Modifier.padding(top = 6.dp))
            }
        }

        FactCard("处理方式") {
            Column {
                DecisionOptionRow("验证并继续", "仅内存缓存，本次会话内 clone/pull 复用。", true, OptionTag.RECOMMENDED) {}
                DecisionOptionRow("改用 OAuth 重新授权", "跳转登录流程，为 OAuth 补充 repo scope。", false) {}
            }
        }
        feedback?.let { FeedbackLine(it, error = true) }
    },
    bottom = {
        TextButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("取消") }
        Button(onClick = {
            if (token.isBlank()) feedback = "请输入 PAT" else onConfirm(token.trim())
        }, modifier = Modifier.weight(1f)) { Text("验证并继续") }
    })
}

/**
 * ⑤ PR 合并策略（P1-5 · squash 默认 + 合并后删分支）。
 */
@Composable
fun PrMergeScreen(
    prTitle: String,
    headBranch: String,
    baseBranch: String,
    onBack: () -> Unit,
    onMerge: (strategy: String, deleteBranch: Boolean) -> Unit,
) {
    var strategy by remember { mutableStateOf(0) } // 0=squash 1=merge 2=rebase
    var deleteBranch by remember { mutableStateOf(true) }

    val names = listOf("Squash and merge", "Merge commit", "Rebase and merge")
    val descs = listOf(
        "提交压缩为 1 个，历史整洁（移动端推荐）。",
        "保留全部提交 + 生成合并提交，历史完整。",
        "提交逐个重放到 $baseBranch，线性历史无合并提交。",
    )

    DecisionScreenShell(
        title = "合并 PR",
        subtitle = "$prTitle · $headBranch → $baseBranch",
        onBack = onBack,
        content = {
        DecisionNote("$headBranch → $baseBranch · 无冲突（fast-forward 可用）")

        FactCard("合并策略") {
            Column {
                names.forEachIndexed { i, n ->
                    DecisionOptionRow(n, descs[i], strategy == i, if (i == 0) OptionTag.RECOMMENDED else OptionTag.NONE) { strategy = i }
                }
            }
        }

        FactCard("合并后") {
            Row(
                Modifier.fillMaxWidth().clickable { deleteBranch = !deleteBranch }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("删除 head 分支（$headBranch）", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
                    Text("合并后自动删除，保持分支列表整洁", fontSize = 11.5.sp, color = Primer.TextTertiary)
                }
                Switch(checked = deleteBranch, onCheckedChange = { deleteBranch = it }, colors = SwitchDefaults.colors(checkedTrackColor = Primer.Green500))
            }
        }

        DecisionNote("策略将按仓库记忆最近一次选择（Room），下次默认带入。")
    },
    bottom = {
        TextButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("取消") }
        Button(onClick = { onMerge(names[strategy], deleteBranch) }, modifier = Modifier.weight(1f)) { Text("${names[strategy]} 并合并") }
    })
}

/**
 * ⑥ 离线编辑同步冲突（P2-4 · 并排 diff 事实区 + 三选项）。
 */
@Composable
fun OfflineConflictScreen(
    fileName: String,
    localContent: String,
    remoteContent: String,
    onBack: () -> Unit,
    onChoose: (choice: String) -> Unit,
) {
    var option by remember { mutableStateOf(0) } // 0=保留本地 1=放弃本地 2=复制远端
    var confirmed by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }

    val localLines = localContent.lines().take(6)
    val remoteLines = remoteContent.lines().take(6)

    DecisionScreenShell(
        title = "同步冲突",
        subtitle = "$fileName · 多端编辑",
        onBack = onBack,
        content = {
        DecisionNote("离线期间，他人在其他设备修改了同一文件。远端 sha 已变化，本地草稿基于旧版本。")

        FactCard("$fileName · 并排对比") {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("本地草稿（离线）", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Primer.Green500, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth().background(Color(0xFFF0FFF4)).padding(vertical = 6.dp))
                    localLines.forEach { l ->
                        Text(l.ifEmpty { " " }, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF1A7F37), maxLines = 1, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text("远端新版本", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Primer.Blue500, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth().background(Color(0xFFF0F7FF)).padding(vertical = 6.dp))
                    remoteLines.forEach { l ->
                        Text(l.ifEmpty { " " }, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF0550AE), maxLines = 1, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                }
            }
        }

        FactCard("处理方式") {
            Column {
                DecisionOptionRow("保留本地", "本地改动不变。提交时将基于旧 sha 被拒 → 走 P0-1 分叉决策页。", option == 0, OptionTag.RECOMMENDED) { option = 0 }
                DecisionOptionRow("放弃本地，载入远端", "本地草稿永久删除（需二次确认）。", option == 1, OptionTag.DANGER) { option = 1 }
                DecisionOptionRow("复制远端为新文件", "双开保全：本地草稿不动，远端另存为 ${fileName}.remote。", option == 2) { option = 2 }
            }
        }

        if (option == 1) {
            Spacer(Modifier.height(4.dp))
            DangerConfirmCard(
                description = "本地草稿将永久删除，未保存内容无法找回。",
                confirmLabel = "我确认放弃本地草稿",
                confirmed = confirmed,
                onToggle = { confirmed = !confirmed },
            )
        }
        feedback?.let { FeedbackLine(it, error = true) }
    },
    bottom = {
        TextButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("取消") }
        Button(
            onClick = {
                when (option) {
                    0 -> onChoose("keep")
                    1 -> if (confirmed) onChoose("remote") else feedback = "请先勾选二次确认"
                    2 -> onChoose("copy")
                }
            },
            enabled = !(option == 1 && !confirmed),
            colors = ButtonDefaults.buttonColors(containerColor = if (option == 1) Primer.Red500 else Primer.Green500),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                when (option) {
                    0 -> "保留本地"
                    1 -> "放弃本地"
                    else -> "复制远端为新文件"
                },
                color = Color.White,
            )
        }
    })
}
