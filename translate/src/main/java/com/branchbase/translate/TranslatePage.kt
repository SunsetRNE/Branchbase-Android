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
 * | `01-core.js` | 配置、状态机、与原生侧的异步桥、批量队列 |
 * | `02-dom.js`  | 段落收集/过滤/跳过、译文插入、视口观察 |
 * | `03-ui.js`   | 浮动按钮、状态展示、显示模式切换 |
 * | `04-boot.js` | 启动：恢复上次开关、自动翻译、滚动兜底 |
 *
 * 拆分的收益不是「文件多好看」，而是：改判定规则只动 02、改按钮只动 03，
 * 且每个文件都能单独通读（合成一个 400 行文件之后没人会去读完它）。
 * 顺序是**显式契约**：01 建命名空间，02/03 挂方法，04 最后启动。
 */
object TranslatePage {

    /** 样式（译文卡片、浮动按钮、仅译文模式、额度提示）。 */
    const val CSS_ASSET = "translate/translate.css"

    /** 按序拼接的脚本清单（顺序即依赖顺序）。 */
    val JS_ASSETS: List<String> = listOf(
        "translate/01-core.js",
        "translate/02-dom.js",
        "translate/03-ui.js",
        "translate/04-boot.js",
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
