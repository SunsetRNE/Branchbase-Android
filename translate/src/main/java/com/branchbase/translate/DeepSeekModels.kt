package com.branchbase.translate

import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 「模型列表」探测：问一下用户填的 OpenAI 兼容端点有哪些模型。
 *
 * ## 为什么放在 :translate 模块
 *
 * 这是 DeepSeek / 中转网关这一类后端的**通用能力**（`GET {baseUrl}/models`，
 * 官方 OpenAI 接口也长这样），不是设置页的排版逻辑。放模块里，设置页只负责
 * 「点按钮 → 拿列表 → 让用户勾选」，也方便用 JVM 单测钉住 URL 拼接与解析。
 *
 * ## 为什么不用 Rust 侧的翻译后端
 *
 * 翻译请求走 `core/src/translate`（Rust）是因为它要复用页面分片/重试/熔断；
 * 探测模型只是**一次 GET**，用 [HttpURLConnection] 足够，且不必为它重建 `.so`。
 * 两条路读同一个 `baseUrl` / `apiKey`，UI 侧不会出现「翻译能用、探测不能用」的分叉。
 *
 * ## 凭据边界
 *
 * Key 只出现在 `Authorization` 头里；**不写日志、不进异常文案、不回显**。
 * 失败只回 HTTP 状态码或网络异常类型，好让 UI 给一句能懂的话。
 */
object DeepSeekModels {

    /** 单次探测的超时（连 + 读各一份）。模型列表是个小请求，10s 足够。 */
    private const val TIMEOUT_MS = 10_000

    /** 未填 Key 的哨兵值（UI 据此提示「请先填写 API Key」，而不是弹一个 HTTP 401）。 */
    const val EMPTY_KEY: String = "EMPTY_KEY"

    /** 端点返回 200 但 `data` 为空。 */
    const val EMPTY_LIST: String = "EMPTY_LIST"

    /** 网络不可达（DNS / 连接拒绝）；UI 据此给本地化提示，而不是把异常类名糊给用户。 */
    const val NETWORK: String = "NETWORK"

    /** 连接 / 读超时。 */
    const val TIMEOUT: String = "TIMEOUT"

    sealed interface Result {
        data class Ok(val models: List<String>) : Result
        data class Fail(val detail: String) : Result
    }

    /**
     * 由用户填的接入地址推出模型列表端点。
     *
     * - 空串 → 官方地址；
     * - 末尾 `/` 去掉；
     * - 已经以 `/models` 结尾就不重复追加（有人会把完整端点贴进来）。
     */
    fun endpoint(baseUrl: String): String {
        val raw = baseUrl.trim().ifEmpty { TranslateProvider.DEEPSEEK_BASE_URL }.trimEnd('/')
        // 允许只填域名（用户常把 `api.deepseek.com` 直接粘进来）；没写协议时补 https
        val base = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
        return if (base.endsWith("/models")) base else "$base/models"
    }

    /**
     * 解析 OpenAI 兼容的 `{"data":[{"id":"…"}]}`。
     *
     * 去重 + 排序：同一模型在网关里可能因别名出现两次；排序让弹窗的勾选顺序稳定
     * （否则每次探测列表顺序不同，用户会以为配置变了）。
     */
    fun parse(json: String): List<String> {
        val data = runCatching { JSONObject(json).optJSONArray("data") }.getOrNull() ?: return emptyList()
        return (0 until data.length())
            .mapNotNull { index -> data.optJSONObject(index)?.optString("id")?.trim()?.takeIf { it.isNotEmpty() } }
            .distinct()
            .sorted()
    }

    /** 阻塞探测（调用方放到 IO 线程）；URL 非法、超时、非 2xx 都收敛成 [Result.Fail]。 */
    fun fetch(baseUrl: String, apiKey: String): Result {
        val key = apiKey.trim()
        if (key.isEmpty()) return Result.Fail(EMPTY_KEY)

        var connection: HttpURLConnection? = null
        return try {
            val url = endpoint(baseUrl)
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Accept", "application/json")
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                Result.Fail("HTTP $code")
            } else {
                val models = parse(readUtf8(connection.inputStream))
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

    private fun readUtf8(stream: InputStream): String = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
}
