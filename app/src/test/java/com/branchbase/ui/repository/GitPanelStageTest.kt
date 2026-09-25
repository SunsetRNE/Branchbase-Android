package com.branchbase.ui.repository

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Git 气泡面板三档的钉子（`GitPanelStage`）。
 *
 * ## 为什么这些纯函数值得单独钉
 *
 * 1. **返回键的层序**：L2（视图）→ L1（动作列表）→ 收起 → 页面。写错一档的表现是
 *    「在视图里按返回，整个面板一下子没了」或者「按返回没反应」——只有真机连按才试得出来；
 * 2. **动效方向**：方向只由层级差决定，写反了动画照播、方向却是反的（用户说不出哪里别扭）；
 * 3. **未落地的档不许装成能用**：`available = false` 的档若被渲染成可点，就是「点了没反应」的假入口。
 *    反过来的那次事故也记在这里：阶段 1 把「提交图」接上了，`available` 却留在 `false` ——
 *    标签条一直标「待接入」，点进去却是一张能用的图。所以这条现在有两个方向的钉子：
 *    一条查枚举值，一条**对着宿主源码**查「渲染了却没标可用」。
 *
 * 外加一条**源码级钉子**：两个宿主（代码页 / 文件页）必须都用 `panelBack` 逐档退，
 * 只改一边会出现「代码页正常、文件页一按返回面板全没」——与 `GitBubbleModeTest` 同一手法。
 */
class GitPanelStageTest {

    @Test
    fun `层级：折叠 0 动作 1 视图 2`() {
        assertEquals(0, panelDepth(GitPanelStage.Collapsed))
        assertEquals(1, panelDepth(GitPanelStage.Actions))
        assertEquals(2, panelDepth(GitPanelStage.View(GitPanelKind.Workspace)))
    }

    @Test
    fun `返回逐档退：视图到动作列表，动作列表到收起`() {
        assertEquals(
            GitPanelStage.Actions,
            panelBack(GitPanelStage.View(GitPanelKind.Workspace)),
        )
        assertEquals(GitPanelStage.Collapsed, panelBack(GitPanelStage.Actions))
    }

    @Test
    fun `已收起时再按返回不动`() {
        // 否则「收起状态下按返回」会把面板重新弹开，比没反应更糟
        assertEquals(GitPanelStage.Collapsed, panelBack(GitPanelStage.Collapsed))
    }

    @Test
    fun `方向：进档 +1 退档 -1 同级 0`() {
        assertEquals(1, panelDirection(GitPanelStage.Actions, GitPanelStage.View(GitPanelKind.Workspace)))
        assertEquals(-1, panelDirection(GitPanelStage.View(GitPanelKind.Workspace), GitPanelStage.Actions))
        assertEquals(0, panelDirection(GitPanelStage.Actions, GitPanelStage.Actions))
    }

    @Test
    fun `同级切换（两档视图之间）不位移`() {
        assertEquals(
            0,
            panelDirection(
                GitPanelStage.View(GitPanelKind.Workspace),
                GitPanelStage.View(GitPanelKind.Graph),
            ),
        )
    }

    @Test
    fun `已落地的档才标可用：四个档全部落地`() {
        // 这条钉子的前身写的是「阶段 0 只有工作区档可用」—— 阶段 1 把「提交图」接上之后
        // 没人来改它，于是它一直替那个**错的**状态站岗：标签条标着「待接入」、
        // 点进去却是一张能用的图。现在改成按「真的接上了没有」表态（见下一条源码级钉子）。
        assertTrue("工作区：阶段 0", GitPanelKind.Workspace.available)
        assertTrue("提交图：阶段 1", GitPanelKind.Graph.available)
        assertTrue("引用树：阶段 2", GitPanelKind.Refs.available)
        assertTrue("文件历史：阶段 4（1.0.100）", GitPanelKind.FileHistory.available)
        // 「待接入」这条通路现在没有用户了（四个档都落地），但机制留着：
        // 下一个档（提交详情 / 管理页之类）照样靠它标「待接入」而不是装死
        assertEquals(
            "没有任何档是待接入了 —— 标签条上那句「待接入」目前在界面上不会出现",
            0,
            GitPanelKind.entries.count { !it.available },
        )
    }

    @Test
    fun `宿主真的渲染的档不许标成待接入`() {
        // 源码级钉子：`available` 与「宿主里有没有那个分支」是同一件事的两处写法，
        // 分开维护过一次就漏了（阶段 1 的提交图）。这里直接对着宿主源码查一遍。
        val host = functionBody(
            source("src/main/java/com/branchbase/ui/repository/GitWorkspacePanel.kt"),
            "fun GitPanelViewHost(",
        )
        val rendered = Regex("""GitPanelKind\.(\w+)\s*->""").findAll(host)
            .map { it.groupValues[1] }
            .toSet()
        assertTrue("没在宿主里找到任何已渲染的档，正则或函数签名变了", rendered.isNotEmpty())
        val stale = GitPanelKind.entries.filter { it.name in rendered && !it.available }
        assertEquals(
            "宿主已经渲染这些档，available 却还是 false —— 标签条会一直标「待接入」：$stale",
            emptyList<GitPanelKind>(),
            stale,
        )
    }

    @Test
    fun `深链接带 openGitPanel 时直接落在视图档`() {
        // 设置 → 本地仓库 的「进入」：落点是工作台，不是动作列表（少点一次）
        assertEquals(
            GitPanelStage.View(GitPanelKind.Workspace),
            initialGitPanelStage(openGitPanel = true),
        )
        assertEquals(GitPanelStage.Collapsed, initialGitPanelStage(openGitPanel = false))
    }

    @Test
    fun `两个宿主都用 panelBack 逐档退`() {
        val hosts = listOf(
            "src/main/java/com/branchbase/ui/repository/RepositoryScreen.kt" to "代码页",
            "src/main/java/com/branchbase/ui/repository/RepositoryFileViewer.kt" to "文件页",
        )
        val missing = hosts.filter { (path, _) ->
            val f = File(path)
            assertTrue("找不到源文件：${f.absolutePath}", f.exists())
            !f.readText().contains("panelBack(")
        }
        assertEquals("这两个宿主都必须用 panelBack 逐档退：$missing", emptyList<Pair<String, String>>(), missing)
    }

    private fun source(relative: String): String {
        val f = File(relative)
        assertTrue("找不到源文件：${f.absolutePath}", f.exists())
        return f.readText()
    }

    /**
     * 顶层函数的正文（从签名到下一个行首 `}`）。
     *
     * 源码级钉子必须**限定在目标函数里**：同一个文件里 `gitPanelKindLabel` 的 `when` 穷尽了
     * 四个档（那是取名字，不代表已经渲染），整文件扫会把「文件历史」也算成已渲染。
     */
    private fun functionBody(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("找不到函数：$signature", start >= 0)
        val end = src.indexOf("\n}\n", start)
        return if (end > start) src.substring(start, end) else src.substring(start)
    }
}
