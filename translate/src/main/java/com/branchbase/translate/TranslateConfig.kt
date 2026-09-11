package com.branchbase.translate

import android.content.Context
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
 *   弱网下还拖首屏）；默认关 + 页内右下角「译」按钮手动触发，把决定权交给用户。
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
) {

    val targetLang: TranslateLang get() = TranslateLang.byCode(target)

    val targetLabel: String get() = targetLang.label

    /** 源语言：由目标语言反推（目前只支持中英互译）。 */
    val source: String get() = TranslateLang.sourceOf(target)

    val providerKind: TranslateProvider get() = TranslateProvider.byCode(provider)

    /** 当前后端是否已具备可用条件（DeepSeek 需要非空 Key）。 */
    val ready: Boolean get() = !providerKind.requiresKey || apiKey.isNotBlank()

    /**
     * 传给 Rust 后端的选项 JSON（`core/src/translate/mod.rs::Options`）。
     *
     * 与 [TranslateSettings.pageConfigJson] 严格分开：那份会**注入到 WebView 页面**里，
     * 这份含 API Key，只走 JNI。两者的字段集合刻意不重叠，避免哪天顺手合并时泄密。
     */
    fun engineOptionsJson(): String = JSONObject()
        .put("provider", providerKind.code)
        .put("apiKey", apiKey)
        .put("model", model)
        .put("baseUrl", baseUrl)
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
            baseUrl = p.getString(KEY_BASE_URL, "") ?: "",
        )
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

    /** 恢复默认（清掉本功能的键，其它设置不动；**含 API Key**）。 */
    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_ENABLED).remove(KEY_TARGET).remove(KEY_DUAL)
            .remove(KEY_STYLE).remove(KEY_PERSIST).remove(KEY_PROTECT)
            .remove(KEY_PROVIDER).remove(KEY_API_KEY).remove(KEY_MODEL).remove(KEY_BASE_URL)
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
     * `rules` 是「哪一段值得翻」的判定参数 —— 页面脚本只使用、不定义，
     * 保证与原生侧 [TranslateTextPolicy] 用的是同一套阈值。
     */
    fun pageConfigJson(config: TranslateConfig): String = JSONObject()
        .put("enabled", config.enabled)
        .put("from", config.source)
        .put("to", config.target)
        .put("dual", config.dual)
        .put("style", config.style)
        .put("protect", config.protect)
        .put("rules", JSONObject(PageRules.DEFAULT.toJson()))
        .toString()

    /** 完整注入脚本（含分号，可直接塞进 `<script>`）。 */
    fun injectScript(config: TranslateConfig): String =
        "window.__bbTranslate = ${pageConfigJson(config)};"

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
