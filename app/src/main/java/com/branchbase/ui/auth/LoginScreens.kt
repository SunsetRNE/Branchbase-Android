package com.branchbase.ui.auth

import androidx.compose.foundation.LocalIndication
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.R
import com.branchbase.ui.theme.color
import com.branchbase.ui.theme.TintRole
import com.branchbase.core.RustBridge
import com.branchbase.ui.theme.AppIcon
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.runtime.collectAsState
import com.branchbase.ui.theme.ThemeMode
import com.branchbase.ui.theme.ThemeRuntime
import com.branchbase.ui.theme.AnimatedStateIcon
import com.branchbase.ui.theme.iconTap
import com.branchbase.ui.theme.rememberPressFeedback
import com.branchbase.ui.theme.Primer
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import com.branchbase.ui.navigation.BackDisposition
import com.branchbase.ui.profile.SubPageHeader
import com.branchbase.ui.theme.AsyncActionLabel
import com.branchbase.ui.theme.ElementMotion
import com.branchbase.ui.theme.rememberPulse
import com.branchbase.ui.theme.revealEnter
import com.branchbase.ui.theme.revealExit
import com.branchbase.ui.theme.selectionColor

/**
 * 登录流程 Compose 界面（骨架）。
 *
 * 设计基准：360dp × 792dp，状态栏 40dp / 手势条 16dp 安全区。
 * 间距规范：水平内边距 22dp（HorizontalPadding）、按钮圆角 8dp（BtnShape）。
 */

private val BtnShape = RoundedCornerShape(8.dp)
private val HorizontalPadding = 22.dp

/**
 * 主按钮（全宽、主蓝）。
 *
 * 两种「需要异步行为」的状态由参数表达，调用方不再各写一份 `if (busy)`：
 * - [busy]：已经发起、还没结果。按钮**同时**不可再点（`enabled = enabled && !busy`），
 *   标签走 [AsyncActionLabel]（旧标签先退、转圈与「校验中…」再进）——
 *   老写法是 `if (busy) {...} else {...}`，文案当帧换掉，字宽一变整块内容就跳一下；
 * - [enabled]：连提交条件都不满足（如密钥为空）。与 [busy] 分开，是因为两者的
 *   语义不同：一个是「你现在还不能点」，一个是「点了、正在处理」。
 *
 * 按下反馈走 [rememberPressFeedback]（120ms 缩到 0.94）—— 登录页两个入口是用户
 * 在这个 App 里按下的**第一批**按钮，只有水波纹会让「按到了」这件事读起来发虚。
 */
@Composable
private fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    busyText: String = stringResource(R.string.state_checking),
    /** 半宽按钮要收窄横向内边距，否则中文标签在 320dp 屏上会换行、被 48dp 定高裁掉。 */
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
) {
    val press = rememberPressFeedback()
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .graphicsLayer {
                scaleX = press.scale.value
                scaleY = press.scale.value
            },
        shape = BtnShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Primer.Blue500,
            contentColor = Color.White,
            // 不可点时不要掉成灰色：那会被读成「坏了」。保留主色、压低不透明度，
            // 与「正在处理」是同一个视觉家族（见上面 busy 的说明）。
            disabledContainerColor = Primer.Blue500.copy(alpha = 0.38f),
            disabledContentColor = Color.White,
        ),
        interactionSource = press.interaction,
        contentPadding = contentPadding,
    ) {
        AsyncActionLabel(
            busy = busy,
            idle = { Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White) },
            busyContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(busyText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
            },
        )
    }
}

/** 次按钮（描边）。按下反馈 / 挂起态与 [PrimaryButton] 同一套规格。 */
@Composable
private fun OutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    busyText: String = stringResource(R.string.state_checking),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
) {
    val press = rememberPressFeedback()
    OutlinedButton(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .graphicsLayer {
                scaleX = press.scale.value
                scaleY = press.scale.value
            }
            .border(1.dp, Primer.Border, BtnShape),
        shape = BtnShape,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = Primer.TextSecondary,
            disabledContentColor = Primer.TextTertiary,
        ),
        interactionSource = press.interaction,
        contentPadding = contentPadding,
    ) {
        AsyncActionLabel(
            busy = busy,
            idle = { Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
            busyContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Primer.TextSecondary,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(busyText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            },
        )
    }
}

