package com.branchbase.ui.repository

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.branchbase.core.RustBridge
import com.branchbase.ui.theme.LocalIsDarkTheme
import com.branchbase.translate.TranslateBridge
import com.branchbase.translate.TranslatePage
import com.branchbase.translate.TranslateRuntime
import com.branchbase.translate.TranslateSettings
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

/**
 * README 渲染器（WebView 方案）。
 *
 * 直接用 GitHub 已渲染的 HTML（`readmeHtml` 返回的 `<article class="markdown-body">`），
 * 注入 Primer markdown CSS 后交给 WebView 渲染，复用 GitHub 网页 100% 的排版结果，
 * 彻底规避自写 HTML 解析器 + Compose 手绘的「渲染判定」问题（空白 / 图标覆盖）。
 *
 * ## 徽章 / 图表渲染的四条硬约束（实测 GitHub API 输出后固化）
 *
 * 1. **相对图片必须先归一化**：`GET /repos/{o}/{r}/readme`（`Accept: html+json`）返回的 HTML
 *    保留 markdown 里的相对路径（`<img src="images/logo.png">`），只有 `data-path` 能告诉我们是
 *    哪个文件。相对路径按 README 所在目录解析后是 `/{o}/{r}/blob/{b}/…`，而该形态返回的是
 *    **HTML 页面而不是图片字节**（实测 302 → `text/html`）。因此：
 *    - 文档基准（`loadDataWithBaseURL`）取 README 所在目录的 **raw** 形态，图片天然落到
 *      `/{o}/{r}/raw/{b}/…`（实测 302 → `raw.githubusercontent.com` → `image/png`）；
 *    - 相对**链接**会因此解析成 raw 形态，导航时再还原成 blob/tree，语义与网页端一致。
 * 2. **徽章大多来自 camo**：GitHub 把图片改写成 `https://camo.githubusercontent.com/<sha>/<hex>`，
 *    路径**没有扩展名** —— 用 `endsWith(".png")` 判断「是不是图片」永远不成立。这里改用
 *    请求头 `Accept` 以 `image/` 开头判定，扩展名只作兜底。
 * 3. **鉴权只发给 GitHub 自有域名**：README 里的徽章可能来自 shields.io / github-readme-stats
 *    等站外域名，绝不能把 PAT 附带给它们（旧实现按扩展名判定，裸 `<img src="…shields.io…svg">`
 *    会真的把 token 发出去）。
 * 4. **高度必须持续跟随**：徽章（camo）、统计卡（vercel 冷启动可达数秒）、`<details>` 展开都会
 *    在首屏之后改变高度。旧实现只在 `onPageFinished` 与 +400ms 各测一次，于是出现「底部被裁切 /
 *    大段留白」。现在由注入脚本用 `ResizeObserver` + 图片 load/error 事件持续上报高度。
 *
 * 链接拦截：`shouldOverrideUrlLoading` → `RustBridge.resolveLink` → [onLinkClick]。
 * 图片鉴权：`shouldInterceptRequest` 只为 GitHub 自有域名的图片附带 token，并落磁盘缓存。
 */
