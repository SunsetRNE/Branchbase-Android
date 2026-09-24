package com.branchbase.translate

import org.json.JSONArray
import org.json.JSONObject

/**
 * 正文页 → 原生的**状态快照**（页面脚本通过 `BBTranslate.report(json)` 推送）。
 *
 * ## 为什么快照由页面推、界面在原生
 *
 * 悬浮球与工具面板必须钉在**屏幕**右下角（导航栏上方），面板要能用满屏幕高度 ——
 * 而正文 WebView 的高度等于整篇内容高度（滚动在外层原生列表），页面里的
 * `position: fixed` 钉的是整篇文章、绝对定位又会被 WebView 的边界裁掉：
 * 正文比屏幕短时，面板就永远长不过正文。把界面搬到原生 Compose 层是唯一能做到
 * 「不受正文约束」的做法，代价就是页面要把它那份状态（开关 / 进度 / 熔断）推上来。
 *
 * ## 容错
 *
 * 页面是 GitHub 的 HTML（可能被第三方脚本影响），这份 JSON 一律**按不可信输入解析**：
 * 字段缺失或类型不对走默认值、状态字不认识回落 `idle`、数字夹到非负。
 * 解析失败返回 null，调用方保持上一次快照（宁可显示旧数据，也不要让界面崩掉）。
 */
data class TranslatePageSnapshot(
    /** 本页翻译是否开着（悬浮球只在开着时出现）。 */
    val on: Boolean = false,
    /** 页面视角的状态字：idle / translating / done / failed / paused / quota / auth。 */
    val status: String = STATUS_IDLE,
    /** 已插入的译文段数。 */
    val translated: Int = 0,
    /** 本页候选段落数 / 候选字符数（面板的进度与体量）。 */
    val candidates: Int = 0,
    val chars: Int = 0,
) {

    val busy: Boolean get() = status == STATUS_TRANSLATING

    /** 需要用户处理的失败态（Key 无效 / 额度用尽 / 连续失败暂停 / 一般失败）。 */
    val blocked: Boolean get() = status in BLOCKED_STATUSES

    companion object {
        const val STATUS_IDLE = "idle"
        const val STATUS_TRANSLATING = "translating"
        const val STATUS_DONE = "done"
        const val STATUS_FAILED = "failed"
        const val STATUS_PAUSED = "paused"
        const val STATUS_QUOTA = "quota"
        const val STATUS_AUTH = "auth"

        /** 页面脚本会出现的状态字（多一个都当成 idle，避免把界面带进未知态）。 */
        val STATUSES: Set<String> = setOf(
            STATUS_IDLE, STATUS_TRANSLATING, STATUS_DONE,
            STATUS_FAILED, STATUS_PAUSED, STATUS_QUOTA, STATUS_AUTH,
        )

        val BLOCKED_STATUSES: Set<String> = setOf(
            STATUS_FAILED, STATUS_PAUSED, STATUS_QUOTA, STATUS_AUTH,
        )

        /** 解析快照；JSON 坏了返回 null（调用方保留上一次的快照）。 */
        fun parse(json: String): TranslatePageSnapshot? = runCatching {
            val o = JSONObject(json)
            TranslatePageSnapshot(
                on = o.optBoolean("on", false),
                status = o.optString("status", STATUS_IDLE).takeIf { it in STATUSES } ?: STATUS_IDLE,
                translated = o.optInt("translated", 0).coerceAtLeast(0),
                candidates = o.optInt("candidates", 0).coerceAtLeast(0),
                chars = o.optInt("chars", 0).coerceAtLeast(0),
            )
        }.getOrNull()
    }
}

/**
 * 原生 → 页面的命令（工具面板上的开关与操作）。
 *
 * 全部走一条 `window.__bbIT.command(name, arg)`，参数用 `JSONObject.quote` 转义 ——
 * 不拼裸字符串，避免将来某个参数变成用户输入时出现 JS 注入。
 */
object TranslatePageCommands {
    /** 打开 / 关闭本页翻译（关闭后悬浮球整层消失，见 [TranslatePageSnapshot.on]）。 */
    const val ON = "on"
    const val OFF = "off"

    /** 显示方式（arg = `1` 对照 / `0` 仅译文）、译文样式（card / underline / plain）、目标语言（zh-CN / en）。 */
    const val DUAL = "dual"
    const val STYLE = "style"
    const val TARGET = "target"

    /** 清熔断重试 / 翻译当前视口 / 翻译全文 / 清空本页译文。 */
    const val RETRY = "retry"
    const val SCAN_VISIBLE = "scan-visible"
    const val SCAN_ALL = "scan-all"
    const val CLEAR = "clear"

    /** 生成可直接 evaluateJavascript 的一行（`window.__bbIT` 缺失时静默跳过）。 */
    fun js(command: String, arg: String = ""): String =
        "window.__bbIT && window.__bbIT.command(${JSONObject.quote(command)}, ${JSONObject.quote(arg)})"
}

/**
 * 一批译文 → 注入页面的 JSON 数组（`window.__bbTranslated(id, to, json)` 的第三个参数）。
 *
 * ## 为什么元素不是「一串字符串」
 *
 * 判定引擎（1.0.89）之后，一段的结果有三种形态：什么都不做、整段译文、**只翻了段内部分片段**。
 * 前两种能塞进一个字符串（空串 = 不做），第三种塞不进去（它是一组「片段 → 译文」），
 * 于是元素变成**混合类型**，由这份编码器统一产出、页面脚本按同一约定解析：
 *
 * | 元素 | 含义 | 页面行为 |
 * |------|------|----------|
 * | `""` | 判定为不需要翻（本身就是目标文字 / 译后一致） | 什么都不做，**不计失败** |
 * | `"译文"` | 整段译文 | 插一张译文块（旧形态，未变） |
 * | `{"mode":"match","parts":[{"s":…,"t":…}]}` | 匹配性译文 | 插一组片段配对 |
 * | `null` | 这一段翻译失败 | 计一次失败（页面据此判断服务不可用） |
 *
 * `""` 与 `null` 必须分开：页面侧靠「一批里一段都没插进去」判定服务不可用，
 * 而**判定跳过**也会「一段都没插进去」—— 混在一起时，一页全是中文的正文会被误报成翻译失败。
 *
 * 用 `org.json` 而不是拼字符串：译文里出现引号、换行、emoji 是常态，
 * 手拼的坏法（JSON 里多一个裸引号）是整个批次静默丢失。
 */
object TranslatePagePayload {

    fun encode(results: List<ParagraphTranslation>): String {
        val arr = JSONArray()
        for (r in results) {
            when (r) {
                is ParagraphTranslation.None -> arr.put("")
                is ParagraphTranslation.Failed -> arr.put(JSONObject.NULL)
                is ParagraphTranslation.Whole -> arr.put(r.text)
                is ParagraphTranslation.Matched -> arr.put(
                    JSONObject()
                        .put("mode", MODE_MATCH)
                        .put(
                            "parts",
                            JSONArray().apply {
                                r.parts.forEach { p ->
                                    put(JSONObject().put("s", p.source).put("t", p.translated))
                                }
                            },
                        ),
                )
            }
        }
        return arr.toString()
    }

    /** 匹配性译文的模式标记（页面脚本 `01-core.js` / `02-dom.js` 按它分派渲染）。 */
    const val MODE_MATCH = "match"
}
