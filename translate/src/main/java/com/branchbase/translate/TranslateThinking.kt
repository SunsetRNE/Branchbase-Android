package com.branchbase.translate

/**
 * 思维深度（思考预算）：让模型在翻译前多「想」一点的开关与档位。
 *
 * ## 为什么单独成一层、还要单独拆出探测按钮
 *
 * 它不是「翻译能不能用」的前提，而是**用户要不要为质量多付延迟与 token** 的选择：
 * 实测与用户口径都表明「最低档就够日常翻译」，高档位只会让翻译变慢、体验变差。
 * 所以默认 [OFF]、界面上单独一行、并且**不替用户决定** —— 想用的人自己选，
 * 服务商认不认由用户点「测试翻译」自己验证（那是另一次真实请求，会消耗 token）。
 *
 * ## 四档 + 自定义，与协议的映射
 *
 * | 档位 | OpenAI 系 `effort` | Anthropic / Gemini `budget_tokens` |
 * |---|---|---|
 * | [OFF] | 不发字段 | 不发字段 |
 * | [LOW] | `low` | 1024 |
 * | [HIGH] | `high` | 4096 |
 * | [MAX] | `high`（OpenAI 的枚举到 `high` 为止） | 16384 |
 * | [CUSTOM] | 用户填的词（如 `medium`） | 用户填的 token 数（如 2048） |
 *
 * 自定义值按「**是不是纯数字**」自动分流：数字 → token 预算（Anthropic / Gemini），
 * 其它 → effort 词（OpenAI 系）。这样一个输入框就能覆盖两套语义，不逼用户先选协议再选单位。
 *
 * ## 模块里只有技术事实
 *
 * 名称与说明是 app 层字符串资源（同 [TranslateProtocol] 的约定）。
 */
enum class TranslateThinking(val code: String) {

    OFF("off"),
    LOW("low"),
    HIGH("high"),
    MAX("max"),

    /** 自定义：值在 [TranslateConfig.thinkingCustom] 里，按「数字 / 词」分流。 */
    CUSTOM("custom"),
    ;

    /** OpenAI 系的 `effort` 值（空 = 不发字段）。 */
    fun effort(custom: String): String = when (this) {
        OFF -> ""
        LOW -> "low"
        HIGH -> "high"
        // OpenAI 的 reasoning_effort 枚举只到 high；想要更细的值请用「自定义」填 xhigh / minimal 之类
        MAX -> "high"
        CUSTOM -> custom.trim().takeIf { it.isNotEmpty() && !it.all(Char::isDigit) }.orEmpty()
    }

    /** Anthropic `thinking.budget_tokens` / Gemini `thinkingBudget` 的值（0 = 不发字段）。 */
    fun budgetTokens(custom: String): Long = when (this) {
        OFF -> 0
        LOW -> 1024
        HIGH -> 4096
        MAX -> 16384
        CUSTOM -> custom.trim().takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toLongOrNull() ?: 0
    }

    /** 是否真的会往请求里放字段（[OFF] 与「自定义填了空」都不放）。 */
    fun isActive(custom: String): Boolean = effort(custom).isNotEmpty() || budgetTokens(custom) > 0

    companion object {

        val default: TranslateThinking = OFF

        /** 认不出来的值一律回落到 [OFF]（老配置没有这个键，脏值也不能让翻译发不出去）。 */
        fun byCode(code: String?): TranslateThinking =
            entries.firstOrNull { it.code.equals(code?.trim(), ignoreCase = true) } ?: default
    }
}
