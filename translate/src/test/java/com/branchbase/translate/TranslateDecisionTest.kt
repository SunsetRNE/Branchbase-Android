package com.branchbase.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 判定引擎单测（1.0.89）：**「要不要翻」→「翻哪一部分」**。
 *
 * 三条规则各有用例，重点钉住两件容易做错的事：
 * 1. **一致即跳过**：中文段落不该被再翻一遍（脏结果 + 白花额度），
 *    零碎外语（`CI`、`v1.2.3`、`a`）也不构成「需要翻的内容」；
 * 2. **混排段落只翻片段**：`npm run dev` 要整块送出去（逐词拆开发出去会得到
 *    「npm 运行 开发」这种读不通的东西），而行内代码 / URL 里的字母**不算**内容
 *    —— 判定在占位符保护视角下做，否则 `https` 会被当成一个外语片段。
 */
class TranslateDecisionTest {

    private fun decide(
        text: String,
        target: String = LANG_ZH,
        rules: PageRules = PageRules.DEFAULT,
    ): TranslateDecision = TranslateDecisionEngine.decide(text, target, rules)

    private fun parts(text: String, target: String = LANG_ZH, rules: PageRules = PageRules.DEFAULT): List<String> =
        (decide(text, target, rules) as TranslateDecision.Matched).parts

    private fun assertSkip(
        text: String,
        reason: DecisionReason,
        target: String = LANG_ZH,
        rules: PageRules = PageRules.DEFAULT,
    ) {
        val d = decide(text, target, rules)
        assertTrue("应当跳过：$text（实际 $d）", d is TranslateDecision.Skip)
        assertEquals("跳过原因：$text", reason, d.reason)
    }

    // ── 规则 ①：一致即跳过 ──

    @Test
    fun `已经是目标文字的段落跳过`() {
        assertSkip("这是一段中文说明，不需要翻译。", DecisionReason.ALREADY_TARGET)
        assertSkip("This is already English.", DecisionReason.ALREADY_TARGET, LANG_EN)
    }

    @Test
    fun `零碎外语不构成需要翻的内容`() {
        // `CI`（2 个字母）够不上 latinRun=3：翻出来只会更差，而且是白花额度
        assertSkip("这是一段中文说明，里面有 CI 字样。", DecisionReason.ALREADY_TARGET)
        assertSkip("用 PR 提交，版本号写在标题里。", DecisionReason.ALREADY_TARGET)
        assertSkip("中文 a 字母", DecisionReason.ALREADY_TARGET)
    }

    @Test
    fun `行内代码与链接不算需要翻的内容`() {
        // 判定在**保护视角**下做：`npm install` 与 URL 在判定眼里是中性字符
        assertSkip("使用 `npm install` 安装依赖，然后重新打开项目。", DecisionReason.ALREADY_TARGET)
        assertSkip("详见 https://example.com/docs 里的说明。", DecisionReason.ALREADY_TARGET)
    }

    @Test
    fun `空白与跳过规则各有原因码`() {
        assertSkip("\u200B\uFEFF", DecisionReason.EMPTY)
        assertSkip("v1.2.3", DecisionReason.SKIP_RULE)
        assertSkip("English | 中文", DecisionReason.SKIP_RULE)
    }

    // ── 规则 ②：外语为主 → 整段翻 ──

    @Test
    fun `外语段落整段翻`() {
        assertTrue(decide("This is a release note for the project.") is TranslateDecision.Whole)
    }

    @Test
    fun `外语为主的中英混排仍然整段翻`() {
        // 英文段落里夹一句中文引文：只翻片段会得到「半句译文 + 半句原文」，读不通
        assertTrue(
            decide("The following is Chinese: 你好世界, which means hello world.") is TranslateDecision.Whole,
        )
    }

    @Test
    fun `目标英文时方向相反_中文段落整段翻`() {
        assertTrue(decide("这是一段中文说明。", LANG_EN) is TranslateDecision.Whole)
        assertTrue(decide("This is already English.", LANG_EN) is TranslateDecision.Skip)
    }

    @Test
    fun `目标英文时英文段落里的中文片段走匹配`() {
        assertEquals(listOf("构建脚本"), parts("Run the 构建脚本 before you push your branch.", LANG_EN))
    }

    // ── 规则 ③：目标文字为主 → 匹配性翻译 ──

    @Test
    fun `中文段落里的外语片段单独翻`() {
        assertEquals(
            listOf("pnpm workspace"),
            parts("项目使用 pnpm workspace 管理依赖，发布前请先跑一遍完整测试。"),
        )
    }

    @Test
    fun `片段吸收中间的空格_不逐词拆开`() {
        // 逐词送翻会得到三份互相不知道上下文的译文，拼起来是「npm 运行 开发」
        assertEquals(listOf("npm run dev"), parts("安装完成后运行 npm run dev 启动开发服务器。"))
    }

    @Test
    fun `片段按出现顺序去重`() {
        assertEquals(
            listOf("pull request"),
            parts("先跑一遍完整的 pull request 检查流程，确认没有遗漏之后再合并分支。"),
        )
    }

