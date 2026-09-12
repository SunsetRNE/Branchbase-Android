package com.branchbase.ui.repository

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.ui.theme.Primer

/**
 * 评论正文的 Markdown 渲染（对标 github.com issue 评论的排版）。
 *
 * ## 为什么不用 WebView
 *
 * 详情页的**主帖**仍走 [ReadmeWebView]（README 渲染链路，含表格/图片/HTML 标签）。
 * 但时间线里每条评论都塞一个 WebView 是不现实的：一屏 5 条评论就是 5 个 WebView 实例，
 * 滚动时内存与掉帧都很明显。评论实际用到的语法又很集中（粗斜体 / 行内代码 /
 * 代码块 / 列表 / 任务清单 / 引用 / 链接 / @提及），因此在 Compose 里原生渲染。
 *
 * ## 分层
 *
 * - [parseMarkdownBlocks]：纯函数，把 Markdown 拆成块级结构 —— 可 JVM 单测，与 UI 无关；
 * - [inlineMarkdown]：把一行文本转成 `AnnotatedString`（粗体 / 斜体 / 行内代码 / 链接 / @提及 / #编号）；
 * - [MarkdownBody]：把块级结构画出来（代码块带「复制」、任务清单可勾选）。
 */

// ───────────────────────── 块级解析 ─────────────────────────

/** Markdown 块级元素（只保留 GitHub 评论里真正会用到的种类）。 */
sealed interface MdBlock {
    /** 普通段落（多行会被折成一段） */
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val text: String) : MdBlock
    data class Ordered(val index: Int, val text: String) : MdBlock
    /**
     * 任务清单项（`- [ ] / - [x]`）。
     *
     * [line] 是它在**源码里的行号**：勾选要落库（PATCH 整段正文），
     * 没有行号就只能按顺序猜，正文里出现两个一模一样的 `- [ ]` 时必然改错行。
     */
    data class Task(val checked: Boolean, val text: String, val line: Int) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock
    data object Divider : MdBlock
}

private val FENCE = Regex("^```\\s*([A-Za-z0-9_+-]*)\\s*$")
private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
// 分组：1 = 前缀（缩进 + 项目符号 + 空格），2 = 勾选状态，3 = 正文。
// 前缀必须单独成组 —— 勾选回写要按「原样替换第 2 组」重建整行，没有前缀组就会丢掉 `- `。
private val TASK = Regex("^(\\s*[-*+]\\s+)\\[([ xX])]\\s+(.*)$")
private val BULLET = Regex("^\\s*[-*+]\\s+(.*)$")
private val ORDERED = Regex("^\\s*\\d+[.)]\\s+(.*)$")
private val QUOTE = Regex("^\\s*>\\s?(.*)$")
private val DIVIDER = Regex("^\\s*(-{3,}|\\*{3,}|_{3,})\\s*$")

/**
 * 把 Markdown 源码拆成块级元素。
 *
 * 处理顺序与注意事项：
 * 1. **围栏代码块优先**：先整段抽走（含内部所有符号），否则里面的 `#`、`-`、`>` 会被当成标题/列表；
 * 2. 引用 / 任务 / 无序 / 有序 / 标题 / 分隔线逐行判定，都锚定行首，避免误伤正文中间的同名符号；
 * 3. 连续普通行合并成一个段落（GitHub 也是这么渲染的：单个换行不分段）；
 * 4. 列表项的续行（缩进行）并回上一个列表项。
 */
fun parseMarkdownBlocks(source: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val lines = source.replace("\r\n", "\n").split("\n")
    val paragraph = mutableListOf<String>()
    // 列表项续行：记录「上一次 push 的块是不是列表」，是则把缩进行并回去
    var lastListIndex = -1

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            out += MdBlock.Paragraph(paragraph.joinToString(" "))
            paragraph.clear()
        }
    }

    var i = 0
    while (i < lines.size) {
        val raw = lines[i]
        val line = raw.trimEnd()

        // ① 围栏代码块
        val fence = FENCE.find(line)
        if (fence != null) {
            flushParagraph()
            val lang = fence.groupValues[1]
            val body = StringBuilder()
            i++
            while (i < lines.size && FENCE.find(lines[i].trimEnd()) == null) {
                body.append(lines[i]).append('\n')
                i++
            }
            out += MdBlock.Code(lang, body.toString().trimEnd('\n'))
            lastListIndex = -1
            i++ // 跳过收尾的 ```
            continue
        }

        if (line.isBlank()) {
            flushParagraph()
            lastListIndex = -1
            i++
            continue
        }

        val heading = HEADING.find(line)
        if (heading != null) {
            flushParagraph()
            out += MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2].trim())
            lastListIndex = -1
            i++
            continue
        }

        if (DIVIDER.matches(line)) {
            flushParagraph()
            out += MdBlock.Divider
            lastListIndex = -1
            i++
            continue
        }

        val quote = QUOTE.find(line)
        if (quote != null) {
            flushParagraph()
            out += MdBlock.Quote(quote.groupValues[1].trim())
            lastListIndex = -1
            i++
            continue
        }

        val task = TASK.find(line)
        if (task != null) {
            flushParagraph()
            out += MdBlock.Task(task.groupValues[2].lowercase() == "x", task.groupValues[3].trim(), i)
            lastListIndex = out.lastIndex
            i++
            continue
        }

        val bullet = BULLET.find(line)
        if (bullet != null) {
            flushParagraph()
            out += MdBlock.Bullet(bullet.groupValues[1].trim())
            lastListIndex = out.lastIndex
            i++
            continue
        }

        val ordered = ORDERED.find(line)
        if (ordered != null) {
            flushParagraph()
            out += MdBlock.Ordered(out.count { it is MdBlock.Ordered } + 1, ordered.groupValues[1].trim())
            lastListIndex = out.lastIndex
            i++
            continue
        }

        // 续行：并回上一个列表项（缩进 + 有前导空白）
        if (lastListIndex >= 0 && line.firstOrNull()?.isWhitespace() == true) {
            val text = line.trim()
            out[lastListIndex] = when (val b = out[lastListIndex]) {
                is MdBlock.Bullet -> b.copy(text = b.text + " " + text)
                is MdBlock.Ordered -> b.copy(text = b.text + " " + text)
                is MdBlock.Task -> b.copy(text = b.text + " " + text)
                else -> b
            }
            i++
            continue
        }

        lastListIndex = -1
        paragraph += line.trim()
        i++
    }
    flushParagraph()
    return out
}

