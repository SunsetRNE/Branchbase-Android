package com.branchbase.ui.auth

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「流程要点介绍页」三条布局决策的结构性钉子（2026-11-24）。
 *
 * Compose 运行时在 JVM 单测里没有，所以按仓库既有套路钉源码：
 *
 * 1. **决策一**：底部两个动作各占一半、平齐 —— 换登录在**左**、主操作在**右**。
 *    此前主按钮整宽、切换入口是整宽文字按钮，视线先落在「换一个」上。
 * 2. **决策二**：副标题与脚注（「适合：…」）不再渲染，但**资源保留**
 *    —— 文案是删是留等逐条评估结论，别被后人当成漏删。
 * 3. **决策三**：文档入口是 GitHub 图标按钮（有按压反馈），点开是就地弹层
 *    （`ModalBottomSheet`），不再是一行跳浏览器的文字链接。
 */
class LoginIntroLayoutTest {

    private val path = "src/main/java/com/branchbase/ui/auth/LoginScreens.kt"

    private fun source(path: String): String {
        val file = File(path)
        assertTrue("找不到源文件：${file.absolutePath}（单测工作目录应为 app 模块根）", file.exists())
        return file.readText()
    }

    /** 截出 `name(` 的完整调用段（按括号配对，允许跨行与嵌套）。 */
    private fun callBlocks(src: String, name: String): List<String> {
        val blocks = mutableListOf<String>()
        var from = 0
        while (true) {
            val at = src.indexOf("$name(", from)
            if (at < 0) break
            var depth = 0
            var i = at + name.length
            var closed = false
            while (i < src.length) {
                when (src[i]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) { i++; closed = true; break }
                    }
                }
                i++
            }
            if (!closed) break
            blocks += src.substring(at, i)
            from = i
        }
        return blocks
    }

    private fun scaffoldBody(): String {
        val src = source(path)
        val at = src.indexOf("private fun AuthIntroScaffold(")
        assertTrue("找不到 AuthIntroScaffold（被改名或搬走了？）", at >= 0)
        return src.substring(at).substringBefore("\nprivate fun FlowStepRow(")
    }

    @Test
    fun `底部两个动作各占一半且切换在左主操作在右`() {
        val body = scaffoldBody()
        val outline = callBlocks(body, "OutlineButton")
        val primary = callBlocks(body, "PrimaryButton")
        assertTrue("底部应恰好一个切换按钮（OutlineButton）", outline.size == 1)
        assertTrue("底部应恰好一个主操作（PrimaryButton）", primary.size == 1)
        assertTrue(
            "切换按钮必须与主操作各占一半（`Modifier.weight(1f)`）—— 尺寸不对等会把视线拽向「换一个」",
            outline.single().contains("Modifier.weight(1f)") &&
                primary.single().contains("Modifier.weight(1f)"),
        )
        assertTrue(
            "切换按钮必须排在主操作之前（左 = 换登录，右 = 主操作）",
            body.indexOf("OutlineButton(") < body.indexOf("PrimaryButton("),
        )
        assertTrue("主操作仍要带挂起态（busy）", primary.single().contains("busy = busy"))
    }

    @Test
    fun `副标题与脚注不再渲染但资源保留`() {
        val body = scaffoldBody()
        listOf("login_oauth_desc", "login_key_desc", "login_oauth_fit", "login_key_fit").forEach {
            assertTrue("介绍页不该再渲染 $it（2026-11-24 清爽化）", !body.contains(it))
        }
        val zh = source("src/main/res/values/strings.xml")
        listOf("login_oauth_desc", "login_key_desc", "login_oauth_fit", "login_key_fit").forEach {
            assertTrue("$it 的资源被删了 —— 当前只隐藏、不删除，删除要等文案评估结论", zh.contains("name=\"$it\""))
        }
    }

    @Test
    fun `文档入口是带按压反馈的 GitHub 图标且点开就地弹层`() {
        val src = source(path)
        assertTrue(
            "文档入口必须用 GitHub 标记矢量图（`R.drawable.ic_github_mark`）",
            src.contains("R.drawable.ic_github_mark"),
        )
        val entry = src.indexOf("private fun GithubDocEntry(")
        assertTrue("找不到 GithubDocEntry", entry >= 0)
        val entryBody = src.substring(entry).substringBefore("\n@Composable")
        assertTrue(
            "图标按钮必须有按压反馈（`rememberPressFeedback` 的形变 + 圆形水波纹）",
            entryBody.contains("rememberPressFeedback()") && entryBody.contains("press.scale.value"),
        )
        assertTrue(
            "旧的文字 DocLink 必须删掉 —— 留着一行蓝字就又会有人把它接回去",
            !src.contains("private fun DocLink("),
        )
        assertTrue(
            "点开必须是就地弹层（`ModalBottomSheet`），不是直接甩浏览器",
            src.contains("ModalBottomSheet("),
        )
    }
}
