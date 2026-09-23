package com.branchbase.ui.auth

import android.content.Intent
import android.net.Uri
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.branchbase.ui.log.Logger
import com.branchbase.ui.navigation.PageBackHandler
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

    /** 登录流程的中间态（模式介绍 / 密钥填写 / 授权中 / 换 token / 2FA / 出错）算「子页」。 */
    fun isLoginStep(s: LoginState): Boolean = s !is LoginState.Idle && s !is LoginState.LoggedIn

    // 登录流程内部也按层级切换（欢迎 0 → 介绍页 1 → 授权中/密钥填写 2 → 主界面 3）：
    // 前进从右滑入、返回向右滑出，与 App 其它页面同一套动效规则。
    //
    // contentKey 取「状态类型」而不是状态本身：`Authorizing(url, verifier)` / `LoggedIn(json)` /
    // `Error(message)` 都是**带 payload 的 data class**，payload 在同页内一变（例如连错两次、
    // 换一个授权地址），按「状态本身」算就会被当成换了一页、白播一次切换动画。
    // 页面身份只有「哪一步」这一层（见 LoginState 的注释：页面态与请求态分开），
    // 所以身份用 `::class` 恰好。
    // 返回键分两段，按「谁最清楚」划分职责：
    // - 登录流程的中间态（模式介绍页 / 密钥填写 / 授权中 / 换 token / 2FA / 出错）→ 回欢迎页；
    // - **已登录的主界面 / 引导页 → 由它们自己处理顶层返回**（“再按一次退出应用”，
    //   见 ui/navigation/TopLevelBack.kt —— 不再回登录页：会话还在，回登录页是与真实
    //   登录状态不符的死状态）；
    // - 欢迎页（Idle）→ 不拦截，交给系统默认行为：**彻底退出 App**（未登录时无需二次确认）。
    //
    // 2026-09 起这一段不再自己挂 BackHandler：兜底收到 [PageSwitcher] 的 `onBack` 里
    // （`isSubPage` 精确描述「哪些格子才该被兜底」）。注意**不能**用默认的 `depth > 0`：
    // `LoggedIn` 的 depth 是 3（位移动画靠它），但它不是子页 —— 拿默认判据会让登录流程
    // 吃掉主界面的返回键，「再按一次退出」直接失灵。
    // 「新增账号」流程中：登录界面**接管整屏**（不组合主界面）。
    //
    // 为什么必须不组合：主界面里就是那个账号页，若两者同时存在会叠两层；
    // 而整屏替换的代价只是「返回时重建一次主界面」—— 与切账号同一条路径，可接受。
    //
    // 登录**成功**时收尾：清掉标记，让主界面接管（新增的账号若被设为当前，主界面就是新账号的）。
    // 用 LaunchedEffect 而不是在渲染里写 state：写状态要放在副作用里。
    val addingAccount by AddAccountFlow.activeState
    // 登录成功即收尾：清掉标记，主界面接管。用 LaunchedEffect 而不是在渲染里写 ——
    // 写状态要放在副作用里，否则重组期间改状态会引发下一帧再重组。
    LaunchedEffect(addingAccount, state) {
        if (addingAccount && state is LoginState.LoggedIn) AddAccountFlow.finish()
    }
    // 新增流程里、且还没登录成功 → 登录界面接管整屏（下面 [PageSwitcher] 里
    // `LoggedIn` 那一格会被换成欢迎页，因此主界面根本不会组合）。
    //
    // 为什么不单独写一条渲染路径：登录流程的步骤与位移动画都收在下面这一个
    // [PageSwitcher] 里，另起一份必然分叉。这里只是把「已登录」这一格换掉。
    val addingInProgress = addingAccount && state !is LoginState.LoggedIn
    // 欢迎页**在新增流程里要拦返回**：`LoginState.Idle` 平时刻意不拦截（未登录时是
    // 「再按一次退出 App」），但从账号页进来的用户按返回是想回账号列表 ——
    // 不拦的话既回不去、还可能直接退出应用（用户报的就是这个）。
    // 深层页面（介绍 / 填密钥 / 2FA…）仍交给状态机自己回退到欢迎页。
    PageBackHandler(enabled = addingInProgress && state is LoginState.Idle) { AddAccountFlow.finish() }

    // 诊断：给「点了添加账号但界面不动」那类问题定性。
    // 出问题时看得到「标记有没有被这一层读到」—— 上一轮正因为没有这行，日志里只剩一句
    // 「点了添加账号」，完全分不清是「标记没置上」还是「置上了但这一层没重组」。
    //
    // 只在**值变化**时记：登录页播放动画期间这一层会频繁重组，每帧一行会把日志刷满。
    val watch = remember { mutableStateOf("") }
    LaunchedEffect(addingAccount, state::class) {
        val now = "新增流程=$addingAccount 状态=${state::class.simpleName} 接管=$addingInProgress"
        if (watch.value != now) {
            watch.value = now
            Logger.ui("登录根布局：$now", "Compose")
        }
    }

    if (addingInProgress) {
        // ⚠️ 这里**不走 [PageSwitcher]**，直接渲染欢迎页。
        //
        // 原因是实测出来的：`PageSwitcher` 底层是 `AnimatedContent`，而它的 `contentKey`
        // 取的是 `state::class`。新增流程中 `state` 仍是 `LoginState.LoggedIn`（用户本来就
        // 登录着），key 完全没变 —— 于是「同一格里把 LoggedInGate 换成 WelcomeScreen」
        // 这件事 AnimatedContent **不会重放内容**，界面纹丝不动。
        // 真机日志里那 5 次「点「添加账号」」后面什么都没有，正是这个形状。
        //
        // 这一步只需要「显示欢迎页（选登录方式）」这一格，不需要位移动画，
        // 所以直接渲染反而更贴合语义，也少一层对 AnimatedContent 行为的依赖。
        WelcomeScreen(
            onOAuthLogin = { viewModel.showOAuthIntro() },
            onKeyLogin = { viewModel.showKeyIntro() },
        )
        return
    }

    PageSwitcher(
        state = state,
        onBack = { viewModel.back() },
        isSubPage = ::isLoginStep,
        modifier = Modifier.fillMaxSize(),
        label = "login-step",
        contentKey = { it::class },
    ) { s ->
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
                // 走到这里说明不在新增流程里（新增流程在上面就 return 了）
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