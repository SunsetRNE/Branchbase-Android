package com.branchbase.translate

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 沉浸式翻译的用户设置。
 *
 * ## 字段分成三组
 *
 * **阅读体验**（只影响页面表现，改完立刻生效）：
 * - [dual]：对照（原文在上、译文在下）是「沉浸式翻译」的默认读法；仅译文适合小屏快速浏览。
 *   两者共用同一份译文缓存，切换不会重翻；
 * - [style]：译文的视觉样式（卡片 / 下划线 / 淡灰），只影响注入 CSS 的一个属性，
 *   不触发重翻、也不用重建 WebView；
 * - [target]：只做中英两向（源语言由目标反推，见 [TranslateLang]），不引入语言选择器的复杂度；
 * - [enabled]：**默认关**。开启意味着打开任意正文页都会自动发翻译请求（消耗额度、
 *   弱网下还拖首屏）；默认关 + 页面上的悬浮球手动触发，把决定权交给用户。
 *
 * **后端与凭据**（见 [TranslateProvider]）：
 * - [provider]：MyMemory（免费，默认）或 DeepSeek（自带 Key）；
 * - [apiKey]：DeepSeek 的 Key。**只在 App 进程内与 JNI 调用中使用**，
 *   绝不会注入页面脚本（见 [TranslateSettings.pageConfigJson] 的注释），
 *   也不会写进日志；存储方式与 GitHub token 一致（应用私有 SharedPreferences）；
 * - [model] / [baseUrl]：模型名与接入地址（空 = 官方默认），
 *   因为官方模型名会变、接口又是 OpenAI 兼容的，用户可能需要接中转或自建网关。
 *
 * **缓存与保护**：
 * - [persist]：译文是否落盘（跨进程复用）。默认开 —— 同一篇 README 重开一次就能秒出译文；
 * - [protect]：是否启用占位符保护（URL / @提及 / #编号 / SHA 等不送去翻译，译后原样还原）。
 *   默认开，出问题时可以关掉用于对比排查。
 *
 * **判定（见 [TranslateDecisionEngine]）**：
 * - [matchPolicy]：混排段落（中文段落里夹着外语片段）的处理方式。默认「只翻外语片段」——
 *   中文段落不再被整段重翻一遍，只有段内**需要翻译的内容**被送去翻译并按片段配对展示。
 *   它是 [PageRules] 的一个字段，既进原生判定、也随设置注入页面脚本（两边同一份规则）。
 */
