package com.branchbase.ui.notification

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.ui.theme.Primer

/**
 * 消息页的「右下角弹窗面板」族（需求 ① / ② 的 UI 层）。
 *
 * 拆成独立文件的原因：`NotificationScreen.kt` 已经承担了数据加载、手势状态机与 4 种布局，
 * 面板/操作条本身有 ~400 行且**完全是展示逻辑**（状态由页面上抛），
 * 混在一起会让「哪里是状态、哪里是渲染」难以分辨。
 */

// ───────────────────────────────── FAB ─────────────────────────────────

/**
 * 右下角筛选入口。
 *
 * 取代了改造前常驻列表上方的四等分筛选行（46dp）：
 * - 列表可视高度 +46dp；
 * - 徽标 = **生效中的筛选维度数**（0 时不显示）。刻意不用未读数：
 *   未读已经在底部导航有红点，两处重复会让人以为「这里点的数字和下面不一样」；
 * - 打开态换成 ✕ 且底色转深灰，与面板形成「按钮 ↔ 面板」的对应关系。
 */
@Composable
fun NotifFilterFab(
    activeDims: Int,
    open: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        FloatingActionButton(
            onClick = onClick,
            shape = CircleShape,
            containerColor = if (open) Primer.Gray900 else Primer.Blue500,
            contentColor = Color.White,
            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp, pressedElevation = 2.dp),
        ) {
            Icon(
                if (open) Icons.Filled.Close else Icons.Filled.Tune,
                contentDescription = if (open) "关闭筛选面板" else "筛选与视图",
                modifier = Modifier.size(22.dp),
            )
        }
        if (activeDims > 0 && !open) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Primer.Gray900)
                    .border(2.dp, Primer.BackgroundSecondary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (activeDims > 9) "9+" else "$activeDims",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
        }
    }
}

// ───────────────────────────────── 面板 ─────────────────────────────────

/**
 * 筛选与视图面板（自 FAB 正上方展开，右下角对齐，带指向箭头）。
 *
 * 与改造前的差别不只是位置：
 * - 单一维度 → 全部维度（分类 / 类型 / 视图模式 / 时间 / 排序 / 过往 Issue）；
 * - 「确定才生效」→ **即时生效**（底部只留「重置 / 完成」）；
 * - 分组标题与计数常驻，用户随时知道当前口径下有多少条。
 */
