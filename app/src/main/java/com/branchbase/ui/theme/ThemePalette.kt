package com.branchbase.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 主题色板：**语义角色**，而不是「一串固定的颜色值」。
 *
 * ## 为什么必须这样拆（这是深色模式的真正前提）
 *
 * 改造前 `Primer` 是一个静态 object，所有颜色都是写死的 `val`，被 1254 处直接引用
 * （其中约 70% 是中性/语义色）。在那种结构下「加深色模式」只有两条路：
 * 逐个调用处写 `if (dark) A else B`（1254 处），或者把 `Primer` 换成能随主题切换的色板。
 * 这里选后者：所有既有 `Primer.XXX` 调用点**一行不改**，只是取值变成「当前主题的对应角色」。
 *
 * ## 角色划分规则（新增颜色时按这个表挑）
 *
 * | 角色 | 浅色 | 深色 | 用途 |
 * |------|------|------|------|
 * | [canvas] | 白 | #0D1117 | 页面底 |
 * | [canvasSubtle] | 白 | #161B22 | 导航栏 / 卡片 / 弹层底（靠它与 [canvas] 的差分层） |
 * | [textPrimary]/[textSecondary]/[textTertiary] | 深→浅灰 | 浅→中灰 | 正文 / 次要 / 说明 |
 * | [iconPrimary]/[iconSecondary] | 中灰 | 亮灰 | 图标 |
 * | [border] | #BFC1C9 | #30363D | 描边（**只做 stroke**） |
 * | [neutralFill]/[neutralFillStrong]/[neutralBorder] | 浅灰阶 | 深灰阶 | chip / 代码底 / 未选中底 |
 * | [emphasisFill] + [onEmphasis] | 深底 + 白字 | **浅底 + 深字** | 高强调胶囊（注意是成对翻转的） |
 * | [accent]/[link] | #0969DA | #1F6FEB / #2F81F7 | 填充用蓝 / 文字链接用蓝（分开！） |
 *
 * ## 为什么要区分 [accent] 与 [link]
 *
 * 浅色下同一个 `#0969DA` 既能当按钮填充、又能当链接文字。深色下这两件事的最优值不同：
 * 填充要够实（#1F6FEB），链接文字要够亮才读得清（#2F81F7）。改造前 `Blue500` 一色两用，
 * 直接翻转会出现「按钮正常、链接发暗」的问题。
 */
/** 代码高亮色板（搜索页代码结果 / 本地渲染）。深色下必须换成 GitHub dark 的语法色，否则深底上不可读。 */
@Immutable
data class CodePalette(
    val matchBg: Color,
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val function: Color,
    val number: Color,
    val lineNo: Color,
    val bg: Color,
    val border: Color,
)

/** 贡献图绿阶（Profile 动态页）。 */
@Immutable
data class ContributionPalette(
    val l0: Color,
    val l1: Color,
    val l2: Color,
    val l3: Color,
    val l4: Color,
)

@Immutable
data class PrimerPalette(
    // ── 表面 ──
    val canvas: Color,
    val canvasSubtle: Color,
    // ── 文字 ──
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    // ── 图标 ──
    val iconPrimary: Color,
    val iconSecondary: Color,
    // ── 描边 ──
    val border: Color,
    // ── 中性填充（浅色是浅灰、深色是深灰）──
    val neutralFill: Color,
    val neutralFillStrong: Color,
    val neutralBorder: Color,
    val neutralMuted: Color,
    // ── 中性文字（Gray500/600/700：次要文字与禁用态）──
    val neutralTextLight: Color,
    val neutralText: Color,
    val neutralTextStrong: Color,
    // ── 高强调填充（浅色=深底白字；深色=浅底深字，成对翻转）──
    val emphasisFill: Color,
    val emphasisOnFill: Color,
    // ── 品牌与状态 ──
    val accent: Color,
    val accentStrong: Color,
    val accentSoft: Color,
    val link: Color,
    val success: Color,
    val successSubtle: Color,
    val danger: Color,
    val dangerSubtle: Color,
    val warning: Color,
    val warningSubtle: Color,
    val done: Color,
    // ── 浅底彩块（chip / 横幅底）──
    // 这些原本散落在各页面里写死（25 处），是「未声明的第二套色板」：
    // 在浅色页面上很自然，放到深色页面上就是一块刺眼的亮斑。
    val successSurface: Color,
    val successSurfaceSoft: Color,
    val dangerSurface: Color,
    val dangerSurfaceSoft: Color,
    val infoSurface: Color,
    val infoSurfaceStrong: Color,
    val infoSurfaceSoft: Color,
    val selectedRow: Color,
    val warningSurface: Color,
    // ── 品牌文字色（比品牌主色深一档，浅色下用于小字标签）──
    val successText: Color,
    val successTextStrong: Color,
    val accentText: Color,
    val warningText: Color,
    val warningTextStrong: Color,
    /** 弹层遮罩 / 深色浮层底（Snackbar 等）。 */
    val inverseSurface: Color,
    val inverseOnSurface: Color,
    // ── 专项色板 ──
    val code: CodePalette,
    val contribution: ContributionPalette,
)