/**
 * 01 · 欢迎页：两种登录模式各一个入口。
 *
 * 两种模式的差别（是否要数字口令）对用户是**决定性**的，所以入口处各带一句定位说明，
 * 点进去还有各自的流程要点介绍页（[OAuthIntroScreen] / [KeyIntroScreen]）。
 *
 * ## 入场为什么要有动画
 *
 * 这是用户装完 App 看到的**第一屏**。此前它是一整块硬渲染（只有主题开关在右上角），
 * 而它后面那两页介绍页却做了逐条错峰入场 —— 第一屏是唯一没有任何引导感的一屏。
 * 现在按「品牌 → 入口① → 入口②」三段错峰出现（每段延迟 90ms，与介绍页同一节奏）：
 * 阅读顺序被动画带着走，用户先认出这是什么 App，再看两条路各自是什么。
 *
 * 三段都用 `AnimatedVisibility` + 只淡入与轻位移（**不用 expandVertically**）：
 * 展开会改变列高，而这一列靠 `weight(1f)` 居中，高度一动整屏就在入场时上下漂。
 */
@Composable
fun WelcomeScreen(
    onOAuthLogin: () -> Unit,
    onKeyLogin: () -> Unit,
) {
    // 首帧先以「未入场」组合，下一帧再翻成 true —— 直接写 true 的话首帧就已经是终态，
    // 动画不会播（这是 `AnimatedVisibility` 最常见的空转写法）。
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    Box(Modifier.fillMaxSize().background(Primer.BackgroundPrimary)) {
        // 主题开关放在**登录首页**：有些用户受不了整屏白色（也有人在强光下看不了深色），
        // 必须在进 App 之前就能改，而不是登录后再去设置里翻
        ThemeModeSwitch(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 8.dp, end = 12.dp),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(horizontal = HorizontalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))

            // 品牌段：图标 + 名称 + 定位（应用图标直接渲染系统当前展示的那枚）
            WelcomeBlock(entered, delayMs = 0) {
                AppIcon(size = 96.dp, shape = RoundedCornerShape(26.dp))
                Spacer(Modifier.height(22.dp))
                Text("Branchbase", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.app_tagline), fontSize = 14.sp, color = Primer.TextTertiary)
            }

            Spacer(Modifier.weight(1f))

            // ① 授权登录（OAuth）
            // 按钮下方原有两句定位说明（`login_oauth_hint` / `login_key_hint`）。
            // 2026-11-24 文案评估：首屏同时承担「选择」和「解释」两件事，两句小字在深色下
            // 更像噪音；两个入口的名字已经说清是什么，细节在点进去的「流程要点介绍」页里
            // （那里有完整说明 + 渲染动画）。所以**只隐藏、不删资源** —— 文案是否删除等评估结论。
            WelcomeBlock(entered, delayMs = 90) {
                PrimaryButton(stringResource(R.string.login_oauth), onOAuthLogin)
            }

            Spacer(Modifier.height(12.dp))

            // ② 密钥登录（PAT）
            WelcomeBlock(entered, delayMs = 180) {
                OutlineButton(stringResource(R.string.login_access_key), onKeyLogin)
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * 欢迎页里的一段入场。
 *
 * 只做淡入 + 1/6 自身高度的上浮：**不要换成 `expandVertically`** ——
 * 这一列上下各有一个 `weight(1f)`，高度一变整屏会在入场期间上下漂。
 */
@Composable
private fun WelcomeBlock(
    visible: Boolean,
    delayMs: Int,
    content: @Composable ColumnScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(ElementMotion.REVEAL_MS, delayMillis = delayMs)) +
            slideInVertically(tween(ElementMotion.REVEAL_MS, delayMillis = delayMs)) { it / 6 },
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, content = content)
    }
}


/**
 * 主题开关（太阳 / 月亮 / 自动）。
 *
 * 三态循环而不是二元开关：**跟随系统**是大多数人的默认期望，而显式锁定浅色/深色
 * 是给「系统设置与个人偏好不一致」的用户（户外看不清深色、或受不了白底刺激）。
 * 图标随档位交叉淡入（[AnimatedStateIcon]），点击反馈裁成圆形（[iconTap]）。
 */
@Composable
fun ThemeModeSwitch(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val mode by ThemeRuntime.mode.collectAsState()
    val press = rememberPressFeedback()
    val icon = when (mode) {
        ThemeMode.SYSTEM -> Icons.Filled.BrightnessAuto
        ThemeMode.LIGHT -> Icons.Filled.LightMode
        ThemeMode.DARK -> Icons.Filled.DarkMode
    }
    Box(
        modifier = modifier
            .size(38.dp)
            .graphicsLayer {
                scaleX = press.scale.value
                scaleY = press.scale.value
            }
            .clip(CircleShape)
            .background(Primer.BackgroundSecondary)
            .border(1.dp, Primer.Border.copy(alpha = 0.6f), CircleShape)
            .iconTap { ThemeRuntime.cycle(context) },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedStateIcon(
            icon = icon,
            // 读屏文案里的档位名同样要跟语言走：`mode.label` 曾是写死的中文，
            // 界面切英文后读屏仍念「跟随系统 / 浅色 / 深色」（`ThemeMode.labelRes` 是资源）
            contentDescription = stringResource(R.string.login_theme_switch, stringResource(mode.labelRes)),
            tint = Primer.IconPrimary,
            modifier = Modifier.size(20.dp),
        )
    }
}

