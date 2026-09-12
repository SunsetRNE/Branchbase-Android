package com.branchbase.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编辑器配色的单测。
 *
 * 这些用例钉的是**用户可见的契约**（不是实现细节）：
 * - 亮色模式正文必须是**深黑**，深色模式必须是**亮白**；
 * - 两套配色的正文都不能再是 Sora 默认的 `#333333`（那正是「渲染成灰色」的根因）；
 * - 正文与底色必须分属明暗两端（否则文字会糊在背景里）。
 *
 * 之所以值得测：颜色错了编译不会报、跑起来不崩，只有真机上肉眼才能发现。
 */
class EditorPaletteTest {

    /** Sora `EditorColorScheme` 的默认正文色（不分明暗），即「发灰」的来源。 */
    private val soraDefaultText = 0xFF333333.toInt()

    private val alpha = 0xFF

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
}