@SuppressLint("SetJavaScriptEnabled")
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
    // 1dp 起步：WebView 视口为 0 时也能加载并测量，测量结果到达后立即撑开
    var webViewHeight by remember { mutableStateOf(1.dp) }

    // README 真实路径（GitHub 在 HTML 外层给出 data-path）→ 相对路径的基准目录
    val readmePath = remember(html) { readmePathOf(html) }
    val baseDir = remember(readmePath) { baseDirOf(readmePath) }
    // 文档基准 = README 所在目录的 raw 形态（相对图片直接命中 raw 字节）
    val documentUrl = remember(host, owner, repo, branch, baseDir) {
        rawBaseUrl(host, owner, repo, branch, baseDir)
    }
    val imageCache = remember(context) { ReadmeImageCache(context) }
    // 沉浸式翻译设置：**进入页面读一次**。若每次重组都读，html 字符串会随之变化，
    // LaunchedEffect(html, …) 会重启 → 整个 WebView 重新加载，页面被反复刷新。
    val translateConfig = remember { TranslateSettings.read(context) }
    // 页面侧资产（译文 CSS + 四个脚本 + 设置注入）由 :translate 模块装载，
    // 这里只负责把它们内联进 HTML —— 正文渲染器不需要知道翻译是怎么实现的
    // 深色主题：正文页由 WebView 渲染，CSS 必须跟着换（这部分 Compose 管不到）
    val darkTheme = LocalIsDarkTheme.current
    val translatePage = remember(translateConfig, darkTheme) {
        TranslatePage.load(context, translateConfig, darkTheme)
    }
    val heightBridge = remember { HeightBridge() }
    // 沉浸式翻译：JS 发一批待译文本 → 原生侧串行翻译 → 结果与状态推回页面
    val translateScope = rememberCoroutineScope()

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            addJavascriptInterface(heightBridge, "BBReadme")
        }
    }

    // 桥必须在页面脚本执行前注册（脚本里会调用 window.BBTranslate.request）
    val translateBridge = remember {
        TranslateBridge(
            scope = translateScope,
            translator = TranslateRuntime.translator,
            onResult = { id, toLang, translations ->
                val payload = JSONObject.wrap(translations)?.toString() ?: "[]"
                webView.evaluateJavascript(
                    "window.__bbTranslated(${JSONObject.quote(id)}, ${JSONObject.quote(toLang)}, ${JSONObject.quote(payload)})",
                    null,
                )
            },
            onStatus = { state ->
                // 额度用尽 / 连续失败熔断：推到页面，让按钮变成「可重试」而不是毫无反应
                webView.post {
                    runCatching {
                        webView.evaluateJavascript(
                            "window.__bbTranslateStatus && window.__bbTranslateStatus(${JSONObject.quote(state)})",
                            null,
                        )
                    }
                }
            },
        ).also { webView.addJavascriptInterface(it, "BBTranslate") }
    }

    // 注入脚本在非主线程回调；统一 post 回主线程再更新 Compose 状态
    SideEffect {
        heightBridge.onHeight = { raw ->
            webView.post {
                val h = raw.coerceIn(1, MAX_README_HEIGHT).dp
                if (h != webViewHeight) webViewHeight = h
            }
        }
    }
    DisposableEffect(webView) {
        onDispose {
            heightBridge.onHeight = null
            runCatching { webView.removeJavascriptInterface("BBTranslate") }
            runCatching { webView.destroy() }
        }
    }

    LaunchedEffect(html, host, owner, repo, branch, login, token, documentUrl) {
        webView.webViewClient = ReadmeWebViewClient(
            host = host,
            owner = owner,
            repo = repo,
            branch = branch,
            login = login,
            token = token,
            baseDir = baseDir,
            documentUrl = documentUrl,
            imageCache = imageCache,
            onLinkClick = { currentOnLinkClick(it) },
            onHeightMeasured = { cssHeight ->
                val h = cssHeight.coerceIn(1, MAX_README_HEIGHT).dp
                if (h != webViewHeight) webViewHeight = h
            },
        )
        webView.loadDataWithBaseURL(
            documentUrl,
            wrapHtml(html, context, translatePage, darkTheme),
            "text/html",
            "UTF-8",
            null,
        )
    }

    AndroidView(factory = { webView }, modifier = Modifier.fillMaxWidth().height(webViewHeight))
}

// ── HTML 基准与 URL 归一化（纯 Kotlin，可单测） ──

/** 超长 README 的高度上限（超出部分由 WebView 内部滚动，避免 LazyColumn 出现巨型 item）。 */
private const val MAX_README_HEIGHT = 20_000

/** 从 GitHub 返回的 HTML 里取 README 路径（`<div id="readme" data-path="docs/README.md">`）。 */
private val README_WRAPPER_PATH_RE = Regex("<[^>]*id=\"readme\"[^>]*data-path=\"([^\"]+)\"")
private val README_PATH_RE = Regex("data-path=\"([^\"]+)\"")

/**
 * 取 README 真实路径。
 *
 * 先认 `id="readme"` 外层容器上的 `data-path`，再退回「文档里第一个 data-path」：
 * 后者在 README 正文自己贴了一段含 `data-path` 的 HTML 时会取错路径，
 * 而 README 路径决定相对图片/链接的基准目录 —— 取错就是整篇图片错位。
 */
internal fun readmePathOf(html: String): String =
    (README_WRAPPER_PATH_RE.find(html) ?: README_PATH_RE.find(html))
        ?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: "README.md"

/** README 所在目录（`docs/README.md` → `docs/`；根目录 README → 空串）。 */
internal fun baseDirOf(path: String): String = path.substringBeforeLast('/', "").let {
    if (it.isEmpty()) "" else "$it/"
}

/** 轻量 URL 解析（不依赖 Android Uri，便于 JVM 单测）。 */
internal data class SimpleUrl(val host: String?, val path: String?)

/**
 * 解析 URL 的 host / path。
 *
 * `java.net.URI` 对未编码的空格 / 非 ASCII 会抛异常（`![](我的 图.png)` 这类源文档很常见），
 * 直接返回 null 会让「鉴权 + 磁盘缓存」整条链路静默失效（私有仓库图片裂图）。
 * 因此解析失败时退回手工拆分，至少把 host / path 拿到。
 */
