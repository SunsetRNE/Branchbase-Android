package com.branchbase.ui.repository

import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.branchbase.core.RustBridge
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * README 渲染器（WebView 方案）。
 *
 * 直接用 GitHub 已渲染的 HTML（`readmeHtml` 返回的 `<article class="markdown-body">`），
 * 注入 Primer markdown CSS 后交给 WebView 渲染，复用 GitHub 网页 100% 的排版结果，
 * 彻底规避自写 HTML 解析器 + Compose 手绘的「渲染判定」问题（空白 / 图标覆盖）。
 *
 * 链接拦截：`shouldOverrideUrlLoading` → `RustBridge.resolveLink` → [onLinkClick]。
 * 图片鉴权：`shouldInterceptRequest` 为私有仓库图片注入 `Authorization: token`。
 */
@Composable
fun ReadmeWebView(
    html: String,
    host: String,
    owner: String,
    repo: String,
    branch: String,
    login: String,
    token: String,
    onLinkClick: (Destination) -> Unit,
) {
    val context = LocalContext.current
    val currentOnLinkClick by rememberUpdatedState(onLinkClick)
    var webViewHeight by remember { mutableStateOf(0.dp) }

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
    }

    LaunchedEffect(html, host, owner, repo, branch, login, token) {
        webView.webViewClient = ReadmeWebViewClient(host, owner, repo, branch, login, token, currentOnLinkClick) { cssHeight ->
            // document.body.scrollHeight 返回 CSS px（viewport 缩放后即 dp）
            webViewHeight = cssHeight.dp
        }
        webView.loadDataWithBaseURL(
            "https://$host/$owner/$repo/",
            wrapHtml(html, context),
            "text/html",
            "UTF-8",
            null,
        )
    }

    AndroidView(factory = { webView }, modifier = Modifier.fillMaxWidth().height(webViewHeight))
}

/** 包裹 GitHub HTML：注入 viewport + Primer markdown CSS，保证 WebView 内样式与 App 一致。 */
private fun wrapHtml(body: String, context: android.content.Context): String {
    val css = runCatching {
        context.assets.open("github-markdown-light.css").bufferedReader().use { it.readText() }
    }.getOrDefault("")
    return """
        <!DOCTYPE html><html><head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
        body { margin: 0; padding: 16px; -webkit-text-size-adjust: 100%; }
        $css
        /* GitHub 新自定义元素兜底（github-markdown-css 可能缺失的规则） */
        markdown-accessiblity-table { display: block; }
        themed-picture, picture { display: inline-block; }
        .markdown-heading { position: relative; }
        .markdown-heading .anchor { float: left; margin-left: -20px; opacity: 0; }
        .markdown-heading:hover .anchor { opacity: 1; }
        </style></head><body>$body</body></html>
    """.trimIndent()
}

/** 是否图片资源请求（决定是否走鉴权拦截）。 */
private fun isImage(url: String): Boolean {
    val lower = url.lowercase()
    return lower.endsWith(".svg") || lower.endsWith(".png") || lower.endsWith(".jpg")
        || lower.endsWith(".jpeg") || lower.endsWith(".gif") || lower.endsWith(".webp")
        || lower.endsWith(".bmp") || lower.endsWith(".ico")
}

/** README 链接拦截 + 图片鉴权。 */
private class ReadmeWebViewClient(
    private val host: String,
    private val owner: String,
    private val repo: String,
    private val branch: String,
    private val login: String,
    private val token: String,
    private val onLinkClick: (Destination) -> Unit,
    private val onHeightMeasured: (Int) -> Unit,
) : WebViewClient() {

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        measureHeight(view)
        // 图片异步加载会改变高度，延迟再测一次
        view.postDelayed({ measureHeight(view) }, 400)
    }

    private fun measureHeight(view: WebView) {
        view.evaluateJavascript("document.body.scrollHeight") { result ->
            val h = result.trim().trim('"').toIntOrNull() ?: 0
            if (h > 0) onHeightMeasured(h)
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        val json = RustBridge.resolveLink(url, host, owner, repo, branch, "", login)
        val dest = runCatching { parseDestination(JSONObject(json)) }.getOrNull() ?: return false

        return when (dest.type) {
            // 站外 → 外开浏览器
            "external" -> {
                runCatching { view.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                true
            }
            // 页内锚点 → 让 WebView 自己滚动
            "anchor" -> false
            // 站内 → 走 App 内部导航
            else -> {
                onLinkClick(dest)
                true
            }
        }
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        if (!isImage(url)) return null
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            if (token.isNotEmpty()) conn.setRequestProperty("Authorization", "token $token")
            conn.setRequestProperty("Referer", "https://$host/")
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.connect()
            WebResourceResponse(
                conn.contentType,
                conn.contentEncoding,
                conn.responseCode,
                "OK",
                null, // 响应头非必需，省略以避免 null key（状态行）问题
                conn.inputStream,
            )
        } catch (e: Exception) {
            null // 鉴权/网络失败 → 让 WebView 按默认方式重试
        }
    }
}
