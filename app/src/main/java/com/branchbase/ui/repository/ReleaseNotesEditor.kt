package com.branchbase.ui.repository

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.ui.theme.CodeSyntax
import com.branchbase.ui.theme.Primer

/**
 * 发布说明编辑器：**行标识槽 + 无框正文**。
 *
 * ## 为什么不用带框的输入框
 *
 * 上一版是 `MarkdownBodyField`：Gray100 底 + 1dp 描边 + 10dp 圆角 + 固定 200dp。
 * 同一屏里单行字段早就改成「只有一条底线」了，正文却还包着一圈框 —— 两套语言，且空内容也占 200dp。
 * 现在借编辑器那套「行号 + **无边框**」的语言：边界交给行号槽与留白，高度随内容长
 * （3 行起步、330dp 封顶后内部滚动）。
 *
 * ## 行号槽的三条硬约束（也是这个组件最容易写错的地方）
 *
 * 1. **一条逻辑行折行成多行时，行号只占第一视觉行的高度**，后面的视觉行留白。
 *    这里用 [TextLayoutResult] 逐行量（`getLineForOffset` → `getLineTop`），
 *    等价于 sora 在 `BranchbaseCodeEditor` 里自己算的那套几何；**不要**按 `\n` 数了就当一行高。
 * 2. **标记列定宽**（[MARKER_W]）：`+` 出现/消失时数字轴不动 —— 与消息卡片「行首恒为 32dp 识别槽」
 *    是同一个约束（`README.md:804-821`）。
 * 3. **当前行底色不覆盖行号槽**：底色画在正文自己的 `drawBehind` 里（正文是 Row 的第二个子项），
 *    编辑器里也是槽底色后绘、盖住整行高亮（`EditorRenderer.java:595`）。
 *
 * ## 与编辑器的两处有意偏离
 *
 * - 正文**不用等宽**：这里是中文说明文，等宽会把中英混排拉散；只借「行标识」不借「等宽」；
 * - **不画分隔竖线**：编辑器侧是 `setDividerWidth(0f)`（`BranchbaseCodeEditor.kt:140`），
 *   真画一条竖线就等于又加了一圈边框。
 */
@Composable
fun ReleaseNotesEditor(
    text: String,
    onTextChange: (String) -> Unit,
    /** 1-based：哪几行是「生成说明」写进来的（见 [generatedLineIndices]）。 */
    generatedLines: Set<Int>,
    modifier: Modifier = Modifier,
) {
    var field by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    // 外部改写正文（生成说明 / 丢弃生成行）时把编辑值跟过去；用户自己敲字时两者本就相等，不会打断光标
    LaunchedEffect(text) {
        if (field.text != text) field = TextFieldValue(text, TextRange(text.length))
    }

    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val density = LocalDensity.current
    val scroll = rememberScrollState()

    val lineStarts = remember(text) { lineStartOffsets(text) }
    val currentLine = remember(field.selection.start, text) { lineOfOffset(text, field.selection.start) }

    // 底色要在 drawBehind（非 composable 的 lambda）里用，所以先取值再进 lambda
    val currentWash = Primer.Gray100
    val addWash = Primer.SuccessSurface
    val lineNoColor = CodeSyntax.LineNo
    val currentNoColor = Primer.TextPrimary
    val markerColor = Primer.SuccessTextStrong
    val caretColor = Primer.Blue500
    val bodyColor = Primer.TextPrimary
    val hintColor = Primer.TextTertiary
    val fallbackPx = with(density) { LINE_H_DP.roundToPx().toFloat() }
    // 行高由 TextLayoutResult 以 px 给出，换算成 dp 直接除 density（绕开 Float.toDp 的重载歧义）
    val densityValue = density.density

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MIN_H, max = MAX_H)
            .verticalScroll(scroll),
    ) {
        // ── 行号槽 ──
        Column(Modifier.width(GUTTER_W)) {
            lineStarts.forEachIndexed { index, start ->
                val height = layout?.lineHeightOf(start, lineStarts.getOrNull(index + 1)) ?: fallbackPx
                Box(Modifier.fillMaxWidth().height((height / densityValue).dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        // 标记列：定宽占位，数字不会因为 + 的出现而左右移动
                        Text(
                            text = if (index + 1 in generatedLines) "+" else "",
                            fontSize = 11.sp,
                            lineHeight = LINE_H,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = markerColor,
                            textAlign = TextAlign.Start,
                            maxLines = 1,
                            modifier = Modifier.width(MARKER_W).padding(start = 4.dp),
                        )
                        Text(
                            text = "${index + 1}",
                            fontSize = 11.sp,
                            lineHeight = LINE_H,
                            fontFamily = FontFamily.Monospace,
                            color = if (index + 1 == currentLine) currentNoColor else lineNoColor,
                            fontWeight = if (index + 1 == currentLine) FontWeight.SemiBold else FontWeight.Normal,
                            textAlign = TextAlign.End,
                            maxLines = 1,
                            modifier = Modifier.weight(1f).padding(end = 5.dp),
                        )
                    }
                }
            }
        }

        // ── 正文（无框） ──
        BasicTextField(
            value = field,
            onValueChange = {
                field = it
                onTextChange(it.text)
            },
            textStyle = TextStyle(fontSize = 13.sp, lineHeight = LINE_H, color = bodyColor),
            cursorBrush = SolidColor(caretColor),
            onTextLayout = { layout = it },
            modifier = Modifier
                .weight(1f)
                .heightIn(min = MIN_H)
                .drawBehind {
                    val result = layout ?: return@drawBehind
                    // 生成行：整行淡绿（复用日志视图「新增行」的语义色）
                    generatedLines.forEach { line ->
                        val start = lineStarts.getOrNull(line - 1) ?: return@forEach
                        drawRect(
                            color = addWash,
                            topLeft = Offset(0f, result.lineTopOf(start)),
                            size = Size(size.width, result.lineHeightOf(start, lineStarts.getOrNull(line))),
                        )
                    }
                    // 当前行：光标所在行（不覆盖行号槽 —— 这里是正文自己的坐标系）
                    val start = lineStarts.getOrNull(currentLine - 1) ?: return@drawBehind
                    drawRect(
                        color = currentWash,
                        topLeft = Offset(0f, result.lineTopOf(start)),
                        size = Size(size.width, result.lineHeightOf(start, lineStarts.getOrNull(currentLine))),
                    )
                },
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) {
                        Text("## 变更\n- …", fontSize = 13.sp, lineHeight = LINE_H, color = hintColor)
                    }
                    inner()
                }
            },
        )
    }
}

