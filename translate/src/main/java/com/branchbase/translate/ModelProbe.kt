package com.branchbase.translate

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 「模型列表」探测：问一下用户填的端点有哪些模型（**按协议**问）。
 *
 * ## 它同时是「测试响应」
 *
 * 设置页的「测试响应」与「获取模型列表」是同一个请求（`GET {base}{modelsPath}`）：
 * 它是最小的一次「地址 + Key + 协议」联合校验 —— 鉴权头对不对、路径对不对、
 * 网关认不认这组配置，一问就知道，而且**不消耗 token**（不产生任何译文）。
 * 所以两者共用本对象，不各写一条 HTTP 路径。
 *
 * ## 为什么放在 :translate 模块
 *
 * 这是「自带 Key 的大模型后端」的通用能力，不是设置页的排版逻辑。放模块里，
 * 设置页只负责「点按钮 → 拿列表 → 让用户勾选」，也方便用 JVM 单测钉住
 * 端点拼接、请求头补全与响应解析（三件真正会静默出错的事）。
 *
 * ## 为什么不用 Rust 侧的翻译后端
 *
 * 翻译请求走 `core/src/translate`（Rust）是因为它要复用页面分片/重试/熔断；
 * 探测只是**一次 GET**，用 [HttpURLConnection] 足够，且不必为它重建 `.so`
 * （探测与 Rust 的翻译请求读同一张方言表，见 [TranslateProtocol] 的类注释）。
 *
 * ## 凭据边界
 *
 * Key 只出现在协议规定的那一个头里（不是所有协议都叫 `Authorization`，见
 * [TranslateProtocol.requestHeaders]）；**不写日志、不进异常文案、不回显**。
 * 失败只回 HTTP 状态码（必要时附响应头里的重试提示）或网络异常类型。
 */
object ModelProbe {

    /** 单次探测的超时（连 + 读各一份）。模型列表是个小请求，10s 足够。 */
    private const val TIMEOUT_MS = 10_000

    /** 未填 Key 的哨兵值（UI 据此提示「请先填写 API Key」，而不是弹一个 HTTP 401）。 */
    const val EMPTY_KEY: String = "EMPTY_KEY"

    /** 端点返回 200 但列表为空（或结构不是这个协议的）。 */
    const val EMPTY_LIST: String = "EMPTY_LIST"

    /** 网络不可达（DNS / 连接拒绝）；UI 据此给本地化提示，而不是把异常类名糊给用户。 */
    const val NETWORK: String = "NETWORK"

    /** 连接 / 读超时。 */
    const val TIMEOUT: String = "TIMEOUT"

    sealed interface Result {
        data class Ok(val models: List<String>) : Result

        /**
         * 失败：`detail` 是**与语言无关**的机器可读部分（`HTTP 429` / 哨兵值），
         * 提示语气（「12 秒后重试」「额度已用尽」）由 :app 侧按资源渲染 ——
         * 模块里出现中文界面文案，英文界面就切不过去了（i18n 规范 §5）。
         */
        data class Fail(
            val detail: String,
            /** 429 / 503 的 `Retry-After`（秒）；没有这个头时为 null。 */
            val retryAfterSeconds: Int? = null,
            /** 额度头的剩余值为 0。 */
            val quotaExhausted: Boolean = false,
        ) : Result
    }

    /** 由协议 + 用户填的接入地址推出模型列表端点（细节见 `TranslateProtocol.joinEndpoint`）。 */
    fun endpoint(protocol: TranslateProtocol, baseUrl: String): String = protocol.modelsEndpoint(baseUrl)

    /** 按协议解析列表响应（`data[].id` 或 Gemini 的 `models[].name`）。 */
    fun parse(protocol: TranslateProtocol, json: String): List<String> = protocol.parseModels(json)

    /** 阻塞探测（调用方放到 IO 线程）；URL 非法、超时、非 2xx 都收敛成 [Result.Fail]。 */
    fun fetch(protocol: TranslateProtocol, baseUrl: String, apiKey: String): Result {
        val key = apiKey.trim()
        if (key.isEmpty()) return Result.Fail(EMPTY_KEY)

        var connection: HttpURLConnection? = null
        return try {
            val url = endpoint(protocol, baseUrl)
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                // 自动补全：头名由协议决定（Authorization / x-api-key / x-goog-api-key）
                protocol.requestHeaders(key).forEach { (name, value) -> setRequestProperty(name, value) }
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                failure(code, connection.headerFields)
            } else {
                val models = parse(protocol, readUtf8(connection.inputStream))
                if (models.isEmpty()) Result.Fail(EMPTY_LIST) else Result.Ok(models)
            }
        } catch (e: java.net.UnknownHostException) {
            Result.Fail(NETWORK)
        } catch (e: java.net.SocketTimeoutException) {
            Result.Fail(TIMEOUT)
        } catch (e: Exception) {
            Result.Fail(e.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * 失败结果：状态码 + **响应头里能照做的那一条**（转成结构化字段，不拼中文）。
     *
     * 429 只回「HTTP 429」的话，用户不知道是「等一会儿」还是「额度用完了」；
     * `Retry-After` 与限额头正好回答这个，而且它们不敏感（不是 Key、不是内容）。
     */
    internal fun failure(code: Int, fields: Map<String?, List<String>>): Result.Fail = Result.Fail(
        detail = "HTTP $code",
        retryAfterSeconds = ResponseHints.retryAfterSeconds(fields),
        quotaExhausted = ResponseHints.remainingZero(fields),
    )

    private fun readUtf8(stream: InputStream): String = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
}

/**
 * 响应头的解读：只挑**用户能照做**的那几个。
 *
 * 各家的响应头名字不同但语义一致，这里只把它们**取成数值**，文案交给 :app：
 *
 * | 头 | 谁发 | 用途 |
 * |---|---|---|
 * | `Retry-After` | 通用（429 / 503） | 直说「等几秒再来」 |
 * | `x-ratelimit-remaining*` | OpenAI 系 / Anthropic / DeepSeek 中转 | 剩余请求数，`0` = 等窗口重置 |
 *
 * 刻意**不读** `Set-Cookie` / `Authorization` 之类的头，也不把响应头整体回显 ——
 * 探测面板只该出现「一句能照做的话」，抓包界面不在设置页的职责里。
 */
object ResponseHints {

    /** `Retry-After: 12` 这类秒数（HTTP-date 形态不解析：它的时区/格式容错成本远大于收益）。 */
    fun retryAfterSeconds(fields: Map<String?, List<String>>): Int? =
        header(fields, "Retry-After")?.toIntOrNull()?.takeIf { it in 0..3600 }

    /** 剩余额度为 0（不同家的头名不同，值相同）。 */
    fun remainingZero(fields: Map<String?, List<String>>): Boolean =
        listOf("x-ratelimit-remaining", "anthropic-ratelimit-requests-remaining", "x-ratelimit-requests-remaining")
            .firstNotNullOfOrNull { header(fields, it) }
            ?.trim()
            ?.toIntOrNull() == 0

    /** 大小写不敏感地取一个头的第一个值（`HttpURLConnection` 返回的头名大小写不保证）。 */
    private fun header(fields: Map<String?, List<String>>, name: String): String? =
        fields.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
}