internal fun parseUrl(url: String): SimpleUrl? {
    runCatching {
        val uri = java.net.URI(url)
        return SimpleUrl(uri.host, uri.path)
    }
    val rest = url.substringAfter("://", "")
    if (rest.isEmpty()) return null // 相对 URL / 非 http(s)：保持原语义（null）
    val host = rest.substringBefore('/').substringBefore('?').substringBefore('#')
    if (host.isEmpty()) return null
    val path = "/" + rest.substringAfter('/', "").substringBefore('?').substringBefore('#')
    return SimpleUrl(host, path)
}

/**
 * raw 形态用的 ref 段（空分支回退 HEAD）。
 * 编码复用同包 [encodePath]：逐段编码且保留 `/`，分支名里的 `/` 必须原样留在 URL 中。
 */
internal fun rawRef(branch: String): String = encodePath(branch.ifBlank { "HEAD" })

/** README 所在目录的 raw 基准 URL（相对图片/链接的解析基准）。 */
internal fun rawBaseUrl(host: String, owner: String, repo: String, branch: String, baseDir: String): String =
    "https://$host/$owner/$repo/raw/${rawRef(branch)}/${encodePath(baseDir)}"

/** 同文档锚点：URL 与文档基准只差 fragment（WebView 自己滚动，不进 App 路由）。 */
internal fun isSameDocumentAnchor(url: String, documentUrl: String): Boolean {
    val i = url.indexOf('#')
    if (i < 0) return false
    return url.substring(0, i) == documentUrl.substringBefore('#')
}

/**
 * 导航 URL 归一化：文档基准是 raw 形态，相对链接会被解析成 `/{o}/{r}/raw/{b}/…`，
 * 这里还原成网页端形态（blob/tree），`resolveLink` 才能给出文件跳转目标。
 */
internal fun toNavigationUrl(url: String, host: String, owner: String, repo: String, branch: String): String? {
    val parts = githubRepoParts(url, host, owner, repo) ?: return null
    val (ownerSeg, repoSeg, kind, rest) = parts
    if (kind == "blob" || kind == "tree") return null // 已是网页形态
    val ref = rawRef(branch)
    if (!rest.startsWith("$ref/")) return null
    val file = rest.substring(ref.length + 1)
    if (file.isEmpty()) return null
    // 末尾 `/` 判目录（对齐 html-parser-design.md §5.1）
    val target = if (file.endsWith("/")) "tree" else "blob"
    return "https://$host/$ownerSeg/$repoSeg/$target/$ref/$file"
}

/**
 * 图片请求归一化：`/{o}/{r}/(blob|raw)/{b}/…` → raw 字节地址。
 * github.com 走 `raw.githubusercontent.com`（直连，避免 302 丢鉴权头）；GHE 走自身 raw 路径。
 */
internal fun rawImageUrl(url: String, host: String, owner: String, repo: String, branch: String): String? {
    val parsed = parseUrl(url) ?: return null
    val urlHost = parsed.host?.lowercase() ?: return null
    if (urlHost == "raw.githubusercontent.com") return url
    if (urlHost != host.lowercase()) return null
    val parts = githubRepoParts(url, host, owner, repo) ?: return null
    val (ownerSeg, repoSeg, _, rest) = parts
    val ref = rawRef(branch)
    if (!rest.startsWith("$ref/")) return null
    val file = rest.substring(ref.length + 1)
    if (file.isEmpty()) return null
    return if (host.equals("github.com", ignoreCase = true)) {
        "https://raw.githubusercontent.com/$ownerSeg/$repoSeg/$ref/$file"
    } else {
        "https://$host/$ownerSeg/$repoSeg/raw/$ref/$file"
    }
}

/** 拆出 `/{o}/{r}/{kind}/{rest…}`（owner/repo 大小写不敏感，返回 URL 中的原始大小写）。 */
internal fun githubRepoParts(
    url: String,
    host: String,
    owner: String,
    repo: String,
): GitHubRepoParts? {
    val parsed = parseUrl(url) ?: return null
    if (!parsed.host.equals(host, ignoreCase = true)) return null
    val path = parsed.path ?: return null
    // 只去掉前导 `/`，保留末尾 `/` —— 它是「目录链接」的唯一信号（raw 形态没有 tree 段）
    val segs = path.trimStart('/').split('/')
    if (segs.size < 4) return null
    if (!segs[0].equals(owner, ignoreCase = true) || !segs[1].equals(repo, ignoreCase = true)) return null
    val kind = segs[2].lowercase()
    if (kind != "blob" && kind != "raw" && kind != "tree") return null
    return GitHubRepoParts(segs[0], segs[1], kind, segs.drop(3).joinToString("/"))
}

internal data class GitHubRepoParts(val owner: String, val repo: String, val kind: String, val rest: String)

/** `Accept` 以 `image/` 开头即视为图片（camo 等无扩展名地址只能靠它）。 */
internal fun isImageAccept(accept: String?): Boolean = accept?.startsWith("image/") == true

