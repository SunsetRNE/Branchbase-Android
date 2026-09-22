package com.branchbase.ui.repository

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仓库页首帧快照单测（2026-09-22）。
 *
 * ## 它守的是什么
 *
 * 用户反馈「已经渲染过的，再次重复进入仓库的话，会有闪烁」。日志里那次重进的缓存**全命中**
 * （`L1 直出 ×8 / L1 命中 ×4`），可页面还是从零组合：`readmeHtml` / `languages` /
 * `contributors` / `repoInfo` 的初始值都是空的，要等一次挂起的缓存读回来才填上 ——
 * 于是每次都重放「正在加载自述文件… → 内容」+ 骨架 + README 从 1dp 撑到几万 dp。
 *
 * 快照把**已渲染过的那一份**留在进程内，重进时当帧就位。所以三条规则不能错：
 *
 * 1. **分块落地**：语言到了就存语言，不能要求「四块都到齐才存」——
 *    否则网络抖一下整份快照就没了，而它存在的意义正是「上次明明渲染出来过」；
 * 2. **按 owner/repo 隔离**：串了就是把另一个仓库的 README 画到这个仓库上；
 * 3. **有上限**：单条 README 正文几十~几百 KB，不能「用过的仓库全留着」。
 */
class RepoOverviewMemoryTest {

    private fun info(name: String) = RepoInfo(
        fullName = "SunsetRNE/$name",
        name = name,
        description = "",
        stars = 1,
        forks = 1,
        watchers = 1,
        license = null,
        defaultBranch = "main",
        ownerLogin = "SunsetRNE",
    )

    @Test
    fun `分块落地_先到的先存`() {
        val m = RepoOverviewMemory()
        m.putLanguages("SunsetRNE", "Branchbase-Android", listOf(LanguageStat("Kotlin", 1, 99.0)))

        val snap = m.get("SunsetRNE", "Branchbase-Android")
        assertEquals("语言单独到达也要留下", 1, snap?.languages?.size)
        assertNull("没拿到的块不该被编出来", snap?.readmeHtml)
        assertNull(snap?.info)
    }

    @Test
    fun `同名仓库不同 owner 不串`() {
        val m = RepoOverviewMemory()
        m.putReadme("SunsetRNE", "Branchbase-Android", "A")
        m.putReadme("deepseek-ai", "Branchbase-Android", "B")

        assertEquals("A", m.get("SunsetRNE", "Branchbase-Android")?.readmeHtml)
        assertEquals("B", m.get("deepseek-ai", "Branchbase-Android")?.readmeHtml)
        assertNull(m.get("other", "Branchbase-Android"))
    }

    @Test
    fun `后到的块覆盖前面的_不丢已经有的块`() {
        val m = RepoOverviewMemory()
        m.putLanguages("o", "r", listOf(LanguageStat("Kotlin", 1, 50.0)))
        m.putContributors("o", "r", listOf(Contributor("a", null, 3)))
        m.putReadme("o", "r", "<article/>")
        m.putInfo("o", "r", info("r"))
        m.putReadmeHeight("o", "r", 4200.dp)

        val snap = m.get("o", "r")
        assertEquals(1, snap?.languages?.size)
        assertEquals(1, snap?.contributors?.size)
        assertEquals("<article/>", snap?.readmeHtml)
        assertEquals("r", snap?.info?.name)
        assertEquals(4200.dp, snap?.readmeHeight)
    }

    /** 上限按条数卡死，淘汰最久未用的那个 —— 这条错的后果是堆悄悄涨上去。 */
    @Test
    fun `超过上限淘汰最久未用的`() {
        val m = RepoOverviewMemory(maxEntries = 2)
        m.putReadme("o", "first", "1")
        m.putReadme("o", "second", "2")
        // 摸一下 first，让 second 变成最久未用
        m.get("o", "first")
        m.putReadme("o", "third", "3")

        assertNull("最久未用的那个要被淘汰", m.get("o", "second"))
        assertEquals("1", m.get("o", "first")?.readmeHtml)
        assertEquals("3", m.get("o", "third")?.readmeHtml)
    }

    @Test
    fun `默认容量要小_它服务的是来回而不是历史`() {
        assertTrue("首帧快照是拿堆换观感的，容量必须是个位数：${RepoOverviewMemory.MAX}", RepoOverviewMemory.MAX <= 4)
    }
}