// ───────────────────────── 两种模式的「流程要点」介绍页 ─────────────────────────

/**
 * 介绍页里的一步。
 *
 * @param highlight 该步是不是这套流程的**关键差异点**（要做呼吸高亮 + 额外示意）
 */
private data class AuthFlowStep(
    val icon: ImageVector,
    @StringRes val titleRes: Int,
    @StringRes val descRes: Int,
    val tint: TintRole,
    val highlight: Boolean = false,
)

/** 授权登录（OAuth）流程要点 */
private val oauthFlowSteps = listOf(
    AuthFlowStep(
        Icons.Filled.OpenInBrowser,
        R.string.login_step_oauth_open_title,
        R.string.login_step_oauth_open_desc,
        TintRole.ACCENT,
    ),
    AuthFlowStep(
        Icons.Filled.VerifiedUser,
        R.string.login_step_oauth_authorize_title,
        R.string.login_step_oauth_authorize_desc,
        TintRole.DONE,
    ),
    AuthFlowStep(
        Icons.Filled.Password,
        R.string.login_step_oauth_device_title,
        R.string.login_step_oauth_device_desc,
        TintRole.WARNING,
        highlight = true,
    ),
    AuthFlowStep(
        Icons.Filled.CheckCircle,
        R.string.login_step_oauth_done_title,
        R.string.login_step_oauth_done_desc,
        TintRole.SUCCESS,
    ),
)

/** 密钥登录（PAT）流程要点 */
private val keyFlowSteps = listOf(
    AuthFlowStep(
        Icons.Filled.Settings,
        R.string.login_step_key_create_title,
        R.string.login_step_key_create_desc,
        TintRole.ACCENT,
    ),
    AuthFlowStep(
        Icons.Filled.Checklist,
        R.string.login_step_key_scopes_title,
        R.string.login_step_key_scopes_desc,
        TintRole.DONE,
        highlight = true,
    ),
    AuthFlowStep(
        Icons.Filled.ContentPaste,
        R.string.login_step_key_paste_title,
        R.string.login_step_key_paste_desc,
        TintRole.WARNING,
    ),
    AuthFlowStep(
        Icons.Filled.Shield,
        R.string.login_step_key_no_otp_title,
        R.string.login_step_key_no_otp_desc,
        TintRole.SUCCESS,
        highlight = true,
    ),
)

/**
 * 权限清单（code → 一句话说明）。
 *
 * code 来自 [KEY_LOGIN_SCOPES] 这个唯一来源：介绍页的清单、网页端链接的预填参数都用它，
 * 避免「文案里写了四项、链接里只填了三项」这类不一致。
 */
private val keyScopeCopyRes: Map<String, Int> = mapOf(
    "repo" to R.string.login_scope_repo,
    "read:user" to R.string.login_scope_read_user,
    "read:org" to R.string.login_scope_read_org,
    "notifications" to R.string.login_scope_notifications,
)

/**
 * 授权登录介绍页（流程要点 + 渲染动画）。
 *
 * 动画不是装饰，而是用来**指明每一步在手机上长什么样**：
 * 步骤逐条错峰入场；「口令验证」这一步配一排会依次点亮的数字格（第 3 步的关键差异），
 * 让用户提前知道会被要求输入 6 位数字，而不是到那一步才懵。
 */
@Composable
fun OAuthIntroScreen(
    onBack: () -> Unit,
    onStart: () -> Unit,
    onSwitchToKey: () -> Unit,
    /**
     * 「发起授权」本身也是**异步动作**：生成 PKCE、拼授权 URL 都要过一次引擎，
     * 拉浏览器之前还有一次跨进程跳转。此前这一步没有任何挂起态 —— 连点两下会
     * 生成两组 PKCE、开两个浏览器标签页，而且第二次的地址与第一次的 verifier 对不上。
     */
    busy: Boolean = false,
) {
    AuthIntroScaffold(
        title = stringResource(R.string.login_oauth),
        steps = oauthFlowSteps,
        primaryText = stringResource(R.string.action_start_oauth),
        busy = busy,
        busyText = stringResource(R.string.state_starting_authorization),
        onBack = onBack,
        onStart = onStart,
        secondaryText = stringResource(R.string.action_switch_to_key),
        onSecondary = onSwitchToKey,
        extra = { CodeCellsRow() },
    )
}

