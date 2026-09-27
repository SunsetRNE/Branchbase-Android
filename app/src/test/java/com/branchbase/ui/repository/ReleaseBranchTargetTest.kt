package com.branchbase.ui.repository

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「发布页的分支 chip 是真能选目标分支的按钮，而且图标是设计稿那一枚」的结构性钉子。
 *
 * 背景：这一枚 chip 原来只是把仓库默认分支**显示**出来（`ReleaseTagTitleRow` 的 `branch` 参数），
 * 图标借的是 Material 的 `AccountTree` —— 一个 24 网格的填充树杈，和设计稿里 16 网格、1.5 描边的
 * 分支图标不是同一个形状。用户点名要的就是这两件事：**分支能选** + **图标落地**。
 *
 * 这里钉四条不变量（Compose 运行时在 JVM 单测里没有，所以按 `ReleaseHeaderLayoutTest` 的套路钉源码）：
 *
 * 1. chip 只在 `onBranch != null` 时可点 —— 编辑已有发布走 null 分支（GitHub 的 `PATCH /releases`
 *    不接受 `target_commitish`，给一个点得动但点完不变的东西比不给更糟）；
 * 2. 图标是 [ReleaseBranchIcon]（`ReleaseIcons.kt`），且 `AccountTree` 不能回来；
 * 3. 图标的几何必须与设计稿 `design/release-redesign/app.js` 的 `ICONS.branch` 逐点一致
 *    （改了设计稿而没改实现，这条测试会红 —— 反向也一样）；
 * 4. 分支列表走的是与「分支管理 / 对比 / 同步」同一份接口 + 同一份缓存键，不是另写一套取数。
 */
class ReleaseBranchTargetTest {

    private val partsPath = "src/main/java/com/branchbase/ui/repository/ReleaseEditParts.kt"
    private val iconsPath = "src/main/java/com/branchbase/ui/repository/ReleaseIcons.kt"
    private val screensPath = "src/main/java/com/branchbase/ui/repository/ReleaseScreens.kt"
    private val syncPath = "src/main/java/com/branchbase/ui/repository/BranchSyncScreen.kt"
    private val designPath = "../design/release-redesign/app.js"

    private fun source(path: String): String {
        val file = File(path)
        assertTrue("找不到源文件：${file.absolutePath}（单测工作目录应为 app 模块根）", file.exists())
        return file.readText()
    }

    /** 去掉 import 与注释行：注释里正好引着设计稿的原路径与反例，不能算进正文。 */
    private fun code(path: String): String =
        source(path).lines()
            .filterNot { it.trimStart().startsWith("import") }
            .filterNot { it.trimStart().startsWith("//") }
            .filterNot { it.trimStart().startsWith("*") }
            .filterNot { it.trimStart().startsWith("/*") }
            .joinToString("\n")

    @Test
    fun `分支 chip 只在新建时可点，编辑已有发布保持只读`() {
        val parts = code(partsPath)
        assertTrue(
            "`ReleaseTagTitleRow` 必须收 `onBranch: (() -> Unit)? = null` —— 由调用方决定这一枚 chip 能不能点",
            parts.contains("onBranch: (() -> Unit)? = null"),
        )
        assertTrue(
            "chip 必须挂 clickable 并回调 onBranch（当前没有就是又退回只读标记）",
            parts.contains(".clickable { onBranch() }"),
        )
        val screens = code(screensPath)
        assertTrue(
            "新建（existing == null）时才把打开分支列表的回调传下去",
            screens.contains("onBranch = if (existing == null)"),
        )
    }

    @Test
    fun `分支图标是设计稿那一枚，不再用 Material 的占位`() {
        val parts = code(partsPath)
        assertTrue(
            "分支 chip 与选择弹层都用 `ReleaseBranchIcon`",
            parts.contains("ReleaseBranchIcon,"),
        )
        assertFalse(
            "Material 的 `AccountTree`（24 网格填充树杈）不是设计稿的分支图标 —— 别让它回来",
            parts.contains("AccountTree"),
        )
        assertTrue("选择弹层里也要用同一枚图标", code(screensPath).contains("ReleaseBranchIcon,"))
        assertFalse(
            "`ReleaseIcons.kt` 之外不该再出现 `AccountTree`",
            code(screensPath).contains("AccountTree"),
        )
    }

