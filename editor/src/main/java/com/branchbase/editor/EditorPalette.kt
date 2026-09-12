package com.branchbase.editor

import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/**
 * 编辑器配色（**纯数据 + 纯映射**，不依赖 Android 运行时）。
 *
 * ## 为什么要有这一层
 *
 * Sora 的默认色板里 `TEXT_NORMAL = #FF333333` —— 它**不区分深色 / 浅色**：
 * - 浅色模式下正文是「深灰」而不是纯黑，对比度不够，观感就是「灰蒙蒙」；
 * - 深色模式下背景已经是深色，`#333333` 的字几乎看不见（同样被判成「发灰」）。
 *
 * 配色一旦写错（浅色给白字、深色给黑字）**只有真机上肉眼才能发现**，编译与常规测试都拦不住。
 * 所以把「选哪套颜色」抽成这里的纯数据 + 纯映射：单测直接断言「哪些 id 必须纯黑/纯白」，
 * [BranchbaseCodeEditor] 只负责把值塞进 Sora（见 [assignments]）。
 *
 * ## 为什么这次连「只有只读预览才露出来」的颜色也一起覆盖
 *
 * 上一版只覆盖了正文 / 背景 / 行号等十来项，其余几十项仍走库默认 —— 而库默认里有不少是
 * **透明或灰**，它们恰恰在「只读预览」这种用法下最容易被看到：
 *
 * | 颜色 | 库默认 | 预览态怎么露出来 |
 * |------|--------|-----------------|
 * | `TEXT_SELECTED` | 透明 | 预览里选中一段文字复制 / 引用，字直接消失 |
 * | `HIGHLIGHTED_DELIMITERS_FOREGROUND` | 半透明黑 | 深色模式下括号配对提示看不见 |
 * | `STRIKETHROUGH` | 透明 | 删除线不显示 |
 * | `DIAGNOSTIC_TOOLTIP_BRIEF_MSG` | 浅色下 `#424242`（灰） | 诊断提示文字发灰 |
 * | `LINE_NUMBER_PANEL_TEXT` | 深色下变深色 | 行号面板文字与底色糊在一起 |
 *
 * 因此现在的契约是：**除语法高亮令牌外，所有前景文字一律纯黑 / 纯白**
 * （[PureTextIds] 是这份契约的机器可读清单，单测逐项断言）。行号也算正文 ——
 * 只读预览里它就是内容的一部分，此前给的是 `#6E7781` / `#8B949E`，同样是「发灰」的来源。
 *
 * 语法令牌（关键字 / 注释 / 字符串 / 运算符 / 函数名）保留主题色：
 * 注释天然偏暗是**语法语义**（不是渲染缺陷），把它刷成纯黑会让高亮失去意义。
 * 颜色取 GitHub Primer 的浅 / 深两套（与 App 主题同源），编辑器不与应用「两种风格」。
 */
