package com.branchbase.ui.profile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.branchbase.R
import com.branchbase.translate.TranslateProtocol
import com.branchbase.ui.theme.Primer

/**
 * 翻译页「凭据与模型」块用到的两个弹窗与小控件（2026-11 第六轮）。
 *
 * 单独成文件的原因：它们都是**界面过程**（探测 → 勾选 → 二次确认 → 落盘），
 * 不该把 `TranslateSettingsScreen` 的编排逻辑再撑长三百行；逻辑仍在页面里，
 * 这里只放「长什么样、点哪个按钮」。
 */

/**
 * 紧凑输入框：标签在框上方，框高 28dp。
 *
 * ## 1.4.48 修的两件事（真机迭代到这一版）
 *
 * 1. **光标上偏**：占位符（12.5sp）与输入行（13sp）原先用各自默认行盒，外层 Box 取较高者，
 *    输入行被顶到 Box 顶部 —— 光标就比文字高出一截。现在两者都固定 `lineHeight = 16.sp`、
 *    内层 Box 用 `CenterStart` 对齐，光标与文字在同一条行盒里；聚焦后不再画占位符
 *    （否则光标会压住占位符第一个字）。
 * 2. **框高再砍**：34 → 28dp（13sp 行高 + 上下 6dp）。标签仍在框上方（10.5sp + 2dp），
 *    不叠框线。28dp 低于 44dp 推荐命中区 —— 输入框不是开关（点进去才聚焦、旁边有卡片留白），
 *    真机仍觉得难点只改 `minHeight` 一处。
 *
 * [headerAction] 放标签行右侧（模型的「获取模型列表」）；[trailingContent] 放框内右侧
 * （Key 的「显示 / 隐藏」）。
 */
@Composable
internal fun CompactField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    mono: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    headerAction: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    // 聚焦后不再画占位符：BasicTextField 的光标从 x=0 起，会正好压住占位符第一个字
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        // 三个字段的标签行统一 20dp：模型行的徽章高约 20dp，另外两行如果只有文字高度，
        // 三个框的起始位置就会参差（真机反馈「密钥框高度对不齐」的一半原因）
        Row(
            Modifier.fillMaxWidth().heightIn(min = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = Primer.TextSecondary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (headerAction != null) headerAction()
        }
        Spacer(Modifier.height(2.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(
                fontSize = 13.sp,
                // 固定行高：光标高度跟它走，不再让占位符的默认行盒把输入行顶高
                lineHeight = 16.sp,
                color = Primer.TextPrimary,
                fontFamily = if (mono) FontFamily.Monospace else null,
            ),
            visualTransformation = visualTransformation,
            cursorBrush = SolidColor(Primer.Blue500),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            decorationBox = { innerTextField ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, Primer.BorderControl, RoundedCornerShape(8.dp))
                        .heightIn(min = 28.dp)
                        .padding(horizontal = 9.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty() && !focused) {
                            Text(
                                placeholder,
                                fontSize = 12.5.sp,
                                lineHeight = 16.sp,
                                color = Primer.TextTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerTextField()
                    }
                    if (trailingContent != null) {
                        Spacer(Modifier.width(8.dp))
                        trailingContent()
                    }
                }
            },
        )
    }
}

/** 「获取模型列表」蓝底徽章按钮（11sp，比行内说明略重、但不抢行内主操作）。 */
@Composable
internal fun ModelListBadge(loading: Boolean, onClick: () -> Unit) {
    val background = if (loading) Primer.Gray150 else Primer.Blue500
    val foreground = if (loading) Primer.TextTertiary else Color.White
    Text(
        stringResource(if (loading) R.string.translate_models_fetching else R.string.translate_models_fetch),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = foreground,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .clickable(enabled = !loading) { onClick() }
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/**
 * 模型列表弹窗：弹簧缩放进场（决策六的「弹性动画弹窗」），勾选后点「保存」。
 *
 * 勾选状态是**弹窗自己的**：关掉弹窗等于放弃这次选择；页面要保留上一次结果时
 * 必须在关闭前把它交出去（页面用 `fetchedModels` 决定是否还显示）。
 * [initiallyChecked] 只用于初始值（已保存的本地列表 ∩ 本次探测结果 + 当前模型）。
 */
@Composable
internal fun ModelPickerDialog(
    models: List<String>,
    initiallyChecked: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    var checked by remember { mutableStateOf(initiallyChecked.filter { it in models }.toSet()) }
    var shown by remember { mutableStateOf(false) }
    // 进场用弹簧：Dialog 的窗口本身没有过渡，先挂载再翻转 visible 才能看到缩放
    LaunchedEffect(Unit) { shown = true }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        AnimatedVisibility(
            visible = shown,
            enter = scaleIn(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                initialScale = 0.86f,
            ) + fadeIn(tween(140)),
            exit = scaleOut(targetScale = 0.92f) + fadeOut(tween(110)),
        ) {
            Column(
                Modifier
                    .padding(horizontal = 24.dp)
                    .fillMaxWidth()
                    .background(Primer.BackgroundPrimary, RoundedCornerShape(16.dp))
                    .padding(16.dp),
            ) {
                Text(
                    stringResource(R.string.translate_models_title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Primer.TextPrimary,
                )
                Spacer(Modifier.height(8.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    models.forEach { model ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    checked = if (model in checked) checked - model else checked + model
                                }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = model in checked,
                                onCheckedChange = {
                                    checked = if (it) checked + model else checked - model
                                },
                            )
                            Text(model, fontSize = 13.sp, color = Primer.TextPrimary, maxLines = 1)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.action_cancel), color = Primer.TextSecondary)
                    }
                    TextButton(
                        onClick = { onConfirm(checked) },
                        enabled = checked.isNotEmpty(),
                    ) {
                        Text(
                            stringResource(R.string.action_save),
                            color = if (checked.isEmpty()) Primer.TextTertiary else Primer.AccentText,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 二次确认：模型列表要**落在本机**（决策六）。
 *
 * 正文是产品口径的原文（用户 2026-11-26 给定）：官方版本不上传含接口地址 / 密钥 / 模型列表的
 * 配置、无任何合作、卸载后无法恢复；任何提供「配置恢复」的版本都视为被篡改的恶意版本。
 * 这段只在**保存前**出现一次，不写进长期说明弹窗（它不是使用方法，是安全边界）。
 */
@Composable
internal fun ModelLocalSaveDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.translate_models_privacy_title),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Primer.TextPrimary,
            )
        },
        text = {
            Column(
                Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    stringResource(R.string.translate_models_privacy_body),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = Primer.TextSecondary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.translate_models_privacy_confirm), color = Primer.AccentText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel), color = Primer.TextSecondary)
            }
        },
    )
}