/** 扩展名兜底判定。 */
internal fun hasImageExtension(path: String?): Boolean {
    val lower = path?.lowercase() ?: return false
    return IMAGE_EXTENSIONS.any { lower.endsWith(it) }
}

/** 是否图片子资源：优先看 `Accept`（camo 等无扩展名地址也能命中），扩展名兜底。 */
private fun isImageRequest(url: String, request: WebResourceRequest): Boolean {
    val accept = request.requestHeaders?.entries
        ?.firstOrNull { it.key.equals("Accept", ignoreCase = true) }?.value
    return isImageAccept(accept) || hasImageExtension(parseUrl(url)?.path)
}

private val IMAGE_EXTENSIONS = listOf(
    ".svg", ".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".ico", ".avif", ".apng",
)

/** 附带凭据的 host 白名单（其余域名一律不发送 token）。 */
private val AUTH_HOSTS = setOf("github.com", "raw.githubusercontent.com")

/**
 * 包裹 GitHub HTML：注入 viewport + Primer markdown CSS + 渲染增强脚本 + 翻译页面资产。
 *
 * 这里只做**拼装**：渲染增强脚本（高度/锚点/宽图）属于正文渲染，留在本文件；
 * 译文样式与页面脚本属于翻译功能，来自 `:translate` 模块的 [TranslatePage]
 * （`assets/translate/` 下的 CSS 与脚本）。这样「改译文样式」不需要动正文渲染器。
 */
private fun wrapHtml(body: String, context: Context, translate: TranslatePage.Assets, dark: Boolean): String {
    val css = runCatching {
        context.assets.open("github-markdown-light.css").bufferedReader().use { it.readText() }
    }.getOrDefault("")
    return """
        <!DOCTYPE html><html><head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <meta name="color-scheme" content="${if (dark) "dark" else "light"}">
        <style>
        /* color-scheme 只影响滚动条 / 表单控件的 UA 配色，
           它**不**改变 prefers-color-scheme —— 后者是「系统偏好」，由 uiMode 决定，
           与应用里可强制切换的主题档位可以不一致。GitHub 的暗色图源
           （<picture><source media="(prefers-color-scheme: dark)">）因此需要脚本按
           [BB_DARK] 手动纠正，见 README_ENHANCE_JS 的 fixThemedPictures()。 */
        html { color-scheme: ${if (dark) "dark" else "light"}; }
        body { margin: 0; padding: 16px; -webkit-text-size-adjust: 100%; }
        $css
        ${if (dark) README_DARK_CSS else ""}
        /* GitHub 新自定义元素兜底（github-markdown-css 可能缺失的规则） */
        themed-picture, picture { display: inline-block; }
        .markdown-heading { position: relative; }
        .markdown-heading .anchor { float: left; margin-left: -20px; opacity: 0; }
        .markdown-heading:hover .anchor { opacity: 1; }
        ${translate.css}
        </style></head><body>$body
        <script>window.BB_DARK = $dark;</script>
        <script>$README_ENHANCE_JS</script>
        <script>${translate.configScript}</script>
        <script>${translate.js}</script></body></html>
    """.trimIndent()
}

/**
 * README 渲染增强脚本（原生 JS，无外部依赖）。
 *
 * ① 高度上报：ResizeObserver + 图片 load/error + 字体就绪 + `<details>` 展开 → 去抖上报
 *    （徽章 / 统计卡 / 图表晚加载导致的裁切与留白都靠它消除）。
 * ② 锚点修复：GitHub 输出 `id="user-content-x"` 而 `href="#x"`，浏览器找不到目标 → 改写 href。
 * ③ Mermaid 图表：GitHub API 只返回高亮源码（`div.highlight-source-mermaid`），加标题条说明。
 * ④ 暗色图源纠正：应用主题 ≠ 系统 uiMode 时，按 `BB_DARK` 手动选定 `<picture>` 的图源。
 *
 * **不再改图片尺寸 / 位置**：曾有一版把超宽图片包进横向滚动容器并强制原始像素宽，
 * 结果把「一行 4 张徽章」拆成一张一行且奇大。图片尺寸一律由 CSS（`max-width: 100%`）
 * 与作者声明决定，脚本只读不写。
 */
