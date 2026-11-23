package com.branchbase.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.R
import com.branchbase.ui.navigation.PageBackHandler
import com.branchbase.ui.navigation.PageLevel
import com.branchbase.ui.navigation.PageSwitcher
import com.branchbase.ui.navigation.rememberTopLevelBackAction
import com.branchbase.ui.main.MainScreen
import com.branchbase.ui.profile.CommitMode
import com.branchbase.ui.settings.SettingsKeys
import com.branchbase.ui.theme.Primer

/**
 * 登录成功后的「提交模式」引导门（LoggedInGate）。
 *
 * - 若本地已配置提交模式（commit_mode）→ 直接进主界面。
 * - 未配置 → 展示「提交模式」引导屏：确定 = 固化到本地配置；跳过 = 不固化（提交时再问）。
 *
 * 返回键：引导屏与主界面**顶层语义一致**（都是「再按一次退出应用」，见 TopLevelBack.kt）——
 * 会话已经建立，这里再回登录页同样是与登录状态不符的死状态。
 *
 * ## 为什么引导页 → 主界面要走嵌套 [PageSwitcher]，而不是 `if / else`
 *
 * 1. **这是首次访问链路的最后一步，也是唯一没有过渡的一步**：登录流程的每一步
 *    （欢迎 → 介绍 → 填写 → 授权 → 主界面）都有动画，唯独「点确定 → 主界面」是硬切；
 * 2. **返回键**：硬切没有「退场中的旧页」这个概念，而引导页挂着自己的顶层返回处理器。
 *    换成 [PageSwitcher] 后它会自动给退场中的引导页下发 `LocalPageActive = false`，
 *    那 200ms 里引导页的处理器放手，主界面的「再按一次退出」不会被吃掉
 *    （这条机制见 `NAVIGATION-NOTES.md` 规则 1）。
 *
 * 主界面首帧重（首页取数 + 底部导航一起组合），所以 [GateRoute.Main] 标了
 * `heavyFirstFrame`：过渡自动降级为 fade-through，不给方向位移（判据见 [PageLevel.heavyFirstFrame]）。
 */
@Composable
fun LoggedInGate(
    sessionJson: String,
    onLogout: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { SettingsKeys.prefs(context) }
    var configured by remember { mutableStateOf(prefs.getString(SettingsKeys.COMMIT_MODE, null) != null) }

    PageSwitcher(
        state = if (configured) GateRoute.Main else GateRoute.Guide,
        // 兜底返回不在这里：两层各自最清楚自己要什么（引导页 / 主界面都是「再按一次退出」）
        onBack = null,
        modifier = Modifier.fillMaxSize(),
        label = "logged-in-gate",
    ) { route ->
        when (route) {
            GateRoute.Guide -> {
                val confirmExit = rememberTopLevelBackAction()
                PageBackHandler(enabled = true) { confirmExit() }
                CommitModeGuideScreen(
                    onConfirm = { mode ->
                        prefs.edit().putString(SettingsKeys.COMMIT_MODE, mode.name).apply()
                        configured = true
                    },
                    onSkip = { configured = true },
                )
            }

            GateRoute.Main -> MainScreen(
                sessionJson = sessionJson,
                onLogout = onLogout,
            )
        }
    }
}

/**
 * 登录成功后的两格内容（引导页 / 主界面）。
 *
 * 与 `MainRoute` / `LoginState` 同一个写法：**页面身份收敛成一条路由**，
 * 由 [PageSwitcher] 负责渲染与过渡，而不是在 `if` 里换 composable。
 */
private sealed interface GateRoute : PageLevel {
    data object Guide : GateRoute {
        override val depth: Int get() = 0
    }

    data object Main : GateRoute {
        override val depth: Int get() = 1

        override val heavyFirstFrame: Boolean get() = true
    }
}

@Composable
private fun CommitModeGuideScreen(onConfirm: (CommitMode) -> Unit, onSkip: () -> Unit) {
    var selected by remember { mutableStateOf<CommitMode?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Primer.BackgroundPrimary)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
    ) {
        Spacer(Modifier.height(40.dp))
        Text(stringResource(R.string.note_choose_commit_default), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.note_commit_mode_later), fontSize = 13.sp, color = Primer.TextTertiary)
        Spacer(Modifier.height(24.dp))

        CommitMode.entries.forEach { m ->
            GuideOption(m, selected = selected == m) { selected = m }
        }

        Spacer(Modifier.weight(1f))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onSkip, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.action_skip), color = Primer.TextSecondary)
            }
            Button(
                onClick = { selected?.let(onConfirm) },
                enabled = selected != null,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.action_ok))
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun GuideOption(mode: CommitMode, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) Primer.SuccessSurface else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .border(2.dp, if (selected) Primer.Green500 else Primer.Border, CircleShape)
                .background(if (selected) Primer.Green500 else Color.Transparent),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            // 整行卡片：用完整说明（状态位才用 mode.labelRes 的短名）
            Text(stringResource(mode.titleRes), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
            Spacer(Modifier.height(2.dp))
            Text(stringResource(mode.descRes), fontSize = 12.sp, color = Primer.TextTertiary)
        }
    }
}