    @Test
    fun `多个片段各成一对`() {
        assertEquals(
            listOf("Docker Desktop", "Windows Subsystem"),
            parts("在 Docker Desktop 里启用 Windows Subsystem 之后再重启，其余内容都是中文说明文字，请务必确认版本一致。"),
        )
    }

    @Test
    fun `片段太短不值得单独翻`() {
        // 默认 matchMinLen=4：三字母的 `npm`、`CLI` 放过（它们更像专有名词）
        assertSkip("本项目的 CLI 支持三种模式，其余内容都是中文说明文字。", DecisionReason.ALREADY_TARGET)
    }

    @Test
    fun `片段阈值可以换掉而不用改判定代码`() {
        val rules = PageRules.DEFAULT.copy(matchMinLen = 3)
        assertEquals(listOf("CLI"), parts("本项目的 CLI 支持三种模式，其余内容都是中文说明文字。", rules = rules))
    }

    @Test
    fun `片段太多时回退整段翻`() {
        // 外语片段超过 maxMatchParts，说明这段其实以外语为主 —— 整段翻更通顺
        val text = "说明文字 alpha beta 与 gamma delta 以及 epsilon zeta 都要按顺序处理，其余内容都是中文说明文字，请务必确认无误。"
        assertEquals(3, parts(text).size)
        assertTrue(decide(text, rules = PageRules.DEFAULT.copy(maxMatchParts = 2)) is TranslateDecision.Whole)
    }

    // ── 使用规则（设置里的「中英混排」） ──

    @Test
    fun `混排规则可以切成整段翻`() {
        val rules = PageRules.DEFAULT.copy(matchPolicy = PageRules.MATCH_POLICY_WHOLE)
        val text = "项目使用 pnpm workspace 管理依赖，发布前请先跑一遍完整测试。"
        assertTrue(decide(text, rules = rules) is TranslateDecision.Whole)
    }

    @Test
    fun `混排规则可以切成不翻`() {
        val rules = PageRules.DEFAULT.copy(matchPolicy = PageRules.MATCH_POLICY_SKIP)
        val text = "项目使用 pnpm workspace 管理依赖，发布前请先跑完完整测试。"
        assertSkip(text, DecisionReason.POLICY_SKIPPED, rules = rules)
        // 纯外语段落不受这条规则影响：它走的还是「整段翻」
        assertTrue(decide("This is a release note for the project.", rules = rules) is TranslateDecision.Whole)
    }

    @Test
    fun `认不出来的混排规则回落到默认`() {
        assertEquals(PageRules.MATCH_POLICY_MATCH, PageRules.matchPolicyOf("天知道"))
        assertEquals(PageRules.MATCH_POLICY_SKIP, PageRules.matchPolicyOf(PageRules.MATCH_POLICY_SKIP))
    }

    // ── 一致判定（译后校验） ──

    @Test
    fun `一致判定忽略大小写_全角标点与首尾标点`() {
        assertTrue(TranslateDecisionEngine.isIdentical("Hello world.", "hello world"))
        assertTrue(TranslateDecisionEngine.isIdentical("你好，世界！", "你好,世界"))
        assertTrue(TranslateDecisionEngine.isIdentical("Hello，world！", "Hello, world!"))
        assertFalse(TranslateDecisionEngine.isIdentical("Hello world", "你好世界"))
        assertFalse(TranslateDecisionEngine.isIdentical("Hello world", "你好，世界"))
    }

    @Test
    fun `一致判定不把括号包裹当成翻译`() {
        // 服务端有时把原文原样包一层引号/括号还回来：那不是译文，插进页面只会重复
        assertTrue(TranslateDecisionEngine.isIdentical("Hello world", "「Hello world」"))
        assertFalse(TranslateDecisionEngine.isIdentical("Hello world", "你好，世界"))
    }

    // ── 与旧入口的一致性 ──

    @Test
    fun `needsTranslation 是判定结论的布尔形态`() {
        listOf(
            "This is a release note for the project.",
            "这是一段中文说明。",
            "项目使用 pnpm workspace 管理依赖，其余内容都是中文。",
            "v1.2.3",
            "English | 中文",
            "Hi",
        ).forEach { text ->
            listOf(LANG_ZH, LANG_EN).forEach { target ->
                assertEquals(
                    "needsTranslation 与 decide 必须一致：$text / $target",
                    decide(text, target) !is TranslateDecision.Skip,
                    TranslateTextPolicy.needsTranslation(text, target),
                )
            }
        }
    }

    @Test
    fun `混排段落算候选_旧口径会把它漏掉`() {
        // 旧口径（汉字占比 > 一半就跳过）会把这段判成「不用翻」，
        // 于是段内的 `pnpm workspace` 永远不会被翻译 —— 这正是 1.0.89 要修的事
        val text = "项目使用 pnpm workspace 管理依赖，其余内容都是中文。"
        assertTrue(TranslateTextPolicy.needsTranslation(text, LANG_ZH))
    }
}
