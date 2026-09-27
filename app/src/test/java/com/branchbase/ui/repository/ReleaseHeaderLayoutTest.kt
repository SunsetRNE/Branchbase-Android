package com.branchbase.ui.repository

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「发布编辑页的分组头（`ReleaseGroupHeader`）是一行定高 20dp，里面不许有会换行的文字」的结构性钉子。
 *
 * 背景（真机缺陷）：这一行原来是
 *
 * ```
 * Text(title) ; Text("  ·  $counter") ; Spacer(Modifier.weight(1f)) ; trailing()
 * ```
 *
 * Row 给**非加权**子项的宽度是「剩余宽度」（逐个子项扣减），而计数文案是
 * `label_lines_chars`（「N 行 · M 字符」，随正文长度增长）：窄屏或大字体下它会被挤成两行（约 28dp），
 * 却因为 `verticalAlignment = CenterVertically` 被塞进 `height(20.dp)` 的盒子里 —— 上下各溢出约 4dp，
 * 画到相邻发丝线和下一个分组上（Compose 不裁剪溢出子项）。这就是用户报的「渲染坐标导致的 UI 重叠」。
 *
 * 修法与不变量（两条一起成立才不会再叠）：
 *
 * 1. 标题与计数**必须** `maxLines = 1` + `TextOverflow.Ellipsis` —— 放不下就省略号，不折行；
 * 2. 两者包进一个 `Modifier.weight(1f)` 的**内层 Row**，右侧动作（非加权子项）因此先拿到自己的固有宽度，
 *    剩下的才给文字；
 * 3. 右侧动作自己的标签（`ReleaseHeaderAction`）也必须 `maxLines = 1` + Ellipsis —— 动作行比整行还宽时
 *    （大字体 / 长标签）默认 `softWrap` 会把它折成两行，同样溢出定高盒子。文字侧与动作侧是同一个缺陷的两半。
 *
 * 这类缺陷只有真机肉眼能看出来，JVM 单测里没有 Compose 运行时，所以按 `FileEditorWiringTest` 的套路钉在源码上。
 */
class ReleaseHeaderLayoutTest {

    private val partsPath = "src/main/java/com/branchbase/ui/repository/ReleaseEditParts.kt"

    private fun source(path: String): String {
        val file = File(path)
        assertTrue("找不到源文件：${file.absolutePath}（单测工作目录应为 app 模块根）", file.exists())
        return file.readText()
    }

    /** 去掉 import 与注释行：注释里正好写着反例（`Spacer(Modifier.weight(1f))`），不能算进正文。 */
    private fun code(path: String): String =
        source(path).lines()
            .filterNot { it.trimStart().startsWith("import") }
            .filterNot { it.trimStart().startsWith("//") }
            .filterNot { it.trimStart().startsWith("*") }
            .filterNot { it.trimStart().startsWith("/*") }
            .joinToString("\n")

    /** `ReleaseGroupHeader` 的函数体（从头一个 `) {` 起做大括号配对，避免误取参数里的 `trailing` 默认值 `{}`）。 */
    private fun headerBody(): String = bodyOf("internal fun ReleaseGroupHeader(")

    /** `ReleaseHeaderAction` 的函数体（同一个套路；参数表里的 `() -> Unit` 没有 `{`，所以头一个 `) {` 就是函数体）。 */
    private fun actionBody(): String = bodyOf("internal fun ReleaseHeaderAction(")

