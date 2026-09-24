package com.branchbase.ui.profile

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.R
import com.branchbase.translate.EngineResult
import com.branchbase.translate.FailKind
import com.branchbase.translate.TranslateConfig
import com.branchbase.translate.TranslateLang
import com.branchbase.translate.TranslateProvider
import com.branchbase.translate.TranslateRuntime
import com.branchbase.translate.TranslateSettings
import com.branchbase.translate.TranslateStats
import com.branchbase.ui.log.Logger
import com.branchbase.ui.settings.ActionRow
import com.branchbase.ui.settings.SettingsSectionTitle
import com.branchbase.ui.settings.SwitchRow
import com.branchbase.ui.theme.Primer
import kotlinx.coroutines.launch

/**
 * 「沉浸式翻译」控制面板（设置 → 沉浸式翻译）。
 *
 * ## 这个功能是什么
 *
 * 在**正文页**（自述文件 README / Issue / PR / 发布说明，即 WebView 渲染的那些页面）里，
 * 把每个段落翻成目标语言并插在原文下面：原文在上、译文在下，可随时切回只看原文。
 * 开启入口就是这里的「自动翻译正文」——**总开关**：开了之后正文页自动开始翻译，
 * 并在页面上出现一枚**可移动悬浮球**，单击展开「翻译工具菜单」（进度数据、常用开关、
 * 重试 / 清空本页译文等操作）。面板里把「本页翻译」关掉，悬浮球立刻收起 —— 页面上
 * 不留控件，要再开就回到本页打开总开关。面板与本页读写同一份配置（一边改，另一边也是新的）。
 *
 * ## 面板上的这些项为什么是这些
 *
 * - **自动翻译**：默认关。开了就是「一进正文页就发请求」，费额度也拖首屏；
 *   默认交给页内按钮手动触发，这里只做「要不要自动化」的开关；
 * - **目标语言**：中英两向（源语言由目标反推，见 [TranslateLang]），覆盖「读英文仓库」
 *   与「给英文使用者看中文文档」；
 * - **显示方式**：对照 / 仅译文。两者共用同一份译文缓存，切换不重翻；
 * - **译文样式**：卡片 / 下划线 / 淡灰，只改注入页面根节点的一个属性，不重翻、不重建 WebView；
 * - **翻译服务**：MyMemory（免费零配置，默认）或 DeepSeek（**用户自带 API Key**）。
 *   选后者时展开 Key / 模型 / 接入地址三个输入框与一个「测试连接」；
 * - **本地缓存 / 保护代码与链接 / 清空缓存**：见各自的说明文案。
 *
 * ## 关于自带 API Key
 *
 * - Key 只存在 App 私有目录（与 GitHub token 同一份 SharedPreferences），
 *   **不会**注入页面脚本、不写日志；但仍建议使用**设置了额度上限**的 Key；
 * - 模型名与接入地址都可改：官方模型名会随版本调整，而接口是 OpenAI 兼容的，
 *   想接中转 / 自建网关时把地址换成自己的即可；
 * - 改完 Key / 换后端会顺手清掉熔断状态（[com.branchbase.translate.Translator.resetFailures]），
 *   否则上一次的「Key 无效」会把页面按钮一直钉在失败态。
 *
 * ## 为什么显示「已暂停 / 额度用尽 / Key 无效」
 *
 * 翻译后端熔断时，页面按钮会变成可重试状态；这里同步展示同一份状态，
 * 用户不必回到页面才知道「为什么没翻」。
 */
