package com.branchbase.ui.repository

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextLinkStyles
import coil.compose.AsyncImage
import com.branchbase.ui.theme.CodeSyntax
import com.branchbase.ui.theme.Primer

/**
 * README 渲染器：把 `parseHtml` 解析出的 Block 列表渲染为 Compose 组件。
 *
 * 对齐 `docs/html-parser-design.md` 的 Block/Inline 模型：
 * heading / paragraph / code / list_item / blockquote / table / image / hr。
 * 行内链接（kind=link）点击回调 [onLinkClick]，供内部导航使用。
 */
@Composable
fun ReadmeContent(
    blocks: List<ReadmeBlock>,
    onLinkClick: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        blocks.forEach { block -> BlockContent(block, onLinkClick) }
    }
}

/** 渲染单个块（折叠块 `details` 内部会递归复用本函数）。 */
@Composable
private fun BlockContent(block: ReadmeBlock, onLinkClick: (Destination) -> Unit) {
    when (block.type) {
        "heading" -> HeadingBlock(block)
        "paragraph" -> InlineText(
            inline = block.inline,
            baseStyle = TextStyle(fontSize = 13.sp, color = Primer.TextPrimary, lineHeight = 20.sp),
            onLinkClick = onLinkClick,
        )
        "code" -> CodeBlock(block)
        "list_item" -> ListItemBlock(block, onLinkClick)
        "blockquote" -> BlockquoteBlock(block, onLinkClick)
        "table" -> TableBlock(block, onLinkClick)
        "image" -> ImageBlock(block, onLinkClick)
        "details" -> DetailsBlock(block, onLinkClick)
        "hr" -> HorizontalDivider(
            color = Primer.Gray200,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
}

// ── 各块 ──

@Composable
private fun HeadingBlock(block: ReadmeBlock) {
    val size = when (block.level) {
        1 -> 20.sp
        2 -> 16.sp
        3 -> 14.sp
        else -> 13.sp
    }
    Text(
        text = inlinePlainText(block.inline),
        fontSize = size,
        fontWeight = FontWeight.Bold,
        color = Primer.TextPrimary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 6.dp),
    )
    if (block.level == 1) {
        HorizontalDivider(color = Primer.Gray200, modifier = Modifier.padding(bottom = 6.dp))
    }
}

@Composable
private fun CodeBlock(block: ReadmeBlock) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .background(CodeSyntax.CodeBg, RoundedCornerShape(6.dp))
            .padding(12.dp),
    ) {
        block.lang?.let {
            Text(it, fontSize = 11.sp, color = Primer.TextTertiary)
            Spacer(Modifier.height(4.dp))
        }
        Text(
            text = block.text.orEmpty(),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 20.sp,
            color = Color(0xFF24292F),
        )
    }
}

@Composable
private fun ListItemBlock(block: ReadmeBlock, onLinkClick: (Destination) -> Unit) {
    val indent = (block.depth.coerceAtLeast(1) - 1) * 16
    Row(modifier = Modifier.fillMaxWidth().padding(start = indent.dp, top = 2.dp, bottom = 2.dp)) {
        val marker = when {
            block.checked != null -> if (block.checked == true) "☑" else "☐"
            block.ordered -> "${block.index}."
            else -> "•"
        }
        Text(
            text = marker,
            fontSize = 13.sp,
            color = if (block.checked != null) Primer.Blue500 else Primer.TextSecondary,
            modifier = Modifier.width(20.dp),
        )
        InlineText(
            inline = block.inline,
            baseStyle = TextStyle(fontSize = 13.sp, color = Primer.TextPrimary, lineHeight = 20.sp),
            onLinkClick = onLinkClick,
        )
    }
}

@Composable
private fun BlockquoteBlock(block: ReadmeBlock, onLinkClick: (Destination) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(20.dp)
                .background(Primer.Gray300),
        )
        Spacer(Modifier.width(10.dp))
        InlineText(
            inline = block.inline,
            baseStyle = TextStyle(fontSize = 13.sp, color = Primer.TextSecondary, lineHeight = 20.sp),
            onLinkClick = onLinkClick,
        )
    }
}

@Composable
private fun TableBlock(block: ReadmeBlock, onLinkClick: (Destination) -> Unit) {
    if (block.rows.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .background(CodeSyntax.CodeBg, RoundedCornerShape(6.dp)),
    ) {
        block.rows.forEachIndexed { i, row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp, horizontal = 8.dp)) {
                row.forEach { cell ->
                    Box(Modifier.weight(1f)) {
                        InlineText(
                            inline = cell,
                            baseStyle = TextStyle(
                                fontSize = 12.5.sp,
                                fontWeight = if (i == 0) FontWeight.SemiBold else FontWeight.Normal,
                                color = Primer.TextPrimary,
                            ),
                            onLinkClick = onLinkClick,
                        )
                    }
                }
            }
            if (i == 0) HorizontalDivider(color = Primer.Gray200)
        }
    }
}

@Composable
private fun DetailsBlock(block: ReadmeBlock, onLinkClick: (Destination) -> Unit) {
    var expanded by remember(block) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        // 标题行（点击展开/收起）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(Primer.Gray150)
                .clickable { expanded = !expanded }
                .padding(10.dp, 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (expanded) "▾" else "▸",
                fontSize = 12.sp,
                color = Primer.TextSecondary,
            )
            Spacer(Modifier.width(6.dp))
            InlineText(
                inline = block.summary,
                baseStyle = TextStyle(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Primer.TextPrimary,
                ),
                onLinkClick = onLinkClick,
            )
        }
        // 展开后的子块
        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp)) {
                block.children.forEach { child -> BlockContent(child, onLinkClick) }
            }
        }
    }
}