    /** 按函数签名取出函数体：从头一个 `) {` 起做大括号配对。 */
    private fun bodyOf(signature: String): String {
        val text = code(partsPath)
        val funStart = text.indexOf(signature)
        assertTrue("`ReleaseEditParts.kt` 里找不到 `$signature`", funStart >= 0)
        val bodyOpen = text.indexOf(") {", funStart)
        assertTrue("`$signature` 的参数表没闭合成 `) {`", bodyOpen >= 0)
        var depth = 0
        for (i in bodyOpen + 2 until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(bodyOpen + 2, i + 1)
                }
            }
        }
        throw AssertionError("`$signature` 的函数体没闭合")
    }

    @Test
    fun `定高二十 dp 的分组头里每个文字都必须单行`() {
        val body = headerBody()
        assertTrue(
            "分组头仍是 height(20.dp) 的定高行；里面一旦有字折行就会画到下面去（当前函数体：\n$body）",
            body.contains("height(20.dp)"),
        )
        val texts = Regex("\\bText\\s*\\(").findAll(body).count()
        assertEquals("分组头里有标题和计数两个 Text（当前函数体：\n$body）", 2, texts)
        assertEquals(
            "两个 Text 都必须 maxLines = 1：定高 20dp 里折行 = 溢出到相邻分组（当前：$texts 个 Text）",
            texts,
            Regex("maxLines\\s*=\\s*1").findAll(body).count(),
        )
        assertEquals(
            "两个 Text 都必须 overflow = TextOverflow.Ellipsis：放不下要收省略号，不能变宽 / 换行",
            texts,
            Regex("overflow\\s*=\\s*TextOverflow\\.Ellipsis").findAll(body).count(),
        )
    }

    @Test
    fun `标题与计数包在 weight 的内层行里，右侧动作先拿到宽度`() {
        val body = headerBody()
        assertTrue(
            "标题与计数必须包进一个 Modifier.weight(1f) 的内层 Row —— 这样右侧动作（非加权）先量到固有宽度，"
                + "剩下的宽度才给文字（当前函数体：\n$body）",
            body.contains("Row(Modifier.weight(1f)"),
        )
        val weightedRow = body.indexOf("Row(Modifier.weight(1f)")
        val trailing = body.indexOf("trailing()")
        assertTrue("分组头必须调用 trailing()（右侧动作）", trailing > 0)
        assertTrue(
            "内层文字 Row 必须在 trailing() 之前：反过来（先文字后动作）就又回到「文字膨胀、动作被顶出去」",
            weightedRow < trailing,
        )
        assertFalse(
            "内层 Row 用 weight(1f) 默认 fill = true 吃掉剩余宽度（动作贴右）；fill = false 会让动作缩在文字后面",
            body.contains("weight(1f, fill = false)"),
        )
    }

    @Test
    fun `不许再用 Spacer 在文字后面顶开右侧动作`() {
        assertFalse(
            "`Spacer(Modifier.weight(1f))` 顶开动作 = 让标题 / 计数无限膨胀，窄屏下折行溢出（就是那个重叠缺陷）",
            headerBody().contains("Spacer(Modifier.weight(1f))"),
        )
    }

    /**
     * 同一行里的另一半：`ReleaseHeaderAction` 的标签。
     *
     * 动作是非加权子项 —— 正常情况下先量到固有宽度，标签不会折；但当动作自己就比这一行宽
     * （大字体 / 长标签，比如「生成说明」在 `fontScale` 2.0 下），Row 给它的约束会小于文字固有宽度，
     * 默认 `softWrap = true` 就会**折成两行**（约 30dp），同样溢出 20dp 的定高盒子。
     * 所以标签也必须 `maxLines = 1` + Ellipsis：宁可收省略号，不许换行。
     */
    @Test
    fun `右侧动作的标签也必须单行`() {
        val body = actionBody()
        val texts = Regex("\\bText\\s*\\(").findAll(body).count()
        assertEquals("动作里只有标签一个 Text（当前函数体：\n$body）", 1, texts)
        assertEquals(
            "动作标签必须 maxLines = 1：放不下要收省略号，不能折行溢出定高分组头（当前：$texts 个 Text）",
            texts,
            Regex("maxLines\\s*=\\s*1").findAll(body).count(),
        )
        assertEquals(
            "动作标签必须 overflow = TextOverflow.Ellipsis（当前函数体：\n$body）",
            texts,
            Regex("overflow\\s*=\\s*TextOverflow\\.Ellipsis").findAll(body).count(),
        )
    }
}
