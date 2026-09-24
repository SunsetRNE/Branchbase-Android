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
        // 规则表：这六个标签走「插到元素内部」（li/td/th 是结构合法性问题，
        // h1–h6 是排版归属问题 —— 见下一条测试）
        assertTrue(
            "li / td / th 必须在 INSERT_INSIDE 里",
            js.contains("var INSERT_INSIDE = { LI: 1, TD: 1, TH: 1, H1: 1, H2: 1, H3: 1, H4: 1, H5: 1, H6: 1 };"),
        )
        // 插入入口必须先判规则表
        assertTrue("insert() 要先判 INSERT_INSIDE", js.contains("if (INSERT_INSIDE[el.tagName])"))
        // 内部插入时原文要被包进包裹层（仅译文模式靠它只藏原文，而不是藏掉整个单元格）
        assertTrue("内部插入要包 .bb-tr-src 包裹层", js.contains("src.className = 'bb-tr-src'"))
        assertTrue("包裹层要能被清空时识别", js.contains("setAttribute('data-bb-wrap', '1')"))
    }

    /**
     * 标题的译文必须**插进标题内部**。
     *
     * 真机现象：h1「DeepSeek Harness」的译文被插成兄弟节点，落在 markdown 的
     * `h1 { border-bottom }` **下面**，看起来像「引用了下一段」的卡片，跟标题脱开了。
     * 顺带钉住「标题里用 `<span>`」：`<h1>` 只接受短语内容，塞 `<div>` 与往 `<ul>` 里塞
     * `<div>` 是同一类非法结构。
     */
    @Test
    fun `标题译文插进标题内部且用 span`() {
        val js = domScript()
        listOf("H1", "H2", "H3", "H4", "H5", "H6").forEach {
            assertTrue("$it 必须在 INSERT_INSIDE 里（否则译文落到标题下边框之外）", js.contains("$it: 1"))
        }
        assertTrue("标题里的译文要用 span（h1 只接受短语内容）", js.contains("/^H[1-6]$/.test(el.tagName)"))
        assertTrue("容器构造要支持 span 分支", js.contains("createElement(inline ? 'span' : 'div')"))
        assertTrue("兄弟节点的普通块仍然用 div", js.contains("makeTranslation(result, false)"))
    }

    /**
     * 匹配性译文（`data-bb-mode="match"`）的渲染规则。
     *
     * 判定引擎（1.0.89）只把中文段落里的外语片段送去翻译（`npm run dev`、`pull request`），
     * 回来的是**一组配对**而不是一段文本。DOM 里只有「原文片段 + 译文片段」两个 span，
     * 箭头与间隔号由 CSS 画（清空/重填都不必管分隔符）。
     */
    @Test
    fun `匹配性译文按片段配对渲染`() {
        val js = domScript()
        assertTrue("要能认出匹配性译文的载荷", js.contains("data-bb-mode"))
        assertTrue("配对容器", js.contains("'bb-tr-pair'"))
        assertTrue("原文片段", js.contains("'bb-tr-pair-src'"))
        assertTrue("译文片段", js.contains("'bb-tr-pair-dst'"))
        // 同一段重复翻译（换目标语言）走 fill() **覆盖**：只写 textContent 会把旧配对的
        // 子节点留在原地，页面上叠出两份译文
        assertTrue("重填要先清空旧内容", js.contains("el.textContent = ''"))
    }

    /**
     * 「仅译文」模式在匹配性译文上的表现：藏掉配对里的原文片段，只留译文。
     *
     * 这是「按使用规则展示」的落点之一 —— 对照模式给出 `原文 → 译文`，
     * 仅译文模式只给译文（原文块本来就被藏起来了，配对里再重复一遍没有意义）。
     */
    @Test
    fun `仅译文模式藏掉配对里的原文片段`() {
        val css = File("src/main/assets/translate/translate.css")
        assertTrue("找不到译文样式：${css.absolutePath}", css.exists())
        assertTrue(
            "仅译文模式必须只藏配对里的原文片段，而不是整对",
            css.readText().contains("""body.bb-tr-only .bb-tr[data-bb-mode="match"] .bb-tr-pair-src"""),
        )
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
