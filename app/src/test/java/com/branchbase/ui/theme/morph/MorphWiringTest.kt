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
    private val morphIcon = File(appRoot, "ui/theme/morph/MorphIcon.kt")

    private fun source(file: File): String {
        assertTrue("找不到文件：${file.absolutePath}", file.isFile)
        return file.readText()
    }

    @Test
    fun `检索页的按钮接了形变组件`() {
        val text = source(searchScreen)
        val calls = Regex("AnimatedMorphIcon\\(").findAll(text).count()
        assertTrue("检索页应至少有 4 处按钮/图标接了形变（类型、排序、过滤、高级筛选），实际 $calls", calls >= 4)

        assertEquals("类型下拉箭头", 2, Regex("MorphIcons\\.ChevronDownUp").findAll(text).count())
        assertEquals("高级筛选的 +/−", 1, Regex("MorphIcons\\.PlusMinus").findAll(text).count())
        assertEquals("过滤按钮（走降级）", 1, Regex("MorphIcons\\.FilterOpen").findAll(text).count())
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
        val text = source(searchScreen)
        val feedback = Regex("rememberPressFeedback\\(\\)").findAll(text).count()
        assertTrue("按下反馈应覆盖工具栏三个按钮与加载更多，实际 $feedback", feedback >= 4)
        // 反馈必须把 interactionSource 交给 clickable，否则 collectIsPressedAsState 永远收不到事件
        assertTrue(
            "按下反馈没有接到 clickable 的 interactionSource 上",
            text.contains("interactionSource = press.interaction") &&
                text.contains("interactionSource = sortPress.interaction") &&
                text.contains("interactionSource = filterPress.interaction"),
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
}
