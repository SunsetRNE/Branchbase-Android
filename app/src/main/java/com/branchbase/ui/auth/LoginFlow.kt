package com.branchbase.ui.auth

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.branchbase.R
import com.branchbase.ui.navigation.PageBackHandler
import com.branchbase.ui.navigation.PageSwitcher
import com.branchbase.MainActivity

/**
 * 登录流程入口：根据 [LoginState] 渲染对应界面，串联整个状态机。
 *
 * ## 这一层只做三件事
 *
 * 1. **把页面态交给 [PageSwitcher]**（换页的唯一机制，见 `LoginState` 的注释）；
 * 2. **把请求态接到页面上**（密钥校验 `keyBusy` / `keyError`、发起授权的 `oauthBusy`）——
 *    这些**不进** [LoginState]，否则状态值一变就会被当成换页（`NAVIGATION-NOTES.md` §三）；
 * 3. **把状态机动作转成回调**（重试 / 取消 / 返回）。
 *
 * 具体怎么画、入场动画怎么排、挂起态怎么切，都在 `LoginScreens.kt` 里 ——
 * 这一层刻意不写任何 `if (busy)`，也不 new 任何布局。
 */
@Composable
fun LoginFlow(
    viewModel: LoginViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    // 两个请求态都独立于页面状态（见 LoginState.KeyInput / OAuthIntroScreen 的注释）
    val keyBusy by viewModel.keyBusy.collectAsState()
    val keyError by viewModel.keyError.collectAsState()
    val oauthBusy by viewModel.oauthBusy.collectAsState()
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
    // 新增流程的页面由**状态机自己表达**（`LoginState.AddAccountWelcome`，见其注释）——
    // 这里不做任何「条件渲染替换整屏」，页面切换完全交给下面的 [PageSwitcher]：
    // 状态一变它必然换页，这是本文件里唯一一处被反复验证过的换页机制。
    //
    // 三次踩坑的教训留在这里，免得后人再走回头路：
    //   1.0.79 用 `DisposableEffect.onDispose` 清标记 → 主界面被撤时把自己取消了；
    //   1.0.81 在 `LoggedIn` 这一个格子里换 composable → `AnimatedContent` 的 contentKey
    //          没变，内容不重放；
    //   1.0.82 读一个旁路状态来决定要不要替换整屏 → 标记置上了，但那一层没重组。
    // 共同点：都在假设「某个状态变了，根布局就会按我想的方式重渲染」。改成状态机换页之后，
    // 这个假设不再需要。
    val addingAccount by viewModel.addingAccount.collectAsState()
    // 登录成功即收尾：清标记，主界面接管（新增的账号若被设为当前，主界面就是新账号的）
    LaunchedEffect(addingAccount, state) {
        // 两条通道一起清（`AddAccountFlow` 是真源、`viewModel.addingAccount` 是给这一层用的），
        // 否则 AddAccountFlow 清了而流还挂着 true —— 下次进账号页判定就不一致
        if (addingAccount && state is LoginState.LoggedIn) viewModel.finishAddAccount()
    }
    // 新增欢迎页的返回键：交给状态机 —— 它会回**已登录的主界面**，而不是退出 App
    // （未登录时的 Idle 欢迎页仍不拦返回，那是「再按一次退出」的既有行为）
    PageBackHandler(enabled = state is LoginState.AddAccountWelcome) { viewModel.back() }

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

            // 「为已有账号新增一个登录」的欢迎页（深度与 Idle 同档）
            is LoginState.AddAccountWelcome -> WelcomeScreen(
                onOAuthLogin = { viewModel.showOAuthIntro() },
                onKeyLogin = { viewModel.showKeyIntro() },
            )

            // 授权登录：流程要点介绍（含数字口令验证说明 + 渲染动画）。
            // `busy` 是「发起授权」这一异步动作的挂起态：没有它，连点会开两个浏览器标签页，
            // 而两次生成的 PKCE 只有后一组是活的（见 OAuthIntroScreen 的参数注释）。
            is LoginState.OAuthIntro -> OAuthIntroScreen(
                onBack = { viewModel.back() },
                onStart = { viewModel.startOAuth() },
                onSwitchToKey = { viewModel.showKeyIntro() },
                busy = oauthBusy,
            )

            // 密钥登录：流程要点介绍（网页端生成密钥 / 勾选权限 + 渲染动画）
            is LoginState.KeyIntro -> KeyIntroScreen(
                onBack = { viewModel.back() },
                onStart = { viewModel.showKeyInput() },
                onSwitchToOAuth = { viewModel.showOAuthIntro() },
            )

            // 密钥填写与校验（错误留在本页，不整页跳走 —— 否则要重新粘贴一次密钥）
            is LoginState.KeyInput -> KeyInputScreen(
                busy = keyBusy,
                error = keyError,
                onBack = { viewModel.back() },
                onSubmit = { token -> viewModel.loginWithKey(token) },
            )

            // 授权中：拉起系统浏览器 + 等深链回调。**这一屏必须有出口**（取消 / 重新打开），
            // 否则用户取消授权再切回来，只能看到一个静止的「正在打开」。
            is LoginState.Authorizing -> AuthorizingScreen(
                authorizeUrl = s.authorizeUrl,
                onBack = { viewModel.back() },
            )

            // 换取 token 中：自动完成，没有可点的东西
            is LoginState.ExchangingToken -> ExchangingTokenScreen()

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

            // 出错：异步链的**失败端**。原来是一行居中的红字（无标题 / 无出口），
            // 现在给「重试（重跑失败的那一步）+ 返回（换一种方式）」两个出口。
            is LoginState.Error -> LoginErrorScreen(
                message = s.message,
                retryLabel = stringResource(
                    if (s.failedAt == LoginFailStep.OAUTH_EXCHANGE) {
                        R.string.action_reauthorize
                    } else {
                        R.string.action_retry
                    },
                ),
                onRetry = { viewModel.retry() },
                onBack = { viewModel.back() },
            )
        }
    }
}
