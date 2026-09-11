package com.branchbase.ui.auth

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.branchbase.ui.navigation.TabSwitcher
import com.branchbase.MainActivity
import com.branchbase.ui.theme.AppIcon
import com.branchbase.ui.theme.Primer

/**
 * 登录流程入口：根据 [LoginState] 渲染对应界面，串联整个状态机。
 */
@Composable
fun LoginFlow(
    viewModel: LoginViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 监听 ON_RESUME：OAuth 授权完成后从浏览器跳回 App，消费深链回调的授权码
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val code = MainActivity.pendingAuthCode
                if (code != null) {
                    MainActivity.pendingAuthCode = null
                    viewModel.consumeDeepLinkCode(code)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 返回键：非初始态时拦截，回到欢迎页（而非直接退出 App）
    BackHandler(enabled = state !is LoginState.Idle) {
        viewModel.cancel()
    }

    // 登录流程的每一步都是「同一个页面的状态变化」（欢迎 → 授权中 → 换 token → 2FA → 进入主界面），
    // 彼此没有前后层级关系，所以用同级淡入淡出；原来是硬切，从浏览器授权回来时观感像闪屏。
    TabSwitcher(state = state, modifier = Modifier.fillMaxSize(), label = "login-step") { s ->
        when (s) {
            is LoginState.Idle -> {
            WelcomeScreen(onSignIn = { viewModel.startOAuth() })
        }

            is LoginState.Authorizing -> {
                // 用系统浏览器打开 GitHub 授权页
                LaunchedEffect(s.authorizeUrl) {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(s.authorizeUrl))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
                // 打开浏览器期间显示过渡页（带应用图标，避免一整屏空白）
                Column(
                    Modifier.fillMaxSize().background(Primer.BackgroundPrimary),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    AppIcon(size = 72.dp, shape = RoundedCornerShape(20.dp))
                    Spacer(Modifier.height(18.dp))
                    Text(
                        "正在打开 GitHub 授权页，请稍候…",
                        color = Primer.TextTertiary
                    )
                }
            }

            is LoginState.ExchangingToken -> {
                // 加载过渡页（换取 token 期间）
                Column(
                    Modifier.fillMaxSize().background(Primer.BackgroundPrimary),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    AppIcon(size = 72.dp, shape = RoundedCornerShape(20.dp))
                    Spacer(Modifier.height(20.dp))
                    CircularProgressIndicator(color = Primer.Blue500)
                }
            }

            is LoginState.NeedTwoFactor -> {
                TwoFactorScreen(onVerify = { code -> viewModel.verifyTwoFactor(code) })
            }

            is LoginState.LoggedIn -> {
                LoggedInGate(
                    sessionJson = s.sessionJson,
                    onLogout = { viewModel.logout() }
                )
            }

            is LoginState.Error -> {
                Box(Modifier.fillMaxSize().background(Primer.BackgroundPrimary), contentAlignment = Alignment.Center) {
                    Text(s.message, color = Primer.Red500)
                }
            }
        }
    }
}