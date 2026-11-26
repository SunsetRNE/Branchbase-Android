package com.branchbase.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.branchbase.ui.theme.TintRole
import com.branchbase.ui.theme.color

/**
 * 底色透明度：**全局唯一常量**，深浅主题都不调。
 *
 * 底色永远是 `图标色 × 12%`（alpha 合成），而不是另配一个不透明的浅色 —— 这样换卡片面、
 * 换主题时不用重新取值：它叠在什么面上就自动变成那个面的 12% 版本。
 */
private const val ChipTintAlpha = 0.12f

/**
 * 图标牌：**纯色图标 + 同色 12% 的圆角底**。
 *
 * 这是 App 里「区块级图标」的统一形态（首页三个区块标题、待处理折叠头；通知列表与登录步骤条
 * 用同样的派生规则，只是尺寸不同）。规则只有三条：
 *
 * 1. 颜色只从 [TintRole] 取，调用处**不传色值** —— 语义色板是唯一真源；
 * 2. 图标用 `role.color()`（**填充色**），不是 `role.textColor()`：牌底本身就是这色的 12%，
 *    文字色那套是为「压在同色底上的小字」准备的，两者不要混用；
 * 3. 尺寸与牌的比例固定（牌 22dp / 圆角 7dp / 图标 13dp ≈ 0.27 与 0.59），
 *    换尺寸时**三个一起换**，只改其中一个会让图标在牌里显大或显小。
 *
 * ## 为什么不是 Material 的 tonal / Badge
 *
 * Material 的 tonal 是把主色染在整块容器上；这里容器永远是中性面，只有图标那一格带色。
 * 一屏彩色块 ≤3 个才读得成「目录」，染多了就变成装饰。
 *
 * 读屏：图标本身 `contentDescription = null` —— 每行都有文字标签，图标不承载独立信息。
 */
@Composable
fun TintChip(
    icon: ImageVector,
    role: TintRole,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    corner: Dp = 7.dp,
    iconSize: Dp = 13.dp,
) {
    val tint = role.color()
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(tint.copy(alpha = ChipTintAlpha)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}
