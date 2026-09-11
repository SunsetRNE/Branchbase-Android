package com.branchbase.translate

/**
 * 翻译后端抽象。
 *
 * 模块里所有逻辑（分片、缓存、调度、页面脚本）都只依赖这个接口，
 * **具体用哪家服务是 :app 的事** —— 当前实现是 `RustTranslateEngine`
 * （Rust 侧 `core/src/translate.rs`，默认 MyMemory 匿名接口）。
 *
 * 这样切服务商 / 加服务商的代价是「新增一个实现类 + 改一行 install」：
 * - 换成 DeepL / 有道：实现 [translate] 即可；
 * - 换成大模型（带术语表、自定义提示词）：提示词属于实现内部，上层无感；
 * - 想在一次请求里翻多段：也是实现内部的事（[FailKind.QUOTA] 等分类保持不变）。
 *
 * 反过来说，**这里不做重试、不做并发、不做缓存**：那些是跨段落的全局策略，
 * 只有上层（[TranslateScheduler] / [Translator]）才看得到全貌。
 */
interface TranslateEngine {

    /** 翻译一段（已由上层切片，长度符合后端上限）。 */
    suspend fun translate(text: String, from: String, to: String): EngineResult

    companion object {

        /** 未安装任何后端时的占位实现（返回可分类的失败，不会 NPE）。 */
        val NONE: TranslateEngine = object : TranslateEngine {
            override suspend fun translate(text: String, from: String, to: String): EngineResult =
                EngineResult.Fail(FailKind.NETWORK, "未安装翻译引擎")
        }

        /**
         * 从后端错误文本里**猜**失败类别。
         *
         * 放在模块里而不是实现里：分类决定「要不要熔断 / 要不要重试」，是要被单测钉住的策略；
         * 而错误文本的形态由两侧约定保证 —— Rust 侧 `translate` 模块会产出带状态码与语义
         * 关键词的消息（如 `DeepSeek 认证失败（401）：…`、`翻译服务返回 403：ALL QUERIES EXHAUSTED`），
         * 再经 `core/src/bridge/jni.rs::into_jstring` 的 `ERROR:` 前缀回传。
         *
         * 判定顺序有讲究：
         * 1. `QUERY LENGTH LIMIT` 里也含 `LIMIT`，但它是**我们自己分片失败**的信号
         *    （不该熔断，熔断会掩盖 bug），所以先排掉；
         * 2. 认证类必须排在通用 `INVALID` 之前 —— `INVALID API KEY` 同时命中两者，
         *    而它要的是「去设置里改 Key」，不是「换个语言对重试」。
         */
        fun classify(message: String): FailKind {
            val m = message.uppercase()
            if (m.contains("QUERY LENGTH LIMIT")) return FailKind.UNKNOWN
            return when {
                m.contains("401") ||
                    m.contains("认证失败") ||
                    m.contains("AUTHENTICATION FAIL") ||
                    m.contains("API KEY") ||
                    m.contains("UNAUTHORIZED") -> FailKind.AUTH

                m.contains("ALL QUERIES EXHAUSTED") ||
                    m.contains("QUOTA") ||
                    m.contains("额度") ||
                    m.contains("余额") ||
                    m.contains("402") ||
                    m.contains("403") ||
                    m.contains("429") ||
                    m.contains("限流") ||
                    m.contains("TOO MANY REQUESTS") -> FailKind.QUOTA

                m.contains("TIMEOUT") ||
                    m.contains("超时") ||
                    m.contains("HTTP 5") ||
                    m.contains("连接") ||
                    m.contains("NETWORK") ||
                    m.contains("DNS") -> FailKind.NETWORK

                m.contains("INVALID") ||
                    m.contains("LANGPAIR") ||
                    m.contains("不支持") ||
                    m.contains("UNSUPPORTED") -> FailKind.UNSUPPORTED

                else -> FailKind.UNKNOWN
            }
        }
    }
}

/** 一次翻译调用的结果（成功 = [Ok]，失败 = [Fail]）。 */
sealed interface EngineResult {

    data class Ok(val text: String) : EngineResult

    data class Fail(val kind: FailKind, val message: String) : EngineResult
}

/** 失败类别（决定重试与熔断行为）。 */
enum class FailKind {

    /**
     * 凭据问题（API Key 没填 / 无效 / 过期）。
     *
     * 重试没有意义（每次都会得到同一个 401），也**不该**被当成「连续失败」去累计 ——
     * 用户要做的只有一件事：去设置里改 Key。所以调度器会像额度用尽那样停发请求，
     * 但把状态标成 [TranslateScheduler.State.pageStatus] 的 `auth`，页面提示「检查 API Key」。
     */
    AUTH,

    /** 额度用尽 / 被限流：**重试没有意义**，立刻熔断本会话的翻译。 */
    QUOTA,

    /** 网络类失败：值得退避重试。 */
    NETWORK,

    /** 语言对不支持等参数问题：重试无用，但不熔断（换一段可能就好了）。 */
    UNSUPPORTED,

    /** 其它（含解析异常）：按单段失败处理。 */
    UNKNOWN,
}