internal data class EditorPalette(
    // ── 正文类：必须纯黑（浅色）/ 纯白（深色），见 [PureTextIds] ──
    /** 普通正文（本次问题的核心）。 */
    val textNormal: Int,
    /** 选中文字的前景色（库默认透明，预览里选中即「字消失」）。 */
    val textSelected: Int,
    /** 行号文字。 */
    val lineNumber: Int,
    /** 当前行行号。 */
    val lineNumberCurrent: Int,
    /** 行号面板文字（长按行号弹出的那个面板）。 */
    val lineNumberPanelText: Int,
    /** 制表符 / 空格等不可打印字符的提示色。 */
    val nonPrintableChar: Int,
    /** 删除线。 */
    val strikethrough: Int,
    /** 括号配对等「高亮分隔符」的前景色。 */
    val delimitersForeground: Int,
    /** 内联提示（inlay hint，如参数名）文字。 */
    val inlayHintForeground: Int,
    /** 补全窗主文字。 */
    val completionPrimary: Int,
    /** 函数签名提示文字。 */
    val signatureText: Int,
    /** 诊断提示（简短 / 详细）。 */
    val tooltipBrief: Int,
    val tooltipDetailed: Int,

    // ── 表面（背景 / 高亮）──
    /** 整块画布底色。 */
    val background: Int,
    /** 行号栏底色（与画布同色，避免出现一条色带）。 */
    val lineNumberBackground: Int,
    /** 当前行高亮。 */
    val currentLine: Int,
    /** 选中文字的背景。 */
    val selectionBackground: Int,
    /** 搜索 / 匹配命中高亮。 */
    val matchedTextBackground: Int,
    val divider: Int,
    val scrollBar: Int,

    // ── 语法令牌（保留主题色，注释偏暗是语义而非缺陷）──
    val keyword: Int,
    val comment: Int,
    val literal: Int,
    val operator: Int,
    val functionName: Int,
    /** 补全窗次要文字（类型 / 说明），允许比主文字暗一档。 */
    val completionSecondary: Int,
) {

    /**
     * 全部「颜色 id → 值」的落点。
     *
     * `EditorColorScheme.XXX` 都是 Java 编译期常量，Kotlin 会**内联成整数** ——
     * 因此这个函数在 JVM 单测里可以直接断言，不需要 Android 运行时（见 `EditorPaletteTest`）。
     */
    fun assignments(): List<Pair<Int, Int>> = listOf(
        EditorColorScheme.TEXT_NORMAL to textNormal,
        EditorColorScheme.TEXT_SELECTED to textSelected,
        EditorColorScheme.LINE_NUMBER to lineNumber,
        EditorColorScheme.LINE_NUMBER_CURRENT to lineNumberCurrent,
        EditorColorScheme.LINE_NUMBER_PANEL_TEXT to lineNumberPanelText,
        EditorColorScheme.NON_PRINTABLE_CHAR to nonPrintableChar,
        EditorColorScheme.STRIKETHROUGH to strikethrough,
        EditorColorScheme.HIGHLIGHTED_DELIMITERS_FOREGROUND to delimitersForeground,
        EditorColorScheme.TEXT_INLAY_HINT_FOREGROUND to inlayHintForeground,
        EditorColorScheme.COMPLETION_WND_TEXT_PRIMARY to completionPrimary,
        EditorColorScheme.SIGNATURE_TEXT_NORMAL to signatureText,
        EditorColorScheme.DIAGNOSTIC_TOOLTIP_BRIEF_MSG to tooltipBrief,
        EditorColorScheme.DIAGNOSTIC_TOOLTIP_DETAILED_MSG to tooltipDetailed,
        EditorColorScheme.WHOLE_BACKGROUND to background,
        EditorColorScheme.LINE_NUMBER_BACKGROUND to lineNumberBackground,
        EditorColorScheme.CURRENT_LINE to currentLine,
        EditorColorScheme.SELECTED_TEXT_BACKGROUND to selectionBackground,
        EditorColorScheme.MATCHED_TEXT_BACKGROUND to matchedTextBackground,
        EditorColorScheme.LINE_DIVIDER to divider,
        EditorColorScheme.SCROLL_BAR_THUMB to scrollBar,
        EditorColorScheme.KEYWORD to keyword,
        EditorColorScheme.COMMENT to comment,
        EditorColorScheme.LITERAL to literal,
        EditorColorScheme.OPERATOR to operator,
        EditorColorScheme.FUNCTION_NAME to functionName,
        EditorColorScheme.COMPLETION_WND_TEXT_SECONDARY to completionSecondary,
    )

    companion object {

        /**
         * 「必须纯黑 / 纯白」的颜色 id 清单（正文类前景）。
         *
         * 这是**对外契约**：少一项，那一项就会退回 Sora 默认的灰 / 透明，
         * 而它多半只在只读预览态才会露出来 —— 单测按这份清单逐项断言（浅色 = 纯黑、深色 = 纯白）。
         */
        val PureTextIds: List<Int> = listOf(
            EditorColorScheme.TEXT_NORMAL,
            EditorColorScheme.TEXT_SELECTED,
            EditorColorScheme.LINE_NUMBER,
            EditorColorScheme.LINE_NUMBER_CURRENT,
            EditorColorScheme.LINE_NUMBER_PANEL_TEXT,
            EditorColorScheme.NON_PRINTABLE_CHAR,
            EditorColorScheme.STRIKETHROUGH,
            EditorColorScheme.HIGHLIGHTED_DELIMITERS_FOREGROUND,
            EditorColorScheme.TEXT_INLAY_HINT_FOREGROUND,
            EditorColorScheme.COMPLETION_WND_TEXT_PRIMARY,
            EditorColorScheme.SIGNATURE_TEXT_NORMAL,
            EditorColorScheme.DIAGNOSTIC_TOOLTIP_BRIEF_MSG,
            EditorColorScheme.DIAGNOSTIC_TOOLTIP_DETAILED_MSG,
        )

        /** 纯黑（浅色模式正文类的值）。 */
        const val PURE_BLACK: Int = 0xFF000000.toInt()

        /** 纯白（深色模式正文类的值）。 */
        const val PURE_WHITE: Int = 0xFFFFFFFF.toInt()

        /** 浅色：正文**纯黑**、底**纯白**。 */
        val Light: EditorPalette = EditorPalette(
            textNormal = PURE_BLACK,
            textSelected = PURE_BLACK,
            lineNumber = PURE_BLACK,
            lineNumberCurrent = PURE_BLACK,
            lineNumberPanelText = PURE_BLACK,
            nonPrintableChar = PURE_BLACK,
            strikethrough = PURE_BLACK,
            delimitersForeground = PURE_BLACK,
            inlayHintForeground = PURE_BLACK,
            completionPrimary = PURE_BLACK,
            signatureText = PURE_BLACK,
            tooltipBrief = PURE_BLACK,
            tooltipDetailed = PURE_BLACK,
            background = 0xFFFFFFFF.toInt(),
            lineNumberBackground = 0xFFFFFFFF.toInt(),
            currentLine = 0x0D000000,
            selectionBackground = 0x330969DA,
            matchedTextBackground = 0x33FFD33D,
            divider = 0xFFD0D7DE.toInt(),
            scrollBar = 0x66808080,
            keyword = 0xFFCF222E.toInt(),
            comment = 0xFF6E7781.toInt(),
            literal = 0xFF0A3069.toInt(),
            operator = 0xFF0550AE.toInt(),
            functionName = 0xFF8250DF.toInt(),
            completionSecondary = 0xFF57606A.toInt(),
        )

        /** 深色：正文**纯白**、底近黑（Primer `canvas.default`）。 */
        val Dark: EditorPalette = EditorPalette(
            textNormal = PURE_WHITE,
            textSelected = PURE_WHITE,
            lineNumber = PURE_WHITE,
            lineNumberCurrent = PURE_WHITE,
            lineNumberPanelText = PURE_WHITE,
            nonPrintableChar = PURE_WHITE,
            strikethrough = PURE_WHITE,
            delimitersForeground = PURE_WHITE,
            inlayHintForeground = PURE_WHITE,
            completionPrimary = PURE_WHITE,
            signatureText = PURE_WHITE,
            tooltipBrief = PURE_WHITE,
            tooltipDetailed = PURE_WHITE,
            background = 0xFF0D1117.toInt(),
            lineNumberBackground = 0xFF0D1117.toInt(),
            currentLine = 0x14FFFFFF,
            selectionBackground = 0x4D58A6FF,
            matchedTextBackground = 0x4DFFD33D,
            divider = 0xFF30363D.toInt(),
            scrollBar = 0x668B949E,
            keyword = 0xFFFF7B72.toInt(),
            comment = 0xFF8B949E.toInt(),
            literal = 0xFFA5D6FF.toInt(),
            operator = 0xFF79C0FF.toInt(),
            functionName = 0xFFD2A8FF.toInt(),
            completionSecondary = 0xFF8B949E.toInt(),
        )

        fun forDark(dark: Boolean): EditorPalette = if (dark) Dark else Light
    }
}
