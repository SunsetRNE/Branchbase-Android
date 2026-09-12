package com.branchbase.translate

import android.content.Context
import android.webkit.JavascriptInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * 页面 ↔ 原生的翻译桥（`@JavascriptInterface`，注入名固定为 `BBTranslate`）。
 *
 * ## 为什么必须异步
 *
 * 一批 3 段就是 3 次 HTTP（每段数百毫秒），同步返回会把 WebView 的 JS 线程按住好几秒
 * ——页面卡死、悬浮球点不动。所以 `request` 立刻返回，翻完再回调
 * `window.__bbTranslated(id, toLang, json)`（页面脚本按 `id` 找回各自的回调）。
 *
 * ## 状态回推
 *
 * 每批结束后把调度器状态（`ok` / `quota` / `paused`）推给页面
 * （`window.__bbTranslateStatus(state)`），页面据此把悬浮球切成「额度用尽 / 翻译失败」。
 * 没有这条通道时，失败表现为「点了没反应」——用户无法区分「在翻」和「挂了」。
 *
 * ## 暴露面只有这四个方法
 *
 * `request` / `retry` / `state` / `pref`。**不再多暴露**：每个 `@JavascriptInterface`
 * 方法都是页面脚本（以及页面里的 XSS）能碰到的攻击面。[pref] 是悬浮面板的快捷设置
 * 回写入口，键与值都由 [TranslateQuickSettings] 白名单校验 —— 凭据类字段（API Key /
 * 模型 / 接入地址 / 服务商）**不在白名单里**，页面永远改不到。
 */
class TranslateBridge(
    private val scope: CoroutineScope,
    private val translator: Translator,
    private val onResult: (id: String, toLang: String, translations: List<String>) -> Unit,
    private val onStatus: (String) -> Unit = {},
    /** 快捷设置落盘用（null = 不落盘，单测用）。 */
    private val context: Context? = null,
) {

    /**
     * 请求翻译一批文本。
     *
     * @param id 页面侧生成的请求号，回调时原样带回
     * @param toLang 目标语言（`zh-CN` / `en`）；空串时用默认语言
     * @param payload JSON 数组字符串（待译文本）
     */
    @JavascriptInterface
    fun request(id: String, toLang: String, payload: String) {
        val texts = parseTexts(payload)
        val to = toLang.ifBlank { TranslateConfig.ZH }
        val from = TranslateLang.sourceOf(to)
        if (texts.isEmpty()) {
            onResult(id, to, emptyList())
            return
        }
        scope.launch {
            val out = withContext(Dispatchers.IO) { translator.translateAll(texts, from, to) }
            onResult(id, to, out)
            onStatus(translator.engineState().pageStatus())
        }
    }

    /** 悬浮球面板上的「重试」：清掉熔断状态，让下一批重新尝试。 */
    @JavascriptInterface
    fun retry() {
        translator.resetFailures()
        onStatus(translator.engineState().pageStatus())
    }

    /** 当前调度器状态（`ok` / `quota` / `paused`），同步返回，供页面启动时决定球的样子。 */
    @JavascriptInterface
    fun state(): String = translator.engineState().pageStatus()

    /**
     * 悬浮球面板上的快捷设置：把一项设置写回原生（设置页与正文页因此共用同一份配置）。
     *
     * 白名单与取值校验都在 [TranslateQuickSettings]：这里不做任何「尽力而为」的解析
     * ——不认识的键、不合法的值直接丢掉，页面拿不到任何反馈（也不需要）。
     */
    @JavascriptInterface
    fun pref(key: String, value: String) {
        val ctx = context ?: return
        runCatching { TranslateQuickSettings.persist(ctx, key, value) }
    }

    private fun parseTexts(payload: String): List<String> = runCatching {
        val arr = JSONArray(payload)
        (0 until arr.length()).map { arr.optString(it) }
    }.getOrDefault(emptyList())
}
