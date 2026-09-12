package com.branchbase.editor

/**
 * 编辑器配色（**纯数据，不依赖 Sora 的任何类型**）。
 *
 * ## 为什么要有这一层
 *
 * Sora 的默认色板里 `TEXT_NORMAL = #FF333333` —— 它**不区分深色 / 浅色**：
 * - 浅色模式下正文是「深灰」而不是纯黑，对比度不够，观感就是「灰蒙蒙」；
 * - 深色模式下背景已经是深色，`#333333` 的字几乎看不见（同样被判成「发灰」）。
 *
 * 配色一旦写错（浅色给白字、深色给黑字）**只有真机上肉眼才能发现**，编译与常规测试都拦不住。
 * 所以把「选哪套颜色」抽成这里的纯数据 + 纯函数：单测就能把
 * 「浅色 = 深黑、深色 = 亮白」这条契约钉死，[BranchbaseCodeEditor] 只负责把值塞进 Sora。
 *
 * 颜色取 GitHub Primer 的浅/深两套（与 App 主题同源），保证编辑器不与应用「两种风格」。
 */
internal data class EditorPalette(
    /** 正文颜色：浅色 = 纯黑，深色 = 纯白（本次修复的核心）。 */
    val textNormal: Int,
    /** 整块画布底色。 */
    val background: Int,
    /** 行号文字。 */
    val lineNumber: Int,
    /** 行号栏底色（与画布同色，避免出现一条色带）。 */
    val lineNumberBackground: Int,
    /** 当前行高亮。 */
    val currentLine: Int,
    /** 选中文字的背景。 */
    val selectionBackground: Int,
    val divider: Int,
    val scrollBar: Int,
    val keyword: Int,
    val comment: Int,
    val literal: Int,
    val operator: Int,
    val functionName: Int,
) {
    companion object {

        /** 浅色：正文**纯黑**（0xFF000000）、底**纯白**。 */
        val Light: EditorPalette = EditorPalette(
            textNormal = 0xFF000000.toInt(),
            background = 0xFFFFFFFF.toInt(),
            lineNumber = 0xFF6E7781.toInt(),
            lineNumberBackground = 0xFFFFFFFF.toInt(),
            currentLine = 0x0D000000,
            selectionBackground = 0x330969DA,
            divider = 0xFFD0D7DE.toInt(),
            scrollBar = 0x66808080,
            keyword = 0xFFCF222E.toInt(),
            comment = 0xFF6E7781.toInt(),
            literal = 0xFF0A3069.toInt(),
            operator = 0xFF0550AE.toInt(),
            functionName = 0xFF8250DF.toInt(),
        )

        /** 深色：正文**纯白**（0xFFFFFFFF）、底近黑（Primer `canvas.default`）。 */
        val Dark: EditorPalette = EditorPalette(
            textNormal = 0xFFFFFFFF.toInt(),
            background = 0xFF0D1117.toInt(),
            lineNumber = 0xFF8B949E.toInt(),
            lineNumberBackground = 0xFF0D1117.toInt(),
            currentLine = 0x14FFFFFF,
            selectionBackground = 0x4D58A6FF,
            divider = 0xFF30363D.toInt(),
            scrollBar = 0x668B949E,
            keyword = 0xFFFF7B72.toInt(),
            comment = 0xFF8B949E.toInt(),
            literal = 0xFFA5D6FF.toInt(),
            operator = 0xFF79C0FF.toInt(),
            functionName = 0xFFD2A8FF.toInt(),
        )

        fun forDark(dark: Boolean): EditorPalette = if (dark) Dark else Light
    }
}