@Composable
fun NotifPanel(
    category: NotifCategory,
    categoryCounts: Map<NotifCategory, Int>,
    onCategory: (NotifCategory) -> Unit,
    types: List<String>,
    typeCounts: Map<String, Int>,
    selectedTypes: Set<String>,
    onToggleType: (String) -> Unit,
    onClearTypes: () -> Unit,
    layout: NotifLayout,
    onLayout: (NotifLayout) -> Unit,
    range: NotifRange,
    onRange: (NotifRange) -> Unit,
    sort: NotifSort,
    onSort: (NotifSort) -> Unit,
    history: List<ArchivedThread>,
    historyQuery: String,
    onHistoryQuery: (String) -> Unit,
    onOpenHistory: (ArchivedThread) -> Unit,
    onRestoreHistory: (ArchivedThread) -> Unit,
    onReset: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Primer.BackgroundPrimary,
            border = BorderStroke(1.dp, Primer.Gray150),
            shadowElevation = 16.dp,
        ) {
            Column {
                // 头部：标题 + 当前口径摘要 + 关闭
                Row(
                    Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("筛选与视图", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        summaryOf(category, selectedTypes, layout, range),
                        fontSize = 11.sp,
                        color = Primer.TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).clickable { onDone() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Close, "关闭面板", tint = Primer.IconSecondary, modifier = Modifier.size(16.dp))
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Primer.Gray150))

                Column(
                    Modifier
                        // 屏高自适应：小屏（<640dp）时面板最高只占 52%，避免顶到状态栏被裁掉
                        .heightIn(max = (LocalConfiguration.current.screenHeightDp.dp * 0.55f).coerceAtMost(540.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                ) {
                    // ① 分类
                    PanelSection("分类") {
                        CategorySegment(category, categoryCounts, onCategory)
                    }

                    // ② 类型（多选）
                    PanelSection(
                        title = "类型",
                        action = if (selectedTypes.isNotEmpty()) "清除" to onClearTypes else null,
                    ) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            types.forEach { t ->
                                PanelChip(
                                    text = typeShortName(t),
                                    count = typeCounts[t],
                                    selected = t in selectedTypes,
                                ) { onToggleType(t) }
                            }
                        }
                    }

                    // ③ 视图模式
                    PanelSection("视图模式") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            NotifLayout.entries.forEach { l ->
                                LayoutOption(
                                    title = l.label,
                                    desc = l.desc,
                                    selected = layout == l,
                                ) { onLayout(l) }
                            }
                        }
                    }

                    // ④ 时间范围 + 排序
                    PanelSection("时间范围") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            NotifRange.entries.forEach { r ->
                                PanelChip(text = r.label, count = null, selected = range == r) { onRange(r) }
                            }
                        }
                    }
                    PanelSection("排序") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            NotifSort.entries.forEach { s ->
                                PanelChip(text = s.label, count = null, selected = sort == s) { onSort(s) }
                            }
                        }
                    }

                    // ⑤ 过往 Issue（本地归档）
                    PanelSection(title = "过往 Issue", action = null, icon = true) {
                        HistorySearch(historyQuery, onHistoryQuery)
                        if (history.isEmpty()) {
                            Text(
                                "还没有处理过的会话。点开或「完成」一条 issue/PR 消息后会自动留档，方便回头找。",
                                fontSize = 11.5.sp,
                                color = Primer.TextTertiary,
                                lineHeight = 17.sp,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        } else {
                            history.forEach { h ->
                                HistoryRow(h, onClick = { onOpenHistory(h) }, onRestore = { onRestoreHistory(h) })
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }

                Box(Modifier.fillMaxWidth().height(1.dp).background(Primer.Gray150))
                Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PanelButton("重置", primary = false, onClick = onReset, modifier = Modifier.weight(1f))
                    PanelButton("完成", primary = true, onClick = onDone, modifier = Modifier.weight(1f))
                }
            }
        }
        // 指向 FAB 的小箭头（旋转 45° 的方块；被上面的 Surface 盖住上半部分）
        Surface(
            color = Primer.BackgroundPrimary,
            shape = RoundedCornerShape(2.dp),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 22.dp)
                .padding(top = 8.dp)
                .size(14.dp)
                .rotate(45f),
        ) {
            Box(Modifier.background(Primer.BackgroundPrimary))
        }
    }
}

/** 面板顶部的口径摘要（「未读 · 2 个类型 · 按仓库」）。 */
private fun summaryOf(
    category: NotifCategory,
    types: Set<String>,
    layout: NotifLayout,
    range: NotifRange,
): String {
    val parts = mutableListOf(category.label)
    if (types.isNotEmpty()) parts += "${types.size} 个类型"
    if (layout != NotifLayout.FLAT) parts += layout.label
    if (range != NotifRange.ALL) parts += range.label
    return parts.joinToString(" · ")
}

@Composable
private fun PanelSection(
    title: String,
    action: Pair<String, () -> Unit>? = null,
    icon: Boolean = false,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            if (icon) {
                Icon(Icons.Filled.Schedule, null, tint = Primer.Gray500, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                title.uppercase(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Primer.TextTertiary,
                letterSpacing = 0.6.sp,
            )
            Spacer(Modifier.weight(1f))
            if (action != null) {
                Text(
                    action.first,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Primer.Blue500,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { action.second() }.padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
        content()
    }
}

/** 分类分段控件：未读 / 全部 / 参与 / 已完成（各带计数）。 */
@Composable
private fun CategorySegment(
    current: NotifCategory,
    counts: Map<NotifCategory, Int>,
    onSelect: (NotifCategory) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Primer.Gray150)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        NotifCategory.entries.forEach { c ->
            val selected = c == current
            val count = counts[c] ?: 0
            Row(
                Modifier
                    .weight(1f)
                    .height(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) Primer.BackgroundPrimary else Color.Transparent)
                    .clickable { onSelect(c) }
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    c.label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) Primer.TextPrimary else Primer.TextSecondary,
                    maxLines = 1,
                )
                if (count > 0) {
                    Spacer(Modifier.width(4.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) Primer.Blue500 else Primer.Blue500.copy(alpha = 0.14f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    ) {
                        Text(
                            if (count > 99) "99+" else "$count",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selected) Color.White else Primer.Blue500,
                        )
                    }
                }
            }
        }
    }
}

