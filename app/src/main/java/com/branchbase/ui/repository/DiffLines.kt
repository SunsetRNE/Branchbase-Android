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
internal fun DiffLineRow(line: DiffLine) {
    if (line.kind == DiffLineKind.Hunk) {
        Text(
            line.text,
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace,
            color = Primer.TextTertiary,
            modifier = Modifier.fillMaxWidth().background(Primer.Gray150).padding(horizontal = 16.dp, vertical = 4.dp),
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
            fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Primer.TextTertiary,
            textAlign = TextAlign.End,
            modifier = Modifier.width(30.dp).padding(end = 4.dp),
        )
        Text(
            line.newLine?.toString().orEmpty(),
            fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Primer.TextTertiary,
            textAlign = TextAlign.End,
            modifier = Modifier.width(30.dp).padding(end = 6.dp),
        )
        Text(
            prefix,
            fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = Primer.TextTertiary,
            modifier = Modifier.width(12.dp),
        )
        Text(
            line.text.ifEmpty { " " },
            fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = Primer.TextPrimary,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
    }
}