/**
 * 把源码第 [line] 行的任务清单勾选状态改成 [checked]，返回新的源码。
 *
 * 行号越界 / 该行不是任务项时**原样返回**（不抛异常、不误改相邻行）：
 * 这个函数的输入来自 UI 点击，防御性比「相信调用方」重要。
 */
fun toggleTaskLine(source: String, line: Int, checked: Boolean): String {
    val lines = source.replace("\r\n", "\n").split("\n").toMutableList()
    if (line !in lines.indices) return source
    val m = TASK.find(lines[line]) ?: return source
    lines[line] = m.groupValues[1] + "[" + (if (checked) "x" else " ") + "] " + m.groupValues[3]
    return lines.joinToString("\n")
}

// ───────────────────────── 行内解析 ─────────────────────────

/**
 * 行内语法主正则（**单次扫描**，按优先级从高到低排列）。
 *
 * 为什么必须单次扫描：`**粗**` 与 `*斜*`、`[文字](url)` 与裸 URL 之间会互相吞并，
 * 分多轮 replace 时后一轮会改写前一轮产出的文本（`a * b * c` 被吃成斜体是典型症状）。
 * 单次扫描下每个位置只被消费一次，顺序即优先级。
 */
private val INLINE_TOKEN = Regex(
    "\\[([^]]*)]\\(([^)\\s]+)\\)" +            // 1 链接文字 / 2 链接地址
        "|\\*\\*([^*]+)\\*\\*" +                  // 3 粗体 **
        "|__([^_]+)__" +                        // 4 粗体 __
        "|\\*([^*\\n]+)\\*" +                      // 5 斜体 *
        "|(?<![A-Za-z0-9_])_([^_\\n]+)_" +          // 6 斜体 _
        "|(^|[\\s(])@([A-Za-z0-9][A-Za-z0-9-]*)" + // 7 前缀 / 8 @提及
        "|(^|[\\s(])#(\\d+)" +                     // 9 前缀 / 10 #编号
        "|(https?://[^\\s<]+)",                 // 11 裸链接
)

/**
 * 行内标记 → `AnnotatedString`。
 *
 * 两级处理：
 * 1. **行内代码优先**：`` ` `` 分段，代码段原样输出（等宽 + 底色），其中的 `*`、`[` 一律不再解析；
 * 2. 非代码段走 [INLINE_TOKEN] 单次扫描，逐段 append 并套样式。
 */
fun inlineMarkdown(
    text: String,
    linkColor: Color = Color.Unspecified,
    codeBg: Color = Color.Unspecified,
): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i <= text.length) {
        val open = text.indexOf('`', i)
        if (open < 0) {
            appendInlineSegment(text.substring(i), linkColor)
            break
        }
        val close = text.indexOf('`', open + 1)
        if (close < 0) {
            appendInlineSegment(text.substring(i), linkColor)
            break
        }
        if (open > i) appendInlineSegment(text.substring(i, open), linkColor)
        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, fontSize = 12.sp)) {
            append(text.substring(open + 1, close))
        }
        i = close + 1
    }
}

/** 非代码段的行内样式扫描（粗体 / 斜体 / 链接 / @提及 / #编号 / 裸链接）。 */
private fun AnnotatedString.Builder.appendInlineSegment(segment: String, linkColor: Color) {
    var cursor = 0
    INLINE_TOKEN.findAll(segment).forEach { m ->
        if (m.range.first > cursor) append(segment.substring(cursor, m.range.first))
        val g = m.groupValues
        when {
            g[1].isNotEmpty() || (g[2].isNotEmpty() && m.value.startsWith("[")) -> {
                withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                    append(g[1].ifBlank { g[2] })
                }
            }
            g[3].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(g[3]) }
            g[4].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(g[4]) }
            g[5].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[5]) }
            g[6].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[6]) }
            g[8].isNotEmpty() -> {
                append(g[7])
                withStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.SemiBold)) { append("@${g[8]}") }
            }
            g[10].isNotEmpty() -> {
                append(g[9])
                withStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.SemiBold)) { append("#${g[10]}") }
            }
            g[11].isNotEmpty() -> withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                append(g[11])
            }
            else -> append(m.value)
        }
        cursor = m.range.last + 1
    }
    if (cursor < segment.length) append(segment.substring(cursor))
}

