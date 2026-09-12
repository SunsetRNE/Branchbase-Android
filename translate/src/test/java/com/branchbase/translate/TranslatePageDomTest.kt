package com.branchbase.translate

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页面脚本的**结构拼接回归钉子**。
 *
 * 背景（真实显示问题）：译文一开始一律插成原文的**兄弟节点**，可是
 * `<ul>/<ol>` 里只允许 `<li>`、`<tr>` 里只允许 `<td>/<th>` —— 把 `<div>` 插进去是
 * 非法 HTML，浏览器按匿名内容处理，表现为「列表的缩进/编号乱掉」「表格被撑开、列错位」。
 * 这类问题只在真机上看得见，所以这里把规则钉在脚本源码上：谁把 `li/td/th` 改回
 * 兄弟节点插入（或把 `th` 从候选中删掉、清空时忘了拆包裹层），单测立刻红。
 *
 * 断言用「源码里的规则」而不是跑 JS：模块里没有 JS 运行时，而这几条规则的**写法**
 * 本身就是契约（与 `ReadmeRenderAssetTest` 钉图片尺寸同一套路）。
 */
class TranslatePageDomTest {

    private fun domScript(): String {
        val file = File("src/main/assets/translate/02-dom.js")
        assertTrue("找不到页面脚本：${file.absolutePath}（单测工作目录应为 translate 模块根）", file.exists())
        return file.readText()
    }

    @Test
    fun `列表项与表格单元格必须内部插入`() {
        val js = domScript()
        // 规则表：这三个标签走「插到元素内部」
        assertTrue("li / td / th 必须在 INSERT_INSIDE 里", js.contains("var INSERT_INSIDE = { LI: 1, TD: 1, TH: 1 };"))
        // 插入入口必须先判规则表
        assertTrue("insert() 要先判 INSERT_INSIDE", js.contains("if (INSERT_INSIDE[el.tagName])"))
        // 内部插入时原文要被包进包裹层（仅译文模式靠它只藏原文，而不是藏掉整个单元格）
        assertTrue("内部插入要包 .bb-tr-src 包裹层", js.contains("src.className = 'bb-tr-src'"))
        assertTrue("包裹层要能被清空时识别", js.contains("setAttribute('data-bb-wrap', '1')"))
    }

    @Test
    fun `候选选择器包含表格表头`() {
        val js = domScript()
        assertTrue(
            "表格表头 th 也是正文，候选选择器里必须有它",
            js.contains("var SELECTOR = 'p, li, h1, h2, h3, h4, h5, h6, blockquote, td, th, dd';"),
        )
    }

    @Test
    fun `清空要拆掉包裹层并复位去重集合`() {
        val js = domScript()
        // 只 remove class 会留下一个空 span；必须把子节点搬回去再删
        assertTrue("清空要处理 [data-bb-wrap]", js.contains("document.querySelectorAll('[data-bb-wrap]')"))
        assertTrue("清空要搬回子节点", js.contains("while (w.firstChild) p.insertBefore(w.firstChild, w)"))
        assertTrue("清空要复位 seen（否则重扫会把旧段落当成已翻）", js.contains("state.seen = (typeof WeakSet"))
    }

    @Test
    fun `原文文本要排除内部译文`() {
        val js = domScript()
        // 内部插入后 innerText 会把译文算进去：候选统计会虚高、判定可能重复翻译
        assertTrue("要按子节点拼原文并跳过 .bb-tr", js.contains("function sourceText(el)"))
        assertTrue("sourceText 要跳过内部译文", js.contains("!== '.bb-tr'") || js.contains("contains('bb-tr')"))
        assertTrue("候选判定要用 sourceText", js.contains("IT.util.normalize(sourceText(el))"))
    }
}
