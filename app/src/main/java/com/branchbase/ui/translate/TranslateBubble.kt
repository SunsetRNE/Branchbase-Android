package com.branchbase.ui.translate

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.translate.TranslateConfig
import com.branchbase.translate.TranslatePageCommands
import com.branchbase.translate.TranslatePageSnapshot
import com.branchbase.translate.TranslateProvider
import com.branchbase.translate.TranslateRuntime
import com.branchbase.translate.TranslateSettings
import com.branchbase.ui.theme.Primer
import kotlin.math.roundToInt

/**
 * 沉浸式翻译的**悬浮球 + 翻译工具面板**（原生 Compose 实现）。
 *
 * ## 为什么界面在原生，而不是页面脚本
 *
 * 正文页的 WebView 高度 = 整篇内容高度（滚动由外层原生列表负责），于是页面里
 * `position: fixed` 钉的是整篇文章的右下角，绝对定位又会被 WebView 的边界裁掉 ——
 * **正文比屏幕短时，面板就永远长不过正文**（只能内部滑动、怎么点都展不开）。
 * 把悬浮球与面板搬到原生层，位置锚定在**窗口**右下角（底部导航条之上），
 * 面板能用满屏幕高度，正文长短与滚动都不再影响它。
 *
 * 代价是一条状态同步通道：页面脚本用 `BBTranslate.report(json)` 推状态上来
 * （[TranslatePageSnapshot]），原生用 `window.__bbIT.command(name, arg)` 下发开关与操作
 * （[TranslatePageCommands]）。页面因此**没有任何写设置的通道**，凭据更安全。
 *
 * ## 交互（与需求一一对应）
 *
 * | 操作 | 行为 |
 * |------|------|
 * | 悬浮球单击 | 展开工具面板，**球让位**（球与面板不同时出现） |
 * | 面板右上角 × | 收起面板，球回来 |
 * | 点面板之外的任意位置 | 同上（全屏透明遮罩，顺带挡住正文误触） |
 * | 拖动悬浮球 | 球跟着手指走，钳制在窗口内（相对默认位的偏移记在 [TranslateBubbleHost]） |
 * | 关掉「本页翻译」 | 球与面板一起消失；重新开启去设置页的「自动翻译正文」 |
 *
 * 球只在**翻译模式**下出现，所以不翻译时页面上没有任何控件。
 */
@Stable
class TranslateBubbleHost {

    /** 当前绑定的正文页（WebView 实例，用来忽略旧页面迟到的上报）。 */
    private var owner: Any? = null

    /** 向页面下发命令的通道（`evaluateJavascript`）。 */
    private var sender: ((command: String, arg: String) -> Unit)? = null

    /** 有没有正文页绑在上面（离开正文页即 false，悬浮控件随之消失）。 */
    var pageBound by mutableStateOf(false)
        private set

    /** 页面最新推上来的状态（[TranslatePageSnapshot]）。 */
    var snapshot by mutableStateOf(TranslatePageSnapshot())
        private set

    /** 原生设置镜像（面板上的开关读它）。 */
    var settings by mutableStateOf(TranslateConfig.DEFAULT)
        private set

    /** 面板是否展开（展开时球隐藏）。 */
    var expanded by mutableStateOf(false)
        private set

    /** 悬浮球相对「右下角默认位」的偏移（px，向左/向上为负）。 */
    var ballOffset by mutableStateOf(Offset.Zero)

    /** 正文页绑定（WebView 创建时调）。owner 用于多页面切换时的迟到上报去重。 */
    fun bind(owner: Any, settings: TranslateConfig, send: (String, String) -> Unit) {
        this.owner = owner
        this.sender = send
        this.settings = settings
        this.snapshot = TranslatePageSnapshot()
        this.expanded = false
        pageBound = true
    }

    /** 正文页销毁（离开页面 / 重建）。只有当前 owner 能解绑，避免误清掉新页面的绑定。 */
    fun unbind(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        this.sender = null
        this.snapshot = TranslatePageSnapshot()
        this.expanded = false
        pageBound = false
    }

    /** 页面状态上报（非当前 owner 的一律丢弃）。 */
    fun update(owner: Any, snapshot: TranslatePageSnapshot) {
        if (this.owner !== owner) return
        this.snapshot = snapshot
    }

    /** 下发一条命令（页面没绑定时静默丢弃）。 */
    fun command(command: String, arg: String = "") {
        sender?.invoke(command, arg)
    }

    /** 面板改完设置后刷新镜像。 */
    fun applySettings(config: TranslateConfig) {
        settings = config
    }

    fun toggleExpanded() {
        expanded = !expanded
    }

    fun collapse() {
        expanded = false
    }

    fun resetBall() {
        ballOffset = Offset.Zero
    }
}