/** 行号槽总宽：左留白 4 + 标记列 [MARKER_W] + 数字列 + 右留白 5。 */
private val GUTTER_W = 40.dp

/** 标记列宽（定宽：`+` 出现时数字轴不动）。 */
private val MARKER_W = 12.dp

/** 行高（正文与行号槽共用，两边才对得齐）。 */
private val LINE_H = 22.sp

/** 同一行高在布局维度上的近似值：只用于「还没量到布局」时的兜底与最小高度。 */
private val LINE_H_DP = 22.dp

/** 3 行起步。 */
private val MIN_H = 66.dp

/** 超过就内部滚动（再高就把附件区挤出屏幕了）。 */
private val MAX_H = 330.dp

// ───────────────────────── 纯函数（可单测） ─────────────────────────

/** 每一逻辑行的起始偏移（`\n` 切分；末行即使为空也算一行）。 */
internal fun lineStartOffsets(text: String): List<Int> {
    val starts = ArrayList<Int>()
    starts += 0
    text.forEachIndexed { index, ch -> if (ch == '\n') starts += index + 1 }
    return starts
}

/** 偏移落在第几个逻辑行（1-based）。 */
internal fun lineOfOffset(text: String, offset: Int): Int {
    val safe = offset.coerceIn(0, text.length)
    var line = 1
    for (i in 0 until safe) if (text[i] == '\n') line++
    return line
}

/**
 * 生成说明写进来的行号（1-based，纯函数，可单测）。
 *
 * 按**行文本**匹配而不是记死行号：用户在生成前后随手敲几行，行号会整体移动；
 * 而「这一行的内容是不是生成器写的」才是要标的东西 —— 用户把某行改掉，标记自然消失。
 */
internal fun generatedLineIndices(text: String, insertedTexts: Set<String>): Set<Int> {
    if (insertedTexts.isEmpty()) return emptySet()
    val result = HashSet<Int>()
    text.split("\n").forEachIndexed { index, line ->
        if (line.isNotBlank() && line in insertedTexts) result += index + 1
    }
    return result
}

/**
 * 把生成说明**追加**到现有正文后面，返回新正文与「插入的那些行」。
 *
 * 上一版是整段替换，所以必须弹一个「替换现有更新内容？」的确认框；现在手写内容原样保留，
 * 那个模态可以直接删掉。
 */
internal fun insertGeneratedNotes(current: String, generated: String): Pair<String, Set<String>> {
    val kept = current.trimEnd()
    val prefix = if (kept.isEmpty()) "" else kept + "\n\n"
    val body = generated.trimEnd()
    return (prefix + body + "\n") to body.split("\n").filter { it.isNotBlank() }.toSet()
}

/** 丢弃生成的那些行（用户写的内容原样保留）。 */
internal fun dropGeneratedNotes(text: String, generatedLines: Set<Int>): String =
    text.split("\n")
        .filterIndexed { index, _ -> index + 1 !in generatedLines }
        .joinToString("\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trimEnd() + "\n"

// ───────────────────────── TextLayoutResult 的两个小助手 ─────────────────────────

/** 某偏移所在视觉行的顶边（像素）。这一版 Compose 的 `getLineTop` 返回 Float。 */
private fun TextLayoutResult.lineTopOf(offset: Int): Float =
    getLineTop(getLineForOffset(offset.coerceIn(0, layoutInput.text.length)))

/**
 * 从某偏移所在视觉行，到「下一条逻辑行的第一视觉行」之间的高度（= 这条逻辑行折行后的总高）。
 *
 * [nextOffset] 为 null 表示这是最后一条逻辑行 —— 此时用 `getLineTop(lineCount)` 取底边，
 * 否则会退化成「最后一行的行高是 0」。
 */
private fun TextLayoutResult.lineHeightOf(offset: Int, nextOffset: Int?): Float {
    val start = lineTopOf(offset)
    val end = if (nextOffset == null) {
        getLineTop(lineCount)
    } else {
        lineTopOf(nextOffset)
    }
    return (end - start).coerceAtLeast(0f)
}