private val README_ENHANCE_JS = """
(function () {
  var lastReported = -1;
  var scheduled = false;

  // 只取 body 的高度：documentElement.scrollHeight 在「视口比内容高」时会回吐视口高度，
  // 导致折叠 <details> 后高度降不下来（底部留白）。body.scrollHeight 始终是内容高度。
  function docHeight() {
    var b = document.body;
    if (!b) return 0;
    // +8：兜住末元素 margin 塌陷 / 亚像素取整，宁可多几像素也不要裁掉内容
    return Math.ceil(b.scrollHeight) + 8;
  }

  function report() {
    var h = docHeight();
    if (h > 0 && h !== lastReported) {
      lastReported = h;
      try { BBReadme.reportHeight(h); } catch (e) {}
    }
  }

  function schedule() {
    if (scheduled) return;
    scheduled = true;
    requestAnimationFrame(function () { scheduled = false; report(); });
  }

  function fixAnchors() {
    var links = document.querySelectorAll('a[href^="#"]');
    for (var i = 0; i < links.length; i++) {
      var a = links[i];
      var id = (a.getAttribute('href') || '').slice(1);
      if (!id || document.getElementById(id)) continue;
      if (document.getElementById('user-content-' + id)) {
        a.setAttribute('href', '#user-content-' + id);
      }
    }
  }

  function decorateMermaid() {
    var blocks = document.querySelectorAll('.highlight-source-mermaid');
    for (var i = 0; i < blocks.length; i++) {
      var b = blocks[i];
      if (b.getAttribute('data-bb-chart')) continue;
      b.setAttribute('data-bb-chart', '1');
      var head = document.createElement('div');
      head.className = 'bb-chart-head';
      head.textContent = 'Mermaid 图表 · 源码';
      b.insertBefore(head, b.firstChild);
    }
  }

  // ⚠️ 这里**不再**做「超宽图片 → 原始像素宽 + 横向滚动容器」的处理（.bb-scroll-x 已移除）。
  // 那套逻辑造成过一次真实回归：GitHub 把徽章 / 头像行输出成 `<p>` 里一串 `<a><img></a>`，
  // 而它是**块级**容器 + 强制 `naturalWidth` —— 结果是
  // 「网页端一排 4 张图 → App 里一张一行、且奇大」，并覆盖作者自己的 width 属性
  // （28px 的 logo、250×55 的徽章部件全被放大）。
  // 现在图片完全交给 CSS 的 `max-width: 100%`（与 GitHub 网页一致）：
  // 行内流不被打断，尺寸以作者声明为准。

  // ── 暗色图源纠正 ──
  // GitHub 把 `#gh-dark-mode-only` 图片渲染成
  // <picture><source media="(prefers-color-scheme: dark)" srcset="…"><img src="…"></picture>。
  // WebView 的 prefers-color-scheme 跟随**系统** uiMode，而 App 的主题档位可以
  // 「系统浅色 + 强制深色」（或反过来）——不一致时暗色图源永远不生效（深色页面里冒出白底图）。
  // 这里按 App 的真实主题选定图源，并移除 <source>，防止浏览器重新覆盖选择结果。
  function fixThemedPictures() {
    var nodes = document.querySelectorAll('picture, themed-picture');
    for (var i = 0; i < nodes.length; i++) {
      var node = nodes[i];
      if (node.getAttribute('data-bb-themed')) continue;
      var img = node.querySelector('img');
      if (!img) continue;
      node.setAttribute('data-bb-themed', '1');
      var sources = node.querySelectorAll('source');
      if (!sources.length) continue;
      var dark = window.BB_DARK === true;
      var want = null;
      for (var j = 0; j < sources.length; j++) {
        var media = sources[j].getAttribute('media') || '';
        var value = sources[j].getAttribute('srcset') || sources[j].getAttribute('src') || '';
        if (!value) continue;
        var themed = media.indexOf('prefers-color-scheme') >= 0;
        var isDark = themed && media.indexOf('dark') >= 0;
        var isLight = themed && media.indexOf('light') >= 0;
        if ((dark && isDark) || (!dark && isLight)) want = value.split(' ')[0];
      }
      if (!want) continue;
      for (var k = sources.length - 1; k >= 0; k--) {
        sources[k].parentNode.removeChild(sources[k]);
      }
      img.removeAttribute('srcset');
      img.setAttribute('src', want);
    }
  }

  function decorate() { fixAnchors(); decorateMermaid(); fixThemedPictures(); }

  if (window.ResizeObserver && document.documentElement) {
    try { new ResizeObserver(schedule).observe(document.documentElement); } catch (e) {}
  }
  document.addEventListener('load', function (e) {
    var t = e.target;
    if (t && t.tagName === 'IMG') { fixThemedPictures(); schedule(); }
  }, true);
  document.addEventListener('error', function (e) {
    var t = e.target;
    if (t && t.tagName === 'IMG') schedule();
  }, true);
  document.addEventListener('toggle', schedule, true);
  if (document.fonts && document.fonts.ready) { document.fonts.ready.then(schedule); }

  decorate();
  report();
  schedule();
  window.addEventListener('load', function () {
    decorate();
    report();
    schedule();
    setTimeout(schedule, 300);
    setTimeout(schedule, 1000);
  });
})();
""".trimIndent()

