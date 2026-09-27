package com.branchbase.ui.repository

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 代码页「路径显示面板」（面包屑）的钉子。
 *
 * 背景（用户 2026-09-27 真机截图报的两件事）：
 * 1. 面包屑把 `owner/repo` 也画进去，仓库名一长（`SunsetRNE/Branchbase-Android`）整行就被它占满，
 *    真正要看的目录链反而看不见 —— 仓库名顶栏已经写着，这一行只该画目录链，**根 = `/`**；
 * 2. 路径一长不折行，`Row` 把每个 `Text` 压成最窄的一列，`Branchbase` 竖着排成 `bra/nch/bas/e`。
 *    要的是**整层**折行，折行处行尾挂 `…`、下一行接着显示。
 *
 * 这里钉三件事：
 * 1. [breadcrumbLayers] 的拆层与跳转目标（根 `/` → `""`、每层点回自己那一级、分隔符不可点）；
 * 2. [wrapCrumbs] 的折行（装不下整层挪下一行、行尾 `…`、**一层都不许丢**、超长单层独占一行）；
 * 3. 接线：列表页的 `BreadcrumbBar` 只收 `path`（不再收 `owner`/`repo`），折行交给纯函数
 *    和自定义 [androidx.compose.ui.layout.Layout]，且每一层的文字都是 `maxLines = 1`
 *    （这行代码就是「不再竖排成窄列」的钉子）。
 */
class BreadcrumbPathTest {

    private fun source(path: String): String {
        val file = File(path)
        assertTrue("找不到源文件：${file.absolutePath}（单测工作目录应为 app 模块根）", file.exists())
        return file.readText()
    }

    private fun labels(path: String): List<String> = breadcrumbLayers(path).flatMap { it.crumbs }.map { it.label }

    private fun targets(path: String): List<String?> = breadcrumbLayers(path).flatMap { it.crumbs }.map { it.target }

    // ── 1. 纯函数：拆层 ──

    @Test
    fun `仓库根只画一个斜杠`() {
        assertEquals(listOf("/"), labels(""))
        assertEquals(listOf(""), targets(""))
        // 根就是「当前目录」，用主色加粗
        assertTrue(breadcrumbLayers("").single().crumbs.single().current)
        // 多余斜杠不该多画一层
        assertEquals(listOf("/"), labels("/"))
        assertEquals(listOf("/"), labels("//"))
    }

    @Test
    fun `路径逐段展开且不再背仓库全名`() {
        assertEquals(listOf("/", "app"), labels("app"))
        assertEquals(listOf("/", "app", "/", "src"), labels("app/src"))
        assertEquals(listOf("/", "app", "/", "src", "/", "git"), labels("app/src/git"))
        // 面包屑里不许再出现 owner/repo 那一段（用户报的就是它）
        assertTrue(
            "面包屑不该再画仓库全名",
            labels("core/src").none { it.contains("/") && it != "/" },
        )
    }

    @Test
    fun `每一层都点得回自己那一级`() {
        assertEquals(listOf("", "app", null, "app/src"), targets("app/src"))
        // 分隔符不可点（`null` 就是「不可点」这个语义的载体）
        assertNull(breadcrumbLayers("app/src")[1].crumbs[0].target)
        // 最后一段是「当前目录」：加粗 + 主色，其余是链接蓝
        val crumbs = breadcrumbLayers("app/src").flatMap { it.crumbs }
        assertEquals(listOf(false, false, false, true), crumbs.map { it.current })
    }

    @Test
    fun `根与第一段同层分隔符与名字同层`() {
        // 根 `/` 和第一段目录名之间**没有**分隔符：那个 `/` 就是它俩的分隔符，
        // 拆成两层的话折行后第二行会以 `app` 开头，看起来像丢了斜杠
        val first = breadcrumbLayers("app/src").first()
        assertEquals(listOf("/", "app"), first.crumbs.map { it.label })
        // 第 k（k≥1）层是「/ + 段名」：折行时斜杠跟着名字走，下一行仍以 `/` 开头
        assertEquals(listOf("/", "src"), breadcrumbLayers("app/src")[1].crumbs.map { it.label })
    }

