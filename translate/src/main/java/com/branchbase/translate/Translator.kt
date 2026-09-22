package com.branchbase.translate

/**
 * 沉浸式翻译的**门面**（上层唯一入口）。
 *
 * ## 职责
 *
 * 把「判定要不要翻 → 查缓存 → 占位符保护 → 分片 → 串行调度 → 还原 → 回写缓存」
 * 串成一条流水线，对调用方只暴露两件事：
 * - [translateParagraph]：翻一段；
 * - [translateAll]：翻一批（顺序与入参一一对应，失败位为空串）。
 *
 * 页面桥（[TranslateBridge]）与设置页都只依赖这个类，模块内部的
 * 引擎/缓存/调度器可以随意替换而不影响它们。
 *
 * ## 三条不变式
 *
 * 1. **失败只影响自己**：任一段失败 → 该位置返回空串，其余段落照常（[translateAll]）；
 * 2. **绝不插入半截译文**：分片里任一片失败、或占位符还原失败且兜底也失败，整段返回 null；
 * 3. **缓存键 = (源语言, 目标语言, 变体, 归一化原文)**：同一段在 README / Issue / 发布说明里
 *    重复出现时只翻一次（这类正文重复率出乎意料地高）。
 *    其中**变体**是「谁翻的 + 怎么翻的」（后端 / 模型 / 接入地址 / 占位符保护开关）——
 *    少了它，换后端后旧译文会继续命中，新后端一次都不会被调用（见 [TranslateCache] 的类注释）。
 */
class Translator(
    private val engine: TranslateEngine,
    private val cache: TranslateCache = TranslateCache(),
    private val scheduler: TranslateScheduler = TranslateScheduler(engine),
    private val rules: PageRules = PageRules.DEFAULT,
    /** 是否启用占位符保护（设置页开关；测试里可注入固定值）。 */
    private val protectTokens: () -> Boolean = { true },
    /**
     * 译文变体指纹（后端 + 模型 + 接入地址），由 :app 从设置里取（见 `TranslateConfig.cacheVariant`）。
     *
     * 它是**缓存键的一部分**：换后端/换模型/换网关之后必须换键，否则新后端一次都不会被调用。
     * 默认空串（测试与「无引擎」实例够用）。
     */
    private val engineVariant: () -> String = { "" },
    /** 每批翻译后的一行汇总（命中/未命中/变体），由 :app 接到日志上。默认不记。 */
    private val log: (String) -> Unit = {},
) {

    // 命中率统计（进程内累计）：设置页展示、日志排查都用它
    private val hits = java.util.concurrent.atomic.AtomicInteger()
    private val misses = java.util.concurrent.atomic.AtomicInteger()

    /**
     * 翻译一段。
     *
     * @return 译文；不需要翻（已经是目标语言 / 太短 / 太长 / 整段是标记）、
     *   或翻译失败时返回 null —— 调用方一律「跳过这一段」。
     */
    suspend fun translateParagraph(text: String, from: String, to: String): String? {
        val src = TranslateTextPolicy.normalize(text)
        if (!TranslateTextPolicy.needsTranslation(src, to, rules)) return null
        val body = if (src.length > MAX_PARAGRAPH) src.take(MAX_PARAGRAPH) else src

        // 变体必须在**查询之前**定下来：占位符保护会改变译文内容（URL / 代码块是否被保护），
        // 所以它也是键的一部分 —— 开着保护翻过一遍，关掉后不能命中「保护版」的译文。
        val protect = protectTokens()
        val variant = variantOf(protect)

        cache.get(from, to, body, variant)?.let {
            hits.incrementAndGet()
            return it
        }
        misses.incrementAndGet()

        val result = translateUncached(body, from, to, protect = protect)
        if (result.isNullOrBlank()) return null
        cache.put(from, to, body, result, variant)
        return result
    }

    /**
     * 批量翻译（**串行**，由调度器的闸门保证一次只发一个请求）。
     *
     * 长度与入参一致：不需要翻、失败的位置都是空串 —— 页面脚本据此跳过插入。
     * 整批结束后记**一行汇总**：一段一行会把日志刷爆（一页 README 几十上百段），
     * 而「这一批命中几段 / 未命中几段」正好回答「为什么重开还要重翻」。
     */
    suspend fun translateAll(texts: List<String>, from: String, to: String): List<String> {
        val hitsBefore = hits.get()
        val missesBefore = misses.get()
        val out = texts.map { text ->
            if (text.isBlank()) "" else translateParagraph(text, from, to).orEmpty()
        }
        log(
            "一批 ${texts.size} 段：命中 ${hits.get() - hitsBefore} / 未命中 ${misses.get() - missesBefore}" +
                "（变体「${variantOf(protectTokens())}」）",
        )
        return out
    }

    /** 设置页展示用：缓存条数 + 命中率 + 熔断状态。 */
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
        )
    }

    /** 清空译文缓存（内存 + 磁盘）。 */
    suspend fun clearCache() = cache.clear()

    /** 页面按钮上的「重试」/ 设置页改完后：清掉熔断状态，让下一批请求重新尝试。 */
    fun resetFailures() = scheduler.reset()

    fun engineState(): TranslateScheduler.State = scheduler.state()

    /** 这一段的缓存变体：后端指纹 + 占位符保护开关（两者都改变译文内容）。 */
    private fun variantOf(protect: Boolean): String =
        engineVariant() + "|protect=" + if (protect) "1" else "0"

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

    // ── 内部流水线 ──

    private suspend fun translateUncached(
        body: String,
        from: String,
        to: String,
        protect: Boolean,
    ): String? {
        val guarded = if (protect) PlaceholderGuard.protect(body) else PlaceholderGuard.Guarded(body, emptyList())

        // 整段都是标记（例如正文里孤零零一条 URL）：翻出来只会是垃圾，直接跳过
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
        return if (protect) translateUncached(body, from, to, protect = false) else null
    }

    private companion object {
        /** 单次请求的字符上限：MyMemory 500，DeepSeek 不限制但没必要整段发（后端 500，留 50 余量给编码）。 */
        const val MAX_CHUNK = 450

        /** 译文字数上限：超长段落（贴日志）翻出来也没人看，截断避免白烧额度。 */
        const val MAX_PARAGRAPH = 1800

        /** 「测试连接」用的固定短句（英语 → 目标语言）。 */
        const val SELF_TEST_TEXT = "Hello, this is a translation connectivity test."
    }
}

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
) {
    val blocked: Boolean get() = quotaBlocked || authFailed || paused

    /**
     * 命中率（0~1）。没查过任何一段时返回 null —— 显示 0% 会让人以为缓存坏了。
     *
     * 它是**判断缓存有没有在干活**的唯一指标：命中率低说明键对不上（变体/语言码/归一化不一致），
     * 而不是「缓存没生效」。分段计数同时能看出「一篇长文重开时有多少段被重翻」。
     */
    val hitRate: Float?
        get() = (hits + misses).takeIf { it > 0 }?.let { hits.toFloat() / it }
}
