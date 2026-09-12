package com.branchbase.translate

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页面启动脚本的「总开关」回归钉子。
 *
 * 背景（用户反馈的缺陷）：在设置里关掉「自动翻译正文」之后，打开仓库页等正文页，
 * 悬浮球**依然出现**。根因是 `boot()` 里的
 * `if (state.auto || saved === '1') on();` —— 页内开关被持久化在 `localStorage`，
 * 于是「设置关掉」之后它仍会把翻译拉起来；这还与脚本自己写的
 * 「设置是用户的明确意图，不该被一次临时开关覆盖」直接矛盾。
 *
 * 现在总开关只有一处（设置里的「自动翻译正文」），页内开关只对当前页面有效。
 * 模块里没有 JS 运行时，所以与 [TranslatePageDomTest] 同一套路：把规则钉在脚本源码上 ——
 * 谁把「上次的页内开关」加回 boot()，单测立刻红。
 */
class TranslateBootScriptTest {

    private fun bootScript(): String {
        val file = File("src/main/assets/translate/03-boot.js")
        assertTrue(
            "找不到页面脚本：${file.absolutePath}（单测工作目录应为 translate 模块根）",
            file.exists(),
        )
        return file.readText()
    }

    @Test
    fun `启动是否翻译只由总开关决定`() {
        val js = bootScript()
        assertTrue("boot() 必须以 state.auto（设置里的总开关）为唯一判据", js.contains("if (state.auto) on();"))
    }

    @Test
    fun `页内开关不得持久化到 localStorage`() {
        val js = bootScript()
        assertFalse(
            "页内开关一旦持久化，设置里关掉后仍会被它拉回开启（悬浮球不受控制的根因）",
            js.contains("localStorage.getItem("),
        )
        assertFalse(
            "同理，不能再把页内开关写进 localStorage",
            js.contains("localStorage.setItem("),
        )
        assertFalse("旧的持久化键必须彻底消失", js.contains("bb_translate_on'"))
    }
}
