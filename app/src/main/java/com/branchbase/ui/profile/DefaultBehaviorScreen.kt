package com.branchbase.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.branchbase.R
import com.branchbase.ui.settings.SettingsSection
import com.branchbase.ui.settings.SwitchRow
import com.branchbase.ui.settings.setWorkflowDefaultHistory
import com.branchbase.ui.settings.workflowDefaultHistory
import com.branchbase.ui.theme.Primer
import com.branchbase.ui.theme.TintRole

/**
 * 「默认行为管理」：一页只回答「进某个列表时先落在哪一档」。
 *
 * 目前只有一项：工作流页（Actions）默认打开运行历史。默认**开**（用户拍板）——
 * 这个页面的高频诉求是「最近跑成什么样」，不是「有哪些工作流」。
 *
 * 容器与行都走设置树既有的那一套（`SettingsSection` + `SwitchRow`），
 * 所以它和别的设置页没有样式差；入口在设置主页单独一张卡（不并进账户卡）。
 */
@Composable
fun DefaultBehaviorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var defaultHistory by remember { mutableStateOf(workflowDefaultHistory(context)) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Primer.BackgroundPrimary)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        SubPageHeader(stringResource(R.string.settings_default_behavior), onBack)
        SettingsSection {
            SwitchRow(
                icon = Icons.Filled.History,
                tint = TintRole.NEUTRAL,
                name = stringResource(R.string.label_workflow_default_history),
                sub = stringResource(R.string.note_workflow_default_history),
                checked = defaultHistory,
                onCheckedChange = {
                    defaultHistory = it
                    setWorkflowDefaultHistory(context, it)
                },
                divider = false,
            )
        }
    }
}
