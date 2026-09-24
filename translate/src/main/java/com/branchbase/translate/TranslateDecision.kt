package com.branchbase.translate

/**
 * 判定引擎：**「这一段要不要翻」→「要翻的话翻哪一部分」→「怎么展示」**。
 *
 * 这是 [TranslateTextPolicy] 背后的唯一实现（那个对象只是「只要一个是/否」的便捷入口）。
 *
 * ## 三条规则（按顺序执行，前一条成立就不再往下走）
 *
 * 1. **一致即跳过**：段内没有任何「需要翻译的内容」时，说明[所需要的目标文字]与
 *    [被翻译目标]本来就是同一份东西 —— 不翻，也不插任何东西。
 *    这里的「一致」有两层：
 *    · **事前**：段内没有外语字母（目标中文）/ 没有汉字（目标英文），或外语只剩
 *      `CI`、`a`、`3D` 这种零碎片段（翻出来只会更差，见 [PageRules.latinRun]）；
 *    · **事后**：译文与原文归一化后逐字相同（见 [isIdentical]）—— 服务端把原文原样
 *      还回来（没翻 / 专有名词 / 源语言=目标语言）时，插一张与原文一模一样的卡片
 *      只会让页面变脏，[Translator] 据此把它记成「无需翻译」并**记住**这个判定。
 * 2. **外语为主 → 整段翻**：段落里目标文字占比不超 [PageRules.hanRatioMax] 时，
 *    按本模块一直以来的行为整段翻（英文段落里夹一句中文引文，整段翻才读得通）。
 * 3. **目标文字为主、段内有需要翻的内容 → 匹配性翻译**：只把**需要翻的片段**
 *    送去翻译（`npm run dev`、`pull request`），其余文字一个字节都不动，
 *    展示时按「片段 → 片段译文」配对列出（见 [TranslateDecision.Matched]）。
 *    翻哪一段、要不要翻，由使用规则 [PageRules.matchPolicy] / [PageRules.matchMinLen] /
 *    [PageRules.maxMatchParts] 决定。
 *
 * ## 为什么判定在「占位符保护视角」下做
 *
 * URL、行内代码、`@提及`、提交 SHA 本来就不是「需要翻译的内容」，
 * 但它们在文本里长得就像外语片段（`https`、`cargo`、`deadbeef`）。
 * 所以判定之前先过一遍 [PlaceholderGuard]：这些标记在判定眼里是**中性字符**
 * （`⟦0⟧` 既不是汉字也不是拉丁字母），于是「详见 https://example.com 的说明」
 * 不会被判成「有需要翻的内容」。
 *
 * 注意这是**判定视角**，与用户设置里的「保护代码与链接」开关无关：
 * 那个开关只决定**送出去的时候**是否把标记换成占位符（[Translator] 里生效）。
 * 两者分开之后，关掉保护的用户也不会看到「https → 超文本传输协议」这种片段译文。
 *
 * ## 为什么放在原生侧（而不是页面脚本里）
 *
 * 页面脚本（`01-core.js`）里有一份**同规则的镜像**，但它只负责粗筛（省往返、省额度）；
 * 权威判定必须在原生侧：只有这里同时看得到「设置 + 文本 + 缓存 + 保护开关」，
 * 判定与缓存键才对得上，而且规则改动有 JVM 单测兜着（见 `TranslateDecisionTest`）。
 */
object TranslateDecisionEngine {

    /**
     * 判定一段文本。
     *
     * @param text 原文（未归一化也能传，内部会归一化）
     * @param target 目标语言码（[LANG_ZH] / [LANG_EN]）
     */
    fun decide(
        text: String,
        target: String = LANG_ZH,
        rules: PageRules = PageRules.DEFAULT,
    ): TranslateDecision {
        val src = TranslateTextPolicy.normalize(text)

        // ── 与文字内容无关的排除项（顺序即优先级）──
        if (src.isEmpty() || src.all { isInvisibleChar(it) }) return skip(src, DecisionReason.EMPTY)
        if (rules.skipRegexes.any { it.containsMatchIn(src) }) return skip(src, DecisionReason.SKIP_RULE)
        if (src.length < rules.minLen || src.length > rules.maxLen) return skip(src, DecisionReason.LENGTH)

        val lang = TranslateLang.byCode(target)
        val guarded = PlaceholderGuard.protect(src)
        val g = guarded.text
        val han = g.count { isHanChar(it) }
        val latin = g.count { isLatinLetter(it) }
        val targetChars = if (lang == TranslateLang.ZH) han else latin
        val foreignChars = if (lang == TranslateLang.ZH) latin else han

        // ① 一致即跳过：段内没有任何外语内容 —— 它本来就是目标文字
        if (foreignChars == 0) return skip(src, DecisionReason.ALREADY_TARGET)

        // ② 外语太零碎（"CI" / "a" / "3D"）：整段判定与片段判定用同一个门槛，
        //    否则「含有 CI 字样」的中文段落会被判成「有需要翻的内容」
        val worth = if (lang == TranslateLang.ZH) {
            TranslateTextPolicy.longestLatinRun(g) >= rules.latinRun
        } else {
            han >= rules.minHan
        }
        if (!worth) return skip(src, DecisionReason.ALREADY_TARGET)

        // ③ 外语为主（含整段都是外语）：整段翻
        val content = targetChars + foreignChars
        if (targetChars <= content * rules.hanRatioMax) return TranslateDecision.Whole(src)

        // ④ 目标文字为主：段内到底有没有「需要翻的内容」
        val parts = fragments(guarded, lang, rules)
        if (parts.isEmpty()) return skip(src, DecisionReason.ALREADY_TARGET)

        return when (rules.matchPolicy) {
            // 整段一起翻：外语片段太多的混排段落也走这里（说明它其实以外语为主）
            PageRules.MATCH_POLICY_WHOLE -> TranslateDecision.Whole(src)
            PageRules.MATCH_POLICY_SKIP -> skip(src, DecisionReason.POLICY_SKIPPED)
            else -> if (parts.size > rules.maxMatchParts) {
                TranslateDecision.Whole(src)
            } else {
                TranslateDecision.Matched(src, parts)
            }
        }
    }

