package com.branchbase.ui.log

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 启动段「可归因」的结构性钉子（源码级，套路同 `FileEditorWiringTest`）。
 *
 * ## 为什么这件事需要钉子
 *
 * 慢帧的「页面」注脚 = **最近一条 UI 类日志**（[LogManager.lastUiMessage]，调用点见 `FrameWatch`），
 * 而启动段在 1.0.64 之前**只有两条 UI 日志**（`App 启动`、`git TLS 证书初始化完成`）——
 * 于是真机日志里 14 次启动、14 条启动慢帧（115~266ms，等待段占 82~190ms）**注脚全是同一句**
 * 「git TLS 证书初始化完成」，而它只是启动过程中打的一条日志，后面还有 1.2~2.9 秒的帧
 * 全被归到它头上。整段最有价值、最好修的一段，恰恰是日志里最不可归因的一段。
 *
 * 修法是两侧一起改，**任何一侧单独失效都不会报错，只会让报表静默少一行**：
 *
 * 1. 启动路径上打 `启动 ▸ <阶段>` 阶段标记（Application 装配 / 日志初始化 / 首选项首载 /
 *    JNI+证书 / 首次组合 / 首页取数），外加 `FrameWatch` 的收尾标记 `启动 ■ 首帧已上屏`
 *    —— 没有收尾标记，启动注脚会一直「粘」到交互段；
 * 2. `tools/perf/frame-baseline.py` 的 `SCENARIO_RULES` 里必须有 `^启动` 桶，且**排在最前**
 *    —— 否则这些帧会落进「其它」，与改之前一样不可比。
 *
 * 另外两条容易踩的：
 * - 标记**不能**撞上现有场景前缀（`进入/打开/切换到「/…`）：撞了就会被算进「层级推进 / 返回」
 *   或「Tab 同级切换」桶，报表看起来一切正常，而数字是错的；
 * - 首帧标记的 tag **不能**是 `帧`（`FrameWatch.TAG`）—— 慢帧自己打的日志会被
 *   `lastUiMessage(excludeTag = TAG)` 排掉，那样它当不了注脚，收尾也就白做了。
 */
class StartupMarkerTest {

    private fun source(path: String): String {
        val file = File(path)
        assertTrue("找不到文件：${file.absolutePath}（单测工作目录应为 app 模块根）", file.exists())
        return file.readText()
    }

    /** 工作目录是 app 模块根，仓库根在上一级。 */
    private fun repoFile(path: String): String = source("../$path")

    private val app = "src/main/java/com/branchbase/BranchbaseApp.kt"
    private val activity = "src/main/java/com/branchbase/MainActivity.kt"
    private val home = "src/main/java/com/branchbase/ui/home/HomeScreen.kt"
    private val frameWatch = "src/main/java/com/branchbase/ui/log/FrameWatch.kt"

    /** 六个阶段标记：缺哪个，那一段就重新变回「git TLS 证书初始化完成」。 */
    private val stageMarkers = listOf(
        "启动 ▸ 日志初始化（建目录 / 清历史）" to app,
        "启动 ▸ 应用装配（Application.onCreate）" to app,
        "启动 ▸ 首选项首次加载（整份 XML 在主线程解析）" to activity,
        "启动 ▸ JNI 库与 git 证书（loadLibrary + 190KB CA）" to activity,
        "启动 ▸ 首次组合：恢复会话 / 登记账号 / 首页取数" to activity,
        "启动 ▸ 首页首帧取数（L2 缓存 + 星标/通知解析）" to home,
    )

    @Test
    fun `六个启动阶段标记都要在`() {
        stageMarkers.forEach { (marker, path) ->
            assertTrue("$path 里少了启动阶段标记「$marker」", source(path).contains(marker))
        }
    }

    @Test
    fun `首帧收尾标记要在_且tag不是帧`() {
        val code = source(frameWatch).lines().filterNot { it.trimStart().startsWith("import") }.joinToString("\n")
        assertTrue("FrameWatch 要打收尾标记，否则启动注脚会粘到交互段", code.contains("启动 ■ 首帧已上屏"))

        val tag = Regex("""STARTUP_TAG\s*=\s*"([^"]+)"""")
            .find(source(frameWatch))?.groupValues?.get(1)
        assertEquals(
            "首帧标记必须用「启动」tag：用「帧」会被 lastUiMessage(excludeTag=TAG) 排掉，当不了注脚",
            "启动",
            tag,
        )
        assertTrue("「帧」是慢帧自己的 tag，不能混用", tag != "帧")
    }

    @Test
    fun `启动标记不许撞上其它场景桶的前缀`() {
        // 与 frame-baseline.py 的 SCENARIO_RULES 保持一致（下面那条用例会校验脚本本身）
        val otherBuckets = listOf(
            Regex("^切换到「"),
            Regex("^进入消息页"),
            Regex("^(进入|打开|展开|返回|关闭|退出|收起)"),
            Regex("(气泡|菜单|More|⋮)"),
        )
        (stageMarkers.map { it.first } + "启动 ■ 首帧已上屏").forEach { marker ->
            otherBuckets.forEach { rule ->
                assertTrue(
                    "「$marker」会命中场景规则 ${rule.pattern} —— 它会被算进别的桶，报表数字就错了",
                    !rule.containsMatchIn(marker),
                )
            }
        }
    }

    @Test
    fun `取数脚本要有独立的启动桶_且排在其它规则前面`() {
        val script = repoFile("tools/perf/frame-baseline.py")
        val marker = script.indexOf("\"App 启动\", re.compile(r\"^启动\")")
        assertTrue("frame-baseline.py 的 SCENARIO_RULES 里少了 ^启动 桶：启动帧会落进「其它」", marker > 0)
        listOf("\"Tab 同级切换（含重建）\"", "\"层级推进 / 返回\"", "\"气泡 / 菜单\"", "\"其它\"").forEach { later ->
            assertTrue(
                "启动桶必须排在 $later 前面（后面的规则更宽，先匹配到的赢）",
                marker < script.indexOf(later),
            )
        }
    }
}
