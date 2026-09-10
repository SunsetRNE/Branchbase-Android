package com.branchbase.ui.repository

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.branchbase.cache.PageCache
import com.branchbase.cache.SearchCacheDatabase
import com.branchbase.cache.SearchCacheManager
import com.branchbase.core.RustBridge
import com.branchbase.ui.theme.Primer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 工作流操作抽屉（底部弹层）：对单个工作流提供「手动触发 / 查看 YAML / 浏览器打开」三个入口。
 *
 * 对齐 `docs/workflow-wireframe.md`：
 * - 能否手动触发取决于工作流 YAML 是否声明 `workflow_dispatch`；
 *   REST 的 workflow 对象不含输入定义，因此必须读文件 → 本地解析（不走网络）。
 * - 读文件失败（无权限/网络/路径缺失）一律按「不可手动触发」处理，只保留只读入口，
 *   不给出一个点了必然失败的按钮。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowActionSheet(
    sessionJson: String,
    owner: String,
    repo: String,
    workflow: WorkflowItem,
    onDismiss: () -> Unit,
    onDispatch: () -> Unit,
    onOpenFile: () -> Unit,
    onOpenBrowser: () -> Unit,
) {
    val (host, token, _) = sessionInfo(sessionJson)
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState()

    var spec by remember(owner, repo, workflow.id) {
        mutableStateOf<WorkflowDispatchSpec?>(null)
    }
    var loading by remember(owner, repo, workflow.id) { mutableStateOf(true) }

    LaunchedEffect(owner, repo, workflow.id, workflow.path) {
        loading = true
        spec = loadDispatchSpec(host, token, owner, repo, workflow.path, context)
        loading = false
    }

    // 触发条件与副标题文案（spec == null 表示仍在加载或读取失败）
    val loaded = spec
    val dispatchable = !loading && loaded != null && loaded.enabled && workflow.path.isNotBlank()
    val dispatchSubtitle = when {
        loading -> "正在读取工作流文件…"
        workflow.path.isBlank() -> "缺少工作流文件路径"
        loaded == null || !loaded.enabled -> "该工作流未声明 workflow_dispatch（无法手动触发）"
        loaded.inputs.isEmpty() -> "直接执行，无需参数"
        else -> "需要填写 ${loaded.inputs.size} 个参数"
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            // 标题行：工作流名 + YAML 路径（右侧为加载指示）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        workflow.name.ifBlank { "未命名工作流" },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Primer.TextPrimary,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        workflow.path.ifBlank { "缺少工作流文件路径" },
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Primer.TextTertiary,
                        maxLines = 1,
                    )
                }
                if (loading) {
                    Spacer(Modifier.width(10.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Primer.Blue500,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            WorkflowActionRow(
                icon = Icons.Filled.PlayArrow,
                title = "执行工作流",
                subtitle = dispatchSubtitle,
                enabled = dispatchable,
                onClick = onDispatch,
            )
            WorkflowActionRow(
                icon = Icons.Filled.Description,
                title = "查看工作流文件",
                subtitle = workflow.path.ifBlank { "缺少工作流文件路径" },
                enabled = true,
                onClick = onOpenFile,
            )
            WorkflowActionRow(
                icon = Icons.AutoMirrored.Filled.OpenInNew,
                title = "在浏览器打开",
                subtitle = workflow.htmlUrl.ifBlank { "该工作流没有网页地址" },
                enabled = workflow.htmlUrl.isNotBlank(),
                onClick = onOpenBrowser,
            )
        }
    }
}

/**
 * 读取工作流 YAML 并解析 `workflow_dispatch` 定义。
 *
 * @return 解析结果；路径为空、请求失败或解析失败时返回 null（调用方按「不可手动触发」处理）。
 */
internal suspend fun loadDispatchSpec(
    host: String,
    token: String,
    owner: String,
    repo: String,
    path: String,
    context: android.content.Context? = null,
): WorkflowDispatchSpec? {
    if (path.isBlank()) return null

    // 工作流 YAML 走「文件内容」缓存：抽屉与触发页都要读同一个文件，且内容几乎不变（TTL 10 分钟）
    val manager = context?.let {
        SearchCacheManager(SearchCacheDatabase.getInstance(it.applicationContext).searchCacheDao())
    }
    val cacheKey = PageCache.workflowFileKey(owner, repo, path)
    val json = if (manager != null) {
        PageCache.refresh(manager, cacheKey, PageCache.TYPE_FILE) {
            RustBridge.getJson(host, token, "/repos/$owner/$repo/contents/${encodePath(path)}")
        }
    } else {
        RustBridge.getJson(host, token, "/repos/$owner/$repo/contents/${encodePath(path)}")
            ?.takeIf { !it.startsWith("ERROR:") }
    } ?: return null

    val yaml = parseFileContent(json)
    if (yaml.isBlank()) return null
    // YAML 解析是本地引擎调用，放到 IO 线程，避免主线程解析大文件卡顿
    return withContext(Dispatchers.IO) { parseDispatchSpec(RustBridge.parseWorkflowInputs(yaml)) }
}

/** 抽屉里的单行操作：图标 + 标题 + 副标题，整行可点；不可用时整行置灰。 */
@Composable
private fun WorkflowActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (enabled) Primer.Gray150 else Primer.Gray100),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                null,
                tint = if (enabled) Primer.Blue500 else Primer.TextTertiary,
                modifier = Modifier.size(17.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).alpha(if (enabled) 1f else 0.45f)) {
            Text(
                title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) Primer.TextPrimary else Primer.TextTertiary,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                subtitle,
                fontSize = 11.sp,
                color = Primer.TextTertiary,
                maxLines = 2,
            )
        }
        if (enabled) {
            Spacer(Modifier.width(8.dp))
            Text("›", fontSize = 15.sp, color = Primer.TextTertiary)
        }
    }
}
