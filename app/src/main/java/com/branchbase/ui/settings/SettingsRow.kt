package com.branchbase.ui.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.R
import com.branchbase.ui.TintChip
import com.branchbase.ui.cardSurface
import com.branchbase.ui.theme.Primer
import com.branchbase.ui.theme.TintRole
import com.branchbase.ui.theme.selectionColor
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * 设置页的**行型封闭集合**（设计规范 `docs/specs/settings-design.md` §4）。
 *
 * 这个文件存在的唯一理由：设置页原先有 **8 套互不相通的行组件**
 * （`SettingsItem` / `ModeOptionRow` / 私有的 `SwitchRow` / `LocalRepoEntry` /
 *  `AboutInfoRow` / `AboutLinkRow` / `FactCard` 行 / 私有的 `SectionTitle`），
 * 于是同一个「一行设置」被实现了 8 次，改一处永远改不干净。
 * 最直接的后果：`SwitchRow` 曾是 `TranslateSettingsScreen` 的 `private fun`，
 * 别的页抄不到 —— **设置主页一个开关都没有**。
 *
 * 现在页面里**只允许**用下面这几个组件产出行，规范 §12 的反模式清单把这叫
 * 「页面里裸 `Row { … }.clickable { }` 自制设置行」。`SettingsSpecTest` 会扫源码拦住它。
 *
 * ## 六种行型
 * | 行型 | 用途 | 右端 |
 * |---|---|---|
 * | [NavRow] | 进子页 | 值 + `›` |
 * | [SwitchRow] | 布尔，**立即生效** | Switch |
 * | [ChoiceRow] | 2–3 档枚举，就地可见 | [SettingsSegment] |
 * | [ActionRow] | 一次性命令 | 无 `›` |
 * | [DangerRow] | 破坏性 → 二次确认 | 无 `›` |
 * | [InfoRow] | 只读展示 | 值（可复制） |
 *
 * 另有 [DisabledNavRow]（[NavRow] 的**禁用态**，不是第 7 种行型）与 [SettingsProse]（说明段落，不是行）。
 */

// ───────────────────────── 尺寸（规范 §4.2，全是硬值） ─────────────────────────

private val RowMinHeight = 48.dp
private val RowPaddingH = 16.dp
private val IconSize = 20.dp
private val IconGap = 12.dp
private val ValueGap = 10.dp
private val CardRadius = 10.dp
private val LabelPadStart = 4.dp
private val SectionPadH = 12.dp
private val SectionPadV = 6.dp

/**
 * 设置树的**两档密度**（规范 §4.2 的「尺寸硬值」由此收敛成一张表）。
 *
 * - [Comfort]：二级页（通知 / 沉浸式翻译 / Git 代理 / 语言…）沿用原尺寸，**这一轮不动**；
 * - [Compact]：只给设置主页（L1）用 —— 主页是「入口索引」，一屏要扫完 7 组，
 *   说明文字与 48dp 行高是它最大的两笔垂直成本（见 `docs/specs/settings-compact-redesign.md`）。
 *
 * 为什么做成密度而不是另起一套 `CompactNavRow`：设置树已经吃过「同一件事 8 套组件」的亏
 * （本文件头注释）。行型仍是那六个，密度只是它们的外参；`SettingsScreen` 在根部
 * provide 一次，子页拿到的还是 [Comfort]。
 *
 * 卡片面**不分档**：两档都用 `Modifier.cardSurface()`（白面 + 4dp 阴影、12dp 圆角）——
 * 与首页 `HomeGroupCard` / 常用仓库卡同一套。1.4.67 起 `Gray100` 下线：浅色下它是「被染灰的白」，
 * 深色下又正好等于 `canvasSubtle`（等于没换）。
 */
