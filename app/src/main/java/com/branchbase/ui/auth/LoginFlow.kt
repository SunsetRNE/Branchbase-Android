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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.branchbase.ui.navigation.PageSwitcher
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
    // 密钥页的「请求态」独立于页面状态（见 LoginState.KeyInput 的注释）
    val keyBusy by viewModel.keyBusy.collectAsState()
    val keyError by viewModel.keyError.collectAsState()
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

    // 返回键分两段，按「谁最清楚」划分职责：
    // - 登录流程的中间态（模式介绍页 / 密钥填写 / 授权中 / 换 token / 2FA / 出错）→ 这里回欢迎页；
    // - **已登录的主界面 / 引导页 → 由它们自己处理顶层返回**（“再按一次退出应用”，
    //   见 ui/navigation/TopLevelBack.kt —— 不再回登录页：会话还在，回登录页是与真实
    //   登录状态不符的死状态），所以这里显式排除 LoggedIn，避免两个 BackHandler 抢同一个事件；
    // - 欢迎页（Idle）→ 这里不拦截，交给系统默认行为：**彻底退出 App**（未登录时无需二次确认）。
    BackHandler(enabled = state !is LoginState.Idle && state !is LoginState.LoggedIn) {
        viewModel.back()
    }

    // 登录流程内部也按层级切换（欢迎 0 → 介绍页 1 → 授权中/密钥填写 2 → 主界面 3）：
    // 前进从右滑入、返回向右滑出，与 App 其它页面同一套动效规则。
    PageSwitcher(state = state, modifier = Modifier.fillMaxSize(), label = "login-step") { s ->
        when (s) {
            // 欢迎页：两种登录模式各一个入口（点进去各有流程要点介绍页）
            is LoginState.Idle -> WelcomeScreen(
                onOAuthLogin = { viewModel.showOAuthIntro() },
                onKeyLogin = { viewModel.showKeyIntro() },
            )

            // 授权登录：流程要点介绍（含数字口令验证说明 + 渲染动画）
            is LoginState.OAuthIntro -> OAuthIntroScreen(
                onBack = { viewModel.back() },
                onStart = { viewModel.startOAuth() },
                onSwitchToKey = { viewModel.showKeyIntro() },
            )

            // 密钥登录：流程要点介绍（网页端生成密钥 / 勾选权限 + 渲染动画）
            is LoginState.KeyIntro -> KeyIntroScreen(
                onBack = { viewModel.back() },
                onStart = { viewModel.showKeyInput() },
                onSwitchToOAuth = { viewModel.showOAuthIntro() },
            )

            // 密钥填写与校验（错误留在本页）
            is LoginState.KeyInput -> KeyInputScreen(
                busy = keyBusy,
                error = keyError,
                onBack = { viewModel.back() },
                onSubmit = { token -> viewModel.loginWithKey(token) },
            )

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
                    // 首次在新设备登录时，网页会要求「设备验证」或口令 —— 提前说清，避免以为卡住了
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "若网页要求输入验证码 / 口令，请在网页上完成（首次在新设备登录时会出现）",
                        fontSize = 11.5.sp,
                        color = Primer.TextTertiary,
                        textAlign = TextAlign.Center,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(horizontal = 32.dp),
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
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "正在校验授权…口令验证已在网页端完成",
                        fontSize = 11.5.sp,
                        color = Primer.TextTertiary,
                    )
                }
            }

            is LoginState.NeedTwoFactor -> {
                TwoFactorScreen(onVerify = { code -> viewModel.verifyTwoFactor(code) })
            }

            is LoginState.LoggedIn -> {
                LoggedInGate(
                    sessionJson = s.sessionJson,
                    onLogout = { viewModel.logout() },
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