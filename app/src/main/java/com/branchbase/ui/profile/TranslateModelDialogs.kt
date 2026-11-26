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
import com.branchbase.translate.ApiAddress
import com.branchbase.translate.TranslateProtocol
import com.branchbase.translate.TranslateThinking
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

/**
 * 蓝底徽章按钮（11sp，比行内说明略重、但不抢行内主操作）。
 *
 * 默认文案是「获取模型列表」，也用来挂别的行内动作（1.4.55 的「测试翻译」）：
 * 两处只是文案不同，形态与命中区必须一致，否则同一块里会出现两种徽章。
 */
@Composable
internal fun ModelListBadge(
    loading: Boolean,
    textRes: Int = R.string.translate_models_fetch,
    loadingTextRes: Int = R.string.translate_models_fetching,
    onClick: () -> Unit,
) {
    val background = if (loading) Primer.Gray150 else Primer.Blue500
    val foreground = if (loading) Primer.TextTertiary else Color.White
    Text(
        stringResource(if (loading) loadingTextRes else textRes),
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
    /** 标签行右侧的动作（思维深度行用它挂「测试翻译」，与模型行的「获取模型列表」同款）。 */
    headerAction: (@Composable () -> Unit)? = null,
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
            if (headerAction != null) headerAction()
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

/** 「官方地址」徽章：与「获取模型列表」同款蓝底徽章，点开地址列表。 */
@Composable
internal fun AddressListBadge(onClick: () -> Unit) {
    Text(
        stringResource(R.string.translate_address_list),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color.White,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Primer.Blue500)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/**
 * 接口地址单选弹窗（1.4.54）：**协议只知道「怎么说」，对谁说在这里选**。
 *
 * 列表来自 `TranslateProtocol.addresses`（官方地址 + 文档明示的兼容入口），
 * 最后一行是「自定义地址」——它不写任何值，只关窗，把选择权交回输入框
 * （中转 / 自建网关 / 只填域名都在那儿改）。
 *
 * 选中状态按**规范化后的地址**比：用户手抄时多一个尾斜杠不该让「当前」消失。
 */
@Composable
internal fun AddressPickerDialog(
    protocol: TranslateProtocol,
    currentBaseUrl: String,
    onDismiss: () -> Unit,
    onSelect: (ApiAddress) -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
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
                    stringResource(R.string.translate_address_title),
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
                    val current = currentBaseUrl.trim().trimEnd('/').lowercase()
                    protocol.addresses.forEach { address ->
                        ModeOptionRow(
                            label = stringResource(address.addressLabelRes),
                            desc = address.baseUrl,
                            selected = current.isNotEmpty() &&
                                current == address.baseUrl.trimEnd('/').lowercase(),
                        ) {
                            onSelect(address)
                        }
                    }
                    ModeOptionRow(
                        label = stringResource(R.string.translate_address_custom),
                        desc = stringResource(R.string.translate_address_custom_desc),
                        selected = current.isEmpty() || protocol.addressOf(currentBaseUrl) == null,
                        divider = true,
                    ) {
                        onDismiss()
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

/**
 * 保存前的兼容性提醒（用户 2026-11-26 决策四）：只在**协议变了**时出现。
 *
 * 三句话分别对应三条真会踩的坑：Key 格式（空格 / 换行 / 前缀）、厂商对协议的兼容性
 * （选错协议服务端回 401 / 404 / 400，不是「翻译失败」）、以及「先测端口再谈翻译」。
 * 它不是每次保存都弹 —— 那会变成噪音，用户会闭眼点「已了解」。
 */
@Composable
internal fun ProtocolWarningDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.translate_warning_title),
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
                    stringResource(R.string.translate_warning_body),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = Primer.TextSecondary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.translate_warning_confirm), color = Primer.AccentText)
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
 * 思维深度单选弹窗（1.4.55）：Off / Low / High / Max / 自定义。
 *
 * 档位顺序与「推荐」标记都按用户口径：**推荐最低档**（日常翻译够用且更快），
 * 高档位只是满足「想要更细」的调节需求，Max 会明显更慢 —— 说明里如实写出来。
 */
@Composable
internal fun ThinkingPickerDialog(
    current: TranslateThinking,
    onDismiss: () -> Unit,
    onSelect: (TranslateThinking) -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
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
                    stringResource(R.string.translate_thinking_title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Primer.TextPrimary,
                )
                Spacer(Modifier.height(8.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    TranslateThinking.entries.forEach { level ->
                        ModeOptionRow(
                            label = stringResource(level.labelRes) +
                                if (level == TranslateThinking.LOW) {
                                    " · " + stringResource(R.string.translate_protocol_recommended)
                                } else {
                                    ""
                                },
                            desc = stringResource(level.descRes),
                            selected = level == current,
                        ) {
                            onSelect(level)
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

/** 档位名（模块只有 code，文案在 app 层）。 */
internal val TranslateThinking.labelRes: Int
    get() = when (this) {
        TranslateThinking.OFF -> R.string.translate_thinking_off
        TranslateThinking.LOW -> R.string.translate_thinking_low
        TranslateThinking.HIGH -> R.string.translate_thinking_high
        TranslateThinking.MAX -> R.string.translate_thinking_max
        TranslateThinking.CUSTOM -> R.string.translate_thinking_custom
    }

/** 档位说明（每行必须说清「代价」，用户才知道自己换来了什么）。 */
internal val TranslateThinking.descRes: Int
    get() = when (this) {
        TranslateThinking.OFF -> R.string.translate_thinking_off_desc
        TranslateThinking.LOW -> R.string.translate_thinking_low_desc
        TranslateThinking.HIGH -> R.string.translate_thinking_high_desc
        TranslateThinking.MAX -> R.string.translate_thinking_max_desc
        TranslateThinking.CUSTOM -> R.string.translate_thinking_custom_desc
    }

/**
 * 状态徽章（1.4.58）：把「这个值到底会不会生效」从一行小字升级成**带色的标签**。
 *
 * 为什么需要：小字（10sp、第三级灰）在真机上极易被跳过，而「自定义值不会被发出去」
 * 恰恰是那种「看起来设置了、实际没生效」的问题 —— 必须靠颜色与位置，而不是靠读句子。
 *
 * 三种语义：将生效（绿）/ 等同关闭（灰）/ 可能被拒（红）。文字极短，
 * 详细原因仍由下面那行说明承担（徽章说不下）。
 */
@Composable
internal fun StatusBadge(text: String, tone: BadgeTone) {
    val (fg, bg) = when (tone) {
        BadgeTone.OK -> Primer.SuccessTextStrong to Primer.SuccessSurface
        BadgeTone.OFF -> Primer.TextTertiary to Primer.Gray150
        BadgeTone.RISKY -> Primer.DangerText to Primer.DangerSurface
    }
    Text(
        text,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 12.sp,
        color = fg,
        maxLines = 1,
        modifier = Modifier
            .background(bg, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

/** 徽章语义（颜色由它决定，文案由调用方给）。 */
internal enum class BadgeTone { OK, OFF, RISKY }

/**
 * 自定义值有问题时的保存确认（1.4.58）：**只在判定不是「将生效」时**出现一次。
 *
 * 这不是「替用户决策」，而是「让无效的配置必须被看见一次」：徽章 + 一行说明仍可能被跳过，
 * 但保存动作是用户一定会做的那一步。点「仍要保存」照常保存。
 */
@Composable
internal fun CustomValueWarningDialog(
    value: String,
    reason: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.translate_thinking_custom_warn_title),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Primer.TextPrimary,
            )
        },
        text = {
            Text(
                stringResource(R.string.translate_thinking_custom_warn_body, value.trim().ifEmpty { "—" }, reason),
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = Primer.TextSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.translate_thinking_custom_warn_confirm), color = Primer.AccentText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel), color = Primer.TextSecondary)
            }
        },
    )
}