    /**
     * 「所需要的目标文字」与「被翻译目标」是否一致（归一化后逐字相同）。
     *
     * 用于**译后校验**：机器翻译把原文原样还回来是常见情形（服务端没翻、专有名词、
     * 源语言与目标语言写反了），而这类「译文」插进页面就是一段与原文重复的卡片 ——
     * 用户看到的是「翻译坏了」，实际是判定漏了一步。
     *
     * 比较形态见 [comparable]：大小写、全角/半角、零宽字符、首尾标点都不算差异 ——
     * 只差了标点的「译文」与原文是同一份东西，没有展示价值。
     */
    fun isIdentical(a: String, b: String): Boolean {
        val x = comparable(a)
        return x == comparable(b)
    }

    /**
     * 比较形态：去掉空白与零宽字符、统一大小写与全角宽度、去掉首尾标点。
     *
     * 只用来判「是不是同一份东西」，**不参与缓存键与译文生成**。
     */
    fun comparable(raw: String): String = buildString(raw.length) {
        for (c in raw) {
            if (isInvisibleChar(c)) continue
            append(foldWidth(c).lowercaseChar())
        }
    }.trim { it.isWhitespace() || isPunctLike(it) }

    // ── 片段切分 ──

    /**
     * 段内「需要翻的片段」。
     *
     * 片段 = 连续的外语字母，**吸收夹在中间的中性字符**（空格 / 标点 / 数字 / 占位符）：
     * `npm run dev` 是一个片段而不是三个词 —— 逐词送翻会得到三份互相不知道上下文的译文，
     * 拼起来是「npm 运行 开发」这种读不通的东西。
     *
     * 首尾的中性字符不算片段的一部分（`Hello, world!` 的片段是 `Hello, world`），
     * 这样送出去的文本不带句末标点，服务端也不会把标点翻进译文里。
     *
     * 片段按出现顺序去重：同一段里 `pull request` 出现三次，只列一对就够
     * （配对展示是「术语表」式的读法，重复三遍只会把卡片撑长）。
     */
    private fun fragments(
        guarded: PlaceholderGuard.Guarded,
        lang: TranslateLang,
        rules: PageRules,
    ): List<String> {
        val text = guarded.text
        val runs = runsOf(text, lang)
        val out = LinkedHashSet<String>()

        var i = 0
        while (i < runs.size) {
            if (runs[i].kind != Kind.FOREIGN) {
                i++
                continue
            }
            var last = i
            var j = i + 1
            // 吸收「内部中性」：中性段后面**紧跟**外语段时，它属于同一个片段
            while (j + 1 < runs.size && runs[j].kind == Kind.NEUTRAL && runs[j + 1].kind == Kind.FOREIGN) {
                last = j + 1
                j += 2
            }
            // 片段取在「守卫坐标」里，先还原回原文（含其中的标记），再判它值不值得翻
            val raw = PlaceholderGuard.restorePartial(text.substring(runs[i].start, runs[last].end), guarded.tokens)
            if (raw != null) worthPart(raw, lang, rules)?.let { out += it }
            i = last + 1
        }
        return out.toList()
    }

    /** 片段本身值不值得翻（阈值与整段判定共用同一套规则）。 */
    private fun worthPart(raw: String, lang: TranslateLang, rules: PageRules): String? {
        val s = TranslateTextPolicy.normalize(raw)
        if (s.length < rules.matchMinLen) return null
        if (rules.skipRegexes.any { it.containsMatchIn(s) }) return null
        val worth = if (lang == TranslateLang.ZH) {
            TranslateTextPolicy.longestLatinRun(s) >= rules.latinRun
        } else {
            s.count { isHanChar(it) } >= rules.minHan
        }
        return if (worth) s else null
    }