data class TranslateConfig(
    val enabled: Boolean = false,
    val target: String = LANG_ZH,
    val dual: Boolean = true,
    val style: String = STYLE_CARD,
    val persist: Boolean = true,
    val protect: Boolean = true,
    val provider: String = TranslateProvider.MYMEMORY.code,
    val apiKey: String = "",
    val model: String = "",
    val baseUrl: String = "",
    /**
     * 端点方言（见 [TranslateProtocol]）：OpenAI Chat / OpenAI Responses / Anthropic Messages / Gemini。
     * 它与 [provider] 正交：只影响「怎么发请求」，不影响「用谁」。
     *
     * Kotlin 侧用它拼探测请求（`ModelProbe`），Rust 侧用它分派翻译请求（`core/src/translate`）。
     *
     * 缺省是 **OpenAI Chat Completions**（1.4.54 起）：覆盖面最广的一档，任何
     * 「OpenAI 兼容」的厂商 / 中转都能接。老配置里的 `deepseek` 由
     * [TranslateProtocol.byCode] 折回本档，地址由 [TranslateSettings] 的迁移补成
     * `https://api.deepseek.com/v1`（否则会变成「DeepSeek 的 Key + OpenAI 的地址」）。
     */
    val protocol: String = TranslateProtocol.default.code,
    /**
     * 思维深度档位（见 [TranslateThinking]）。默认 [TranslateThinking.OFF]：
     * 思考会让翻译变慢、也更费 token，而「最低档就够日常翻译」是实测口径 ——
     * 所以它**默认关**，由用户自己为质量多付延迟。
     */
    val thinking: String = TranslateThinking.OFF.code,
    /** 自定义档位的原值（数字 → token 预算，词 → effort；见 [TranslateThinking]）。 */
    val thinkingCustom: String = "",
    val matchPolicy: String = PageRules.MATCH_POLICY_MATCH,
) {

    val targetLang: TranslateLang get() = TranslateLang.byCode(target)

    /** 源语言：由目标语言反推（目前只支持中英互译）。 */
    val source: String get() = TranslateLang.sourceOf(target)

    val providerKind: TranslateProvider get() = TranslateProvider.byCode(provider)

    /** 当前协议（认不出来的码回落到默认，见 [TranslateProtocol.byCode]）。 */
    val protocolKind: TranslateProtocol get() = TranslateProtocol.byCode(protocol)

    /** 当前思维深度档位（认不出来的码回落到关）。 */
    val thinkingKind: TranslateThinking get() = TranslateThinking.byCode(thinking)

    /**
     * 本次生效的判定规则（原生判定与页面脚本注入**共用这一份**）。
     *
     * 目前只有 [matchPolicy] 由设置决定，其余是 [PageRules] 的默认值；
     * 以后新增「用户可调的判定参数」也挂在这里，页面脚本与原生侧不会各拿各的。
     */
    fun rules(): PageRules = PageRules.DEFAULT.copy(matchPolicy = PageRules.matchPolicyOf(matchPolicy))

    /** 当前后端是否已具备可用条件（DeepSeek 需要非空 Key）。 */
    val ready: Boolean get() = !providerKind.requiresKey || apiKey.isNotBlank()

    /**
     * **译文缓存变体指纹**（进缓存键，见 `TranslateCache` 的类注释）。
     *
     * 组成：后端 + **协议** + 模型 + 接入地址。这四样任一变化都会改变译文，所以必须换键 ——
     * 否则换到 DeepSeek 之后命中的还是 MyMemory 的旧译文，用户看到的是「换了没效果」。
     *
     * 协议也算变体：同一句原文按 Chat Completions 与按 Anthropic Messages 发出去，
     * 提示词相同但**服务端的组织方式不同**（system 的位置、max_tokens 的有无），
     * 译文可能不同；换协议时留着旧译文等于让两个方言共用一份缓存。
     *
     * **刻意不含 API Key**：同一后端 + 同一模型/地址，换 Key 不改变译文（Key 只是通行证），
     * 把它算进去会让「换 Key」= 「整库缓存作废」，白白重烧一遍额度。
     *
     * 也不含 `style` / `dual`：那两项只影响页面表现（CSS 与插入方式），同一份译文照用 ——
     * 这正是「对照 / 仅译文」「三种样式」切换不重翻的原因（见类注释第一组字段）。
     */
    fun cacheVariant(): String =
        "${providerKind.code}|$protocol|$model|$baseUrl|${thinkingKind.code}:${thinkingCustom.trim()}"

    /**
     * 传给 Rust 后端的选项 JSON（`core/src/translate/mod.rs::Options`）。
     *
     * 与 [TranslateSettings.pageConfigJson] 严格分开：那份会**注入到 WebView 页面**里，
     * 这份含 API Key，只走 JNI。两者的字段集合刻意不重叠，避免哪天顺手合并时泄密。
     *
     * ## `protocol` 这一项现在还没被 Rust 使用
     *
     * Rust 的 `Options` 是 `#[serde(default)]` + 未知字段忽略，所以老 `.so`（不认识这个键）也不会
     * 报错 —— 1.4.51 起 Rust 侧读它分派（`translate::Protocol`），老 `.so` 则继续按 chat/completions 跑。
     */
    fun engineOptionsJson(strictThinking: Boolean = false): String = JSONObject()
        .put("provider", providerKind.code)
        .put("protocol", protocolKind.code)
        .put("apiKey", apiKey)
        .put("model", model)
        .put("baseUrl", baseUrl)
        .put(
            // 档位在本侧就折成两套值：Rust 只负责按协议摆字段，不再自己解释档位
            "thinking",
            JSONObject()
                .put("effort", thinkingKind.effort(thinkingCustom))
                .put("budgetTokens", thinkingKind.budgetTokens(thinkingCustom))
                // 探测「服务商认不认这个字段」时必须**严格**：失败要如实报错，
                // 不能走真实翻译的「去掉字段重试一次」兜底，否则探测永远成功
                .put("strict", strictThinking),
        )
        .toString()

    companion object {
        const val ZH: String = LANG_ZH
        const val EN: String = LANG_EN

        /** 译文样式：卡片（默认）/ 虚线下划线 / 淡灰无边框。 */
        const val STYLE_CARD: String = "card"
        const val STYLE_UNDERLINE: String = "underline"
        const val STYLE_PLAIN: String = "plain"

        val STYLES: List<String> = listOf(STYLE_CARD, STYLE_UNDERLINE, STYLE_PLAIN)

        val DEFAULT: TranslateConfig = TranslateConfig()
    }
}

