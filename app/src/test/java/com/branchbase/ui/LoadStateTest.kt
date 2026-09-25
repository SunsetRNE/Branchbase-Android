package com.branchbase.ui

import com.branchbase.ui.theme.skeletonRowsFor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「三态判定」与「等高占位」的纯函数单测。
 *
 * 为什么值得钉：这两条都是**只在真机上看得出来**的界面规则 ——
 * 三态判错的表现是「明明有内容却说没有」（或反过来永远转圈），
 * 行数算错的表现是「骨架没铺满、内容一到又撑高」，两者都不会编译报错、也不会让页面崩。
 */
class LoadStateTest {

    @Test
    fun `加载中优先于手上已有的旧数据`() {
        // 缓存直出之后回源：手上有一份旧数据，但这一轮仍然是 Loading ——
        // 判据不是「列表空不空」，否则「回源中」会被当成「已完成」
        assertEquals(LoadState.Loading, loadStateOf(loading = true, count = 0))
        assertEquals(LoadState.Loading, loadStateOf(loading = true, count = 7))
    }

    @Test
    fun `加载完成后空与有内容必须分开`() {
        assertEquals(LoadState.Empty, loadStateOf(loading = false, count = 0))
        assertEquals(LoadState.Ready, loadStateOf(loading = false, count = 1))
        assertEquals(LoadState.Ready, loadStateOf(loading = false, count = 100))
    }

    @Test
    fun `占位行数按区域高度算且至少一行`() {
        // 330dp 的盒子、34dp 行高 → 9 行（9*34 = 306 ≤ 330，10 行会超出）
        assertEquals(9, skeletonRowsFor(areaDp = 330, rowDp = 34))
        // 带行距时按「行高 + 行距」为一步：330 放得下 9 行 34+2（9*34+8*2 = 322 ≤ 330）
        assertEquals(9, skeletonRowsFor(areaDp = 330, rowDp = 34, gapDp = 2))
        // 40+2 时只能放 7 行（7*40+6*2 = 292 ≤ 330，而 8 行要 334 > 330）
        assertEquals(7, skeletonRowsFor(areaDp = 330, rowDp = 40, gapDp = 2))
        // 区域小于一行时也要给一行（否则占位区是空的，等于没占位）
        assertEquals(1, skeletonRowsFor(areaDp = 10, rowDp = 34))
        assertEquals(1, skeletonRowsFor(areaDp = 0, rowDp = 0))
        // 正好整除：不留一行空白
        assertEquals(10, skeletonRowsFor(areaDp = 340, rowDp = 34))
    }
}
