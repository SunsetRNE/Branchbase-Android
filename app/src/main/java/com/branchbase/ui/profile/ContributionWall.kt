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
import com.branchbase.ui.theme.PlaceholderSwap
import com.branchbase.ui.theme.Primer
import com.branchbase.ui.theme.ProfileColors
import com.branchbase.ui.theme.skeletonBlock

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

        // 区块级过渡：**骨架 → 内容走元素级的 [PlaceholderSwap]**（延迟现身 + 骨架先退 /
        // 内容再进 + 尺寸动画），不是 `Crossfade`。
        //
        // 这一处是动态页「闪」的第二个来源：整页骨架在**事件流**到齐时就让位了，
        // 而贡献日历走 GraphQL，通常还在路上 —— 于是用户看到的是
        // 「整页骨架 → 又一层「加载中…」文字 → 内容」两层加载态。
        // 现在加载态本身就是同尺寸骨架，数据到了在原地替换。
        //
        // 内容分支有「网格 Row + 图例 Row」两段 —— [PlaceholderSwap] 内部已经套了一层 Column，
        // 所以这里不必再套（历史上少了那层时，图例文字盖在网格上，是真机截图抓到的）。
        PlaceholderSwap(
            loading = loading,
            skeleton = { ContributionWallSkeleton() },
        ) {
            when {
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
}

/**
 * 贡献墙骨架：**与真实网格同尺寸**（7 行 × 13 列、10dp 格 / 2dp 间距 + 底部汇总条占位）。
 *
 * 这一屏原先的加载态是居中一行「加载中…」。问题不在那行字本身，而在**它出现的时机**：
 * 动态页的整页骨架在事件流到齐时就让位了，而贡献日历走 GraphQL、通常还在路上 ——
 * 用户看到的是「整页骨架 → 又一层加载文字 → 内容」两层加载态，就是那一下「闪」。
 *
 * 列数取 13（而不是真实的 52 周）：屏宽放不下 52 列，多出来的只会在骨架里挤成一团。
 * 高度按 7 行算，与真实网格一致，所以填成内容时**没有位移**，只有淡入。
 *
 * 微光由屏幕级的 `ProvideShimmer` 下发（[skeletonBlock] 在绘制期读 alpha）；
 * 单独使用本组件时没有下发者，占位块为实心灰（`LocalShimmerAlpha` 的默认值），也不会崩。
 *
 * 网格画法复用 [SkeletonGrid]（活动热力骨架用的是同一份）——
 * 两处各写一份的话，改格子尺寸时只会改到其中一处。
 */
@Composable
internal fun ContributionWallSkeleton() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        SkeletonGrid(cols = 13, cellHeight = CELL)
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth(0.55f).height(12.dp).skeletonBlock())
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
