package com.branchbase.ui.decision

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「决策页的反馈行不能硬插入」的结构性钉子。
 *
 * 背景（2026-11-24）：`FeedbackLine` 此前就是一个 `Text` —— 运行中 / 失败原因 / 需要处理
 * 这些**异步动作的结果**一出现，下面的按钮当帧下移，读起来像「页面跳了一下」。
 * 现在统一走 `revealEnter()`（展开 + 淡入，220ms）。Compose 运行时在 JVM 单测里没有，
 * 所以按仓库既有套路钉源码。
 *
 * 钉三条：出现必须走 `AnimatedVisibility`、节奏必须取元素级规格、首帧必须翻 true
 * （最后一条最容易写错成「永远是终态、动画不播」）。
 */
class DecisionFeedbackMotionTest {

    private val path = "src/main/java/com/branchbase/ui/decision/SyncDecisionScreens.kt"

    private fun source(): String {
        val file = File(path)
        assertTrue("找不到源文件：${file.absolutePath}（单测工作目录应为 app 模块根）", file.exists())
        return file.readText()
    }

    @Test
    fun `反馈行出现必须走展开淡入`() {
        val src = source()
        val at = src.indexOf("internal fun FeedbackLine(")
        assertTrue("找不到 FeedbackLine（被改名或搬走了？）", at >= 0)
        val body = src.substring(at).substringBefore("\n}")

        assertTrue(
            "FeedbackLine 必须用 AnimatedVisibility 出现 —— 否则就是当帧插入一行字，" +
                "下面的按钮与说明会跟着跳",
            body.contains("AnimatedVisibility("),
        )
        assertTrue(
            "出现节奏必须取元素级规格 `revealEnter()` —— 各页自定时长会让反馈行比周围动效快半拍",
            body.contains("revealEnter()"),
        )
        assertTrue(
            "首次组合必须把 visible 翻成 true（`LaunchedEffect(Unit) { shown = true }`）—— " +
                "直接写 true 的话首帧已经是终态，动画根本不会播",
            body.contains("LaunchedEffect(Unit) { shown = true }"),
        )
    }
}
