package com.branchbase.ui.profile

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.branchbase.R
import com.branchbase.core.RustBridge
import com.branchbase.translate.ModelProbe
import com.branchbase.translate.PageRules
import com.branchbase.translate.CustomValueIssue
import com.branchbase.translate.TranslateConfig
import com.branchbase.translate.TranslateEngine
import com.branchbase.translate.TranslateThinking
import com.branchbase.translate.TranslateLang
import com.branchbase.translate.TranslateProtocol
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
import kotlinx.coroutines.delay
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
 * - **翻译服务**：MyMemory（免费零配置，默认）或**外部模型接入**（用户自备 Key 与协议）。
 *   选后者时展开「协议 / 地址 / Key / 模型」四个字段与两个动作（保存、测试响应）；
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
    // 模型框**不空着**：保存的值为空时直接填当前协议的默认模型（Rust 侧空值即各后端的默认值），
    // 用户一眼能看到「实际会用哪个」，不用猜占位符。
    var modelDraft by remember {
        // 默认模型跟着「协议 + 地址」走：DeepSeek 地址预填 deepseek-flash，OpenAI 地址留空
        mutableStateOf(
            config.model.ifBlank { config.protocolKind.defaultModelFor(config.baseUrl) },
        )
    }
    var baseUrlDraft by remember { mutableStateOf(config.baseUrl) }
    // 协议草稿与其它三个字段同一原则：**改完点保存才落盘**（探测只读草稿，不顺手改配置）。
    var protocolDraft by remember { mutableStateOf(config.protocolKind) }
    // 协议 / 地址都是单选，点一项就选完并关窗
    var protocolPickerOpen by remember { mutableStateOf(false) }
    var addressPickerOpen by remember { mutableStateOf(false) }
    // 保存前的兼容性提醒（只在**协议变了**时出现一次，见 ProtocolWarningDialog）
    var warningOpen by remember { mutableStateOf(false) }
    // ── 思维深度（1.4.55）──
    // 默认关：思考会更慢更贵，而「最低档就够日常翻译」是实测口径；档位由用户自己选。
    var thinkingDraft by remember { mutableStateOf(config.thinkingKind) }
    var thinkingCustomDraft by remember { mutableStateOf(config.thinkingCustom) }
    var thinkingPickerOpen by remember { mutableStateOf(false) }
    // 自定义值无效时的保存确认（徽章 + 小字都可能被跳过，保存这一步必须看见一次）
    var customWarningOpen by remember { mutableStateOf(false) }
    var testingTranslation by remember { mutableStateOf(false) }
    // 「测试翻译」的结论：与「测试响应」（端口探测）**分开两条**，互不覆盖
    var translationOutcome by remember { mutableStateOf<TestOutcome?>(null) }
    // 上次探测的结论：该服务商认不认这个字段（null = 还没测过，不置灰）
    val thinkingScope = TranslateSettings.thinkingScope(protocolDraft, baseUrlDraft, modelDraft)
    var thinkingSupported by remember(thinkingScope) {
        mutableStateOf(TranslateSettings.readThinkingSupport(context, thinkingScope))
    }
    var keyVisible by remember { mutableStateOf(false) }
    var testingResponse by remember { mutableStateOf(false) }
    var probeOutcome by remember { mutableStateOf<TestOutcome?>(null) }
    // 非空 = 正在「逐字输入」这个模型名（点候选芯片 / 保存模型列表后触发）
    var typingTarget by remember { mutableStateOf<String?>(null) }
    // 保存成功覆盖弹窗（弹簧曲线进场，约 1.8s 自动消失）
    var saveSuccess by remember { mutableStateOf(false) }
    // 「!」徽标打开的说明弹窗。默认关：说明是「需要时再查」的东西，不该一进页面就糊一屏。
    var notesOpen by remember { mutableStateOf(false) }

    // ── 凭据与模型（2026-11 第六轮） ──
    // 折叠态默认：还没填 Key 就展开（引导配置），已配置就收起（省高度）。
    var credentialsExpanded by remember {
        mutableStateOf(config.providerKind.requiresKey && config.apiKey.isBlank())
    }
    // 本机保存的模型列表（用户从探测结果里勾选出的子集）；只用于候选芯片，不参与翻译请求。
    // 1.4.54 起按「协议 + 地址」分桶：在 DeepSeek 下保存的列表不会飘到 Anthropic 档。
    val modelScope = TranslateSettings.modelsScope(protocolDraft, baseUrlDraft)
    var localModels by remember(modelScope) { mutableStateOf(TranslateSettings.readModels(context, modelScope)) }
    var fetchingModels by remember { mutableStateOf(false) }
    var modelError by remember { mutableStateOf<LocalizedText?>(null) }
    // 非空 = 模型选择弹窗打开；同时 confirmingModels 也非空 = 二次确认叠在上面
    var fetchedModels by remember { mutableStateOf<List<String>?>(null) }
    var confirmingModels by remember { mutableStateOf<Set<String>?>(null) }

    LaunchedEffect(Unit) { stats = translator.stats() }

    // 选中模型时**模仿人打字**逐字填入（决策：选中后的输入动画）。28ms/字，一个模型名约 0.4s；
    // 中途换目标会取消上一条协程，不会出现两段打字叠加。
    LaunchedEffect(typingTarget) {
        val target = typingTarget ?: return@LaunchedEffect
        modelDraft = ""
        target.forEachIndexed { index, _ ->
            delay(28)
            modelDraft = target.substring(0, index + 1)
        }
        typingTarget = null
    }

    /**
     * 探测失败的统一文案（「获取模型列表」与「测试响应」共用一张映射表）。
     *
     * 响应头带来的提示（`Retry-After` / 额度用尽）在 :translate 里只留下**数值**，
     * 句子在这里拼：模块里写中文，英文界面就切不过去了（i18n 规范 §5）。
     */
    fun modelsFailure(fail: ModelProbe.Result.Fail): LocalizedText {
        val detail = fail.detail
        return when {
            detail == ModelProbe.EMPTY_KEY -> LocalizedText(R.string.translate_models_need_key)
            detail == ModelProbe.EMPTY_LIST -> LocalizedText(R.string.translate_models_empty)
            detail == ModelProbe.NO_MODELS_ENDPOINT -> LocalizedText(R.string.translate_models_no_endpoint)
            detail == ModelProbe.NETWORK -> LocalizedText(R.string.translate_models_network)
            detail == ModelProbe.TIMEOUT -> LocalizedText(R.string.translate_models_timeout)
            fail.retryAfterSeconds != null -> LocalizedText(
                R.string.translate_models_failed_retry,
                listOf(detail, fail.retryAfterSeconds.toString()),
            )
            fail.quotaExhausted -> LocalizedText(R.string.translate_models_failed_quota, listOf(detail))
            else -> LocalizedText(R.string.translate_models_failed, listOf(detail))
        }
    }

    /**
     * 「测试翻译」（1.4.55）：与「测试响应」**两套逻辑、两个按钮**，互不混用。
     *
     * - 「测试响应」= `GET /models`：只证明端口与鉴权通（可能 404，很多网关没有这个接口）；
     * - 「测试翻译」= 真的翻一句话：证明「这组协议 + 地址 + Key + 模型」能出译文。
     *
     * 它做两件事，因为「字段被接受」只有在翻译真的跑通时才有意义：
     * 1. 先用**关闭思维深度**的配置翻一句 —— 不通就到此为止（翻译本身不可用）；
     * 2. 思维深度开着时，再用**严格模式**（`strictThinking`，不做兜底）翻一句：
     *    成功 = 服务商接受该字段；失败 = 拒绝。结论落盘，界面标灰提示。
     *
     * 消耗：两次极短请求（各一个词），与真实翻译同一价格量级，所以按钮旁写明。
     */
    fun probeTranslation() {
        if (testingTranslation) return
        if (keyDraft.isBlank()) {
            translationOutcome = TestOutcome(LocalizedText(R.string.translate_thinking_need_key), ok = false)
            return
        }
        if (modelDraft.isBlank()) {
            translationOutcome = TestOutcome(LocalizedText(R.string.translate_thinking_need_model), ok = false)
            return
        }
        testingTranslation = true
        translationOutcome = null
        val base = TranslateSettings.read(context).copy(
            provider = TranslateProvider.EXTERNAL.code,
            protocol = protocolDraft.code,
            apiKey = keyDraft.trim(),
            model = modelDraft.trim(),
            baseUrl = baseUrlDraft.trim(),
        )
        val level = thinkingDraft
        val custom = thinkingCustomDraft
        scope.launch {
            var supported: Boolean? = null
            val outcome = withContext(Dispatchers.IO) {
                fun detail(raw: String): String =
                    TranslateEngine.splitErrorCode(raw.removePrefix("ERROR:").trim()).second

                // ① 翻译本身可用吗（关掉思维深度）
                val plain = RustBridge.translateOrError(
                    "Hello",
                    "en",
                    base.target,
                    base.copy(thinking = TranslateThinking.OFF.code).engineOptionsJson(),
                )
                if (plain.startsWith("ERROR:")) {
                    TestOutcome(
                        LocalizedText(R.string.translate_thinking_unavailable, listOf(detail(plain))),
                        ok = false,
                    )
                } else if (!level.isActive(custom)) {
                    TestOutcome(LocalizedText(R.string.translate_thinking_ok), ok = true)
                } else {
                    // ② 服务商认不认这个字段（严格模式：失败不兜底，如实暴露）
                    val strict = RustBridge.translateOrError(
                        "Hello",
                        "en",
                        base.target,
                        base.copy(thinking = level.code, thinkingCustom = custom)
                            .engineOptionsJson(strictThinking = true),
                    )
                    if (strict.startsWith("ERROR:")) {
                        supported = false
                        TestOutcome(
                            LocalizedText(R.string.translate_thinking_rejected, listOf(detail(strict))),
                            ok = false,
                        )
                    } else {
                        supported = true
                        TestOutcome(LocalizedText(R.string.translate_thinking_ok_field), ok = true)
                    }
                }
            }
            testingTranslation = false
            translationOutcome = outcome
            supported?.let {
                TranslateSettings.setThinkingSupport(context, thinkingScope, it)
                thinkingSupported = it
            }
        }
    }

    /** 保存**只写配置**，不发任何网络请求（决策：保存与测试拆开）。 */
    fun saveCredentials() {
        // 空模型：用当前地址行的默认模型（没有就留空，翻译时会明确提示去填）
        if (modelDraft.isBlank()) modelDraft = protocolDraft.defaultModelFor(baseUrlDraft)
        TranslateSettings.setApiKey(context, keyDraft)
        TranslateSettings.setModel(context, modelDraft)
        TranslateSettings.setBaseUrl(context, baseUrlDraft)
        TranslateSettings.setProtocol(context, protocolDraft.code)
        TranslateSettings.setThinking(context, thinkingDraft.code, thinkingCustomDraft)
        config = TranslateSettings.read(context)
        translator.resetFailures()
        probeOutcome = null
        saveSuccess = true
        scope.launch { stats = translator.stats() }
    }

    /**
     * 保存入口：先做「值到底会不会生效」的拦截，再看协议是否变过。
     *
     * 判定为「将生效」才直接保存；否则先弹一次确认 —— 不是替用户决策（仍可保存），
     * 而是让「设了等于没设」这件事在**保存**这一步必须被看见。
     */
    fun requestSave() {
        val issue = if (thinkingDraft == TranslateThinking.CUSTOM) {
            TranslateThinking.checkCustom(thinkingCustomDraft, protocolDraft)
        } else {
            CustomValueIssue.OK_BUDGET
        }
        when {
            issue != CustomValueIssue.OK_BUDGET && issue != CustomValueIssue.OK_EFFORT ->
                customWarningOpen = true
            protocolDraft != config.protocolKind -> warningOpen = true
            else -> saveCredentials()
        }
    }

    /**
     * 「测试响应」：只确认端点**有响应**，不做任何真实翻译（不耗 token、不出译文）。
     * 复用 `GET {baseUrl}/models` —— 它正是「服务器是否接受这组地址 + Key」的最小请求。
     */
    fun probeResponse() {
        if (testingResponse) return
        testingResponse = true
        probeOutcome = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                ModelProbe.fetch(protocolDraft, baseUrlDraft, keyDraft)
            }
            testingResponse = false
            probeOutcome = when (result) {
                is ModelProbe.Result.Ok -> TestOutcome(
                    LocalizedText(R.string.translate_probe_ok, listOf(result.models.size.toString())),
                    ok = true,
                )
                is ModelProbe.Result.Fail -> TestOutcome(modelsFailure(result), ok = false)
            }
        }
    }

    /**
     * 获取模型列表（决策五）：只读当前输入框里的地址与 Key，**不先落盘** ——
     * 探测失败不该顺手改掉用户正在编辑的配置。
     */
    fun fetchModels() {
        if (fetchingModels) return
        fetchingModels = true
        modelError = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                ModelProbe.fetch(protocolDraft, baseUrlDraft, keyDraft)
            }
            fetchingModels = false
            when (result) {
                is ModelProbe.Result.Ok -> fetchedModels = result.models
                is ModelProbe.Result.Fail -> modelError = modelsFailure(result)
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
                            probeOutcome = null
                            // 模型探测的结果属于上一家服务商：换后端不能把旧列表留在屏上
                            fetchedModels = null
                            confirmingModels = null
                            modelError = null
                            credentialsExpanded = keyDraft.isBlank()
                            scope.launch { stats = translator.stats() }
                        }
                    }

                    // 外部模型的凭据块：只在选中时出现，且**默认可折叠**（决策二）。
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
                            // 决策三：块内再压一档 —— 输入框改成「框内两行」（标签 10sp + 值 13sp，
                            // 框高 ≥46dp），省掉独立标签行；块内边距 10/8，按钮与说明各收一号。
                            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                                // 协议行（2026-11 第八轮）：方言决定「路径 + 鉴权头 + 响应体」三件事，
                                // 所以排在四个字段的最前面 —— 先定怎么说，再填地址与 Key。
                                ProtocolField(
                                    label = stringResource(R.string.translate_protocol_label),
                                    value = stringResource(protocolDraft.labelRes),
                                    hint = stringResource(R.string.translate_protocol_choose),
                                    onClick = { protocolPickerOpen = true },
                                )
                                // 地址行（1.4.54 通用化）：协议只管「怎么说」，对谁说在这里。
                                // 标签行右侧是「官方地址」徽章 —— 点开是当前协议的已知地址列表，
                                // 选完写进下面的输入框；输入框本身仍可自由编辑（中转 / 自建）。
                                CompactField(
                                    label = stringResource(R.string.translate_base_url_label),
                                    value = baseUrlDraft,
                                    placeholder = protocolDraft.officialBaseUrl,
                                    mono = true,
                                    onValueChange = { baseUrlDraft = it },
                                    headerAction = {
                                        AddressListBadge { addressPickerOpen = true }
                                    },
                                )
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
                                            // 固定 16sp 行高：不加这一行，11sp + 默认行盒会比输入行高 2dp，
                                            // 把 API Key 框撑到 30dp，与另外两个 28dp 的框对不齐
                                            lineHeight = 16.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Primer.Blue500,
                                            modifier = Modifier
                                                .clickable { keyVisible = !keyVisible }
                                                .padding(horizontal = 2.dp),
                                        )
                                    },
                                )
                                // 模型行：标签行右侧「获取模型列表」，框内右侧绿色「当前」徽标。
                                // activeModel = 正在打的字或已填的值（打字过程中徽标不闪）
                                val activeModel = typingTarget ?: modelDraft.trim()
                                CompactField(
                                    label = stringResource(R.string.translate_model_label),
                                    value = modelDraft,
                                    placeholder = protocolDraft.defaultModelFor(baseUrlDraft),
                                    mono = true,
                                    onValueChange = { modelDraft = it },
                                    headerAction = {
                                        ModelListBadge(loading = fetchingModels) { fetchModels() }
                                    },
                                    trailingContent = if (activeModel.isNotBlank()) {
                                        { ModelCurrentBadge() }
                                    } else {
                                        null
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
                                // 候选芯片：本机保存的列表优先；还没探测过时回落到**当前协议**的已知候选。
                                // 横向滚动：网关可能返回几十个模型；选中蓝底、未选中白底。
                                // 协议没给候选（OpenAI / Anthropic / Gemini 的模型名变化快，不臆造）
                                // 且没探测过时，整行不出现 —— 空行比一排假候选诚实。
                                val chips = localModels.ifEmpty { protocolDraft.suggestionsFor(baseUrlDraft) }
                                if (chips.isNotEmpty()) {
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 6.dp)
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        chips.forEach { model ->
                                            SelectableModelChip(
                                                text = model,
                                                // 打字过程中按目标高亮：整段输入动画读起来是「这一项正在被选中」
                                                selected = model == activeModel,
                                                onClick = { typingTarget = model },
                                            )
                                        }
                                    }
                                }
                                // ── 思维深度（1.4.55）：与「翻译能不能用」无关，单独占一行 ──
                                // 单独拆出来的原因：它只影响质量与延迟，且**要花 token**；
                                // 不该和「地址 / Key / 模型」这些「不填就用不了」的字段混在一起。
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(top = 2.dp, bottom = 6.dp)
                                        .height(1.dp)
                                        .background(Primer.Gray200),
                                )
                                ProtocolField(
                                    label = stringResource(R.string.translate_thinking_label),
                                    value = stringResource(thinkingDraft.labelRes),
                                    hint = stringResource(R.string.translate_thinking_choose),
                                    onClick = { thinkingPickerOpen = true },
                                    headerAction = {
                                        // 与「获取模型列表」同款徽章：真实翻一句（消耗极少 token）
                                        ModelListBadge(
                                            loading = testingTranslation,
                                            textRes = R.string.translate_thinking_probe,
                                            loadingTextRes = R.string.translate_thinking_probing,
                                        ) { probeTranslation() }
                                    },
                                )
                                // 自定义值的判定（纯函数）：既驱动标签行徽章，也驱动保存前的拦截
                                val customIssue = TranslateThinking.checkCustom(thinkingCustomDraft, protocolDraft)
                                if (thinkingDraft == TranslateThinking.CUSTOM) {
                                    CompactField(
                                        label = stringResource(R.string.translate_thinking_custom_field),
                                        value = thinkingCustomDraft,
                                        placeholder = stringResource(R.string.translate_thinking_custom_placeholder),
                                        mono = true,
                                        onValueChange = { thinkingCustomDraft = it },
                                        headerAction = {
                                            // 只靠小字不可靠：先给一枚带色的状态徽章（将生效 / 等同关闭 / 可能被拒）
                                            StatusBadge(
                                                text = stringResource(customIssue.badgeRes),
                                                tone = customIssue.tone,
                                            )
                                        },
                                    )
                                    Text(
                                        stringResource(customIssue.messageRes),
                                        fontSize = 10.sp,
                                        lineHeight = 14.sp,
                                        color = when (customIssue.tone) {
                                            BadgeTone.OK -> Primer.SuccessTextStrong
                                            BadgeTone.OFF -> Primer.TextTertiary
                                            BadgeTone.RISKY -> Primer.DangerText
                                        },
                                        modifier = Modifier.padding(bottom = 2.dp),
                                    )
                                }
                                // 「推荐最低档」只对**选档位**有意义；选了自定义就不该再拿推荐挤一行
                                if (thinkingDraft != TranslateThinking.CUSTOM || thinkingSupported == false) {
                                    Text(
                                        if (thinkingSupported == false) {
                                            stringResource(R.string.translate_thinking_unsupported)
                                        } else {
                                            stringResource(R.string.translate_thinking_hint)
                                        },
                                        fontSize = 10.sp,
                                        lineHeight = 14.sp,
                                        color = if (thinkingSupported == false) Primer.DangerText else Primer.TextTertiary,
                                    )
                                }
                                translationOutcome?.let { outcome ->
                                    Box(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                                        TestResultText(outcome)
                                    }
                                }
                                Text(
                                    stringResource(R.string.translate_thinking_cost),
                                    fontSize = 10.sp,
                                    lineHeight = 14.sp,
                                    color = Primer.TextTertiary,
                                )

                                // 保存与测试拆开：保存只写配置；测试只探端点响应（不翻译、不耗 token）。
                                Row(
                                    Modifier.fillMaxWidth().padding(top = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    ActionPill(text = stringResource(R.string.action_save), enabled = true) {
                                        requestSave()
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    ActionPill(
                                        text = if (testingResponse) {
                                            stringResource(R.string.translate_testing)
                                        } else {
                                            stringResource(R.string.translate_probe_response)
                                        },
                                        enabled = !testingResponse,
                                        secondary = true,
                                    ) {
                                        probeResponse()
                                    }
                                }
                                probeOutcome?.let { outcome ->
                                    Box(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                                        TestResultText(outcome)
                                    }
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

    // 保存成功：盖一层轻弹窗，告诉用户「写进去了」（保存是静默写盘，没有这个反馈就像没反应）
    if (saveSuccess) {
        SaveSuccessDialog(onDismiss = { saveSuccess = false })
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

    // 协议单选弹窗：弹簧进场、点一项即生效 —— 单选不需要「保存」再确认一次。
    // 换协议要顺手把**默认值**带过去：地址与模型如果还是上一档的默认值（或空着），
    // 就跟到新协议；用户手填过的值一律不动（`carryOver` 只认「空」与「等于旧默认」两种）。
    if (protocolPickerOpen) {
        val previous = protocolDraft
        val previousAddressDefault = previous.defaultModelFor(baseUrlDraft)
        ProtocolPickerDialog(
            current = previous,
            recommended = TranslateProtocol.guessFrom(baseUrlDraft),
            onDismiss = { protocolPickerOpen = false },
            onSelect = { picked ->
                protocolDraft = picked
                baseUrlDraft = carryOver(baseUrlDraft, previous.officialBaseUrl, picked.officialBaseUrl)
                modelDraft = carryOver(modelDraft, previousAddressDefault, picked.defaultModelFor(baseUrlDraft))
                // 上一档的探测结果对新方言没有意义（路径与响应体都变了），一律清掉
                fetchedModels = null
                confirmingModels = null
                modelError = null
                probeOutcome = null
                protocolPickerOpen = false
            },
        )
    }

    // 地址单选弹窗：当前协议的已知地址（第一项 = 官方）。选完写进输入框，仍可手改。
    // 同样只带「默认值」：模型若还是上一行的默认模型（或空着）才跟到新行的默认模型。
    if (addressPickerOpen) {
        val previousDefaultModel = protocolDraft.defaultModelFor(baseUrlDraft)
        AddressPickerDialog(
            protocol = protocolDraft,
            currentBaseUrl = baseUrlDraft,
            onDismiss = { addressPickerOpen = false },
            onSelect = { address ->
                baseUrlDraft = address.baseUrl
                modelDraft = carryOver(
                    modelDraft,
                    previousDefaultModel,
                    protocolDraft.defaultModelFor(address.baseUrl),
                )
                fetchedModels = null
                confirmingModels = null
                modelError = null
                probeOutcome = null
                addressPickerOpen = false
            },
        )
    }

    // 思维深度单选弹窗：Off / Low / High / Max / 自定义（推荐最低档）
    if (thinkingPickerOpen) {
        ThinkingPickerDialog(
            current = thinkingDraft,
            onDismiss = { thinkingPickerOpen = false },
            onSelect = { picked ->
                thinkingDraft = picked
                // 换档位后上一次的「字段是否被接受」不再代表新档位
                translationOutcome = null
                thinkingPickerOpen = false
            },
        )
    }

    // 自定义值无效的保存确认：徽章之外的第二道（也更难忽略的）提示
    if (customWarningOpen) {
        val issue = TranslateThinking.checkCustom(thinkingCustomDraft, protocolDraft)
        CustomValueWarningDialog(
            value = thinkingCustomDraft,
            reason = stringResource(issue.messageRes),
            onDismiss = { customWarningOpen = false },
            onConfirm = {
                customWarningOpen = false
                if (protocolDraft != config.protocolKind) warningOpen = true else saveCredentials()
            },
        )
    }

    // 保存前的兼容性提醒（用户 2026-11-26 决策四）：只在**协议变了**时出现。
    // 「协议换了但 Key 还是上一家的」是这里最容易踩的坑，提醒一句比事后 401 好懂。
    if (warningOpen) {
        ProtocolWarningDialog(
            onDismiss = { warningOpen = false },
            onConfirm = {
                warningOpen = false
                saveCredentials()
            },
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
                TranslateSettings.setModels(context, modelScope, saved)
                localModels = saved
                // 当前模型如果不在这次保存的列表里，用排序后的首个当默认并用打字动画填入
                if (saved.isNotEmpty() && modelDraft.trim() !in saved) {
                    typingTarget = saved.first()
                }
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

/**
 * 换协议时的默认值接力：只接「空着的」与「还是上一档默认值的」两种。
 *
 * 例子：地址留空（或正好是 `https://api.deepseek.com`）时切到 Anthropic →
 * 跟着变成 `https://api.anthropic.com`；用户手填过中转地址则原样保留。
 * 这条规则**两边都对称**（新默认是空时也把旧默认清掉），否则会留下「上一档的模型名
 * 挂在新协议上」的脏状态。
 */
private fun carryOver(current: String, previousDefault: String, nextDefault: String): String =
    if (current.isBlank() || current.trim() == previousDefault) nextDefault else current

/** 翻译后端的界面文案在 app 层（模块只留与语言无关的 `code`，UI 文案必须走资源）。 */
private val TranslateProvider.labelRes: Int
    get() = when (this) {
        TranslateProvider.MYMEMORY -> R.string.translate_provider_mymemory_label
        TranslateProvider.EXTERNAL -> R.string.translate_provider_external_label
    }

private val TranslateProvider.descRes: Int
    get() = when (this) {
        TranslateProvider.MYMEMORY -> R.string.translate_provider_mymemory_desc
        TranslateProvider.EXTERNAL -> R.string.translate_provider_external_desc
    }

/** 判定 → 徽章文字（极短：徽章说不下原因，原因交给下面那行）。 */
private val CustomValueIssue.badgeRes: Int
    get() = when (this) {
        CustomValueIssue.OK_BUDGET, CustomValueIssue.OK_EFFORT ->
            R.string.translate_thinking_custom_badge_ok
        CustomValueIssue.EMPTY, CustomValueIssue.DIGITS_IGNORED, CustomValueIssue.WORD_IGNORED ->
            R.string.translate_thinking_custom_badge_off
        else -> R.string.translate_thinking_custom_badge_risky
    }

/** 判定 → 徽章语义（绿 / 灰 / 红）。 */
private val CustomValueIssue.tone: BadgeTone
    get() = when (this) {
        CustomValueIssue.OK_BUDGET, CustomValueIssue.OK_EFFORT -> BadgeTone.OK
        CustomValueIssue.EMPTY, CustomValueIssue.DIGITS_IGNORED, CustomValueIssue.WORD_IGNORED -> BadgeTone.OFF
        else -> BadgeTone.RISKY
    }

/** 自定义值的校验结论 → 一句话（模块只给分类，句子在这里）。 */
private val CustomValueIssue.messageRes: Int
    get() = when (this) {
        CustomValueIssue.OK_BUDGET -> R.string.translate_thinking_custom_ok_budget
        CustomValueIssue.OK_EFFORT -> R.string.translate_thinking_custom_ok_effort
        CustomValueIssue.EMPTY -> R.string.translate_thinking_custom_empty
        CustomValueIssue.DIGITS_IGNORED -> R.string.translate_thinking_custom_digits_ignored
        CustomValueIssue.WORD_IGNORED -> R.string.translate_thinking_custom_word_ignored
        CustomValueIssue.BUDGET_TOO_SMALL -> R.string.translate_thinking_custom_too_small
        CustomValueIssue.BUDGET_TOO_LARGE -> R.string.translate_thinking_custom_too_large
        CustomValueIssue.UNKNOWN_EFFORT -> R.string.translate_thinking_custom_unknown_effort
    }

/** 协议名（短名，进「协议」字段的值位）。 */
internal val TranslateProtocol.labelRes: Int
    get() = when (this) {
        TranslateProtocol.OPENAI_CHAT -> R.string.translate_protocol_openai_chat
        TranslateProtocol.OPENAI_RESPONSES -> R.string.translate_protocol_openai_responses
        TranslateProtocol.ANTHROPIC_MESSAGES -> R.string.translate_protocol_anthropic
        TranslateProtocol.GEMINI -> R.string.translate_protocol_gemini
    }

/** 协议的一句话说明（进弹窗的每一行）：说清端点与鉴权头这两个用户能验证的事实。 */
internal val TranslateProtocol.descRes: Int
    get() = when (this) {
        TranslateProtocol.OPENAI_CHAT -> R.string.translate_protocol_openai_chat_desc
        TranslateProtocol.OPENAI_RESPONSES -> R.string.translate_protocol_openai_responses_desc
        TranslateProtocol.ANTHROPIC_MESSAGES -> R.string.translate_protocol_anthropic_desc
        TranslateProtocol.GEMINI -> R.string.translate_protocol_gemini_desc
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

/**
 * 候选模型芯片：选中蓝底白字、未选中白底深字（决策：用底色区分选中态）。
 *
 * 白底 + `BorderControl` 描边：白底在 `Gray100` 卡片上仍然看得见，且 1dp 控件边界
 * 满足 3:1（装饰性 `Border` 只有 1.8:1）。
 */
@Composable
private fun SelectableModelChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) Color.White else Primer.TextSecondary,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) Primer.Blue500 else Primer.BackgroundPrimary)
            .border(
                1.dp,
                if (selected) Primer.Blue500 else Primer.BorderControl,
                RoundedCornerShape(999.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 9.dp, vertical = 3.dp),
    )
}

/**
 * 「当前使用模型」徽标（绿色）：放在模型框内右侧，高度 16dp —— 刚好与 28dp 框的
 * 内容行同高，不会把框撑高（这正是 1.4.48 之前 API Key 框比别的框高 2dp 的同一个坑）。
 *
 * 配色：`SuccessTextStrong` 底 + `BackgroundPrimary` 字。浅色下 ≈4.9:1、深色下 ≈7:1。
 */
@Composable
private fun ModelCurrentBadge() {
    Text(
        stringResource(R.string.translate_model_current),
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 12.sp,
        color = Primer.BackgroundPrimary,
        modifier = Modifier
            .background(Primer.SuccessTextStrong, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

/**
 * 保存成功的覆盖弹窗：弹簧曲线进场（缩放过冲 + 轻微下移），约 1.8s 自动消失，
 * 点外面也能关。保存是**静默写盘**，没有这一层反馈时用户会以为按钮没生效。
 *
 * 配色：绿色勾放在 `SuccessSurface` 圆底上（`SuccessTextStrong` 勾 ≈5.2:1），
 * 正文用 `TextSecondary`，标题 `TextPrimary` —— 不引入新的色值。
 */
@Composable
private fun SaveSuccessDialog(onDismiss: () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        shown = true
        delay(1800)
        closing = true
        delay(180)
        onDismiss()
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // usePlatformDefaultWidth = false 时窗口铺满屏幕、内容默认落在左上角 ——
        // 必须自己用全屏 Box 居中，否则成功提示会贴在屏幕左上。
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AnimatedVisibility(
                visible = shown && !closing,
                enter = scaleIn(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                    initialScale = 0.72f,
                ) + fadeIn(tween(140)) + slideInVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                    initialOffsetY = { it / 8 },
                ),
                exit = scaleOut(targetScale = 0.9f) + fadeOut(tween(120)),
            ) {
                Column(
                    Modifier
                        .padding(horizontal = 48.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Primer.BackgroundPrimary)
                        .padding(horizontal = 22.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(44.dp).background(Primer.SuccessSurface, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = Primer.SuccessTextStrong,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.translate_saved_title),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Primer.TextPrimary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.translate_saved_body),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = Primer.TextSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * 操作按钮：主按钮蓝底白字、次按钮浅灰底深色字。
 *
 * 本轮把「保存」与「测试响应」拆成两个：保存只写配置，测试只探端点 ——
 * 一个按钮同时做两件事时，用户无法只保存不测试（或只测试不保存）。
 */
@Composable
private fun ActionPill(
    text: String,
    enabled: Boolean,
    secondary: Boolean = false,
    onClick: () -> Unit,
) {
    val background = when {
        !enabled -> Primer.Gray100
        secondary -> Primer.Gray150
        else -> Primer.Blue500
    }
    val foreground = when {
        !enabled -> Primer.TextTertiary
        secondary -> Primer.TextPrimary
        else -> Color.White
    }
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = foreground,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(background)
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
