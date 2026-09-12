package com.branchbase.ui.repository

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「文件页只读预览（查看态）跟随主题」的结构性钉子（源码级，套路同 `FileEditorWiringTest`）。
 *
 * 现场：只读预览的正文色是硬编码的 `Color(0xFF24292F)` —— 那是**浅色主题**的取值。
 * 深色模式下页面底色是 `CodeSyntax.CodeBg`（`#161B22`），深灰字压深色底，几乎读不出来；
 * 同一页的编辑态（`:editor` 的代码编辑器）与搜索页的代码块都是跟随主题的，只有这里不跟随。
 *
 * 颜色写错编译不报、单测不红，只有真机上肉眼能发现 —— 所以钉两条：
 * 1. 本页不得再出现这个硬编码色值；
 * 2. 预览正文必须用主题文字色（`Primer.TextPrimary`，与搜索页代码块同一约定）。
 */
class FileViewerThemeTest {

    private val viewerPath = "src/main/java/com/branchbase/ui/repository/RepositoryFileViewer.kt"

    private fun source(): String {
        val file = File(viewerPath)
        assertTrue("找不到源文件：${file.absolutePath}（单测工作目录应为 app 模块根）", file.exists())
        return file.readText()
    }

    @Test
    fun `只读预览正文不得硬编码浅色主题的文字色`() {
        assertTrue(
            "只读预览正文必须跟随主题，不能再出现硬编码的 0xFF24292F —— 深色模式下它就是「深灰字压深色底」",
            !source().contains("0xFF24292F"),
        )
    }

    @Test
    fun `只读预览正文使用主题文字色`() {
        // 预览正文那一行就是 `line.ifEmpty { " " }` 的 Text：它后面紧跟的 textStyle 里
        // 必须出现主题文字色（中间允许夹注释）。
        assertTrue(
            "只读预览正文必须用 Primer.TextPrimary（随三档主题切换，与搜索页代码块同一约定）",
            Regex("line\\.ifEmpty \\{ \" \" \\}[\\s\\S]{0,400}?color\\s*=\\s*Primer\\.TextPrimary")
                .containsMatchIn(source()),
        )
    }
}
