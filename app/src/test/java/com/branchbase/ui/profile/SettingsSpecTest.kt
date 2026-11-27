package com.branchbase.ui.profile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置树的**结构性钉子**（源码级，套路同 `ui/repository/WorkflowLogThemeTest`）。
 *
 * `docs/specs/settings-design.md` 是一份规范，但规范写在文档里不会自己生效 ——
 * 这个类把其中**机械可判定**的几条变成断言，改坏了立刻红：
 *
 * | # | 钉子 | 规范 |
 * |---|---|---|
 * | 1 | 无写死色值 | §9 |
 * | 2 | 设置页不许自制行（chevron 只许出现在公共行组件里） | §4.1 |
 * | 3 | 开关行不配「保存」 | §5.3 |
 * | 4 | 危险行只许打开确认，不许直接执行 | §7.2 |
 * | 5 | 枚举存 `name` 不存 `ordinal` | §8.2 |
 * | 6 | 二级页返回目标唯一（回设置主页，不是回个人主页） | §3.1 |
 * | 7 | 落盘键只在唯一文件里声明 | §8.1 |
 * | 8 | 玻璃导航栏开关走 SettingsKeys 且设置页提供 SwitchRow | §5.2 / §8.1 |
 */
class SettingsSpecTest {

    private fun source(path: String) = File(path).readText()