/**
 * 「协议」字段：外观与 [CompactField] 完全一致（标签 10.5sp + 28dp 框），但**不可输入** ——
 * 点一下打开 [ProtocolPickerDialog]。
 *
 * 为什么不做成真正的一行 `ActionRow`：四个字段连着排，行型换一种就会破坏「标签 / 值 / 框线」
 * 对齐（1.4.48 真机反馈过的同一个坑）。协议是这个块里的**第一个字段**，形态必须与另外三个一致。
 *
 * 28dp 低于 44dp 命中区 —— 与 [CompactField] 同一条边界说明（§19.4）：输入类控件旁边有卡片留白，
 * 真机仍觉得难点只改 [CompactField] 的 `minHeight` 与这里的 `heightIn` 两处。
 */
@Composable
internal fun ProtocolField(
    label: String,
    value: String,
    hint: String,
    onClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = Primer.TextSecondary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(2.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, Primer.BorderControl, RoundedCornerShape(8.dp))
                .clickable { onClick() }
                .heightIn(min = 28.dp)
                .padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                value,
                fontSize = 13.sp,
                // 与输入框同一个行高：不加这一条，四种字段的框高会差 2dp（1.4.48 的根因）
                lineHeight = 16.sp,
                color = Primer.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                hint,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Primer.Blue500,
                maxLines = 1,
            )
        }
    }
}

/**
 * 协议单选弹窗（2026-11 第八轮）：弹簧缩放进场，点一项**即生效并关窗**。
 *
 * 为什么不像模型列表那样「勾选 → 保存 → 二次确认」：
 * - 单选没有「多选框」这种中间态，点一下意图就完整了；
 * - 它不涉及隐私边界（写盘的是协议码，不是 Key / 地址 / 模型列表），
 *   二次确认那一套是为「把配置落在本机」准备的，这里没有可确认的内容。
 *
 * 行复用 [ModeOptionRow]：与设置里其它 4–7 档单选（语言 / 主题 / 提交模式）同一个形态与配色，
 * 用户不必在弹窗里认一套新的单选框。
 *
 * [recommended] 是从**当前地址**猜出来的协议（`TranslateProtocol.guessFrom`），只作标记：
 * 猜错时不打扰（不预选、不自动切换），猜对时用户少想一步。
 */
@Composable
internal fun ProtocolPickerDialog(
    current: TranslateProtocol,
    recommended: TranslateProtocol?,
    onDismiss: () -> Unit,
    onSelect: (TranslateProtocol) -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    // 进场用弹簧：Dialog 的窗口本身没有过渡，先挂载再翻转 visible 才能看到缩放
    LaunchedEffect(Unit) { shown = true }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        AnimatedVisibility(
            visible = shown,
            enter = scaleIn(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                initialScale = 0.86f,
            ) + fadeIn(tween(140)),
            exit = scaleOut(targetScale = 0.92f) + fadeOut(tween(110)),
        ) {
            Column(
                Modifier
                    .padding(horizontal = 24.dp)
                    .fillMaxWidth()
                    .background(Primer.BackgroundPrimary, RoundedCornerShape(16.dp))
                    .padding(16.dp),
            ) {
                Text(
                    stringResource(R.string.translate_protocol_title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Primer.TextPrimary,
                )
                Spacer(Modifier.height(8.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    TranslateProtocol.entries.forEach { protocol ->
                        ModeOptionRow(
                            label = stringResource(protocol.labelRes) +
                                if (protocol == recommended) {
                                    " · " + stringResource(R.string.translate_protocol_recommended)
                                } else {
                                    ""
                                },
                            desc = stringResource(protocol.descRes),
                            selected = protocol == current,
                        ) {
                            onSelect(protocol)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.action_cancel), color = Primer.TextSecondary)
                    }
                }
            }
        }
    }
}