/** 会话（单例式）随组合树向下传：`MainActivity` 建，`ReadmeWebView` 绑，本文件的界面渲染。 */
val LocalTranslateBubbleHost = staticCompositionLocalOf<TranslateBubbleHost?> { null }

private val BALL_SIZE = 48.dp
private val PANEL_WIDTH = 288.dp
private val EDGE = 16.dp

/** 底部安全区：系统导航栏 + 仓库页底栏 56dp + 间隙（与 GitBubblePanel 的 edgePadding 同源）。 */
private val BOTTOM_SAFE_EXTRA = 76.dp

/** 顶部安全区：状态栏 + 顶部栏（面板往上长时不许盖住它）。 */
private val TOP_SAFE_EXTRA = 56.dp

/**
 * 悬浮控件本体。放在应用根布局的最后（覆盖层），只要正文页绑着且翻译开着就会出现。
 */
@Composable
fun TranslateBubble(host: TranslateBubbleHost, modifier: Modifier = Modifier) {
    val visible = host.pageBound && host.snapshot.on
    // 球消失（关掉本页翻译 / 离开正文页）时，展开的面板一起收起
    LaunchedEffect(visible) { if (!visible) host.collapse() }
    if (!visible) return

    val density = LocalDensity.current
    val navBars = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val statusBars = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomSafe = navBars + BOTTOM_SAFE_EXTRA
    val topSafe = statusBars + TOP_SAFE_EXTRA

    BoxWithConstraints(modifier.fillMaxSize()) {
        val ballPx = with(density) { BALL_SIZE.toPx() }
        val edgePx = with(density) { EDGE.toPx() }
        val bottomSafePx = with(density) { bottomSafe.toPx() }
        val topSafePx = with(density) { topSafe.toPx() }
        val containerW = with(density) { maxWidth.toPx() }
        val containerH = with(density) { maxHeight.toPx() }
        // 面板宽度跟着窗口收窄，下面的横向钳制用它的实际宽度（不然窄屏上会被推出屏幕）
        val panelWidth = minOf(PANEL_WIDTH, maxWidth - EDGE * 2)
        val panelPx = with(density) { panelWidth.toPx() }
        val maxLeftBall = (containerW - ballPx - edgePx).coerceAtLeast(0f)
        val maxLeftPanel = (containerW - panelPx - edgePx).coerceAtLeast(0f)
        // 纵向：球不许拖到顶部栏之上
        val maxUp = (containerH - ballPx - bottomSafePx - topSafePx).coerceAtLeast(0f)
        // 面板展开时**纵向回到默认位**（底部安全线之上）：这样面板永远贴着底、往上长，
        // 用满可用高度；球被拖到高处时，面板也不会因此被顶部裁掉。
        val offsetX = host.ballOffset.x.coerceIn(-if (host.expanded) maxLeftPanel else maxLeftBall, 0f)
        val offsetY = if (host.expanded) 0f else host.ballOffset.y.coerceIn(-maxUp, 0f)

        if (host.expanded) {
            // 点面板之外的任意位置 → 收起面板（同时挡住正文，避免误触链接）
            val interaction = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(interactionSource = interaction, indication = null) { host.collapse() },
            )
        }

        // 面板可用高度：窗口高度扣掉上下安全区与一点间隙 —— 「内容完整显示」就靠它，
        // 超出部分才在面板内部滚动（不再受正文 WebView 高度影响）
        val maxPanel = (maxHeight - topSafe - bottomSafe - 12.dp).coerceAtLeast(180.dp)

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .padding(end = EDGE, bottom = bottomSafe),
            horizontalAlignment = Alignment.End,
        ) {
            if (host.expanded) {
                TranslatePanel(host, panelWidth, maxPanel)
            } else {
                TranslateBall(
                    host = host,
                    onDrag = { drag ->
                        host.ballOffset = Offset(
                            (host.ballOffset.x + drag.x).coerceIn(-maxLeftBall, 0f),
                            (host.ballOffset.y + drag.y).coerceIn(-maxUp, 0f),
                        )
                    },
                )
            }
        }
    }
}

/* ───────────── 悬浮球 ───────────── */

