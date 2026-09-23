package com.branchbase.ui.navigation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「返回键消费逻辑」的结构性钉子（源码级，和 `ReadmeRenderAssetTest` / `TranslateBootScriptTest`
 * 同一套路：这些行为只有真机连按才试得出来，JVM 单测里没有 Compose 运行时，只能把规则钉在源码上）。
 *
 * 两条规则来自 `docs/specs/NAVIGATION-NOTES.md`：
 *
 * 1. **页面级返回键一律走 `PageBackHandler`**，不许裸用 `androidx.activity.compose.BackHandler`
 *    —— 裸用手会绕过 `LocalPageActive`，退场动画期间新旧两页抢同一个事件；
 * 2. **页面内部有「自己的下一层」（决策页 / 详情页 / 编辑态）时，必须自己消费返回键**，
 *    否则系统返回键与页面左上角的返回箭头会走成两条路（一个跳层、一个丢页）。
 */
class BackConsumptionTest {

    private fun source(path: String): String {
        val file = File(path)
        assertTrue("找不到源文件：${file.absolutePath}（单测工作目录应为 app 模块根）", file.exists())
        return file.readText()
    }

    /** 全部页面源码（路径 → 内容），排除 import 行后再做匹配。 */
    private fun pageSources(): List<Pair<String, String>> = File("src/main/java")
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .map { file ->
            file.path to file.readLines()
                .filterNot { it.trimStart().startsWith("import") }
                .joinToString("\n")
        }
        .toList()

    @Test
    fun `页面不得裸用 BackHandler`() {
        // 允许的两处：
        // - PageTransitions.kt：PageBackHandler 自己的实现（唯一合法入口）；
        // - LoginFlow.kt：登录流程的顶层兜底（欢迎页不拦截、已登录交给主界面，按状态显式排除）。
        val allowed = setOf(
            "src/main/java/com/branchbase/ui/navigation/PageTransitions.kt",
            "src/main/java/com/branchbase/ui/auth/LoginFlow.kt",
        )
        // \b 保证不会把 PageBackHandler( 也算进来（"e" 与 "B" 之间没有单词边界）
        val bare = Regex("\\bBackHandler\\s*\\(")
        val offenders = pageSources()
            .filter { (path, _) -> path !in allowed }
            .filter { (_, text) -> bare.containsMatchIn(text) }
            .map { (path, _) -> path }
        assertEquals(
            "页面级返回键必须走 PageBackHandler（裸 BackHandler 会绕过 LocalPageActive，" +
                "退场动画期间新旧两页抢同一个事件），违规文件：$offenders",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `有内部层级的页面必须自己消费返回键`() {
        // 这些页面内部都有「决策页 / 详情页 / 编辑态」这类自己的下一层，
        // 且页面内都有返回箭头：系统返回键必须消费同一层，不能直接跳出去。
        //
        // 1.0.70 起合格写法有**两种**（都是「消费了自己的下一层」）：
        // - 页面自己挂 `PageBackHandler`（下一层与页面无关，例如评论编辑态、多选态）；
        // - 或者把「退回上一层」交给 `PageSwitcher(onBack = …)` 兜底
        //   （页面路由本身由切换器管，见下一条用例）。
        val pagesWithInnerLayer = mapOf(
            "src/main/java/com/branchbase/ui/profile/ProfileScreen.kt" to "个人页子页（设置的下级页要退回设置）",
            "src/main/java/com/branchbase/ui/profile/SubPageScreens.kt" to "本地仓库页的十余个决策页",
            "src/main/java/com/branchbase/ui/task/TaskScreen.kt" to "任务详情页",
            "src/main/java/com/branchbase/ui/repository/RepositoryFileViewer.kt" to "文件页决策页 + 编辑态",
            "src/main/java/com/branchbase/ui/repository/IssueDetailScreen.kt" to "Issue 评论编辑态",
            "src/main/java/com/branchbase/ui/notification/NotificationScreen.kt" to "通知多选 / 筛选面板",
        )
        val missing = pagesWithInnerLayer.filter { (path, _) ->
            val text = source(path)
            !text.contains("PageBackHandler") && !text.contains("onBack =")
        }
        assertEquals(
            "这些页面必须消费自己的下一层（PageBackHandler 或 PageSwitcher(onBack = …)），" +
                "否则系统返回键会跳过页面内的返回箭头：$missing",
            emptyMap<String, String>(),
            missing,
        )
    }

    /**
     * **默认返回必须是必填的**（2026-09 起）。
     *
     * `PageSwitcher` 现在自己给每一格子页注册兜底返回，宿主只需提供一个「退一层」的动作。
     * 如果哪天有人给 `onBack` 补上一个默认值（`= null`），编译器兜底就没了 ——
     * 新写的切换器可以再次「不表态」，而后果依旧是静默的：系统返回跳掉一层。
     * 那正是这一整套改动要消灭的失败模式，所以在这里钉死：参数**没有默认值**。
     */
    @Test
    fun `PageSwitcher 的默认返回是必填参数`() {
        val text = source("src/main/java/com/branchbase/ui/navigation/PageTransitions.kt")
        assertTrue(
            "`onBack` 必须是必填参数（写成 `= null` 就等于允许新切换器不表态）",
            text.contains("onBack: (() -> Unit)?,"),
        )
        assertFalse(
            "`onBack` 不许有默认值：默认值会让「忘了给兜底」重新变成静默缺陷",
            text.contains("onBack: (() -> Unit)? = null"),
        )
        assertTrue(
            "切换器要自己注册兜底（注册在 content 之前 ⇒ 页面自己的处理器优先）",
            text.contains("if (onBack != null && isSubPage(target)) {") &&
                text.contains("PageBackHandler(onBack = onBack)"),
        )
    }

    /**
     * 「退一层」在宿主那边必须是一条**穷尽 `when`**（`RepoRoute` / `MainRoute` 都是 sealed）。
     *
     * 这是「默认处理」能成立的最后一环：新增路由时编译器会强制先在 `leavePage` 里表态，
     * 而不是像旧写法那样「漏挂一个分支 ⇒ 那一页按返回直接跳掉一层」。
     * 仓库页的网页登录页就是这么漏过一版的（`RepoRoute.WebLogin` 当年没有 handler）。
     */
    @Test
    fun `宿主的退一层规则必须是穷尽 when`() {
        listOf(
            "src/main/java/com/branchbase/ui/repository/RepositoryScreen.kt" to "RepoRoute.Tab -> Unit",
            "src/main/java/com/branchbase/ui/main/MainScreen.kt" to "MainRoute.Tabs -> Unit",
        ).forEach { (path, tabBranch) ->
            val text = source(path)
            assertTrue("$path 要有唯一的 leavePage() 规则", text.contains("fun leavePage()"))
            assertTrue(
                "$path 的 when 要显式处理顶层（`$tabBranch`）—— 那句话就是「这一层不做事，交给外层」",
                text.contains(tabBranch),
            )
            assertTrue("$path 的切换器要把这条规则交给兜底", text.contains("onBack = ::leavePage"))
        }
        assertTrue(
            "仓库页不再逐分支挂 handler（漏一个就跳层）",
            !source("src/main/java/com/branchbase/ui/repository/RepositoryScreen.kt").contains("PageBackHandler { filePage = null }"),
        )
    }
}
