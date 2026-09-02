package com.branchbase.ui.log

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.ui.theme.Primer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// 分类 / 级别 语义色（对齐 HTML 原型）
internal val catColor: Map<LogCategory, Color> = mapOf(
    LogCategory.UI_RENDER to Color(0xFF3F8FE0),
    LogCategory.NETWORK to Color(0xFF3FB950),
    LogCategory.REMOTE_EXEC to Color(0xFFBC8CFF),
    LogCategory.LOCAL_TASK to Color(0xFFD29922),
)
internal val levelColor: Map<LogLevel, Color> = mapOf(
    LogLevel.DEBUG to Color(0xFF6E7681),
    LogLevel.INFO to Color(0xFF3F8FE0),
    LogLevel.WARN to Color(0xFFD29922),
    LogLevel.ERROR to Color(0xFFF85149),
)

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

internal fun formatTime(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.of("Asia/Shanghai")).format(timeFmt)

private enum class LogMode { STREAM, RAW }

@Composable
fun LogScreen(onBack: () -> Unit) {
    LaunchedEffect(Unit) { Logger.ui("进入日志页", "Compose") }
    var logs by remember { mutableStateOf(LogManager.all()) }
    var mode by remember { mutableStateOf(LogMode.STREAM) }
    var keyword by remember { mutableStateOf("") }
    var curCategory by remember { mutableStateOf<LogCategory?>(null) }
    var curLevel by remember { mutableStateOf<LogLevel?>(null) }
    var curTag by remember { mutableStateOf<String?>(null) }
    var showFilter by remember { mutableStateOf(false) }
    var levelMenu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    fun refresh() { logs = LogManager.all() }

    // 进入日志页时主动拉取最新日志（避免 remember 首次读取到旧值/空值）
    LaunchedEffect(Unit) { logs = LogManager.all() }

    val filtered = remember(logs, keyword, curCategory, curLevel, curTag) {
        logs.filter { e ->
            (curCategory == null || e.category == curCategory) &&
                (curLevel == null || e.level == curLevel) &&
                (curTag == null || e.tag == curTag) &&
                (keyword.isBlank() || e.message.contains(keyword, true) || e.tag.contains(keyword, true))
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Primer.BackgroundPrimary).statusBarsPadding().navigationBarsPadding(),
    ) {
        // 顶部导航
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Primer.IconPrimary, modifier = Modifier.size(24.dp).clickable { onBack() })
            Spacer(Modifier.width(8.dp))
            Text("日志", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                val c = LogManager.logFile()?.readText().orEmpty()
                clipboard.setText(AnnotatedString(c.ifEmpty { "（暂无日志）" }))
            }) { Text("导出 .log", color = Primer.Blue500, fontSize = 13.sp) }
            TextButton(onClick = { LogManager.clear(); refresh() }) { Text("清空", color = Primer.Red500, fontSize = 13.sp) }
        }

        // 工具栏：搜索 + 级别下拉 + 过滤
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                placeholder = { Text("搜索日志", fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f).height(48.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
            )
            Box {
                TextButton(onClick = { levelMenu = true }) {
                    Text(curLevel?.name ?: "级别", fontSize = 12.sp)
                }
                DropdownMenu(expanded = levelMenu, onDismissRequest = { levelMenu = false }) {
                    DropdownMenuItem(text = { Text("全部级别") }, onClick = { curLevel = null; levelMenu = false })
                    LogLevel.entries.forEach { lv ->
                        DropdownMenuItem(text = { Text(lv.name) }, onClick = { curLevel = lv; levelMenu = false })
                    }
                }
            }
            Box(
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (curCategory != null || curLevel != null || curTag != null) Primer.Blue500 else Primer.Gray150)
                    .clickable { showFilter = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.FilterList, "过滤", tint = if (curCategory != null || curLevel != null || curTag != null) Color.White else Primer.IconSecondary)
            }
        }

        // 统计徽章（单行，横向滚动，避免膨胀）
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LogCategory.entries.forEach { c ->
                StatBadge(c.label, logs.count { it.category == c }, catColor[c]!!)
            }
            StatBadge("共", logs.size, null)
        }

        // 双加载
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(10.dp))
                .background(Primer.Gray150).padding(4.dp),
        ) {
            listOf(LogMode.STREAM to "时间流", LogMode.RAW to "原始日志").forEach { (m, label) ->
                Text(
                    label,
                    fontSize = 13.sp,
                    fontWeight = if (mode == m) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (mode == m) Primer.Blue500 else Primer.TextTertiary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(7.dp))
                        .background(if (mode == m) Color.White else Color.Transparent)
                        .clickable { mode = m }.padding(vertical = 8.dp),
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // 日志内容
        if (mode == LogMode.STREAM) {
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("（无匹配日志）", color = Primer.TextTertiary, fontSize = 13.sp) }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    items(filtered, key = { it.time.toString() + it.message }) { e ->
                        LogStreamItem(e, onClick = { clipboard.setText(AnnotatedString("${formatTime(e.time)} [${e.category.label}] [${e.tag}] ${e.level.name} ${e.message}")) })
                    }
                }
            }
        } else {
            // 原始日志
            Box(Modifier.fillMaxSize().padding(horizontal = 16.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF0A0E14)).border(1.dp, Primer.Border, RoundedCornerShape(12.dp)).padding(14.dp)) {
                Text(
                    filtered.joinToString("\n") { "${formatTime(it.time)} [${it.category.label}] [${it.tag}] ${it.level.name} ${it.message}" }
                        .ifEmpty { "（无日志）" },
                    fontSize = 12.sp,
                    color = Color(0xFFC9D1D9),
                    lineHeight = 20.sp,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    // 过滤面板
    if (showFilter) {
        FilterDialog(
            logs = logs,
            curCategory = curCategory,
            curLevel = curLevel,
            curTag = curTag,
            onCategory = { curCategory = it },
            onLevel = { curLevel = it },
            onTag = { curTag = it },
            onCopy = { clipboard.setText(AnnotatedString(logs.joinToString("\n") { "${formatTime(it.time)} [${it.category.label}] [${it.tag}] ${it.level.name} ${it.message}" })) },
            onClear = { LogManager.clear(); refresh() },
            onDismiss = { showFilter = false },
        )
    }
}

@Composable
private fun StatBadge(label: String, count: Int, dotColor: Color?) {
    Row(
        modifier = Modifier.wrapContentWidth().clip(RoundedCornerShape(11.dp))
            .background(Color(0xFF21262D)).border(1.dp, Color(0xFF30363D), RoundedCornerShape(11.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dotColor != null) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(dotColor))
            Spacer(Modifier.width(5.dp))
        }
        Text(label, fontSize = 11.sp, color = Color(0xFF8B949E))
        Spacer(Modifier.width(4.dp))
        Text("$count", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE6EDF3))
    }
}