@Composable
private fun ImageBlock(block: ReadmeBlock, onLinkClick: (Destination) -> Unit) {
    val src = block.src
    val alt = block.alt?.takeIf { it.isNotBlank() } ?: "图片"

    // 图片尺寸：优先 width/height（px→dp），否则 fillMaxWidth 撑满（保持原行为）
    val w = block.width
    val h = block.height
    val modifier = when {
        w != null && h != null -> Modifier.size(w.dp, h.dp).padding(vertical = 8.dp)
        w != null -> Modifier.width(w.dp).padding(vertical = 8.dp)
        h != null -> Modifier.height(h.dp).padding(vertical = 8.dp)
        else -> Modifier.fillMaxWidth().padding(vertical = 8.dp)
    }

    // 无有效地址时退化为占位（保持原行为）
    if (src.isNullOrBlank()) {
        Box(
            modifier = Modifier.fillMaxWidth().height(64.dp).background(CodeSyntax.CodeBg, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(alt, fontSize = 12.sp, color = Primer.TextTertiary)
        }
        return
    }

    // 徽章（图片被链接包裹）可点击跳转
    val dest = block.dest
    val clickableModifier = if (dest != null) {
        modifier.clickable { onLinkClick(dest) }
    } else {
        modifier
    }
    AsyncImage(
        model = src,
        contentDescription = alt,
        contentScale = ContentScale.Fit,
        modifier = clickableModifier,
    )
}

// ── 行内内容 ──

@Composable
private fun InlineText(
    inline: List<ReadmeInline>,
    baseStyle: TextStyle,
    onLinkClick: (Destination) -> Unit,
) {
    val linkDests = remember(inline) { mutableMapOf<String, Destination>() }
    val imageNodes = remember(inline) { mutableMapOf<String, ReadmeInline>() }
    val linkListener = remember(inline, onLinkClick) {
        LinkInteractionListener { link ->
            (link as? LinkAnnotation.Clickable)?.tag?.let { tag -> linkDests[tag] }?.let(onLinkClick)
        }
    }
    val annotated = remember(inline, onLinkClick) {
        buildAnnotatedString { appendInlines(inline, this, SpanStyle(), linkDests, imageNodes, linkListener) }
    }
    val inlineContent = remember(inline, onLinkClick) {
        imageNodes.mapValues { (_, node) ->
            val w = node.width
            val h = node.height
            val pw = (w ?: h ?: 20).sp
            val ph = (h ?: w ?: 20).sp
            InlineTextContent(Placeholder(pw, ph, PlaceholderVerticalAlign.AboveBaseline)) {
                AsyncImage(
                    model = node.src,
                    contentDescription = node.value,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .then(
                            when {
                                w != null -> Modifier.width(w.dp)
                                h != null -> Modifier.height(h.dp)
                                else -> Modifier.height(20.dp) // 徽标（badge）无 width/height 时默认 20dp 高
                            }
                        )
                        .clickable(enabled = node.dest != null) { node.dest?.let(onLinkClick) },
                )
            }
        }
    }
    Text(text = annotated, style = baseStyle, inlineContent = inlineContent)
}

/** 递归把行内树写入 builder，图片用 inline content，链接用 LinkAnnotation 记录点击目标。 */
private fun appendInlines(
    items: List<ReadmeInline>,
    builder: AnnotatedString.Builder,
    style: SpanStyle,
    linkDests: MutableMap<String, Destination>,
    imageNodes: MutableMap<String, ReadmeInline>,
    linkListener: LinkInteractionListener,
) {
    for (item in items) {
        when (item.kind) {
            "text" -> builder.withStyle(style) { append(item.value) }
            "code" -> builder.withStyle(
                style.merge(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        background = Color(0xFFEEF0F3),
                    )
                )
            ) { append(item.value) }
            "bold" -> appendInlines(item.children, builder, style.merge(SpanStyle(fontWeight = FontWeight.Bold)), linkDests, imageNodes, linkListener)
            "italic" -> appendInlines(item.children, builder, style.merge(SpanStyle(fontStyle = FontStyle.Italic)), linkDests, imageNodes, linkListener)
            "strike" -> appendInlines(item.children, builder, style.merge(SpanStyle(textDecoration = TextDecoration.LineThrough)), linkDests, imageNodes, linkListener)
            "link" -> {
                val tag = "l${builder.length}"
                item.dest?.let { linkDests[tag] = it }
                builder.withLink(
                    LinkAnnotation.Clickable(
                        tag,
                        TextLinkStyles(SpanStyle(color = Primer.Blue500, textDecoration = TextDecoration.Underline)),
                        linkListener,
                    )
                ) {
                    appendInlines(item.children, builder, style, linkDests, imageNodes, linkListener)
                }
            }
            "image" -> {
                val id = "i${builder.length}"
                imageNodes[id] = item
                builder.appendInlineContent(id, item.value.ifBlank { "图片" })
            }
            else -> builder.withStyle(style) { append(item.value) }
        }
    }
}

/** 提取行内纯文本（标题等不渲染链接的场景），递归展开容器节点。 */
private fun inlinePlainText(inline: List<ReadmeInline>): String {
    val sb = StringBuilder()
    appendPlain(inline, sb)
    return sb.toString()
}

private fun appendPlain(items: List<ReadmeInline>, sb: StringBuilder) {
    for (it in items) {
        sb.append(it.value)
        appendPlain(it.children, sb)
    }
}