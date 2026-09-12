package com.branchbase.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.core.RustBridge
import com.branchbase.ui.theme.AppIcon
import com.branchbase.ui.theme.Primer
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ContentPaste
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
import com.branchbase.ui.navigation.BackDisposition
import com.branchbase.ui.profile.SubPageHeader
import com.branchbase.ui.theme.ElementMotion
import com.branchbase.ui.theme.rememberPulse
import com.branchbase.ui.theme.selectionColor

/**
 * 登录流程 Compose 界面（骨架）。
 *
 * 设计基准：360dp × 792dp，状态栏 40dp / 手势条 16dp 安全区。
 * 间距规范：水平内边距 22dp（HorizontalPadding）、按钮圆角 8dp（BtnShape）。
 */

private val BtnShape = RoundedCornerShape(8.dp)
private val HorizontalPadding = 22.dp

/** 主按钮（全宽、主蓝） */
@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = BtnShape,
        colors = ButtonDefaults.buttonColors(containerColor = Primer.Blue500)
    ) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}

/** 次按钮（描边） */
@Composable
private fun OutlineButton(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .border(1.dp, Primer.Border, BtnShape),
        shape = BtnShape,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Primer.TextSecondary)
    ) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * 01 · 欢迎页 WelcomeScreen
 */
/**
 * 01 · 欢迎页：两种登录模式各一个入口。
 *
 * 两种模式的差别（是否要数字口令）对用户是**决定性**的，所以入口处各带一句定位说明，
 * 点进去还有各自的流程要点介绍页（[OAuthIntroScreen] / [KeyIntroScreen]）。
 */
@Composable
fun WelcomeScreen(
    onOAuthLogin: () -> Unit,
    onKeyLogin: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Primer.BackgroundPrimary)
            .navigationBarsPadding()
            .padding(horizontal = HorizontalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))

        // 应用图标：直接渲染系统当前展示的那枚图标（不再用「蓝底 + 字母 B」占位）
        AppIcon(size = 96.dp, shape = RoundedCornerShape(26.dp))

        Spacer(Modifier.height(22.dp))
        Text("Branchbase", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary)
        Spacer(Modifier.height(6.dp))
        Text("GitHub 第三方客户端", fontSize = 14.sp, color = Primer.TextTertiary)

        Spacer(Modifier.weight(1f))

        // ① 授权登录（OAuth）
        PrimaryButton("授权登录", onOAuthLogin)
        Spacer(Modifier.height(6.dp))
        Text(
            "推荐 · 网页点一下授权即可；账号开了双重验证时需要输入数字口令",
            fontSize = 11.5.sp,
            color = Primer.TextTertiary,
            lineHeight = 16.sp,
        )

        Spacer(Modifier.height(14.dp))

        // ② 密钥登录（PAT）
        OutlineButton("密钥登录", onKeyLogin)
        Spacer(Modifier.height(6.dp))
        Text(
            "网页端生成密钥并勾选权限；登录时无需数字口令验证",
            fontSize = 11.5.sp,
            color = Primer.TextTertiary,
            lineHeight = 16.sp,
        )

        Spacer(Modifier.height(16.dp))
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
    val title: String,
    val desc: String,
    val tint: Color,
    val highlight: Boolean = false,
)

/** 授权登录（OAuth）流程要点 */
private val oauthFlowSteps = listOf(
    AuthFlowStep(
        Icons.Filled.OpenInBrowser,
        "打开 GitHub 授权页",
        "用系统浏览器打开，在网页上确认这次授权",
        Primer.Blue500,
    ),
    AuthFlowStep(
        Icons.Filled.VerifiedUser,
        "点「Authorize」授权本应用",
        "只授予列出的权限（仓库 / 用户 / 组织 / 通知），随时可在 GitHub 撤销",
        Primer.Purple500,
    ),
    AuthFlowStep(
        Icons.Filled.Password,
        "输入数字口令（开了双重验证时）",
        "这是官方客户端登录同样绕不过的一步：网页端会要求 6 位数字口令",
        Primer.Orange500,
        highlight = true,
    ),
    AuthFlowStep(
        Icons.Filled.CheckCircle,
        "自动跳回 App，登录完成",
        "授权码由系统自动带回，不需要手动复制任何内容",
        Primer.Green500,
    ),
)