@Composable
private fun LogStreamItem(entry: LogEntry, onClick: () -> Unit) {
    val lvlColor = levelColor[entry.level] ?: Color.Gray
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 1.dp).clip(RoundedCornerShape(0.dp))
            .background(Color(0xFF161B22))
            .clickable { onClick() }
            .padding(start = 3.dp),
    ) {
        Box(
            Modifier.fillMaxWidth()
                .background(if (entry.level == LogLevel.ERROR || entry.level == LogLevel.WARN) lvlColor else Color.Transparent)
                .padding(start = if (entry.level == LogLevel.ERROR || entry.level == LogLevel.WARN) 3.dp else 0.dp)
                .padding(horizontal = 11.dp, vertical = 11.dp),
        ) {
            Column {
                Text(entry.message, fontSize = 14.sp, color = Color(0xFFE6EDF3), lineHeight = 20.sp)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(formatTime(entry.time), fontSize = 11.sp, color = Color(0xFF6E7681))
                    Text(entry.category.label, fontSize = 11.sp, color = catColor[entry.category] ?: Color.Gray)
                    Text(entry.level.name, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = lvlColor)
                }
            }
        }
    }
}

@Composable
private fun FilterDialog(
    logs: List<LogEntry>,
    curCategory: LogCategory?,
    curLevel: LogLevel?,
    curTag: String?,
    onCategory: (LogCategory?) -> Unit,
    onLevel: (LogLevel?) -> Unit,
    onTag: (String?) -> Unit,
    onCopy: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tags = remember(logs) { listOf(null) + logs.map { it.tag }.distinct() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("过滤", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("日志类别", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Primer.TextTertiary)
                FilterOption("全部", curCategory == null) { onCategory(null) }
                LogCategory.entries.forEach { c ->
                    FilterOption(c.label, curCategory == c, dot = catColor[c]) { onCategory(c) }
                }
                Spacer(Modifier.height(8.dp))
                Text("级别", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Primer.TextTertiary)
                FilterOption("全部级别", curLevel == null) { onLevel(null) }
                LogLevel.entries.forEach { lv ->
                    FilterOption(lv.name, curLevel == lv) { onLevel(lv) }
                }
                Spacer(Modifier.height(8.dp))
                Text("模块 tag", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Primer.TextTertiary)
                tags.forEach { t ->
                    FilterOption(t ?: "全部 tag", curTag == t) { onTag(t) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCopy(); onDismiss() }) { Text("复制日志") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onClear(); onDismiss() }) { Text("清空", color = Primer.Red500) }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}

@Composable
private fun FilterOption(label: String, selected: Boolean, dot: Color? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, fontSize = 14.sp, color = if (selected) Primer.Blue500 else Primer.TextPrimary, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}