enum class SettingsDensity(
    val rowMinHeight: Dp,
    val rowPaddingH: Dp,
    val iconSize: Dp,
    val iconGap: Dp,
    val valueGap: Dp,
    val sectionPadV: Dp,
    val labelFontSize: TextUnit,
    val labelPadBottom: Dp,
    val nameFontSize: TextUnit,
    val textPadV: Dp,
    val subGap: Dp,
    val subFontSize: TextUnit,
    val subLineHeight: TextUnit,
    val valueFontSize: TextUnit,
    val chevronSize: Dp,
    /**
     * 单选行（[com.branchbase.ui.profile.ModeOptionRow]）的上下内边距。
     *
     * 单选行不带图标、也不走 [SettingsRowShell]，所以它的行高不在上面那张表里 ——
     * 但翻译页的行高有一半来自这 12dp。数值放在密度表里，而不是在行里按 `if (Compact)` 分叉：
     * 页面只 provide 一次密度，行组件不需要知道自己被哪个页面调用。
     */
    val optionPadV: Dp,
    /** 单选圆点的直径（Comfort 18dp / Compact 16dp）。 */
    val optionDotSize: Dp,
) {
    Comfort(
        rowMinHeight = RowMinHeight,
        rowPaddingH = RowPaddingH,
        iconSize = IconSize,
        iconGap = IconGap,
        valueGap = ValueGap,
        sectionPadV = SectionPadV,
        labelFontSize = 12.sp,
        labelPadBottom = 6.dp,
        nameFontSize = 14.sp,
        textPadV = 11.dp,
        subGap = 3.dp,
        subFontSize = 12.sp,
        subLineHeight = 17.sp,
        valueFontSize = 12.sp,
        chevronSize = 20.dp,
        optionPadV = 12.dp,
        optionDotSize = 18.dp,
    ),

    Compact(
        rowMinHeight = 44.dp,
        rowPaddingH = 14.dp,
        iconSize = 18.dp,
        iconGap = 10.dp,
        valueGap = 8.dp,
        sectionPadV = 4.dp,
        labelFontSize = 11.sp,
        labelPadBottom = 4.dp,
        nameFontSize = 14.sp,
        textPadV = 9.dp,
        subGap = 2.dp,
        subFontSize = 11.sp,
        subLineHeight = 15.sp,
        valueFontSize = 11.sp,
        chevronSize = 18.dp,
        optionPadV = 8.dp,
        optionDotSize = 16.dp,
    ),
}

/**
 * 当前设置树的密度；默认 [SettingsDensity.Comfort]。
 *
 * 2026-11 起**每个要压密的设置页在自己根上 provide 一次**（主页 [Compact]、
 * 沉浸式翻译页 [Compact]），不再有「全仓只许主页 provide」的限制 —— 那是第三轮
 * 「只压主页」的临时约束，本轮明确让二级页也能压。仍不允许在**行组件**里偷偷 provide：
 * 那会把「某一页的密度」变成「所有调用点的密度」。`SettingsSpecTest` 按文件钉住这一点。
 */
val LocalSettingsDensity = staticCompositionLocalOf { SettingsDensity.Comfort }

/**
 * 禁用行的半透明度（规范 §6.3「不可用的行要看得出来」）。
 *
 * 乘进**颜色**而不是挂 `Modifier.alpha`：后者是 `graphicsLayer`（`AlphaKt` → `graphicsLayer`），
 * 一整层 RenderNode 只为了把实色图标与实色文字调淡。乘进颜色是同一帧内的同一个结果，零图层。
 */
private const val DisabledAlpha = 0.5f

/**
 * 分组：12sp 小标签 + 卡片容器（规范 §4.4）。
 *
 * 组内分隔线由**每一行自己画在顶部**（`divider = true`），所以每组的第一行要传 `divider = false`。
 * 不用「自动插分隔线」的写法是因为行数是编译期的，靠索引判断反而更脆。
 */
