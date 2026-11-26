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
import com.branchbase.ui.theme.Primer

/**
 * 翻译页「凭据与模型」块用到的两个弹窗与小控件（2026-11 第六轮）。
 *
 * 单独成文件的原因：它们都是**界面过程**（探测 → 勾选 → 二次确认 → 落盘），
 * 不该把 `TranslateSettingsScreen` 的编排逻辑再撑长三百行；逻辑仍在页面里，
 * 这里只放「长什么样、点哪个按钮」。
 */

/**
 * 紧凑输入框（40dp 高，Material 的 `OutlinedTextField` 默认 56dp）。
 *
 * 为什么不用 `OutlinedTextField`：决策三要求「凭据块进一步提高密度」，而 Material3 的
 * 输入框高度由内部固定 padding 决定，外部 `height()` 会裁字。这里用 [BasicTextField]
 * 自绘边框与占位符：13sp 文字 + 上下 9dp，命中区仍 ≥40dp；描边走 `BorderControl`
 * （WCAG 1.4.11 的 3:1 控件边界），不是装饰性 `Border`。
 *
 * [headerAction] 放在标签行右侧（模型那一行放「获取模型列表」徽章）。
 */
@Composable
internal fun CompactField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    mono: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: String? = null,
    onTrailingClick: (() -> Unit)? = null,
    headerAction: (@Composable () -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Primer.TextSecondary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (headerAction != null) headerAction()
        }
        Spacer(Modifier.height(4.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(
                fontSize = 13.sp,
                color = Primer.TextPrimary,
                fontFamily = if (mono) FontFamily.Monospace else null,
            ),
            visualTransformation = visualTransformation,
            cursorBrush = SolidColor(Primer.Blue500),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { innerTextField ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, Primer.BorderControl, RoundedCornerShape(8.dp))
                        .defaultMinSize(minHeight = 40.dp)
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty()) {
                            Text(
                                placeholder,
                                fontSize = 12.5.sp,
                                color = Primer.TextTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerTextField()
                    }
                    if (trailing != null && onTrailingClick != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            trailing,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Primer.Blue500,
                            modifier = Modifier
                                .clickable { onTrailingClick() }
                                .padding(vertical = 2.dp),
                        )
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
            .padding(horizontal = 8.dp, vertical = 4.dp),
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
