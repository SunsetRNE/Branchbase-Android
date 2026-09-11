package com.branchbase.ui.profile

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.branchbase.translate.EngineResult
import com.branchbase.translate.FailKind
import com.branchbase.translate.TranslateConfig
import com.branchbase.translate.TranslateLang
import com.branchbase.translate.TranslateProvider
import com.branchbase.translate.TranslateRuntime
import com.branchbase.translate.TranslateSettings
import com.branchbase.translate.TranslateStats
import com.branchbase.ui.log.Logger
import com.branchbase.ui.theme.Primer
import kotlinx.coroutines.launch

/**
 * 「沉浸式翻译」控制面板（设置 → 沉浸式翻译）。
 *
 * ## 这个功能是什么
 *
 * 在**正文页**（自述文件 README / Issue / PR / 发布说明，即 WebView 渲染的那些页面）里，
 * 把每个段落翻成目标语言并插在原文下面：原文在上、译文在下，可随时切回只看原文。
 * 页内右下角还有一枚「译」按钮，随手可开（不依赖本页设置，长按可切换显示方式）。
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
        SubPageHeader("沉浸式翻译", onBack)

        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {

            // ── 自动翻译开关 ──
            SwitchRow(
                title = "自动翻译正文",
                desc = "开启后进入自述文件等正文页会自动开始翻译；关闭时在页面右下角点「译」按钮手动触发。",
                checked = config.enabled,
            ) {
                config = config.copy(enabled = it)
                TranslateSettings.setEnabled(context, it)
            }

            SectionTitle("翻译服务")
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
                        placeholder = "sk-…（platform.deepseek.com 申请）",
                        mono = true,
                        visualTransformation = if (keyVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailing = if (keyVisible) "隐藏" else "显示",
                        onTrailingClick = { keyVisible = !keyVisible },
                        onValueChange = { keyDraft = it },
                    )
                    InputField(
                        label = "模型（可留空用默认）",
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
                        label = "接入地址（可留空用官方）",
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
                            text = if (testing) "测试中…" else "保存并测试连接",
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
                        "Key 只保存在 App 私有目录，不会注入网页、不写日志；建议使用设置了额度上限的 Key。\n" +
                            "接口为 OpenAI 兼容格式，接入地址可填中转或自建网关（例如 https://你的域名/v1）。",
                        fontSize = 11.5.sp,
                        color = Primer.TextTertiary,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
            }

            SectionTitle("目标语言")
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

            SectionTitle("显示方式")
            ModeOptionRow(
                label = "原文 + 译文对照",
                desc = "译文插在每段原文下方，原文保持不动（推荐）",
                selected = config.dual,
            ) {
                config = config.copy(dual = true)
                TranslateSettings.setDual(context, true)
            }
            ModeOptionRow(
                label = "仅显示译文",
                desc = "隐藏原文，只留译文（小屏快速浏览用，可随时切回对照）",
                selected = !config.dual,
            ) {
                config = config.copy(dual = false)
                TranslateSettings.setDual(context, false)
            }

            SectionTitle("译文样式")
            ModeOptionRow(
                label = "卡片",
                desc = "蓝色左边框 + 浅灰底，边界最清楚（默认）",
                selected = config.style == TranslateConfig.STYLE_CARD,
            ) {
                config = config.copy(style = TranslateConfig.STYLE_CARD)
                TranslateSettings.setStyle(context, TranslateConfig.STYLE_CARD)
            }
            ModeOptionRow(
                label = "下划线",
                desc = "无底色，只用虚线下划线标出译文",
                selected = config.style == TranslateConfig.STYLE_UNDERLINE,
            ) {
                config = config.copy(style = TranslateConfig.STYLE_UNDERLINE)
                TranslateSettings.setStyle(context, TranslateConfig.STYLE_UNDERLINE)
            }
            ModeOptionRow(
                label = "淡灰",
                desc = "无边框无底色，最不打扰阅读",
                selected = config.style == TranslateConfig.STYLE_PLAIN,
            ) {
                config = config.copy(style = TranslateConfig.STYLE_PLAIN)
                TranslateSettings.setStyle(context, TranslateConfig.STYLE_PLAIN)
            }

            SectionTitle("翻译质量")
            SwitchRow(
                title = "保护代码与链接",
                desc = "翻译前把 URL、@提及、#编号、提交 SHA 等标记临时占位，译完原样还原；" +
                    "避免它们被机器翻译改写（默认开启）。",
                checked = config.protect,
            ) {
                config = config.copy(protect = it)
                TranslateSettings.setProtect(context, it)
            }

            SectionTitle("译文缓存")
            SwitchRow(
                title = "本地缓存",
                desc = "把译文落盘，重开 App 后同一段不再重复翻译（关闭只是不再读写磁盘，不清除已存内容）。",
                checked = config.persist,
            ) {
                config = config.copy(persist = it)
                TranslateSettings.setPersist(context, it)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        scope.launch {
                            translator.clearCache()
                            stats = translator.stats()
                            Toast.makeText(context, "已清空译文缓存", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("清空译文缓存", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "内存 ${stats.memoryCount} 段 / 本地 ${stats.diskCount} 段" + statusSuffix(stats),
                        fontSize = 12.sp,
                        color = if (stats.blocked) Primer.Red500 else Primer.TextTertiary,
                    )
                }
                Text("清空", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Primer.Blue500)
            }

            SectionTitle("说明")
            Text(
                "• 生效范围：自述文件（README）、Issue / PR 主帖、发布说明等**正文页**" +
                    "（评论区为 Compose 原生渲染，暂不在范围内）。\n" +
                    "• 页内右下角的「译」按钮可随时开关，长按切换对照 / 仅译文，与本页设置相互独立；" +
                    "整页候选文本少于 5000 字符时一次翻完，更长时按视口滚动逐段翻译（省额度）。\n" +
                    "• 翻译服务：MyMemory 免费匿名（额度约 5000 词/天）；DeepSeek 需要你自己的 API Key，按 token 计费。" +
                    "服务不可用时（额度用尽 / Key 无效 / 连续失败）会自动暂停并在页面按钮上给出提示。\n" +
                    "• 隐私：只把**正文段落文本**发往你选择的服务；DeepSeek 的 Key 存于应用私有目录、" +
                    "不注入网页、不写日志。正文里出现的 URL / @提及 / #编号 / 提交 SHA 会先占位保护、译后还原。",
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

/** 开关行（标题 + 说明 + Switch），本页有三处相同结构。 */
@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
            Spacer(Modifier.height(3.dp))
            Text(desc, fontSize = 12.sp, color = Primer.TextTertiary, lineHeight = 17.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Primer.Blue500),
        )
    }
}

/** 分组标题（与设置页其它分组同款样式）。 */
@Composable
private fun SectionTitle(title: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
    ) {
        Text(
            title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Primer.TextTertiary,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Primer.Gray100)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
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
    val ok = result.startsWith("连接正常")
    Text(
        result,
        fontSize = 11.5.sp,
        color = if (ok) Primer.Green500 else Primer.Red500,
        lineHeight = 16.sp,
    )
}