@Composable
fun SettingsSection(
    label: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val d = LocalSettingsDensity.current
    Column(modifier.fillMaxWidth().padding(horizontal = SectionPadH, vertical = d.sectionPadV)) {
        if (label != null) {
            Text(
                label,
                fontSize = d.labelFontSize,
                fontWeight = FontWeight.SemiBold,
                color = Primer.TextTertiary,
                modifier = Modifier.padding(start = LabelPadStart, bottom = d.labelPadBottom),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                // 1.4.67：与首页 / 仓库卡同一套（ui/CardSurface.kt）—— 白面 + 4dp 阴影、12dp 圆角。
                // 原先的 Gray100 在浅色下靠 5% 明度差硬造边界，深色下又正好等于 canvasSubtle。
                .cardSurface(),
            content = content,
        )
    }
}

/**
 * 组内分隔线（画在行的**顶部**；每组第一行不画）。
 *
 * 用 `BorderEmphasis` 的 40% 而不是实色：卡片本身已经没有描边了（浅色下纯黑 1dp 框
 * 把整页读成线框图 —— 真机反馈「取消掉黑边」），分隔线若仍是实色黑，会在灰底上
 * 重新变成一排黑线。40% 与首页 `TodoDivider` 同一档，只用来断行、不用来框内容。
 *
 * 公开是给「卡片里不是行」的块用的（翻译页的 Key / 模型输入块）：它也要在卡片里
 * 与上一行断开，而分隔线的颜色/透明度只该有一份真源，不许页面自己画第二个 40% 灰线。
 */
@Composable
fun SettingsDivider() {
    // 1.4.67：`BorderEmphasis × 40%` → `Primer.Border`。与首页 `TodoDivider` 同一条真机结论 ——
    // 纯黑 40% 压在灰底上刚好，压到白面上就是整页对比度最高的东西。
    Box(Modifier.fillMaxWidth().height(1.dp).background(Primer.Border))
}

/**
 * 所有行共用的骨架：分隔线 + 48dp 最小高度 + 16dp 左右内边距 + 可选整行点击。
 *
 * `onClick == null` 时不挂 `clickable` —— 只读行（[InfoRow]）不该有水波纹，
 * 那会让人以为「点了会发生什么」。
 */
@Composable
private fun SettingsRowShell(
    divider: Boolean,
    onClick: (() -> Unit)?,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val d = LocalSettingsDensity.current
    Column(Modifier.fillMaxWidth()) {
        if (divider) SettingsDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
                .defaultMinSize(minHeight = d.rowMinHeight)
                .padding(horizontal = d.rowPaddingH),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** 行首图标（默认 20dp，紧凑档 18dp；`IconSecondary`；危险行由调用方换成 `DangerText`）。 */
@Composable
private fun RowScope.RowIcon(icon: ImageVector, name: String, tint: TintRole? = null) {
    val d = LocalSettingsDensity.current
    if (tint != null) {
        // 22/7/13 与首页区块标题同一档；图标名沿用行名（设置树原本就用图标名当无障碍名）
        TintChip(icon, tint, contentDescription = name)
    } else {
        Icon(icon, contentDescription = name, tint = Primer.IconSecondary, modifier = Modifier.size(d.iconSize))
    }
    Spacer(Modifier.width(d.iconGap))
}

/**
 * 名称 +（可选）说明 +（可选）值。
 *
 * **值负责省略，不许值去挤名称**（规范 §4.2）：名称/说明一个权重盒、值另一个权重盒，
 * 一人一半 —— 名称长时在自己的份额里省略，而不是把值顶出行外。
 *
 * ## ⚠️ 两个盒子都必须**吃掉**自己的份额（`weight(1f)`，`fill` 默认 `true`）
 *
 * 这是这一块最容易踩、且踩了**不报错**的坑：给名称写 `weight(1f, fill = false)`（或给外层
 * 文本块写）之后，这一块会缩到**文字宽度**，剩下的空白留在**行尾**，于是值列与行尾控件
 * （`›` / `Switch` / 分段控件 / 胶囊）全都停在文字后面 —— 每一行一个位置。
 * 2026-09 的真机截图圈选处就是这个形态：「日志」的 `›` 紧跟名称、「语言」的跟在值后、
 * 只有说明折行的「Git 代理」才碰巧贴右。
 *
 * 吃掉份额之后：值列的右边缘落在文本块末尾，行尾控件落在行的 16dp 内边距上。
 * 名称与值各自的**上限没有变**（仍是一人一半），变的只是行尾那截空白归谁 ——
 * 所以这条是「行尾控件贴右」的钉子，不是新的排版选择。`SettingsSpecTest` 钉住它。
 */
@Composable
private fun RowScope.SettingsText(
    name: String,
    sub: String? = null,
    value: String? = null,
    valueSlot: (@Composable () -> Unit)? = null,
    /**
     * 说明覆盖项：默认取密度表；个别行要「压成一行」时把字号收小、[subMaxLines] 设 1。
     * 见主题页的悬浮玻璃导航栏说明（规范 §十七）。
     */
    subFontSize: TextUnit? = null,
    subLineHeight: TextUnit? = null,
    subMaxLines: Int = Int.MAX_VALUE,
) {
    val d = LocalSettingsDensity.current
    Column(Modifier.weight(1f).padding(vertical = d.textPadV)) {
        Text(
            name,
            fontSize = d.nameFontSize,
            color = Primer.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (sub != null) {
            Spacer(Modifier.height(d.subGap))
            Text(
                sub,
                fontSize = subFontSize ?: d.subFontSize,
                lineHeight = subLineHeight ?: d.subLineHeight,
                color = Primer.TextTertiary,
                // 只给「必须一行」的说明设上限；其余说明保持自由折行
                maxLines = subMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (valueSlot != null) {
        Spacer(Modifier.width(d.valueGap))
        // 权重盒：由它负责「不够就省略」，而不是回头去挤名称
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { valueSlot() }
    } else if (value != null) {
        Spacer(Modifier.width(d.valueGap))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            Text(
                value,
                fontSize = d.valueFontSize,
                color = Primer.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
            )
        }
    }
}

// ───────────────────────── ① 导航行 ─────────────────────────

/**
 * 导航行：进子页。有说明时整行变高，`›` 仍垂直居中。
 *
 * [statusChip] 是值列的**状态胶囊**形态（规范 §4.3：值若是「状态」而不是「配置」就用它，
 * 与 [SwitchRow] / [InfoRow] 的同名参数一致）。传了它就**不再画纯文本 `value`** ——
 * 两个都画会让值列变成两截（名称被挤、右端还得再省一次）。
 */
@Composable
fun NavRow(
    icon: ImageVector,
    name: String,
    onClick: () -> Unit,
    value: String? = null,
    sub: String? = null,
    divider: Boolean = true,
    enabled: Boolean = true,
    statusChip: (@Composable () -> Unit)? = null,
    /** 图标语义色：传了就是图标牌（22dp / 同色 12% 底），不传动图标的裸灰图标。 */
    tint: TintRole? = null,
) {
    SettingsRowShell(divider = divider, onClick = onClick, enabled = enabled) {
        Row(
            // 吃掉份额（`fill` 默认 true）：`fill = false` 时这一块缩到文字宽度，
            // 空白留在行尾，`›` 会跟在名称/值后面而不是贴在 16dp 内边距上（见 `SettingsText` 的坑）。
            Modifier.weight(1f).alpha(if (enabled) 1f else 0.5f),
            // 图标与右端的值按**整行**垂直居中（原型 `design/settings-redesign/style.css`
            // 的 `.row { align-items: center }`）。
            // Row 默认是 Top 对齐：说明折行把这个内层 Row 撑高之后，图标与值都停在卡片上沿，
            // 而名称被 `SettingsText` 的 11dp 上内边距压下去 —— 行越高偏得越明显，
            // 「通知」这种三行说明的行，图标直接贴在卡片左上角。
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RowIcon(icon, name, tint)
            SettingsText(
                name = name,
                sub = sub,
                value = if (statusChip == null) value else null,
                valueSlot = statusChip,
            )
        }
        // 不可点时**不画 `›`** —— 画了就是在承诺「点得进去」
        if (enabled) {
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = Primer.TextTertiary,
                modifier = Modifier.size(LocalSettingsDensity.current.chevronSize),
            )
        }
    }
}

// ───────────────────────── ② 开关行 ─────────────────────────

/**
 * 开关行：布尔，**立即生效**（规范 §5.3）。
 *
 * 两条硬约束：
 * - **不配「保存」按钮** —— 开关语义就是即时；
 * - 副作用写进 [sub]（例如「关闭只是不再读写磁盘，不清除已存内容」）。
 */
@Composable
fun SwitchRow(
    name: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
    sub: String? = null,
    divider: Boolean = true,
    statusChip: (@Composable () -> Unit)? = null,
    /** 图标语义色：传了就是图标牌，不传是裸灰图标。 */
    tint: TintRole? = null,
    /** 说明字号覆盖（默认取密度表）；「必须一行」的说明用它收小字号，见 [SettingsText]。 */
    subFontSize: TextUnit? = null,
    subLineHeight: TextUnit? = null,
    subMaxLines: Int = Int.MAX_VALUE,
    /**
     * 说明**独占一行、占满行宽**（画在名称与开关下方，左边缘与名称对齐）。
     *
     * 给「说明需要占满一行」的开关行用（主题页的悬浮玻璃导航栏）：跟着名称走的文本列在 360dp 屏上
     * 只有约 210dp，22 个字就要 9.5sp —— 低于可读线；换成整行宽（约 272dp）后 12sp 也放得下。
     * 默认 `false`，其余开关行不受影响。
     */
    subFullWidth: Boolean = false,
) {
    // 整行可点：点开关与点整行效果一致。Switch 自己会消费点击，不会双触发。
    SettingsRowShell(divider = divider, onClick = { onCheckedChange(!checked) }) {
        val d = LocalSettingsDensity.current
        if (sub != null && subFullWidth) {
            Column(Modifier.weight(1f).padding(vertical = d.textPadV)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (icon != null) RowIcon(icon, name, tint)
                    Text(
                        name,
                        fontSize = d.nameFontSize,
                        color = Primer.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(d.valueGap))
                    SwitchControl(checked, onCheckedChange)
                }
                Spacer(Modifier.height(d.subGap))
                Text(
                    sub,
                    fontSize = subFontSize ?: d.subFontSize,
                    lineHeight = subLineHeight ?: d.subLineHeight,
                    color = Primer.TextTertiary,
                    maxLines = subMaxLines,
                    overflow = TextOverflow.Ellipsis,
                    // 与名称左对齐：越过图标与图标间距
                    modifier = Modifier.padding(start = d.iconSize + d.iconGap),
                )
            }
        } else {
            if (icon != null) RowIcon(icon, name, tint)
            SettingsText(
                name = name,
                sub = sub,
                subFontSize = subFontSize,
                subLineHeight = subLineHeight,
                subMaxLines = subMaxLines,
            )
            if (statusChip != null) {
                Spacer(Modifier.width(d.valueGap))
                statusChip()
            }
            Spacer(Modifier.width(d.iconGap))
            SwitchControl(checked, onCheckedChange)
        }
    }
}

/** 开关本体（颜色统一：选中轨道 `Blue500`）。普通开关行与「说明整行宽」形态共用。 */
@Composable
private fun SwitchControl(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(checkedTrackColor = Primer.Blue500),
    )
}

// ───────────────────────── ③ 选择行（2–3 档枚举） ─────────────────────────

/**
 * 选择行：2–3 档枚举**就地可见**，取代「点一下循环」（规范 §5.2）。
 *
 * 为什么必须取代循环：用户看不到还有哪几档、无法一步到达目标档、误点一次越过一档且**无法撤销**。
 */
@Composable
fun <T> ChoiceRow(
    icon: ImageVector,
    name: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    sub: String? = null,
    divider: Boolean = true,
) {
    SettingsRowShell(divider = divider, onClick = null) {
        RowIcon(icon, name)
        SettingsText(name = name, sub = sub)
        Spacer(Modifier.width(LocalSettingsDensity.current.valueGap))
        SettingsSegment(options = options, selected = selected, onSelect = onSelect)
    }
}

/**
 * 分段控件（2–3 档）。
 *
 * 容器描边用 [Primer.BorderControl]（不是 `Border`）：它是**可交互控件**的边界，
 * 按 WCAG 1.4.11 要 ≥3:1；`Border` 是装饰性描边，只有 1.80:1。
 */
@Composable
fun <T> SettingsSegment(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Primer.Gray150)
            .border(1.dp, Primer.BorderControl, RoundedCornerShape(8.dp))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { (key, label) ->
            val on = key == selected
            Text(
                label,
                fontSize = 11.sp,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                color = selectionColor(on, on = Color.White, off = Primer.TextSecondary),
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(selectionColor(on, on = Primer.Blue500))
                    .clickable { onSelect(key) }
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            )
        }
    }
}

// ───────────────────────── ④ 动作行 ─────────────────────────

/** 动作行：触发一次性命令（导出、跳系统设置…）。**没有 `›`** —— 它不换页。 */
@Composable
fun ActionRow(
    icon: ImageVector,
    name: String,
    onClick: () -> Unit,
    sub: String? = null,
    hint: String? = null,
    divider: Boolean = true,
    /** 图标语义色：传了就是图标牌，不传是裸灰图标。 */
    tint: TintRole? = null,
) {
    SettingsRowShell(divider = divider, onClick = onClick) {
        RowIcon(icon, name, tint)
        SettingsText(name = name, sub = sub)
        if (hint != null) {
            Spacer(Modifier.width(ValueGap))
            Text(hint, fontSize = 11.sp, color = Primer.TextTertiary, maxLines = 1)
        }
    }
}

// ───────────────────────── ⑤ 危险行 ─────────────────────────

/**
 * 危险行：文字与图标走 [Primer.DangerText]（**不是**拿填充色 `Red500` 当文字 —— 那在浅色下
 * 只有 3.13:1，见规范 §7.2）。
 *
 * `onClick` 里**必须**打开二次确认；标题要带对象名（规范 §4.6 / §7.2）。
 */
@Composable
fun DangerRow(
    icon: ImageVector,
    name: String,
    onClick: () -> Unit,
    sub: String? = null,
    hint: String? = null,
    divider: Boolean = true,
) {
    SettingsRowShell(divider = divider, onClick = onClick) {
        RowIcon(icon, name, tint = TintRole.DANGER)
        SettingsText(name = name, sub = sub)
        if (hint != null) {
            Spacer(Modifier.width(LocalSettingsDensity.current.valueGap))
            Text(hint, fontSize = LocalSettingsDensity.current.valueFontSize, color = Primer.DangerText, maxLines = 1)
        }
    }
}

// ───────────────────────── ⑥ 只读行 ─────────────────────────

/**
 * 只读行：不可点、不可导航，但**值是拷贝源**（版本号 / 构建指纹这类内容用户要抄给开发者）。
 *
 * 规范 §4.1 把它列为 [`InfoRow`] 的唯一例外：整行点击 = 复制，不是换页。
 */
@Composable
fun InfoRow(
    icon: ImageVector,
    name: String,
    value: String,
    sub: String? = null,
    divider: Boolean = true,
    onCopy: ((String) -> Unit)? = null,
    statusChip: (@Composable () -> Unit)? = null,
    tint: TintRole? = null,
) {
    SettingsRowShell(
        divider = divider,
        onClick = onCopy?.let { { it(value) } },
    ) {
        RowIcon(icon, name)
        SettingsText(
            name = name,
            sub = sub,
            value = if (statusChip == null) value else null,
            valueSlot = statusChip,
        )
    }
}

// ───────────────────────── 附：禁用态 ─────────────────────────

/**
 * 导航行的**禁用态**（规范 §6.3）—— 不是第 7 种行型。
 *
 * 规范禁止「只把整行 `alpha(0.55f)` 了事」：用户既不知道**为什么不能点**，
 * 也不知道**怎么才能点**。这里三条一起给：
 * ① 视觉降级；② 说明列写明原因，且**原因不跟着降透明度**（它是禁用态唯一的出路）；
 * ③ 一个直达那个设置的按钮。
 *
 * ## [fixLabel] 默认值是**资源**，不是中文字面量
 *
 * 这里原先是 `fixLabel: String = "去设置"` —— 写死的中文，界面切英文后这个按钮仍是
 * 「去设置」（2026-09 真机：设置 → 代码与提交 → 本地仓库 那一行）。
 * 现在传 `null` 就走 [R.string.action_open_settings]，在**函数体内**用 `stringResource`
 * 解析（跟着 `LocalConfiguration` 走）；调用方要别的动词（例如「开启」）就自己传
 * `stringResource(...)` —— 传进来的仍然必须是**已解析的当前语言文案**。
 */
@Composable
fun DisabledNavRow(
    icon: ImageVector,
    name: String,
    reason: String,
    onFix: () -> Unit,
    fixLabel: String? = null,
    divider: Boolean = true,
    tint: TintRole? = null,
) {
    val fixText = fixLabel ?: stringResource(R.string.action_open_settings)
    SettingsRowShell(divider = divider, onClick = null) {
        Row(
            // 同 NavRow：吃掉份额，否则「去设置」按钮会跟在原因文字后面，而不是贴在行尾
            Modifier.weight(1f),
            // 与 NavRow 同一条规则：图标按整行垂直居中，不跟着首行文字往上飘（原型 `.row { align-items: center }`）
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 禁用态的半透明乘进颜色里，不用 `Modifier.alpha` ——
            // `Modifier.alpha` 就是 graphicsLayer（AlphaKt → graphicsLayer），
            // 而这里只有实色图标与实色文字，乘 alpha 与整层降透明在观感上等价。
            Icon(
                icon,
                contentDescription = name,
                tint = Primer.IconSecondary.copy(alpha = DisabledAlpha),
                modifier = Modifier.size(LocalSettingsDensity.current.iconSize),
            )
            Spacer(Modifier.width(LocalSettingsDensity.current.iconGap))
            Column(Modifier.weight(1f).padding(vertical = LocalSettingsDensity.current.textPadV)) {
                Text(
                    name,
                    fontSize = LocalSettingsDensity.current.nameFontSize,
                    color = Primer.TextPrimary.copy(alpha = DisabledAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(LocalSettingsDensity.current.subGap))
                // 原因**不降透明度**：它是这一行唯一能告诉用户「怎么办」的东西
                Text(
                    reason,
                    fontSize = LocalSettingsDensity.current.subFontSize,
                    lineHeight = LocalSettingsDensity.current.subLineHeight,
                    color = Primer.TextSecondary,
                )
            }
        }
        Spacer(Modifier.width(LocalSettingsDensity.current.valueGap))
        Text(
            fixText,
            fontSize = LocalSettingsDensity.current.valueFontSize,
            fontWeight = FontWeight.SemiBold,
            color = Primer.AccentText,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(7.dp))
                .border(1.dp, Primer.BorderControl, RoundedCornerShape(7.dp))
                .clickable(onClick = onFix)
                .padding(horizontal = 9.dp, vertical = 5.dp),
        )
    }
}

// ───────────────────────── 说明段落（不是行型） ─────────────────────────

/**
 * 说明段落：**不是行型**，也**不许拿 [InfoRow] 冒充散文** ——
 * 冒充出来的「行」没有名称列也没有值列，只是借了行的排版，会让「行型统计」失真
 * （原型第一版就踩过，见 `design/settings-redesign/README.md` §7.5）。
 */
@Composable
fun SettingsProse(text: String, divider: Boolean = true) {
    Column(Modifier.fillMaxWidth()) {
        if (divider) SettingsDivider()
        Text(
            text,
            fontSize = LocalSettingsDensity.current.subFontSize,
            lineHeight = LocalSettingsDensity.current.subLineHeight,
            color = Primer.TextTertiary,
            modifier = Modifier.padding(horizontal = LocalSettingsDensity.current.rowPaddingH, vertical = 12.dp),
        )
    }
}

/**
 * 说明弹窗：把**长篇说明**从页面上收进一个可滚动弹窗（2026-11 决策）。
 *
 * ## 为什么有这个组件
 *
 * 沉浸式翻译页原先在页尾摊着一段 570+ 字的「说明」，外加三个开关行各自的 44–99 字说明 ——
 * 一页三分之一的滚动高度是「读文档」。决策是：行内只留一句**必要**的副作用说明（§6.2），
 * 其余收进弹窗，由页头右上角的「!」徽标打开。
 *
 * 它和 `AlertDialog` 用在二次确认的场合**不同**：这里只有一个「知道了」，没有破坏性动作，
 * 也不写对象名 —— 它回答的是「这一页怎么用」，不是「删不删」。
 *
 * ## 为什么正文是 [bullets] 而不是一整段
 *
 * 传进来的一整段在多语言下没法换行/分段（译文行宽与中文差很多），也没法让译者在条目之间
 * 独立调整语序。每条一句话、由 UI 画 `•`，弹窗里天然是清单，扫读成本比一大段低。
 *
 * @param bullets 每条一句（**不要**自己带 `•`），文案必须来自资源
 */
@Composable
fun SettingsNotesDialog(
    title: String,
    bullets: List<String>,
    onDismiss: () -> Unit,
    confirmLabel: String? = null,
) {
    val confirm = confirmLabel ?: stringResource(R.string.action_got_it)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
        },
        text = {
            // 说明可能长（翻译页 11 条）：给上限 + 自己滚，别把弹窗撑出屏幕 ——
            // 撑出屏幕时 AlertDialog 会把按钮顶掉，用户只剩返回键一条路。
            //
            // 右侧常驻滚动条：AlertDialog 的滚动区没有系统滚动条，用户看首屏
            // 不知道下面还有；条本身可拖，不只是装饰。
            val state = rememberScrollState()
            var viewport by remember { mutableIntStateOf(0) }
            var content by remember { mutableIntStateOf(0) }
            Box(Modifier.fillMaxWidth().heightIn(max = 420.dp).onSizeChanged { viewport = it.height }) {
                Column(
                    // 右侧留 10dp 给滚动条：文字不压在条下面，条的命中区仍是 20dp
                    Modifier.fillMaxWidth().padding(end = 10.dp).verticalScroll(state),
                ) {
                    Column(
                        Modifier.fillMaxWidth().onSizeChanged { content = it.height },
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        bullets.forEach { bullet ->
                            Row(verticalAlignment = Alignment.Top) {
                                Text("•", fontSize = 13.sp, lineHeight = 19.sp, color = Primer.TextTertiary)
                                Spacer(Modifier.width(6.dp))
                                Text(bullet, fontSize = 13.sp, lineHeight = 19.sp, color = Primer.TextSecondary)
                            }
                        }
                    }
                }
                NotesScrollbar(
                    state = state,
                    viewportPx = viewport,
                    contentPx = content,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(confirm, color = Primer.AccentText) }
        },
    )
}

