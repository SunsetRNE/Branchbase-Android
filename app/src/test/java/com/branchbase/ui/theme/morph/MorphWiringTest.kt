package com.branchbase.ui.theme.morph

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 接线的**源码级钉子**（套路同 `ThemeConvergenceTest` / `FileViewerThemeTest`）。
 *
 * 为什么这类约定要钉在源码上：形变接错了编译不报、单测不红，只有真机上肉眼能发现，
 * 而且发现时通常已经被当成「动效有点怪」放过去了。这里把三件事钉死：
 *
 * 1. **检索页的按钮真的接了形变组件**（不是又退回 `Icon(if (…) …)` 的硬切）；
 * 2. **结构差异大的配对必须走降级**（`MorphIcons` 台账里同时存在「可形变」与「明确不形变」两类）；
 * 3. **进度必须以 lambda 进绘制期消费** —— 这条一破，每帧重组整棵调用树，
 *    「加载时/切页面时卡」的老问题会以另一种形式回来。
 */
class MorphWiringTest {

    private val appRoot = File("src/main/java/com/branchbase")
    private val searchScreen = File(appRoot, "ui/search/SearchScreen.kt")
    private val moreBubble = File(appRoot, "ui/navigation/MoreBubble.kt")
    private val morphIcon = File(appRoot, "ui/theme/morph/MorphIcon.kt")

    private fun source(file: File): String {
        assertTrue("找不到文件：${file.absolutePath}", file.isFile)
        return file.readText()
    }

    @Test
    fun `检索页的选项按钮接了形变台账`() {
        val text = source(searchScreen)
        // 类型 / 排序下拉与过滤按钮已收进抽屉：选中态由 CardSegments 的滑动胶囊表达，
        // 搜索页里只剩高级筛选这一对 +/− 还需要形变（展开 ⇄ 收起）。
        assertEquals("高级筛选的 +/−", 1, Regex("MorphIcons\\.PlusMinus").findAll(text).count())
        assertTrue(
            "高级筛选行必须真的渲染形变组件",
            Regex("AnimatedMorphIcon\\(").findAll(text).count() >= 1,
        )
        assertFalse(
            "类型/排序下拉箭头已随下拉收进抽屉，分段控件负责选中态",
            text.contains("MorphIcons.ChevronDownUp"),
        )
        assertFalse(
            "过滤按钮已收进抽屉入口卡，不再自己用 Tune/FilterOpen 表达展开",
            text.contains("MorphIcons.FilterOpen"),
        )
        // 抽屉与分段必须是共享件（个人页抽屉同源），不能在搜索页再抄一套
        assertTrue("搜索页应复用 CardDrawer", text.contains("CardDrawer("))
        assertTrue("搜索页应复用 CardSegments", text.contains("CardSegments("))
    }

    @Test
    fun `加减号不再用字符硬切`() {
        val text = source(searchScreen)
        assertFalse(
            "「+ / −」两个字符的硬切已经被形变替换，别退回去",
            text.contains("if (expanded) \"−\" else \"+\""),
        )
        assertFalse("过滤按钮不再直接写死 Tune 图标（改由台账决定形变或降级）", text.contains("Icons.Filled.Tune"))
    }

    @Test
    fun `按钮按下反馈接在点击源上`() {
        // 搜索页的选项入口与搜索卡改走共享 FloatingCardButton：按压原语与 interactionSource
        // 的接线收在那一份实现里，任何调用点都不会再自己写一份 tween 把反馈分家
        val shared = source(File(appRoot, "ui/FloatingCard.kt"))
        assertTrue(
            "浮层卡必须复用全 App 唯一的按压原语",
            shared.contains("rememberPressFeedback()"),
        )
        // 反馈必须把 interactionSource 交给 clickable，否则 collectIsPressedAsState 永远收不到事件
        assertTrue(
            "浮层卡的按下反馈没有接到 clickable 的 interactionSource 上",
            shared.contains("interactionSource = press.interaction"),
        )
        val text = source(searchScreen)
        assertTrue("搜索页的选项入口应走 FloatingCardButton", text.contains("FloatingCardButton("))
        // 分页「加载更多」仍在本文件里自己接按压反馈
        assertTrue(
            "加载更多仍要有按下反馈",
            Regex("rememberPressFeedback\\(\\)").findAll(text).count() >= 1 &&
                text.contains("interactionSource = press.interaction"),
        )
    }

    @Test
    fun `进度值走 lambda 进绘制期消费`() {
        val text = source(morphIcon)
        assertTrue(
            "进度必须以 () -> Float 传进来（每帧只失效绘制，不重组）",
            text.contains("progress: () -> Float"),
        )
        assertTrue("渲染在 Canvas 的绘制作用域里读进度", text.contains("val frame = plan.frame(progress()"))
    }

    @Test
    fun `台账里可形变与明确不形变两类都在`() {
        assertTrue(
            "台账必须同时有「可形变」与「明确不形变」两类，否则降级路径没有被真实用例覆盖",
            MorphIcons.all.any { it.expectation is MorphExpectation.Morph } &&
                MorphIcons.all.any { it.expectation is MorphExpectation.Fallback },
        )
    }

    /**
     * 底栏「更多」手柄：**已接台账**，且不再靠旋转假装状态。
     *
     * 这两条一起才有意义 —— 只换字形不删除旋转，中间帧会同时「转」和「换」，
     * 比原来的硬切换更糊；只删旋转不接台账，等于把决定又写回调用方。
     */
    @Test
    fun `更多手柄接了形变台账_且不再用旋转表达状态`() {
        val text = source(moreBubble)
        assertTrue(
            "手柄应改走 AnimatedMorphIcon（台账决定形变或降级），而不是自己 Icon(if…)",
            text.contains("AnimatedMorphIcon("),
        )
        assertTrue("手柄应引用台账里的 MoreOpen", text.contains("MorphIcons.MoreOpen"))
        assertFalse(
            "展开态不再把 MoreHoriz 转 90° —— 转完还是 MoreVert，语义没变，用户读不到「已展开」",
            text.contains("rotationZ"),
        )
        assertTrue(
            "面板进场应走 morePanelEnter（贴栏抽屉的弹簧原语），不要就地糊一套 spring",
            text.contains("morePanelEnter("),
        )
    }
}