/**
 * 设置的读写（SharedPreferences，与全局其它开关同用 `branchbase` 这份）。
 *
 * ## 为什么读接口带进程内缓存
 *
 * 设置被读得**非常频繁**：每翻译一段都要读一次 `protect`（占位符保护开关）、
 * 每次落盘前都要读一次 `persist`。SharedPreferences 本身有内存镜像，但每次仍要
 * 走一遍同步锁 + 新建 data class；这里缓存一份不可变快照，写入时失效。
 * App 是单进程，不存在多进程不同步的问题。
 */
object TranslateSettings {

    private const val PREFS = "branchbase"
    private const val KEY_ENABLED = "translate.enabled"
    private const val KEY_TARGET = "translate.target"
    private const val KEY_DUAL = "translate.dual"
    private const val KEY_STYLE = "translate.style"
    private const val KEY_PERSIST = "translate.persist"
    private const val KEY_PROTECT = "translate.protect"
    private const val KEY_PROVIDER = "translate.provider"
    private const val KEY_API_KEY = "translate.deepseek.key"
    private const val KEY_MODEL = "translate.deepseek.model"
    private const val KEY_BASE_URL = "translate.deepseek.baseUrl"
    private const val KEY_PROTOCOL = "translate.deepseek.protocol"
    private const val KEY_THINKING = "translate.thinking.level"
    private const val KEY_THINKING_CUSTOM = "translate.thinking.custom"
    /** 探测结果：某档「协议 + 地址 + 模型」认不认思维深度字段（JSON 对象，scope → bool）。 */
    private const val KEY_THINKING_SUPPORT = "translate.thinking.support"
    /** 模型列表（按协议 + 地址分桶的 JSON 对象；键名沿用 1.4.53 的名字以免丢数据）。 */
    private const val KEY_MODELS = "translate.deepseek.models"
    /** 1.4.53 的 DeepSeek 默认地址（`/v1` 与根地址等价，文档明示）。 */
    private const val LEGACY_DEEPSEEK_BASE = "https://api.deepseek.com/v1"
    private const val KEY_MATCH_POLICY = "translate.matchPolicy"

    @Volatile
    private var cached: TranslateConfig? = null

    fun read(context: Context): TranslateConfig =
        cached ?: load(context).also { cached = it }

    private fun load(context: Context): TranslateConfig {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return TranslateConfig(
            enabled = p.getBoolean(KEY_ENABLED, false),
            target = p.getString(KEY_TARGET, LANG_ZH) ?: LANG_ZH,
            dual = p.getBoolean(KEY_DUAL, true),
            style = p.getString(KEY_STYLE, TranslateConfig.STYLE_CARD)
                ?.takeIf { it in TranslateConfig.STYLES } ?: TranslateConfig.STYLE_CARD,
            persist = p.getBoolean(KEY_PERSIST, true),
            protect = p.getBoolean(KEY_PROTECT, true),
            provider = TranslateProvider.byCode(p.getString(KEY_PROVIDER, null)).code,
            apiKey = p.getString(KEY_API_KEY, "") ?: "",
            model = p.getString(KEY_MODEL, "") ?: "",
            baseUrl = migrateLegacyBaseUrl(
                rawProtocol = p.getString(KEY_PROTOCOL, null),
                baseUrl = p.getString(KEY_BASE_URL, "") ?: "",
            ),
            protocol = TranslateProtocol.byCode(p.getString(KEY_PROTOCOL, null)).code,
            thinking = TranslateThinking.byCode(p.getString(KEY_THINKING, null)).code,
            thinkingCustom = p.getString(KEY_THINKING_CUSTOM, "") ?: "",
            matchPolicy = PageRules.matchPolicyOf(p.getString(KEY_MATCH_POLICY, null)),
        )
    }

    /**
     * 1.4.53 → 1.4.54 的地址迁移：老配置「协议 = deepseek 且地址留空」。
     *
     * 老语义是「DeepSeek 的 Key + DeepSeek 的地址」，折成新语义是「OpenAI Chat 协议 +
     * `https://api.deepseek.com/v1`」。不迁移的话会变成「DeepSeek 的 Key + OpenAI 的地址」，
     * 每个请求都 401 —— 这是这次拆层里唯一会让老用户「升级后突然不能翻」的地方。
     *
     * **只读时计算，不落盘**：用户没改过设置时行为与老版本一致；一旦保存，
     * 界面里的草稿（已经带迁移后的地址）就会被写进配置，迁移自然收敛。
     * 用户如果本来填了自定义地址，则原样保留（那可能是他自己的中转）。
     */
    internal fun migrateLegacyBaseUrl(rawProtocol: String?, baseUrl: String): String {
        val wasDeepSeek = rawProtocol?.trim()?.lowercase() == "deepseek"
        if (!wasDeepSeek || baseUrl.isNotBlank()) return baseUrl
        return LEGACY_DEEPSEEK_BASE
    }

