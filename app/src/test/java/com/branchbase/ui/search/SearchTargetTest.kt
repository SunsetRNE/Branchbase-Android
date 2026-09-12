package com.branchbase.ui.search

import com.branchbase.ui.repository.RepoRelation
import com.branchbase.ui.repository.repoRelationOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索结果「跳转目标」的解析与仓库存取关系判定单测。
 *
 * 这两件事正是搜索页曾经的缺口：结果只有展示字段、没有跳转信息 → 卡片连点击都加不了
 * （「搜到了，点了一下，没反应」）。所以解析必须被钉住：不同搜索接口给目标信息的字段名不一样。
 */
class SearchTargetTest {

    @Test
    fun `仓库标识兼容 full_name 与 API 地址两种形态`() {
        assertEquals("octocat" to "hello-world", ownerRepoOf("octocat/hello-world"))
        assertEquals(
            "octocat" to "hello-world",
            ownerRepoOf("https://api.github.com/repos/octocat/hello-world"),
        )
        // issue 搜索给的是 repository_url（API 地址），可能带后续路径
        assertEquals(
            "rust-lang" to "rust",
            ownerRepoOf("https://api.github.com/repos/rust-lang/rust/issues/1"),
        )
        assertNull(ownerRepoOf(""))
        assertNull(ownerRepoOf("onlyone"))
        assertNull(ownerRepoOf("https://example.com/not-a-repo"))
    }

    @Test
    fun `站内目标映射到仓库深链接_用户与主题交给浏览器`() {
        val file = SearchTarget.File("a", "b", "src/Main.kt").toRepoDeepLink()
        assertEquals("a", file?.owner)
        assertEquals("b", file?.repo)
        assertEquals("src/Main.kt", file?.path)

        val issue = SearchTarget.Issue("a", "b", 42).toRepoDeepLink()
        assertEquals(42L, issue?.issueNumber)

        val pr = SearchTarget.PullRequest("a", "b", 7).toRepoDeepLink()
        assertEquals(7L, pr?.pullNumber)

        val commit = SearchTarget.Commit("a", "b", "deadbeef").toRepoDeepLink()
        assertEquals("deadbeef", commit?.commitSha)

        // 用户主页 / 主题页站内没有页面 → 只能外开
        assertNull(SearchTarget.Web("https://github.com/octocat").toRepoDeepLink())
    }

    @Test
    fun `主题结果拼出官方主题页地址`() {
        assertEquals("https://github.com/topics/android", topicWebUrl("android"))
        // 用户可能带 # 前缀（GitHub 的显示形态）
        assertEquals("https://github.com/topics/kotlin", topicWebUrl("#kotlin"))
    }

    @Test
    fun `仓库关系四档判定`() {
        // 账号仓库：owner 就是自己（大小写不敏感）
        assertEquals(RepoRelation.OWN, repoRelationOf("Octocat", "octocat", canPush = true))
        // 账号协作仓库：不是 owner 但有写权限
        assertEquals(RepoRelation.COLLABORATOR, repoRelationOf("rust-lang", "octocat", canPush = true))
        // 非账号仓库：别人的公开仓库（能看不能写）
        assertEquals(
            RepoRelation.FOREIGN,
            repoRelationOf("rust-lang", "octocat", canPush = false, canPull = true, isPrivate = false),
        )
        // 非自身协作仓库：私有 + 连读权限都没有（权限被撤销 / 未授权的私有仓库）
        assertEquals(
            RepoRelation.NOT_COLLABORATOR,
            repoRelationOf("secret-org", "octocat", canPush = false, canPull = false, isPrivate = true),
        )
        // 拿不到 owner 或未登录：不要瞎猜，按「非账号仓库」展示
        assertEquals(RepoRelation.FOREIGN, repoRelationOf(null, "octocat"))
        assertEquals(RepoRelation.FOREIGN, repoRelationOf("rust-lang", ""))
    }

    @Test
    fun `只有账号仓库与协作仓库算有写权限`() {
        assertTrue(RepoRelation.OWN.canWrite)
        assertTrue(RepoRelation.COLLABORATOR.canWrite)
        assertTrue(!RepoRelation.FOREIGN.canWrite)
        assertTrue(!RepoRelation.NOT_COLLABORATOR.canWrite)
    }
}
