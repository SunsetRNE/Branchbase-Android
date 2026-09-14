package com.branchbase.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.ui.theme.Primer
import com.branchbase.ui.theme.selectionColor

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
    Column(modifier.fillMaxWidth().padding(horizontal = SectionPadH, vertical = SectionPadV)) {
        if (label != null) {
            Text(
                label,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Primer.TextTertiary,
                modifier = Modifier.padding(start = LabelPadStart, bottom = 6.dp),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(CardRadius))
                .background(Primer.BackgroundSecondary)
                // 卡片描边是**装饰性**的，用 Gray200（neutralBorder）而不是 BorderControl
                .border(1.dp, Primer.Gray200, RoundedCornerShape(CardRadius)),
            content = content,
        )
    }
}

/** 组内分隔线（画在行的**顶部**；每组第一行不画）。 */
@Composable
private fun SettingsRowDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Primer.Gray200))
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
    Column(Modifier.fillMaxWidth()) {
        if (divider) SettingsRowDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
                .defaultMinSize(minHeight = RowMinHeight)
                .padding(horizontal = RowPaddingH),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** 行首图标（20dp，`IconSecondary`；危险行由调用方换成 `DangerText`）。 */
@Composable
private fun RowScope.RowIcon(icon: ImageVector, name: String, tint: Color = Primer.IconSecondary) {
    Icon(icon, contentDescription = name, tint = tint, modifier = Modifier.size(IconSize))
    Spacer(Modifier.width(IconGap))
}

/**
 * 名称 +（可选）说明 +（可选）值。
 *
 * **值负责省略，不许值去挤名称**（规范 §4.2）：名称/说明是一个 `weight(1f, fill = false)`
 * 的块，值拿另一个权重盒并右对齐 —— 名称短时把余量让给值，名称长时值自己省略。
 */
@Composable
private fun RowScope.SettingsText(
    name: String,
    sub: String? = null,
    value: String? = null,
    valueSlot: (@Composable () -> Unit)? = null,
) {
    Column(Modifier.weight(1f, fill = false).padding(vertical = 11.dp)) {
        Text(
            name,
            fontSize = 14.sp,
            color = Primer.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (sub != null) {
            Spacer(Modifier.height(3.dp))
            Text(sub, fontSize = 12.sp, lineHeight = 17.sp, color = Primer.TextTertiary)
        }
    }
    if (valueSlot != null) {
        Spacer(Modifier.width(ValueGap))
        // 权重盒：由它负责「不够就省略」，而不是回头去挤名称
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { valueSlot() }
    } else if (value != null) {
        Spacer(Modifier.width(ValueGap))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            Text(
                value,
                fontSize = 12.sp,
                color = Primer.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
            )
        }
    }
}

// ───────────────────────── ① 导航行 ─────────────────────────

/** 导航行：进子页。有说明时整行变高，`›` 仍垂直居中。 */
@Composable
fun NavRow(
    icon: ImageVector,
    name: String,
    onClick: () -> Unit,
    value: String? = null,
    sub: String? = null,
    divider: Boolean = true,
    enabled: Boolean = true,
) {
    SettingsRowShell(divider = divider, onClick = onClick, enabled = enabled) {
        Row(Modifier.weight(1f, fill = false).alpha(if (enabled) 1f else 0.5f)) {
            RowIcon(icon, name)
            SettingsText(name = name, sub = sub, value = value)
        }
        // 不可点时**不画 `›`** —— 画了就是在承诺「点得进去」
        if (enabled) {
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = Primer.TextTertiary,
                modifier = Modifier.size(20.dp),
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
) {
    // 整行可点：点开关与点整行效果一致。Switch 自己会消费点击，不会双触发。
    SettingsRowShell(divider = divider, onClick = { onCheckedChange(!checked) }) {
        if (icon != null) RowIcon(icon, name)
        SettingsText(name = name, sub = sub)
        if (statusChip != null) {
            Spacer(Modifier.width(ValueGap))
            statusChip()
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Primer.Blue500),
        )
    }
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
        Spacer(Modifier.width(ValueGap))
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
) {
    SettingsRowShell(divider = divider, onClick = onClick) {
        RowIcon(icon, name)
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
        RowIcon(icon, name, tint = Primer.DangerText)
        SettingsText(name = name, sub = sub)
        if (hint != null) {
            Spacer(Modifier.width(ValueGap))
            Text(hint, fontSize = 11.sp, color = Primer.DangerText, maxLines = 1)
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
 */
@Composable
fun DisabledNavRow(
    icon: ImageVector,
    name: String,
    reason: String,
    onFix: () -> Unit,
    fixLabel: String = "去设置",
    divider: Boolean = true,
) {
    SettingsRowShell(divider = divider, onClick = null) {
        Row(Modifier.weight(1f, fill = false)) {
            Box(Modifier.alpha(0.5f)) { Icon(icon, contentDescription = name, tint = Primer.IconSecondary, modifier = Modifier.size(IconSize)) }
            Spacer(Modifier.width(IconGap))
            Column(Modifier.weight(1f, fill = false).padding(vertical = 11.dp)) {
                Text(
                    name,
                    fontSize = 14.sp,
                    color = Primer.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alpha(0.5f),
                )
                Spacer(Modifier.height(3.dp))
                // 原因**不降透明度**：它是这一行唯一能告诉用户「怎么办」的东西
                Text(reason, fontSize = 12.sp, lineHeight = 17.sp, color = Primer.TextSecondary)
            }
        }
        Spacer(Modifier.width(ValueGap))
        Text(
            fixLabel,
            fontSize = 12.sp,
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
        if (divider) SettingsRowDivider()
        Text(
            text,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = Primer.TextTertiary,
            modifier = Modifier.padding(horizontal = RowPaddingH, vertical = 12.dp),
        )
    }
}

/**
 * 分节标题（**无卡片**的旧版布局，用于尚未迁移到 [SettingsSection] 的页面）。
 *
 * 规范 §4.4 允许保留分节标题，但要求**同一棵设置树里必须统一** ——
 * 所以这个函数是**过渡态**，不是第二种标准：
 * 设置主页与通知页已用卡片式 [SettingsSection]；沉浸式翻译页仍在用它
 * （该页不在本轮原型范围内）。等那一页迁移完，这个函数应当删除。
 */
@Composable
fun SettingsSectionTitle(title: String) {
    Text(
        title,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = Primer.TextTertiary,
        modifier = Modifier.padding(start = RowPaddingH, end = RowPaddingH, top = 16.dp, bottom = 6.dp),
    )
}

/**
 * 账户行（设置主页第 1 组）—— 头像 + 登录名 + 服务器 + 状态胶囊。
 *
 * 它不是 §4.1 的 6 种行型之一，而是**账户卡**：
 * 比普通行高（40dp 头像），并且状态胶囊要在一屏内可读 ——
 * 「令牌失效 / 触发限流」这类状态必须第一眼看到，而不是点进账号管理才发现。
 */
@Composable
fun AccountRow(
    login: String?,
    host: String?,
    statusLabel: String?,
    statusTone: StatusTone,
    onClick: () -> Unit,
) {
    SettingsRowShell(divider = false, onClick = onClick) {
        Box(
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(999.dp)).background(Primer.Gray150),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                login?.take(1)?.uppercase() ?: "?",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Primer.TextSecondary,
            )
        }
        Spacer(Modifier.width(IconGap))
        Column(Modifier.weight(1f, fill = false).padding(vertical = 14.dp)) {
            Text(
                login ?: "未登录",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Primer.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                host ?: "登录后才能同步仓库与通知",
                fontSize = 12.sp,
                color = Primer.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (statusLabel != null) {
            Spacer(Modifier.width(ValueGap))
            StatusChip(statusLabel, statusTone)
        }
        Spacer(Modifier.width(6.dp))
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = Primer.TextTertiary,
            modifier = Modifier.size(20.dp),
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
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}
