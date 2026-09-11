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
import com.branchbase.ui.theme.AppIcon
import com.branchbase.ui.theme.Primer

/**
 * 登录流程 Compose 界面（骨架）。
 *
 * 设计基准：360dp × 792dp，状态栏 40dp / 手势条 16dp 安全区。
 * 间距规范：水平内边距 22dp（HorizontalPadding）、按钮圆角 8dp（BtnShape）。
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

        // 应用图标：直接渲染系统当前展示的那枚图标（不再用「蓝底 + 字母 B」占位）
        AppIcon(size = 96.dp, shape = RoundedCornerShape(26.dp))

        Spacer(Modifier.height(22.dp))
        Text("Branchbase", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary)
        Spacer(Modifier.height(6.dp))
        Text("GitHub 第三方客户端", fontSize = 14.sp, color = Primer.TextTertiary)

        Spacer(Modifier.weight(1f))

        PrimaryButton("登录 / Sign in", onSignIn)
        Spacer(Modifier.height(16.dp))
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