/**
 * 浅色板：**与改造前的取值逐位一致**。
 *
 * 这是刻意的约束 —— 引入主题架构时不能顺手改浅色外观，否则「深浅对比」的结论就不可信了。
 */
val LightPrimerPalette = PrimerPalette(
    canvas = Color(0xFFFFFFFF),
    canvasSubtle = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF050505),
    textSecondary = Color(0xFF41434E),
    textTertiary = Color(0xFF6A6D7C),
    iconPrimary = Color(0xFF525560),
    iconSecondary = Color(0xFF9194A1),
    border = Color(0xFFBFC1C9),
    neutralFill = Color(0xFFF7F7F9),
    neutralFillStrong = Color(0xFFEFF0F5),
    neutralBorder = Color(0xFFE3E4E8),
    neutralMuted = Color(0xFFBFC1C9),
    neutralTextLight = Color(0xFF6A6D7C),
    neutralText = Color(0xFF525560),
    neutralTextStrong = Color(0xFF41434E),
    emphasisFill = Color(0xFF17181C),
    emphasisOnFill = Color(0xFFFFFFFF),
    accent = Color(0xFF0969DA),
    accentStrong = Color(0xFF005CC5),
    accentSoft = Color(0xFF2188FF),
    link = Color(0xFF0969DA),
    success = Color(0xFF28A745),
    successSubtle = Color(0xFFDCFFE4),
    danger = Color(0xFFD73A49),
    dangerSubtle = Color(0xFFFFDCE0),
    warning = Color(0xFFF66A0A),
    warningSubtle = Color(0xFFFFF1E0),
    done = Color(0xFF6F42C1),
    successSurface = Color(0xFFF0FFF4),
    successSurfaceSoft = Color(0xFFEAF9F0),
    dangerSurface = Color(0xFFFFEBEC),
    dangerSurfaceSoft = Color(0xFFFFF1F2),
    infoSurface = Color(0xFFF0F7FF),
    infoSurfaceStrong = Color(0xFFE3F0FF),
    infoSurfaceSoft = Color(0xFFE6F1FF),
    selectedRow = Color(0xFFF6FAFF),
    warningSurface = Color(0xFFFFF1E0),
    successText = Color(0xFF1A7F37),
    successTextStrong = Color(0xFF176F2C),
    accentText = Color(0xFF0550AE),
    warningText = Color(0xFF9A6700),
    warningTextStrong = Color(0xFF7D4C00),
    inverseSurface = Color(0xFF17181C),
    inverseOnSurface = Color(0xFFFFFFFF),
    code = CodePalette(
        matchBg = Color(0xFFFFF8C5),
        keyword = Color(0xFFCF222E),
        string = Color(0xFF0A3069),
        comment = Color(0xFF6A737D),
        function = Color(0xFF8250DF),
        number = Color(0xFF0550AE),
        lineNo = Color(0xFFC0C6CC),
        bg = Color(0xFFF6F8FA),
        border = Color(0xFFD0D7DE),
    ),
    contribution = ContributionPalette(
        l0 = Color(0xFFEBEDF0),
        l1 = Color(0xFF9BE9A8),
        l2 = Color(0xFF40C463),
        l3 = Color(0xFF30A14E),
        l4 = Color(0xFF216E39),
    ),
)

