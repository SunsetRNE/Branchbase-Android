package com.branchbase.ui.navigation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「返回键消费逻辑」的结构性钉子（源码级，和 `ReadmeRenderAssetTest` / `TranslateBootScriptTest`
 * 同一套路：这些行为只有真机连按才试得出来，JVM 单测里没有 Compose 运行时，只能把规则钉在源码上）。
 *
 * 两条规则来自根目录的 `NAVIGATION-NOTES.md`：
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
        val pagesWithInnerLayer = mapOf(
            "src/main/java/com/branchbase/ui/profile/ProfileScreen.kt" to "个人页子页（设置的下级页要退回设置）",
            "src/main/java/com/branchbase/ui/profile/SubPageScreens.kt" to "本地仓库页的十余个决策页",
            "src/main/java/com/branchbase/ui/task/TaskScreen.kt" to "任务详情页",
            "src/main/java/com/branchbase/ui/repository/RepositoryFileViewer.kt" to "文件页决策页 + 编辑态",
            "src/main/java/com/branchbase/ui/repository/IssueDetailScreen.kt" to "Issue 评论编辑态",
            "src/main/java/com/branchbase/ui/notification/NotificationScreen.kt" to "通知多选 / 筛选面板",
        )
        val missing = pagesWithInnerLayer.filter { (path, _) -> !source(path).contains("PageBackHandler(") }
        assertEquals(
            "这些页面必须用 PageBackHandler 消费自己的下一层（否则系统返回键会跳过页面内的返回箭头）：$missing",
            emptyMap<String, String>(),
            missing,
        )
    }
}