/**
 * 密钥登录介绍页（流程要点 + 渲染动画）。
 *
 * 「勾选权限」这一步渲染成会依次打勾的权限清单，并高亮「无需数字口令验证」这一条卖点。
 */
@Composable
fun KeyIntroScreen(onBack: () -> Unit, onStart: () -> Unit, onSwitchToOAuth: () -> Unit) {
    AuthIntroScaffold(
        title = stringResource(R.string.login_access_key),
        steps = keyFlowSteps,
        primaryText = stringResource(R.string.action_enter_key),
        onBack = onBack,
        onStart = onStart,
        secondaryText = stringResource(R.string.action_switch_to_oauth),
        onSecondary = onSwitchToOAuth,
        extra = { ScopeChecklist() },
    )
}

/**
 * 介绍页外壳：标题 + 副标题 + 步骤列表 + 可选示意图 + 底部按钮。
 *
 * 入场动画：整页一次性淡入，步骤**逐条错峰**（每条 +90ms）从下方浮入 ——
 * 逐条出现会让用户按顺序读完，而不是一眼扫过（介绍页最怕没人看）。
 */
@Composable
private fun AuthIntroScaffold(
    title: String,
    steps: List<AuthFlowStep>,
    primaryText: String,
    onBack: () -> Unit,
    onStart: () -> Unit,
    secondaryText: String,
    onSecondary: () -> Unit,
    extra: @Composable () -> Unit,
    busy: Boolean = false,
    busyText: String? = null,
) {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Primer.BackgroundPrimary)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        SubPageHeader(title, onBack)

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = HorizontalPadding),
        ) {
            // 2026-11-24 清爽化：副标题与脚注（「适合：…」）不再渲染 ——
            // 页面标题 + 四条要点已经说清「是什么、怎么走」，小字只是在重复。
            // 资源保留（文案是否删除等逐条评估结论）。
            steps.forEachIndexed { index, step ->
                AnimatedVisibility(
                    visible = entered,
                    enter = fadeIn(tween(ElementMotion.REVEAL_MS, delayMillis = index * 90)) +
                        slideInVertically(tween(ElementMotion.REVEAL_MS, delayMillis = index * 90)) { it / 4 },
                    exit = fadeOut(tween(120)),
                ) {
                    FlowStepRow(index = index + 1, step = step, last = index == steps.lastIndex)
                }
            }

            Spacer(Modifier.height(10.dp))
            // 该模式的「示意图」：授权登录是数字口令格，密钥登录是权限清单
            extra()
            Spacer(Modifier.height(16.dp))
        }

        // 底部两个动作**各占一半、平齐**（2026-11-24 决策一）：
        // 左边是「换一种登录方式」，右边才是主操作。原先主按钮整宽、切换入口是一条
        // 整宽文字按钮，视线会先落在「换一个」上；现在主操作在右、切换在左，且尺寸对等。
        // 标签因此必须够短：`action_switch_to_*` 已从「改用密钥登录（免口令）」收敛成「改用密钥」。
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = HorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlineButton(
                text = secondaryText,
                onClick = onSecondary,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 10.dp),
            )
            PrimaryButton(
                text = primaryText,
                onClick = onStart,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 10.dp),
                busy = busy,
                // 挂起态文案：授权登录说「正在打开授权页」，其余走通用短名
                busyText = busyText ?: stringResource(R.string.state_checking),
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** 一步：序号圆点 + 图标 + 标题/说明；[AuthFlowStep.highlight] 的步骤带呼吸高亮。 */
@Composable
private fun FlowStepRow(index: Int, step: AuthFlowStep, last: Boolean) {
    val pulse = rememberPulse(minScale = 1f, maxScale = if (step.highlight) 1.05f else 1f)
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        // 左侧：序号 + 连接线
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(step.tint.color().copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(step.icon, contentDescription = null, tint = step.tint.color(), modifier = Modifier.size(15.dp))
            }
            if (!last) {
                Box(
                    Modifier
                        .width(2.dp)
                        .height(26.dp)
                        .background(Primer.Gray150),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(top = 1.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$index. ${stringResource(step.titleRes)}",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Primer.TextPrimary,
                )
                if (step.highlight) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .graphicsLayer {
                                scaleX = pulse.value
                                scaleY = pulse.value
                            }
                            .clip(RoundedCornerShape(5.dp))
                            .background(step.tint.color().copy(alpha = 0.14f))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    ) {
                        Text(stringResource(R.string.label_key_points), fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = step.tint.color())
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(stringResource(step.descRes), fontSize = 11.5.sp, color = Primer.TextTertiary, lineHeight = 17.sp)
        }
    }
}

