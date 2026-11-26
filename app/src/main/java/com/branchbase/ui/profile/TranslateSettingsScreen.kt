package com.branchbase.ui.profile

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.R
import com.branchbase.translate.DeepSeekModels
import com.branchbase.translate.EngineResult
import com.branchbase.translate.FailKind
import com.branchbase.translate.PageRules
import com.branchbase.translate.TranslateConfig
import com.branchbase.translate.TranslateLang
import com.branchbase.translate.TranslateProvider
import com.branchbase.translate.TranslateRuntime
import com.branchbase.translate.TranslateSettings
import com.branchbase.translate.TranslateStats
import com.branchbase.ui.LocalizedText
import com.branchbase.ui.log.Logger
import com.branchbase.ui.resolve
import com.branchbase.ui.settings.ActionRow
import com.branchbase.ui.settings.LocalSettingsDensity
import com.branchbase.ui.settings.SettingsDensity
import com.branchbase.ui.settings.SettingsNotesDialog
import com.branchbase.ui.settings.SettingsSection
import com.branchbase.ui.settings.SwitchRow
import com.branchbase.ui.theme.Primer
import com.branchbase.ui.theme.iconTap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * ## 2026-11 第五轮：这一页的形态变了（决策见 `settings-compact-redesign.md` §九）
 *
 * - **总开关只在本页**：主页「翻译」组那枚开关删了（它是同一份 `translate.enabled`
 *   的第二个入口，与 `modules-design.md` §1「开启入口只有一个」自相矛盾）；
 * - **压密**：根部 provide [SettingsDensity.Compact]（44dp 行高），七组全部卡片化，
 *   单选行的内边距与圆点直径也走密度表（`optionPadV` / `optionDotSize`）；
 * - **说明收进弹窗**：原先摊在页尾的 570+ 字「说明」+ 各行 44–99 字的长说明，改成
 *   行内一句必要副作用、其余收进 [SettingsNotesDialog]，由页头右上角的小红「!」打开；
 * - 卡片化之后 `SettingsSectionTitle`（无卡片的过渡态）成为死代码，一并删除。
 *
 * ## 面板上的这些项为什么是这些
 *
 * - **自动翻译**：默认关。开了就是「一进正文页就发请求」，费额度也拖首屏；
 *   默认交给页内按钮手动触发，这里只做「要不要自动化」的开关；
 * - **目标语言**：中英两向（源语言由目标反推，见 [TranslateLang]），覆盖「读英文仓库」
 *   与「给英文使用者看中文文档」；
 * - **显示方式**：对照 / 仅译文。两者共用同一份译文缓存，切换不重翻；
 * - **译文样式**：卡片 / 下划线 / 淡灰，只改注入页面根节点的一个属性，不重翻、不重建 WebView；
 * - **中英混排**：中文段落里夹着外语片段时怎么办（判定引擎的使用规则）—— 只翻外语片段（默认，
 *   按「片段 → 译文」配对展示）/ 整段一起翻（旧行为）/ 混排不翻。规则真源在 `PageRules.matchPolicy`，
 *   既进原生判定也随设置注入页面脚本；
 * - **翻译服务**：MyMemory（免费零配置，默认）或 DeepSeek（**用户自带 API Key**）。
 *   选后者时展开 Key / 模型 / 接入地址三个输入框与一个「测试连接」；
 * - **本地缓存 / 保护代码与链接 / 清空缓存**：见各自的说明文案（细节在「!」弹窗里）。
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
    var testResult by remember { mutableStateOf<TestOutcome?>(null) }
    // 「!」徽标打开的说明弹窗。默认关：说明是「需要时再查」的东西，不该一进页面就糊一屏。
    var notesOpen by remember { mutableStateOf(false) }

    // ── 凭据与模型（2026-11 第六轮） ──
    // 折叠态默认：还没填 Key 就展开（引导配置），已配置就收起（省高度）。
    var credentialsExpanded by remember {
        mutableStateOf(config.providerKind.requiresKey && config.apiKey.isBlank())
    }
    // 本机保存的模型列表（用户从探测结果里勾选出的子集）；只用于候选芯片，不参与翻译请求
    var localModels by remember { mutableStateOf(TranslateSettings.readModels(context)) }
    var fetchingModels by remember { mutableStateOf(false) }
    var modelError by remember { mutableStateOf<LocalizedText?>(null) }
    // 非空 = 模型选择弹窗打开；同时 confirmingModels 也非空 = 二次确认叠在上面
    var fetchedModels by remember { mutableStateOf<List<String>?>(null) }
    var confirmingModels by remember { mutableStateOf<Set<String>?>(null) }

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

    /**
     * 探测模型列表（决策五）：只读当前输入框里的地址与 Key，**不先落盘** ——
     * 探测失败不该顺手改掉用户正在编辑的配置。
     */
    fun fetchModels() {
        if (fetchingModels) return
        fetchingModels = true
        modelError = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { DeepSeekModels.fetch(baseUrlDraft, keyDraft) }
            fetchingModels = false
            when (result) {
                is DeepSeekModels.Result.Ok -> fetchedModels = result.models
                is DeepSeekModels.Result.Fail -> modelError = when (result.detail) {
                    DeepSeekModels.EMPTY_KEY -> LocalizedText(R.string.translate_models_need_key)
                    DeepSeekModels.EMPTY_LIST -> LocalizedText(R.string.translate_models_empty)
                    DeepSeekModels.NETWORK -> LocalizedText(R.string.translate_models_network)
                    DeepSeekModels.TIMEOUT -> LocalizedText(R.string.translate_models_timeout)
                    else -> LocalizedText(R.string.translate_models_failed, listOf(result.detail))
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Primer.BackgroundPrimary)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // 页头右侧的「!」：本轮决策四。徽标本身 18dp，命中区 40dp（iconTap 不放大触控区，
        // 所以外层盒要自己给够尺寸），点击打开说明弹窗。
        SubPageHeader(stringResource(R.string.translate_title), onBack) {
            NotesBadge { notesOpen = true }
        }

        // 压密只作用于本页（决策二）：`CompositionLocalProvider` 的作用域就是这棵子树，
        // 主题页 / 通知页等拿到的仍是默认 Comfort。`SettingsSpecTest` 按文件钉住这一点。
        CompositionLocalProvider(LocalSettingsDensity provides SettingsDensity.Compact) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {

                // ── ① 总开关（也是页面上唯一的开启入口：关掉后页面不留控件） ──
                // 单行卡片不带组名：页头已经写着「沉浸式翻译」，再标一个「翻译」只会重复（§3.2）。
                SettingsSection {
                    SwitchRow(
                        name = stringResource(R.string.translate_auto_toggle),
                        // 行内只留「关了会发生什么」（副作用，§6.2 允许）；为什么会这样、悬浮球怎么用，
                        // 都在「!」弹窗里 —— 原先这行 77 字说明要占 4 行，是整页最高的一行。
                        sub = stringResource(R.string.translate_auto_toggle_desc),
                        checked = config.enabled,
                        onCheckedChange = {
                            config = config.copy(enabled = it)
                            TranslateSettings.setEnabled(context, it)
                        },
                        divider = false,
                    )
                }

                // ── ② 翻译服务 ──
                SettingsSection(stringResource(R.string.translate_service_section)) {
                    TranslateProvider.entries.forEachIndexed { index, provider ->
                        ModeOptionRow(
                            label = stringResource(provider.labelRes),
                            desc = stringResource(provider.descRes),
                            selected = config.providerKind == provider,
                            divider = index != 0,
                        ) {
                            config = config.copy(provider = provider.code)
                            TranslateSettings.setProvider(context, provider.code)
                            // 换后端后清掉上一次的熔断（例如从「Key 无效」切回免费接口）
                            translator.resetFailures()
                            testResult = null
                            // 模型探测的结果属于上一家服务商：换后端不能把旧列表留在屏上
                            fetchedModels = null
                            confirmingModels = null
                            modelError = null
                            credentialsExpanded = keyDraft.isBlank()
                            scope.launch { stats = translator.stats() }
                        }
                    }

                    // DeepSeek 凭据：只在选中时出现，且**默认可折叠**（决策二）。
                    // 折叠态只用一行 ActionRow 报「已配置 / 未配置 Key」；展开态才铺表单。
                    if (config.providerKind.requiresKey) {
                        ActionRow(
                            icon = Icons.Filled.Key,
                            name = stringResource(R.string.translate_credentials_toggle),
                            sub = if (keyDraft.isNotBlank()) {
                                stringResource(R.string.translate_credentials_configured)
                            } else {
                                stringResource(R.string.translate_credentials_missing)
                            },
                            hint = if (credentialsExpanded) {
                                stringResource(R.string.action_collapse)
                            } else {
                                stringResource(R.string.action_expand)
                            },
                            onClick = { credentialsExpanded = !credentialsExpanded },
                        )
                        // 展开 / 收起都是弹簧（决策四）：展开的过冲读出「弹出来」，
                        // 收起用无过冲阻尼，避免卡片底边抖一下再落位。
                        AnimatedVisibility(
                            visible = credentialsExpanded,
                            enter = expandVertically(
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                                expandFrom = Alignment.Top,
                            ) + fadeIn(tween(140)),
                            exit = shrinkVertically(
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                                shrinkTowards = Alignment.Top,
                            ) + fadeOut(tween(110)),
                        ) {
                            // 决策三：块内再压一档 —— 输入框 34dp（不再是 Material 的 56dp）、
                            // 标签 10.5sp + 3dp 间距、块内边距 10/8、按钮与说明都收一号。
                            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                                CompactField(
                                    label = "API Key",
                                    value = keyDraft,
                                    placeholder = stringResource(R.string.translate_key_placeholder),
                                    mono = true,
                                    visualTransformation = if (keyVisible) {
                                        VisualTransformation.None
                                    } else {
                                        PasswordVisualTransformation()
                                    },
                                    onValueChange = { keyDraft = it },
                                    trailingContent = {
                                        Text(
                                            if (keyVisible) stringResource(R.string.action_hide) else stringResource(R.string.action_show),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Primer.Blue500,
                                            modifier = Modifier
                                                .clickable { keyVisible = !keyVisible }
                                                .padding(horizontal = 2.dp, vertical = 2.dp),
                                        )
                                    },
                                )
                                // 模型行右侧的「获取模型列表」：探测 `{baseUrl}/models`（决策五）。
                                CompactField(
                                    label = stringResource(R.string.translate_model_label),
                                    value = modelDraft,
                                    placeholder = TranslateProvider.DEEPSEEK_MODEL,
                                    mono = true,
                                    onValueChange = { modelDraft = it },
                                    // 徽章放回标签行右侧（决策五的原始形态）：框内空间留给模型名，
                                    // 也不会和「显示 / 隐藏」在同一行里打架
                                    headerAction = {
                                        ModelListBadge(loading = fetchingModels) { fetchModels() }
                                    },
                                )
                                modelError?.let { error ->
                                    Text(
                                        error.resolve(),
                                        fontSize = 11.sp,
                                        lineHeight = 15.sp,
                                        color = Primer.DangerText,
                                        modifier = Modifier.padding(bottom = 6.dp),
                                    )
                                }
                                // 候选芯片：本机保存的列表优先；还没探测过时回落到内置候选。
                                // FlowRow：网关可能返回几十个模型，Row 不换行会超出卡片被裁掉，且不报错。
                                val chips = if (localModels.isEmpty()) {
                                    TranslateProvider.MODEL_SUGGESTIONS
                                } else {
                                    localModels
                                }
                                FlowRow(
                                    Modifier.fillMaxWidth().padding(bottom = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    chips.forEach { model ->
                                        SuggestionChip(model) { modelDraft = model }
                                    }
                                }
                                CompactField(
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
                                    // 结果文字占剩余宽度：长错误（网络不可达 + 原因）不会把按钮顶出卡片
                                    Box(Modifier.weight(1f)) { TestResultText(testResult) }
                                }
                                // 隐私说明压成一行：Key 的归属与「不外泄」是必须留在表面的信息（§6.2）；
                                // OpenAI 兼容 / 可接中转属于「怎么用」，在「!」弹窗里。
                                Text(
                                    stringResource(R.string.translate_key_note),
                                    fontSize = 10.sp,
                                    color = Primer.TextTertiary,
                                    lineHeight = 14.sp,
                                )
                            }
                        }
                    }
                }

                // ── ③ 目标语言 ──
                SettingsSection(stringResource(R.string.translate_target_language)) {
                    TranslateLang.entries.forEachIndexed { index, lang ->
                        ModeOptionRow(
                            label = stringResource(lang.labelRes),
                            desc = stringResource(lang.descRes),
                            selected = config.target == lang.code,
                            divider = index != 0,
                        ) {
                            config = config.copy(target = lang.code)
                            TranslateSettings.setTarget(context, lang.code)
                        }
                    }
                }

                // ── ④ 中英混排：中文段落里夹着外语片段时怎么办（判定引擎的使用规则） ──
                // 默认「只翻片段」：段内没有需要翻的内容就整段跳过，有就只把片段送去翻译、
                // 按「片段 → 译文」配对展示。选「整段一起翻」等于回到旧行为（中文会被再翻一遍）。
                SettingsSection(stringResource(R.string.translate_mix_section)) {
                    ModeOptionRow(
                        label = stringResource(R.string.translate_mix_match),
                        desc = stringResource(R.string.translate_mix_match_desc),
                        selected = config.matchPolicy == PageRules.MATCH_POLICY_MATCH,
                        divider = false,
                    ) {
                        config = config.copy(matchPolicy = PageRules.MATCH_POLICY_MATCH)
                        TranslateSettings.setMatchPolicy(context, PageRules.MATCH_POLICY_MATCH)
                    }
                    ModeOptionRow(
                        label = stringResource(R.string.translate_mix_whole),
                        desc = stringResource(R.string.translate_mix_whole_desc),
                        selected = config.matchPolicy == PageRules.MATCH_POLICY_WHOLE,
                        divider = true,
                    ) {
                        config = config.copy(matchPolicy = PageRules.MATCH_POLICY_WHOLE)
                        TranslateSettings.setMatchPolicy(context, PageRules.MATCH_POLICY_WHOLE)
                    }
                    ModeOptionRow(
                        label = stringResource(R.string.translate_mix_skip),
                        desc = stringResource(R.string.translate_mix_skip_desc),
                        selected = config.matchPolicy == PageRules.MATCH_POLICY_SKIP,
                        divider = true,
                    ) {
                        config = config.copy(matchPolicy = PageRules.MATCH_POLICY_SKIP)
                        TranslateSettings.setMatchPolicy(context, PageRules.MATCH_POLICY_SKIP)
                    }
                }

                // ── ⑤ 显示方式 ──
                SettingsSection(stringResource(R.string.translate_display_mode)) {
                    ModeOptionRow(
                        label = stringResource(R.string.translate_mode_dual),
                        desc = stringResource(R.string.translate_mode_dual_desc),
                        selected = config.dual,
                        divider = false,
                    ) {
                        config = config.copy(dual = true)
                        TranslateSettings.setDual(context, true)
                    }
                    ModeOptionRow(
                        label = stringResource(R.string.translate_mode_only),
                        desc = stringResource(R.string.translate_mode_only_desc),
                        selected = !config.dual,
                        divider = true,
                    ) {
                        config = config.copy(dual = false)
                        TranslateSettings.setDual(context, false)
                    }
                }

                // ── ⑥ 译文样式 ──
                SettingsSection(stringResource(R.string.translate_style_section)) {
                    ModeOptionRow(
                        label = stringResource(R.string.translate_style_card),
                        desc = stringResource(R.string.translate_style_card_desc),
                        selected = config.style == TranslateConfig.STYLE_CARD,
                        divider = false,
                    ) {
                        config = config.copy(style = TranslateConfig.STYLE_CARD)
                        TranslateSettings.setStyle(context, TranslateConfig.STYLE_CARD)
                    }
                    ModeOptionRow(
                        label = stringResource(R.string.translate_style_underline),
                        desc = stringResource(R.string.translate_style_underline_desc),
                        selected = config.style == TranslateConfig.STYLE_UNDERLINE,
                        divider = true,
                    ) {
                        config = config.copy(style = TranslateConfig.STYLE_UNDERLINE)
                        TranslateSettings.setStyle(context, TranslateConfig.STYLE_UNDERLINE)
                    }
                    ModeOptionRow(
                        label = stringResource(R.string.translate_style_plain),
                        desc = stringResource(R.string.translate_style_plain_desc),
                        selected = config.style == TranslateConfig.STYLE_PLAIN,
                        divider = true,
                    ) {
                        config = config.copy(style = TranslateConfig.STYLE_PLAIN)
                        TranslateSettings.setStyle(context, TranslateConfig.STYLE_PLAIN)
                    }
                }

                // ── ⑦ 翻译质量 ──
                SettingsSection(stringResource(R.string.translate_quality_section)) {
                    SwitchRow(
                        name = stringResource(R.string.translate_guard_toggle),
                        // 这句是「关掉会怎样」的副作用（占位保护失效），保留在行内（§6.2）。
                        sub = stringResource(R.string.translate_guard_toggle_desc),
                        checked = config.protect,
                        onCheckedChange = {
                            config = config.copy(protect = it)
                            TranslateSettings.setProtect(context, it)
                        },
                        divider = false,
                    )
                }

                // ── ⑧ 译文缓存 ──
                SettingsSection(stringResource(R.string.translate_cache_section)) {
                    SwitchRow(
                        name = stringResource(R.string.translate_cache_disk_toggle),
                        // 关闭的副作用（不读盘、但也不清已存内容）是必须留在表面的那半句；
                        // 完整解释进「!」弹窗。
                        sub = stringResource(R.string.translate_cache_disk_toggle_desc),
                        checked = config.persist,
                        onCheckedChange = {
                            config = config.copy(persist = it)
                            TranslateSettings.setPersist(context, it)
                        },
                        divider = false,
                    )
                    // 原先这里是自制的 Row + clickable —— 规范 §4.1 要求行必须来自 6 种行型之一。
                    // 归位成 ActionRow：行为不变（仍然立即清空）；是否该按 §7 补二次确认见规范 §11 待办。
                    val cacheLine = stringResource(R.string.translate_cache_stats, stats.memoryCount, stats.diskCount)
                    val cacheNote = statusNote(stats)
                    ActionRow(
                        icon = Icons.Filled.Delete,
                        name = stringResource(R.string.translate_cache_clear),
                        sub = if (cacheNote == null) {
                            cacheLine
                        } else {
                            stringResource(R.string.translate_cache_with_status, cacheLine, cacheNote.resolve())
                        },
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
                    // 判定引擎的可见性：跳过多少段（「这段为什么没翻」）、匹配翻译了多少段 / 多少对片段。
                    // 数字是**本进程累计**的（与缓存行同一个口径），重进设置页不会归零。
                    Text(
                        stringResource(R.string.translate_decision_stats, stats.skipped, stats.matched, stats.parts),
                        fontSize = 11.sp,
                        color = Primer.TextTertiary,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 6.dp),
                    )
                }

                Spacer(Modifier.height(20.dp))
            }
        }
    }

    // 说明弹窗（决策三）：正文全部来自资源、逐条一句话；UI 负责画「•」。
    if (notesOpen) {
        SettingsNotesDialog(
            title = stringResource(R.string.translate_notes_title),
            bullets = listOf(
                stringResource(R.string.translate_notes_scope),
                stringResource(R.string.translate_notes_entry),
                stringResource(R.string.translate_notes_panel),
                stringResource(R.string.translate_notes_batch),
                stringResource(R.string.translate_notes_service),
                stringResource(R.string.translate_notes_mix),
                stringResource(R.string.translate_notes_render),
                stringResource(R.string.translate_notes_cache),
                stringResource(R.string.translate_notes_privacy),
                stringResource(R.string.translate_notes_guard),
                stringResource(R.string.translate_notes_credentials),
            ),
            onDismiss = { notesOpen = false },
        )
    }

    // 模型选择（决策六第一步）：勾选来自本次探测的结果；「保存」再进二次确认。
    fetchedModels?.let { models ->
        val preset = remember(models, localModels, modelDraft) {
            (localModels.filter { it in models } + listOfNotNull(modelDraft.takeIf { it in models })).toSet()
        }
        ModelPickerDialog(
            models = models,
            initiallyChecked = preset,
            onDismiss = { fetchedModels = null },
            onConfirm = { confirmingModels = it },
        )
    }

    // 二次确认（决策六第二步）：只在保存前出现，讲清「配置只在本机」，确认后才写盘。
    confirmingModels?.let { selected ->
        ModelLocalSaveDialog(
            onDismiss = { confirmingModels = null },
            onConfirm = {
                val saved = selected.sorted()
                TranslateSettings.setModels(context, saved)
                localModels = saved
                confirmingModels = null
                fetchedModels = null
                Toast.makeText(context, context.getString(R.string.translate_models_saved), Toast.LENGTH_SHORT).show()
            },
        )
    }
}

