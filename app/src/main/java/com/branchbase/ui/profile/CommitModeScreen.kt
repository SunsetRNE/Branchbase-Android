package com.branchbase.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.ui.log.Logger
import com.branchbase.ui.theme.Primer

/**
 * 提交模式独立子页（对齐 `design/settings-split-prototype.html` 屏 2）。
 *
 * 原来这 3 个模式卡片直接塞在设置主页里，占掉近半屏；拆出来后
 * 设置主页只留一行入口并显示当前模式。
 */
@Composable
fun CommitModeScreen(onBack: () -> Unit) {
    LaunchedEffectOnce()

    val context = LocalContext.current
    var mode by remember { mutableStateOf(commitMode(context)) }

    Column(
        modifier = Modifier.fillMaxSize().background(Primer.BackgroundPrimary)
            .statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        SubPageHeader("提交模式", onBack)

        Text(
            "决定你改完代码后，改动以什么方式落到 GitHub。切换后立即生效，已存在的本地仓库不受影响。",
            fontSize = 12.sp,
            color = Primer.TextTertiary,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )

        CommitMode.entries.forEach { m ->
            ModeOptionRow(
                // 卡片是整行，可以承载完整说明；状态位（设置页右侧值等）只用短名 m.label
                label = m.title,
                desc = m.desc,
                selected = mode == m,
                onClick = {
                    mode = m
                    saveCommitMode(context, m)
                },
            )
        }

        Text(
            "当前：${mode?.label ?: "未配置（首次提交时会询问）"}",
            fontSize = 11.5.sp,
            color = Primer.TextTertiary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        Spacer(Modifier.height(16.dp))
    }
}

/** 进入页面的日志（保持与其它子页一致的行为）。 */
@Composable
private fun LaunchedEffectOnce() {
    androidx.compose.runtime.LaunchedEffect(Unit) { Logger.ui("进入提交模式页", "Compose") }
}
