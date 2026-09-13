package com.branchbase.ui.repository

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「工作流日志块跟随主题 + 日志只有一条取数路径」的结构性钉子（源码级，套路同
 * [FileViewerThemeTest]）。
 *
 * 现场两件事：
 * 1. 日志正文写死了 `Color(0xFF24292F)`、底色写死 `Color(0xFFF6F8FA)` —— 都是**浅色主题**
 *    的取值（`385df8f` 刚在文件页修掉同一类问题，工作流页漏了）。颜色写错编译不报、单测不红，
 *    只有真机深色下肉眼能发现，所以在这里钉住；
 * 2. 取作业日志以前在运行详情页与 Job 详情页**各写一遍**（各自的 URL、各自的失败判定、
 *    各自的分段）。现在收进 `:joblogs`，`:app` 只剩 `JobLogWiring.kt` 一处拼地址 ——
 *    这条也钉住，防止哪天又在页面里长出第二条。
 */
class WorkflowLogThemeTest {

    private val runDetailPath = "src/main/java/com/branchbase/ui/repository/WorkflowRunDetailScreen.kt"
    private val jobDetailPath = "src/main/java/com/branchbase/ui/repository/RepositoryWorkflowScreens.kt"
    private val wiringPath = "src/main/java/com/branchbase/ui/repository/JobLogWiring.kt"

    private fun source(path: String): String {
        val file = File(path)
        assertTrue("找不到源文件：${file.absolutePath}（单测工作目录应为 app 模块根）", file.exists())
        return file.readText()
    }

    @Test
    fun `日志块不得再硬编码浅色主题的底色与正文色`() {
        listOf(runDetailPath, jobDetailPath).forEach { path ->
            val src = source(path)
            assertTrue(
                "$path 的日志块必须跟随主题，不能再出现 0xFF24292F（深色下就是「深灰字压深色底」）",
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
        // 必须锚在日志块上：`color = Primer.TextPrimary` 在运行详情页里有九处，
        // 只断言「文件里出现过」等于没测（改回硬编码色也能通过）。
        // 两个文件的书写顺序相反，所以各用一条针对性正则：
        // 运行详情页是先底色后正文，Job 详情页是先正文后底色。
        val runDetail = source(runDetailPath)
        assertTrue(
            "$runDetailPath 的日志框必须「底色 CodeSyntax.CodeBg + 正文 Primer.TextPrimary」",
            Regex("\\.background\\(CodeSyntax\\.CodeBg\\)[\\s\\S]{0,600}?color = Primer\\.TextPrimary")
                .containsMatchIn(runDetail),
        )

        val jobDetail = source(jobDetailPath)
        assertTrue(
            "$jobDetailPath 的日志块必须「正文 Primer.TextPrimary + 底色 CodeSyntax.CodeBg」",
            Regex("color = Primer\\.TextPrimary[\\s\\S]{0,200}?background\\(CodeSyntax\\.CodeBg\\)")
                .containsMatchIn(jobDetail),
        )
    }

    @Test
    fun `取日志地址只允许出现在接线层一处`() {
        // 注意不能简单禁掉 `/actions/jobs/`：Job 详情页取 steps 用的是同一个父路径。
        // 这里禁的是「父路径后面又跟 /logs」—— 那才是自己拼日志地址。
        val selfBuiltLogUrl = Regex("actions/jobs/[\\s\\S]{0,40}?/logs")
        listOf(runDetailPath, jobDetailPath).forEach { path ->
            val src = source(path)
            assertTrue(
                "$path 不能再自己拼作业日志地址 —— 取数归 :joblogs，地址归 JobLogWiring.kt",
                !selfBuiltLogUrl.containsMatchIn(src),
            )
        }
        assertTrue(
            "作业日志地址必须由 JobLogWiring.kt 注入（否则 :joblogs 拿不到数据源）",
            source(wiringPath).contains("/actions/jobs/\$jobId/logs"),
        )
    }
}