    fun setEnabled(context: Context, enabled: Boolean) = write(context, KEY_ENABLED, enabled)

    fun setTarget(context: Context, target: String) = write(context, KEY_TARGET, target)

    fun setDual(context: Context, dual: Boolean) = write(context, KEY_DUAL, dual)

    fun setStyle(context: Context, style: String) = write(context, KEY_STYLE, style)

    fun setPersist(context: Context, persist: Boolean) = write(context, KEY_PERSIST, persist)

    fun setProtect(context: Context, protect: Boolean) = write(context, KEY_PROTECT, protect)

    fun setProvider(context: Context, provider: String) =
        write(context, KEY_PROVIDER, TranslateProvider.byCode(provider).code)

    /** 保存 API Key（写入前 trim：从剪贴板粘贴常带空格/换行）。 */
    fun setApiKey(context: Context, key: String) = write(context, KEY_API_KEY, key.trim())

    fun setModel(context: Context, model: String) = write(context, KEY_MODEL, model.trim())

    /** 保存自定义接入地址（trim 并去掉末尾 `/`；空串表示用官方地址）。 */
    fun setBaseUrl(context: Context, url: String) =
        write(context, KEY_BASE_URL, url.trim().trimEnd('/'))

    /**
     * 保存协议（认不出来的码回落到默认）。
     *
     * **只改这一个键**：切换协议时不会顺手改地址或模型 —— 「地址该不该跟着换」
     * 是设置页的交互决定（它会把默认值一起带过去），存储层不做猜测。
     */
    fun setProtocol(context: Context, protocol: String) =
        write(context, KEY_PROTOCOL, TranslateProtocol.byCode(protocol).code)