/**
 * 高度上报桥（`@JavascriptInterface`，只暴露一个整型上报方法）。
 *
 * 翻译桥不在这里 —— 它属于 `:translate` 模块（`TranslateBridge`），
 * 本文件只负责把它注册到 WebView 并把结果/状态转成 JS 调用。
 */
private class HeightBridge {
    @Volatile
    var onHeight: ((Int) -> Unit)? = null

    @JavascriptInterface
    fun reportHeight(height: Int) {
        onHeight?.invoke(height)
    }
}

// ── 图片缓存（README 内图片落盘，二次进入零网络） ──

/**
 * README 图片磁盘缓存。
 *
 * 为什么不用 WebView 自带缓存：带 token 取图必须由 `shouldInterceptRequest` 自己发请求
 * （WebView 的请求头无法追加 Authorization），自取会绕过它的 HTTP 缓存。这里按 URL 落盘
 * （`cacheDir/readme_images`，与 Coil 的 `image_cache` 分开，避免互相驱逐），上限 [MAX_BYTES]。
 */
private class ReadmeImageCache(private val context: Context) {

    private val dir: File get() = File(context.cacheDir, DIR).apply { mkdirs() }

    /** 命中缓存直接返回；否则带 token 拉取、原子落盘后再返回。失败返回 null 交给 WebView。 */
    fun load(url: String, token: String, referer: String): WebResourceResponse? {
        val sha = sha1(url)
        // 文件名后缀即 MIME 依据（缓存命中时拿不到响应头，只能靠后缀）
        findCached(sha)?.let { return response(it) }
        // 临时文件名有两个硬约束：
        // ① 不能以 sha 开头（否则会被 findCached 误判成已缓存）；
        // ② **每次请求唯一** —— 同一张图并发未命中时，两条线程共写一个临时文件会互相截断，
        //    先改名的那份会把交错内容固化成永久缓存（缓存命中路径只按 URL 直接返回，永不重校验）。
        val tmp = File(dir, "tmp-$sha-${UUID.randomUUID()}")
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                setRequestProperty("Authorization", "token $token")
                setRequestProperty("Referer", referer)
                setRequestProperty("User-Agent", "Branchbase/0.1")
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 15_000
            }
            val contentType = try {
                if (conn.responseCode !in 200..299) return null
                tmp.outputStream().use { out -> conn.inputStream.use { it.copyTo(out) } }
                conn.contentType
            } finally {
                conn.disconnect()
            }
            if (tmp.length() == 0L) {
                tmp.delete()
                return null
            }
            val ext = extOf(url) ?: extOfMime(contentType) ?: "img"
            val target = File(dir, "$sha.$ext")
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return null
            }
            pruneIfNeeded()
            response(target)
        } catch (e: Exception) {
            tmp.delete()
            null // 鉴权/网络失败 → 交给 WebView 默认加载
        }
    }

    /**
     * 命中缓存。
     *
     * 超过 [TTL_MS] 就删掉重取：这本缓存按 URL 命中，而响应头里的 `max-age` 只管 WebView
     * 那一层 —— README 里的同名图片在分支上被替换后，App 会一直显示旧图。
     * 命中时刷新 mtime，作为 [pruneIfNeeded] 的「最后访问时间」。
     */
    private fun findCached(sha: String): File? {
        val now = System.currentTimeMillis()
        val hit = dir.listFiles()
            ?.firstOrNull { it.isFile && it.name.startsWith("$sha.") && it.length() > 0 }
            ?: return null
        if (now - hit.lastModified() > TTL_MS) {
            hit.delete()
            return null
        }
        hit.setLastModified(now)
        return hit
    }

    private fun response(file: File): WebResourceResponse = WebResourceResponse(
        mimeTypeOf(file.extension),
        null,
        200,
        "OK",
        mapOf("Cache-Control" to "max-age=86400"),
        FileInputStream(file),
    )

    /** 超出上限时按最后访问时间淘汰，避免缓存无限增长。 */
    private fun pruneIfNeeded() {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_BYTES) return
        files.sortedBy { it.lastModified() }.forEach { f ->
            if (total <= MAX_BYTES) return@forEach
            val len = f.length()
            if (f.delete()) total -= len
        }
    }

    private fun sha1(url: String): String =
        MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /**
     * 从 URL 路径取扩展名（GitHub 仓库内图片基本都有后缀）。
     *
     * 必须校验「只含字母数字」：`path.substringAfterLast('.')` 在
     * `/…/img.v2/logo` 这类路径上会取出 `v2/logo`，长度检查（≤5）也会放过它，
     * 拼进文件名就得到带 `/` 的非法路径 —— `renameTo` 必然失败，
     * 图片退化成「无鉴权匿名加载」，私有仓库直接裂图。
     */
    private fun extOf(url: String): String? {
        val segment = parseUrl(url)?.path?.lowercase()?.substringAfterLast('/', "") ?: return null
        val ext = segment.substringAfterLast('.', "")
        return ext.takeIf { it.isNotEmpty() && it.length <= 5 && it.all { c -> c.isLetterOrDigit() } }
    }

    private fun extOfMime(contentType: String?): String? = when (contentType?.substringBefore(';')?.trim()) {
        "image/svg+xml" -> "svg"
        "image/png" -> "png"
        "image/jpeg" -> "jpg"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/bmp" -> "bmp"
        "image/x-icon", "image/vnd.microsoft.icon" -> "ico"
        "image/avif" -> "avif"
        "image/apng" -> "apng"
        else -> null
    }

    private fun mimeTypeOf(ext: String): String = when (ext.lowercase()) {
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "ico" -> "image/x-icon"
        "avif" -> "image/avif"
        "apng" -> "image/apng"
        else -> "image/*"
    }

    private companion object {
        const val DIR = "readme_images"
        const val MAX_BYTES = 32L * 1024 * 1024
        /** 缓存有效期：与响应头里写给 WebView 的 max-age 对齐（一天）。 */
        const val TTL_MS = 24L * 60 * 60 * 1000
    }
}