    @Test
    fun `逗号空格中文目录名不会被拆开`() {
        // 目录名里有空格 / 中文：一层就是一枚 token，整段进同一层
        val layers = breadcrumbLayers("a b/中文目录")
        assertEquals(listOf("/", "a b"), layers[0].crumbs.map { it.label })
        assertEquals(listOf("/", "中文目录"), layers[1].crumbs.map { it.label })
        assertEquals("a b/中文目录", layers[1].crumbs[1].target)
    }

    // ── 2. 纯函数：折行 ──

    @Test
    fun `一行装得下就不折行也不挂省略号`() {
        val lines = wrapCrumbs(listOf(20f, 20f, 20f), ellipsisWidth = 10f, maxWidth = 200f)
        assertEquals(1, lines.size)
        assertEquals(CrumbLine(0, 3, continued = false), lines.single())
    }

    @Test
    fun `装不下的层挪到下一行且行尾挂省略号`() {
        // 60 + 「…」10 = 70 > 50 ⇒ 第 3 层（20）挪到第二行；第一行行尾挂「…」
        val lines = wrapCrumbs(listOf(20f, 20f, 20f), ellipsisWidth = 10f, maxWidth = 50f)
        assertEquals(listOf(CrumbLine(0, 2, continued = true), CrumbLine(2, 3, continued = false)), lines)
    }

    @Test
    fun `最后一行不挂省略号而且一层都不丢`() {
        // 各种宽度扫一遍：行的区间必须首尾相接、正好覆盖所有层、且每行非空
        val widths = listOf(30f, 12f, 48f, 60f, 9f, 25f, 33f, 7f)
        listOf(20f, 40f, 80f, 120f, 240f, 1000f).forEach { max ->
            val lines = wrapCrumbs(widths, ellipsisWidth = 11f, maxWidth = max)
            assertEquals("max=$max：层数不齐", 0, lines.first().first)
            assertEquals("max=$max：最后一层没落到行里", widths.size, lines.last().lastExclusive)
            assertFalse("max=$max：最后一行不该挂省略号", lines.last().continued)
            lines.zipWithNext().forEach { (a, b) ->
                assertEquals("max=$max：行之间接不上", a.lastExclusive, b.first)
                assertTrue("max=$max：出现空行", a.lastExclusive > a.first)
            }
            // 不是最后一行 ⇒ 行尾必有「…」（用户口径：换行处的最后一个元素改成「…」）
            lines.dropLast(1).forEach { line ->
                assertTrue("max=$max：折行处少了省略号", line.continued)
                val used = (line.first until line.lastExclusive).sumOf { widths[it].toDouble() }
                val next = line.lastExclusive
                // 断在这里是因为「再加一层就放不下」：多一层 + 它的「…」必须超宽（不早断、不晚断）
                val room = if (next < widths.size - 1) 11.0 else 0.0
                assertTrue(
                    "max=$max：断行位置不对（$used + ${widths[next]} + $room ≤ $max）",
                    used + widths[next] + room > max,
                )
                // 单层就超宽时那一层独占一行（会被自己裁切），此时不给「…」留位是允许的
                if (line.lastExclusive - line.first > 1) {
                    assertTrue("max=$max：行尾的「…」没留位子", used + 11 <= max)
                }
            }
        }
    }

    @Test
    fun `比整行还宽的单层独占一行`() {
        val lines = wrapCrumbs(listOf(500f, 20f), ellipsisWidth = 10f, maxWidth = 100f)
        assertEquals(2, lines.size)
        assertEquals(CrumbLine(0, 1, continued = true), lines[0])
        assertEquals(CrumbLine(1, 2, continued = false), lines[1])
    }