/** 密钥登录（PAT）流程要点 */
private val keyFlowSteps = listOf(
    AuthFlowStep(
        Icons.Filled.Settings,
        "在网页端生成密钥",
        "GitHub → Settings → Developer settings → Personal access tokens",
        Primer.Blue500,
    ),
    AuthFlowStep(
        Icons.Filled.Checklist,
        "勾选权限后生成",
        "经典密钥勾 repo / read:user / read:org / notifications 四项即可（下方已列出）",
        Primer.Purple500,
        highlight = true,
    ),
    AuthFlowStep(
        Icons.Filled.ContentPaste,
        "粘贴到 App 并确认",
        "密钥只保存在本机，不上传、不写日志；可随时在网页端撤销",
        Primer.Orange500,
    ),
    AuthFlowStep(
        Icons.Filled.Shield,
        "登录时无需数字口令验证",
        "密钥本身就是凭证，这是密钥登录相比授权登录最直接的好处",
        Primer.Green500,
        highlight = true,
    ),
)

/**
 * 权限清单（code → 一句话说明）。
 *
 * code 来自 [KEY_LOGIN_SCOPES] 这个唯一来源：介绍页的清单、网页端链接的预填参数都用它，
 * 避免「文案里写了四项、链接里只填了三项」这类不一致。
 */
private val keyScopeCopy = mapOf(
    "repo" to "读写仓库（提交 / 分支 / PR）",
    "read:user" to "读取账号资料",
    "read:org" to "读取组织信息",
    "notifications" to "读取与标记通知",
)

private val keyScopes: List<Pair<String, String>> =
    KEY_LOGIN_SCOPES.map { it to keyScopeCopy.getValue(it) }

/**
 * 授权登录介绍页（流程要点 + 渲染动画）。
 *
 * 动画不是装饰，而是用来**指明每一步在手机上长什么样**：
 * 步骤逐条错峰入场；「口令验证」这一步配一排会依次点亮的数字格（第 3 步的关键差异），
 * 让用户提前知道会被要求输入 6 位数字，而不是到那一步才懵。
 */
@Composable
fun OAuthIntroScreen(onBack: () -> Unit, onStart: () -> Unit, onSwitchToKey: () -> Unit) {
    AuthIntroScaffold(
        title = "授权登录",
        subtitle = "通过 GitHub 官方 OAuth 授权，最省事的一条路；账号开启双重验证时需要输入数字口令。",
        steps = oauthFlowSteps,
        footNote = "适合：已经在 GitHub 网页端登录、且记得住/拿得到数字口令的账号。",
        primaryText = "开始授权登录",
        onBack = onBack,
        onStart = onStart,
        secondaryText = "改用密钥登录（免口令）",
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
        title = "密钥登录",
        subtitle = "在 GitHub 网页端生成一枚访问密钥，填进 App 即可；登录时不需要数字口令验证。",
        steps = keyFlowSteps,
        footNote = "适合：不想每次都输数字口令，或主要用手机做提交/发 PR 的账号。",
        primaryText = "填写密钥",
        onBack = onBack,
        onStart = onStart,
        secondaryText = "改用授权登录（一键授权）",
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
    subtitle: String,
    steps: List<AuthFlowStep>,
    footNote: String,
    primaryText: String,
    onBack: () -> Unit,
    onStart: () -> Unit,
    secondaryText: String,
    onSecondary: () -> Unit,
    extra: @Composable () -> Unit,
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
            AnimatedVisibility(
                visible = entered,
                enter = fadeIn(tween(ElementMotion.REVEAL_MS)) +
                    slideInVertically(tween(ElementMotion.REVEAL_MS)) { it / 6 },
            ) {
                Text(subtitle, fontSize = 12.5.sp, color = Primer.TextTertiary, lineHeight = 19.sp)
            }
            Spacer(Modifier.height(14.dp))

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

            Spacer(Modifier.height(14.dp))
            Text(footNote, fontSize = 11.5.sp, color = Primer.TextTertiary, lineHeight = 17.sp)
            Spacer(Modifier.height(16.dp))
        }

        Column(Modifier.padding(horizontal = HorizontalPadding)) {
            PrimaryButton(primaryText, onStart)
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onSecondary, modifier = Modifier.fillMaxWidth()) {
                Text(secondaryText, fontSize = 13.sp, color = Primer.Blue500)
            }
            Spacer(Modifier.height(12.dp))
        }
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
                    .background(step.tint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(step.icon, contentDescription = null, tint = step.tint, modifier = Modifier.size(15.dp))
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
                    "$index. ${step.title}",
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
                            .background(step.tint.copy(alpha = 0.14f))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    ) {
                        Text("要点", fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = step.tint)
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(step.desc, fontSize = 11.5.sp, color = Primer.TextTertiary, lineHeight = 17.sp)
        }
    }
}