/**
 * 说明弹窗的**可拖动滚动条**（2026-11 第六轮）。
 *
 * 只在这个弹窗里用，所以不抽公共组件：它的尺寸口径（20dp 命中区 / 3dp 条 / 28dp 最小拇指）
 * 是照「弹窗内滚动」定的，放大到别的列表反而会碍事。
 *
 * 拖拽用 `scrollTo` 而不是 `dispatchRawDelta`：要把「拇指走了多少像素」换算成
 * 「内容滚了多少像素」（比例 = maxValue / 可走轨道），两者不是 1:1。
 */
@Composable
private fun NotesScrollbar(
    state: ScrollState,
    viewportPx: Int,
    contentPx: Int,
    modifier: Modifier = Modifier,
) {
    // 内容没超出视口就不画：画一条拖不动的灰条比没有更让人困惑
    if (viewportPx <= 0 || contentPx <= viewportPx) return
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val maxScroll = state.maxValue.toFloat().coerceAtLeast(1f)
    val thumbPx = (viewportPx * (viewportPx.toFloat() / contentPx))
        .coerceAtLeast(with(density) { 28.dp.toPx() })
        .coerceAtMost(viewportPx.toFloat())
    val trackPx = (viewportPx - thumbPx).coerceAtLeast(1f)
    val offsetPx = (trackPx * (state.value / maxScroll)).roundToInt()

    Box(
        modifier
            .width(20.dp)
            .fillMaxHeight()
            .pointerInput(contentPx, viewportPx) {
                detectVerticalDragGestures { _, dragAmount ->
                    // 拖动量 × （内容可滚距离 / 轨道可走距离）
                    val delta = dragAmount * (maxScroll / trackPx)
                    scope.launch {
                        state.scrollTo((state.value + delta).roundToInt().coerceIn(0, state.maxValue))
                    }
                }
            },
        contentAlignment = Alignment.TopEnd,
    ) {
        // 轨道：只做「这里可以滚」的暗示，颜色最淡
        Box(
            Modifier.align(Alignment.CenterEnd).width(3.dp).fillMaxHeight()
                .background(Primer.Gray150, RoundedCornerShape(2.dp)),
        )
        // 拇指：跟着 state.value 走；宽度 3dp 在浅色 / 深色下都看得见
        Box(
            Modifier.align(Alignment.TopEnd)
                .offset { IntOffset(0, offsetPx) }
                .width(3.dp)
                .height(with(density) { thumbPx.toDp() })
                .background(Primer.Gray500, RoundedCornerShape(2.dp)),
        )
    }
}