@Composable
private fun TranslateBall(host: TranslateBubbleHost, onDrag: (Offset) -> Unit) {
    val snapshot = host.snapshot
    val fill = when {
        snapshot.status == TranslatePageSnapshot.STATUS_AUTH -> Primer.Red500
        snapshot.status == TranslatePageSnapshot.STATUS_QUOTA -> Primer.Orange500
        snapshot.blocked -> Primer.Red500
        else -> Primer.Blue500
    }
    val badge = when {
        snapshot.status == TranslatePageSnapshot.STATUS_AUTH -> "!"
        snapshot.status == TranslatePageSnapshot.STATUS_QUOTA -> "!"
        snapshot.blocked -> "↻"
        snapshot.translated > 0 -> snapshot.translated.toString()
        else -> ""
    }

    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(BALL_SIZE)
                .shadow(6.dp, CircleShape)
                .clip(CircleShape)
                .background(fill)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var dragged = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                if (!dragged) host.toggleExpanded()
                                break
                            }
                            val delta = change.positionChange()
                            if (!dragged &&
                                (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                            ) {
                                dragged = true
                            }
                            if (dragged && (delta.x != 0f || delta.y != 0f)) {
                                onDrag(delta)
                                change.consume()
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (snapshot.busy) "…" else "译",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
        if (badge.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(18.dp)
                    .shadow(2.dp, CircleShape)
                    .clip(CircleShape)
                    .background(if (badge == "!" || badge == "↻") Primer.Red500 else Primer.Green500)
                    .border(2.dp, Primer.BackgroundPrimary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(badge, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }
}

/* ───────────── 工具面板 ───────────── */

@Composable
private fun TranslatePanel(host: TranslateBubbleHost, width: Dp, maxHeight: Dp) {
    val context = LocalContext.current
    val snapshot = host.snapshot
    val settings = host.settings

    /** 面板上的设置改动：原生落盘 + 立即作用到当前页。 */
    fun persist(block: () -> Unit) {
        block()
        host.applySettings(TranslateSettings.read(context))
    }

    Box(
        Modifier
            .width(width)
            .heightIn(max = maxHeight)
            .shadow(12.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(Primer.BackgroundPrimary)
            .border(1.dp, Primer.Border, RoundedCornerShape(16.dp)),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            PanelHeader(snapshot) { host.collapse() }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                ProgressBlock(snapshot, settings)
                Spacer(Modifier.height(6.dp))
                BlockTitle("快捷设置")
                ToggleRow("本页翻译", snapshot.on) {
                    if (snapshot.on) {
                        host.command(TranslatePageCommands.OFF)
                        Toast.makeText(
                            context,
                            "已关闭本页翻译，悬浮球已收起；重新开启：设置 → 沉浸式翻译 → 自动翻译正文",
                            Toast.LENGTH_LONG,
                        ).show()
                    } else {
                        host.command(TranslatePageCommands.ON)
                    }
                }
                SegRow(
                    label = "显示方式",
                    options = listOf("1" to "原文+译文", "0" to "仅译文"),
                    selected = if (settings.dual) "1" else "0",
                ) { value ->
                    persist { TranslateSettings.setDual(context, value == "1") }
                    host.command(TranslatePageCommands.DUAL, value)
                }
                SegRow(
                    label = "译文样式",
                    options = listOf(
                        TranslateConfig.STYLE_CARD to "卡片",
                        TranslateConfig.STYLE_UNDERLINE to "下划线",
                        TranslateConfig.STYLE_PLAIN to "淡灰",
                    ),
                    selected = settings.style,
                ) { value ->
                    persist { TranslateSettings.setStyle(context, value) }
                    host.command(TranslatePageCommands.STYLE, value)
                }
                SegRow(
                    label = "目标语言",
                    options = listOf("zh-CN" to "中文", "en" to "English"),
                    selected = settings.target,
                ) { value ->
                    persist { TranslateSettings.setTarget(context, value) }
                    host.command(TranslatePageCommands.TARGET, value)
                }
                ToggleRow("自动翻译正文", settings.enabled) { next ->
                    persist { TranslateSettings.setEnabled(context, next) }
                    // 总开关要**立刻**作用到当前页：关掉就把本页翻译一起关掉（悬浮球随之收起），
                    // 打开就直接开始翻。只落盘不通知页面的话，会出现「开关显示已关，
                    // 页面还在翻、悬浮球还在」——这正是用户报的「悬浮球不受控制」。
                    host.command(if (next) TranslatePageCommands.ON else TranslatePageCommands.OFF)
                }
                ToggleRow("本地缓存", settings.persist) { next ->
                    persist { TranslateSettings.setPersist(context, next) }
                }
                ToggleRow("保护代码与链接", settings.protect) { next ->
                    persist { TranslateSettings.setProtect(context, next) }
                }

                Spacer(Modifier.height(6.dp))
                BlockTitle("操作")
                ActionRow(
                    listOfNotNull(
                        if (snapshot.blocked) "重试" to { host.command(TranslatePageCommands.RETRY) } else null,
                        "翻译当前视口" to { host.command(TranslatePageCommands.SCAN_VISIBLE) },
                        "翻译全文" to { host.command(TranslatePageCommands.SCAN_ALL) },
                        "清空本页译文" to { host.command(TranslatePageCommands.CLEAR) },
                        "重置悬浮球" to { host.resetBall() },
                    ),
                )
                Text(
                    "更多设置：App「设置 → 沉浸式翻译」",
                    fontSize = 11.sp,
                    color = Primer.TextTertiary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun PanelHeader(snapshot: TranslatePageSnapshot, onClose: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Primer.BackgroundSecondary)
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("沉浸式翻译", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary)
        Spacer(Modifier.width(8.dp))
        StatusChip(snapshot)
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .clickable { onClose() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Close, contentDescription = "收起", tint = Primer.IconSecondary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun StatusChip(snapshot: TranslatePageSnapshot) {
    val (text, fg, bg) = when (snapshot.status) {
        TranslatePageSnapshot.STATUS_TRANSLATING -> Triple("翻译中…", Primer.Blue600, Primer.Blue400.copy(alpha = .25f))
        TranslatePageSnapshot.STATUS_DONE -> Triple("已完成", Primer.Green500, Primer.Green100)
        TranslatePageSnapshot.STATUS_AUTH -> Triple("Key 无效", Primer.Red500, Primer.Red100)
        TranslatePageSnapshot.STATUS_QUOTA -> Triple("额度用尽", Primer.Orange500, Primer.Gray100)
        TranslatePageSnapshot.STATUS_PAUSED -> Triple("已暂停", Primer.Red500, Primer.Red100)
        TranslatePageSnapshot.STATUS_FAILED -> Triple("翻译失败", Primer.Red500, Primer.Red100)
        else -> Triple("空闲", Primer.TextSecondary, Primer.Gray100)
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(bg)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(text, fontSize = 11.sp, color = fg, maxLines = 1)
    }
}

@Composable
private fun ProgressBlock(snapshot: TranslatePageSnapshot, settings: TranslateConfig) {
    BlockTitle("本页进度")
    val total = maxOf(snapshot.candidates, snapshot.translated)
    val fraction = if (total > 0) (snapshot.translated.toFloat() / total).coerceIn(0f, 1f) else 0f
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Primer.Gray150),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Primer.Blue500),
        )
    }
    Spacer(Modifier.height(5.dp))
    KeyValue("已译 ${snapshot.translated} / 候选 $total 段", "${snapshot.chars} 字符 · ${(fraction * 100).roundToInt()}%")
    KeyValue(
        "服务：${if (settings.providerKind == TranslateProvider.DEEPSEEK) "DeepSeek" else "MyMemory"}",
        "目标：${if (settings.target == TranslateConfig.EN) "English" else "中文"} · ${styleLabel(settings.style)}",
    )
}

private fun styleLabel(style: String): String = when (style) {
    TranslateConfig.STYLE_UNDERLINE -> "下划线"
    TranslateConfig.STYLE_PLAIN -> "淡灰"
    else -> "卡片"
}

@Composable
private fun BlockTitle(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = Primer.TextTertiary,
        modifier = Modifier.padding(top = 10.dp, bottom = 7.dp),
    )
}

@Composable
private fun KeyValue(left: String, right: String) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(left, fontSize = 11.5.sp, color = Primer.TextTertiary, modifier = Modifier.weight(1f))
        Text(right, fontSize = 11.5.sp, color = Primer.TextTertiary, maxLines = 1)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 12.5.sp, color = Primer.TextPrimary, modifier = Modifier.weight(1f))
        MiniSwitch(checked, onChange)
    }
}

