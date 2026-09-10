package com.branchbase.ui.notification

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 通知类型短名单测。
 *
 * 存在的理由很具体：筛选栏四格各占 1/4 宽（约 79dp，去内边距剩 67dp），
 * 原名 `Pull Request` 在这个宽度下必然被省略成 `Pull Requ…`，
 * 而点开下拉菜单又能看到全名 —— 同一份数据两种文案，用户会怀疑自己点错了。
 */
class NotificationModelsTest {

    @Test
    fun `长名映射为短名`() {
        assertEquals("PR", typeShortName("PullRequest"))
        assertEquals("讨论", typeShortName("Discussion"))
        assertEquals("版本", typeShortName("Release"))
        assertEquals("提交", typeShortName("Commit"))
    }

    @Test
    fun `三种 CI 类型统一显示为工作流`() {
        assertEquals("工作流", typeShortName("CheckSuite"))
        assertEquals("工作流", typeShortName("CheckRun"))
        assertEquals("工作流", typeShortName("WorkflowRun"))
    }

    @Test
    fun `两种安全警报统一显示为安全`() {
        assertEquals("安全", typeShortName("RepositoryVulnerabilityAlert"))
        assertEquals("安全", typeShortName("RepositoryAdvisory"))
    }

    @Test
    fun `Issue 本身够短_保持原样`() {
        assertEquals("Issue", typeShortName("Issue"))
    }

    @Test
    fun `未知类型原样返回_不吞掉新类型`() {
        // 后端新增 subject.type 时筛选菜单仍要能列出它，不能被映射成空串或「其它」
        assertEquals("SomethingNew", typeShortName("SomethingNew"))
        assertEquals("", typeShortName(""))
    }
}
