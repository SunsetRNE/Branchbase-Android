package com.branchbase.ui.profile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.ui.theme.Primer
import com.branchbase.ui.theme.ProfileColors

private const val ROWS = 6
private val WEEK_CELL = 20.dp
private val HOUR_CELL = 10.dp
private val AXIS_GAP = 2.dp
private val TYPE_LABEL_WIDTH = 58.dp

private val LEVELS = listOf(
    ProfileColors.ContributionL0,
    ProfileColors.ContributionL1,
    ProfileColors.ContributionL2,
    ProfileColors.ContributionL3,
    ProfileColors.ContributionL4,
)

/** 横轴模式。 */
private enum class AxisMode(val label: String) {
    WEEK("按周"),
    HOUR("按小时"),
}

/**
 * 活动十字坐标轴：纵轴=活动类型（Top 6），横轴=时间。
 *
 * 对齐 `design/activity-axis-prototype.html`。与「活动热力」并存：
 * 热力看总量节奏，十字轴看「哪类活动集中在哪段时间 / 哪个时段」。
 *
 * @param events 来自 `/users/{login}/received_events`（近 90 天公开事件）
 */
@Composable
internal fun ActivityAxis(events: List<ActivityEvent>, modifier: Modifier = Modifier) {
    var mode by remember { mutableStateOf(AxisMode.WEEK) }
    var selected by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    val types = remember(events) {
        events.groupingBy { it.type.removeSuffix("Event") }.eachCount()
            .entries.sortedByDescending { it.value }
            .take(ROWS).map { it.key }
    }
    val matrix = remember(events, mode) { buildMatrix(events, types, mode) }
    val cols = if (mode == AxisMode.WEEK) 12 else 24
    val cellW = if (mode == AxisMode.WEEK) WEEK_CELL else HOUR_CELL

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("活动十字坐标轴", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
            Spacer(Modifier.weight(1f))
            AxisMode.entries.forEach { m ->
                Box(
                    Modifier.clip(RoundedCornerShape(14.dp))
                        .background(if (mode == m) Primer.Blue500 else Primer.BackgroundPrimary)
                        .border(1.dp, if (mode == m) Primer.Blue500 else Primer.Border, RoundedCornerShape(14.dp))
                        .clickable { mode = m; selected = null }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        m.label,
                        fontSize = 11.5.sp,
                        fontWeight = if (mode == m) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (mode == m) androidx.compose.ui.graphics.Color.White else Primer.TextSecondary,
                    )
                }
                Spacer(Modifier.width(6.dp))
            }
        }

        if (types.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(90.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("暂无活动数据", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextSecondary)
                    Spacer(Modifier.height(4.dp))
                    Text("推送、开 PR、星标等活动会出现在这里", fontSize = 11.5.sp, color = Primer.TextTertiary)
                }
            }
            return@Column
        }

        val scroll = rememberScrollState()
        Row(Modifier.padding(horizontal = 16.dp)) {
            // 纵轴标签
            Column(Modifier.padding(top = 16.dp)) {
                types.forEach { t ->
                    Text(
                        t,
                        fontSize = 10.5.sp,
                        color = Primer.TextTertiary,
                        maxLines = 1,
                        modifier = Modifier.width(TYPE_LABEL_WIDTH).height(if (mode == AxisMode.WEEK) WEEK_CELL else HOUR_CELL),
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.horizontalScroll(scroll)) {
                // 横轴标签
                Row(Modifier.height(16.dp)) {
                    repeat(cols) { c ->
                        Text(
                            if (mode == AxisMode.WEEK) weekLabel(c) else if (c % 3 == 0) "$c" else "",
                            fontSize = if (mode == AxisMode.WEEK) 9.sp else 8.sp,
                            color = Primer.TextTertiary,
                            maxLines = 1,
                            modifier = Modifier.width(cellW + AXIS_GAP),
                        )
                    }
                }
                // 矩阵
                Canvas(
                    Modifier
                        .width((cellW + AXIS_GAP) * cols)
                        .height((WEEK_CELL + AXIS_GAP) * ROWS)
                        .pointerInput(matrix, mode) {
                            detectTapGestures { offset ->
                                val stepX = cellW.toPx() + AXIS_GAP.toPx()
                                val stepY = WEEK_CELL.toPx() + AXIS_GAP.toPx()
                                val c = (offset.x / stepX).toInt()
                                val r = (offset.y / stepY).toInt()
                                if (r in 0 until ROWS && c in 0 until cols) selected = r to c
                            }
                        },
                ) {
                    val w = cellW.toPx()
                    val h = WEEK_CELL.toPx()
                    val g = AXIS_GAP.toPx()
                    val radius = CornerRadius(3.dp.toPx())
                    for (r in 0 until ROWS) {
                        for (c in 0 until cols) {
                            val count = matrix.counts.getOrNull(r)?.getOrNull(c) ?: 0
                            val topLeft = Offset(c * (w + g), r * (h + g))
                            drawRoundRect(
                                color = LEVELS[levelOf(count, matrix.max)],
                                topLeft = topLeft,
                                size = Size(w, h),
                                cornerRadius = radius,
                            )
                            if (selected == (r to c)) {
                                drawRoundRect(
                                    color = Primer.TextPrimary,
                                    topLeft = topLeft,
                                    size = Size(w, h),
                                    cornerRadius = radius,
                                    style = Stroke(width = 1.5.dp.toPx()),
                                )
                            }
                        }
                    }
                }
            }
        }

        // 图例 + 汇总
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (mode == AxisMode.WEEK) "过去 12 周共 ${matrix.total} 次" else "近 90 天共 ${matrix.total} 次",
                fontSize = 11.5.sp,
                color = Primer.TextTertiary,
            )
            Spacer(Modifier.weight(1f))
            Text("少", fontSize = 9.5.sp, color = Primer.TextTertiary)
            LEVELS.forEach { c ->
                Spacer(Modifier.width(3.dp))
                Box(Modifier.width(10.dp).height(10.dp).clip(RoundedCornerShape(2.dp)).background(c))
            }
            Spacer(Modifier.width(3.dp))
            Text("多", fontSize = 9.5.sp, color = Primer.TextTertiary)
        }

        // 选中详情
        selected?.let { (r, c) ->
            val type = types.getOrNull(r) ?: return@let
            val count = matrix.counts.getOrNull(r)?.getOrNull(c) ?: 0
            val scope = if (mode == AxisMode.WEEK) "第 ${c + 1} 周（由近及远）" else "${c}:00–${(c + 1) % 24}:00"
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(8.dp)).background(Primer.Gray150)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$scope · $type", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "$count 次",
                        fontSize = 11.5.sp,
                        color = if (count > 0) Primer.Green500 else Primer.TextTertiary,
                    )
                }
                if (count == 0) {
                    Spacer(Modifier.height(4.dp))
                    Text("这一格没有活动", fontSize = 11.5.sp, color = Primer.TextTertiary)
                }
            }
        }
    }
}

