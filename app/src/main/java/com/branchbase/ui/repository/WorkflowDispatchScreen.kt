package com.branchbase.ui.repository

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.branchbase.core.RustBridge
import com.branchbase.ui.theme.iconTap
import com.branchbase.ui.theme.Primer
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 手动触发工作流（`workflow_dispatch`）表单页。
 *
 * 数据来源：
 * - 输入定义：读工作流 YAML → `RustBridge.parseWorkflowInputs`（REST 的 workflow 对象不含 inputs）
 * - 分支列表：`GET /repos/{o}/{r}/branches`（`ref` 支持分支或标签，这里只列分支）
 *
 * 提交走 `POST /actions/workflows/{id}/dispatches`（成功 204 → Rust 侧返回 null）；
 * 422 最常见的原因是未声明 `workflow_dispatch` 或输入类型与 YAML 不符，因此单独给提示。
 */
@Composable
fun WorkflowDispatchScreen(
    sessionJson: String,
    owner: String,
    repo: String,
    workflow: WorkflowItem,
    defaultRef: String,
    onBack: () -> Unit,
    onDispatched: () -> Unit,
) {
    val (host, token, _) = sessionInfo(sessionJson)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var spec by remember(owner, repo, workflow.id) {
        mutableStateOf<WorkflowDispatchSpec?>(null)
    }
    var branches by remember(owner, repo) { mutableStateOf<List<BranchItem>>(emptyList()) }
    var loading by remember(owner, repo, workflow.id) { mutableStateOf(true) }
    var ref by remember(owner, repo, workflow.id, defaultRef) { mutableStateOf(defaultRef) }
    var refMenu by remember { mutableStateOf(false) }

    // 表单值：输入名 → 字符串值（boolean 也以 "true"/"false" 存放，统一给 buildInputsJson 消费）
    val values = remember(owner, repo, workflow.id) { mutableStateMapOf<String, String>() }

    var submitting by remember { mutableStateOf(false) }
    var dispatched by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var formError by remember { mutableStateOf<String?>(null) }
    var choiceMenu by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(owner, repo, workflow.id, workflow.path) {
        loading = true
        error = null
        // 两份数据互不依赖，并行取
        val (loadedSpec, loadedBranches) = coroutineScope {
            val specDeferred = async { loadDispatchSpec(host, token, owner, repo, workflow.path, context) }
            val branchesDeferred = async {
                RustBridge.listBranches(host, token, owner, repo)
                    ?.takeIf { !it.startsWith("ERROR:") }
                    ?.let { parseBranches(it) }
                    ?: emptyList()
            }
            specDeferred.await() to branchesDeferred.await()
        }
        spec = loadedSpec
        branches = loadedBranches
        // 输入初值：boolean 按 default == "true"，choice 优先 default（不在 options 里则取第一项），其余用 default
        values.clear()
        loadedSpec?.inputs?.forEach { input ->
            values[input.name] = when {
                input.isBoolean -> (input.default == "true").toString()
                input.isChoice -> input.default.takeIf { it in input.options } ?: input.options.first()
                else -> input.default
            }
        }
        loading = false
    }

    // 分支列表到达后补默认 ref（defaultRef 为空时用第一个分支）
    LaunchedEffect(branches) {
        if (ref.isBlank()) branches.firstOrNull()?.let { ref = it.name }
    }

    /** 提交：先校验必填与 ref，再触发；成功延时关闭，失败按 422 给额外提示。 */
    fun submit(specForSubmit: WorkflowDispatchSpec) {
        if (ref.isBlank()) {
            formError = "请先选择分支"
            return
        }
        formError = null
        error = null
        val missing = missingRequiredInputs(specForSubmit, values)
        if (missing.isNotEmpty()) {
            formError = "请填写：${missing.joinToString(", ")}"
            return
        }
        val inputsJson = buildInputsJson(specForSubmit, values)
        val targetRef = ref
        submitting = true
        scope.launch {
            val err = RustBridge.dispatchWorkflow(
                host, token, owner, repo, workflow.id, targetRef, inputsJson,
            )
            submitting = false
            if (err == null) {
                dispatched = true
                delay(1200)
                onDispatched()
            } else {
                error = "触发失败：$err" +
                    if (err.contains("422") || err.contains("Unprocessable")) {
                        "\n该工作流可能未声明 workflow_dispatch，或参数类型不符"
                    } else {
                        ""
                    }
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(Primer.BackgroundPrimary)
            .statusBarsPadding().navigationBarsPadding(),
    ) {
        // 顶部：返回 + 标题
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Primer.IconPrimary,
                modifier = Modifier.size(24.dp).iconTap(enabled = !submitting) { onBack() },
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "执行 · ${workflow.name.ifBlank { "未命名工作流" }}",
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary, maxLines = 1,
                )
                Text("$owner/$repo", fontSize = 11.sp, color = Primer.TextTertiary, maxLines = 1)
            }
        }

        val loadedSpec = spec
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Primer.Blue500)
            }

            loadedSpec == null -> Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                Text(
                    "无法读取工作流文件，暂时不能手动触发",
                    fontSize = 13.sp, color = Primer.TextTertiary,
                )
            }

            !loadedSpec.enabled -> Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Primer.Gray150)
                        .padding(16.dp),
                ) {
                    Text(
                        "该工作流未声明 workflow_dispatch，无法手动触发",
                        fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "在仓库「代码」页打开 ${workflow.path.ifBlank { "工作流文件" }}，在 on: 下加上 workflow_dispatch: " +
                            "后即可在本页手动执行。",
                        fontSize = 11.5.sp, color = Primer.TextTertiary, lineHeight = 18.sp,
                    )
                }
            }

            else -> {
                Column(Modifier.weight(1f)) {
                    Column(
                        Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                    ) {
                        Spacer(Modifier.height(6.dp))

                        // ref 选择器（分支或标签，等宽显示）
                        Text("分支 / 标签（ref）", fontSize = 11.sp, color = Primer.TextTertiary)
                        Spacer(Modifier.height(5.dp))
                        Box {
                            Row(
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(1.dp, Primer.Border, RoundedCornerShape(8.dp))
                                    .clickable(enabled = !submitting) { refMenu = true }
                                    .padding(horizontal = 12.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    ref.ifBlank { "请选择分支" },
                                    fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                                    color = if (ref.isBlank()) Primer.TextTertiary else Primer.TextPrimary,
                                    maxLines = 1, modifier = Modifier.weight(1f),
                                )
                                Text("▾", fontSize = 12.sp, color = Primer.TextTertiary)
                            }
                            DropdownMenu(expanded = refMenu, onDismissRequest = { refMenu = false }) {
                                branches.forEach { b ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                b.name, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                                                color = if (b.name == ref) Primer.Blue500 else Primer.TextPrimary,
                                            )
                                        },
                                        onClick = { ref = b.name; refMenu = false },
                                    )
                                }
                            }
                        }
                        if (branches.isEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text("分支列表加载失败，可直接使用当前默认分支", fontSize = 10.5.sp, color = Primer.TextTertiary)
                        }

                        if (loadedSpec.inputs.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            Text("参数", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
                        }

                        loadedSpec.inputs.forEach { input ->
                            WorkflowInputRow(
                                input = input,
                                value = values[input.name] ?: "",
                                enabled = !submitting && !dispatched,
                                choiceExpanded = choiceMenu == input.name,
                                onValueChange = { values[input.name] = it },
                                onToggleChoice = { choiceMenu = if (choiceMenu == input.name) null else input.name },
                                onPickChoice = { picked -> values[input.name] = picked; choiceMenu = null },
                            )
                        }

                        formError?.let {
                            Spacer(Modifier.height(10.dp))
                            Text(it, fontSize = 12.sp, color = Primer.Red500, lineHeight = 18.sp)
                        }
                        error?.let {
                            Spacer(Modifier.height(10.dp))
                            Text(it, fontSize = 12.sp, color = Primer.Red500, lineHeight = 18.sp)
                        }
                        if (dispatched) {
                            Spacer(Modifier.height(10.dp))
                            Text("已触发，运行记录稍后出现在列表中", fontSize = 12.sp, color = Primer.Green500)
                        }

                        Spacer(Modifier.height(20.dp))
                    }

                    // 底部提交按钮
                    Box(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (submitting || dispatched) Primer.Blue400 else Primer.Blue500)
                            .alpha(if (submitting) 0.7f else 1f)
                            .clickable(enabled = !submitting && !dispatched) { submit(loadedSpec) }
                            .padding(vertical = 13.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            when {
                                dispatched -> "已触发"
                                submitting -> "执行中…"
                                else -> "执行工作流"
                            },
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                        )
                    }
                }
            }
        }
    }
}

