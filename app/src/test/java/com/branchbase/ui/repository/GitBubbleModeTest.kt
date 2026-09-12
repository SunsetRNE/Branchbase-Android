package com.branchbase.ui.repository

import com.branchbase.ui.profile.CommitMode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「Git 悬浮球只在本地 Git 模式下出现」单测。
 *
 * 需求：提交模式是「单文件 / 多文件」时，代码页 / 文件页右下角不该出现那枚蓝色 Git 球。
 * 它管的全是本地仓库的事（工作树改动数 / 领先落后 / 本地分支同步），
 * 而这两种模式直接调远端 API 提交，本地没有工作树 —— 球挂着只会挡正文，
 * 还会给出「本地仓库未拉取」这类与当前模式无关的动作；只有「本地仓库（Git）」模式才该有。
 *
 * 判定收进 `showGitBubble`（纯函数）：三个模式各钉一例；
 * 再加一条源码级钉子确认两个调用点（代码页 / 文件页）都真的用它门控。
 */
class GitBubbleModeTest {

    @Test
    fun `只有本地仓库模式显示 Git 悬浮球`() {
        assertTrue("本地仓库（Git）模式下必须出现", showGitBubble(CommitMode.LOCAL_REPO))
    }

    @Test
    fun `单文件与多文件模式都不显示`() {
        assertFalse("单文件模式走远端 API，没有本地工作树", showGitBubble(CommitMode.SINGLE_FILE))
        assertFalse("多文件模式走暂存区 + 远端 API，同样没有本地工作树", showGitBubble(CommitMode.MULTI_FILE))
    }

    @Test
    fun `未配置提交模式时不显示`() {
        // 未配置时提交会先弹「选择提交模式」；此时出现一个「本地仓库未拉取」的球只会误导
        assertFalse("未配置模式（null）时不该出现", showGitBubble(null))
    }

    @Test
    fun `两个调用点都必须按模式门控`() {
        // 代码页与文件页各有一枚 Git 球；两边都得门控，只改一边会出现
        // 「代码页没了、文件页还在」（用户报的正是这种不一致）
        val callSites = listOf(
            "src/main/java/com/branchbase/ui/repository/RepositoryScreen.kt" to "代码页",
            "src/main/java/com/branchbase/ui/repository/RepositoryFileViewer.kt" to "文件页",
        )
        val missing = callSites.filter { (path, _) ->
            val file = File(path)
            assertTrue("找不到源文件：${file.absolutePath}", file.exists())
            !Regex("showGitBubble\\(").containsMatchIn(file.readText())
        }
        assertEquals("这两个调用点都必须用 showGitBubble 门控：$missing", emptyList<Pair<String, String>>(), missing)
    }
}
