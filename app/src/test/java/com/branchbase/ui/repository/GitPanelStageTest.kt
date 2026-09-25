package com.branchbase.ui.repository

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `阶段 0 只有工作区档可用，其余档不得渲染成可点`() {
        assertTrue(GitPanelKind.Workspace.available)
        assertFalse("提交图按阶段 1 接入", GitPanelKind.Graph.available)
        assertFalse("引用树按阶段 2 接入", GitPanelKind.Refs.available)
        assertFalse("文件历史按阶段 4 接入", GitPanelKind.FileHistory.available)
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
}