/** 单个输入项：按类型渲染控件，标题下用小字显示 description，必填加红字标记。 */
@Composable
private fun WorkflowInputRow(
    input: WorkflowInputSpec,
    value: String,
    enabled: Boolean,
    choiceExpanded: Boolean,
    onValueChange: (String) -> Unit,
    onToggleChoice: () -> Unit,
    onPickChoice: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        if (input.isBoolean) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WorkflowInputTitle(input, Modifier.weight(1f))
                Switch(
                    checked = value == "true",
                    onCheckedChange = { onValueChange(it.toString()) },
                    enabled = enabled,
                )
            }
        } else {
            WorkflowInputTitle(input)
            Spacer(Modifier.height(6.dp))
            when {
                input.isChoice -> Box {
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, Primer.Border, RoundedCornerShape(8.dp))
                            .clickable(enabled = enabled) { onToggleChoice() }
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            value.ifBlank { input.options.first() },
                            fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                            color = Primer.TextPrimary, maxLines = 1, modifier = Modifier.weight(1f),
                        )
                        Text("▾", fontSize = 12.sp, color = Primer.TextTertiary)
                    }
                    DropdownMenu(expanded = choiceExpanded, onDismissRequest = { onToggleChoice() }) {
                        input.options.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        option, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                                        color = if (option == value) Primer.Blue500 else Primer.TextPrimary,
                                    )
                                },
                                onClick = { onPickChoice(option) },
                            )
                        }
                    }
                }

                else -> OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    enabled = enabled,
                    keyboardOptions = if (input.isNumber) {
                        KeyboardOptions(keyboardType = KeyboardType.Number)
                    } else {
                        KeyboardOptions.Default
                    },
                    placeholder = {
                        Text(
                            input.default.ifBlank { input.type.ifBlank { "string" } },
                            fontSize = 12.sp, color = Primer.TextTertiary,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (input.description.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(input.description, fontSize = 10.5.sp, color = Primer.TextTertiary, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun WorkflowInputTitle(input: WorkflowInputSpec, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            input.name, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace, color = Primer.TextPrimary,
        )
        Spacer(Modifier.width(6.dp))
        Text(input.type.ifBlank { "string" }, fontSize = 10.sp, color = Primer.TextTertiary)
        if (input.required) {
            Spacer(Modifier.width(6.dp))
            Text("必填", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Primer.Red500)
        }
    }
}