/** 芯片（类型 / 时间 / 排序共用）：选中蓝底白字 + 计数。 */
@Composable
private fun PanelChip(
    text: String,
    count: Int?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = if (selected) Primer.Blue500 else Primer.BackgroundPrimary,
        border = BorderStroke(1.dp, if (selected) Primer.Blue500 else Primer.Gray200),
        modifier = Modifier.clip(CircleShape).clickable { onClick() },
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) Color.White else Primer.TextSecondary,
                maxLines = 1,
            )
            if (count != null) {
                Spacer(Modifier.width(5.dp))
                Text(
                    "$count",
                    fontSize = 10.5.sp,
                    color = if (selected) Color.White.copy(alpha = 0.8f) else Primer.TextTertiary,
                )
            }
        }
    }
}

/** 视图模式单选卡（平铺 / 按仓库 / 按主题 / 两级）：标题 + 一行说明 + 单选圆点。 */
@Composable
private fun LayoutOption(
    title: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Primer.Blue500.copy(alpha = 0.06f) else Primer.BackgroundPrimary)
            .border(
                1.dp,
                if (selected) Primer.Blue500 else Primer.Gray200,
                RoundedCornerShape(10.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = 2.dp)
                .size(16.dp)
                .clip(CircleShape)
                .border(1.5.dp, if (selected) Primer.Blue500 else Primer.Gray300, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Primer.Blue500))
            }
        }
        Spacer(Modifier.width(9.dp))
        Column {
            Text(title, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
            Text(desc, fontSize = 11.sp, color = Primer.TextTertiary, lineHeight = 15.sp)
        }
    }
}

/** 面板内的按钮（重置 / 完成）。 */
@Composable
private fun PanelButton(
    text: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .height(36.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (primary) Primer.Blue500 else Primer.BackgroundPrimary)
            .border(1.dp, if (primary) Primer.Blue500 else Primer.Gray200, RoundedCornerShape(9.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (primary) Color.White else Primer.TextPrimary,
        )
    }
}

/** 过往 Issue 搜索框（自绘，避免 OutlinedTextField 的 56dp 高度把面板撑长）。 */
@Composable
private fun HistorySearch(query: String, onChange: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(Primer.Gray100)
            .border(1.dp, Primer.Gray200, RoundedCornerShape(9.dp))
            .padding(horizontal = 9.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, null, tint = Primer.TextTertiary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(7.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text("搜索标题 / 仓库 / 编号", fontSize = 12.5.sp, color = Primer.TextTertiary)
            }
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.5.sp, color = Primer.TextPrimary),
                cursorBrush = SolidColor(Primer.Blue500),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 过往 Issue 行：类型图标 + 标题 + 仓库#编号 + 相对时间 + 「恢复未读」。 */
@Composable
private fun HistoryRow(
    item: ArchivedThread,
    onClick: () -> Unit,
    onRestore: () -> Unit,
) {
    val meta = runCatching { typeShortName(item.subjectType) }.getOrDefault("Issue")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Primer.Gray150),
            contentAlignment = Alignment.Center,
        ) {
            Text(meta.take(2), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Primer.TextSecondary)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = Primer.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.repoFullName + (item.number?.let { " #$it" } ?: ""),
                    fontSize = 11.sp,
                    color = Primer.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(6.dp))
                Text(relativeTimeOf(item.updatedAtMs), fontSize = 11.sp, color = Primer.TextTertiary)
                if (item.isDone) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "已完成",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Primer.Green500,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Primer.Green500.copy(alpha = 0.12f))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(
            "恢复未读",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = Primer.Blue500,
            modifier = Modifier
                .clip(RoundedCornerShape(7.dp))
                .border(1.dp, Primer.Gray200, RoundedCornerShape(7.dp))
                .clickable { onRestore() }
                .padding(horizontal = 7.dp, vertical = 4.dp),
        )
    }
}

