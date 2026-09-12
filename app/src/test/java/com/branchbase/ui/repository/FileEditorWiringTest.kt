package com.branchbase.ui.repository

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「文件页编辑态 = `:editor` 模块的代码编辑器」的结构性钉子（源码级，套路同 `BackConsumptionTest`：
 * 这些行为只有真机上肉眼可见，JVM 单测里没有 Compose / Android 运行时，只能把规则钉在源码上）。
 *
 * 背景：`:editor` 模块封装了 Sora Editor，关于页也留了「代码编辑器」的痕迹行，
 * 但组件 `BranchbaseCodeEditor` 在 App 里**一次都没被调用过** —— 文件页的编辑态用的还是
 * Material 的 `OutlinedTextField`：四周一圈方框、没有行号，观感比同一页的只读预览还差。
 *
 * 这类「模块写了却没人接」的缺陷编译不报、测试不红，只有真机肉眼能看出来，所以钉两条：
 *
 * 1. 文件页必须调用 `BranchbaseCodeEditor`（`:editor` 不是摆设）；
 * 2. 编辑态的**正文**不得回退到带方框的输入框（提交信息那种单行输入不受此限）。
 */
class FileEditorWiringTest {

    private val viewerPath = "src/main/java/com/branchbase/ui/repository/RepositoryFileViewer.kt"

    private fun source(path: String): String {
        val file = File(path)
        assertTrue("找不到源文件：${file.absolutePath}（单测工作目录应为 app 模块根）", file.exists())
        return file.readText()
    }

    /** 去掉 import 行后再匹配，避免把 `import androidx.compose.material3.OutlinedTextField` 算进去。 */
    private fun code(path: String): String =
        source(path).lines().filterNot { it.trimStart().startsWith("import") }.joinToString("\n")

    @Test
    fun `文件页编辑态必须用模块提供的代码编辑器`() {
        assertTrue(
            "文件页必须调用 :editor 的 BranchbaseCodeEditor —— 模块只有真被接上才不是摆设（带行号、无方框）",
            code(viewerPath).contains("BranchbaseCodeEditor("),
        )
    }

    @Test
    fun `编辑态的正文不得再用带方框的输入框`() {
        // 只允许一个 OutlinedTextField：提交信息。正文再出现一个就说明回退成了「一圈边框 + 没有行号」。
        val count = Regex("\\bOutlinedTextField\\s*\\(").findAll(code(viewerPath)).count()
        assertEquals(
            "文件页只剩「提交信息」一个 OutlinedTextField，正文必须走代码编辑器（当前数量：$count）",
            1,
            count,
        )
    }

    @Test
    fun `编辑器跟随三档主题与页面代码表面色`() {
        val text = code(viewerPath)
        // 深色判定必须用 App 的生效值（用户可在设置里锁定浅色 / 深色），
        // 用 isSystemInDarkTheme() 会在「锁浅色 + 系统深色」时让编辑器与页面不一致
        assertTrue(
            "编辑器必须用 LocalIsDarkTheme.current 判定明暗",
            Regex("darkTheme\\s*=\\s*LocalIsDarkTheme\\.current").containsMatchIn(text),
        )
        // 编辑器自带的默认底色（白 / #0D1117）与页面代码表面色（CodeSyntax.CodeBg）不同，
        // 不传就会在页面里贴出一块颜色不一样的砖，切「查看 ↔ 编辑」时肉眼可见
        assertTrue(
            "必须把页面代码表面色传给编辑器（backgroundColor = CodeSyntax.CodeBg…），否则编辑区与只读预览色差可见",
            Regex("backgroundColor\\s*=\\s*CodeSyntax\\.CodeBg").containsMatchIn(text),
        )
    }
}
