package com.branchbase.ui.repository

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「工作流日志块跟随主题 + 日志只有一条取数路径」的结构性钉子（源码级，套路同
 * [com.branchbase.ui.theme.ThemeContrastTest] / `FileViewerThemeTest`）。
 *
 * 现场两件事：
 * 1. 日志正文写死了 `Color(0xFF24292F)`、底色写死 `Color(0xFFF6F8FA)` —— 都是**浅色主题**
 *    的取值（`385df8f` 刚在文件页修掉同一类问题，工作流页漏了）。颜色写错编译不报、单测不红，
 *    只有真机深色下肉眼能发现，所以在这里钉住；
 * 2. 取作业日志以前在运行详情页与 Job 详情页**各写一遍**（各自的 URL、各自的失败判定、
 *    各自的分段）。现在收进 `:joblogs`，`:app` 只剩 `JobLogWiring.kt` 一处拼地址 ——
 *    这条也钉住，防止哪天又在页面里长出第二条。
 *
 * > 卡片流重绘后日志块集中到了 `JobLogScreen.kt`（运行详情页不再内嵌日志小窗），
 * > 所以「用主题色板」那一条改成查渲染日志的那一个文件；「地址只许一处」改成**扫整个 ui 目录**，
 * > 比原先写死两个文件名更抗新增文件。
 */
class WorkflowLogThemeTest {

    private val uiDir = File("src/main/java/com/branchbase/ui")
    private val wiringPath = "src/main/java/com/branchbase/ui/repository/JobLogWiring.kt"
    private val logScreenPath = "src/main/java/com/branchbase/ui/repository/JobLogScreen.kt"

    /** 工作流这条链路上参与渲染的源文件（重绘后跨了多个文件）。 */
    private val workflowSources = listOf(
        "src/main/java/com/branchbase/ui/repository/WorkflowRunDetailScreen.kt",
        "src/main/java/com/branchbase/ui/repository/RepositoryWorkflowScreens.kt",
        logScreenPath,
        "src/main/java/com/branchbase/ui/repository/JobCard.kt",
        "src/main/java/com/branchbase/ui/repository/RunHeaderCard.kt",
        "src/main/java/com/branchbase/ui/repository/WorkflowFormat.kt",
    )

    private fun source(path: String): String {
        val file = File(path)
        assertTrue("找不到源文件：${file.absolutePath}（单测工作目录应为 app 模块根）", file.exists())
        return file.readText()
    }

    @Test
    fun `日志相关页面不得再硬编码浅色主题的底色与正文色`() {
        workflowSources.forEach { path ->
            val src = source(path)
            assertTrue(
                "$path 必须跟随主题，不能再出现 0xFF24292F（深色下就是「深灰字压深色底」）",
                !src.contains("0xFF24292F"),
            )
            assertTrue(
                "$path 的日志块底色必须用 CodeSyntax.CodeBg，不能再出现 0xFFF6F8FA",
                !src.contains("0xFFF6F8FA"),
            )
        }
    }

    @Test
    fun `日志块用主题色板`() {
        // 渲染日志的那个文件：底色 CodeSyntax.CodeBg + 正文 Primer.TextPrimary
        val src = source(logScreenPath)
        assertTrue("日志页的日志底应为 CodeSyntax.CodeBg", src.contains("CodeSyntax.CodeBg"))
        assertTrue("日志页的日志正文应为 Primer.TextPrimary", src.contains("color = if (current) Primer.AccentText else Primer.TextPrimary"))
    }

    @Test
    fun `取日志地址只允许出现在接线层一处`() {
        // 注意不能简单禁掉 `/actions/jobs/`：取 steps 用的是同一个父路径。
        // 这里禁的是「父路径后面又跟 /logs」—— 那才是自己拼日志地址。
        // 必须落在**字符串字面量**里才算「自己拼地址」—— KDoc 里引用接口路径（`GET /actions/jobs/{id}/logs`）
        // 是文档，不是代码；用引号把它挡在外面。
        val selfBuiltLogUrl = Regex("\"[^\"]*actions/jobs/[^\"]{0,40}/logs\"")
        val offenders = uiDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.absolutePath != File(wiringPath).absolutePath }
            .filter { selfBuiltLogUrl.containsMatchIn(it.readText()) }
            .map { it.name }
            .toList()
        assertEquals(
            "作业日志地址只能出现在 JobLogWiring.kt（取数归 :joblogs，地址归接线层）：$offenders",
            emptyList<String>(),
            offenders,
        )
        assertTrue(
            "作业日志地址必须由 JobLogWiring.kt 注入（否则 :joblogs 拿不到数据源）",
            source(wiringPath).contains("/actions/jobs/\$jobId/logs"),
        )
    }
}