// ── WebViewClient ──

/** README 链接拦截 + 图片鉴权。 */
private class ReadmeWebViewClient(
    private val host: String,
    private val owner: String,
    private val repo: String,
    private val branch: String,
    private val login: String,
    private val token: String,
    private val baseDir: String,
    private val documentUrl: String,
    private val imageCache: ReadmeImageCache,
    private val onLinkClick: (Destination) -> Unit,
    private val onHeightMeasured: (Int) -> Unit,
) : WebViewClient() {

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        // 兜底测量：注入脚本上报失败（JS 关闭 / 桥异常）时仍有高度
        measureHeight(view)
        view.postDelayed({ measureHeight(view) }, 800)
    }

    private fun measureHeight(view: WebView) {
        runCatching {
            view.evaluateJavascript("document.body ? document.body.scrollHeight : 0") { result ->
                val h = result.trim().trim('"').toIntOrNull() ?: 0
                if (h > 0) onHeightMeasured(h)
            }
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val requestUrl = request.url.toString()
        // 同文档锚点（目录/标题跳转）→ 交给 WebView 自己滚动
        if (isSameDocumentAnchor(requestUrl, documentUrl)) return false

        // 相对链接在 raw 基准下会解析成 /{o}/{r}/raw/{b}/… → 还原为 blob/tree 再分类
        val url = toNavigationUrl(requestUrl, host, owner, repo, branch) ?: requestUrl
        // native 符号缺失（.so 未重编译）/ 序列化失败都不能把 URL 交回 WebView：
        // 那会让 README 视图自己导航到 raw 地址，页面被替换且退不回来。
        // 解析失败一律「拦截但不处理」——点了没反应，好过页面被毁。
        val json = runCatching {
            RustBridge.resolveLink(url, host, owner, repo, branch, baseDir, login)
        }.getOrNull() ?: return true
        val dest = runCatching { parseDestination(JSONObject(json)) }.getOrNull() ?: return true

        return when (dest.type) {
            // 站外 → 外开浏览器
            "external" -> {
                val target = dest.url.takeIf { it.isNotBlank() } ?: url
                runCatching { view.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target))) }
                true
            }
            // 页内锚点 → 让 WebView 自己滚动
            "anchor" -> false
            // raw（README 里的裸 raw 链接）→ 与站外走同一条路（交给系统 / 浏览器处理字节流），
            // 但**分类不同**：raw 目标带 owner / repo / branch / path，
            // 以后要做「应用内查看 / 加入下载」时不必再解析一次 URL。
            // 注意：目前两者的用户可见行为**完全一致** —— 这里刻意保留独立分支作为落点，
            // 不要在注释或测试里把它说成「已经避免了被当成站外」。
            "raw" -> {
                val target = dest.url.takeIf { it.isNotBlank() } ?: url
                runCatching { view.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target))) }
                true
            }
            // 站内 → 走 App 内部导航
            else -> {
                onLinkClick(dest)
                true
            }
        }
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        if (token.isEmpty()) return null // 未登录：交给 WebView（走其自身 HTTP 缓存）
        val url = request.url.toString()
        if (!isImageRequest(url, request)) return null
        val urlHost = request.url.host?.lowercase() ?: return null
        // 只处理 GitHub 自有域名：站外图片（shields.io / github-readme-stats 等）绝不附带 token
        if (urlHost !in AUTH_HOSTS && urlHost != host.lowercase()) return null
        val target = rawImageUrl(url, host, owner, repo, branch) ?: return null
        return imageCache.load(target, token, "https://$host/")
    }
}