/** 授权登录的示意图：一排数字口令格，按顺序点亮（说明「会要求输入 6 位数字」）。 */
@Composable
private fun CodeCellsRow() {
    var filled by remember { mutableIntStateOf(0) }
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
        Text("口令验证长这样", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextSecondary)
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
                        color = Primer.Blue600,
                    )
                }
            }
        }
    }
}

/** 密钥登录的示意图：权限清单逐个打勾（经典 PAT 需要勾的四项）。 */
@Composable
private fun ScopeChecklist() {
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
        Text("勾选这些权限即可", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextSecondary)
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Primer.BackgroundPrimary)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        SubPageHeader("密钥登录", onBack)

        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = HorizontalPadding)) {
            Text(
                "粘贴在 GitHub 网页端生成的访问密钥。密钥只保存在本机，不会上传到任何服务器。",
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
                singleLine = true,
                label = { Text("访问密钥", fontSize = 12.sp) },
                placeholder = { Text("ghp_… 或 github_pat_…", fontSize = 13.sp, color = Primer.TextTertiary) },
                visualTransformation = if (visible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    Text(
                        if (visible) "隐藏" else "显示",
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

            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(error, fontSize = 11.5.sp, color = Primer.Red500, lineHeight = 17.sp)
            }

            Spacer(Modifier.height(12.dp))
            // 直接跳到「新建经典密钥」页并预填权限，省得用户自己找、自己勾
            Text(
                "去网页端生成密钥（已预填所需权限）",
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
                "• 经典密钥：勾选 repo / read:user / read:org / notifications 四项\n" +
                    "• 细粒度密钥：至少给 Contents、Issues、Pull requests、Notifications 读权限（含 Metadata）\n" +
                    "• 登录时不要求数字口令验证；密钥可随时在网页端撤销",
                fontSize = 11.5.sp,
                color = Primer.TextTertiary,
                lineHeight = 18.sp,
            )
        }

        Column(Modifier.padding(horizontal = HorizontalPadding)) {
            Button(
                onClick = { onSubmit(token) },
                enabled = !busy && token.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = BtnShape,
                colors = ButtonDefaults.buttonColors(containerColor = Primer.Blue500),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("校验中…", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                } else {
                    Text("登录", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
            }
            Spacer(Modifier.height(12.dp))
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
        Text("双重验证", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary)
        Spacer(Modifier.height(6.dp))
        Text("输入 GitHub 生成的 6 位验证码", fontSize = 14.sp, color = Primer.TextTertiary)

        Spacer(Modifier.height(28.dp))

        // 6 格验证码输入（骨架：真实实现用 BasicTextField 或自定义）
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(6) { i ->
                Box(
                    modifier = Modifier
                        .size(44.dp, 54.dp)
                        .background(Color.White, RoundedCornerShape(8.dp))
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

        PrimaryButton("验证") {
            if (RustBridge.validateTwoFactor(code)) {
                onVerify(code)
            }
        }
    }
}
