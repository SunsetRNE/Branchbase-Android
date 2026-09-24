package com.branchbase.translate

/**
 * 沉浸式翻译的**门面**（上层唯一入口）。
 *
 * ## 职责
 *
 * 把「判定要不要翻 / 翻哪一部分 → 查缓存 → 占位符保护 → 分片 → 串行调度 → 还原 → 回写缓存」
 * 串成一条流水线，对调用方只暴露两件事：
 * - [translateOne]：翻一段；
 * - [translateBatch]：翻一批（顺序与入参一一对应）。
 *
 * 判定本身在 [TranslateDecisionEngine]（1.0.89 引入），这里负责把它的三种结论落成产物：
 * 跳过（[ParagraphTranslation.None]）、整段译文（[ParagraphTranslation.Whole]）、
 * 匹配性译文（[ParagraphTranslation.Matched]）。
 * 页面桥（[TranslateBridge]）与设置页都只依赖这个类，模块内部的引擎/缓存/调度器可以随意替换。
 *
 * ## 四条不变式
 *
 * 1. **失败只影响自己**：任一段失败 → 该位置是 [ParagraphTranslation.Failed]，其余段落照常
 *    （[translateBatch]）；匹配性翻译里失败的**片段**只是不出现在配对里，已拿到的每一对都是完整的；
 * 2. **绝不插入半截译文**：分片里任一片失败、或占位符还原失败且兜底也失败，整段判失败；
 * 3. **一致就不插**：译后与原文归一化相同（[TranslateDecisionEngine.isIdentical]）的段落
 *    不插卡片 —— 它记进判定缓存（[verdicts]），**下次连请求都不发**。
 *    没有这一步时，服务端把原文原样还回来会插出一张与原文逐字相同的卡片，
 *    用户看到的是「翻译坏了」，而且每次重开页面都要再问一次；
 * 4. **缓存键 = (源语言, 目标语言, 变体, 归一化原文)**：同一段在 README / Issue / 发布说明里
 *    重复出现时只翻一次（这类正文重复率出乎意料地高）。
 *    其中**变体**是「谁翻的 + 怎么翻的」（后端 / 模型 / 接入地址 / 占位符保护开关）——
 *    少了它，换后端后旧译文会继续命中，新后端一次都不会被调用（见 [TranslateCache] 的类注释）。
 *    匹配性翻译的每个**片段**同样按这条键各自缓存（`npm run dev` 在全站只翻一次）。
 */
