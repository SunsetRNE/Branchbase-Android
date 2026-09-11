package com.branchbase.translate

/**
 * 占位符保护：翻译前把「不该被翻译、也不能被改动」的片段换成 `⟦n⟧`，译完再还原。
 *
 * ## 要解决的真实问题
 *
 * 机器翻译对 URL、`@用户名`、`#123`、提交 SHA、`%s` 这类标记的处理是**不可预测**的：
 * - 常见表现一：`https://github.com/a/b` 被「翻译」成 `https://github.com/a/乙`（路径被译）；
 * - 常见表现二：`@torvalds` 被拆成 `@ 托瓦兹`，还原后链接失效、用户名对不上；
 * - 常见表现三：`#1234` 的编号被当成数字改写（`1,234`），issue 引用直接指向错误对象。
 *
 * 网页版沉浸式翻译同样做「标记/代码/链接保护」，只是它的保护对象是 HTML 标签；
 * 我们拿到的是 `innerText`（纯文本），所以要保护的是**文本里的标记**。
 *
 * ## 标记用 `⟦n⟧`（U+27E6 / U+27E7）而不是 `{}` 或 `[[n]]`
 *
 * - 花括号 / 方括号在 Markdown、代码、公式里太常见，容易与真实内容撞车；
 * - `⟦⟧` 是数学双括号，几乎只会出现在这里，且主流 MT 服务会原样保留（不译、不拆）。
 *
 * 即便如此仍留了兜底：[restore] 容忍服务端插入空白（`⟦ 0 ⟧`），
 * 并且**任何一枚占位符丢失都返回 null** —— 半截译文比不翻更容易误导，
 * 调用方会退化成「不加保护地再翻一次」（见 [Translator]）。
 */
object PlaceholderGuard {

    /** 保护结果：`text` 是被替换后的文本，`tokens` 按占位符下标存放原片段。 */
    data class Guarded(val text: String, val tokens: List<String>) {

        val isEmpty: Boolean get() = tokens.isEmpty()

        /** 去掉占位符后还剩多少「实义字母/汉字」——为 0 说明整段都是标记，无需翻译。 */
        val hasContent: Boolean
            get() = text.replace(PLACEHOLDER, "").any { it.isLetter() || isHanChar(it) }
    }

    private val PLACEHOLDER = Regex("""⟦\s*(\d+)\s*⟧""")
    private val PLACEHOLDER_LIKE = Regex("""⟦[^⟧]*⟧""")

    /**
     * 保护顺序即优先级（先长的、更具体的先占位）。
     *
     * 用「依次替换」而不是合并成一条大正则：替换后的片段变成 `⟦n⟧`，
     * 后续规则不可能再命中它，天然避免重叠，也便于逐条单测。
     */
    private val RULES: List<Regex> = listOf(
        Regex("""`[^`\n]{1,120}`"""),                                                    // 行内代码
        Regex("""https?://[^\s<>()\[\]{}"']+"""),                                        // URL
        Regex("""\b[\w.+-]+@[\w-]+\.[\w.-]{2,}\b"""),                                    // 邮箱
        Regex("""(?<![A-Za-z0-9_])@[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})"""),                // GitHub @提及
        Regex("""(?<![A-Za-z0-9_])#\d{1,10}"""),                                         // issue / PR 编号
        Regex("""\$\{[^}\n]{1,60}\}"""),                                                 // ${模板变量}
        Regex("""%(?:\d+\$)?[sdif]"""),                                                  // printf 占位符
        // 提交 SHA：7~40 位十六进制，且**必须含数字**——否则 added / beefed 这类纯字母词会误伤
        Regex("""\b(?=[0-9a-f]{7,40}\b)(?=[0-9a-f]*\d)[0-9a-f]{7,40}\b"""),
    )

    /** 把 [text] 里的标记替换成占位符。 */
    fun protect(text: String): Guarded {
        var out = text
        val tokens = mutableListOf<String>()
        for (rule in RULES) {
            out = rule.replace(out) { m ->
                tokens += m.value
                "⟦${tokens.size - 1}⟧"
            }
        }
        return Guarded(out, tokens)
    }

    /**
     * 还原占位符。
     *
     * @return 还原后的文本；**任一占位符丢失**（服务端改写/吞掉）时返回 null。
     */
    fun restore(translated: String, tokens: List<String>): String? {
        if (tokens.isEmpty()) return translated
        val used = BooleanArray(tokens.size)
        val sb = StringBuilder()
        var last = 0
        for (m in PLACEHOLDER.findAll(translated)) {
            val i = m.groupValues[1].toIntOrNull() ?: continue
            if (i !in tokens.indices) continue
            used[i] = true
            sb.append(translated, last, m.range.first).append(tokens[i])
            last = m.range.last + 1
        }
        sb.append(translated, last, translated.length)
        if (used.any { !it }) return null
        // 服务端可能把 ⟦ 归一到别的括号（例如 ［0］），这类残留宁可判失败也不要插进页面
        val leftover = PLACEHOLDER_LIKE.find(sb)
        if (leftover != null) return null
        return sb.toString()
    }
}