    /** 保存思维深度档位与自定义值（认不出来的档位回落到关）。 */
    fun setThinking(context: Context, level: String, custom: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_THINKING, TranslateThinking.byCode(level).code)
            .putString(KEY_THINKING_CUSTOM, custom.trim())
            .apply()
        cached = null
    }

    /**
     * 上次「测试翻译」的结论：该 scope（协议 + 地址 + 模型）认不认思维深度字段。
     *
     * `null` = 还没测过（不置灰、不提示）；`false` = 服务商拒绝该字段（界面标灰 + 一句解释）。
     * 只由**显式探测**写入 —— 真实翻译的「去掉字段重试」是静默兜底，不改变这里的结论。
     */
    fun readThinkingSupport(context: Context, scope: String): Boolean? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_THINKING_SUPPORT, null) ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()?.let { if (it.has(scope)) it.getBoolean(scope) else null }
    }

    fun setThinkingSupport(context: Context, scope: String, supported: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = runCatching { JSONObject(prefs.getString(KEY_THINKING_SUPPORT, null) ?: "{}") }
            .getOrDefault(JSONObject())
        current.put(scope, supported)
        write(context, KEY_THINKING_SUPPORT, current.toString())
    }

    /**
     * 探测结论与思维深度的 scope：协议 + 地址 + **模型**（同一地址下不同模型对字段的支持可能不同）。
     */
    fun thinkingScope(protocol: TranslateProtocol, baseUrl: String, model: String): String {
        val base = baseUrl.trim().trimEnd('/').lowercase().ifEmpty { protocol.officialBaseUrl.lowercase() }
        return "${protocol.code}|$base|${model.trim()}"
    }

    /**
     * 本机保存的模型列表（2026-11 第六轮起；1.4.54 起**按协议 + 地址分桶**）。
     *
     * 它是「用户从探测结果里勾选出的子集」，只用来在设置页给候选芯片，**不参与翻译请求**
     * （真正发出去的是 [TranslateConfig.model]）。
     *
     * ## 为什么要分桶
     *
     * 1.4.53 的列表是全局一份：在 DeepSeek 下保存的 `deepseek-*` 会在切到 Anthropic 之后
     * 继续显示（真机截图里的那排芯片），点了必然 404。现在按 [scopeKey] 存，
     * 换协议 / 换地址都看不到上一家的模型。
     *
     * 存 JSON 对象（`{scope: [models]}`）而不是 `StringSet`：顺序要稳定（用户勾选的顺序
     * 就是他读到的顺序），而 `StringSet` 在 SharedPreferences 里不保证顺序。
     */
    fun readModels(context: Context, scope: String): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODELS, null) ?: return emptyList()
        return runCatching {
            val byScope = JSONObject(raw)
            val arr = byScope.optJSONArray(scope) ?: return emptyList()
            (0 until arr.length())
                .mapNotNull { arr.optString(it)?.trim()?.takeIf(String::isNotEmpty) }
                .distinct()
        }.getOrDefault(emptyList())
    }

    fun setModels(context: Context, scope: String, models: List<String>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = runCatching { JSONObject(prefs.getString(KEY_MODELS, null) ?: "{}") }.getOrDefault(JSONObject())
        current.put(
            scope,
            JSONArray(models.map { it.trim() }.filter { it.isNotEmpty() }.distinct()),
        )
        write(context, KEY_MODELS, current.toString())
    }

    /**
     * 模型列表的分桶键：协议 + 规范化后的地址。
     *
     * 地址参与分桶是因为同一档协议下，不同厂商的模型集合完全不同（OpenAI vs DeepSeek）。
     * 空地址归一成协议官方地址，避免「留空」与「显式填官方地址」被当成两个桶。
     */
    fun modelsScope(protocol: TranslateProtocol, baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/').lowercase().ifEmpty { protocol.officialBaseUrl.lowercase() }
        return "${protocol.code}|$base"
    }

    /** 保存混排段落的处理规则（认不出来的取值回落到默认，见 [PageRules.matchPolicyOf]）。 */
    fun setMatchPolicy(context: Context, policy: String) =
        write(context, KEY_MATCH_POLICY, PageRules.matchPolicyOf(policy))

    /** 恢复默认（清掉本功能的键，其它设置不动；**含 API Key**）。 */
    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_ENABLED).remove(KEY_TARGET).remove(KEY_DUAL)
            .remove(KEY_STYLE).remove(KEY_PERSIST).remove(KEY_PROTECT)
            .remove(KEY_PROVIDER).remove(KEY_API_KEY).remove(KEY_MODEL).remove(KEY_BASE_URL)
            .remove(KEY_PROTOCOL).remove(KEY_THINKING).remove(KEY_THINKING_CUSTOM)
            .remove(KEY_THINKING_SUPPORT)
            .remove(KEY_MODELS).remove(KEY_MATCH_POLICY)
            .apply()
        cached = null
    }

    /**
     * 注入到正文页的配置脚本（`window.__bbTranslate`）。
     *
     * 走注入而不是让 JS 反问原生：页面脚本在 `loadDataWithBaseURL` 时就跑，
     * 那时没有可用的同步通道问「设置是什么」；而翻译开关必须在首帧前就位，
     * 否则会出现「先按关闭渲染、再跳成开启」的闪动。
     *
     * ## 这里**绝不能**出现 API Key
     *
     * 页面是 GitHub 的 HTML（含仓库里的第三方脚本可能影响的作用域）+
     * `addJavascriptInterface` 暴露的桥；把 Key 注入进去等于把它交给页面。
     * 因此注入字段只有「页面真正需要的阅读体验」，凭据只走 JNI
     * （见 [TranslateConfig.engineOptionsJson]）。有单测钉住这条边界。
     *
     * 用 `JSONObject` 而不是字符串拼接：`to` 将来若变成用户填的语言码，
     * 拼字符串就是一个注入漏洞（`'` 会直接逃出 JSON 字面量）。
     *
     * `rules` 是「哪一段值得翻、翻哪一部分」的判定参数 —— 页面脚本只使用、不定义，
     * 保证与原生侧 [TranslateDecisionEngine] 用的是同一套阈值（含用户选的混排规则）。
     */
    fun pageConfigJson(config: TranslateConfig, dark: Boolean = false): String = JSONObject()
        .put("enabled", config.enabled)
        .put("dark", dark)
        .put("from", config.source)
        .put("to", config.target)
        .put("dual", config.dual)
        .put("style", config.style)
        .put("protect", config.protect)
        .put("rules", JSONObject(config.rules().toJson()))
        .toString()

    /** 完整注入脚本（含分号，可直接塞进 `<script>`）。 */
    fun injectScript(config: TranslateConfig, dark: Boolean = false): String =
        "window.__bbTranslate = ${pageConfigJson(config, dark)};"

    private fun write(context: Context, key: String, value: Any) {
        val e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        when (value) {
            is Boolean -> e.putBoolean(key, value)
            is String -> e.putString(key, value)
        }
        e.apply()
        cached = null
    }
}