/**
 * 深色板（对齐 GitHub Primer dark）。
 *
 * 需要强调的两处：
 * - [canvasSubtle] 与 [canvas] 在深色下**不同**（#161B22 vs #0D1117）：浅色下两者都是纯白，
 *   所以导航栏/卡片原本没有层次；深色下靠这层差自然分层，反而比浅色更清楚；
 * - [emphasisFill] 是浅色底 + 深字：浅色模式里「深底白字」的高强调胶囊，
 *   在深色模式里必须反过来（浅底深字），否则在深底上完全看不出「被选中」。
 */
val DarkPrimerPalette = PrimerPalette(
    canvas = Color(0xFF0D1117),
    canvasSubtle = Color(0xFF161B22),
    textPrimary = Color(0xFFE6EDF3),
    textSecondary = Color(0xFFC9D1D9),
    textTertiary = Color(0xFF8B949E),
    iconPrimary = Color(0xFFB1BAC4),
    iconSecondary = Color(0xFF8B949E),
    border = Color(0xFF30363D),
    neutralFill = Color(0xFF161B22),
    neutralFillStrong = Color(0xFF21262D),
    neutralBorder = Color(0xFF30363D),
    neutralMuted = Color(0xFF484F58),
    neutralTextLight = Color(0xFF8B949E),
    neutralText = Color(0xFFB1BAC4),
    neutralTextStrong = Color(0xFFC9D1D9),
    emphasisFill = Color(0xFFE6EDF3),
    emphasisOnFill = Color(0xFF0D1117),
    accent = Color(0xFF1F6FEB),
    accentStrong = Color(0xFF388BFD),
    accentSoft = Color(0xFF2F81F7),
    link = Color(0xFF2F81F7),
    success = Color(0xFF3FB950),
    successSubtle = Color(0xFF12261E),
    danger = Color(0xFFF85149),
    dangerSubtle = Color(0xFF2D1418),
    warning = Color(0xFFD29922),
    warningSubtle = Color(0xFF2A2113),
    done = Color(0xFFA371F7),
    successSurface = Color(0xFF12261E),
    successSurfaceSoft = Color(0xFF0F2018),
    dangerSurface = Color(0xFF2D1418),
    dangerSurfaceSoft = Color(0xFF2A1216),
    infoSurface = Color(0xFF0D2440),
    infoSurfaceStrong = Color(0xFF12304F),
    infoSurfaceSoft = Color(0xFF10283F),
    selectedRow = Color(0xFF161B22),
    warningSurface = Color(0xFF2A2113),
    successText = Color(0xFF3FB950),
    successTextStrong = Color(0xFF3FB950),
    accentText = Color(0xFF79C0FF),
    warningText = Color(0xFFD29922),
    warningTextStrong = Color(0xFFD29922),
    inverseSurface = Color(0xFFE6EDF3),
    inverseOnSurface = Color(0xFF0D1117),
    // GitHub dark 的语法色与「空贡献格」灰：浅色那套放在深底上不是刺眼就是看不见
    code = CodePalette(
        matchBg = Color(0xFF3B2300),
        keyword = Color(0xFFFF7B72),
        string = Color(0xFFA5D6FF),
        comment = Color(0xFF8B949E),
        function = Color(0xFFD2A8FF),
        number = Color(0xFF79C0FF),
        lineNo = Color(0xFF6E7681),
        bg = Color(0xFF161B22),
        border = Color(0xFF30363D),
    ),
    contribution = ContributionPalette(
        l0 = Color(0xFF161B22),
        l1 = Color(0xFF0E4429),
        l2 = Color(0xFF006D32),
        l3 = Color(0xFF26A641),
        l4 = Color(0xFF39D353),
    ),
)

/**
 * 当前主题色板。
 *
 * 用 `staticCompositionLocalOf`：主题切换是**低频、全局**事件，不需要细粒度订阅；
 * 静态局部值在切换时整体重组，代价可接受且读取开销最低（1254 处读取）。
 */
val LocalPrimerPalette = staticCompositionLocalOf { LightPrimerPalette }