@Composable
private fun MiniSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Box(
        Modifier
            .size(width = 40.dp, height = 22.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(if (checked) Primer.Blue500 else Primer.Gray200)
            .clickable { onChange(!checked) }
            .padding(2.dp),
    ) {
        Box(
            Modifier
                .offset(x = if (checked) 18.dp else 0.dp)
                .size(18.dp)
                .shadow(1.dp, CircleShape)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

@Composable
private fun SegRow(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 12.5.sp, color = Primer.TextPrimary, modifier = Modifier.weight(1f))
        Row(
            Modifier
                .clip(RoundedCornerShape(9.dp))
                .background(Primer.Gray100)
                .border(1.dp, Primer.Border, RoundedCornerShape(9.dp))
                .padding(2.dp),
        ) {
            options.forEach { (value, text) ->
                val on = value == selected
                Box(
                    Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (on) Primer.BackgroundPrimary else Color.Transparent)
                        .clickable { onSelect(value) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text,
                        fontSize = 11.5.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        color = if (on) Primer.Blue600 else Primer.TextSecondary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionRow(actions: List<Pair<String, () -> Unit>>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        actions.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { (label, onClick) ->
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Primer.BackgroundPrimary)
                            .border(1.dp, Primer.Border, RoundedCornerShape(8.dp))
                            .clickable { onClick() }
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, fontSize = 12.sp, color = Primer.TextPrimary, maxLines = 1)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
