package com.branchbase.core

import com.branchbase.translate.EngineResult
import com.branchbase.translate.FailKind
import com.branchbase.translate.TranslateConfig
import com.branchbase.translate.TranslateEngine

/**
 * 翻译后端的 Rust 实现（`:translate` 模块的 [TranslateEngine] 在 Android 侧的落地）。
 *
 * 这一层薄得几乎只剩三件事：
 * 1. 把当前设置翻译成 Rust 侧认识的**选项 JSON**（后端 + 凭据 + 协议，
 *    见 [TranslateConfig.engineOptionsJson]）；
 * 2. 把 `ERROR:` 前缀的错误文本交给 [TranslateEngine.classify] 分类；
 * 3. 在真正发请求之前拦掉「选了 DeepSeek 却没填 Key」这种必然失败的情况。
 *
 * ## 为什么构造参数是「取配置的函数」而不是配置本身
 *
 * 用户在设置页改 Key / 换后端之后，**不应该**还需要重启 App 或重建整条翻译链路：
 * 引擎持有的是 `() -> TranslateConfig`（内部读的是带进程内缓存的 `TranslateSettings`），
 * 每次调用取最新值。写设置的一方只要顺手 [com.branchbase.translate.Translator.resetFailures]
 * 清掉熔断，下一页就能用新 Key。
 *
 * ## `protocol` 会一路带到 Rust 的分派
 *
 * 方言表在 `:translate` 的 `TranslateProtocol`（五档：DeepSeek / OpenAI Chat /
 * OpenAI Responses / Anthropic / Gemini）。Rust 侧 `translate::Protocol` 读同一个码，
 * 决定端点、鉴权头、请求体与响应体（见 `core/src/translate/{responses,anthropic,gemini}.rs`）。
 * **`.so` 必须随发版重建**：旧 `.so` 不认识 `protocol`，会继续按 `chat/completions` 跑
 * （serde 忽略未知字段），表现为「协议选了不生效」。
 *
 * ## 为什么分类不写在 Rust 里
 *
 * 分类决定的是**上层的重试与熔断策略**，属于 Kotlin 侧的问题；而且分类规则本身是纯函数，
 * 放在 :translate 模块里可以被 JVM 单测直接覆盖。
 */
class RustTranslateEngine(
    private val config: () -> TranslateConfig,
) : TranslateEngine {

    override suspend fun translate(text: String, from: String, to: String): EngineResult {
        val current = config()

        // 没填 Key 就别发请求了：省一次往返，也给用户一句能照着做的提示。
        // 归到 AUTH（而不是 NETWORK）：调度器会停下来提示「去设置检查」，不会当成断网重试。
        if (current.providerKind.requiresKey && current.apiKey.isBlank()) {
            return EngineResult.Fail(FailKind.AUTH, "尚未配置 API Key")
        }

        // 协议没有内置默认模型（OpenAI / Anthropic / Gemini）时，空模型一定失败：
        // 与其发出去等一句 400，不如在这里就停下并说清去哪儿填。Rust 侧也有同一道兜底。
        if (current.protocolKind.requiresExplicitModel && current.model.isBlank()) {
            return EngineResult.Fail(
                FailKind.AUTH,
                "尚未配置模型：请在「设置 → 沉浸式翻译 → 模型」里填写，或点「获取模型列表」选一个",
            )
        }

        val raw = RustBridge.translateOrError(text, from, to, current.engineOptionsJson())
        return when {
            // JNI 调用本身失败（runCatching 兜住，含旧 .so 缺符号）→ 按网络类处理，值得重试
            raw.isBlank() -> EngineResult.Fail(FailKind.NETWORK, "翻译服务无响应")
            raw.startsWith(ERROR_PREFIX) -> {
                val message = raw.removePrefix(ERROR_PREFIX).trim()
                // 失败文案要上屏（「测试连接」的原始原因）：把稳定码前缀剥掉，
                // 码只用来分类；分类本身也是码优先、正文兜底。
                val (_, text) = TranslateEngine.splitErrorCode(message)
                EngineResult.Fail(TranslateEngine.classify(message), text)
            }
            else -> EngineResult.Ok(raw)
        }
    }

    private companion object {
        /** 与 `core/src/bridge/jni.rs::into_jstring` 的约定保持一致。 */
        const val ERROR_PREFIX = "ERROR:"
    }
}