class Translator(
    private val engine: TranslateEngine,
    private val cache: TranslateCache = TranslateCache(),
    private val scheduler: TranslateScheduler = TranslateScheduler(engine),
    /**
     * 判定规则（[PageRules]）。
     *
     * 做成函数而不是常量：混排规则是用户设置（设置 → 沉浸式翻译 → 中英混排），
     * 改完必须**下一段就生效** —— 而页面脚本侧的规则是每次进页面时注入的，
     * 原生侧若钉死在 install 那一刻的取值，两边就会各判各的（表现为「点了没反应」）。
     */
    private val rules: () -> PageRules = { PageRules.DEFAULT },
    /** 是否启用占位符保护（设置页开关；测试里可注入固定值）。 */
    private val protectTokens: () -> Boolean = { true },
    /**
     * 译文变体指纹（后端 + 模型 + 接入地址），由 :app 从设置里取（见 `TranslateConfig.cacheVariant`）。
     *
     * 它是**缓存键的一部分**：换后端/换模型/换网关之后必须换键，否则新后端一次都不会被调用。
     * 默认空串（测试与「无引擎」实例够用）。
     */
    private val engineVariant: () -> String = { "" },
    /** 每批翻译后的一行汇总（命中/未命中/判定跳过/变体），由 :app 接到日志上。默认不记。 */
    private val log: (String) -> Unit = {},
) {

    // 命中率与判定统计（进程内累计）：设置页展示、日志排查都用它
    private val hits = java.util.concurrent.atomic.AtomicInteger()
    private val misses = java.util.concurrent.atomic.AtomicInteger()
    private val skipped = java.util.concurrent.atomic.AtomicInteger()
    private val identical = java.util.concurrent.atomic.AtomicInteger()
    private val matchedParts = java.util.concurrent.atomic.AtomicInteger()
    private val matchedParagraphs = java.util.concurrent.atomic.AtomicInteger()

    /**
     * 判定缓存：**「这段翻出来还是它自己」**（译后与原文一致 / 片段全部不可译）。
     *
     * 只放内存（进程内）：它省的是「同一段反复问服务端」，而重新装一次 App 的代价
     * 只是一次注定返回原文的请求；落盘则要在磁盘缓存里混进一种非译文的值，
     * 得不偿失（磁盘缓存的每条记录都被当成译文用）。
     *
     * 键里带规则指纹（[verdictKey]）：用户把混排规则从「只翻片段」改成「整段翻」之后，
     * 上一次「这段没什么可翻」的结论就不成立了。
     */
    private val verdicts = LruCache<String, Boolean>(VERDICT_ENTRIES)

    /**
     * 翻译一段。
     *
     * @return [ParagraphTranslation.None]（不需要翻 / 与目标文字一致）、
     *   [ParagraphTranslation.Whole]、[ParagraphTranslation.Matched]，或
     *   [ParagraphTranslation.Failed]（这一段落翻译失败）。
     */
    suspend fun translateOne(text: String, from: String, to: String): ParagraphTranslation {
        val r = rules()
        val src = TranslateTextPolicy.normalize(text)
        val protect = protectTokens()

        // 变体必须在**查询之前**定下来：占位符保护会改变译文内容（URL / 代码块是否被保护），
        // 所以它也是键的一部分 —— 开着保护翻过一遍，关掉后不能命中「保护版」的译文。
        val variant = variantOf(protect)
        val key = verdictKey(to, variant, r, src)

        // 判定引擎：一致即跳过（段内没有需要翻的内容 / 命中跳过规则 / 用户规则选了不翻）
        val decision = TranslateDecisionEngine.decide(src, to, r)
        if (decision is TranslateDecision.Skip) {
            skipped.incrementAndGet()
            return ParagraphTranslation.None
        }
        // 上一次已经确认过「这段翻出来还是它自己」：连请求都不发（见 [verdicts]）
        if (verdicts.get(key) == true) {
            skipped.incrementAndGet()
            return ParagraphTranslation.None
        }

        return when (decision) {
            is TranslateDecision.Whole -> whole(decision.source, from, to, protect, variant, key)
            is TranslateDecision.Matched -> matched(decision, from, to, protect, variant, key)
            is TranslateDecision.Skip -> ParagraphTranslation.None
        }
    }

    /**
     * 批量翻译（**串行**，由调度器的闸门保证一次只发一个请求）。
     *
     * 长度与入参一致，顺序一一对应：页面脚本据此把结果安回各自的段落。
     * 整批结束后记**一行汇总**：一段一行会把日志刷爆（一页 README 几十上百段），
     * 而「这一批命中几段 / 未命中几段 / 判定跳过几段 / 匹配翻译几段」正好回答
     * 「为什么重开还要重翻」「为什么这段没翻」。
     */
    suspend fun translateBatch(texts: List<String>, from: String, to: String): List<ParagraphTranslation> {
        val hitsBefore = hits.get()
        val missesBefore = misses.get()
        val skippedBefore = skipped.get()
        val out = texts.map { text ->
            if (text.isBlank()) ParagraphTranslation.None else translateOne(text, from, to)
        }
        log(
            "一批 ${texts.size} 段：命中 ${hits.get() - hitsBefore} / 未命中 ${misses.get() - missesBefore}" +
                " / 判定跳过 ${skipped.get() - skippedBefore}" +
                " / 匹配 ${out.count { it is ParagraphTranslation.Matched }} 段" +
                "（变体「${variantOf(protectTokens())}」）",
        )
        return out
    }

    /** 设置页展示用：缓存条数 + 命中率 + 判定统计 + 熔断状态。 */
    suspend fun stats(): TranslateStats {
        val state = scheduler.state()
        return TranslateStats(
            memoryCount = cache.memoryCount(),
            diskCount = cache.diskCount(),
            quotaBlocked = state.quotaBlocked,
            authFailed = state.authFailed,
            paused = state.paused,
            hits = hits.get(),
            misses = misses.get(),
            skipped = skipped.get(),
            identical = identical.get(),
            matched = matchedParagraphs.get(),
            parts = matchedParts.get(),
        )
    }

    /**
     * 清空译文缓存（内存 + 磁盘）**与判定缓存**。
     *
     * 判定缓存一起清是刻意的：它记的是「这段不用翻」，与译文缓存是同一类东西 ——
     * 用户点「清空」想要的是「下次重新判一遍、重新翻一遍」，留着旧判定会让
     * 一部分段落「清了也不翻」。
     */
    suspend fun clearCache() {
        cache.clear()
        verdicts.clear()
    }

    /** 页面按钮上的「重试」/ 设置页改完后：清掉熔断状态，让下一批请求重新尝试。 */
    fun resetFailures() = scheduler.reset()

    fun engineState(): TranslateScheduler.State = scheduler.state()

    /** 这一段的缓存变体：后端指纹 + 占位符保护开关（两者都改变译文内容）。 */
    private fun variantOf(protect: Boolean): String =
        engineVariant() + "|protect=" + if (protect) "1" else "0"

    /**
     * 判定缓存的键：目标语言 + 变体 + **规则指纹** + 归一化原文。
     *
     * 规则指纹只取真正会改变判定的字段（混排策略与片段阈值）—— 把整个 [PageRules]
     * 拼进去的话，改一个与本段无关的阈值就会让全部判定缓存失效。
     */
    private fun verdictKey(to: String, variant: String, rules: PageRules, src: String): String =
        "$to|$variant|${rules.matchPolicy}/${rules.matchMinLen}/${rules.maxMatchParts}|$src"

    // ── 内部流水线 ──

    /**
     * 整段翻（外语为主的段落，或用户把混排规则设成了「整段一起翻」）。
     *
     * 缓存键用**原文**（`body`）而不是守卫文本：守卫文本里的 `⟦n⟧` 编号是段落内的局部下标，
     * 拿它当键会让「同一段原文在不同段落里编号不同」而互相撞键。
     */
    private suspend fun whole(
        src: String,
        from: String,
        to: String,
        protect: Boolean,
        variant: String,
        key: String,
    ): ParagraphTranslation {
        // 译文字数上限：超长段落（贴日志）翻出来也没人看，截断避免白烧额度
        val body = if (src.length > MAX_PARAGRAPH) src.take(MAX_PARAGRAPH) else src

        cache.get(from, to, body, variant)?.let {
            hits.incrementAndGet()
            return ParagraphTranslation.Whole(it)
        }
        misses.incrementAndGet()

        val guarded = guard(body, protect)
        // 整段都是标记（例如正文里孤零零一条 URL）：翻出来只会是垃圾，直接跳过
        if (protect && !guarded.hasContent) return ParagraphTranslation.None

        val translated = translateUncached(body, guarded, from, to, protect)
            ?: return ParagraphTranslation.Failed

        // 「所需要的目标文字」与「被翻译目标」一致：服务端把原文原样还回来了。
        // 插一张与原文逐字相同的卡片只会让页面变脏 —— 记成「无需翻译」并记住这次判定。
        if (TranslateDecisionEngine.isIdentical(body, translated)) {
            verdicts.put(key, true)
            identical.incrementAndGet()
            return ParagraphTranslation.None
        }

        cache.put(from, to, body, translated, variant)
        return ParagraphTranslation.Whole(translated)
    }

    /**
     * 匹配性翻译：只翻段内**需要翻的片段**，按「片段 → 译文」配对返回。
     *
     * 每个片段各自走一遍「缓存 → 保护 → 调度 → 还原」，并各自进缓存：
     * `npm run dev` 这种片段在全站只翻一次。片段与原文一致时（专有名词、产品名）
     * 不产出配对 —— 配对里 `Docker → Docker` 这种行没有信息量。
     */
    private suspend fun matched(
        decision: TranslateDecision.Matched,
        from: String,
        to: String,
        protect: Boolean,
        variant: String,
        key: String,
    ): ParagraphTranslation {
        val out = mutableListOf<TranslatedPart>()
        var failures = 0

        for (part in decision.parts) {
            val cached = cache.get(from, to, part, variant)
            if (cached != null) {
                hits.incrementAndGet()
                out += TranslatedPart(part, cached)
                continue
            }
            misses.incrementAndGet()

            val translated = translateUncached(part, guard(part, protect), from, to, protect)
            when {
                translated == null -> failures++
                TranslateDecisionEngine.isIdentical(part, translated) -> identical.incrementAndGet()
                else -> {
                    cache.put(from, to, part, translated, variant)
                    out += TranslatedPart(part, translated)
                }
            }
        }

        matchedParts.addAndGet(out.size)
        return when {
            // 一部分失败一部分成功：已拿到的每一对都是完整的，照常展示（失败的那几对不展示）
            out.isNotEmpty() -> {
                matchedParagraphs.incrementAndGet()
                ParagraphTranslation.Matched(out)
            }
            // 全都没翻出来：按整段失败处理（页面据此累计失败批次）
            failures > 0 -> ParagraphTranslation.Failed
            // 片段与原文全都一致：这段其实没有需要翻的内容 —— 记住，下次不再问
            else -> {
                verdicts.put(key, true)
                ParagraphTranslation.None
            }
        }
    }

    /** 按设置决定是否加占位符保护。 */
    private fun guard(text: String, protect: Boolean): PlaceholderGuard.Guarded =
        if (protect) PlaceholderGuard.protect(text) else PlaceholderGuard.Guarded(text, emptyList())

    /**
     * 送出去翻、拿回来还原（不含缓存与判定）。
     *
     * @param body 原文（分片上限与「不加保护地重翻」都用它）
     * @param guarded [body] 的保护结果（[protect] 为 false 时是原样）
     * @return 还原后的译文；失败或整段没有实义内容时返回 null
     */
    private suspend fun translateUncached(
        body: String,
        guarded: PlaceholderGuard.Guarded,
        from: String,
        to: String,
        protect: Boolean,
    ): String? {
        if (protect && !guarded.hasContent) return null

        val out = StringBuilder()
        for (piece in chunk(guarded.text, MAX_CHUNK)) {
            when (val r = scheduler.call(piece, from, to)) {
                is EngineResult.Ok -> {
                    if (out.isNotEmpty()) out.append('\n')
                    out.append(r.text.trim())
                }

                is EngineResult.Fail -> return null
            }
        }

        val joined = out.toString().trim()
        if (joined.isEmpty()) return null
        if (guarded.isEmpty) return joined

        PlaceholderGuard.restore(joined, guarded.tokens)?.let { return it }
        // 占位符被服务端改写/吞掉 → 退化成「不加保护再翻一次」。
        // 代价是可能翻坏 URL，但总好过整段没有译文；只兜底一层，不会递归。
        return if (protect) translateUncached(body, guard(body, false), from, to, protect = false) else null
    }

    /**
     * 设置页的「测试连接」：**绕过缓存、判定与占位符保护**，直接请求一次固定文本。
     *
     * 为什么走调度器而不是直连引擎：调度器才是「真实链路」——串行闸门、熔断与错误分类
     * 都在它里面；测出「Key 无效」时，页面侧的按钮也会同步变成可重试状态，信息是一致的。
     * 测试前先 [resetFailures]，否则上一次的熔断会让测试直接返回失败、看不出真实原因。
     */
    suspend fun selfTest(
        text: String = SELF_TEST_TEXT,
        from: String = LANG_EN,
        to: String = LANG_ZH,
    ): EngineResult {
        scheduler.reset()
        return scheduler.call(text, from, to)
    }

    private companion object {
        /** 单次请求的字符上限：MyMemory 500，DeepSeek 不限制但没必要整段发（后端 500，留 50 余量给编码）。 */
        const val MAX_CHUNK = 450

        /** 译文字数上限：超长段落（贴日志）翻出来也没人看，截断避免白烧额度。 */
        const val MAX_PARAGRAPH = 1800

        /** 「测试连接」用的固定短句（英语 → 目标语言）。 */
        const val SELF_TEST_TEXT = "Hello, this is a translation connectivity test."

        /** 判定缓存条目上限：一页正文几十到上百段，512 条足够覆盖几页。 */
        const val VERDICT_ENTRIES = 512
    }
}