// ───────────────────────── 表单：输入（不是行型） ─────────────────────────

/**
 * L2 输入页的表单块：标签 + 输入框 + **紧贴的**反馈 + 动作按钮（规范 §5.5 / §6.4）。
 *
 * 规范禁止把输入框放在设置主页，也禁止把反馈渲染到页尾 ——
 * 现状 Git 代理就是主页 `AlertDialog` + 页尾 `proxyFeedback` 的组合，
 * 对话框一关，用户看到的只有页面最底部一行小字。
 *
 * 输入框不是「行型」：它没有名称列/值列，也不参与整行点击，所以不进 §4.1 的封闭集合。
 */
@Composable
fun SettingsField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String? = null,
    isError: Boolean = false,
    feedback: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = RowPaddingH, vertical = 12.dp)) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextSecondary)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = isError,
            placeholder = placeholder?.let { { Text(it, fontSize = 12.sp, color = Primer.TextTertiary) } },
        )
        if (feedback != null) {
            Spacer(Modifier.height(7.dp))
            feedback()
        }
        if (actions != null) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

/** 输入框下方的一行反馈：成功用 `SuccessTextStrong`、失败用 `DangerText`（规范 §6.4）。 */
@Composable
fun SettingsFieldFeedback(text: String, ok: Boolean) {
    Text(
        (if (ok) "✓ " else "✕ ") + text,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        color = if (ok) Primer.SuccessTextStrong else Primer.DangerText,
    )
}

