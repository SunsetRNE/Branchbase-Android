package com.branchbase.translate

import org.json.JSONObject

/**
 * 翻译协议（**端点方言**）：同一句「把这段翻成中文」，各家的路径、鉴权头、响应体长得都不一样。
 *
 * ## 为什么是一个独立概念，而不是 [TranslateProvider] 的一个字段
 *
 * [TranslateProvider] 回答的是**「用谁」**（免费匿名接口 / 自带 Key 的大模型），
 * 协议回答的是**「怎么说」**：同样是自带 Key，可以按 DeepSeek 官方写法说、
 * 按 OpenAI Chat Completions 说、按 Anthropic Messages 说、按 Gemini 说。
 * 两者正交 —— 换协议不改「用谁」，换服务商也不该被迫改协议。
 *
 * 把方言收在一张表里，是为了让「请求头自动补全」只有**一个真源**：
 * 探测（本模块的模型列表 GET）与翻译（`core/src/translate` 的分派）读同一张表，
 * 不会出现「探测认得 x-api-key、翻译却还在发 Bearer」的分叉。
 *
 * ## 表里为什么是这五档
 *
 * 用户 2026-11-26 定的清单：DeepSeek 官方（默认）/ OpenAI Chat Completions /
 * OpenAI Responses / Anthropic Messages / Gemini API。覆盖了主流三家的原生格式
 * 与 OpenAI 的两代端点；任何「OpenAI 兼容」的中转都归到 [DEEPSEEK] 或 [OPENAI_CHAT]
 * （两者只差默认地址与路径前缀，鉴权头完全相同）。
 *
 * ## 这里**只有技术事实**（路径 / 头 / 解析）
 *
 * 界面上的名称与一句话说明是 app 层字符串资源（同 [TranslateProvider] 的约定）：
 * 写在这里切英文就切不过去了。同理也**不自造模型候选** —— 官方模型名变化快，
 * 只有默认协议给两个已知值，其余留空，交给「获取模型列表」去问服务端。
 *
 * ## 两侧的分工（1.4.51 起五档协议端到端可用）
 *
 * - Kotlin（本文件）：探测（模型列表）、默认地址 / 模型、设置页渲染；
 * - Rust（`core/src/translate` 的 `Protocol` + `responses` / `anthropic` / `gemini` 三个
 *   后端模块）：真正发翻译请求时的端点、鉴权头、请求体与响应体解析。
 *
 * 两侧是**同一张表的两份实现**（Kotlin 要拼探测请求，Rust 要发翻译请求，共用不了同一份代码），
 * 加协议 / 改默认值时两边一起改；`.so` 随发版流水线重建，改完 Rust 必须发一次版才生效。
 */