@Composable
fun TranslateSettingsScreen(onBack: () -> Unit) {
    LaunchedEffect(Unit) { Logger.ui("进入沉浸式翻译设置页", "Compose") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val translator = remember { TranslateRuntime.translator }

    var config by remember { mutableStateOf(TranslateSettings.read(context)) }
    var stats by remember { mutableStateOf(TranslateStats(0, 0, false, false, false)) }
    // 输入中的文本与「已保存的配置」分开：Key/模型/地址都是**失焦或点保存才落盘**，
    // 否则每敲一个字符都会写一次 SharedPreferences 并清一次熔断
    var keyDraft by remember { mutableStateOf(config.apiKey) }
    var modelDraft by remember { mutableStateOf(config.model) }
    var baseUrlDraft by remember { mutableStateOf(config.baseUrl) }
    var keyVisible by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { stats = translator.stats() }

    /** 落盘凭据并清熔断：改完立刻可用，不用回页面点重试。 */
    fun saveCredentials() {
        TranslateSettings.setApiKey(context, keyDraft)
        TranslateSettings.setModel(context, modelDraft)
        TranslateSettings.setBaseUrl(context, baseUrlDraft)
        config = TranslateSettings.read(context)
        translator.resetFailures()
        testResult = null
        scope.launch { stats = translator.stats() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Primer.BackgroundPrimary)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        SubPageHeader(stringResource(R.string.translate_title), onBack)

        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {

            // ── 自动翻译开关（也是页面上唯一的开启入口：关掉后页面不留控件） ──
            SwitchRow(
                name = stringResource(R.string.translate_auto_toggle),
                sub = stringResource(R.string.translate_auto_toggle_desc),
                checked = config.enabled,
                onCheckedChange = {
                    config = config.copy(enabled = it)
                    TranslateSettings.setEnabled(context, it)
                },
            )

            SettingsSectionTitle(stringResource(R.string.translate_service_section))
            TranslateProvider.entries.forEach { provider ->
                ModeOptionRow(
                    label = provider.label,
                    desc = provider.description,
                    selected = config.providerKind == provider,
                ) {
                    config = config.copy(provider = provider.code)
                    TranslateSettings.setProvider(context, provider.code)
                    // 换后端后清掉上一次的熔断（例如从「Key 无效」切回免费接口）
                    translator.resetFailures()
                    testResult = null
                    scope.launch { stats = translator.stats() }
                }
            }

            // ── DeepSeek 凭据：只在选中时展开 ──
            if (config.providerKind.requiresKey) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    InputField(
                        label = "API Key",
                        value = keyDraft,
                        placeholder = stringResource(R.string.translate_key_placeholder),
                        mono = true,
                        visualTransformation = if (keyVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailing = if (keyVisible) stringResource(R.string.action_hide) else stringResource(R.string.action_show),
                        onTrailingClick = { keyVisible = !keyVisible },
                        onValueChange = { keyDraft = it },
                    )
                    InputField(
                        label = stringResource(R.string.translate_model_label),
                        value = modelDraft,
                        placeholder = TranslateProvider.DEEPSEEK_MODEL,
                        mono = true,
                        onValueChange = { modelDraft = it },
                    )
                    // 模型候选：官方模型名会变，点一下填入，也允许手打其它名字
                    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                        TranslateProvider.MODEL_SUGGESTIONS.forEach { model ->
                            SuggestionChip(model) {
                                modelDraft = model
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                    InputField(
                        label = stringResource(R.string.translate_base_url_label),
                        value = baseUrlDraft,
                        placeholder = TranslateProvider.DEEPSEEK_BASE_URL,
                        mono = true,
                        onValueChange = { baseUrlDraft = it },
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ActionPill(
                            text = if (testing) stringResource(R.string.translate_testing) else stringResource(R.string.translate_save_and_test),
                            enabled = !testing,
                        ) {
                            saveCredentials()
                            testing = true
                            testResult = null
                            scope.launch {
                                val result = translator.selfTest(to = config.target, from = config.source)
                                testResult = describeTestResult(result)
                                testing = false
                                stats = translator.stats()
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        TestResultText(testResult)
                    }
                    Text(
                        stringResource(R.string.translate_key_note),
                        fontSize = 11.5.sp,
                        color = Primer.TextTertiary,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
            }

            SettingsSectionTitle(stringResource(R.string.translate_target_language))
            TranslateLang.entries.forEach { lang ->
                ModeOptionRow(
                    label = lang.label,
                    desc = lang.description,
                    selected = config.target == lang.code,
                ) {
                    config = config.copy(target = lang.code)
                    TranslateSettings.setTarget(context, lang.code)
                }
            }

            SettingsSectionTitle(stringResource(R.string.translate_display_mode))
            ModeOptionRow(
                label = stringResource(R.string.translate_mode_dual),
                desc = stringResource(R.string.translate_mode_dual_desc),
                selected = config.dual,
            ) {
                config = config.copy(dual = true)
                TranslateSettings.setDual(context, true)
            }
            ModeOptionRow(
                label = stringResource(R.string.translate_mode_only),
                desc = stringResource(R.string.translate_mode_only_desc),
                selected = !config.dual,
            ) {
                config = config.copy(dual = false)
                TranslateSettings.setDual(context, false)
            }

            SettingsSectionTitle(stringResource(R.string.translate_style_section))
            ModeOptionRow(
                label = stringResource(R.string.translate_style_card),
                desc = stringResource(R.string.translate_style_card_desc),
                selected = config.style == TranslateConfig.STYLE_CARD,
            ) {
                config = config.copy(style = TranslateConfig.STYLE_CARD)
                TranslateSettings.setStyle(context, TranslateConfig.STYLE_CARD)
            }
            ModeOptionRow(
                label = stringResource(R.string.translate_style_underline),
                desc = stringResource(R.string.translate_style_underline_desc),
                selected = config.style == TranslateConfig.STYLE_UNDERLINE,
            ) {
                config = config.copy(style = TranslateConfig.STYLE_UNDERLINE)
                TranslateSettings.setStyle(context, TranslateConfig.STYLE_UNDERLINE)
            }
            ModeOptionRow(
                label = stringResource(R.string.translate_style_plain),
                desc = stringResource(R.string.translate_style_plain_desc),
                selected = config.style == TranslateConfig.STYLE_PLAIN,
            ) {
                config = config.copy(style = TranslateConfig.STYLE_PLAIN)
                TranslateSettings.setStyle(context, TranslateConfig.STYLE_PLAIN)
            }

            SettingsSectionTitle(stringResource(R.string.translate_quality_section))
            SwitchRow(
                name = stringResource(R.string.translate_guard_toggle),
                sub = stringResource(R.string.translate_guard_toggle_desc),
                checked = config.protect,
                onCheckedChange = {
                    config = config.copy(protect = it)
                    TranslateSettings.setProtect(context, it)
                },
            )

            SettingsSectionTitle(stringResource(R.string.translate_cache_section))
            SwitchRow(
                name = stringResource(R.string.translate_cache_disk_toggle),
                sub = stringResource(R.string.translate_cache_disk_toggle_desc),
                checked = config.persist,
                onCheckedChange = {
                    config = config.copy(persist = it)
                    TranslateSettings.setPersist(context, it)
                },
            )
            // 原先这里是自制的 Row + clickable —— 规范 §4.1 要求行必须来自 6 种行型之一。
            // 归位成 ActionRow：行为不变（仍然立即清空）；是否该按 §7 补二次确认见规范 §11 待办。
            ActionRow(
                icon = Icons.Filled.Delete,
                name = stringResource(R.string.translate_cache_clear),
                sub = stringResource(R.string.translate_cache_stats, stats.memoryCount, stats.diskCount) + statusSuffix(stats),
                hint = stringResource(R.string.action_clear),
                onClick = {
                    scope.launch {
                        translator.clearCache()
                        stats = translator.stats()
                        // scope.launch 的 lambda 不是组合上下文，这里只能走 context.getString；
                        // Toast 属于「非 Compose 表面」，本来就该由 Context 决定语言
                        Toast.makeText(context, context.getString(R.string.translate_cache_cleared), Toast.LENGTH_SHORT).show()
                    }
                },
            )

            SettingsSectionTitle(stringResource(R.string.translate_notes_section))
            Text(
                stringResource(R.string.translate_notes_body),
                fontSize = 12.sp,
                color = Primer.TextTertiary,
                lineHeight = 19.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 状态后缀（缓存行里的熔断提示）。 */
private fun statusSuffix(stats: TranslateStats): String = when {
    stats.authFailed -> " · API Key 无效或未配置"
    stats.quotaBlocked -> " · 额度已用尽（会话内暂停）"
    stats.paused -> " · 连续失败已暂停"
    else -> ""
}

/** 把「测试连接」的结果翻成一句人话。 */
private fun describeTestResult(result: EngineResult): String = when (result) {
    is EngineResult.Ok -> "连接正常，译文：${result.text.take(60)}"
    is EngineResult.Fail -> when (result.kind) {
        FailKind.AUTH -> "失败：Key 无效或未填写"
        FailKind.QUOTA -> "失败：额度不足或被限流"
        FailKind.NETWORK -> "失败：网络不可达（${result.message.take(60)}）"
        FailKind.UNSUPPORTED -> "失败：参数不被接受（${result.message.take(60)}）"
        FailKind.UNKNOWN -> "失败：${result.message.take(80)}"
    }
}

/**
 * 单行输入（标签 + 输入框 + 可选右侧动作）。
 *
 * 与 [ProfileEditScreen] 里的 `EditField` 同款外观，这里额外支持「密码态 + 显示/隐藏」：
 * API Key 是一串敏感字符，默认遮住，但允许用户核对粘贴进来的内容。
 */
@Composable
private fun InputField(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    mono: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: String? = null,
    onTrailingClick: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextSecondary)
        Spacer(Modifier.height(5.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(placeholder, fontSize = 13.sp, color = Primer.TextTertiary) },
            singleLine = true,
            visualTransformation = visualTransformation,
            trailingIcon = if (trailing == null || onTrailingClick == null) {
                null
            } else {
                {
                    Text(
                        trailing,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Primer.Blue500,
                        modifier = Modifier
                            .clickable { onTrailingClick() }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            },
            textStyle = TextStyle(
                fontSize = 13.5.sp,
                fontFamily = if (mono) FontFamily.Monospace else null,
            ),
        )
    }
}

/** 小圆角建议按钮（模型候选）。 */
@Composable
private fun SuggestionChip(text: String, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 11.5.sp,
        color = Primer.Blue500,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Primer.Gray100)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** 主操作按钮（本页只有「保存并测试连接」一处）。 */
@Composable
private fun ActionPill(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = if (enabled) androidx.compose.ui.graphics.Color.White else Primer.TextTertiary,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) Primer.Blue500 else Primer.Gray100)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** 测试结果文字（成功绿色 / 失败红色）。 */
@Composable
private fun TestResultText(result: String?) {
    if (result == null) return
    val ok = result.startsWith(stringResource(R.string.translate_test_ok))
    Text(
        result,
        fontSize = 11.5.sp,
        color = if (ok) Primer.Green500 else Primer.Red500,
        lineHeight = 16.sp,
    )
}

