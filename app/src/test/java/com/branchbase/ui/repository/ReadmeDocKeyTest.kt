package com.branchbase.ui.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自述文件「文档指纹」单测（2026-09-22）。
 *
 * ## 它守的是什么
 *
 * 自述文件是 LazyColumn 里一项 4~6 万 dp 高的 item：整页滚到底再往回滚时，这一项会被回收，
 * 旧的 `DisposableEffect` 会 `destroy()` 掉 WebView，重新组合时又要重新 `loadDataWithBaseURL`
 * —— 用户看到的是「自述文件重新加载」，而且还跳回整篇描述的最顶部（1dp → 4 万 dp 的锚点错位）。
 * 修法是让 WebView 活过回收（[ReadmeViewHolder]），靠**指纹**判断「上面已经是这一篇」。
 *
 * ## 为什么这一条必须有测试
 *
 * 普通的缓存键漏一个维度只是命中率低；**这里的键漏一个维度是把另一篇文档当成这一篇**：
 * 用户看到旧内容，而且只要页面不再重建就永远不刷新。方向相反，所以判据也要反过来写 ——
 * 下面每条都在钉「不许相等」。
 */
class ReadmeDocKeyTest {

    private fun key(
        html: String = "<article>hello</article>",
        host: String = "github.com",
        owner: String = "SunsetRNE",
        repo: String = "Branchbase-Android",
        branch: String = "main",
        login: String = "SunsetRNE",
        token: String = "ghp_x",
        documentUrl: String = "https://github.com/SunsetRNE/Branchbase-Android/raw/main/",
    ) = readmeDocKey(html, host, owner, repo, branch, login, token, documentUrl)

    @Test
    fun `同一篇文档指纹稳定`() {
        assertEquals(key(), key())
    }

    @Test
    fun `换分支就是另一篇_不许复用`() {
        assertNotEquals("main 的 README 不能当 master 的用", key(), key(branch = "master"))
    }

    @Test
    fun `换仓库是另一篇`() {
        assertNotEquals(key(), key(repo = "Sundown"))
        assertNotEquals(key(), key(owner = "deepseek-ai", repo = "deepseek-harness"))
    }

    @Test
    fun `正文变了就是另一篇`() {
        assertNotEquals(key(), key(html = "<article>changed</article>"))
    }

    @Test
    fun `基准目录变了是另一篇_相对图片靠它解析`() {
        assertNotEquals(
            "README 在 docs/ 下与在根目录下，同一份正文的相对图会指到不同地方",
            key(),
            key(documentUrl = "https://github.com/SunsetRNE/Branchbase-Android/raw/main/docs/"),
        )
    }

    @Test
    fun `登录名与令牌参与指纹_私有仓库的图鉴权不同`() {
        assertNotEquals(key(), key(login = "someone-else"))
        assertNotEquals(key(), key(token = "ghp_y"))
    }

    @Test
    fun `指纹里不出现登录名与令牌原文`() {
        // owner 本来就在键里（它决定哪一篇文档），所以这里让 login ≠ owner 才验得准
        val k = key(owner = "acme", repo = "widget", login = "SunsetRNE", token = "ghp_secret_token")
        assertFalse("登录名不该明文进指纹：$k", k.contains("SunsetRNE"))
        assertFalse("令牌不该明文进指纹：$k", k.contains("ghp_secret_token"))
        assertTrue("但仓库坐标要在（它才是「哪一篇」）：$k", k.contains("acme/widget"))
    }
}