/**
 * 页头右上角的「!」徽标（决策四）：**小**、**红底**、**不抢眼**。
 *
 * 尺寸：圆 18dp，比页头的 24dp 返回箭头小一圈；命中区是外层 40dp 盒
 * （`iconTap` 只把水波纹裁圆、不放大触控区，所以尺寸必须由盒自己给）。
 *
 * 配色：底 `DangerText`、叹号 `DangerSurface` —— 不是随手取的红。两个主题下都过
 * WCAG 文本对比：浅色 `#9E1C24` 底 + 白系字 ≈ 7.9:1；深色 `#F85149` 底 + 深红字 ≈ 5.2:1。
 * 若用 `Red500` 做底，深色下白字只有 3.3:1（低于 4.5），而这是承载「说明」语义的字形。
 *
 * 语义：只有图标一个 `contentDescription`（「翻译说明」），外层 `clickable` 会合并它 ——
 * 读屏读作「翻译说明，按钮」，不会把「!」念成一个无用符号。
 */
@Composable
private fun NotesBadge(onClick: () -> Unit) {
    val description = stringResource(R.string.translate_notes_badge)
    Box(
        modifier = Modifier.size(40.dp).iconTap { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .background(Primer.DangerText, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.PriorityHigh,
                contentDescription = description,
                tint = Primer.DangerSurface,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

/**
 * 「测试连接」的结论：文案 + 语气。
 *
 * 语气由**产生方**给出（`ok`），不从文案里猜 —— 原来 `TestResultText` 用
 * `startsWith("连接正常")` 判颜色，界面切英文后那句永远不成立，失败会被渲染成绿色
 * （§5.3 记录的同一个坑）。文案存 [LocalizedText]，语言切换后不会停在旧语言。
 */
private data class TestOutcome(val text: LocalizedText, val ok: Boolean)

/** 缓存行里的熔断说明（纯函数只返回资源；没有提示时返回 null）。 */
private fun statusNote(stats: TranslateStats): LocalizedText? = when {
    stats.authFailed -> LocalizedText(R.string.translate_status_auth_failed)
    stats.quotaBlocked -> LocalizedText(R.string.translate_status_quota_blocked)
    stats.paused -> LocalizedText(R.string.translate_status_paused)
    else -> null
}

/** 把「测试连接」的结果翻成一句人话（判定见 [TestOutcome.ok]，文案交给资源）。 */
private fun describeTestResult(result: EngineResult): TestOutcome = when (result) {
    is EngineResult.Ok -> TestOutcome(
        LocalizedText(R.string.translate_test_ok_detail, listOf(result.text.take(60))),
        ok = true,
    )
    is EngineResult.Fail -> when (result.kind) {
        FailKind.AUTH -> TestOutcome(LocalizedText(R.string.translate_test_fail_auth), ok = false)
        FailKind.QUOTA -> TestOutcome(LocalizedText(R.string.translate_test_fail_quota), ok = false)
        FailKind.NETWORK -> TestOutcome(
            LocalizedText(R.string.translate_test_fail_network, listOf(result.message.take(60))),
            ok = false,
        )
        FailKind.UNSUPPORTED -> TestOutcome(
            LocalizedText(R.string.translate_test_fail_unsupported, listOf(result.message.take(60))),
            ok = false,
        )
        FailKind.UNKNOWN -> TestOutcome(
            LocalizedText(R.string.translate_test_fail_raw, listOf(result.message.take(80))),
            ok = false,
        )
    }
}

/** 翻译后端的界面文案在 app 层（模块只留与语言无关的 `code`，UI 文案必须走资源）。 */
private val TranslateProvider.labelRes: Int
    get() = when (this) {
        TranslateProvider.MYMEMORY -> R.string.translate_provider_mymemory_label
        TranslateProvider.DEEPSEEK -> R.string.translate_provider_deepseek_label
    }

private val TranslateProvider.descRes: Int
    get() = when (this) {
        TranslateProvider.MYMEMORY -> R.string.translate_provider_mymemory_desc
        TranslateProvider.DEEPSEEK -> R.string.translate_provider_deepseek_desc
    }

/** 目标语言的界面文案同上（母语自称由资源决定，别在模块里写死中文）。 */
private val TranslateLang.labelRes: Int
    get() = when (this) {
        TranslateLang.ZH -> R.string.translate_lang_zh_label
        TranslateLang.EN -> R.string.translate_lang_en_label
    }

private val TranslateLang.descRes: Int
    get() = when (this) {
        TranslateLang.ZH -> R.string.translate_lang_zh_desc
        TranslateLang.EN -> R.string.translate_lang_en_desc
    }

/** 小圆角建议按钮（模型候选）。块内压密后 11sp / 7×3dp。 */
@Composable
private fun SuggestionChip(text: String, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 11.sp,
        color = Primer.Blue500,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Primer.Gray100)
            .clickable { onClick() }
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** 主操作按钮（本页只有「保存并测试连接」一处）。块内压密后 12.5sp / 10×6dp。 */
@Composable
private fun ActionPill(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = if (enabled) Color.White else Primer.TextTertiary,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) Primer.Blue500 else Primer.Gray100)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/** 测试结果文字（成功绿色 / 失败红色）。 */
@Composable
private fun TestResultText(result: TestOutcome?) {
    if (result == null) return
    Text(
        result.text.resolve(),
        fontSize = 11.5.sp,
        color = if (result.ok) Primer.Green500 else Primer.Red500,
        lineHeight = 16.sp,
    )
}