/** 授权登录的示意图：一排数字口令格，按顺序点亮（说明「会要求输入 6 位数字」）。 */
@Composable
private fun CodeCellsRow() {
    var filled by remember { mutableIntStateOf(0) }
    // 文档入口从「一行文字链接」换成 GitHub 图标按钮：更短、更像一个入口，
    // 点下去是**就地弹出的整理版文档**（不再直接把人甩进浏览器）。
    var showDoc by remember { mutableStateOf(false) }
    // 依次点亮 1→6 再从头开始：把「要输 6 位口令」这件事演出来
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(260)
            filled = if (filled >= 6) 0 else filled + 1
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Primer.Gray100)
            .padding(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.label_code_prompt_example),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = Primer.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            GithubDocEntry(onClick = { showDoc = true })
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(6) { i ->
                val on = i < filled
                Box(
                    Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(selectionColor(on, on = Primer.Blue500.copy(alpha = 0.14f), off = Color.White))
                        .border(
                            1.dp,
                            selectionColor(on, on = Primer.Blue500, off = Primer.Border),
                            RoundedCornerShape(7.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (on) "•" else "",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Primer.AccentText,
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        // 两种情况互斥，且都只影响「首次新设备登录」——这是最容易误解的一步，写清楚
        Text(
            stringResource(R.string.login_twofactor_bullets),
            fontSize = 11.sp,
            color = Primer.TextTertiary,
            lineHeight = 17.sp,
        )
    }

    if (showDoc) {
        GithubDocSheet(onDismiss = { showDoc = false })
    }
}

/**
 * GitHub 文档入口：**经典 GitHub 标记**的圆形图标按钮（2026-11-24 决策三）。
 *
 * 触感动画取元素级规格：[rememberPressFeedback] 的 120ms 按压缩放 + 圆形水波纹
 * （`clickable(interactionSource = …, indication = LocalIndication.current)` 且 clip 在前，
 * 与 `Motion.kt` 里那条用法一致）。整块 30dp 是点击区，图标 16dp。
 */
@Composable
private fun GithubDocEntry(onClick: () -> Unit) {
    val press = rememberPressFeedback()
    Box(
        modifier = Modifier
            .size(30.dp)
            .graphicsLayer {
                scaleX = press.scale.value
                scaleY = press.scale.value
            }
            .clip(CircleShape)
            .background(Primer.BackgroundPrimary)
            .border(1.dp, Primer.Border, CircleShape)
            .clickable(
                interactionSource = press.interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_github_mark),
            contentDescription = stringResource(R.string.github_doc_entry),
            tint = Primer.IconPrimary,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * GitHub 官方文档的**就地整理版**（中/英各一套资源，跟随界面语言）。
 *
 * 为什么不再直接跳浏览器：设备验证是首次登录最容易懵的一步，原文跳出去就回不来
 * （授权流程还在等深链回调）。这里把「什么时候出现 / 怎么通过 / 收不到怎么办 /
 * 能不能跳过 / 安全提醒」压成五段，读得完再决定；想看原文仍留了一个入口。
 *
 * 动效：M3 `ModalBottomSheet` 自带滑入 + 拖拽关闭；内容整块 `verticalScroll`，
 * 长文在弹层里自己滚，不牵动页面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GithubDocSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_github_mark),
                    contentDescription = null,
                    tint = Primer.IconPrimary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.github_doc_title),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Primer.TextPrimary,
                    )
                    Text(
                        stringResource(R.string.github_doc_source),
                        fontSize = 11.sp,
                        color = Primer.TextTertiary,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            DocSection(R.string.github_doc_when_title, R.string.github_doc_when_body)
            DocSection(R.string.github_doc_how_title, R.string.github_doc_how_body)
            DocSection(R.string.github_doc_missing_title, R.string.github_doc_missing_body)
            DocSection(R.string.github_doc_skip_title, R.string.github_doc_skip_body)
            DocSection(R.string.github_doc_security_title, R.string.github_doc_security_body, danger = true)

            OutlinedButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_DEVICE_VERIFY_DOC)),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Primer.Blue500),
            ) {
                Text(stringResource(R.string.github_doc_open), fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** 弹层里的一小节：标题 + 正文；安全提醒用危险色，避免和「操作说明」混成一片。 */
@Composable
private fun DocSection(@StringRes title: Int, @StringRes body: Int, danger: Boolean = false) {
    Text(
        stringResource(title),
        fontSize = 13.5.sp,
        fontWeight = FontWeight.Bold,
        color = if (danger) Primer.Red500 else Primer.TextPrimary,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        stringResource(body),
        fontSize = 12.sp,
        color = Primer.TextTertiary,
        lineHeight = 19.sp,
    )
    Spacer(Modifier.height(14.dp))
}

/** GitHub 官方文档：登录时验证新设备（设备验证 / 口令）。 */
private const val GITHUB_DEVICE_VERIFY_DOC =
    "https://docs.github.com/zh/authentication/keeping-your-account-and-data-secure/verifying-new-devices-when-signing-in"

/** 密钥登录的示意图：权限清单逐个打勾（经典 PAT 需要勾的四项）。 */
@Composable
private fun ScopeChecklist() {
    // 权限是**契约**（与 KEY_LOGIN_SCOPES 同源），说明文案是**界面文案**（跟语言走）
    val keyScopes = KEY_LOGIN_SCOPES.map { it to stringResource(keyScopeCopyRes.getValue(it)) }
    var ticked by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(620)
            ticked = if (ticked >= keyScopes.size) 0 else ticked + 1
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Primer.Gray100)
            .padding(12.dp),
    ) {
        Text(stringResource(R.string.label_tick_scopes), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextSecondary)
        Spacer(Modifier.height(6.dp))
        keyScopes.forEachIndexed { i, (scope, desc) ->
            val on = i < ticked
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(selectionColor(on, on = Primer.Green500))
                        .border(
                            1.dp,
                            selectionColor(on, on = Primer.Green500, off = Primer.Border),
                            RoundedCornerShape(4.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (on) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(scope, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Primer.TextPrimary)
                Spacer(Modifier.width(8.dp))
                Text(desc, fontSize = 11.sp, color = Primer.TextTertiary)
            }
        }
    }
}

/**
 * 密钥填写页。
 *
 * 密钥默认遮蔽（可点「显示」核对粘贴内容）；失败原因**留在本页**，
 * 不整屏跳到错误页 —— 否则用户得重新进入、重新粘贴。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyInputScreen(
    busy: Boolean,
    error: String?,
    onBack: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var token by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // 失败原因**过渡出现**而不是当帧插入一段字：插入会让下面的说明整体下移，
    // 读起来像「页面跳了一下」。退场动画期间 `error` 已经被清空，所以要记住最后一句
    // （否则退场那 120ms 会先变空白、再收起来）。
    var lastError by remember { mutableStateOf("") }
    // 在组合内就地记住（带判重，写入一次即稳定）：换成 `LaunchedEffect` 会晚一帧，
    // 而晚的那一帧正好是「展开动画已经开始、里面还没有字」—— 会看到一段空盒子先撑开。
    if (error != null && error != lastError) lastError = error

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Primer.BackgroundPrimary)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            SubPageHeader(stringResource(R.string.login_access_key), onBack)

            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = HorizontalPadding)) {
                Text(
                    stringResource(R.string.note_paste_key),
                    fontSize = 12.5.sp,
                    color = Primer.TextTertiary,
                    lineHeight = 19.sp,
                )
                Spacer(Modifier.height(14.dp))

                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    // 失败时输入框自己也变色：只靠下方一行小字，用户很容易把这次失败读成「没反应」
                    isError = error != null,
                    singleLine = true,
                    label = { Text(stringResource(R.string.label_access_key), fontSize = 12.sp) },
                    placeholder = { Text(stringResource(R.string.hint_key_placeholder), fontSize = 13.sp, color = Primer.TextTertiary) },
                    visualTransformation = if (visible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        Text(
                            if (visible) stringResource(R.string.action_hide) else stringResource(R.string.action_show),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Primer.Blue500,
                            modifier = Modifier
                                .clickable { visible = !visible }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    },
                    textStyle = TextStyle(fontSize = 13.5.sp),
                )

                AnimatedVisibility(
                    visible = error != null,
                    enter = revealEnter(),
                    exit = revealExit(),
                ) {
                    Text(
                        lastError,
                        fontSize = 11.5.sp,
                        color = Primer.Red500,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                Spacer(Modifier.height(12.dp))
                // 直接跳到「新建经典密钥」页并预填权限，省得用户自己找、自己勾
                Text(
                    stringResource(R.string.action_generate_key_web),
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Primer.Blue500,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(keyTokenCreateUrl())),
                                )
                            }
                        }
                        .padding(vertical = 6.dp),
                )

                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.login_key_scopes_bullets),
                    fontSize = 11.5.sp,
                    color = Primer.TextTertiary,
                    lineHeight = 18.sp,
                )
            }

            Column(Modifier.padding(horizontal = HorizontalPadding)) {
                PrimaryButton(
                    text = stringResource(R.string.action_sign_in),
                    onClick = { onSubmit(token) },
                    enabled = token.isNotBlank(),
                    busy = busy,
                    busyText = stringResource(R.string.state_checking),
                )
                Spacer(Modifier.height(12.dp))
            }
        }

        // 2026-11-24 决策二：密钥校验统一成**整屏覆盖**的加载态（与授权进度页同一观感），
        // 不再只有按钮里那一下转圈 —— 校验是一次网络往返，令牌短则一两百毫秒，
        // 长的会被读成「点完没反应」。覆盖层同时吃掉点击，避免校验中误触下面的链接。
        if (busy) {
            LoginBusyOverlay(
                title = stringResource(R.string.state_verifying_key),
                note = stringResource(R.string.note_verifying_key),
            )
        }
    }
}

/**
 * 04 · 双重验证 TwoFactorScreen
 */
@Composable
fun TwoFactorScreen(
    onVerify: (String) -> Unit,
) {
    var code by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Primer.BackgroundPrimary)
            .navigationBarsPadding()
            .padding(horizontal = HorizontalPadding)
            .padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.label_two_factor), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.hint_enter_code), fontSize = 14.sp, color = Primer.TextTertiary)

        Spacer(Modifier.height(28.dp))

        // 6 格验证码输入（骨架：真实实现用 BasicTextField 或自定义）
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(6) { i ->
                Box(
                    modifier = Modifier
                        .size(44.dp, 54.dp)
                        .background(Primer.BackgroundPrimary, RoundedCornerShape(8.dp))
                        .border(
                            2.dp,
                            if (i < code.length) Primer.Blue500 else Primer.Border,
                            RoundedCornerShape(8.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (i < code.length) code[i].toString() else "",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Primer.TextPrimary
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        PrimaryButton(
            text = stringResource(R.string.action_verify),
            onClick = {
                if (RustBridge.validateTwoFactor(code)) {
                    onVerify(code)
                }
            },
        )
    }
}

// ───────────────────── 等待结果 / 失败：异步链的另外两端 ─────────────────────

/**
 * 登录链路里「已经发起、在等结果」的那一屏（授权中 / 换 token 中）。
 *
 * ## 为什么这一屏必须有出口
 *
 * 这里是登录链路唯一一处**用户会被晾着**的地方：拉起系统浏览器之后，回不回来取决于
 * 浏览器里的那一步。此前它只有一行「正在打开 GitHub 授权页」和一个静止的应用图标 ——
 * 用户在浏览器里点了「取消」再切回来，看到的是同一句话，**没有任何可点的东西**
 * （唯一的出口是系统返回键，而它不可见）。现在：
 *
 * - 底部给「重新打开授权页」与「取消」两个入口：前者兜「浏览器被系统回收 / 没弹出来」，
 *   后者兜「我改主意了」；
 * - 整块内容淡入 + 上浮，与欢迎页 / 介绍页同一节奏，避免「跳转回来是另一张静止图」。
 *
 * 注意**不要**在这里自动判定「用户取消了授权」：回前台不等于没拿到授权码
 * （用户可能只是切出去看一眼再回来），误判会把正常等待变成失败页。
 * 出口交给用户，比猜用户更可靠。
 */
@Composable
fun AuthorizingScreen(
    authorizeUrl: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    // 进这一屏就拉起浏览器；`authorizeUrl` 变了（重新授权）会再拉一次
    LaunchedEffect(authorizeUrl) { openAuthPage(context, authorizeUrl) }
    LoginWaitingScreen(
        title = stringResource(R.string.state_opening_auth_page),
        note = stringResource(R.string.note_complete_code_in_browser),
    ) {
        // 2026-11-24 决策一：原来的「取消」改成「返回上一页」——退回去的是**授权登录的
        // 流程要点页**（`loginBackTarget`），不是欢迎页；两个动作各占一半、等高平齐，
        // 与介绍页底部同一套版式（左 = 退一步，右 = 继续）。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlineButton(
                text = stringResource(R.string.action_back_previous),
                onClick = onBack,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 10.dp),
            )
            PrimaryButton(
                text = stringResource(R.string.action_reopen_auth_page),
                onClick = { openAuthPage(context, authorizeUrl) },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 10.dp),
            )
        }
    }
}

