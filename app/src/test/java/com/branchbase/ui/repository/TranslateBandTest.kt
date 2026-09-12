package com.branchbase.ui.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 沉浸式翻译「可见带」换算单测。
 *
 * 正文 WebView 的高度等于整篇内容高度（外层原生列表负责滚动），页面里的
 * `position: fixed` 因此钉在整篇文章的右下角 —— 长 README 里悬浮球会落到文末。
 * 悬浮球/面板改用绝对定位，位置由这里算出的可见带决定，所以这段换算必须准：
 *
 * - 窗口坐标（px）→ 页面 CSS px（除以 density，WebView 的 CSS 像素与 dp 1:1）；
 * - 可见带 = 「允许摆放悬浮控件的窗口区域」∩「正文自身」；
 * - 正文滚出屏幕时返回**空带**（而不是 null）：页面要收到这个信号才会收起悬浮球。
 */
class TranslateBandTest {

    private val density = 2.8f          // 与真机接近的密度，方便看出「忘了除 density」的错误
    private val windowH = 2800          // 1000 CSS px 高的窗口

    @Test
    fun `正文从头显示时带子就是可见区域`() {
        // 正文上沿在窗口 y=560px（顶部栏之下），高度 2800px（=1000 CSS px）
        val band = translateBandCss(
            webViewTopPx = 560f,
            webViewHeightPx = 2800f,
            windowHeightPx = windowH,
            safeTopPx = 560f,
            safeBottomPx = 2520f,        // 底部导航条之上
            density = density,
        )
        assertNotNull(band)
        assertEquals(0f, band!!.first, 0.01f)
        assertEquals(700f, band.second, 0.01f)   // (2520-560)/2.8
    }

    @Test
    fun `正文向上滚出屏幕时带子上沿跟着下移`() {
        // 已经向下滚了 560px：WebView 上沿跑到窗口 y=0 以上（正文够高，下沿仍由安全区决定）
        val band = translateBandCss(
            webViewTopPx = -560f,
            webViewHeightPx = 5600f,
            windowHeightPx = windowH,
            safeTopPx = 560f,
            safeBottomPx = 2520f,
            density = density,
        )
        assertEquals(400f, band!!.first, 0.01f)    // (560-(-560))/2.8
        assertEquals(1100f, band.second, 0.01f)    // (2520+560)/2.8
    }

    @Test
    fun `带子下沿不会超出正文自身的下沿`() {
        // 正文只到窗口 y=2240（比安全下沿 2520 还高）：可见带下沿只能到正文底
        val band = translateBandCss(
            webViewTopPx = -560f,
            webViewHeightPx = 2800f,
            windowHeightPx = windowH,
            safeTopPx = 560f,
            safeBottomPx = 2520f,
            density = density,
        )
        assertEquals(400f, band!!.first, 0.01f)
        assertEquals(1000f, band.second, 0.01f)
    }

    @Test
    fun `正文整体滚出屏幕时返回空带`() {
        val band = translateBandCss(
            webViewTopPx = -4000f,
            webViewHeightPx = 2800f,
            windowHeightPx = windowH,
            safeTopPx = 560f,
            safeBottomPx = 2520f,
            density = density,
        )
        assertEquals(band!!.first, band.second, 0.01f)   // 空带：页面据此收起悬浮球
    }

    @Test
    fun `短正文不会超出自身高度`() {
        // 正文只有 280px 高（100 CSS px），可见区下沿远在其下方
        val band = translateBandCss(
            webViewTopPx = 560f,
            webViewHeightPx = 280f,
            windowHeightPx = windowH,
            safeTopPx = 560f,
            safeBottomPx = 2520f,
            density = density,
        )
        assertEquals(0f, band!!.first, 0.01f)
        assertEquals(100f, band.second, 0.01f)     // 夹到正文自身高度
    }

    @Test
    fun `正文上沿在顶部栏之下时带子上沿为 0`() {
        val band = translateBandCss(
            webViewTopPx = 1200f,
            webViewHeightPx = 2800f,
            windowHeightPx = windowH,
            safeTopPx = 560f,
            safeBottomPx = 2520f,
            density = density,
        )
        assertEquals(0f, band!!.first, 0.01f)      // (560-1200)/2.8 为负 → 夹到 0
        assertEquals(471.42f, band.second, 0.05f)  // (2520-1200)/2.8
    }

    @Test
    fun `无法测量时返回 null 而不是空带`() {
        // WebView 还没有尺寸：此时推送空带会让页面把悬浮球收起来，是错的
        assertNull(
            translateBandCss(0f, 0f, windowH, 560f, 2520f, density),
        )
        assertNull(
            translateBandCss(0f, 2800f, 0, 560f, 2520f, density),
        )
        assertNull(
            translateBandCss(0f, 2800f, windowH, 560f, 2520f, 0f),
        )
        // 安全区算反了（底部在顶部之上）也当测量失败
        assertNull(
            translateBandCss(0f, 2800f, windowH, 2520f, 560f, density),
        )
    }

    @Test
    fun `密度不同则同一段窗口对应不同的页面坐标`() {
        val at2 = translateBandCss(0f, 2800f, windowH, 0f, 2800f, 2f)!!
        val at4 = translateBandCss(0f, 2800f, windowH, 0f, 2800f, 4f)!!
        assertEquals(1400f, at2.second, 0.01f)
        assertEquals(700f, at4.second, 0.01f)
        assertTrue(at2.second > at4.second)
    }
}
