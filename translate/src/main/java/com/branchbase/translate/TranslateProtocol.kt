package com.branchbase.translate

import org.json.JSONObject

/**
 * Anthropic 的必填版本头值（与 Rust 侧 `translate::ANTHROPIC_VERSION` 一致）。
 *
 * 放在**文件顶层**而不是 companion 里：枚举项的构造参数要用它，而 companion 在枚举项
 * 初始化时还没建好（Kotlin 会直接报 `Companion object is uninitialized`）。
 */
const val ANTHROPIC_VERSION: String = "2023-06-01"

/**
 * 翻译协议（**端点方言**）：同一句「把这段翻成中文」，各家要的路径、鉴权头、响应体都不一样。
 *
 * ## 协议与厂商是两件事（1.4.54 起彻底拆开）
 *
 * | 概念 | 回答什么 | 谁定义 |
 * |---|---|---|
 * | **协议**（本文件） | 「怎么说」：路径形状、鉴权头、请求体与响应体的结构 | OpenAI / Anthropic / Google 三家 |
 * | **接口地址**（[ApiAddress]） | 「对谁说」：某个厂商 / 中转 / 自建网关的 base URL | 用户选（官方列表 + 输入框） |
 *
 * 这是这次通用化的核心：以前 [DEEPSEEK] 既是「协议」又是「厂商」，于是模型候选、默认地址、
 * 错误文案全被锁死在 DeepSeek 上；换成任意厂商就得改代码。现在协议只有四档（三家的原生格式），
 * 厂商只是「地址列表里的一行」，新增厂商 = 加一行 [ApiAddress]，不动协议。
 *
 * DeepSeek 在这套模型里的位置很直白：它**没有自己的报文协议**（官方文档把它归在「兼容协议」），
 * 三条通道分别是「OpenAI Chat 兼容（`…/v1`）」「OpenAI Responses 兼容」「Anthropic 兼容（`…/anthropic`）」，
 * 所以它在三档协议下各占一行地址，而不是一个协议。
 *
 * ## 请求头自动补全只有**一个真源**
 *
 * 探测（本模块的模型列表 GET）与翻译（`core/src/translate` 的分派）读同一张表，
 * 不会出现「探测认得 x-api-key、翻译却还在发 Bearer」的分叉。Rust 侧是这张表的第二份实现
 * （跨 JNI 共用不了代码），码值逐字一致，由 `SettingsSpecTest` 的钉子互盯。
 *
 * ## 这里**只有技术事实**（路径 / 头 / 解析）
 *
 * 界面上的名称与说明是 app 层字符串资源：写在这里切英文就切不过去了。同理也**不臆造模型候选** ——
 * 只有已知厂商（如 DeepSeek）在其地址行里给两个官方模型名，其余交给「获取模型列表」问服务端。
 */
