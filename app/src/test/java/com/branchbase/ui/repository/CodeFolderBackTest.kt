package com.branchbase.ui.repository

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 代码页「返回上一层文件夹」的钉子（纯函数 + 源码级接线，与 `ProfileBackTargetTest` / `GitWorkbenchWiringTest` 同一套路）。
 *
 * 缺陷（用户报的，2026-09-26 真机复现）：代码页点进任意文件夹（如 `core/src`）后，
 * **系统返回键与左上角返回箭头都直接跳出整个仓库页** —— 只有面包屑上那截 24dp 宽的蓝字能回上一层，
 * 而它看起来只是标题文字。`NAVIGATION-NOTES.md` 规则 2 早就把这类问题写成条文
 * （「页面里有自己的下一层却不消费返回键」「返回键写死回到最外层」），并点名要用
 * `profileBackTarget` / `subPageDepth` 那样的**纯函数 + 单测**来钉住。
 *
 * 这里钉三件事，全是「只在真机上连按才看得出来」的：
 * 1. [parentPath] 的边界（根目录 / 多级 / 多余斜杠）—— 退目录退回 `/` 或空段是线上才炸的；
 * 2. [codeFolderBackTarget] 只在「Tab 骨架 + 代码页 + 有目录」时消费返回键，其余放手给宿主；
 * 3. **两条返回路径必须同源**：目录状态住页面级、左箭头与系统返回键取同一个 `folderUp`，
 *    列表首行还要有一个整行可点的「上一层」。
 */
class CodeFolderBackTest {

    private fun source(path: String): String {
        val file = File(path)
        assertTrue("找不到源文件：${file.absolutePath}（单测工作目录应为 app 模块根）", file.exists())
        return file.readText()
    }

    // ── 1. 纯函数：上一层目录 ──

    @Test
    fun `上一层目录逐级退`() {
        assertEquals("core", parentPath("core/src"))
        assertEquals("core/src", parentPath("core/src/git"))
        assertEquals("", parentPath("core"))
    }

    @Test
    fun `根目录与空串退不出东西`() {
        assertEquals("", parentPath(""))
        assertEquals("", parentPath("/"))
        // 多余斜杠不该退出一段空目录（API 里空段就是根，退回 "" 才是对的）
        assertEquals("", parentPath("core/"))
        assertEquals("core", parentPath("core//src"))
        assertEquals("", parentPath("//"))
    }

    @Test
    fun `进目录与退目录是一对`() {
        listOf("", "core", "core/src/git").forEach { start ->
            listOf("src", "a b", "中文目录").forEach { name ->
                assertEquals("进 $name 再退必须回到 $start", start, parentPath(joinPath(start, name)))
            }
        }
    }

    // ── 2. 纯函数：返回键的落点 ──

    @Test
    fun `代码页在子目录里按返回先上一层`() {
        assertEquals("core", codeFolderBackTarget(RepoPage.Code, tabRoute = true, path = "core/src"))
        assertEquals("", codeFolderBackTarget(RepoPage.Code, tabRoute = true, path = "core"))
    }

    @Test
    fun `根目录与别的页面不消费返回键`() {
        // 已在仓库根目录：放手给宿主（退 Tab / 关仓库页）
        assertNull(codeFolderBackTarget(RepoPage.Code, tabRoute = true, path = ""))
        // 全屏子页（路由不是 Tab）自己会消费，代码页不许抢
        assertNull(codeFolderBackTarget(RepoPage.Code, tabRoute = false, path = "core/src"))
        // 不是代码页就没有「上一层目录」这回事
        RepoPage.entries.filter { it != RepoPage.Code }.forEach { page ->
            assertNull("$page 不该按目录退层", codeFolderBackTarget(page, tabRoute = true, path = "core/src"))
        }
    }

    // ── 3. 接线：目录状态住页面级，两条返回路径同源 ──

    @Test
    fun `目录状态住在页面级且返回键按层级分派`() {
        val repo = source("src/main/java/com/branchbase/ui/repository/RepositoryScreen.kt")
        assertTrue(
            "目录状态必须住页面级（打开文件会切走 Tab，状态在列表里会丢）",
            repo.contains("var codePath by remember(repo) { mutableStateOf(\"\") }"),
        )
        assertTrue(
            "返回键要走那个纯函数取值",
            repo.contains("codeFolderBackTarget(page, tabRoute = route == RepoRoute.Tab, path = codePath)"),
        )
        assertTrue(
            "系统返回键：气泡与退目录两级都要挂着",
            repo.contains("PageBackHandler(bubbleExpanded || folderUp != null)"),
        )
        assertTrue(
            "左上角返回箭头必须与系统返回键同一个目标（不许各写一份 if）",
            repo.contains("onBack = { if (folderUp != null) codePath = folderUp else onBack() }"),
        )
        assertTrue(
            "代码页拿的是宿主那份目录，且进退目录都回调宿主",
            repo.contains("path = codePath,") && repo.contains("onNavigate = { codePath = it },"),
        )
    }

    @Test
    fun `列表首行有整行可点的上一层`() {
        val list = source("src/main/java/com/branchbase/ui/repository/RepositoryListScreens.kt")
        assertFalse(
            "目录状态不该再留在列表里（页面级才是真源）",
            list.contains("var path by remember { mutableStateOf(\"\") }"),
        )
        assertTrue("子目录里要有「上一层」首行", list.contains("item(key = PARENT_ROW_KEY) { ParentFolderRow { onNavigate(parentPath(path)) } }"))
        assertTrue("「上一层」行要整行可点", list.contains("private fun ParentFolderRow(onClick: () -> Unit)"))
        // 渲染口径：文件管理器那套「文件夹 + `..`」（2026-09-26 用户拍板，替换掉原先的上箭头 + 「上一层」文案）
        assertTrue("上一层用 `..` 当名字（文件管理器的老约定）", list.contains("private const val PARENT_ENTRY_NAME = \"..\""))
        assertTrue("上一层长得像目录项：与目录行同一个文件夹图标", list.contains("Icons.Filled.Folder,") && list.contains("PARENT_ENTRY_NAME,"))
        assertFalse("不该再有只在这里出现的上箭头", list.contains("KeyboardArrowUp"))
        assertTrue(
            "看名字看不出「上一层」的含义，a11y 标签必须兜底",
            list.contains("contentDescription = stringResource(R.string.nav_up_one_level),"),
        )
    }

    @Test
    fun `分档标签条不再把英文档名挤成窄列`() {
        val panel = source("src/main/java/com/branchbase/ui/repository/GitWorkspacePanel.kt")
        val start = panel.indexOf("private fun GitPanelTabs(")
        assertTrue("找不到 GitPanelTabs", start >= 0)
        // 取到这个函数自己的结尾（顶层 `}` 收尾），别把文件下面别的函数扫进来
        val body = panel.substring(start).substringBefore("\n}\n")
        assertTrue(
            "标签条要用 FlowRow：Row 在英文四档下会把 File history 词内折成三行（268dp 面板装不下 273dp）",
            body.contains("FlowRow("),
        )
        assertFalse(
            "旧的 Row 版容器会把最后一档挤成窄列",
            body.contains(
                // 前缀 `\n    ` 是为了不误伤 `FlowRow(`（`FlowRow(` 里也含子串 `Row(`）
                "\n    Row(\n" +
                    "        Modifier.fillMaxWidth(),\n" +
                    "        horizontalArrangement = Arrangement.spacedBy(4.dp),",
            ),
        )
    }
}
