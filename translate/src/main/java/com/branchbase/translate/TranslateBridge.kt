package com.branchbase.translate

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
 * `request` / `retry` / `state` / `report`。**不再多暴露**：每个 `@JavascriptInterface`
 * 方法都是页面脚本（以及页面里的 XSS）能碰到的攻击面。注意**没有**「写设置」的方法 ——
 * 工具面板上的快捷设置由原生自己落盘（它本来就是原生 Compose 界面），页面既读不到
 * 也改不到凭据，少一条信任通道。
 *
 * 方向也不一样：`request` / `retry` / `state` 是页面问原生，`report` 是页面**推**状态；
 * 原生的命令（开关 / 重扫 / 清空…）走 `evaluateJavascript` 调页面脚本的
 * `window.__bbIT.command()`（见 [TranslatePageCommands]），不经这里。
 */
class TranslateBridge(
    private val scope: CoroutineScope,
    private val translator: Translator,
    private val onResult: (id: String, toLang: String, translations: List<String>) -> Unit,
    private val onStatus: (String) -> Unit = {},
    /** 页面推上来的状态快照 JSON（见 [TranslatePageSnapshot]）。 */
    private val onReport: (String) -> Unit = {},
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
     * 页面把「本页翻译状态」推上来：悬浮球与工具面板（原生 Compose）据此显示开关、
     * 进度与失败态。JSON 由 [TranslatePageSnapshot.parse] 按不可信输入解析。
     */
    @JavascriptInterface
    fun report(json: String) {
        runCatching { onReport(json) }
    }

    private fun parseTexts(payload: String): List<String> = runCatching {
        val arr = JSONArray(payload)
        (0 until arr.length()).map { arr.optString(it) }
    }.getOrDefault(emptyList())
}