enum class TranslateProtocol(
    /** 落盘 / options JSON 里的稳定码；**改了就是破坏性变更**（老配置读不回来）。 */
    val code: String,
    /**
     * 翻译端点路径。
     *
     * 含 `{model}` 的是 Gemini 形态（模型名在路径里，见 [chatEndpoint]），
     * 其余协议模型名在请求体里。
     */
    val chatPath: String,
    /** 模型列表端点路径（「获取模型列表」与「测试响应」共用）。 */
    val modelsPath: String,
    /** 鉴权头方案（头名都不同，见 [authHeaders]）。 */
    val auth: AuthScheme,
    /**
     * 必填的版本头：Anthropic 独有。
     *
     * 取值 `2023-06-01`：官方当前推荐更新的版本（2026-10-21），但中转 / 兼容网关
     * （含 DeepSeek 的 `/anthropic` 端点）对老版本的接受度最高；这一项**不在界面上暴露**
     * —— 它不是用户的配置，是协议的组成部分。
     */
    val versionHeader: Pair<String, String>? = null,
    /**
     * 该协议下**已知**的接口地址（第一项 = 官方地址，也是输入框的占位符）。
     *
     * 只收官方文档明确写过的：`api.deepseek.com/v1` 与 `…/anthropic` 是 DeepSeek 文档
     * 明示的兼容入口；中转 / 自建网关一律走输入框（不在表里猜）。
     */
    val addresses: List<ApiAddress>,
) {

    /** OpenAI Chat Completions：`POST {base}/v1/chat/completions`。 */
    OPENAI_CHAT(
        code = "openai-chat",
        chatPath = "/v1/chat/completions",
        modelsPath = "/v1/models",
        auth = AuthScheme.BEARER,
        addresses = listOf(
            ApiAddress("openai", "https://api.openai.com/v1"),
            // DeepSeek 官方：base 用 /v1（文档明示「v1 与模型版本无关」），路径与 OpenAI 完全一致
            ApiAddress(
                id = "deepseek",
                baseUrl = "https://api.deepseek.com/v1",
                defaultModel = "deepseek-flash",
                modelSuggestions = listOf("deepseek-flash", "deepseek-v4-pro"),
            ),
        ),
    ),

    /**
     * OpenAI Responses：`POST {base}/v1/responses`。
     *
     * 模型列表与 Chat Completions **共用** `GET /v1/models`（同一个 Models API）；
     * 变的是请求体（`input` 而不是 `messages`）与响应体（`output[]` 里的 `output_text`）。
     */
    OPENAI_RESPONSES(
        code = "openai-responses",
        chatPath = "/v1/responses",
        modelsPath = "/v1/models",
        auth = AuthScheme.BEARER,
        addresses = listOf(ApiAddress("openai", "https://api.openai.com/v1")),
    ),

    /**
     * Anthropic Messages：`POST {base}/v1/messages`。
     *
     * 三个独有要求：`x-api-key`（不是 Bearer）、`anthropic-version`（Anthropic 缺了直接 400，
     * DeepSeek 的兼容通道会忽略它 —— 多送无害，所以统一补上）、`max_tokens` 必填且 `system` 是顶层字段。
     */
    ANTHROPIC_MESSAGES(
        code = "anthropic-messages",
        chatPath = "/v1/messages",
        modelsPath = "/v1/models",
        auth = AuthScheme.X_API_KEY,
        versionHeader = "anthropic-version" to ANTHROPIC_VERSION,
        addresses = listOf(
            ApiAddress("anthropic", "https://api.anthropic.com"),
            ApiAddress("deepseek", "https://api.deepseek.com/anthropic"),
        ),
    ),

    /**
     * Gemini API：`POST {base}/v1beta/models/{model}:generateContent`。
     *
     * 模型名在**路径**里（所以 [chatPath] 带占位符），Key 走 `x-goog-api-key` 头
     * （也接受 `?key=` 查询参数 —— 头更干净：查询串会进 URL、进日志、进代理的访问记录）。
     */
    GEMINI(
        code = "gemini",
        chatPath = "/v1beta/models/{model}:generateContent",
        modelsPath = "/v1beta/models",
        auth = AuthScheme.X_GOOG_API_KEY,
        addresses = listOf(ApiAddress("google", "https://generativelanguage.googleapis.com")),
    ),
    ;

    /** 官方地址（地址列表第一项；输入框留空时用它）。 */
    val officialBaseUrl: String get() = addresses.first().baseUrl

    /**
     * 某个地址属于表里的哪一项（认不出来 = 自定义地址）。
     *
     * 比较前统一：去空格、去尾 `/`、小写 —— 用户手抄地址时常带这两样。
     */
    fun addressOf(baseUrl: String): ApiAddress? {
        val normalized = normalize(baseUrl)
        if (normalized.isEmpty()) return null
        return addresses.firstOrNull { normalize(it.baseUrl) == normalized }
    }

    /** 该地址行的默认模型（自定义地址 = 空，由用户填或探测）。 */
    fun defaultModelFor(baseUrl: String): String = addressOf(baseUrl)?.defaultModel.orEmpty()

    /** 该地址行的内置候选（自定义地址 = 空，界面只显示探测/保存过的列表）。 */
    fun suggestionsFor(baseUrl: String): List<String> = addressOf(baseUrl)?.modelSuggestions.orEmpty()

    /** 当前协议的鉴权头（值就是 Key 本身或 `Bearer <Key>`；没 Key 就不补头）。 */
    fun authHeaders(apiKey: String): List<Pair<String, String>> {
        val key = apiKey.trim()
        if (key.isEmpty()) return emptyList()
        return when (auth) {
            AuthScheme.BEARER -> listOf("Authorization" to "Bearer $key")
            AuthScheme.X_API_KEY -> listOf("x-api-key" to key)
            AuthScheme.X_GOOG_API_KEY -> listOf("x-goog-api-key" to key)
        }
    }

    /**
     * 一个 JSON 请求要带的头（**自动补全的清单**，顺序固定便于单测与抓包比对）。
     *
     * 只有三条真需要：`Accept`（要 JSON 不要 HTML）、`Content-Type`（POST 才有）、
     * 鉴权头（各家头名不同）；Anthropic 再加版本头。**不补** `User-Agent` /
     * `Content-Length` / `Accept-Encoding`：那些 `HttpURLConnection` / reqwest 自己会算，
     * 手写反而容易写出与服务端期望不一致的值。
     */
    fun requestHeaders(apiKey: String, json: Boolean = false): List<Pair<String, String>> {
        val headers = mutableListOf("Accept" to "application/json")
        if (json) headers += "Content-Type" to "application/json"
        headers += authHeaders(apiKey)
        versionHeader?.let { headers += it }
        return headers
    }

    /** [chatPath] 拼上模型名后的翻译端点（Gemini 用；模型为空时保留 `{model}` 占位符）。 */
    fun chatEndpoint(baseUrl: String, model: String): String {
        val path = if (chatPath.contains(MODEL_PLACEHOLDER)) {
            chatPath.replace(MODEL_PLACEHOLDER, model.trim().ifEmpty { MODEL_PLACEHOLDER })
        } else {
            chatPath
        }
        return joinEndpoint(baseUrl, path, officialBaseUrl)
    }

    /** 模型列表端点：`{base}{modelsPath}`（地址里已含路径前缀时不重复追加）。 */
    fun modelsEndpoint(baseUrl: String): String = joinEndpoint(baseUrl, modelsPath, officialBaseUrl)

    /**
     * 解析模型列表响应（**响应体方言**也在这里）。
     *
     * - OpenAI / Anthropic：`{"data":[{"id":"…"}]}`；
     * - Gemini：`{"models":[{"name":"models/gemini-…","supportedGenerationMethods":[…]}]}` ——
     *   名字带 `models/` 前缀（用的时候要去掉），且**混着只能做嵌入的模型**，
     *   所以按 `supportedGenerationMethods` 过滤掉不支持 `generateContent` 的那些。
     *
     * 去重 + 排序：同一模型可能因别名出现两次；顺序稳定，用户才不会以为配置变了。
     */
    fun parseModels(json: String): List<String> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val names = when (this) {
            GEMINI -> parseGeminiModels(root)
            else -> parseDataIds(root)
        }
        return names.distinct().sorted()
    }

    companion object {

        /** 界面默认协议（也是认不出来的码的落点）。 */
        val default: TranslateProtocol = OPENAI_CHAT

        private const val MODEL_PLACEHOLDER = "{model}"

        /**
         * 认不出来的码一律回落到默认，绝不抛异常（老配置 / 手写配置都可能带脏值）。
         *
         * **兼容 1.4.53 及更早的 `deepseek`**：那时它既是协议又是厂商，落在
         * 「OpenAI Chat 兼容 + DeepSeek 地址」这一格；这里只搬协议，地址由
         * `TranslateSettings` 的一次性迁移补上（否则老配置会变成「DeepSeek 的 Key + OpenAI 的地址」）。
         */
        fun byCode(code: String?): TranslateProtocol {
            val c = code?.trim()?.lowercase().orEmpty()
            if (c == "deepseek") return OPENAI_CHAT
            return entries.firstOrNull { it.code == c } ?: default
        }

        /**
         * 从接入地址猜协议 —— 只用于弹窗里的「推荐」标记，**绝不自动改用户配置**。
         *
         * 认不出来（中转 / 自建网关 / 只填了域名）返回 null：那种地址本来就靠用户自己知道。
         */
        fun guessFrom(baseUrl: String): TranslateProtocol? {
            val host = hostOf(baseUrl) ?: return null
            return when {
                // DeepSeek 的三条通道：默认按 OpenAI Chat 兼容（地址列表里还有 /anthropic 一行）
                host == "api.deepseek.com" || host.endsWith(".deepseek.com") -> OPENAI_CHAT
                host == "api.openai.com" || host.endsWith(".openai.com") -> OPENAI_CHAT
                host == "api.anthropic.com" || host.endsWith(".anthropic.com") -> ANTHROPIC_MESSAGES
                host == "generativelanguage.googleapis.com" -> GEMINI
                else -> null
            }
        }

        /** 取主机名：容忍「没写协议」「带端口」「带路径」「带 userinfo」。 */
        private fun hostOf(baseUrl: String): String? {
            val raw = baseUrl.trim()
            if (raw.isEmpty()) return null
            val noScheme = raw.substringAfter("://", raw)
            val host = noScheme.substringBefore('/').substringBefore('?').substringBefore('#')
                .substringBefore('@').substringBefore(':').lowercase()
            return host.takeIf { it.contains('.') }
        }

        /**
         * 拼端点，并容忍用户把路径前缀也写进地址里。
         *
         * 规则：
         * - 空地址 → `officialBase` + 路径；
         * - 没写协议 → 补 `https://`（用户常直接粘 `api.deepseek.com`）；
         * - 地址已以整条路径结尾 → 原样（有人会贴完整端点）；
         * - 地址已含路径的第一段（`https://api.openai.com/v1` + `/v1/chat/completions`）
         *   → 只补剩余段，不写出 `/v1/v1/`。
         */
        fun joinEndpoint(baseUrl: String, path: String, officialBase: String): String {
            val raw = baseUrl.trim().ifEmpty { officialBase }
            val withScheme = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
            val base = withScheme.trimEnd('/')
            if (base.endsWith(path)) return base
            val segments = path.trim('/').split('/')
            for (take in segments.size - 1 downTo 1) {
                if (base.endsWith("/" + segments.take(take).joinToString("/"))) {
                    return base + "/" + segments.drop(take).joinToString("/")
                }
            }
            return base + path
        }

        private fun normalize(baseUrl: String): String =
            baseUrl.trim().trimEnd('/').lowercase()
    }
}