/** 换取 token 中：没有任何可点的东西（自动完成），只需要把「在等」这件事说出来。 */
@Composable
fun ExchangingTokenScreen() {
    LoginWaitingScreen(title = stringResource(R.string.state_verifying_auth))
}

/**
 * 整屏覆盖的加载态（密钥校验等**留在原页**的异步动作）。
 *
 * 与 `LoginWaitingScreen`（整页等待）的区别：那一屏是「页面身份换了」，这里是
 * 「同一个页面、正在等结果」—— 所以不能换页（换了会丢掉已输入的内容），
 * 用一层不透明覆盖 + 点击拦截达到同样的「整屏在忙」观感。
 */
@Composable
private fun LoginBusyOverlay(title: String, note: String? = null) {
    val swallow = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Primer.BackgroundPrimary.copy(alpha = 0.97f))
            // 吃掉点击：覆盖层下面的链接（去网页端生成密钥）不该在「正在校验」时被误触
            .clickable(interactionSource = swallow, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppIcon(size = 72.dp, shape = RoundedCornerShape(20.dp))
            Spacer(Modifier.height(18.dp))
            CircularProgressIndicator(color = Primer.Blue500)
            Spacer(Modifier.height(14.dp))
            Text(
                title,
                fontSize = 12.5.sp,
                color = Primer.TextTertiary,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp,
            )
            if (note != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    note,
                    fontSize = 11.5.sp,
                    color = Primer.TextTertiary,
                    textAlign = TextAlign.Center,
                    lineHeight = 17.sp,
                )
            }
        }
    }
}

