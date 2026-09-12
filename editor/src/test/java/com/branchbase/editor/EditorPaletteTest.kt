package com.branchbase.editor

import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编辑器配色的单测。
 *
 * 这些用例钉的是**用户可见的契约**（不是实现细节）：
 * - 亮色模式正文必须是**深黑**，深色模式必须是**亮白**；
 * - 两套配色的正文都不能再是 Sora 默认的 `#333333`（那正是「渲染成灰色」的根因）；
 * - 正文与底色必须分属明暗两端（否则文字会糊在背景里）；
 * - **正文类颜色一个都不能漏**：漏掉的那些会退回库默认的灰 / 透明，
 *   而它们多半只在「只读预览」态才露出来（选中文字、行号面板、内联提示、诊断提示……）。
 *
 * 之所以值得测：颜色错了编译不会报、跑起来不崩，只有真机上肉眼才能发现。
 *
 * 注：这里直接引用 `EditorColorScheme.XXX` 是安全的 —— 它们是 Java 编译期常量，
 * Kotlin 会内联成整数，单测不会去加载 Android 类（见 `EditorPalette.assignments` 的注释）。
 */
class EditorPaletteTest {

    /** Sora `EditorColorScheme` 的默认正文色（不分明暗），即「发灰」的来源。 */
    private val soraDefaultText = 0xFF333333.toInt()

    private val alpha = 0xFF

    private val lightMap = EditorPalette.Light.assignments().toMap()
    private val darkMap = EditorPalette.Dark.assignments().toMap()

    private fun argbAlpha(color: Int): Int = (color ushr 24) and 0xFF