    /**
     * 图标几何对着设计稿钉：两边任意一侧改了坐标，这条测试都会红。
     *
     * 设计稿那一枚是 16×16、`fill="none" stroke-width="1.5"` 的描边图形：
     * 两个半径 1.7 的圆环节点（圆心 `4.6,4` 与 `4.6,12`）、一条竖直连线 `M4.6 5.7v4.6`、
     * 一条带 2.4 圆角的支线 `M6.3 4h3.2A2.4 2.4 0 0 1 11.9 6.4v3.1`。
     */
    @Test
    fun `图标几何与设计稿的 ICONS_branch 一致`() {
        val icons = code(iconsPath)
        assertTrue("图标网格必须是设计稿的 16×16", icons.contains("viewportWidth = 16f") && icons.contains("viewportHeight = 16f"))
        assertTrue("描边宽度必须是设计稿的 1.5", icons.contains("strokeLineWidth = 1.5f"))

        // 设计稿侧：路径字符串必须还在（改了设计稿就要同步改实现）
        val design = source(designPath)
        val designBranch = Regex("branch:\\s*'([^']*)'").find(design)?.groupValues?.get(1)
        assertTrue("`design/release-redesign/app.js` 里找不到 ICONS.branch（设计稿改了？）", designBranch != null)
        for (geometry in listOf("4.6", "1.7", "M4.6 5.7v4.6", "M6.3 4h3.2A2.4 2.4 0 0 1 11.9 6.4v3.1")) {
            assertTrue("设计稿的 ICONS.branch 里没有 `$geometry`（设计稿被改过了，实现要跟着改）", designBranch!!.contains(geometry))
        }

        // 实现侧：设计稿的四个图元逐点照抄
        for (node in listOf(
            "moveTo(2.9f, 4f)",      // 上节点圆心 4.6,4 - 半径 1.7
            "moveTo(2.9f, 12f)",     // 下节点圆心 4.6,12 - 半径 1.7
            "moveTo(4.6f, 5.7f)",    // M4.6 5.7
            "verticalLineTo(10.3f)", // v4.6
            "moveTo(6.3f, 4f)",      // M6.3 4
            "horizontalLineTo(9.5f)", // h3.2
            "arcToRelative(2.4f, 2.4f, 0f, isMoreThanHalf = false, isPositiveArc = true, dx1 = 2.4f, dy1 = 2.4f)",
            "verticalLineTo(9.5f)",  // ...v3.1
        )) {
            assertTrue("实现里少了设计稿的这段几何：`$node`", icons.contains(node))
        }
    }

    @Test
    fun `分支列表与分支管理页同源同缓存，不另写一套取数`() {
        val screens = code(screensPath)
        assertTrue(
            "分支列表必须走 `RustBridge.listBranches(host, token, owner, repo)`（和分支同步页同一个接口）",
            screens.contains("RustBridge.listBranches(host, token, owner, repo)"),
        )
        assertTrue(
            "必须复用 `PageCache.branchListKey`：分支管理 / 对比 / 同步三页共用同一份缓存，别各拉一遍",
            screens.contains("PageCache.branchListKey(owner, repo)"),
        )
        assertTrue(
            "选中分支后必须写回 target —— 否则点了等于没点（target_commitish 与生成说明的基准都不会变）",
            screens.contains("target = picked"),
        )
        assertTrue(
            "分支名解析必须复用 `parseBranchNames`（`BranchSyncScreen.kt`），别在发布页再写一份 JSON 解析",
            code(syncPath).contains("internal fun parseBranchNames("),
        )
    }
}