    @Test
    fun `没有层时没有行`() {
        assertEquals(emptyList<CrumbLine>(), wrapCrumbs(emptyList(), ellipsisWidth = 10f, maxWidth = 100f))
    }

    @Test
    fun `行尾省略号的个数不会超过备下的位子`() {
        val layers = breadcrumbLayers("app/src/main/java/com/branchbase/ui/repository")
        for (width in 1..600) {
            val lines = wrapCrumbs(List(layers.size) { 40f }, ellipsisWidth = 10f, maxWidth = width.toFloat())
            assertTrue(
                "一行至多挂一枚「…」、行数又不超过层数 ⇒ layers.size - 1 枚一定够用",
                lines.count { it.continued } <= (layers.size - 1).coerceAtLeast(0),
            )
        }
    }

    // ── 3. 落位表（折行方案 → 摆哪些子项、行尾用哪枚「…」） ──

    @Test
    fun `落位表把每个子项恰好摆一次`() {
        val counts = breadcrumbLayers("app/src/main/java/com/branchbase/ui/repository").map { it.crumbs.size }
        val breaks = (counts.size - 1).coerceAtLeast(0)
        for (width in 1..600) {
            val lines = wrapCrumbs(List(counts.size) { 40f }, ellipsisWidth = 10f, maxWidth = width.toFloat())
            val placed = crumbRows(counts, lines).flatMap { it.children }
            assertEquals(
                "顺序按层往下走：不重、不漏、也不乱",
                (breaks until breaks + counts.sum()).toList(),
                placed,
            )
        }
    }

    @Test
    fun `每个折行点各用一枚省略号的位子`() {
        val counts = listOf(2, 2, 2, 2, 2)
        val rows = crumbRows(
            counts,
            listOf(CrumbLine(0, 1, continued = true), CrumbLine(1, 3, continued = true), CrumbLine(3, 5, continued = false)),
        )
        assertEquals("续行依次取位子，最后一行不留「…」", listOf<Int?>(0, 1, null), rows.map { it.ellipsisSlot })
        val slots = rows.mapNotNull { it.ellipsisSlot }
        assertEquals("位子不能复用 —— 复用就是把「…」从上一行挪走", slots.size, slots.toSet().size)
        assertTrue("位子不超过备下的 breaks 枚", slots.all { it < (counts.size - 1).coerceAtLeast(0) })
        assertEquals("第一行只放第一层（根 + 第一段）", listOf(4, 5), rows[0].children)
        assertEquals("第二行接着放第二、第三层", listOf(6, 7, 8, 9), rows[1].children)
        assertEquals("最后一行放完剩下的层", listOf(10, 11, 12, 13), rows[2].children)
    }

    @Test
    fun `只有一层时既没有折行也没有省略号的位子`() {
        val rows = crumbRows(listOf(1), listOf(CrumbLine(0, 1, continued = false)))
        assertEquals(listOf(CrumbRow(listOf(0), null)), rows)
    }

    // ── 4. 接线：列表页的面包屑 ──

    @Test
    fun `面包屑不再收 owner repo`() {
        val list = source("src/main/java/com/branchbase/ui/repository/RepositoryListScreens.kt")
        assertTrue(
            "BreadcrumbBar 只该收 path（仓库名在顶栏，不在这条路径里）",
            list.contains("private fun BreadcrumbBar(path: String, onNavigate: (String) -> Unit)"),
        )
        assertFalse("不该再收 owner", list.contains("private fun BreadcrumbBar(owner: String"))
        assertFalse("旧的调用点也不该还在", list.contains("BreadcrumbBar(owner, repo, path"))
        assertTrue("调用点同步", list.contains("BreadcrumbBar(path, onNavigate)"))
        assertTrue("拆层走纯函数", list.contains("remember(path) { breadcrumbLayers(path) }"))
    }

