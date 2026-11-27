package com.branchbase.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.ui.theme.ElementMotion
import com.branchbase.ui.theme.Primer

/**
 * 浮层卡分段控件：**等分**的 2~4 段 + 一枚会滑动的选中胶囊。
 *
 * 选中块用 `animateDpAsState` + `spring(ζ=0.72, stiffness=420)` 位移；文字色同步 180ms
 * （[ElementMotion.COLOR_MS]）—— 否则胶囊到了、字还是旧颜色，切换读起来是「两件事在动」。
 *
 * 段数不是写死的 2：个人页抽屉里是「全部 + 有语言落进去的大类」，数据里没有脚本类仓库时就不出现
 * 「脚本」这一段（给一个点了必然空的分段，比少一段更糟）；搜索页抽屉是固定的四段
 * （目标 / 倾向 / 语言 / 更多）。两处共用同一份位移与配色，不各写一套。
 *
 * 整块控件本身是一张 10dp / 2dp 阴影的浮层卡：它常驻在抽屉内部，比页面卡降两档，
 * 免得与抽屉里一行一行的卡抢同一层。
 */
@Composable
fun CardSegments(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .cardSurface(shape = RoundedCornerShape(10.dp), elevation = 2.dp)
            .padding(3.dp),
    ) {
        val gap = 3.dp
        val count = labels.size.toFloat()
        val segWidth = (maxWidth - gap * (count - 1f)) / count
        val index = selectedIndex.coerceIn(0, (labels.size - 1).coerceAtLeast(0))
        val offset by animateDpAsState(
            targetValue = (segWidth + gap) * index.toFloat(),
            animationSpec = spring(dampingRatio = 0.72f, stiffness = 420f),
            label = "card-segment",
        )
        Box(
            Modifier
                .offset(x = offset)
                .width(segWidth)
                .height(30.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Primer.Blue500),
        )
        Row(Modifier.height(30.dp)) {
            labels.forEachIndexed { i, label ->
                if (i > 0) Spacer(Modifier.width(gap))
                val on = i == index
                val color by animateColorAsState(
                    targetValue = if (on) Color.White else Primer.TextSecondary,
                    animationSpec = tween(ElementMotion.COLOR_MS),
                    label = "card-segment-text",
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        fontSize = 12.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                        color = color,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