/**
 * 等待屏外壳（授权中 / 换 token 中）。
 *
 * 内容居中、动作贴底：这一屏会被系统浏览器盖住，用户真正看到它的时刻是「切回来之后」——
 * 所以动作要落在**拇指够得到**的底部，而不是图标下面。
 */
@Composable
private fun LoginWaitingScreen(
    title: String,
    note: String? = null,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    Box(Modifier.fillMaxSize().background(Primer.BackgroundPrimary)) {
        Column(
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedVisibility(
                visible = entered,
                enter = fadeIn(tween(ElementMotion.REVEAL_MS)) +
                    slideInVertically(tween(ElementMotion.REVEAL_MS)) { it / 8 },
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AppIcon(size = 72.dp, shape = RoundedCornerShape(20.dp))
                    Spacer(Modifier.height(18.dp))
                    CircularProgressIndicator(color = Primer.Blue500)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        title,
                        fontSize = 12.5.sp,
                        color = Primer.TextTertiary,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp,
                    )
                    if (note != null) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            note,
                            fontSize = 11.5.sp,
                            color = Primer.TextTertiary,
                            textAlign = TextAlign.Center,
                            lineHeight = 17.sp,
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = HorizontalPadding)
                .padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = actions,
        )
    }
}

/**
 * 登录失败页（异步链的**失败端**）。
 *
 * ## 为什么它取代了原来那一行红字
 *
 * 原实现是 `Box { Text(message, color = Red500) }`：没有标题、没有出口、也没有说明 ——
 * 用户看到的是一屏「ERROR: ...」的原始报文，而唯一的出路是看不见的系统返回键。
 * 这一步最常出现在「授权完成、但换取令牌失败」之后，恰恰是用户最可能想**再来一次**的时刻。
 *
 * 三个要素缺一不可：
 * 1. **是什么**：标题 + 原始失败原因（原样保留，便于对日志）；
 * 2. **怎么办**：`重试`（重跑失败的那一步，而不是一律回欢迎页）+ `返回`（换一种登录方式）；
 * 3. **怎么来的**：一句提示说明「重试 = 从这一步重新走、不会重复授权」。
 */
