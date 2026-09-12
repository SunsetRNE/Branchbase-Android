package com.branchbase.translate

import android.content.Context

/**
 * 页面侧资产装载（CSS + 模块化脚本 + 设置注入）。
 *
 * ## 为什么脚本要内联而不是 `<script src>`
 *
 * 正文页用 `loadDataWithBaseURL` 加载，文档基准是 `https://github.com/...`（远端地址），
 * 相对路径的 `<script src="translate/01-core.js">` 会被解析成远端 URL 而取不到本地资源。
 * 因此脚本与 CSS 全部由这里读成字符串、由 `:app` 内联进 HTML。
 *
 * ## 脚本拆分与拼接顺序
 *
 * 页面脚本按「一个文件一层职责」拆开，装载时按固定顺序拼接成一个 `<script>`：
 *
 * | 文件 | 职责 |
 * |------|------|
 * | `01-core.js` | 配置、状态机、与原生侧的异步桥、批量队列、状态上报 |
 * | `02-dom.js`  | 段落收集/过滤/跳过、译文插入、视口观察、候选统计与清空 |
 * | `03-boot.js` | 启动与命令入口：开关、自动翻译、显示方式/样式/语言、滚动兜底 |
 *
 * 拆分的收益不是「文件多好看」，而是：改判定规则只动 02、改开关与命令只动 03，
 * 且每个文件都能单独通读。顺序是**显式契约**：01 建命名空间，02 挂 DOM，
 * 03 最后启动并挂上 `command()`。
 *
 * 悬浮球与工具面板**不在页面脚本里** —— 它们是原生 Compose 控件
 * （app 的 ui/translate/TranslateBubble.kt），页面只负责执行命令 + 上报状态。
 */
object TranslatePage {

    /** 样式（译文卡片与显示模式；悬浮控件由原生绘制）。 */
    const val CSS_ASSET = "translate/translate.css"

    /** 按序拼接的脚本清单（顺序即依赖顺序）。 */
    val JS_ASSETS: List<String> = listOf(
        "translate/01-core.js",
        "translate/02-dom.js",
        "translate/03-boot.js",
    )

    /** 装载结果：CSS / 合并后的 JS / 设置注入脚本。 */
    data class Assets(val css: String, val js: String, val configScript: String)

    fun load(context: Context, config: TranslateConfig, dark: Boolean = false): Assets = Assets(
        css = readAsset(context, CSS_ASSET),
        js = JS_ASSETS.joinToString(separator = "\n") { readAsset(context, it) },
        configScript = TranslateSettings.injectScript(config, dark),
    )

    /**
     * 读资产。**失败返回空串而不是抛异常**：翻译是增强功能，
     * 缺一个脚本文件最多「翻译按钮不出现」，绝不能让正文页整个加载失败。
     */
    private fun readAsset(context: Context, path: String): String =
        runCatching {
            context.assets.open(path).bufferedReader().use { it.readText() }
        }.getOrDefault("")
}
