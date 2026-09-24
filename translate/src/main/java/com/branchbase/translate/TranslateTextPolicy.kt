package com.branchbase.translate

/**
 * 文本判定与字符分类的**公共入口**（纯函数，可 JVM 单测）。
 *
 * ## 判定本身不在这里
 *
 * 「这一段要不要翻 / 翻哪一部分 / 怎么展示」的完整判定在 [TranslateDecisionEngine]
 * （1.0.89 引入的判定引擎）。这里只保留两样东西：
 * - 文本原语：[normalize] / [longestLatinRun] / [isHanChar] / [isLatinLetter]，
 *   判定引擎、占位符保护、页面脚本镜像都用同一套；
 * - [needsTranslation]：只要一个是/否的调用方用它，它就是把判定结论折成 `Boolean`。
 *
 * ## 为什么不只靠页面脚本判
 *
 * 页面脚本（`02-dom.js`）会用注入的 [PageRules] 先粗筛一遍，但那只是**省流量的优化**：
 * 脚本随版本迭代、用户可能改、也可能被页面 CSP 影响。真正的判定放在这里，理由有三：
 * 1. 原生侧是唯一能同时看到「设置 + 段落 + 缓存」的地方，判定与缓存键才能对齐；
 * 2. 判错的代价不对称 —— 把中文段落再翻一遍，会插出一段更差的中文（用户直接看到脏结果），
 *    而漏判只是少翻一段（用户再点一次就好），所以这里宁严勿宽；
 * 3. 纯函数 + 单测，规则改动有回归保护。
 */
object TranslateTextPolicy {

    /** 折叠所有空白（含全角空格）为首尾去空的单行文本。 */
    fun normalize(raw: String): String =
        raw.replace(WHITESPACE, " ").trim()

    /**
     * 判定结论（含「翻哪一部分」）。
     *
     * @param text 原文（未归一化也能传）
     * @param target 目标语言码（[LANG_ZH] / [LANG_EN]，未知码按默认语言处理）
     */
    fun decide(
        text: String,
        target: String = LANG_ZH,
        rules: PageRules = PageRules.DEFAULT,
    ): TranslateDecision = TranslateDecisionEngine.decide(text, target, rules)

    /**
     * 是否需要翻译（[decide] 的布尔形态）。
     *
     * @param text 已经 [normalize] 或未归一化的原文都能传（内部会再归一化一次）。
     * @param target 目标语言码（[LANG_ZH] / [LANG_EN]，未知码按默认语言处理）。
     */
    fun needsTranslation(text: String, target: String = LANG_ZH, rules: PageRules = PageRules.DEFAULT): Boolean =
        decide(text, target, rules) !is TranslateDecision.Skip

    /** 文本里最长的连续拉丁字母串长度。 */
    internal fun longestLatinRun(s: String): Int {
        var best = 0
        var cur = 0
        for (c in s) {
            if (c in 'a'..'z' || c in 'A'..'Z') {
                cur++
                if (cur > best) best = cur
            } else {
                cur = 0
            }
        }
        return best
    }

    private val WHITESPACE = Regex("[\\s\u3000]+")
}

/**
 * 是否汉字（CJK 统一表意文字；不含标点，判「已经是中文」时标点不该计入）。
 *
 * 做成顶层函数而不是成员扩展：成员扩展必须带着接收者作用域才能用，
 * 别的文件（`PlaceholderGuard` 判断「去占位符后还剩不剩实义内容」）也要用同一个判定。
 */
internal fun isHanChar(c: Char): Boolean = c in '\u4e00'..'\u9fff'

/**
 * 是否「需要翻译的字母」（拉丁 / 希腊 / 西里尔 / 假名 / 谚文）。
 *
 * 目标中文时它等于「外语字母」，目标英文时它等于「目标文字字母」（见 [TranslateDecisionEngine]）。
 *
 * **范围是显式写死的**，而不是用 `Char.isLetter()`：页面脚本（`01-core.js`）里有一份
 * 同规则的镜像，而 JS 侧没有 `isLetter` —— 用 Kotlin 的完整 Unicode 判定会让两边慢慢对不上
 * （表现为「页面筛掉了原生其实想翻的段落」）。这里列的是「中英互译场景里真会出现的文字」，
 * 阿拉伯文 / 天城文等不在其中：它们会被当成中性字符（不翻），这也是本模块「只做中英两向」
 * 这条边界的延续。
 */
internal fun isLatinLetter(c: Char): Boolean = when (c.code) {
    in 0x41..0x5A, in 0x61..0x7A,          // 拉丁基本区
    in 0x00C0..0x024F,                     // 拉丁扩展 A/B（带变音符号的西欧/中欧字母）
    in 0x0370..0x04FF,                     // 希腊 + 西里尔
    in 0x3040..0x30FF,                     // 平假名 + 片假名
    in 0xAC00..0xD7AF,                     // 谚文音节
    -> true

    else -> false
}

/** 不可见字符（零宽空格/连接符/BOM/软连字符）。 */
internal fun isInvisibleChar(c: Char): Boolean = c.isWhitespace() ||
    c == '\u200B' || c == '\u200C' || c == '\u200D' ||
    c == '\uFEFF' || c == '\u00AD'

