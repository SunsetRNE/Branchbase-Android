package com.branchbase.ui.repository

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仓库页加载 effect 的**结构性钉子**（源码级，套路同 `FileEditorWiringTest`）。
 *
 * ## 它守的是什么（真机现场 2026-09-22，v1.0.65）
 *
 * 用户反馈：「仓库页面对旧有页面的命中概率好低，很容易重建和重新渲染」。
 * 日志里的形状是**同一批缓存读连着出现三轮**：
 * ```
 * 22:52:52.538  L2 直出 repo-info ×1 / @main / repo-lang / repo-contrib  → 未命中 repo-lang/@main/repo-contrib
 * 22:52:52.629  L1 直出 repo-info ×2 / @main / repo-lang / repo-contrib  → 未命中 repo-lang/@main/repo-contrib
 * 22:52:53.019  L1 直出 repo-info ×2 / @main / repo-lang / repo-contrib  → 未命中 repo-lang/@main/repo-contrib
 * ```
 * 一次「进入仓库详情页」里，整段加载跑了 **3 遍**（外部的 `sharedInfo` 与缓存直出会先后把
 * 默认分支补上，而 effect 的键里有 `branch` / `repoInfo?.defaultBranch`）。每遍都会：
 * 把语言 / 贡献者重新打回加载态（骨架闪一次）、并换一个 `coroutineScope` 重启在途请求
 * （上一遍的请求被取消 ⇒ 同一份数据发 3 次、只落最后一份）。
 *
 * ## 钉住什么
 *
 * 1. 「与分支无关的那一半」的键只能是 `(owner, repo, refreshTick)` ——
 *    键里再出现分支，整段加载就会跟着分支重跑；
 * 2. README 必须是**独立的** effect，且键跟着 `readmeBranch` 走
 *    （它是唯一真正依赖分支的一块；分支未知时直接返回，绝不猜）；
 * 3. 重新打加载态前要看「手上有没有数据」—— 这条被违反时页面不会报错，
 *    只会「重进/重跑时闪一下骨架」，正是用户描述的那个观感。
 */
class RepoOverviewLoadTest {

    private val overviewPath = "src/main/java/com/branchbase/ui/repository/RepositoryOverviewScreen.kt"
    private val screenPath = "src/main/java/com/branchbase/ui/repository/RepositoryScreen.kt"

    private fun code(path: String): String {
        val file = File(path)
        assertTrue("找不到源文件：${file.absolutePath}（单测工作目录应为 app 模块根）", file.exists())
        return file.readText().lines().filterNot { it.trimStart().startsWith("import") }.joinToString("\n")
    }

    @Test
    fun `与分支无关的加载不许多带分支键`() {
        val src = code(overviewPath)
        assertTrue(
            "「仓库信息 + 语言 + 贡献者」那一段只该跟 (owner, repo, refreshTick) 走",
            src.contains("LaunchedEffect(owner, repo, refreshTick)"),
        )
        assertFalse(
            "键里带上分支 = 外部 sharedInfo / 缓存直出补上分支时整段重跑（实测 3 遍：数据发 3 次、骨架闪 3 次）",
            src.contains("LaunchedEffect(owner, repo, branch, refreshTick, repoInfo?.defaultBranch)"),
        )
    }

    @Test
    fun `README 必须是独立的 effect 且跟着分支走`() {
        val src = code(overviewPath)
        assertTrue("README 的取数分支要被提出来当一个稳定的键", src.contains("val readmeBranch = branch ?: repoInfo?.defaultBranch"))
        assertTrue("README 要有自己的 effect", src.contains("LaunchedEffect(owner, repo, readmeBranch, refreshTick)"))
        assertTrue(
            "分支未知时必须直接返回（保持骨架），绝不拿猜的分支去取",
            src.contains("readmeBranch ?: return@LaunchedEffect"),
        )
    }

    @Test
    fun `重新打加载态之前要看手上有没有数据`() {
        val src = code(overviewPath)
        listOf(
            "if (force || repoInfo == null) infoLoading = true",
            "if (force || readmeHtml == null) readmeLoading = true",
            "if (force || languages.isEmpty()) langLoading = true",
            "if (force || contributors.isEmpty()) contribLoading = true",
        ).forEach {
            assertTrue("缺了这一行：$it（无条件置 true 会让每次重跑都闪一次骨架）", src.contains(it))
        }
    }

    /**
     * 关系态（星标双向态 / Watch 档位）先直出再复核。
     *
     * 判定要走「网页会话 → GraphQL」两条腿，冷的一次实测 ~800ms
     * （真机日志 `22:52:52.519 进入仓库页` → `22:52:53.320 判定`）；这段时间按钮此前是空的 ——
     * 表现就是「页面先渲染一遍、结论到了再重画一遍」。
     */
    @Test
    fun `关系态先直出再回源复核`() {
        val src = code(screenPath)
        val cached = src.indexOf("RepoActions.cachedRelation(")
        val fresh = src.indexOf("RepoActions.loadRelation(")
        assertTrue("关系态要先直出（含过期），别让按钮空着等一次往返", cached > 0)
        assertTrue("关系态仍然要回源复核", fresh > 0)
        assertTrue("顺序必须是「先直出、后复核」", cached < fresh)
        assertTrue(
            "复核不到时要保留直出的旧值（`?.let` 而不是直接赋值 null）",
            src.contains("RepoActions.loadRelation(context, sessionHost, sessionToken, owner, repo, sessionLogin)\n            ?.let { relation = it }"),
        )
    }
}