/**
 * 一段的翻译产物。
 *
 * 与 [TranslateDecision] 的区别：那个是**判定**（还没翻），这个是**产物**（翻完了，或明确失败）。
 * 桥（[TranslateBridge]）把它编码成页面脚本认识的 JSON（见 [TranslatePagePayload.encode]）。
 */
sealed interface ParagraphTranslation {

    /**
     * 不需要翻：本身就是目标文字、命中跳过规则，或译后与原文一致 —— 页面什么都不做。
     */
    data object None : ParagraphTranslation

    /**
     * 这一段翻译失败了（网络/额度/熔断）。
     *
     * 与 [None] 分开是必须的：页面侧靠「一批里一段都没插进去」判断服务不可用，
     * 而判定跳过（[None]）也会「一段都没插进去」—— 混在一起时，一页全是中文的正文
     * 会被误报成「翻译失败」。
     */
    data object Failed : ParagraphTranslation

    /** 整段译文（块级插入，本模块一直以来的行为）。 */
    data class Whole(val text: String) : ParagraphTranslation

    /** 匹配性译文：只翻了段内这些片段（按出现顺序，每对的原文与译文都完整）。 */
    data class Matched(val parts: List<TranslatedPart>) : ParagraphTranslation
}

/** 一对「原文片段 → 译文片段」。 */
data class TranslatedPart(val source: String, val translated: String)