// ───────────────────────── 渲染 ─────────────────────────

/**
 * 评论正文渲染。
 *
 * @param onLinkClick 链接 / @提及 / #编号 的点击回调（由调用方决定跳内置页还是浏览器）
 * @param onToggleTask 任务清单勾选（只对**自己的**评论给回调；别人的任务项不可改）
 */
@Composable
fun MarkdownBody(
    source: String,
    modifier: Modifier = Modifier,
    onLinkClick: (String) -> Unit = {},
    onCopyCode: ((String) -> Unit)? = null,
    onToggleTask: ((MdBlock.Task) -> Unit)? = null,
) {
    val blocks = remember(source) { parseMarkdownBlocks(source) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEachIndexed { index, block ->
            when (block) {
                is MdBlock.Paragraph -> Text(
                    inlineMarkdown(block.text, linkColor = Primer.Link, codeBg = Primer.Gray150),
                    fontSize = 13.5.sp,
                    lineHeight = 21.sp,
                    color = Primer.TextPrimary,
                )

                is MdBlock.Heading -> Text(
                    inlineMarkdown(block.text, linkColor = Primer.Link, codeBg = Primer.Gray150),
                    fontSize = when (block.level) {
                        1, 2 -> 16.sp
                        3 -> 15.sp
                        else -> 13.5.sp
                    },
                    fontWeight = FontWeight.Bold,
                    color = Primer.TextPrimary,
                    modifier = Modifier.padding(top = if (index == 0) 0.dp else 4.dp),
                )

                is MdBlock.Bullet -> BulletRow(marker = "•", content = block.text)
                is MdBlock.Ordered -> BulletRow(marker = "${block.index}.", content = block.text)

                is MdBlock.Task -> Row(verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier
                            .padding(top = 2.dp)
                            .size(15.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .border(1.5.dp, if (block.checked) Primer.Blue500 else Primer.Gray300, RoundedCornerShape(4.dp))
                            .background(if (block.checked) Primer.Blue500 else Color.Transparent)
                            .clickable(enabled = onToggleTask != null) { onToggleTask?.invoke(block) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (block.checked) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = "已完成",
                                tint = Color.White,
                                modifier = Modifier.size(11.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        inlineMarkdown(block.text, linkColor = Primer.Link, codeBg = Primer.Gray150),
                        fontSize = 13.5.sp,
                        lineHeight = 21.sp,
                        color = if (block.checked) Primer.TextTertiary else Primer.TextPrimary,
                        textDecoration = if (block.checked) TextDecoration.LineThrough else null,
                    )
                }

                is MdBlock.Quote -> Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.width(3.dp).height(IntrinsicHeightMin).background(Primer.Gray200))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        inlineMarkdown(block.text, linkColor = Primer.Link, codeBg = Primer.Gray150),
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = Primer.TextSecondary,
                    )
                }

                is MdBlock.Code -> CodeBlock(block.lang, block.code, onCopyCode)

                MdBlock.Divider -> Box(Modifier.fillMaxWidth().height(1.dp).background(Primer.Gray200))
            }
        }
    }
}

/** 引用块左侧竖条需要一个最小高度（`IntrinsicSize` 在 LazyColumn 里开销大，这里给固定值）。 */
private val IntrinsicHeightMin = 20.dp

@Composable
private fun BulletRow(marker: String, content: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            marker,
            fontSize = 13.5.sp,
            lineHeight = 21.sp,
            color = Primer.TextSecondary,
            modifier = Modifier.width(20.dp),
        )
        Text(
            inlineMarkdown(content, linkColor = Primer.Link, codeBg = Primer.Gray150),
            fontSize = 13.5.sp,
            lineHeight = 21.sp,
            color = Primer.TextPrimary,
        )
    }
}

/** 代码块：等宽字体 + 横向滚动 + 右上角复制（对标网页版的代码块工具条）。 */
@Composable
private fun CodeBlock(lang: String, code: String, onCopy: ((String) -> Unit)?) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Primer.Gray100)
            .border(1.dp, Primer.Gray200, RoundedCornerShape(8.dp)),
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            if (lang.isNotBlank()) {
                Text(
                    lang,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Primer.TextTertiary,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Text(
                code,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.8.sp,
                lineHeight = 18.sp,
                color = Primer.TextPrimary,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
        }
        if (onCopy != null) {
            Text(
                "复制",
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = Primer.TextSecondary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Primer.BackgroundPrimary)
                    .border(1.dp, Primer.Gray200, RoundedCornerShape(6.dp))
                    .clickable { onCopy(code) }
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            )
        }
    }
}