// ───────────────────────── 状态胶囊 ─────────────────────────

/** 状态胶囊的语气（规范 §4.3）：前景走语义**文字**色，底色走对应 Surface。 */
enum class StatusTone { OK, WARN, BAD, INFO, MUTE }

/**
 * 状态胶囊：值若是**状态**（账号校验 / 权限 / 签名）而不是**配置**时用它，
 * 而不是一句灰字 —— 状态需要一眼分辨「好 / 警告 / 坏」。
 */
@Composable
fun StatusChip(text: String, tone: StatusTone) {
    val (fg, bg) = when (tone) {
        StatusTone.OK -> Primer.SuccessTextStrong to Primer.SuccessSurface
        StatusTone.WARN -> Primer.WarningText to Primer.WarningSurface
        StatusTone.BAD -> Primer.DangerText to Primer.DangerSurface
        StatusTone.INFO -> Primer.AccentText to Primer.InfoSurface
        StatusTone.MUTE -> Primer.TextTertiary to Primer.Gray150
    }
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = fg,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        // 圆角交给 background 自己画（`background(color, shape)` 走 outline，不建图层）；
        // 这里没有 clickable，用不上 `clip` —— 而 `Modifier.clip` 就是
        // `graphicsLayer(shape, clip=true)`，白建一层 RenderNode。
        // （带水波纹的地方**不能**这么换：clip 还负责把水波纹裁进圆角。）
        modifier = Modifier
            .background(bg, RoundedCornerShape(999.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}
