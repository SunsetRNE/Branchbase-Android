package com.branchbase.ui.repository

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这个仓库是不是我的 / 我能不能发版」为什么慢 —— 发布 Tab 的取数顺序钉子（源码级）。
 *
 * ## 真机观感
 *
 * 用户问：「关于仓库是否是账号持有者的判定，为什么那么慢？」
 *
 * 判定规则本身**零网络**（`repoRelationOf`：owner 与本地登录名一比就出结论，`permissions.push`
 * 只是补充协同身份）；慢的永远是它的输入。发布 Tab 原来的取数是一条**串行链**：
 *
 * ```
 * L1 直出列表 → GET /repos/{owner}/{repo}/releases → GET /releases/latest
 *              →（必要时）逐条 GET /releases/{id} 补 assets
 *              → GET /repos/{owner}/{repo}   ← 写权限判定排在最后一步
 * ```
 *
 * 于是列表（有缓存，同帧就能显示）之后，「+ 新建发布」还要再等 2~4 次串行往返才冒出来。
 * 而同一份 `/repos/{owner}/{repo}`，仓库页刚写进 `PreloadStore` 的 info 缓存（同一个键）。
 *
 * ## 修法（本测试守的）
 *
 * 1. 先读 info 缓存（含过期）当帧定形 —— 命中时「+」与列表同帧出现；
 * 2. 网络那次用 `async` **与列表并行**跑（不再排在链尾），回来覆盖即可；
 * 3. 顺手把结果写回 info 缓存，仓库页因此也少发一次同样的请求。
 */
class ReleasePermissionFastPathTest {

    private val listPath = "src/main/java/com/branchbase/ui/repository/RepositoryListScreens.kt"

    private fun code(): String {
        val file = File(listPath)
        assertTrue("找不到源文件：${file.absolutePath}（单测工作目录应为 app 模块根）", file.exists())
        return file.readText().lines()
            .filterNot { it.trimStart().startsWith("import") }
            .joinToString("\n")
    }

    @Test
    fun `写权限先吃缓存再回源`() {
        val src = code()
        assertTrue(
            "先读仓库信息缓存（含过期）：仓库页刚写过同一个键，命中就同帧定形",
            src.contains("manager.getStale(PreloadStore.infoKey(owner, repo), PreloadStore.TYPE_INFO)"),
        )
        assertTrue("缓存里就有写权限", src.contains("parseRepoInfo(cached)?.let { canPush = it.canPush }"))
        assertTrue(
            "回源结果要写回同一个 info 键（仓库页读的是它，省掉一次重复请求）",
            src.contains("manager.put(PreloadStore.infoKey(owner, repo), PreloadStore.TYPE_INFO, info)"),
        )
    }

    @Test
    fun `写权限的回源与列表并行而不是排在链尾`() {
        val src = code()
        val push = src.indexOf("val pushJob = async {")
        val list = src.indexOf("RustBridge.getJson(host, token, \"/repos/\$owner/\$repo/releases\")")
        val await = src.indexOf("pushJob.await()")
        assertTrue("要有一个独立的 pushJob：$push", push > 0)
        assertTrue("找不到发布列表回源那一行，测试要跟着改锚点：$list", list > 0)
        assertTrue("pushJob 必须**先**发起来（排在列表回源之前才叫并行）：$push / $list", push < list)
        assertTrue("结论要在后面 await 收口：$await", await > list)
        assertFalse(
            "旧写法把写权限放在链尾的最后一步 —— 「是不是我的仓库」就要等上面 2~4 次串行往返",
            src.contains("canPush = RustBridge.getRepoInfo(host, token, owner, repo)"),
        )
    }

    @Test
    fun `取不到权限时按无权限处理`() {
        val src = code()
        assertTrue("canPush 初始值仍必须是 false（保守：拿不到就不显示发布入口）", src.contains("var canPush by remember { mutableStateOf(false) }"))
        assertTrue(
            "回源失败（ERROR: / null）时不许把 canPush 置 true",
            src.contains("RustBridge.getRepoInfo(host, token, owner, repo)?.takeIf { !it.startsWith(\"ERROR:\") }"),
        )
    }
}