// ───────────────────────────────── 多选操作条 ─────────────────────────────────

/**
 * 多选底部操作条（需求 ②）。
 *
 * 位置从「替换筛选行」改为**底部**：筛选行已被删除，而底部的拇指可达性更好；
 * 高度 54dp 与底部导航同宽，进入/退出多选时列表的滚动位置与视觉重心都不跳。
 *
 * 执行中有进度转圈且按钮禁用；「退出」始终可用（请求会在后台跑完，不该把用户锁住）。
 */
@Composable
fun NotifSelectionBar(
    selectedCount: Int,
    visibleCount: Int,
    running: Boolean,
    onRead: () -> Unit,
    onDone: () -> Unit,
    onMute: () -> Unit,
    onMore: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = Primer.BackgroundPrimary,
        shadowElevation = 8.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = Primer.Blue500,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text("处理中…", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextSecondary)
                Spacer(Modifier.weight(1f))
            } else {
                Text(
                    "已选 $selectedCount / 共 $visibleCount",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Primer.TextPrimary,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
            }
            SelectionAction(Icons.Filled.MarkEmailRead, "已读", !running && selectedCount > 0, onRead)
            SelectionAction(Icons.Filled.Done, "完成", !running && selectedCount > 0, onDone)
            SelectionAction(Icons.Filled.VolumeOff, "静音", !running && selectedCount > 0, onMute)
            SelectionAction(Icons.Filled.MoreHoriz, "更多", !running && selectedCount > 0, onMore)
            SelectionAction(Icons.Filled.Close, "退出", true, onExit)
        }
    }
}

@Composable
private fun SelectionAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .width(58.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (enabled) Primer.Blue500 else Primer.Gray300,
            modifier = Modifier.size(19.dp),
        )
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) Primer.Blue500 else Primer.Gray300,
        )
    }
}

// ───────────────────────────────── 遮罩 / 顶栏多选态 ─────────────────────────────────

/** 面板遮罩：点击关闭；用 tween 做淡入淡出，避免「瞬间出现」的割裂感。 */
@Composable
fun NotifScrim(visible: Boolean, onClick: () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(180)),
        exit = fadeOut(tween(160)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource(), indication = null) { onClick() },
        )
    }
}

/** 面板入场动画：自右下角放大淡入（与原型一致）。 */
@Composable
fun NotifPanelAnimated(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(160)) + scaleIn(
            initialScale = 0.86f,
            transformOrigin = TransformOrigin(1f, 1f),
            animationSpec = tween(240),
        ),
        exit = fadeOut(tween(140)) + scaleOut(
            targetScale = 0.9f,
            transformOrigin = TransformOrigin(1f, 1f),
            animationSpec = tween(180),
        ),
        modifier = modifier,
    ) {
        content()
    }
}

/** 多选上下文操作栏（覆盖顶部栏）：✕ + 计数 + 全选。 */
@Composable
fun NotifSelectionTopBar(
    selectedCount: Int,
    visibleCount: Int,
    allSelected: Boolean,
    onExit: () -> Unit,
    onToggleAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Primer.Blue500.copy(alpha = 0.06f))
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).clickable { onExit() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Close, "退出多选", tint = Primer.TextSecondary, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(4.dp))
        Text("已选 $selectedCount", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary)
        Spacer(Modifier.width(4.dp))
        Text("/ 共 $visibleCount", fontSize = 12.sp, color = Primer.TextSecondary)
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).clickable { onToggleAll() }.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (allSelected) Icons.Filled.Close else Icons.Filled.SelectAll,
                null,
                tint = Primer.Blue500,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                if (allSelected) "取消全选" else "全选",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Primer.Blue500,
            )
        }
    }
}
