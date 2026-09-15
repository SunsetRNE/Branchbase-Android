package com.branchbase.ui.profile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「`Crossfade` 的 lambda 里必须先套一层 `Column`」的结构性钉子（源码级）。
 *
 * ## 为什么需要它
 *
 * `Crossfade` 的实现是 `Box { 每个状态各一层 }` —— 交给它的**多个子元素不会纵向排列**，
 * 而是在同一坐标上叠着画。1.0.49 把三处「多子元素」的块塞进 Crossfade 之后，真机上动态页整片错版：
 * 贡献墙的图例盖在网格上、「活动类型分布 / 活动热力 / 最近活动」三段叠成一团。
 *
 * 这类 bug **不报错、不崩溃**：当时 14 条单测全绿、`:app:assembleDebug` 也绿，只有真机截图看得出来。
 * JVM 单测里没有 Compose 运行时（渲染不了布局），所以和 `BackConsumptionTest` 一样，
 * 把规则钉在**源码形状**上：`Crossfade(...) { ->` 之后的第一行代码必须是 `Column(`。
 *
 * ## 规则的两条边界
 *
 * - 允许 lambda 体与箭头同行（`) { isLoading -> Column(...) {`）；
 * - 跳过空行与 `//` 注释行（Crossfade 的用法说明本来就写在 lambda 里面）。
 */
class CrossfadeLayoutTest {

    private fun offenders(path: String, text: String): List<String> {
        val lines = text.lines()
        val bad = mutableListOf<String>()
        lines.forEachIndexed { i, raw ->
            val line = raw.trimStart()
            // 注释里的 Crossfade 说明不算调用点
            if (line.startsWith("*") || line.startsWith("//") || line.startsWith("/*")) return@forEachIndexed
            if (!line.contains("Crossfade(")) return@forEachIndexed

            // 调用可能跨多行，lambda 箭头往后找（12 行足够覆盖参数列表）
            var arrowLine = -1
            var inlineBody = ""
            for (j in i until minOf(i + 12, lines.size)) {
                val idx = lines[j].indexOf("->")
                if (idx >= 0) {
                    arrowLine = j
                    inlineBody = lines[j].substring(idx + 2).trim()
                    break
                }
            }
            if (arrowLine < 0) {
                bad += "$path:${i + 1} Crossfade 之后 12 行内没有 lambda 箭头（写法变了？请同步更新本钉子）"
                return@forEachIndexed
            }
            if (inlineBody.startsWith("Column(")) return@forEachIndexed
            if (inlineBody.isNotEmpty()) {
                bad += "$path:${arrowLine + 1} lambda 体不是以 Column( 开头：${inlineBody.take(48)}"
                return@forEachIndexed
            }
            var k = arrowLine + 1
            while (k < lines.size && (lines[k].isBlank() || lines[k].trimStart().startsWith("//"))) k++
            val first = lines.getOrNull(k)?.trimStart().orEmpty()
            if (!first.startsWith("Column(")) {
                bad += "$path:${k + 1} Crossfade 的 lambda 体第一行不是 Column(：${first.take(48)}"
            }
        }
        return bad
    }

    @Test
    fun `Crossfade 的 lambda 体必须先套一层 Column`() {
        val sources = File("src/main/java")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.path to it.readText() }
            .toList()
        assertTrue("没扫到源文件（单测工作目录应为 app 模块根）", sources.isNotEmpty())

        val bad = sources.flatMap { (path, text) -> offenders(path, text) }
        assertEquals(
            "Crossfade 的内容落在 Box 里，多子元素会叠着画（真机上表现为整页错版），" +
                "lambda 体第一行必须是 Column(Modifier.fillMaxWidth()) { … }。违规：$bad",
            emptyList<String>(),
            bad,
        )
    }

    @Test
    fun `钉子确实覆盖到了已知的 Crossfade 调用点`() {
        // 防止「规则写错了但恰好没匹配到任何调用点」这种假绿：
        // 目前全项目 3 处（RegionSwap、动态页活动区、贡献墙）。
        val count = File("src/main/java")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sumOf { file ->
                file.readLines().count { line ->
                    val t = line.trimStart()
                    !t.startsWith("*") && !t.startsWith("//") && t.contains("Crossfade(")
                }
            }
        assertTrue("Crossfade 调用点数量意外（当前应有 3 处，实际 $count）—— 规则可能已失效", count >= 3)
    }
}