    @Test
    fun `折行交给自定义 Layout 与纯函数`() {
        val list = source("src/main/java/com/branchbase/ui/repository/RepositoryListScreens.kt")
        assertTrue("要用 Layout 自己量自己摆（Row 会把目录名压成竖排）", list.contains("private fun CrumbFlow("))
        assertTrue("折行方案走纯函数", list.contains("wrapCrumbs(layerWidths.toList()"))
        assertTrue("折行处行尾挂省略号", list.contains("crumbRows("))
        assertFalse("「…」的位子计数只许待在纯函数里", list.contains("slot++"))
        assertTrue("同一层里已量的宽度要扣掉（否则整层超出被裁）", list.contains("Constraints(maxWidth = room)"))
        assertTrue("每层都点得回自己那一级", list.contains("onNavigate(crumb.target)"))
        assertTrue("分隔符与「当前目录」的配色要分开", list.contains("crumb.target == null"))
        assertTrue("当前目录加粗", list.contains("if (crumb.current) FontWeight.SemiBold"))
    }

    @Test
    fun `每个可能的折行点各备一枚省略号`() {
        val list = source("src/main/java/com/branchbase/ui/repository/RepositoryListScreens.kt")
        val models = source("src/main/java/com/branchbase/ui/repository/RepositoryModels.kt")
        val start = list.indexOf("private fun CrumbFlow(")
        assertTrue("找不到 CrumbFlow", start >= 0)
        val body = list.substring(start).substringBefore("\n}\n")
        assertTrue(
            "折行最多 layers.size - 1 次，位子得备够",
            list.contains("val breaks = (layers.size - 1).coerceAtLeast(0)"),
        )
        assertTrue("位子先占掉前 breaks 个子项", body.contains("repeat(breaks) {"))
        assertTrue("前 breaks 个子项不属于任何层", body.contains("var child = breaks"))
        assertTrue("摆哪些子项、用哪枚「…」交给纯函数", body.contains("val rows = crumbRows(layers.map { it.crumbs.size }, lines)"))
        assertTrue("落位表怎么说就怎么摆", body.contains("row.children.forEach { ci ->"))
        assertTrue("行尾的「…」从落位表里取位子", body.contains("row.ellipsisSlot?.let { slot ->"))
        assertTrue("一枚 placeable 只能摆一次：按顺序取位子", body.contains("placeables[slot].place(ellipsisX, y)"))
        assertTrue("用掉的位子往下顺延（在纯函数里）", models.contains("CrumbRow(children, if (line.continued) slot++ else null)"))
        assertTrue(
            "单层就超宽的那一行已经自带框架裁出的「…」，别再叠一枚、也别溢到屏幕外",
            body.contains("ellipsisX + placeables[slot].width <= maxPx"),
        )
        assertFalse(
            "共用一枚「…」时第二次摆放是把它挪走，前面几行会空一块",
            body.contains("placeables[0].place("),
        )
        assertTrue(
            "只有一层时没有位子，也就不能去摸 0 号 placeable",
            body.contains("if (breaks > 0) (placeables[0].width + gapPx).toFloat() else 0f"),
        )
    }

    @Test
    fun `每枚目录名的文字都是单行`() {
        val list = source("src/main/java/com/branchbase/ui/repository/RepositoryListScreens.kt")
        val start = list.indexOf("private fun CrumbFlow(")
        assertTrue("找不到 CrumbFlow", start >= 0)
        val body = list.substring(start).substringBefore("\n}\n")
        assertTrue("一层就是一枚不可再折的 token：不许竖排成窄列", body.contains("maxLines = 1"))
        assertTrue("超长目录名按行宽裁切而不是挤扁", body.contains("overflow = TextOverflow.Ellipsis"))
        assertFalse("旧的 Row 版容器会把每段压成竖排", body.contains("\n    Row(\n"))
    }
}
