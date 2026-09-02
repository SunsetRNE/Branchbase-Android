package com.branchbase.ui.auth

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.branchbase.MainActivity
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

    when (val s = state) {
        is LoginState.Idle -> {
            WelcomeScreen(
                onSignIn = { viewModel.startOAuth() },
                onBrowseAsGuest = { viewModel.browseAsGuest() }
            )
        }

        is LoginState.Guest -> {
            // 游客浏览：进入主界面（未登录态）
            Box(Modifier.fillMaxSize().background(Primer.BackgroundPrimary)) {
                Text("游客浏览模式（待接入主界面）", Modifier.align(Alignment.Center), color = Primer.TextTertiary)
            }
        }

        is LoginState.Authorizing -> {
            // 用系统浏览器打开 GitHub 授权页
            LaunchedEffect(s.authorizeUrl) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(s.authorizeUrl))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
            // 打开浏览器期间显示过渡页
            Box(Modifier.fillMaxSize().background(Primer.BackgroundPrimary), contentAlignment = Alignment.Center) {
                Text(
                    "正在打开 GitHub 授权页，请稍候…",
                    color = Primer.TextTertiary
                )
            }
        }

        is LoginState.ExchangingToken -> {
            // 加载过渡页
            Box(Modifier.fillMaxSize().background(Primer.BackgroundPrimary), contentAlignment = Alignment.Center) {
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