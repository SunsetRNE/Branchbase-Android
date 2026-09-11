package com.branchbase.translate

import org.json.JSONArray
import org.json.JSONObject

/**
 * 语言码常量（本模块唯一来源：设置、分片、页面脚本注入都从这里派生）。
 *
 * 只做「中英两向」是产品取舍，不是能力上限：后端（MyMemory）本身支持上百种语言对，
 * 要加语言只需在这里加常量 + 在 [TranslateLang] 里加一项 + 设置页多一行，
 * 分片、缓存、调度、页面脚本都不用动。
 */
const val LANG_ZH: String = "zh-CN"
const val LANG_EN: String = "en"

/**
 * 可选的**目标**语言。
 *
 * 源语言由目标语言反推（[sourceOf]）：中英互译的两个方向刚好覆盖本 App 的场景
 * ——「读英文仓库」与「给英文读者看中文文档」。不再引入语言选择器的复杂度。
 */
enum class TranslateLang(val code: String, val label: String, val description: String) {

    ZH(
        LANG_ZH,
        "中文（简体）",
        "把英文原文翻成中文对照显示（默认，读英文仓库用）",
    ),
    EN(
        LANG_EN,
        "English",
        "把中文原文翻成英文对照显示（给英文读者看中文文档）",
    ),
    ;

    /** 该语言下「已经是目标语言」的段落会长这样（供设置页与文档说明，判定在 [TranslateTextPolicy]）。 */
    val opposite: TranslateLang get() = if (this == ZH) EN else ZH

    companion object {

        val default: TranslateLang = ZH

        fun byCode(code: String?): TranslateLang =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: default

        /** 目标语言 → 源语言（只做中英两向，因此是取反）。 */
        fun sourceOf(target: String): String = byCode(target).opposite.code

        /** 源语言码（与 [sourceOf] 等价，语义更直白的别名）。 */
        fun sourceCode(target: String): String = sourceOf(target)
    }
}

/**
 * 页面里「哪一段值得翻」的判定参数。
 *
 * ## 为什么要序列化到页面脚本
 *
 * 判定分两处执行，但**规则只能有一个真源**：
 * - 页面脚本（`assets/translate/02-dom.js`）在收集段落时要先粗筛一遍，避免把一堆
 *   无需翻译的段落发回原生侧（省往返、也省额度）；
 * - 原生侧 [TranslateTextPolicy] 收到文本后再判定一次（权威判定，防止脚本版本漂移）。
 *
 * 若把阈值写死在 JS 里，两边就会各改各的、慢慢对不上。因此 Kotlin 侧定义参数，
 * 启动时随设置一起注入 `window.__bbTranslate.rules`，JS 只使用、不定义。
 *
 * ## 每个参数的来历（对齐网页版沉浸式翻译的实测取值）
 *
 * - [minLen] / [maxLen]：太短（"OK"、"v1.2"）没有翻译价值；太长基本是贴日志，
 *   翻出来也没人看，还会白烧额度（MyMemory 匿名额度按词计）；
 * - [latinRun]：目标中文时，段落里至少要有 3 个连续拉丁字母才认为「是英文」；
 * - [hanRatioMax]：汉字占比超过一半就认为本来就是中文（中英混排的中文段落再翻一次，
 *   译文质量反而更差）；
 * - [minHan]：目标英文时，段落里至少要有 4 个汉字才值得翻；
 * - [skipPatterns]：**一眼就不该翻**的短文本（纯数字、纯 URL、版本号、标签残留…），
 *   它们是正则**源码字符串**，Kotlin 与页面脚本各自编译一份、判定同一件事；
 * - [immediateLimit]：整页候选文本总长度不超过它时**不做视口优先**，一次性全翻
 *   （短页面「点一下整页就翻好了」的体验最好；长页面才需要按视口推进省额度）。
 */
data class PageRules(
    val minLen: Int = 2,
    val maxLen: Int = 1200,
    val latinRun: Int = 3,
    val hanRatioMax: Double = 0.5,
    val minHan: Int = 4,
    val immediateLimit: Int = 5_000,
    val skipPatterns: List<String> = DEFAULT_SKIP_PATTERNS,
) {

    /** 编译后的跳过规则（每段判定一次，避免重复编译正则）。 */
    val skipRegexes: List<Regex> by lazy { skipPatterns.mapNotNull { runCatching { Regex(it) }.getOrNull() } }

    /**
     * 注入页面脚本的 JSON。
     *
     * 用 `org.json` 而不是手拼字符串：`skipPatterns` 里全是正则，
     * 手拼要自己处理反斜杠转义，出错的样子是「JS 侧正则静默失效」——最难查的那种 bug。
     */
    fun toJson(): String = JSONObject()
        .put("minLen", minLen)
        .put("maxLen", maxLen)
        .put("latinRun", latinRun)
        .put("hanRatioMax", hanRatioMax)
        .put("minHan", minHan)
        .put("immediateLimit", immediateLimit)
        .put("skipPatterns", JSONArray(skipPatterns))
        .toString()

    companion object {

        /** 网页版沉浸式翻译 `isNeedToTranslate` 的六条跳过规则，按 GitHub 正文场景裁剪。 */
        val DEFAULT_SKIP_PATTERNS: List<String> = listOf(
            // 纯数字（含千分位、小数、百分比、区间）
            "^[0-9\\s.,%+\\-~/×]+$",
            // 纯 URL（裸链接没有翻译价值，还容易被服务端改坏）
            "^https?://\\S+$",
            // 形如 v1.2.3 / 1.0.0-rc1 的版本号
            "^v?\\d+(\\.\\d+){1,3}([-+][0-9A-Za-z.-]+)?$",
            // 单个 @提及 / #编号（GitHub 正文里到处是，翻出来只会更糟）
            "^[@#][A-Za-z0-9_-]{1,40}$",
            // HTML 标签残留（GitHub 偶尔把 <style>/<script> 片段塞进正文）
            "<\\s*/?\\s*(script|style|link)\\b",
        )

        val DEFAULT: PageRules = PageRules()
    }
}