    private fun luminance(color: Int): Int {
        val r = (color ushr 16) and 0xFF
        val g = (color ushr 8) and 0xFF
        val b = color and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    @Test
    fun `浅色模式正文是纯黑而不是灰`() {
        val text = EditorPalette.Light.textNormal
        assertEquals(0xFF000000.toInt(), text)
        assertEquals(alpha, argbAlpha(text))
        assertNotEquals(soraDefaultText, text)
    }

    @Test
    fun `深色模式正文是纯白而不是灰`() {
        val text = EditorPalette.Dark.textNormal
        assertEquals(0xFFFFFFFF.toInt(), text)
        assertEquals(alpha, argbAlpha(text))
        assertNotEquals(soraDefaultText, text)
    }

    @Test
    fun `forDark 选到对应的一套`() {
        assertEquals(EditorPalette.Light, EditorPalette.forDark(dark = false))
        assertEquals(EditorPalette.Dark, EditorPalette.forDark(dark = true))
    }

    @Test
    fun `正文与底色分属明暗两端`() {
        // 亮色：底亮字暗；深色：底暗字亮 —— 反了就是「黑底黑字 / 白底白字」
        assertTrue(luminance(EditorPalette.Light.background) > luminance(EditorPalette.Light.textNormal))
        assertTrue(luminance(EditorPalette.Dark.background) < luminance(EditorPalette.Dark.textNormal))
    }

    @Test
    fun `两套配色的底色不同且都不透明`() {
        assertNotEquals(EditorPalette.Light.background, EditorPalette.Dark.background)
        assertEquals(alpha, argbAlpha(EditorPalette.Light.background))
        assertEquals(alpha, argbAlpha(EditorPalette.Dark.background))
    }

    @Test
    fun `行号栏与画布同色避免色带`() {
        assertEquals(EditorPalette.Light.background, EditorPalette.Light.lineNumberBackground)
        assertEquals(EditorPalette.Dark.background, EditorPalette.Dark.lineNumberBackground)
    }

    // ───────────────────── 只读 / 预览态：一个正文类颜色都不能漏 ─────────────────────

    @Test
    fun `正文类颜色在两种模式下都是纯黑与纯白`() {
        EditorPalette.PureTextIds.forEach { id ->
            assertEquals("浅色模式 id=$id 必须是纯黑", EditorPalette.PURE_BLACK, lightMap[id])
            assertEquals("深色模式 id=$id 必须是纯白", EditorPalette.PURE_WHITE, darkMap[id])
        }
    }

    @Test
    fun `覆盖清单包含全部正文类 id 且不重复`() {
        val ids = EditorPalette.Light.assignments().map { it.first }
        assertEquals(ids.size, ids.toSet().size)
        // 每一份「必须纯黑/纯白」的 id 都必须真的被写进色板，否则它会退回库默认值
        assertTrue(EditorPalette.PureTextIds.all { it in ids })
        assertTrue(EditorColorScheme.TEXT_NORMAL in ids)
    }

    @Test
    fun `无语言也可见的表面类 id 一个都不能漏`() {
        // 编辑态接上真编辑器后，这些表面不依赖语法分析器就会被画出来：
        // 当前行、括号配对、行号面板、滚动条、选中文字的浮窗。
        // 漏一项就退回库默认（透明 / 深灰），只有真机上肉眼能发现。
        val ids = EditorPalette.Light.assignments().map { it.first }
        EditorPalette.VisibleSurfaceIds.forEach { id ->
            assertTrue("表面类 id=$id 必须显式覆盖", id in ids)
        }
    }

    @Test
    fun `滚动条轨道透明而滑块可见`() {
        // 轨道铺满整条边：给成不透明色就是画布上一道竖带；滑块反过来必须看得见
        assertEquals(0, lightMap[EditorColorScheme.SCROLL_BAR_TRACK])
        assertEquals(0, darkMap[EditorColorScheme.SCROLL_BAR_TRACK])
        assertNotEquals(0, lightMap[EditorColorScheme.SCROLL_BAR_THUMB])
        assertNotEquals(0, darkMap[EditorColorScheme.SCROLL_BAR_THUMB])
    }

    @Test
    fun `浮窗与行面板底色不透明且与正文分属明暗两端`() {
        // 选中文字后浮出的操作窗 / 长按行号的行面板，都是「文字压在色块上」：
        // 底色透明就会让图标与正文糊在一起；底色与正文同向则等于看不见
        listOf(
            EditorColorScheme.TEXT_ACTION_WINDOW_BACKGROUND,
            EditorColorScheme.LINE_NUMBER_PANEL,
        ).forEach { id ->
            assertEquals("浅色 id=$id 底色必须不透明", alpha, argbAlpha(lightMap.getValue(id)))
            assertEquals("深色 id=$id 底色必须不透明", alpha, argbAlpha(darkMap.getValue(id)))
            assertTrue(
                "浅色 id=$id 的底色必须比正文亮",
                luminance(lightMap.getValue(id)) > luminance(EditorPalette.Light.textNormal),
            )
            assertTrue(
                "深色 id=$id 的底色必须比正文暗",
                luminance(darkMap.getValue(id)) < luminance(EditorPalette.Dark.textNormal),
            )
        }
    }

    @Test
    fun `不再出现 Sora 默认的灰色正文`() {
        EditorPalette.PureTextIds.forEach { id ->
            assertNotEquals(soraDefaultText, lightMap[id])
            assertNotEquals(soraDefaultText, darkMap[id])
        }
    }

    @Test
    fun `只读预览与编辑态共用同一份配色`() {
        // 模块**不提供**「只读专用色板」：同一主题只有一份实例。
        // 上一版的缺陷正是「只覆盖了一部分 id」，预览态才会露出库默认的灰 —— 这里把「不分裂」钉住。
        assertSame(EditorPalette.Light, EditorPalette.forDark(false))
        assertSame(EditorPalette.Dark, EditorPalette.forDark(true))
    }

    @Test
    fun `语法令牌保留主题色而不是被刷成黑白`() {
        // 注释 / 关键字天然有色（注释偏暗是语法语义，不是渲染缺陷）；
        // 把整块色板刷成黑白会让高亮失去意义，所以这里反向钉一条。
        assertNotEquals(EditorPalette.PURE_BLACK, lightMap[EditorColorScheme.COMMENT])
        assertNotEquals(EditorPalette.PURE_BLACK, lightMap[EditorColorScheme.KEYWORD])
        assertNotEquals(EditorPalette.PURE_WHITE, darkMap[EditorColorScheme.COMMENT])
    }
}