    /** 极长的**同类字符**区间（见 [Kind]）：片段切分与统计都基于它。 */
    private class Run(val kind: Kind, val start: Int, val end: Int)

    private fun runsOf(text: String, lang: TranslateLang): List<Run> {
        val out = mutableListOf<Run>()
        var i = 0
        while (i < text.length) {
            val kind = kindOf(text[i], lang)
            var j = i + 1
            while (j < text.length && kindOf(text[j], lang) == kind) j++
            out += Run(kind, i, j)
            i = j
        }
        return out
    }

    /** 一个字符对目标语言而言的类别。 */
    private fun kindOf(c: Char, lang: TranslateLang): Kind = when {
        isHanChar(c) -> if (lang == TranslateLang.ZH) Kind.TARGET else Kind.FOREIGN
        isLatinLetter(c) -> if (lang == TranslateLang.ZH) Kind.FOREIGN else Kind.TARGET
        else -> Kind.NEUTRAL
    }

    /**
     * 字符类别。
     *
     * [TARGET] 是「目标语言的文字」（目标中文时是汉字，目标英文时是拉丁字母）；
     * [FOREIGN] 是「要翻的外语」；其余（数字、标点、空白、占位符、emoji）都是 [NEUTRAL]。
     */
    private enum class Kind { TARGET, FOREIGN, NEUTRAL }

    // ── 内部工具 ──

    private fun skip(source: String, reason: DecisionReason): TranslateDecision.Skip =
        TranslateDecision.Skip(source, reason)

    /** 全角 ASCII 区（`！` `，` `：`…）折成半角，另外补齐几个不在那个区里的全角标点。 */
    private fun foldWidth(c: Char): Char = when (c) {
        in '\uFF01'..'\uFF5E' -> (c.code - 0xFEE0).toChar()
        '\u3000' -> ' '
        '“', '”' -> '"'
        '‘', '’' -> '\''
        '、' -> ','
        '。' -> '.'
        '—', '–', '―' -> '-'
        '…' -> '.'
        else -> c
    }

    /**
     * 标点 / 符号 / 控制字符（比较形态里首尾会被去掉）。
     *
     * 用「不是字母数字、也不是空白」来定义，而不是逐类枚举 `CharCategory`：
     * 这里只关心「它是不是承载语义的字符」，省略号（`…`）、破折号（`—`）、
     * emoji 都该按标点处理，枚举反而会漏。
     */
    private fun isPunctLike(c: Char): Boolean = !c.isLetterOrDigit() && !c.isWhitespace()
}

/** 判定结论的原因码 —— 统计与日志按它分类，也是排查「这段为什么没翻」的唯一线索。 */
enum class DecisionReason {
    /** 空白 / 零宽字符：没有内容。 */
    EMPTY,

    /** 命中跳过规则（纯数字、纯 URL、版本号、语言切换行…）。 */
    SKIP_RULE,

    /** 长度不在 `[PageRules.minLen, PageRules.maxLen]` 内。 */
    LENGTH,

    /** **「所需要的目标文字」与「被翻译目标」一致**：段内没有需要翻的内容。 */
    ALREADY_TARGET,

    /** 段内确有需要翻的内容，但使用规则（[PageRules.matchPolicy]）选了「不翻」。 */
    POLICY_SKIPPED,

    /** 需要翻译（整段或片段）。 */
    TRANSLATABLE,
}

/**
 * 判定结论。
 *
 * 三种结果对应三种页面行为：什么都不做（[Skip]）、插一张整段译文卡片（[Whole]）、
 * 插一组「片段 → 译文」配对（[Matched]）。
 */
sealed interface TranslateDecision {

    /** 归一化后的原文（判定与缓存键都用它）。 */
    val source: String

    val reason: DecisionReason

    /** 不翻。 */
    data class Skip(override val source: String, override val reason: DecisionReason) : TranslateDecision

    /** 整段翻：译文作为一段整体展示（块级插入，本模块一直以来的行为）。 */
    data class Whole(override val source: String) : TranslateDecision {
        override val reason: DecisionReason get() = DecisionReason.TRANSLATABLE
    }

    /**
     * 匹配性翻译：只翻段内这些片段，展示时按「片段 → 译文」配对列出。
     *
     * [parts] 是**去重后**的原文片段（按出现顺序，已归一化），译文由 [Translator] 补上。
     */
    data class Matched(override val source: String, val parts: List<String>) : TranslateDecision {
        override val reason: DecisionReason get() = DecisionReason.TRANSLATABLE

        /** 片段总字符数（日志与统计用）。 */
        val chars: Int get() = parts.sumOf { it.length }
    }
}