enum class TranslateProtocol(
    /** 落盘 / options JSON 里的稳定码；**改了就是破坏性变更**（老配置读不回来）。 */
    val code: String,
    /** 接入地址留空时用的官方地址（也是界面上的占位符）。 */
    val defaultBaseUrl: String,
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
    /** 默认模型（空 = 不自造，界面留空由服务端默认）。 */
    val defaultModel: String = "",
    /** 界面候选芯片（空 = 不显示芯片行）。 */
    val modelSuggestions: List<String> = emptyList(),
) {

    /**
     * DeepSeek 官方默认协议：`POST {base}/chat/completions`，`Authorization: Bearer`。
     *
     * 官方同时给了 `base_url (OpenAI) = https://api.deepseek.com` 与
     * `base_url (Anthropic) = https://api.deepseek.com/anthropic`，所以同一个 Key
     * 既能按本协议说，也能按 [ANTHROPIC_MESSAGES] 说 —— 选哪档是用户的自由。
     */
    DEEPSEEK(
        code = "deepseek",
        defaultBaseUrl = "https://api.deepseek.com",
        chatPath = "/chat/completions",
        modelsPath = "/models",
        auth = AuthScheme.BEARER,
        defaultModel = "deepseek-flash",
        modelSuggestions = listOf("deepseek-flash", "deepseek-v4-pro"),
    ),

    /** OpenAI Chat Completions：`POST {base}/v1/chat/completions`。 */
    OPENAI_CHAT(
        code = "openai-chat",
        defaultBaseUrl = "https://api.openai.com",
        chatPath = "/v1/chat/completions",
        modelsPath = "/v1/models",
        auth = AuthScheme.BEARER,
    ),

    /**
     * OpenAI Responses：`POST {base}/v1/responses`。
     *
     * 模型列表与 Chat Completions **共用** `GET /v1/models`（同一个 Models API）；
     * 变的是请求体（`input` 而不是 `messages`）与响应体（`output[]` 里的 `output_text`），
     * 那两段在 Rust 侧接入时才会用到。
     */
    OPENAI_RESPONSES(
        code = "openai-responses",
        defaultBaseUrl = "https://api.openai.com",
        chatPath = "/v1/responses",
        modelsPath = "/v1/models",
        auth = AuthScheme.BEARER,
    ),

    /**
     * Anthropic Messages：`POST {base}/v1/messages`。
     *
     * 三个独有要求：`x-api-key`（不是 Bearer）、`anthropic-version`（缺了直接 400）、
     * `max_tokens` 必填且 `system` 是**顶层字段**（不是 messages 里的一条）。
     */
    ANTHROPIC_MESSAGES(
        code = "anthropic-messages",
        defaultBaseUrl = "https://api.anthropic.com",
        chatPath = "/v1/messages",
        modelsPath = "/v1/models",
        auth = AuthScheme.X_API_KEY,
        versionHeader = "anthropic-version" to "2023-06-01",
    ),

    /**
     * Gemini API：`POST {base}/v1beta/models/{model}:generateContent`。
     *
     * 模型名在**路径**里（所以 [chatPath] 带占位符），Key 走 `x-goog-api-key` 头
     * （也接受 `?key=` 查询参数 —— 头更干净：查询串会进 URL、进日志、进代理的访问记录）。
     */
    GEMINI(
        code = "gemini",
        defaultBaseUrl = "https://generativelanguage.googleapis.com",
        chatPath = "/v1beta/models/{model}:generateContent",
        modelsPath = "/v1beta/models",
        auth = AuthScheme.X_GOOG_API_KEY,
    ),
    ;

    /**
     * 是否必须由用户显式填模型（没有内置默认值）。
     *
     * DeepSeek 有默认模型；其余四档的口径是「不臆造模型名」，空着就一定发不出去 ——
     * 设置页的翻译链路据此提前给一句「去填模型」，而不是让服务端回一个 400。
     */
    val requiresExplicitModel: Boolean get() = defaultModel.isEmpty()

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
        return joinEndpoint(baseUrl, path, defaultBaseUrl)
    }

    /** 模型列表端点：`{base}{modelsPath}`（地址里已含路径前缀时不重复追加）。 */
    fun modelsEndpoint(baseUrl: String): String = joinEndpoint(baseUrl, modelsPath, defaultBaseUrl)

    /**
     * 解析模型列表响应（**响应体方言**也在这里）。
     *
     * - OpenAI / DeepSeek / Anthropic：`{"data":[{"id":"…"}]}`；
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
        val default: TranslateProtocol = DEEPSEEK

        private const val MODEL_PLACEHOLDER = "{model}"

        /** 认不出来的码一律回落到默认，绝不抛异常（老配置 / 手写配置都可能带脏值）。 */
        fun byCode(code: String?): TranslateProtocol =
            entries.firstOrNull { it.code.equals(code?.trim(), ignoreCase = true) } ?: default

        /**
         * 从接入地址猜协议 —— 只用于弹窗里的「推荐」标记，**绝不自动改用户配置**。
         *
         * 认不出来（中转 / 自建网关 / 只填了域名）返回 null：那种地址本来就靠用户自己知道。
         */
        fun guessFrom(baseUrl: String): TranslateProtocol? {
            val host = hostOf(baseUrl) ?: return null
            return when {
                host == "api.deepseek.com" || host.endsWith(".deepseek.com") -> DEEPSEEK
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
    }
}

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

/** `{"data":[{"id":"…"}]}`：OpenAI / DeepSeek / Anthropic 三家共用（Anthropic 多几个字段，忽略）。 */
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