    /**
     * 去掉 Kotlin 注释后的源码。
     *
     * 有些钉子钉的正是「代码里不许出现某个写法」，而那个写法**恰好要写在注释里**
     * （`SettingsRow.kt` 的「别写 `fill = false`，否则行尾控件乱跑」）。
     * 直接扫原文会让「把坑写下来」这个好习惯变成假红 —— 而假红比没有检查更坏：
     * 它训练人忽略这套钉子。字符串字面量原样保留（`"http://…"` 里的 `//` 不是注释）。
     */
    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == '/' && src.startsWith("//", i) -> while (i < src.length && src[i] != '\n') i++
                c == '/' && src.startsWith("/*", i) -> {
                    var depth = 1
                    i += 2
                    while (i < src.length && depth > 0) {
                        when {
                            src.startsWith("/*", i) -> { depth++; i += 2 }
                            src.startsWith("*/", i) -> { depth--; i += 2 }
                            else -> i++
                        }
                    }
                }
                c == '"' -> {
                    out.append(c)
                    i++
                    while (i < src.length && src[i] != '"') {
                        if (src[i] == '\\') { out.append(src[i]); i++ }
                        if (i < src.length) { out.append(src[i]); i++ }
                    }
                    if (i < src.length) { out.append(src[i]); i++ }
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    /** 设置树参与渲染的全部源文件。 */
    private val settingsTree = listOf(
        "src/main/java/com/branchbase/ui/settings/SettingsRow.kt",
        "src/main/java/com/branchbase/ui/settings/SettingsKeys.kt",
        "src/main/java/com/branchbase/ui/settings/GitProxy.kt",
        "src/main/java/com/branchbase/ui/settings/GitProxyScreen.kt",
        "src/main/java/com/branchbase/ui/settings/AppLanguage.kt",
        "src/main/java/com/branchbase/ui/settings/LanguageScreen.kt",
        "src/main/java/com/branchbase/ui/settings/ThemeScreen.kt",
        "src/main/java/com/branchbase/ui/profile/SubPageScreens.kt",
        "src/main/java/com/branchbase/ui/profile/CommitModeScreen.kt",
        "src/main/java/com/branchbase/ui/profile/CommitModeStore.kt",
        "src/main/java/com/branchbase/ui/profile/TranslateSettingsScreen.kt",
        "src/main/java/com/branchbase/ui/profile/TranslateModelDialogs.kt",
    )

    private val rowComponentsPath = "src/main/java/com/branchbase/ui/settings/SettingsRow.kt"
    private val themePagePath = "src/main/java/com/branchbase/ui/settings/ThemeScreen.kt"
    private val settingsPagePath = "src/main/java/com/branchbase/ui/profile/SubPageScreens.kt"
    private val translatePagePath = "src/main/java/com/branchbase/ui/profile/TranslateSettingsScreen.kt"
    /** 方言表在隔壁 :translate 模块（测试工作目录是 app/，所以用 ../ 上去）。 */
    private val protocolPath = "../translate/src/main/java/com/branchbase/translate/TranslateProtocol.kt"

    /**
     * 设置主页 + 通知页那一段（不含同一文件里的关于页 / 本地仓库页）。
     *
     * 划出这一段是因为 `SubPageScreens.kt` 还住着别的页面：钉子必须只约束设置树，
     * 否则「关于页用了某个图标」会把设置页判红（假红，比没有测试更坏）。
     */
    private fun settingsRegion(): String {
        val s = source("src/main/java/com/branchbase/ui/profile/SubPageScreens.kt")
        val start = s.indexOf("fun SettingsScreen(")
        val end = s.indexOf("// ───────────────────────── 本地仓库列表页")
        assertTrue("找不到设置页区段（start=$start end=$end）", start in 0 until end)
        return s.substring(start, end)
    }

    // ── ① 无写死色值（§9） ──────────────────────────────────────────────

    @Test
    fun `设置树里没有写死色值`() {
        settingsTree.forEach { path ->
            val hits = Regex("""Color\(0x""").findAll(source(path)).toList()
            assertEquals("$path 里出现了写死的 Color(0x…)，应当取 Primer 语义角色（规范 §9）", 0, hits.size)
        }
    }

    @Test
    fun `危险文字用语义角色而不是填充色`() {
        val row = source(rowComponentsPath)
        assertTrue("DangerRow 的文字必须走 Primer.DangerText（规范 §7.2）", row.contains("Primer.DangerText"))
        assertFalse(
            "DangerRow 里出现 Primer.Red500 当文字：浅色下只有 3.13:1，低于 AA",
            Regex("""DangerRow[\s\S]{0,600}?Primer\.Red500""").containsMatchIn(row),
        )
    }

    // ── ② 行型封闭：不许自制行（§4.1） ──────────────────────────────────

    @Test
    fun `六个行型都还在公共组件文件里`() {
        val row = source(rowComponentsPath)
        // 只匹配「名字 + 左括号」，不匹配 "fun Xxx(" —— ChoiceRow 是泛型的（`fun <T> ChoiceRow(`），
        // 写成 "fun ChoiceRow(" 会永远找不到，那是测试假红（第一版就踩了）
        listOf("NavRow(", "SwitchRow(", "ChoiceRow(", "ActionRow(", "DangerRow(", "InfoRow(")
            .forEach { assertTrue("缺少行型：$it（规范 §4.1 的封闭集合）", row.contains(it)) }
    }

    @Test
    fun `设置页里没有自制行`() {
        val region = settingsRegion()
        // chevron 是「这是一条导航行」的唯一视觉承诺。页面自己画 chevron，
        // 就等于自己实现了一套导航行 —— 那正是 8 套行组件的成因（规范 §4.1 / §12）。
        assertFalse(
            "设置页里出现了 ChevronRight：行必须来自 ui/settings/SettingsRow.kt 的 6 种行型",
            region.contains("ChevronRight"),
        )
    }

    @Test
    fun `设置页的行都来自公共行组件`() {
        val region = settingsRegion()
        val used = listOf(
            "NavRow(", "SwitchRow(", "ChoiceRow(", "ActionRow(", "DangerRow(", "InfoRow(",
            "DisabledNavRow(", "SettingsProse(", "ModeOptionRow(",
        ).filter { region.contains(it) }
        // 2026-11：主页只剩 4 种行型 —— 压密删掉账户卡，主题三档搬进主题页后连 ChoiceRow 也不在主页了
        // （+ 说明段落不再上主页）。变的只是行型的**数量**，「必须来自公共组件」这条没变。
        assertTrue("设置页一个公共行组件都没用到，说明又退回了自制行", used.size >= 4)
    }

    // ── ③ 开关不配「保存」（§5.3） ──────────────────────────────────────

    @Test
    fun `设置页没有保存按钮`() {
        assertFalse(
            "设置页出现了「保存」：开关与选择行必须立即生效，不配保存（规范 §5.3）",
            settingsRegion().contains("\"保存\""),
        )
    }

    // ── ③′ 主页是「入口索引」，不是设置项容器（2026-11 压密） ────────────

    /**
     * 设置主页那一段（到通知页为止）。
     *
     * [settingsRegion] 从 SettingsScreen 一直划到「本地仓库列表页」，中途还住着
     * NotificationSettingsScreen —— 而二级页**允许**保留说明段落（本轮只压主页）。
     * 用整段去断言「不许出现 sub / SettingsProse」会把通知页判红，那是假红。
     */
    private fun settingsHomeRegion(): String {
        val s = source(settingsPagePath)
        val start = s.indexOf("fun SettingsScreen(")
        val end = s.indexOf("fun NotificationSettingsScreen(")
        assertTrue("找不到设置主页区段（start=$start end=$end）", start in 0 until end)
        return s.substring(start, end)
    }

    @Test
    fun `设置主页不展开说明文字`() {
        val region = settingsHomeRegion()
        assertFalse(
            "设置主页又出现了行内说明（`sub = stringResource…`）：主页一行一个入口，" +
                "说明归各自二级页（见 docs/specs/settings-compact-redesign.md）",
            Regex("""sub\s*=\s*stringResource""").containsMatchIn(region),
        )
        assertFalse(
            "设置主页又出现了 SettingsProse 说明段落（主页不再放散文）",
            region.contains("SettingsProse("),
        )
    }

    /**
     * 密度是**每页一次**的外参（2026-11 第五轮放宽）。
     *
     * 第三轮（设置主页压密）时这条钉的是「全仓只许主页 provide 一次」—— 当时的背景是
     * 二级页一行都不动。第五轮明确让**沉浸式翻译页也压密**，所以规则升级为：
     *
     * - 每个**要压密**的设置页在自己根上 provide 一次（当前：主页、翻译页）；
     * - 其余设置文件 0 次（Comfort 是默认值）；
     * - **不许在行组件里 provide** —— 那会把「某一页的密度」变成「所有调用点的密度」，
     *   行组件文件本身就不该出现 `LocalSettingsDensity provides`。
     */
    @Test
    fun `设置页的密度只在各自根上 provide 一次`() {
        assertTrue(
            "设置主页必须在根部 provide SettingsDensity.Compact（否则行高/图标/卡片底回到舒适档）",
            settingsHomeRegion().contains("LocalSettingsDensity provides SettingsDensity.Compact"),
        )
        val dens = Regex("LocalSettingsDensity provides")
        assertEquals(
            "设置主页只许 provide 一次",
            1,
            dens.findAll(source(settingsPagePath)).count(),
        )
        assertEquals(
            "沉浸式翻译页也要压密（决策二：整页行高）",
            1,
            dens.findAll(source(translatePagePath)).count(),
        )
        // 行组件 / 其它二级页不许跟着改：Comfort 是 CompositionLocal 的默认值
        settingsTree
            .filter { it != settingsPagePath && it != translatePagePath }
            .forEach { path ->
                assertEquals(
                    "$path 不许 provide 密度：压密只发生在具体设置页的根上（行组件里 provide 会污染所有调用点）",
                    0,
                    dens.findAll(source(path)).count(),
                )
            }
    }

    // ── ⑨″ 翻译页（2026-11 第五轮：压密 + 说明进弹窗 + 页头徽标） ─────────

    @Test
    fun `主页翻译组只剩入口_总开关只在翻译页`() {
        val region = settingsHomeRegion()
        assertFalse(
            "主页又出现了「自动翻译正文」开关：它是同一份 translate.enabled 的第二个入口，" +
                "总开关只在翻译页（modules-design §1「开启入口只有一个」）",
            region.contains("translate_auto_toggle"),
        )
        assertTrue("主页要保留「沉浸式翻译」入口行", region.contains("onOpenTranslate"))
        assertTrue("总开关必须留在翻译页顶部", source(translatePagePath).contains("translate_auto_toggle"))
    }

    @Test
    fun `翻译页已卡片化_长说明收进弹窗`() {
        val page = source(translatePagePath)
        assertTrue("翻译页必须用卡片式 SettingsSection（§4.4 同一棵树要统一）", page.contains("SettingsSection("))
        assertTrue("说明由页头徽标打开 SettingsNotesDialog", page.contains("SettingsNotesDialog("))
        assertTrue("页头徽标要进 trailing 槽（文案走资源）", page.contains("translate_notes_badge"))
        assertFalse("翻译页还有无卡片的过渡态 SettingsSectionTitle", page.contains("SettingsSectionTitle("))
        assertFalse("翻译页又出现了页尾散文 SettingsProse", page.contains("SettingsProse("))
        assertFalse("翻译页不许再摊着 570 字的 translate_notes_body", page.contains("translate_notes_body"))
        assertFalse(
            "SettingsSectionTitle 已无调用点：过渡态函数要从行组件文件里删掉",
            source(rowComponentsPath).contains("fun SettingsSectionTitle("),
        )
    }

    @Test
    fun `翻译页凭据块可折叠_模型探测有二次确认`() {
        val page = source(translatePagePath)
        assertTrue("凭据块要有折叠入口行", page.contains("translate_credentials_toggle"))
        assertTrue(
            "展开 / 收起必须走 AnimatedVisibility + spring（决策四的弹性动画），不是固定铺展",
            page.contains("AnimatedVisibility(") && page.contains("spring("),
        )
        assertTrue("模型探测结果要进选择弹窗", page.contains("ModelPickerDialog("))
        assertTrue("保存本机列表前必须有二次确认弹窗", page.contains("ModelLocalSaveDialog("))
        assertTrue("确认后才写本地列表", page.contains("TranslateSettings.setModels"))
    }

    @Test
    fun `说明弹窗有可拖动的滚动条`() {
        val row = source(rowComponentsPath)
        assertTrue("说明弹窗要画滚动条（AlertDialog 没有系统滚动条）", row.contains("NotesScrollbar("))
        assertTrue(
            "滚动条要能拖（detectVerticalDragGestures），不是纯装饰",
            row.contains("detectVerticalDragGestures"),
        )
        // 二次确认的正文是本轮新增的安全边界，两句关键承诺不能被删掉
        val strings = source("src/main/res/values/strings.xml")
        listOf("不会上传任何第三方服务端", "均为恶意修改版本", "官方仓库发布列表中下载正确版本").forEach { claim ->
            assertTrue("隐私说明缺少关键承诺：$claim", strings.contains(claim))
        }
    }

    @Test
    fun `翻译页模型选择与保存测试拆分`() {
        val page = source(translatePagePath)
        assertTrue(
            "模型框不能空着：进入时要用当前生效的默认模型预填",
            page.contains("protocolKind.defaultModelFor(config.baseUrl)"),
        )
        assertTrue("选中模型要有逐字输入动画", page.contains("typingTarget") && page.contains("delay("))
        assertTrue("当前模型要有绿色徽标", page.contains("translate_model_current"))
        assertTrue("保存与测试必须是两个动作", page.contains("translate_probe_response"))
        assertFalse(
            "「测试响应」不许再走真实翻译（会消耗用户额度）；只探端点响应",
            page.contains("translator.selfTest("),
        )
    }

    @Test
    fun `翻译页协议单选_请求头自动补全只有一个真源`() {
        val page = source(translatePagePath)
        assertTrue("凭据块要有协议字段（与另外三个输入框同形态）", page.contains("ProtocolField("))
        assertTrue("协议选择要走弹性弹窗", page.contains("ProtocolPickerDialog("))
        assertTrue("协议是用户配置，必须落盘", page.contains("TranslateSettings.setProtocol"))
        assertTrue("换协议要把默认地址 / 模型接力过去", page.contains("carryOver("))
        assertTrue(
            "探测必须按当前协议发（否则 Anthropic 的 x-api-key 永远补不上）",
            page.contains("ModelProbe.fetch(protocolDraft"),
        )
        assertTrue("方言表在 :translate（探测与将来的 Rust 分派读同一张表）", File(protocolPath).exists())
        val table = source(protocolPath)
        listOf("openai-chat", "openai-responses", "anthropic-messages", "gemini").forEach { code ->
            assertTrue("方言表缺少协议 $code", table.contains("code = \"$code\""))
        }
        // 1.4.54：DeepSeek 不再是协议，而是协议下的一行**地址**
        assertTrue("地址表里要保留 DeepSeek 这一行", table.contains("id = \"deepseek\""))
        assertTrue("协议层不许再出现 deepseek 档", !table.contains("DEEPSEEK("))
        listOf("Authorization", "x-api-key", "x-goog-api-key", "anthropic-version").forEach { header ->
            assertTrue("请求头自动补全缺少 $header", table.contains(header))
        }
        assertTrue("模型列表端点也要按协议拼", table.contains("modelsPath"))

        // 两侧是**同一张表的两份实现**（Kotlin 拼探测请求、Rust 发翻译请求）：
        // 一边加档、另一边没跟上时，这里必须红 —— 否则线上表现是「协议选了不生效」。
        val rustTable = File("../core/src/translate/mod.rs").readText()
        com.branchbase.translate.TranslateProtocol.entries.forEach { protocol ->
            assertTrue(
                "Rust 方言表缺少协议 ${protocol.code}（Kotlin 与 core/src/translate/mod.rs 必须逐字一致）",
                rustTable.contains("\"${protocol.code}\""),
            )
        }
        listOf("x-api-key", "x-goog-api-key", "anthropic-version").forEach { header ->
            assertTrue("Rust 侧缺少头名 $header", rustTable.contains(header))
        }
        // 协议码在 Rust 侧也必须只有四档（deepseek 只作为 from_code 的兼容别名出现）
        listOf("openai-chat", "openai-responses", "anthropic-messages", "gemini").forEach { code ->
            assertTrue("Rust 方言表缺少协议 $code", rustTable.contains("\"$code\""))
        }
        assertTrue(
            "Rust 侧必须有回应答解析模块（Chat / Responses / Anthropic / Gemini 各一份）",
            listOf("openai_chat.rs", "responses.rs", "anthropic.rs", "gemini.rs", "prompt.rs")
                .all { File("../core/src/translate/$it").exists() },
        )
    }

    @Test
    fun `翻译页地址与协议拆开_换协议才提醒`() {
        val page = source(translatePagePath)
        assertTrue("地址行要有「官方地址」入口（徽章 + 弹窗）", page.contains("AddressListBadge"))
        assertTrue("地址选择要走弹性弹窗", page.contains("AddressPickerDialog("))
        assertTrue("换协议时要提醒 Key 格式与厂商兼容性", page.contains("ProtocolWarningDialog("))
        assertTrue(
            "模型列表要按协议 + 地址分桶（1.4.53 的全局一份会把上一家的模型飘过来）",
            page.contains("TranslateSettings.modelsScope("),
        )
        // 服务商行改名：不再是 DeepSeek
        val strings = source("src/main/res/values/strings.xml")
        assertTrue("服务商行要叫「外部模型接入」", strings.contains("name=\"translate_provider_external_label\">外部模型接入"))
        assertFalse("「DeepSeek（自带 Key）」这类口径不许回来", strings.contains("自带 Key"))
    }

    @Test
    fun `翻译页思维深度单独一行_两套探测互不混用`() {
        val page = source(translatePagePath)
        assertTrue("档位选择要走弹性弹窗", page.contains("ThinkingPickerDialog("))
        assertTrue("思维深度要有独立一行", page.contains("translate_thinking_label"))
        assertTrue("要有自己的探测按钮（不是复用测试响应）", page.contains("probeTranslation()"))
        assertTrue(
            "探测必须走严格模式：否则「服务商不认字段」会被去掉字段的兜底掩盖成成功",
            page.contains("strictThinking = true"),
        )
        assertTrue("探测结论要落盘（界面据此标灰）", page.contains("TranslateSettings.setThinkingSupport"))
        assertTrue("保存时档位要落盘", page.contains("TranslateSettings.setThinking("))
        assertTrue(
            "自定义值要当场校验（填了但当前协议不认 → 等同关闭，必须当场说清）",
            page.contains("TranslateThinking.checkCustom("),
        )
        // 只靠小字不可靠：必须有带色徽章 + 保存前的拦截
        assertTrue("自定义值要有状态徽章", page.contains("StatusBadge("))
        assertTrue("无效值保存前要拦一次", page.contains("CustomValueWarningDialog("))
        assertTrue("保存走统一的 requestSave（先校验再存）", page.contains("requestSave()"))
        // 两套探测的结论变量必须分开：混用一个就会「测完端口把翻译结论冲掉」
        assertTrue(page.contains("probeOutcome"))
        assertTrue(page.contains("translationOutcome"))
    }

    @Test
    fun `翻译页保存成功有弹簧反馈`() {
        val page = source(translatePagePath)
        assertTrue("保存成功要有覆盖弹窗", page.contains("SaveSuccessDialog("))
        assertTrue("弹窗进场要走 spring（曲线动画），不是直接出现", page.contains("spring("))
        val strings = source("src/main/res/values/strings.xml")
        assertTrue(
            "提示文案要说明写到了本地",
            strings.contains("name=\"translate_saved_body\">当前本地已成功保存相应的 Key 配置"),
        )
    }

    @Test
    fun `翻译页行内说明控制在一行`() {
        // 决策二：整页行高压下来，靠的是「每行一句、11sp 一行放得下」。
        // 文本区约 246dp（360 − 行内边距 − 圆点 − 间距 − 开关），11sp 下约 22 个汉字。
        // 这里钉住翻译页**行内**用到的说明/描述资源，防止以后又把长句塞回行里。
        val strings = source("src/main/res/values/strings.xml")
        listOf(
            "translate_auto_toggle_desc", "translate_mode_dual_desc", "translate_mode_only_desc",
            "translate_mix_match_desc", "translate_mix_whole_desc", "translate_mix_skip_desc",
            "translate_style_card_desc", "translate_style_underline_desc", "translate_style_plain_desc",
            "translate_guard_toggle_desc", "translate_cache_disk_toggle_desc", "translate_key_note",
            "translate_provider_mymemory_desc", "translate_provider_external_desc",
            "translate_lang_zh_desc", "translate_lang_en_desc",
        ).forEach { key ->
            val value = Regex("""<string name="$key">([^<]*)</string>""").find(strings)
            assertTrue("找不到 $key", value != null)
            assertTrue(
                "$key 超过 22 个字（${value!!.groupValues[1].length}）：行内说明要一行放得下，" +
                    "长解释进「!」弹窗（settings-design §十八）",
                value.groupValues[1].length <= 22,
            )
        }
    }

    @Test
    fun `开关行的开关立即回调`() {
        val row = source(rowComponentsPath)
        val switchRow = row.substring(row.indexOf("SwitchRow("), row.indexOf("ChoiceRow("))
        assertTrue("SwitchRow 必须把 onCheckedChange 直接接到 Switch 上", switchRow.contains("onCheckedChange = onCheckedChange"))
        assertTrue("SwitchRow 的整行点击也要立刻生效", switchRow.contains("onClick = { onCheckedChange(!checked) }"))
    }

    // ── ④ 危险行必须二次确认（§7.2 / §7.3） ─────────────────────────────

    @Test
    fun `危险行只打开确认而不直接执行`() {
        val region = settingsRegion()
        val dangerAt = region.indexOf("DangerRow(")
        assertTrue("设置页应当有一个危险行（退出登录）", dangerAt >= 0)
        val block = region.substring(dangerAt, minOf(region.length, dangerAt + 700))

        assertTrue("危险行的 onClick 必须打开二次确认（规范 §7.2）", block.contains("confirmLogout = true"))
        assertFalse(
            "危险行里直接调用了 onLogout()：破坏性动作必须过确认框",
            block.substringBefore("},").contains("onLogout()"),
        )
    }

    @Test
    fun `确认框写清对象名_影响范围与不可撤销`() {
        val region = settingsRegion()
        // 标题与按钮文案已经**资源化**（i18n 抽取），所以这两条钉子钉在**资源值**上 ——
        // 那才是用户看到的文案。继续钉 Kotlin 源码文本的话，每抽取一次就假红一次，
        // 而假红比没有检查更坏：它训练人忽略这套钉子。
        val strings = source("src/main/res/values/strings.xml")
        assertTrue(
            "确认框标题要含对象名（动词 + 对象名，规范 §4.6）",
            strings.contains(">退出登录 %1\$s<"),
        )
        assertTrue(
            "确认按钮要写动词，不是「确定」（规范 §7.2）",
            strings.contains("name=\"action_sign_out\">退出登录<"),
        )
        // 正文也已资源化，同样钉资源值（钉源码文本会在每次抽取后假红一次）
        assertTrue(
            "确认框正文要写「会发生什么」",
            strings.contains("name=\"confirm_sign_out_body\">将清除本机保存的凭据"),
        )
        assertTrue("确认框正文要写「影响范围」", strings.contains("不受影响"))
        assertTrue("确认框正文要写「能不能撤销」", strings.contains("此操作不可撤销"))
    }

    // ── ⑤ 枚举存 name 不存 ordinal（§8.2） ─────────────────────────────

    @Test
    fun `设置落盘不存 ordinal`() {
        settingsTree.forEach { path ->
            assertFalse(
                "$path 里出现 .ordinal：枚举落盘要存 name，顺序一变就串档（规范 §8.2）",
                source(path).contains(".ordinal"),
            )
        }
    }

    // ── ⑥ 返回目标唯一（§3.1） ─────────────────────────────────────────

    @Test
    fun `设置树的二级页返回目标都是设置主页`() {
        val profile = source("src/main/java/com/branchbase/ui/profile/ProfileScreen.kt")
        val subPages = listOf(
            "LocalRepo", "About", "Log", "NotificationSettings",
            "Translate", "Accounts", "CommitMode", "GitProxy", "Language", "Theme",
        )
        subPages.forEach { page ->
            val marker = "SubPage.$page -> "
            val at = profile.indexOf(marker)
            assertTrue("ProfileScreen 里找不到 $marker 的路由", at >= 0)
            val line = profile.substring(at, profile.indexOf('\n', at))
            assertTrue(
                "$page 的返回目标不是设置主页；规范 §3.1 要求二级页统一回 Settings（当前：$line）",
                line.contains("onBack = { subPage = SubPage.Settings }"),
            )
        }
    }

    @Test
    fun `设置树的返回层级由 subPageDepth 唯一决定`() {
        val profile = source("src/main/java/com/branchbase/ui/profile/ProfileScreen.kt")
        val depthBlock = profile.substring(
            profile.indexOf("internal fun subPageDepth"),
            profile.indexOf("internal fun profileBackTarget"),
        )
        // 二级页都必须是 depth 2，否则返回键会跳过设置主页
        listOf(
            "LocalRepo", "About", "Log", "NotificationSettings", "Translate",
            "Accounts", "CommitMode", "GitProxy", "Language", "Theme",
        )
            .forEach { assertTrue("subPageDepth 漏了 SubPage.$it", depthBlock.contains("SubPage.$it")) }
    }

    // ── ⑦ 落盘键集中（§8.1） ───────────────────────────────────────────

    @Test
    fun `设置键只在唯一文件里声明`() {
        val keysPath = "src/main/java/com/branchbase/ui/settings/SettingsKeys.kt"
        assertTrue("设置键必须集中声明（规范 §8.1）", File(keysPath).exists())

        val literals = listOf("\"commit_mode\"", "\"git_proxy\"", "\"notif_layout\"", "\"glass_navigation\"")
        literals.forEach { literal ->
            settingsTree.forEach { path ->
                if (path == keysPath) return@forEach
                assertFalse(
                    "$path 里出现了键字面量 $literal：应当引 SettingsKeys（规范 §8.1 / §8.3）",
                    source(path).contains(literal),
                )
            }
        }

        val keys = source(keysPath)
        listOf("COMMIT_MODE", "GIT_PROXY", "NOTIF_LAYOUT", "GLASS_NAVIGATION").forEach {
            assertTrue("SettingsKeys 缺少 $it", keys.contains("const val $it"))
        }
    }

    // ── ⑧ 行解剖：尺寸与省略（§4.2） ────────────────────────────────────

    @Test
    fun `行高与图标尺寸符合规范`() {
        val row = source(rowComponentsPath)
        assertTrue("行最小高度应为 48dp（规范 §4.2）", row.contains("RowMinHeight = 48.dp"))
        assertTrue("图标应为 20dp（规范 §4.2）", row.contains("IconSize = 20.dp"))
        assertTrue("左右内边距应为 16dp（规范 §4.2）", row.contains("RowPaddingH = 16.dp"))
        // 紧凑档（设置主页）：行 44dp / 图标 18dp —— 这两个值就是「压密」本身，
        // 改回 48/20 等于悄悄撤销这一版的结构性改动
        assertTrue("紧凑档行最小高度应为 44dp", row.contains("rowMinHeight = 44.dp"))
        assertTrue("紧凑档图标应为 18dp", row.contains("iconSize = 18.dp"))
        // 1.4.67：卡片面统一走 ui/CardSurface.kt（白面 + 4dp 阴影、12dp 圆角）。
        // 原先钉的是 Gray100 —— 那是「用底色当边界」的上一代写法：浅色下靠 5% 明度差硬造，
        // 深色下 Gray100 又正好等于 canvasSubtle（等于没换）。边界现在由阴影承担。
        val sectionBodyAll = row.substring(row.indexOf("fun SettingsSection("))
        assertTrue(
            "设置卡片必须用共用的卡片面 cardSurface()（白面 + 4dp 阴影、12dp 圆角）",
            sectionBodyAll.contains(".cardSurface()"),
        )
        assertFalse(
            "设置卡片又退回 Gray100 了：那是被 1.4.67 换掉的上一代底色边界写法",
            sectionBodyAll.contains(".background(Primer.Gray100)"),
        )
        // 2026-11 真机反馈「取消掉黑边」：浅色下 BorderEmphasis 是纯黑 1dp，
        // 七张卡一起框出来的是一张线框图。卡片不再描边，分隔线也降成 40% 软线。
        val sectionBody = row.substring(row.indexOf("fun SettingsSection(")).substringBefore("\n}\n")
        assertFalse(
            "设置卡片又画了描边：浅色下 BorderEmphasis 是纯黑，七张卡会读成线框图",
            sectionBody.contains(".border("),
        )
        // 1.4.67：分隔线从 `BorderEmphasis × 40%` 换成 `Primer.Border` —— 与首页 TodoDivider 同一条
        // 真机结论：纯黑 40% 压灰底刚好，压到白面上就成了整页对比度最高的东西。
        assertTrue(
            "组内分隔线要用描边色（Primer.Border），不再用纯黑 40%",
            row.contains("background(Primer.Border)"),
        )
    }

    @Test
    fun `行首图标与右端的值按整行垂直居中`() {
        val row = source(rowComponentsPath)
        // 图标与文字被**内层 Row** 包住的两个行型（NavRow / DisabledNavRow）。
        // 内层 Row 用默认的 Top 对齐时：图标与右端的值停在行顶，名称却被 SettingsText 的
        // 11dp 上内边距压下去 —— 行越高偏得越狠（三行说明的「通知」行里图标贴在卡片左上角）。
        // 原型是 `.row { align-items: center }`（`design/settings-redesign/style.css` §4.2），
        // 所以这条是「实现别退回顶对齐」的钉子，而不是新增的样式选择。
        listOf("fun NavRow(", "fun DisabledNavRow(").forEach { fn ->
            val body = row.substring(row.indexOf(fn)).substringBefore("\n}\n")
            assertTrue(
                "$fn 里的内层 Row 必须垂直居中（verticalAlignment = Alignment.CenterVertically）",
                body.contains("verticalAlignment = Alignment.CenterVertically"),
            )
        }
    }

    @Test
    fun `值负责省略而不是挤名称`() {
        val row = source(rowComponentsPath)
        val text = row.substring(row.indexOf("private fun RowScope.SettingsText("))
        assertTrue(
            "名称要有自己的权重盒（份额一人一半）；内边距由密度给（d.textPadV）",
            text.contains("Modifier.weight(1f).padding(vertical = d.textPadV)"),
        )
        assertTrue("值要有独立的权重盒并右对齐", text.contains("contentAlignment = Alignment.CenterEnd"))
        assertTrue("值要能省略", text.contains("TextOverflow.Ellipsis"))
        // 份额必须**吃掉**（`fill` 默认 true）。写成 `fill = false` 时名称块缩到文字宽度，
        // 值列停在文字后面、不贴右 —— 见 ⑬ `设置树里没有缩到文字宽度的主轴盒子`
        assertFalse(
            "名称的权重盒不许缩到文字宽度（fill = false）",
            text.contains("Column(Modifier.weight(1f, fill = false)"),
        )
    }

    // ── ⑨ 控件选型（§5.2） ─────────────────────────────────────────────

    // ── ⑨′ 主题页（2026-11：外观类收拢进单独一页） ──────────────────────

    @Test
    fun `主题三档在主题页_主页只留入口`() {
        val theme = source(themePagePath)
        assertTrue("主题页的三档要取自 ThemeMode.entries", theme.contains("ThemeMode.entries"))
        assertTrue("三档必须并排可见（ModeOptionRow / 单选），不许循环手势", theme.contains("ModeOptionRow("))
        assertFalse(
            "主题页又出现了 ThemeRuntime.cycle：循环手势看不到全部档位、误点无法撤销（规范 §5.2）",
            theme.contains("ThemeRuntime.cycle"),
        )

        val region = settingsHomeRegion()
        assertFalse("主页又出现了三档分段：主题已收进主题页（规范 §十七）", region.contains("ThemeMode.entries"))
        assertFalse("主页又出现了 ChoiceRow：外观类不在主页就地展开", region.contains("ChoiceRow("))
        assertTrue("主页的「主题」必须是入口行（点击进主题页）", region.contains("onOpenTheme"))
        assertTrue("入口行要带当前档位（值列与主题页同源）", region.contains("value = stringResource(themeMode.labelRes)"))
    }

    @Test
    fun `悬浮玻璃导航栏开关只在主题页`() {
        val theme = source(themePagePath)
        assertTrue(
            "主题页必须把悬浮玻璃导航栏作为选项（外观类设置只有这一个落点）",
            theme.contains("label_glass_navigation") && theme.contains("SwitchRow("),
        )
        assertTrue("开关要写进 SettingsKeys 的运行时（否则主界面不跟着切）", theme.contains("GlassNavigationRuntime.set"))

        // 说明压成一行（2026-11 决策）：独占整行宽 + 单行上限，且**随开关状态换文案**
        // （关：现在是什么；开：已经发生什么）。360dp 屏上跟着名称走的文本列只有约 210dp，
        // 超过 22 个字就放不下 —— 换成整行宽（约 272dp）后 12sp 也够。
        assertTrue("说明必须限制成一行（subMaxLines = 1）", theme.contains("subMaxLines = 1"))
        assertTrue("说明要独占一行占满行宽（subFullWidth = true）", theme.contains("subFullWidth = true"))
        assertTrue("说明要随开关状态切换（if (glassNavigation)）", theme.contains("if (glassNavigation)"))
        val strings = source("src/main/res/values/strings.xml")
        listOf("note_glass_navigation_off", "note_glass_navigation_on").forEach { key ->
            val note = Regex("""<string name="$key">([^<]*)</string>""").find(strings)
            assertTrue("找不到 $key 文案", note != null)
            assertTrue(
                "$key 不能超过 22 个字（272dp 文本区在 12sp 下的上限）",
                note!!.groupValues[1].length <= 22,
            )
        }

        val region = settingsHomeRegion()
        assertFalse(
            "设置主页又出现了玻璃导航栏开关：外观类开关只在主题页（规范 §十七），主页只留入口",
            region.contains("label_glass_navigation"),
        )
        assertFalse("设置主页不再直接读玻璃导航偏好", region.contains("glassNavigationEnabled"))
    }

    // ── ⑩ 禁用行给出路（§6.3） ─────────────────────────────────────────

    @Test
    fun `本地仓库是判定式出现_非本地模式整行隐藏`() {
        val region = settingsHomeRegion()
        assertTrue(
            "本地仓库行必须由提交模式判定（2026-11-27 决策三：本地管理模式才渲染）",
            region.contains("if (mode == CommitMode.LOCAL_REPO)"),
        )
        assertTrue("本地管理模式下仍然要能进本地仓库页", region.contains("onOpenLocalRepo"))
        assertFalse(
            "非本地管理模式下不许再渲染禁用行或其「去设置」出路（决策三：整行隐藏，不是置灰）",
            region.contains("DisabledNavRow(") || region.contains("note_local_repo_requires_git_mode"),
        )
    }

    @Test
    fun `本地仓库快捷入口挂在提交模式页第三档行尾`() {
        val page = source("src/main/java/com/branchbase/ui/profile/CommitModeScreen.kt")
        assertTrue("第三档行尾要挂 LocalRepoShortcut", page.contains("LocalRepoShortcut("))
        assertTrue(
            "出现条件必须走纯函数 showLocalRepoShortcut（决策二的三条口径只在那一处）",
            page.contains("showLocalRepoShortcut(mode, localRepoCount)"),
        )
        assertTrue(
            "按钮要挂在第三档（LOCAL_REPO）的行尾槽上，不许自己另起一行",
            page.contains("trailing = if (m == CommitMode.LOCAL_REPO && showLocalRepo)"),
        )
        assertTrue("按钮点击要进本地仓库页", page.contains("onOpenLocalRepo"))
    }

    @Test
    fun `Git 代理入口搬进提交模式抽屉_设置主页不再有网络组`() {
        val region = settingsHomeRegion()
        assertFalse("设置主页不许再有 Git 代理行（2026-11-27 决策四）", region.contains("nav_git_proxy"))
        assertFalse("主页的「网络」组已下线", region.contains("label_network_group"))
        val page = source("src/main/java/com/branchbase/ui/profile/CommitModeScreen.kt")
        assertTrue("Git 代理入口要在提交模式抽屉里", page.contains("nav_git_proxy"))
        assertTrue("抽屉里的代理值要脱敏后显示", page.contains("displayGitProxy(gitProxy(context))"))
        assertTrue("代理行要能进二级页", page.contains("onOpenGitProxy()"))
        // 2026-11-27 真机反馈「感官上奇怪」：行内说明列只有半行宽，长说明会被挤成
        // 十来个字一行、连排十行 —— 释义留在 GitProxyScreen，入口行只放名称与值。
        // 扫去注释后的源码：这条规则本身写在 CommitModeScreen 的注释里，
        // 扫原文会把「把坑写下来」判红（假红比没有检查更坏）
        assertFalse(
            "抽屉里的代理行不许再塞长说明（半行宽会把 60 来个字挤成十几行）",
            stripComments(page).contains("note_proxy_scope_detail"),
        )
    }

    @Test
    fun `禁用行的原因不跟着降到看不见`() {
        val row = source(rowComponentsPath)
        val block = row.substring(row.indexOf("fun DisabledNavRow("))
        // 说明用 TextSecondary（不降 alpha），只有名称与图标降级
        assertTrue("禁用行的原因要用 TextSecondary 保持可读", block.contains("color = Primer.TextSecondary"))
    }

    /**
     * 2026-11 决策反转：主页**不再**渲染账号身份（头像 / 登录名 / 服务器 / 状态胶囊）。
     *
     * 原先这条钉子要求「必须把 avatarUrl 传给 AccountRow」—— 锚的是账户卡这个形态。
     * 主页压密后账户组只剩一行「账号管理」入口：身份与多账号切换都在二级页里回答，
     * 主页那张卡只能回答「我是谁」，回答不了「怎么换」。钉子跟着钉新的形态。
     */
    @Test
    fun `设置主页不再渲染账号身份_只留账号管理入口`() {
        val region = settingsRegion()
        assertFalse(
            "设置主页又出现了 AccountRow / 头像：身份信息已收进账号管理页",
            region.contains("AccountRow(") || region.contains("avatar = account"),
        )
        assertTrue(
            "设置主页的账户组必须保留「账号管理」入口（图标 + 名称，不带身份）",
            region.contains("nav_accounts_manage"),
        )
        assertTrue("入口图标要匹配语义", region.contains("Icons.Filled.Person"))
    }

    // ── ⑫ 首帧绘制量与组合期读盘（2026-09-22 设置页首帧绘制归因） ──────────

    /**
     * 「进入设置页」是慢帧里**绘制段**最集中的一处：22 条慢帧、绘制累计 893ms，其中 16 条
     * 「进入那一刻的首帧」就占了 734.6ms（单帧 22~82ms，均值 45.9ms/次）；同期的等待段只有
     * 33ms —— 不是主线程被占，是这一帧真在录大量绘制命令。
     *
     * 那些毫秒里，`Modifier.alpha` 与 `Modifier.clip` 各要**新建一层 RenderNode**
     * （`AlphaKt` / `ClipKt` 都直接落到 `graphicsLayer`）。钉两条机械可判定的：
     *
     * 1. 禁用态的淡出乘进颜色（`.copy(alpha = …)`），不许挂整层 `Modifier.alpha`；
     * 2. **纯装饰**的圆角交给 `background(color, shape)`（走 outline、不建层）——
     *    状态胶囊是全树里唯一「有圆角背景、但没有 clickable」的地方，`clip` 在那里纯属白建层。
     *
     * ⚠️ 边界：带水波纹的行**必须**留着 `clip`（它还负责把水波纹裁进圆角），
     * 所以第 2 条只钉胶囊这一处，不是「全树不许出现 clip」。
     */
    @Test
    fun `禁用行的半透明乘进颜色而不是整层 alpha`() {
        settingsTree.forEach { path ->
            assertFalse(
                "$path 里出现了 Modifier.alpha —— 它是 graphicsLayer，只为了调淡实色文字/图标不值一层 RenderNode",
                source(path).contains("Modifier.alpha("),
            )
        }
        assertTrue(
            "禁用态的半透明要乘进颜色（DisabledAlpha）",
            source(rowComponentsPath).contains(".copy(alpha = DisabledAlpha)"),
        )
    }

    @Test
    fun `状态胶囊的圆角走 background 而不是 clip`() {
        val row = source(rowComponentsPath)
        assertFalse(
            "状态胶囊没有 clickable，不需要 clip 把水波纹裁进圆角 —— 那层 RenderNode 是白建的",
            row.contains(".clip(RoundedCornerShape(999.dp))"),
        )
        assertTrue(
            "圆角应该交给 background(color, shape)（outline 绘制，不建图层）",
            row.contains(".background(bg, RoundedCornerShape(999.dp))"),
        )
    }

    /**
     * 组合期读 SharedPreferences = 每次重组都读一遍（首次读盘之后虽然命中内存，
     * 但设置页首帧那一帧里整棵树要重组好几次）。这两处原先都是裸调：
     * `commitMode(context)` 直接写在函数体里、`gitProxy(context)` 写在 `item {}` 的组合体里
     * （后者每次这一项被滚回来都会再读一次）。同文件里 `frameWatchEnabled` / `TranslateSettings.read`
     * 本来就在 `remember` 里 —— 这条钉子只是把已经写下来的约定补齐。
     */
    @Test
    fun `设置页的组合期读盘都要进 remember`() {
        val page = source(settingsPagePath)
        assertTrue("提交模式要进 remember", page.contains("remember { commitMode(context) }"))
        assertFalse("不许裸调 commitMode（每次重组读一次 prefs）", page.contains("val mode = commitMode(context)"))
        // 2026-11-27：Git 代理行搬进「提交模式」抽屉，代理值跟着搬家 —— 仍然要求「一次读盘、进 remember」
        val commitModePage = source("src/main/java/com/branchbase/ui/profile/CommitModeScreen.kt")
        assertTrue(
            "提交模式页的代理值要进 remember",
            commitModePage.contains("remember {\n        displayGitProxy(gitProxy(context))"),
        )
        assertFalse(
            "不许裸调代理值（每次重组读一次 prefs）",
            commitModePage.contains("val proxyValue = displayGitProxy("),
        )
        assertFalse("设置页不再读代理值（网络组已下线）", page.contains("displayGitProxy("))
    }

    // ── ⑬ 行尾控件贴右（2026-09-24 真机截图圈选处） ────────────────────────

    /**
     * 圈选处的形态：同一个 `›` 在设置页每行停在不同位置 —— 「日志」紧跟名称、「语言」跟在值后、
     * 「Git 代理」因为说明折行才碰巧贴右；账户卡的状态胶囊与 `›` 也停在登录名后面。
     *
     * 成因是**主轴盒子写了 `fill = false`**：`Row` 的权重盒在 `fill = false` 时缩到**文字宽度**，
     * 没花掉的份额变成行尾的空白（`Arrangement.Start` 把空白留在最后），
     * 于是排在它后面的值列与行尾控件（`›` / `Switch` / 分段控件 / 胶囊 / 「去设置」）全部
     * 停在文字后面，而不是落在行的 16dp 内边距上。
     *
     * 规格要的是原型 `.row .txt { flex: 1 1 auto }` 的语义（`design/settings-redesign/style.css`）：
     * 中间那块**吃掉**份额，尾控件由 `SettingsRowShell` 顶到行尾。名称/值各自的**上限没变**
     * （还是一人一半），所以这条不是新的排版选择，而是「行尾那截空白归谁」。
     */
    @Test
    fun `设置树里没有缩到文字宽度的主轴盒子`() {
        // 扫**去注释**的源码：这条规则本身写在 SettingsRow.kt 的注释里（教后来者别踩），
        // 扫原文会把那句注释判红 —— 假红比没有检查更坏
        mapOf(
            rowComponentsPath to stripComments(source(rowComponentsPath)),
            settingsPagePath to stripComments(settingsRegion()),
        ).forEach { (path, src) ->
            assertFalse(
                "$path 里出现了 `weight(…, fill = false)`：主轴盒子会缩到文字宽度，" +
                    "空白留在行尾 —— 值列与行尾控件停在文字后面，每行一个位置（规范 §4.2）",
                Regex("""weight\([^)]*fill\s*=\s*false""").containsMatchIn(src),
            )
        }
    }

    @Test
    fun `名称列吃掉份额_值列右对齐在文本块末尾`() {
        val row = source(rowComponentsPath)
        assertTrue(
            "名称列的权重盒必须吃掉份额（`Modifier.weight(1f)`）：只缩到文字宽度时，" +
                "值列的右边缘就落在文字后面，而不是文本块末尾（规范 §4.2）",
            row.contains("Column(Modifier.weight(1f).padding(vertical = d.textPadV))"),
        )
        assertTrue(
            "值列要在自己的权重盒里右对齐（右边缘 ＝ 文本块末尾 ＝ 行尾控件左侧）",
            row.contains("Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd)"),
        )
    }

    /**
     * 行尾控件的落点由 [SettingsRowShell] 唯一决定（16dp 内边距 + 主轴盒子吃份额）。
     * 某个行型自己起一个 `Row` 放 `›`、或页面自制行，都会把「贴右」重新变成「跟着文字跑」。
     */
    @Test
    fun `每种行型的行尾控件都走同一个骨架`() {
        // 去注释之后再找函数：`SettingsRow.kt` 的文档里大量提到这些行型的名字，
        // 而「名字 + 左括号」才是调用/定义（`ChoiceRow` 是泛型，签名是 `fun <T> ChoiceRow(`，
        // 写成 `fun ChoiceRow(` 会永远找不到 —— 那是测试假红，第一版就踩过）
        val row = stripComments(source(rowComponentsPath))
        listOf(
            "NavRow(", "SwitchRow(", "ChoiceRow(", "ActionRow(",
            "DangerRow(", "InfoRow(", "DisabledNavRow(",
        ).forEach { fn ->
            val at = row.indexOf(fn)
            assertTrue("找不到 $fn", at >= 0)
            val body = row.substring(at).substringBefore("\n}\n")
            assertTrue(
                "$fn 没有走 SettingsRowShell：行尾控件的落点就跑掉了（规范 §4.2）",
                body.contains("SettingsRowShell("),
            )
        }
    }

    // ── ⑭ 分组标题下线（2026-11-27 决策五） ───────────────────────────────

    /**
     * 设置主页的六张卡**都不画** 12sp 分组小标签。
     *
     * 规范 §4.4 允许「省掉标题、靠卡片边界分组」，但要求**整棵树统一** —— 一半带标签、
     * 一半不带正是它禁止的形态（1.4.61 之前「通知」是唯一没有标签的卡，另外五张都有）。
     * 现在组由卡片边界 + 组内分隔线 + 8dp 间距区分；五个分组名资源（`label_*_group`）
     * 随唯一调用点一并删除，不留死资源。
     *
     * ⚠️ 钉的只是**主页**这一棵：二级页（通知 / 主题 / 沉浸式翻译 / Git 代理 / 提交模式抽屉）
     * 照旧有组名 —— 那是「一页几组」的页内导航，与主页「入口索引」不是同一件事。
     */
    @Test
    fun `设置主页不画分组标题_组靠卡片边界分隔`() {
        val region = settingsHomeRegion()
        assertFalse(
            "主页又给某张卡加了分组标题：整棵树要么全有要么全无（规范 §4.4），" +
                "组现在靠卡片边界 + 组内分隔线区分",
            Regex("""SettingsSection\(\s*stringResource""").containsMatchIn(region),
        )
        val strings = source("src/main/res/values/strings.xml")
        listOf(
            "label_account_group", "label_appearance_group", "label_translation_group",
            "label_code_commit_group", "label_about_group",
        ).forEach { key ->
            assertFalse(
                "分组名资源 $key 已随唯一调用点删除（不留死资源）；要恢复分组标题就五张卡一起恢复",
                strings.contains("\"$key\""),
            )
        }
    }
}
