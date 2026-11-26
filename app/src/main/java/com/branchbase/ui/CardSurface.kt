package com.branchbase.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.branchbase.ui.theme.Primer

/**
 * 卡片面：**中性白面 + 4dp 阴影、圆角 12dp**。
 *
 * 底色取 `Primer.BackgroundSecondary`（= `canvasSubtle`）：浅色板里它等于 canvas（纯白），
 * 卡片靠阴影与页面分层；深色板里它是 `#161B22`，靠与 canvas（`#0D1117`）的明度差分层 ——
 * 同一份代码两套主题都成立，不需要给阴影写主题分支（深色下阴影本来就看不见）。
 *
 * ## 为什么不是 `Gray100`
 *
 * 灰底在浅色下是「一块被染灰的白」：分组靠 5% 明度差硬造，反而压不住同屏的正文与链接；
 * 而深色下 `Gray100`（`#161B22`）正好等于 `canvasSubtle`，与白面卡片是**同一个面** ——
 * 也就是说灰底只解决浅色，且解决得不好。1.4.62 起首页与常用仓库管理页统一用本函数。
 *
 * ## 用法
 *
 * 放在 `padding` 之后、内容 `padding` 之前，**中间不要再插 `background`**：
 * 阴影的 shape 与 `clip` 的 shape 必须同一个，否则圆角处会露出直角阴影。
 */
val CardShape: Shape = RoundedCornerShape(12.dp)

/** 卡片阴影。原型是双层柔光（`0 1px 2px` + `0 10px 24px`），Compose 只有一个 elevation。 */
val CardElevation: Dp = 4.dp

@Composable
fun Modifier.cardSurface(
    shape: Shape = CardShape,
    elevation: Dp = CardElevation,
): Modifier = shadow(elevation, shape).clip(shape).background(Primer.BackgroundSecondary)
