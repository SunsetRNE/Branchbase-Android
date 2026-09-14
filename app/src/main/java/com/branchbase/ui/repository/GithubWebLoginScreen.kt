package com.branchbase.ui.repository

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.branchbase.core.GithubWebSession
import com.branchbase.ui.navigation.PageBackHandler
import com.branchbase.ui.theme.Primer
import com.branchbase.ui.theme.iconTap

/**
 * GitHub 网页会话登录页（内嵌 WebView）。
 *
 * ## 为什么必须让用户单独登录一次
 *
 * 网页端只认浏览器 Cookie，OAuth token 拿不到网页会话（见 [GithubWebSession] 的说明）。
 * 而「自定义通知」这类能力只有网页端有 —— 这是唯一的取舍：要么登录一次换全能力，
 * 要么放弃该能力。
 *
 * ## 交互约定
 *
 * - 直接打开目标仓库页：未登录时 GitHub 自己会跳登录页，**登录成功后自动回到仓库页**，
 *   不需要我们自己拼 `return_to`；
 * - 用页面上的 `<meta name="user-login">` 判定登录结果（未登录时该 meta 是空串），
 *   比看 URL 可靠 —— 登录流程里 URL 会经过 `/session`、2FA、`/sessions/two-factor` 等好几跳；
 * - 登录成功即**自动**抓取 Cookie，用户点「完成」只是确认；返回键先在网页里后退，
 *   退不动了才离开本页（否则登录中途按返回会直接退出）。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GithubWebLoginScreen(
    host: String,
    repoPath: String,
    onBack: () -> Unit,
    onLoggedIn: (String) -> Unit,
) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var login by remember { mutableStateOf("") }
    // 已抓到的会话（点「完成」时才回调，避免登录途中反复触发上层刷新）
    var captured by remember { mutableStateOf(false) }

    PageBackHandler {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else onBack()
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                destroy()
            }
            webView = null
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Primer.BackgroundPrimary)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        DetailTopBar(title = "登录 GitHub 网页会话", onBack = onBack)

        Text(
            text = "App 内的星标、关注、复刻都走官方 API，不需要这一步。\n" +
                "只有「自定义通知（Custom）」需要网页会话 —— " +
                "登录后 Cookie 只保存在本机，用于 github.com 的网页端点。",
            fontSize = 12.sp,
            color = Primer.TextTertiary,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        settings.userAgentString = WEB_USER_AGENT
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                // 未登录时 user-login 是空串；登录后是登录名
                                view.evaluateJavascript(
                                    "(function(){var m=document.querySelector('meta[name=\"user-login\"]');return m?m.content:'';})()",
                                ) { raw ->
                                    val name = raw.orEmpty().trim().trim('"')
                                    if (name.isNotEmpty()) {
                                        login = name
                                        if (!captured) {
                                            captured = GithubWebSession.capture(ctx, host, name) != null
                                        }
                                    }
                                }
                            }
                        }
                        loadUrl("https://$host$repoPath")
                        webView = this
                    }
                },
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = if (login.isNotEmpty()) "已登录：$login" else "请在下方网页中登录 GitHub",
                fontSize = 12.sp,
                color = if (login.isNotEmpty()) Primer.Green500 else Primer.TextTertiary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "完成",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (login.isNotEmpty()) Primer.OnEmphasis else Primer.TextTertiary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (login.isNotEmpty()) Primer.Green500 else Primer.Gray150)
                    .iconTap(enabled = login.isNotEmpty()) {
                        // 再抓一次：登录刚完成时页面可能还在跳转，onPageFinished 未必抓到
                        GithubWebSession.capture(context, host, login)
                        onLoggedIn(login)
                    }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
        Spacer(Modifier.height(2.dp))
    }
}

/**
 * 与 Rust 侧 `WEB_UA` 保持一致的桌面 UA。
 *
 * 必须一致：网页版的 `react-app.embeddedData`（判定数据的载体）只在桌面 UA 下输出，
 * 而会话是 WebView 建立的 —— 两边 UA 不一致时，抓到的页面结构与登录时的页面不同源。
 */
private const val WEB_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
