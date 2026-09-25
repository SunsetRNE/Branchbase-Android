package com.branchbase.ui.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 深链接的落点（`RepoDeepLink` / `initialRepoPage`）。
 *
 * 「设置 → 本地仓库」那一行点「进入」走的就是它：打开代码页 + 把 Git 面板展开到工作台。
 * 这条路上最容易漏的是 **tab 与面板两个字段的搭配** —— 只传 `openGitPanel` 而 `page` 留空，
 * 页面落在项目页而气泡只长在代码页上，用户看到的是「点了『进入』什么都没发生」。
 * 所以 `openGitPanel` **隐式带上代码页**，这条规则钉在这里。
 */
class RepoDeepLinkTest {

    @Test
    fun `默认落点还是项目页`() {
        assertEquals(RepoPage.Overview, initialRepoPage(openGitPanel = false, explicit = null))
    }

    @Test
    fun `openGitPanel 隐式带上代码页`() {
        assertEquals(RepoPage.Code, initialRepoPage(openGitPanel = true, explicit = null))
    }

    @Test
    fun `显式指定的 tab 优先`() {
        // 将来的入口可能要把面板和别的 tab 组合（例如从通知直达提交页再开面板）
        assertEquals(RepoPage.Issues, initialRepoPage(openGitPanel = true, explicit = RepoPage.Issues))
        assertEquals(RepoPage.Commits, initialRepoPage(openGitPanel = false, explicit = RepoPage.Commits))
    }

    @Test
    fun `深链接默认不开面板`() {
        // 通知 / 搜索结果这些入口进的是仓库首页，面板弹出来会挡住它们要看的正文
        assertFalse(RepoDeepLink("o", "r").openGitPanel)
        assertEquals(
            GitPanelStage.Collapsed,
            initialGitPanelStage(RepoDeepLink("o", "r").openGitPanel),
        )
    }
}
