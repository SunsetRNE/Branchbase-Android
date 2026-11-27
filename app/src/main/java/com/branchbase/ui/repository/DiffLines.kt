package com.branchbase.ui.repository

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.ui.theme.Primer

/**
 * unified diff 的**共享渲染**：单行（行号列 + 前缀列 + 代码，增 / 删底色对齐网页端）。
 *
 * 这里原本是 `BranchCompareScreen` 的私有函数。1.0.99 起本地 diff 页也要画 diff ——
 * 而「同一个东西在两处各画一遍」的下场是样式漂：一边改了字号 / 底色，另一边还是旧的，
 * 用户看到两条路径下的差异长得不一样（本仓库在详情页脚手架、设置行上都吃过这个亏）。
 *
 * 引擎那边也是同一个口径：`diff_worktree` 与 `diff_commit` 输出**同一套结构**，
 * 就是为了让上层共用一份渲染（见 `core/src/git/mod.rs` 的 `render_diff`）。
 */
@Composable
internal fun DiffLineRow(
    line: DiffLine,
    /**
     * 紧凑档：分支对比把 diff 收进浮层卡后用。
     *
     * 只动「每行占多少横向空间 / 字号」，不动增删底色与行号/前缀的列结构 ——
     * 两处仍然是同一份渲染，只是密度不同（整页 diff 16dp 边距 / 30dp 行号列，卡内 10 / 25）。
     */
    compact: Boolean = false,
) {
    val hPadding = if (compact) 10.dp else 16.dp
    val lineNoWidth = if (compact) 25.dp else 30.dp
    val prefixWidth = if (compact) 10.dp else 12.dp
    val codeSize = if (compact) 10.5.sp else 11.5.sp
    val lineNoSize = if (compact) 9.sp else 10.sp
    if (line.kind == DiffLineKind.Hunk) {
        Text(
            line.text,
            fontSize = if (compact) 9.sp else 10.5.sp,
            fontFamily = FontFamily.Monospace,
            color = Primer.TextTertiary,
            modifier = Modifier.fillMaxWidth().background(Primer.Gray150).padding(horizontal = hPadding, vertical = if (compact) 3.dp else 4.dp),
        )
        return
    }
    val bg = when (line.kind) {
        DiffLineKind.Add -> Primer.SuccessSurface
        DiffLineKind.Remove -> Primer.DangerSurface
        else -> Color.Transparent
    }
    val prefix = when (line.kind) {
        DiffLineKind.Add -> "+"
        DiffLineKind.Remove -> "-"
        else -> " "
    }
    Row(Modifier.fillMaxWidth().background(bg)) {
        Text(
            line.oldLine?.toString().orEmpty(),
            fontSize = lineNoSize, fontFamily = FontFamily.Monospace, color = Primer.TextTertiary,
            textAlign = TextAlign.End,
            modifier = Modifier.width(lineNoWidth).padding(end = 4.dp),
        )
        Text(
            line.newLine?.toString().orEmpty(),
            fontSize = lineNoSize, fontFamily = FontFamily.Monospace, color = Primer.TextTertiary,
            textAlign = TextAlign.End,
            modifier = Modifier.width(lineNoWidth).padding(end = 6.dp),
        )
        Text(
            prefix,
            fontSize = codeSize, fontFamily = FontFamily.Monospace, color = Primer.TextTertiary,
            modifier = Modifier.width(prefixWidth),
        )
        Text(
            line.text.ifEmpty { " " },
            fontSize = codeSize, fontFamily = FontFamily.Monospace, color = Primer.TextPrimary,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
    }
}
