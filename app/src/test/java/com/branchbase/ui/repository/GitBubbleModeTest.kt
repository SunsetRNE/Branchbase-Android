package com.branchbase.ui.repository

import com.branchbase.ui.profile.CommitMode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「Git 工具气泡球」两重门控单测：**提交模式** + **账号与仓库的关系**。
 *
 * ① 模式：提交模式是「单文件 / 多文件」时，代码页 / 文件页右下角不该出现那枚蓝色 Git 球。
 *    它管的全是本地仓库的事（工作树改动数 / 领先落后 / 本地分支同步），而这两种模式直接调
 *    远端 API 提交，本地没有工作树 —— 球挂着只会挡正文，还会给出「本地仓库未拉取」这类与
 *    当前模式无关的动作；只有「本地仓库（Git）」模式才该有。
 * ② 账号（2026-09-26 补充规则）：**非团队、非协同、非仓库管理员**身份的账号，在相应的仓库下
 *    不得显示这枚球。球里每一个动作（提交 / 撤销 / 上游 / 回退 / 分支 / 合并 / 本地分支同步）
 *    都要写权限，只读的外人点下去只会得到一句失败。
 *
 * 判定收进 `showGitBubble(mode, relation)`（纯函数）：两种模式 × 四种关系各钉一例；
 * 再加源码级钉子确认两个调用点（代码页 / 文件页）都真的用它门控，且文件页的关系态
 * 是**从宿主传进去的**（参数没有默认值 —— 少传一个就编不过）。
 */
class GitBubbleModeTest {

    @Test
    fun `本地仓库模式的账号仓库与协同账号显示 Git 悬浮球`() {
        assertTrue("账号仓库（仓库管理员）必须出现", showGitBubble(CommitMode.LOCAL_REPO, RepoRelation.OWN))
        assertTrue(
            "协作账号 / 团队授予写权限（permissions.push）必须出现",
            showGitBubble(CommitMode.LOCAL_REPO, RepoRelation.COLLABORATOR),
        )
    }

    @Test
    fun `非团队非协同非仓库管理员看不到球`() {
        assertFalse(
            "别人的公开仓库（只读外人）不该出现",
            showGitBubble(CommitMode.LOCAL_REPO, RepoRelation.FOREIGN),
        )
        assertFalse(
            "连读权限都没有的私有仓库更不该出现",
            showGitBubble(CommitMode.LOCAL_REPO, RepoRelation.NOT_COLLABORATOR),
        )
        assertFalse(
            "仓库信息还没到（relation 为 null）时保守不显示",
            showGitBubble(CommitMode.LOCAL_REPO, null),
        )
    }

    @Test
    fun `单文件与多文件模式都不显示`() {
        assertFalse("单文件模式走远端 API，没有本地工作树", showGitBubble(CommitMode.SINGLE_FILE, RepoRelation.OWN))
        assertFalse(
            "多文件模式走暂存区 + 远端 API，同样没有本地工作树",
            showGitBubble(CommitMode.MULTI_FILE, RepoRelation.COLLABORATOR),
        )
    }

    @Test
    fun `未配置提交模式时不显示`() {
        // 未配置时提交会先弹「选择提交模式」；此时出现一个「本地仓库未拉取」的球只会误导
        assertFalse("未配置模式（null）时不该出现", showGitBubble(null, RepoRelation.OWN))
    }

    @Test
    fun `两个调用点都按模式与账号门控`() {
        // 代码页与文件页各有一枚 Git 球；两边都得门控，只改一边会出现
        // 「代码页没了、文件页还在」（用户报的正是这种不一致）
        val codePage = sourceOf("src/main/java/com/branchbase/ui/repository/RepositoryScreen.kt")
        val filePage = sourceOf("src/main/java/com/branchbase/ui/repository/RepositoryFileViewer.kt")

        assertTrue(
            "代码页要用 showGitBubble(mode, gitBallRelation) 门控",
            codePage.contains("showGitBubble(mode, gitBallRelation)"),
        )
        assertTrue(
            "文件页要用 showGitBubble(effectiveMode, gitBallRelation) 门控",
            filePage.contains("showGitBubble(effectiveMode, gitBallRelation)"),
        )
        // 关系态由宿主算一次、写窗口传下去：文件页参数**没有默认值**，宿主漏传就编不过
        assertTrue(
            "宿主必须把关系态算出来（owner / permissions.push 口径）",
            codePage.contains("val gitBallRelation = repoRelationOf("),
        )
        assertTrue(
            "宿主必须把关系态传给文件页",
            codePage.contains("gitBallRelation = gitBallRelation,"),
        )
        assertTrue(
            "文件页的参数不能有默认值（漏传应当编不过，而不是静默藏球）",
            filePage.contains("gitBallRelation: RepoRelation,"),
        )
    }

    private fun sourceOf(relative: String): String {
        val file = File(relative)
        assertTrue("找不到源文件：${file.absolutePath}", file.exists())
        return file.readText()
    }

    @Test
    fun `判定函数本身只用模式与关系两个输入`() {
        // 反例保护：第 ① 条规则不该被别处改写（早前有实现把「未拉取」也算进来，球会时有时无）
        val source = sourceOf("src/main/java/com/branchbase/ui/repository/GitBubblePanel.kt")
        assertTrue(
            "判定实现必须就这两条：mode == LOCAL_REPO && relation?.canWrite == true",
            source.contains("mode == CommitMode.LOCAL_REPO && relation?.canWrite == true"),
        )
    }

    @Test
    fun `只有这两个调用点`() {
        // 第三个调用点意味着第三处门控（多半会漏）—— 找到就得一起改，这条钉子逼着改的人看见。
        // 注意代码页那一支前面还有 `page == RepoPage.Code &&`，所以按「调用形状」数、不按 `if (` 数。
        val calls = Regex("showGitBubble\\((mode|effectiveMode), gitBallRelation\\)")
        assertEquals(
            "代码页只应有一个 Git 球门控",
            1,
            calls.findAll(sourceOf("src/main/java/com/branchbase/ui/repository/RepositoryScreen.kt")).count(),
        )
        assertEquals(
            "文件页只应有一个 Git 球门控",
            1,
            calls.findAll(sourceOf("src/main/java/com/branchbase/ui/repository/RepositoryFileViewer.kt")).count(),
        )
    }
}