/** 设置页展示用的统计快照。 */
data class TranslateStats(
    val memoryCount: Int,
    val diskCount: Int,
    /** 额度用尽（余额/限流）：本会话已停翻。 */
    val quotaBlocked: Boolean,
    /** API Key 未配置或失效：需要用户去设置里处理。 */
    val authFailed: Boolean,
    /** 连续失败过多，已暂停。 */
    val paused: Boolean,
    /** 本进程内命中的段数。 */
    val hits: Int = 0,
    /** 本进程内未命中的段数（未命中才真的发请求）。 */
    val misses: Int = 0,
    /** 本进程内判定为「无需翻译」的段数（本身就是目标文字 / 一致）。 */
    val skipped: Int = 0,
    /** [skipped] 里「译后与原文一致」的那部分（单独计数便于排查服务端行为）。 */
    val identical: Int = 0,
    /** 匹配性翻译的段数（只翻了段内部分片段）。 */
    val matched: Int = 0,
    /** 匹配性翻译产出的「片段 → 译文」对数。 */
    val parts: Int = 0,
) {
    val blocked: Boolean get() = quotaBlocked || authFailed || paused

    /**
     * 命中率（0~1）。没查过任何一段时返回 null —— 显示 0% 会让人以为缓存坏了。
     *
     * 它是**判断缓存有没有在干活**的唯一指标：命中率低说明键对不上（变体/语言码/归一化不一致），
     * 而不是「缓存没生效」。分段计数同时能看出「一篇长文重开时有多少段被重翻」。
     * 判定跳过的段落不进这个分母（它们压根没查缓存）。
     */
    val hitRate: Float?
        get() = (hits + misses).takeIf { it > 0 }?.let { hits.toFloat() / it }
}
