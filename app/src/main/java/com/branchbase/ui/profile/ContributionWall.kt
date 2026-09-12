package com.branchbase.ui.profile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.ui.theme.Primer
import com.branchbase.ui.theme.ProfileColors

/** 单格尺寸与间距（与 `design/contribution-wall-prototype.html` 一致）。 */
private val CELL = 10.dp
private val GAP = 2.dp
private val COL_STEP = 12.dp

/**
 * 5 级绿阶（复用既有 ProfileColors，与动态页热力图保持一致）。
 *
 * 必须**在 composable 里现取**：主题色只能在 @Composable 上下文读，
 * 而 Canvas 的绘制 lambda 不是 composable 上下文，所以要在外面取成局部值再传进去。
 */
@Composable
private fun contributionLevels(): List<Color> = listOf(
    ProfileColors.ContributionL0,
    ProfileColors.ContributionL1,
    ProfileColors.ContributionL2,
    ProfileColors.ContributionL3,
    ProfileColors.ContributionL4,
)

/**
 * 贡献墙：52 周 × 7 天热力网格（横向滚动，默认停在最右=最近）。
 *
 * 数据来自 GraphQL `contributionCalendar`；[degradedNote] 非空表示走了降级路径
 * （REST 事件近似），此时标题右侧显示数据范围，不伪装成完整贡献墙。
 *
 * 点任意格子回调 [onDaySelected]；[selectedDate] 用于高亮当前选中格。
 */
@Composable
fun ContributionWall(
    calendar: ContributionCalendar?,
    loading: Boolean,
    error: String?,
    degradedNote: String? = null,
    selectedDate: String? = null,
    onDaySelected: (ContributionDay) -> Unit = {},
) {
    val scroll = rememberScrollState()
    val weeks = calendar?.weeks ?: emptyList()
    val max = (calendar?.maxCount ?: 0).coerceAtLeast(1)

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("贡献墙", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
            Spacer(Modifier.weight(1f))
            Text(
                degradedNote ?: "过去一年",
                fontSize = 12.sp,
                color = if (degradedNote != null) Primer.TextTertiary else Primer.Blue500,
            )
        }

        when {
            loading -> Box(Modifier.fillMaxWidth().height(90.dp), contentAlignment = Alignment.Center) {
                Text("加载中…", fontSize = 12.5.sp, color = Primer.TextTertiary)
            }

            error != null -> Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                Text(error, fontSize = 12.5.sp, color = Primer.TextTertiary)
            }

            weeks.isEmpty() -> Box(Modifier.fillMaxWidth().height(90.dp), contentAlignment = Alignment.Center) {
                Text("暂无贡献数据", fontSize = 12.5.sp, color = Primer.TextTertiary)
            }

            else -> {
                val monthLabels = remember(calendar) { monthLabelsOf(weeks) }
                // Canvas 的绘制 lambda 不是 composable 上下文，主题色必须在这里先取好
                val levels = contributionLevels()
                val selectedColor = Primer.TextPrimary
                Row(Modifier.padding(horizontal = 16.dp)) {
                    // 星期标签（固定列，不随网格滚动）
                    Column(Modifier.padding(top = 16.dp)) {
                        listOf("", "一", "", "三", "", "五", "").forEach { label ->
                            Text(
                                label,
                                fontSize = 9.sp,
                                color = Primer.TextTertiary,
                                modifier = Modifier.height(COL_STEP),
                            )
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    Column(Modifier.horizontalScroll(scroll)) {
                        // 月份标签（与网格同步滚动）
                        Row(Modifier.height(16.dp)) {
                            monthLabels.forEachIndexed { idx, (label, startWeek) ->
                                val endWeek = monthLabels.getOrNull(idx + 1)?.second ?: weeks.size
                                Text(
                                    label,
                                    fontSize = 9.5.sp,
                                    color = Primer.TextTertiary,
                                    modifier = Modifier.width((COL_STEP * (endWeek - startWeek).coerceAtLeast(1))),
                                )
                            }
                        }
                        // 网格
                        Canvas(
                            Modifier
                                .width(COL_STEP * weeks.size)
                                .height(COL_STEP * 7)
                                .pointerInput(calendar) {
                                    detectTapGestures { offset ->
                                        val step = CELL.toPx() + GAP.toPx()
                                        val col = (offset.x / step).toInt()
                                        val row = (offset.y / step).toInt()
                                        weeks.getOrNull(col)?.getOrNull(row)?.let(onDaySelected)
                                    }
                                },
                        ) {
                            val c = CELL.toPx()
                            val g = GAP.toPx()
                            val radius = CornerRadius(2.dp.toPx())
                            weeks.forEachIndexed { wi, week ->
                                week.forEachIndexed { di, day ->
                                    val topLeft = Offset(wi * (c + g), di * (c + g))
                                    drawRoundRect(
                                        color = levels[levelOf(day.count, max)],
                                        topLeft = topLeft,
                                        size = Size(c, c),
                                        cornerRadius = radius,
                                    )
                                    if (selectedDate != null && day.date == selectedDate) {
                                        drawRoundRect(
                                            color = selectedColor,
                                            topLeft = topLeft,
                                            size = Size(c, c),
                                            cornerRadius = radius,
                                            style = Stroke(width = 1.5.dp.toPx()),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                LaunchedEffect(weeks.size) {
                    // 默认滚到最右（最近一周）
                    scroll.scrollTo(scroll.maxValue)
                }

                // 图例 + 汇总
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val cal = calendar
                    Text(
                        buildString {
                            append("共 ${cal?.total ?: 0} 次贡献")
                            if (cal != null && cal.activeDays > 0) append(" · 活跃 ${cal.activeDays} 天")
                            if (cal != null && cal.maxCount > 0) append(" · 最深 ${cal.maxCount} 次/天")
                        },
                        fontSize = 11.5.sp,
                        color = Primer.TextTertiary,
                    )
                    Spacer(Modifier.weight(1f))
                    Text("少", fontSize = 9.5.sp, color = Primer.TextTertiary)
                    contributionLevels().forEach { c ->
                        Spacer(Modifier.width(3.dp))
                        Box(Modifier.width(10.dp).height(10.dp).clip(RoundedCornerShape(2.dp)).background(c))
                    }
                    Spacer(Modifier.width(3.dp))
                    Text("多", fontSize = 9.5.sp, color = Primer.TextTertiary)
                }
            }
        }
    }
}

/** 色阶分级（与既有热力图一致）。 */
private fun levelOf(count: Int, max: Int): Int = when {
    count <= 0 -> 0
    count * 4 < max -> 1
    count * 2 < max -> 2
    count * 4 < max * 3 -> 3
    else -> 4
}

/** 月份标签：取每月第一周出现的位置，返回 (标签, 起始列索引)。 */
private fun monthLabelsOf(weeks: List<List<ContributionDay>>): List<Pair<String, Int>> {
    val out = ArrayList<Pair<String, Int>>()
    var lastMonth = -1
    weeks.forEachIndexed { index, week ->
        val date = week.firstOrNull()?.date ?: return@forEachIndexed
        val month = date.substringAfter('-', "").take(2).toIntOrNull() ?: return@forEachIndexed
        if (month != lastMonth) {
            lastMonth = month
            out += "${month}月" to index
        }
    }
    return out
}