/** 矩阵结果：每行一个类型，每列一个时间桶。 */
private data class AxisMatrix(val counts: List<List<Int>>, val max: Int, val total: Int)

private fun buildMatrix(
    events: List<ActivityEvent>,
    types: List<String>,
    mode: AxisMode,
): AxisMatrix {
    val cols = if (mode == AxisMode.WEEK) 12 else 24
    val counts = MutableList(types.size) { MutableList(cols) { 0 } }
    val indexOfType = types.withIndex().associate { (i, t) -> t to i }

    val now = System.currentTimeMillis()
    val weekMs = 7L * 24 * 60 * 60 * 1000
    val cal = java.util.Calendar.getInstance()

    var total = 0
    events.forEach { e ->
        if (e.createdAt <= 0) return@forEach
        val type = e.type.removeSuffix("Event")
        val r = indexOfType[type] ?: return@forEach
        val bucket = if (mode == AxisMode.WEEK) {
            val weeksAgo = ((now - e.createdAt) / weekMs).toInt()
            // 左旧右新：11 = 12 周前，0 = 本周
            11 - weeksAgo
        } else {
            cal.timeInMillis = e.createdAt
            cal.get(java.util.Calendar.HOUR_OF_DAY)
        }
        if (bucket !in 0 until cols) return@forEach
        counts[r][bucket]++
        total++
    }

    val max = counts.maxOfOrNull { row -> row.maxOrNull() ?: 0 } ?: 0
    return AxisMatrix(counts, max.coerceAtLeast(1), total)
}

private fun levelOf(count: Int, max: Int): Int = when {
    count <= 0 -> 0
    count * 4 < max -> 1
    count * 2 < max -> 2
    count * 4 < max * 3 -> 3
    else -> 4
}

/** 按周模式的横轴标签：近 12 周，返回该列起始日期（M/d）。 */
private fun weekLabel(col: Int): String {
    val weeksAgo = 11 - col
    val t = System.currentTimeMillis() - weeksAgo * 7L * 24 * 60 * 60 * 1000
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = t
    return "${cal.get(java.util.Calendar.MONTH) + 1}/${cal.get(java.util.Calendar.DAY_OF_MONTH)}"
}
