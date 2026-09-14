package com.branchbase.ui.repository

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.ui.theme.Primer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 仓库页三个按钮的弹层：**Watch 控制面板**与**复刻对话框**。
 *
 * 两者都严格对齐网页版的流程，包括文案层级与默认值：
 * - Watch 面板 = 网页版仓库页「Notification settings」下拉的四个档位 + 底部 Watch settings；
 * - 复刻对话框 = 网页版「Create a new fork」（账户 / 名称 / 只复刻默认分支）。
 */

// ── Watch 控制面板 ──

/**
 * Watch 档位面板。
 *
 * @param threadTypes 已加载的 Custom 事件清单；为空且 [onLoadThreadTypes] 非空时，展开 Custom 会去取
 * @param onSelect 选定档位：第二参数是 Custom 勾选的事件名（非 Custom 时为空）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchPanelSheet(
    current: WatchLevel,
    watchersCount: Long?,
    hasWebSession: Boolean,
    threadTypes: List<WatchThreadType>,
    onLoadThreadTypes: suspend () -> List<WatchThreadType>,
    onSelect: (WatchLevel, List<String>) -> Unit,
    onOpenSettings: () -> Unit,
    onLoginWeb: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var customOpen by remember { mutableStateOf(current == WatchLevel.CUSTOM) }
    var types by remember { mutableStateOf(threadTypes) }
    var loadingTypes by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(threadTypes.filter { it.subscribed }.map { it.name }) }

    // Custom 是唯一需要额外数据的档位：只在真正展开时拉，且结果里就带着当前勾选
    LaunchedEffect(customOpen) {
        if (!customOpen || types.isNotEmpty()) return@LaunchedEffect
        loadingTypes = true
        types = onLoadThreadTypes()
        picked = types.filter { it.subscribed }.map { it.name }
        loadingTypes = false
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 10.dp),
        ) {
            Text("Notification settings", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary)
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    append(current.title)
                    watchersCount?.let { append(" · $it 人正在关注") }
                },
                fontSize = 12.sp,
                color = Primer.TextTertiary,
            )
            Spacer(Modifier.height(10.dp))

            WatchLevel.entries.forEach { level ->
                WatchLevelRow(
                    level = level,
                    selected = level == current,
                    expanded = level == WatchLevel.CUSTOM && customOpen,
                    onClick = {
                        if (level == WatchLevel.CUSTOM) {
                            customOpen = true
                        } else {
                            onSelect(level, emptyList())
                        }
                    },
                )
                if (level == WatchLevel.CUSTOM && customOpen) {
                    CustomThreadList(
                        types = types,
                        loading = loadingTypes,
                        picked = picked,
                        onToggle = { name ->
                            picked = if (name in picked) picked - name else picked + name
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        SheetPrimaryButton(
                            text = "应用自定义",
                            enabled = !loadingTypes && (picked.isNotEmpty() || types.isNotEmpty()),
                            onClick = { onSelect(WatchLevel.CUSTOM, picked) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            // 没有网页会话时 Custom 写不进去（公开 API 表达不了）—— 提前说清楚，
            // 而不是等用户勾完再报「操作失败」
            if (!hasWebSession) {
                NoticeRow(
                    text = "自定义通知只有网页端点支持，登录网页会话后可直接在这里设置。",
                    action = "登录",
                    onAction = onLoginWeb,
                )
            }
            SheetLinkRow(text = "Watch settings", onClick = onOpenSettings)
        }
    }
}

@Composable
private fun WatchLevelRow(
    level: WatchLevel,
    selected: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            if (selected && !expanded) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = Primer.Green500, modifier = Modifier.size(14.dp))
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(level.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
            Text(level.description, fontSize = 11.5.sp, color = Primer.TextTertiary, lineHeight = 16.sp)
        }
        if (level == WatchLevel.CUSTOM) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = Primer.TextTertiary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun CustomThreadList(
    types: List<WatchThreadType>,
    loading: Boolean,
    picked: List<String>,
    onToggle: (String) -> Unit,
) {
    when {
        loading -> Row(Modifier.padding(start = 28.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(color = Primer.Blue500, strokeWidth = 2.dp, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(8.dp))
            Text("正在读取可订阅事件…", fontSize = 12.sp, color = Primer.TextTertiary)
        }

        types.isEmpty() -> Text(
            "暂时读不到事件清单（需要网络）。",
            fontSize = 12.sp,
            color = Primer.TextTertiary,
            modifier = Modifier.padding(start = 28.dp, top = 6.dp, bottom = 6.dp),
        )

        else -> Column(Modifier.padding(start = 28.dp)) {
            types.forEach { type ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = type.enabled) { onToggle(type.name) }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CheckBoxMark(checked = type.name in picked, enabled = type.enabled)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        type.label,
                        fontSize = 13.sp,
                        color = if (type.enabled) Primer.TextPrimary else Primer.TextTertiary,
                        modifier = Modifier.weight(1f),
                    )
                    if (!type.enabled) {
                        Text("该仓库未启用", fontSize = 11.sp, color = Primer.TextTertiary)
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckBoxMark(checked: Boolean, enabled: Boolean) {
    val border = if (enabled) Primer.BorderControl else Primer.Gray200
    Box(
        Modifier
            .size(15.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(if (checked) Primer.Green500 else Primer.BackgroundPrimary)
            .border(1.dp, if (checked) Primer.Green500 else border, RoundedCornerShape(3.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = Primer.OnEmphasis, modifier = Modifier.size(11.dp))
        }
    }
}

// ── 复刻对话框 ──

/**
 * 复刻对话框（对齐网页版「Create a new fork」）。
 *
 * @param onCheckExists 目标账户下是否已存在同名仓库（true 已存在 / false 可用 / null 校验失败）
 * @param onCreate 提交复刻；返回结果决定是关闭并跳转还是就地显示错误
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForkSheet(
    sourceOwner: String,
    repoName: String,
    login: String,
    branchCount: Int?,
    defaultBranch: String,
    onLoadTargets: suspend () -> List<String>,
    onCheckExists: suspend (owner: String, name: String) -> Boolean?,
    onCreate: suspend (organization: String, name: String, defaultBranchOnly: Boolean) -> RepoActions.ForkResult,
    onCreated: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    var targets by remember { mutableStateOf(listOf(login).filter { it.isNotBlank() }) }
    var owner by remember { mutableStateOf(login) }
    var ownerMenu by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(repoName) }
    // 网页版默认**不勾选**：不勾 = 复制全部分支
    var defaultBranchOnly by remember { mutableStateOf(false) }
    var checkState by remember { mutableStateOf<Boolean?>(null) } // null = 未校验
    var checking by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val list = onLoadTargets()
        if (list.isNotEmpty()) {
            targets = list
            if (owner !in list) owner = list.first()
        }
    }

    // 重名校验：防抖 450ms —— 每敲一个字母发一次请求既费流量也会让提示闪烁
    LaunchedEffect(owner, name) {
        error = null
        if (name.isBlank()) {
            checkState = null
            return@LaunchedEffect
        }
        checking = true
        checkState = null
        delay(450)
        checkState = onCheckExists(owner, name)
        checking = false
    }

    val taken = checkState == true
    val canCreate = !creating && name.isNotBlank() && !taken && !checking && owner.isNotBlank()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp),
        ) {
            Text("Create a new fork", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary)
            Spacer(Modifier.height(2.dp))
            Text(
                "从 $sourceOwner/$repoName 复刻一份到你自己的账户或组织。",
                fontSize = 12.sp,
                color = Primer.TextTertiary,
            )
            Spacer(Modifier.height(14.dp))

            FieldLabel("Owner")
            Box {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Primer.Gray100)
                        .border(1.dp, Primer.Gray200, RoundedCornerShape(6.dp))
                        .clickable { ownerMenu = true }
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(owner.ifBlank { "选择账户" }, fontSize = 13.sp, color = Primer.TextPrimary, modifier = Modifier.weight(1f))
                    Icon(Icons.Filled.ExpandMore, contentDescription = null, tint = Primer.TextTertiary, modifier = Modifier.size(16.dp))
                }
                DropdownMenu(expanded = ownerMenu, onDismissRequest = { ownerMenu = false }) {
                    targets.forEach { t ->
                        DropdownMenuItem(
                            text = { Text(t, fontSize = 13.sp, color = Primer.TextPrimary) },
                            onClick = { owner = t; ownerMenu = false },
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            FieldLabel("Repository name")
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Primer.Gray100)
                    .border(1.dp, Primer.Gray200, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 9.dp),
            ) {
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 13.sp, color = Primer.TextPrimary),
                    cursorBrush = SolidColor(Primer.Blue500),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = when {
                    name.isBlank() -> "名称不能为空"
                    checking -> "正在检查 $owner 下是否已有同名仓库…"
                    taken -> "$owner 下已存在同名仓库，换个名字，或直接打开那一个。"
                    checkState == false -> "$owner 下没有同名仓库，可以创建。"
                    else -> "改名后 GitHub 不会自动成为原仓库的复刻网络成员。"
                },
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                color = when {
                    taken -> Primer.Red500
                    checkState == false && !checking -> Primer.Green500
                    else -> Primer.TextTertiary
                },
            )

            Spacer(Modifier.height(14.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { defaultBranchOnly = !defaultBranchOnly }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Spacer(Modifier.size(1.dp))
                CheckBoxMark(checked = defaultBranchOnly, enabled = true)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Copy the DEFAULT branch only", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Primer.TextPrimary)
                    Text(
                        text = if (branchCount != null && branchCount > 1) {
                            "源仓库共 $branchCount 个分支。勾选后只复制默认分支 $defaultBranch，其余分支不会出现在你的复刻里。"
                        } else {
                            "勾选后只复制默认分支 $defaultBranch；不勾则复制全部分支。"
                        },
                        fontSize = 11.5.sp,
                        color = Primer.TextTertiary,
                        lineHeight = 16.sp,
                    )
                }
            }

            error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, fontSize = 12.sp, color = Primer.Red500, lineHeight = 17.sp)
            }

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (creating) {
                    CircularProgressIndicator(color = Primer.Blue500, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(10.dp))
                }
                SheetPrimaryButton(
                    text = "Create fork",
                    enabled = canCreate,
                    onClick = {
                        creating = true
                        error = null
                        scope.launch {
                            val result = onCreate(
                                if (owner == login) "" else owner,
                                name.trim(),
                                defaultBranchOnly,
                            )
                            creating = false
                            if (result.error != null) error = result.error else onCreated(result.fullName.orEmpty())
                        }
                    },
                )
            }
        }
    }
}

// ── 小组件 ──

@Composable
private fun FieldLabel(text: String) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextSecondary, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun SheetLinkRow(text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = Primer.Blue500, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 13.sp, color = Primer.Blue500)
    }
}

@Composable
private fun NoticeRow(text: String, action: String, onAction: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Primer.Gray100)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, fontSize = 11.5.sp, color = Primer.TextSecondary, lineHeight = 16.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(
            action,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Primer.Blue500,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onAction() }.padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun SheetPrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = if (enabled) Primer.OnEmphasis else Primer.TextTertiary,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (enabled) Primer.Green500 else Primer.Gray150)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
