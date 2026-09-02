package com.branchbase.ui.auth

import android.content.Context
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
import com.branchbase.ui.main.MainScreen
import com.branchbase.ui.profile.CommitMode
import com.branchbase.ui.profile.KEY_COMMIT_MODE
import com.branchbase.ui.theme.Primer

/**
 * 登录成功后的「提交模式」引导门（LoggedInGate）。
 *
 * - 若本地已配置提交模式（commit_mode）→ 直接进主界面。
 * - 未配置 → 展示「提交模式」引导屏：确定 = 固化到本地配置；跳过 = 不固化（提交时再问）。
 */
@Composable
fun LoggedInGate(sessionJson: String, onLogout: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("branchbase", Context.MODE_PRIVATE) }
    var configured by remember { mutableStateOf(prefs.getString(KEY_COMMIT_MODE, null) != null) }

    if (!configured) {
        CommitModeGuideScreen(
            onConfirm = { mode ->
                prefs.edit().putString(KEY_COMMIT_MODE, mode.name).apply()
                configured = true
            },
            onSkip = { configured = true },
        )
    } else {
        MainScreen(sessionJson = sessionJson, onLogout = onLogout)
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
        Text("选择编写代码提交时的默认行为", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary)
        Spacer(Modifier.height(6.dp))
        Text("之后可在「设置」里随时更改；选择「跳过」将在提交时再次询问。", fontSize = 13.sp, color = Primer.TextTertiary)
        Spacer(Modifier.height(24.dp))

        CommitMode.entries.forEach { m ->
            GuideOption(m, selected = selected == m) { selected = m }
        }

        Spacer(Modifier.weight(1f))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onSkip, modifier = Modifier.weight(1f)) {
                Text("跳过", color = Primer.TextSecondary)
            }
            Button(
                onClick = { selected?.let(onConfirm) },
                enabled = selected != null,
                modifier = Modifier.weight(1f),
            ) {
                Text("确定")
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
            .background(if (selected) Color(0xFFF0FFF4) else Color.Transparent)
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
            Text(mode.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
            Spacer(Modifier.height(2.dp))
            Text(mode.desc, fontSize = 12.sp, color = Primer.TextTertiary)
        }
    }
}