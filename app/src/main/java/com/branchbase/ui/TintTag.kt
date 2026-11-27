package com.branchbase.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.ui.theme.TintRole
import com.branchbase.ui.theme.color
import com.branchbase.ui.theme.textColor

/**
 * 底色透明度：与 [TintChip] 同一条全局规则（图标色 × 12%）。
 *
 * 这里不引用 TintChip 的私有常量，是因为两者是不同的组件；但**数值必须一致** ——
 * 同一行里一枚牌 + 一枚标签时，两块底的浓度不同会立刻读成两套色板。
 */
private const val TagTintAlpha = 0.12f

/**
 * 语义小标签：**同色 12% 底 + 语义文字色 + 可选 9dp 图标**。
 *
 * 与 [TintChip] 的分工：
 * - 牌（[TintChip]）用于**行首 / 区块级**的类别图标；
 * - 标签（本组件）用于名称后面的**状态**（默认 / 受保护 / 新增 / 删除 / 重命名）。
 *
 * 三条规则与 TintChip 一致：颜色只从 [TintRole] 取；文字用 `textColor()`（不是填充色，
 * 压在同色 12% 底上才过 WCAG AA）；尺寸只做一套（9sp / 9dp 图标 / 最小 15dp 高），
 * 不按页面另调 —— 两个分支页都用这一套。
 *
 * 不直接拿 `Primer.Red500` 当文字色：它是**填充色**，压在浅色底上会掉到 4.0 以下
 * （见 [TintRole.textColor] 的说明）。
 */
@Composable
fun TintTag(
    text: String,
    role: TintRole,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val fill = role.color()
    val ink = role.textColor()
    Row(
        modifier
            .heightIn(min = 15.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(fill.copy(alpha = TagTintAlpha))
            .padding(horizontal = 4.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = ink,
                modifier = Modifier.size(9.dp),
            )
        }
        Text(
            text,
            fontSize = 9.sp,
            lineHeight = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = ink,
            maxLines = 1,
        )
    }
}
