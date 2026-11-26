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

        /** Anthropic `thinking.budget_tokens` 的下限（官方要求 ≥1024）。 */
        const val MIN_BUDGET: Long = 1024

        /** 常见服务商的上限（Gemini 2.5 Pro 为 32768；更大基本会被拒）。 */
        const val MAX_BUDGET: Long = 32768

        /** 已知的 effort 词（OpenAI 系的枚举；`xhigh` 属较新模型，一并认）。 */
        val KNOWN_EFFORTS: Set<String> = setOf("minimal", "low", "medium", "high", "xhigh")

        /**
         * **即时校验**自定义值：它在当前协议下到底会变成什么。
         *
         * 这是「自定义」唯一容易静默出错的地方：填了一个当前协议不认的形态（Anthropic 填词、
         * OpenAI 填数字），`effort` 与 `budgetTokens` 会**同时为空** —— 请求里什么都不加，
         * 用户以为开了思维深度，其实等同关闭。所以输入框下面必须当场说清。
         *
         * 这里只返回**与语言无关**的判定，句子在 app 层拼。
         */
        fun checkCustom(custom: String, protocol: TranslateProtocol): CustomValueIssue {
            val value = custom.trim()
            if (value.isEmpty()) return CustomValueIssue.EMPTY

            val numeric = value.all(Char::isDigit)
            val budget = if (numeric) value.toLongOrNull() ?: Long.MAX_VALUE else 0L
            val budgetOnlyProtocol = protocol == TranslateProtocol.ANTHROPIC_MESSAGES ||
                protocol == TranslateProtocol.GEMINI
            val effortOnlyProtocol = !budgetOnlyProtocol

            if (numeric) {
                // 数字只对「按预算说话」的两档有意义；OpenAI 系会直接忽略它
                if (effortOnlyProtocol) return CustomValueIssue.DIGITS_IGNORED
                return when {
                    // Anthropic 的 thinking 预算最小 1024；低于它会被服务端拒绝
                    budget < MIN_BUDGET -> CustomValueIssue.BUDGET_TOO_SMALL
                    // 各家常见上限在 32k 上下；更大只会浪费额度或直接被拒
                    budget > MAX_BUDGET -> CustomValueIssue.BUDGET_TOO_LARGE
                    else -> CustomValueIssue.OK_BUDGET
                }
            }

            // 词只对 OpenAI 系的 effort 有意义
            if (budgetOnlyProtocol) return CustomValueIssue.WORD_IGNORED
            return if (value.lowercase() in KNOWN_EFFORTS) {
                CustomValueIssue.OK_EFFORT
            } else {
                CustomValueIssue.UNKNOWN_EFFORT
            }
        }

        val default: TranslateThinking = OFF

        /** 认不出来的值一律回落到 [OFF]（老配置没有这个键，脏值也不能让翻译发不出去）。 */
        fun byCode(code: String?): TranslateThinking =
            entries.firstOrNull { it.code.equals(code?.trim(), ignoreCase = true) } ?: default
    }
}

/**
 * 自定义值在当前协议下的判定（[TranslateThinking.checkCustom] 的返回值）。
 *
 * 全部是**与语言无关**的分类：句子由 app 层按资源拼，模块里不出现界面文案。
 */
enum class CustomValueIssue {
    /** 会被当作 token 预算正确发出去（Anthropic / Gemini）。 */
    OK_BUDGET,

    /** 会被当作 effort 正确发出去（OpenAI 系）。 */
    OK_EFFORT,

    /** 空值：等同关闭（用户以为自己开了）。 */
    EMPTY,

    /** 纯数字，但当前协议只认 effort 词 → 数字被忽略，等同关闭。 */
    DIGITS_IGNORED,

    /** 词，但当前协议只认 token 预算 → 词被忽略，等同关闭。 */
    WORD_IGNORED,

    /** 预算低于 Anthropic 的下限 1024。 */
    BUDGET_TOO_SMALL,

    /** 预算超过常见上限 32768。 */
    BUDGET_TOO_LARGE,

    /** 不是已知的 effort 词（服务商大概率拒绝，但可以点「测试翻译」验证）。 */
    UNKNOWN_EFFORT,
}
