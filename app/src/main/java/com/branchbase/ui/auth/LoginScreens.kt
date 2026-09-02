package com.branchbase.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.core.RustBridge
import com.branchbase.ui.theme.Primer

/**
 * 登录流程 Compose 界面（骨架）。
 *
 * 设计基准：360dp × 792dp，状态栏 40dp / 手势条 16dp 安全区。
 * 间距规范见 docs/login-wireframe.md。
 */

private val BtnShape = RoundedCornerShape(8.dp)
private val HorizontalPadding = 22.dp

/** 主按钮（全宽、主蓝） */
@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = BtnShape,
        colors = ButtonDefaults.buttonColors(containerColor = Primer.Blue500)
    ) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}

/** 次按钮（描边） */
@Composable
private fun OutlineButton(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .border(1.dp, Primer.Border, BtnShape),
        shape = BtnShape,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Primer.TextSecondary)
    ) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * 01 · 欢迎页 WelcomeScreen
 */
@Composable
fun WelcomeScreen(
    onSignIn: () -> Unit,
    onBrowseAsGuest: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Primer.BackgroundPrimary)
            .navigationBarsPadding()
            .padding(horizontal = HorizontalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))

        // Logo 占位（96dp）
        Box(
            modifier = Modifier
                .size(96.dp)
                .background(Primer.Blue500, RoundedCornerShape(26.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("B", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(22.dp))
        Text("Branchbase", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary)
        Spacer(Modifier.height(6.dp))
        Text("GitHub 第三方客户端", fontSize = 14.sp, color = Primer.TextTertiary)

        Spacer(Modifier.weight(1f))

        PrimaryButton("登录 / Sign in", onSignIn)
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onBrowseAsGuest) {
            Text("继续浏览", color = Primer.Blue500, fontSize = 15.sp)
        }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * 02 · 登录方式 LoginMethodScreen
 */
@Composable
fun LoginMethodScreen(
    onGitHubLogin: () -> Unit,
    onPatLogin: () -> Unit,
    onBrowseAsGuest: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Primer.BackgroundPrimary)
            .navigationBarsPadding()
            .padding(horizontal = HorizontalPadding)
            .padding(top = 24.dp),
    ) {
        Text("登录 Branchbase", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary)
        Spacer(Modifier.height(6.dp))
        Text("使用 GitHub 账号安全登录", fontSize = 14.sp, color = Primer.TextTertiary)

        Spacer(Modifier.height(28.dp))
        PrimaryButton("使用 GitHub 登录", onGitHubLogin)

        Spacer(Modifier.height(12.dp))
        OutlineButton("使用 Personal Access Token", onPatLogin)

        Spacer(Modifier.height(18.dp))
        // 分隔线「或」
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f).height(1.dp).background(Primer.Border))
            Text("或", modifier = Modifier.padding(horizontal = 12.dp), color = Primer.TextTertiary, fontSize = 12.sp)
            Box(Modifier.weight(1f).height(1.dp).background(Primer.Border))
        }
        Spacer(Modifier.height(18.dp))

        TextButton(onClick = onBrowseAsGuest, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("先随便逛逛", color = Primer.Blue500, fontSize = 15.sp)
        }
    }
}

/**
 * 04 · 双重验证 TwoFactorScreen
 */
@Composable
fun TwoFactorScreen(
    onVerify: (String) -> Unit,
) {
    var code by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Primer.BackgroundPrimary)
            .navigationBarsPadding()
            .padding(horizontal = HorizontalPadding)
            .padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("双重验证", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary)
        Spacer(Modifier.height(6.dp))
        Text("输入 GitHub 生成的 6 位验证码", fontSize = 14.sp, color = Primer.TextTertiary)

        Spacer(Modifier.height(28.dp))

        // 6 格验证码输入（骨架：真实实现用 BasicTextField 或自定义）
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(6) { i ->
                Box(
                    modifier = Modifier
                        .size(44.dp, 54.dp)
                        .background(Color.White, RoundedCornerShape(8.dp))
                        .border(
                            2.dp,
                            if (i < code.length) Primer.Blue500 else Primer.Border,
                            RoundedCornerShape(8.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (i < code.length) code[i].toString() else "",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Primer.TextPrimary
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        PrimaryButton("验证") {
            if (RustBridge.validateTwoFactor(code)) {
                onVerify(code)
            }
        }
    }
}
