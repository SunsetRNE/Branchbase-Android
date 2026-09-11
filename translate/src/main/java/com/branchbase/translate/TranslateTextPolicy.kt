package com.branchbase.translate

/**
 * 「这一段到底要不要翻」的**权威判定**（纯函数，可 JVM 单测）。
 *
 * ## 为什么不只靠页面脚本判
 *
 * 页面脚本（`02-dom.js`）会用注入的 [PageRules] 先粗筛一遍，但那只是**省流量的优化**：
 * 脚本随版本迭代、用户可能改、也可能被页面 CSP 影响。真正的判定放在这里，理由有三：
 * 1. 原生侧是唯一能同时看到「设置 + 段落 + 缓存」的地方，判定与缓存键才能对齐；
 * 2. 判错的代价不对称 —— 把中文段落再翻一遍，会插出一段更差的中文（用户直接看到脏结果），
 *    而漏判只是少翻一段（用户再点一次就好），所以这里宁严勿宽；
 * 3. 纯函数 + 单测，规则改动有回归保护。
 *
 * ## 判定顺序（对齐网页版沉浸式翻译的 `isNeedToTranslate`）
 *
 * 1. 归一化空白（页面 `innerText` 里的换行/多空格在翻译前后没有意义，还会干扰缓存命中）；
 * 2. **零宽字符/空白**：GitHub 正文里塞了不少 `&#8203;` 之类的占位，去掉后为空即跳过；
 * 3. **跳过规则** [PageRules.skipPatterns]：纯数字、纯 URL、版本号、单个 @提及 / #编号、
 *    标签残留 —— 这些「翻出来只会更糟」，而且发出去就是白花额度；
 * 4. 长度落在 `[minLen, maxLen]`；
 * 5. 语言判定：目标中文要求出现 `latinRun` 个连续拉丁字母且汉字占比不超 `hanRatioMax`；
 *    目标英文要求至少 `minHan` 个汉字。
 */
object TranslateTextPolicy {

    /** 折叠所有空白（含全角空格）为首尾去空的单行文本。 */
    fun normalize(raw: String): String =
        raw.replace(WHITESPACE, " ").trim()

    /**
     * 是否需要翻译。
     *
     * @param text 已经 [normalize] 或未归一化的原文都能传（内部会再归一化一次）。
     * @param target 目标语言码（[LANG_ZH] / [LANG_EN]，未知码按默认语言处理）。
     */
    fun needsTranslation(text: String, target: String = LANG_ZH, rules: PageRules = PageRules.DEFAULT): Boolean {
        val s = normalize(text)
        if (s.isEmpty() || s.all { isInvisibleChar(it) }) return false
        if (rules.skipRegexes.any { it.containsMatchIn(s) }) return false
        if (s.length < rules.minLen || s.length > rules.maxLen) return false
        return if (TranslateLang.byCode(target) == TranslateLang.ZH) {
            val han = s.count { isHanChar(it) }
            longestLatinRun(s) >= rules.latinRun && han <= s.length * rules.hanRatioMax
        } else {
            s.count { isHanChar(it) } >= rules.minHan
        }
    }

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

/** 不可见字符（零宽空格/连接符/BOM/软连字符）。 */
internal fun isInvisibleChar(c: Char): Boolean = c.isWhitespace() ||
    c == '\u200B' || c == '\u200C' || c == '\u200D' ||
    c == '\uFEFF' || c == '\u00AD'