/**
 * README 正文的深色覆盖（`github-markdown-light.css` 是浅色主题，这里整段覆盖）。
 *
 * 覆盖范围：正文/标题颜色、链接、行内代码、代码底与**语法高亮令牌**、引用、表格（含表头底色）、
 * 分隔线、`kbd`、GFM 告警块、Mermaid 源码卡外框。
 *
 * 三条踩过的坑（都是「浅色规则没被覆盖到」而不是「颜色不好看」）：
 * 1. `table th` 的浅色底 `#f6f8fa` 必须一起换掉 —— 只改边框时，`th` 的文字是
 *    `.markdown-body` 的 `#e6edf3`（近白）压在浅灰底上，对比度 1.11:1，表头直接看不见；
 * 2. `.pl-*` 语法令牌是**浅色主题配色**，只换 `<pre>` 背景会让关键字/注释对比度掉到 3.2:1 ~ 3.8:1；
 * 3. `kbd` 与 `.highlight-source-mermaid` 卡片的背景/描边同理，不覆盖会在深色页面里留浅色块。
 *
 * 图片与徽章不动（它们自带底色，强行反色会更糟）；暗色图源的切换由注入脚本按主题选定
 * （见 README_ENHANCE_JS 的 `fixThemedPictures`），不用 CSS 处理。
 */
private val README_DARK_CSS = """
.markdown-body { color: #e6edf3; background: transparent; }
.markdown-body h1, .markdown-body h2, .markdown-body h3,
.markdown-body h4, .markdown-body h5, .markdown-body h6 { color: #e6edf3; border-bottom-color: #21262d; }
.markdown-body p, .markdown-body li, .markdown-body td, .markdown-body dd { color: #c9d1d9; }
.markdown-body a { color: #2f81f7; }
.markdown-body strong { color: #e6edf3; }
.markdown-body code, .markdown-body tt { background: #161b22; color: #e6edf3; }
.markdown-body pre, .markdown-body .highlight { background: #161b22 !important; }
.markdown-body pre code { color: #e6edf3; }
.markdown-body blockquote { color: #8b949e; border-left-color: #30363d; }
.markdown-body table th, .markdown-body table td { border-color: #30363d; }
.markdown-body table tr { background: transparent; border-top-color: #21262d; }
.markdown-body table tr:nth-child(2n) { background: #161b22; }
.markdown-body table th { background-color: #21262d; color: #e6edf3; }
.markdown-body hr { background-color: #30363d; }
.markdown-body img { background: transparent; }
.markdown-body kbd {
  color: #e6edf3;
  background-color: #161b22;
  border-color: #30363d;
  box-shadow: inset 0 -1px 0 #30363d;
}
.markdown-body .highlight-source-mermaid { background-color: #161b22; border-color: #30363d; }
.markdown-body .bb-chart-head { background: #21262d; color: #8b949e; border-color: #30363d; }
/* 语法令牌：对齐 Primer 暗色，替换浅色主题的 pl-* 配色 */
.markdown-body .pl-k { color: #ff7b72; }
.markdown-body .pl-c1 { color: #79c0ff; }
.markdown-body .pl-ent { color: #7ee787; }
.markdown-body .pl-en { color: #d2a8ff; }
.markdown-body .pl-s, .markdown-body .pl-s1, .markdown-body .pl-s2, .markdown-body .pl-sr { color: #a5d6ff; }
.markdown-body .pl-c, .markdown-body .pl-cm { color: #8b949e; }
.markdown-body .pl-v, .markdown-body .pl-sm, .markdown-body .pl-mi { color: #ffa657; }
.markdown-body .pl-e, .markdown-body .pl-ii { color: #ffa657; }
/* GFM 告警块：语义色对齐 Primer 暗色 */
.markdown-body .markdown-alert { border-left-color: #30363d; }
.markdown-body .markdown-alert.markdown-alert-note { border-left-color: #2f81f7; }
.markdown-body .markdown-alert.markdown-alert-note .markdown-alert-title { color: #2f81f7; }
.markdown-body .markdown-alert.markdown-alert-tip { border-left-color: #3fb950; }
.markdown-body .markdown-alert.markdown-alert-tip .markdown-alert-title { color: #3fb950; }
.markdown-body .markdown-alert.markdown-alert-important { border-left-color: #a371f7; }
.markdown-body .markdown-alert.markdown-alert-important .markdown-alert-title { color: #a371f7; }
.markdown-body .markdown-alert.markdown-alert-warning { border-left-color: #d29922; }
.markdown-body .markdown-alert.markdown-alert-warning .markdown-alert-title { color: #d29922; }
.markdown-body .markdown-alert.markdown-alert-caution { border-left-color: #f85149; }
.markdown-body .markdown-alert.markdown-alert-caution .markdown-alert-title { color: #f85149; }
"""
