package com.branchbase.ui.navigation

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「导航栏槽位」的结构性钉子（源码级，与 [PageTransitionsTest] / `BackConsumptionTest` 同一套路：
 * 这类问题只有在真机上反复切 Tab 才看得出来，JVM 单测里没有 Compose 运行时，只能把结构钉在源码上）。
 *
 * 钉的是用户反馈的那个 bug：**切页面时底部导航栏上下跳**。根因有两条，缺一条都不成立：
 * 1. 栏长在 `PageSwitcher` **里面** —— 同级切换的动效带 2% 高的垂直位移，整条栏跟着上移 / 上浮，
 *    退场旧栏与新栏还会错位同时出现在屏上；
 * 2. Tab 目的地被塞进外层路由（`Tabs(selected)` / `Tab(page)` / `Main(tab)`）—— 切一次 Tab
 *    就等于换一次路由，于是上面那条动效在每次切 Tab 时都会触发（内层 `TabSwitcher` 还会再播一次）。
 *
 * 正确形态：`NavigationShell { PageSwitcher { ... } }`，Tab 只活在内容区自己的 `TabSwitcher` 里。
 * 详见 `NavigationShell` 的文件头注释与 `README` 的「页面级」约定。
 */
class NavShellStructureTest {

    private fun source(path: String): String {
        val file = File(path)
        assertTrue("找不到源文件：${file.absolutePath}（单测工作目录应为 app 模块根）", file.exists())
        return file.readText()
    }

    /** 三处「带底部导航栏的骨架」→ 它们各自的外层路由该怎么写。 */
    private val shells = mapOf(
        "src/main/java/com/branchbase/ui/main/MainScreen.kt" to "data object Tabs : MainRoute",
        "src/main/java/com/branchbase/ui/repository/RepositoryScreen.kt" to "data object Tab : RepoRoute",
        "src/main/java/com/branchbase/ui/profile/ProfileScreen.kt" to "data object Main : ProfileRoute",
    )

    @Test
    fun `导航栏槽位必须在页面切换器外面`() {
        shells.keys.forEach { path ->
            val text = source(path)
            val shellAt = text.indexOf("NavigationShell(")
            val switcherAt = text.indexOf("PageSwitcher(")
            assertTrue("$path：找不到 NavigationShell( —— 底部导航栏必须挂在壳子的 bar 槽位里", shellAt >= 0)
            assertTrue("$path：找不到 PageSwitcher(", switcherAt >= 0)
            assertTrue(
                "$path：NavigationShell 必须**包住** PageSwitcher（在文件里先出现）。" +
                    "栏放进切换器里，同级切换的垂直位移会带着整条栏一起动 —— 那就是「切页面时导航栏上下跳」",
                shellAt < switcherAt,
            )
        }
    }

    @Test
    fun `Tab 维度不进外层路由`() {
        shells.forEach { (path, marker) ->
            assertTrue(
                "$path：外层路由必须是 `$marker`（data object）。带上 Tab 目的地的话，" +
                    "切一次 Tab 就等于换一次路由，整块内容连导航栏一起被同级动效播；" +
                    "Tab 是内容区自己的维度，交给内容区那层 TabSwitcher。",
                source(path).contains(marker),
            )
        }
    }
}
