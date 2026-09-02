package com.branchbase.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.branchbase.ui.theme.Primer
import org.json.JSONObject

/**
 * 登录成功界面：解析 sessionJson，展示当前登录用户信息，提供登出。
 */
@Composable
fun LoggedInScreen(
    sessionJson: String,
    onLogout: () -> Unit,
) {
    // 解析会话 JSON，提取用户信息（失败则用占位）
    val user = runCatching { JSONObject(sessionJson).getJSONObject("user") }.getOrNull()
    val login = user?.optString("login", "未知用户") ?: "未知用户"
    val name = user?.optString("name")?.takeIf { it.isNotBlank() }
    val avatarUrl = user?.optString("avatar_url")?.takeIf { it.isNotBlank() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Primer.BackgroundPrimary)
            .statusBarsPadding()
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(80.dp))

        // 头像（真实图片，失败回退首字母）
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(Primer.Blue500),
            contentAlignment = Alignment.Center
        ) {
            if (avatarUrl != null) {
                AsyncImage(
                    model = avatarUrl,
                    contentDescription = login,
                    modifier = Modifier.size(96.dp).clip(CircleShape),
                )
            } else {
                Text(
                    login.take(1).uppercase(),
                    color = Color.White,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(login, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary)
        if (name != null) {
            Spacer(Modifier.height(6.dp))
            Text(name, fontSize = 15.sp, color = Primer.TextTertiary)
        }

        Spacer(Modifier.height(32.dp))

        // 已登录提示
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 60.dp)
                .background(Primer.Green100, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "✓ 已登录 GitHub",
                modifier = Modifier.padding(vertical = 14.dp),
                color = Primer.Green500,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
        }

        Spacer(Modifier.weight(1f))

        // 登出按钮
        TextButton(onClick = onLogout) {
            Text("登出", color = Primer.Red500, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(32.dp))
    }
}