@Composable
fun LoginErrorScreen(
    message: String,
    retryLabel: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Primer.BackgroundPrimary)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = HorizontalPadding),
    ) {
        Spacer(Modifier.weight(1f))

        AnimatedVisibility(
            visible = entered,
            enter = fadeIn(tween(ElementMotion.REVEAL_MS)) +
                slideInVertically(tween(ElementMotion.REVEAL_MS)) { it / 8 },
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Primer.DangerSurfaceSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = Primer.Red500,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.login_error_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Primer.TextPrimary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    message,
                    fontSize = 12.5.sp,
                    color = Primer.TextTertiary,
                    textAlign = TextAlign.Center,
                    lineHeight = 19.sp,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.login_error_hint),
                    fontSize = 11.5.sp,
                    color = Primer.TextTertiary,
                    textAlign = TextAlign.Center,
                    lineHeight = 17.sp,
                )
            }
        }

        Spacer(Modifier.weight(1f))

        PrimaryButton(text = retryLabel, onClick = onRetry)
        Spacer(Modifier.height(10.dp))
        OutlineButton(text = stringResource(R.string.action_back), onClick = onBack)
        Spacer(Modifier.height(16.dp))
    }
}

/** 用系统浏览器打开授权页（失败不抛：没有浏览器时用户还有「取消」这条出口）。 */
private fun openAuthPage(context: android.content.Context, url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
