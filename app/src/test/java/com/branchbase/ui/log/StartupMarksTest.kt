package com.branchbase.ui.log

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 启动标记的进程级闸门单测。
 *
 * ## 为什么这个闸门需要存在（两次都栽在同一件事上）
 *
 * 启动标记是给慢帧当注脚用的（注脚 = 最近一条 UI 类日志），所以它必须**只属于启动**：
 * `frame-baseline.py` 的 `^启动` 场景桶会按前缀把这些帧归到「App 启动」里。
 *
 * - 1.0.65：首页那条标记无条件打 → 切回首页就重跑 effect，日志里 +20s / +22s / +27s 各一次；
 * - 1.0.67：改成 `resumeTick == 1`（首次组合）→ 真机日志 +116s 又打了一次 ——
 *   `resumeTick` 是 `remember` 出来的，**页面一被重建它就从 1 重来**。
 *
 * 两次的后果一样：之后几帧的慢帧注脚全变成「启动 ▸ …」，报表看着正常、桶是错的。
 * 进程级的「打过没有」不受页面生命周期影响，是唯一可靠的判据。
 *
 * ⚠️ 这里能测是因为 [StartupMarks] **不碰 Android**（纯内存集合）。
 */
class StartupMarksTest {

    @Test
    fun firstTimeOnlyOnce() {
        val key = "test-key-${System.nanoTime()}"
        assertTrue("第一次要放行", StartupMarks.firstTime(key))
        assertFalse("同一个 key 第二次必须拦住（页面重建不该让标记复活）", StartupMarks.firstTime(key))
        assertFalse(StartupMarks.firstTime(key))
    }

    @Test
    fun keysAreIndependent() {
        val a = "a-${System.nanoTime()}"
        val b = "b-${System.nanoTime()}"
        assertTrue(StartupMarks.firstTime(a))
        assertTrue("另一个阶段标记不许被连坐", StartupMarks.firstTime(b))
        assertFalse(StartupMarks.firstTime(a))
    }
}