/**
 * 某档协议下的一个**接口地址**（厂商 / 中转 / 自建网关）。
 *
 * `id` 只是给 app 层查名字用的稳定键（`"openai"` / `"deepseek"` / …），名字是字符串资源；
 * 这样加厂商不需要动界面代码，也不会把中文写进模块。
 *
 * [defaultModel] / [modelSuggestions] 挂在地址上而不是协议上：同一档协议下，不同厂商的
 * 默认模型与已知模型完全不同（OpenAI 地址不该预填 `deepseek-flash`，DeepSeek 地址该预填）。
 */
data class ApiAddress(
    val id: String,
    val baseUrl: String,
    val defaultModel: String = "",
    val modelSuggestions: List<String> = emptyList(),
)

/**
 * 鉴权头的三种写法（值由 [TranslateProtocol.authHeaders] 拼）。
 *
 * 单独成一个枚举，是为了让「哪家走哪种头」在表里一眼可读：`BEARER`（OpenAI 系）、
 * `X_API_KEY`（Anthropic）、`X_GOOG_API_KEY`（Gemini）。
 */
enum class AuthScheme {
    BEARER,
    X_API_KEY,
    X_GOOG_API_KEY,
}

/** `{"data":[{"id":"…"}]}`：OpenAI / Anthropic 两家共用（Anthropic 多几个字段，忽略）。 */
private fun parseDataIds(root: JSONObject): List<String> {
    val data = root.optJSONArray("data") ?: return emptyList()
    return (0 until data.length()).mapNotNull { index ->
        data.optJSONObject(index)?.optString("id")?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/** `{"models":[{"name":"models/…","supportedGenerationMethods":[…]}]}`：Gemini 形态。 */
private fun parseGeminiModels(root: JSONObject): List<String> {
    val models = root.optJSONArray("models") ?: return emptyList()
    return (0 until models.length()).mapNotNull { index ->
        val model = models.optJSONObject(index) ?: return@mapNotNull null
        val methods = model.optJSONArray("supportedGenerationMethods")
        // 字段缺失时不筛（有的网关不返回它）；返回了就必须支持 generateContent ——
        // 只做嵌入的模型放进翻译候选芯片里，选中了也翻不出来。
        if (methods != null) {
            val supported = (0 until methods.length()).any { methods.optString(it) == "generateContent" }
            if (!supported) return@mapNotNull null
        }
        model.optString("name")
            .trim()
            .removePrefix("models/")
            .takeIf { it.isNotEmpty() }
    }
}
