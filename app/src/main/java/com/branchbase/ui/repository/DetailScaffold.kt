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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.R
import com.branchbase.ui.theme.Primer
import com.branchbase.ui.theme.iconTap

/**
 * 仓库详情类页面的**共享脚手架**：顶栏 + 加载/失败/空态。
 *
 * 这些小组件原本是 `WorkflowRunDetailScreen` 里的私有函数，于是别的详情页各造了一套
 * （标题字号、返回箭头、失败重试按钮的样式都在各写各的）。集中在这里，
 * 后续新增详情页直接复用，样式不会再漂。
 *
 * 只放「与业务无关」的东西：不认识 GitHub、不认识仓库、不认识工作流。
 */

/** 详情页顶栏：返回 + 单行标题 + 可选的右侧操作位。 */
@Composable
fun DetailTopBar(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.action_back),
                tint = Primer.IconPrimary,
                modifier = Modifier.size(24.dp).iconTap { onBack() },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Primer.TextPrimary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            actions()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Primer.Gray150))
    }
}

/** 分节标题（左对齐，上下留白；右侧可挂计数或分段控件）。 */
@Composable
fun DetailSectionTitle(title: String, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = Primer.TextSecondary,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

@Composable
fun DetailLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Primer.Blue500)
    }
}

@Composable
fun DetailErrorRetry(onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.error_load_failed), fontSize = 13.sp, color = Primer.TextSecondary)
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.action_retry),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Primer.Blue500,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, Primer.Blue500, RoundedCornerShape(6.dp))
                    .clickable { onRetry() }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
fun DetailEmptyText(text: String) {
    Text(
        text,
        fontSize = 12.5.sp,
        color = Primer.TextTertiary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
    )
}

/** 详情页整屏容器：页面底 + 状态栏/导航栏内边距 + 顶栏。 */
@Composable
fun DetailScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(Primer.BackgroundPrimary)
            .statusBarsPadding().navigationBarsPadding(),
    ) {
        DetailTopBar(title = title, onBack = onBack, actions = actions)
        content()
    }
}
