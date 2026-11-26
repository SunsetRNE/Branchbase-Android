package com.branchbase.ui.profile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.R
import com.branchbase.core.AccountStore
import com.branchbase.core.LocalRepos
import com.branchbase.ui.log.Logger
import com.branchbase.ui.settings.NavRow
import com.branchbase.ui.settings.SettingsSection
import com.branchbase.ui.settings.displayGitProxy
import com.branchbase.ui.settings.gitProxy
import com.branchbase.ui.theme.Primer
import com.branchbase.ui.theme.revealEnter
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 提交模式独立子页（设置页规范见 `docs/specs/settings-design.md` §三）。
 *
 * ## 2026-11-24 的两条决策
 *
 * 1. **当前模式从页尾一行文字变成头部状态徽标**：结论要第一眼看到，而不是滚到底才知道
 *    「现在用的是哪种」。徽标同时是**内容抽屉的入口**。
 * 2. **原页首说明搬进抽屉并扩写**：说明本身的体量不该常驻（会把三个选项挤到屏幕外），
 *    但删掉又会让「切了会怎样」无处可查 —— 所以做成「点徽标 → 抽屉」，抽屉里逐模式讲
 *    「改动去了哪 / 什么时候用」，原文保留为导语。
 *
 * ## 2026-11-27 的补充
 *
 * 3. **「本地仓库」快捷按钮挂在第三档右侧**：选档与进仓库列表是同一行上的两件事，
 *    后者不再只从设置主页进（设置主页那一行在非本地模式下整行隐藏，见决策三）。
 *    出现条件收在 `showLocalRepoShortcut(mode, repoCount)`（推 `CommitModeStore.kt`）。
 * 4. **Git 代理入口搬进说明抽屉**：它只作用于本地仓库的 clone / pull / push，
 *    与三档是同一件事的两半；设置主页的「网络」组因此下线。
 *
 * 动效：徽标标签切换走 `AnimatedContent`（淡入 + 纵向滑动），单选圆点在选中时做一次轻弹，
 * 抽屉内容进场走统一的 `revealEnter()`（纵向展开 + 淡入）。
 */
@Composable
fun CommitModeScreen(
    onBack: () -> Unit,
    onOpenLocalRepo: () -> Unit,
    onOpenGitProxy: () -> Unit,
) {
    LaunchedEffectOnce()

    val context = LocalContext.current
    var mode by remember { mutableStateOf(commitMode(context)) }
    var drawerOpen by remember { mutableStateOf(false) }
    // 仓库数量只在进入本页时数一次（本页不进保活）；数的是**当前账号**的目录，
    // 与本地仓库页 `LocalRepos.rootFor(context, login)` 同一套隔离规则。
    val localRepoCount = remember { LocalRepos.count(context, AccountStore.currentLogin(context)) }
    // 抽屉里那一行代理的值：同样进 remember（读 prefs，组合期不裸调）
    val proxyValue = remember {
        displayGitProxy(gitProxy(context)).ifEmpty { context.getString(R.string.state_not_set) }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Primer.BackgroundPrimary)
            .statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        SubPageHeader(
            title = stringResource(R.string.nav_commit_mode),
            onBack = onBack,
            trailing = {
                CurrentModeBadge(mode = mode, onClick = { drawerOpen = true })
            },
        )

        CommitModeOptions(
            mode = mode,
            showLocalRepo = showLocalRepoShortcut(mode, localRepoCount),
            onOpenLocalRepo = onOpenLocalRepo,
        ) { m ->
            mode = m
            saveCommitMode(context, m)
            Logger.ui("提交模式改为 ${m.logLabel}", "Compose")
        }

        Spacer(Modifier.height(16.dp))
    }

    if (drawerOpen) {
        CommitModeSheet(
            current = mode,
            proxyValue = proxyValue,
            onOpenGitProxy = onOpenGitProxy,
            onDismiss = { drawerOpen = false },
        )
    }
}

/**
 * 头部状态徽标：当前提交模式 + 抽屉入口。
 *
 * 未配置时显示「未设置」——这比空白或隐藏更诚实：用户知道**现在还没选**，
 * 而不是以为某个默认值已生效。标签切换走 `AnimatedContent`，
 * 让「刚刚改成了另一个」这件事在头部就看得见。
 */
@Composable
private fun CurrentModeBadge(mode: CommitMode?, onClick: () -> Unit) {
    val label = mode?.let { stringResource(it.labelRes) } ?: stringResource(R.string.commit_mode_badge_unset)
    val a11y = stringResource(R.string.commit_mode_badge_a11y, label)
    // 三档各自的「底色 / 前景 / 圆点」：同一个徽标形状，换档时三色一起弹过去。
    // 颜色不是装饰：单次=蓝、缓存=紫、本地=绿，扫一眼就能认出当前是哪一档。
    val (bg, fg, dot) = when (mode) {
        CommitMode.SINGLE_FILE -> Triple(Primer.InfoSurface, Primer.Blue600, Primer.Blue500)
        CommitMode.MULTI_FILE -> Triple(Primer.PurpleSurface, Primer.Purple500, Primer.Purple500)
        CommitMode.LOCAL_REPO -> Triple(Primer.SuccessSurface, Primer.SuccessTextStrong, Primer.Green500)
        null -> Triple(Primer.Gray150, Primer.TextSecondary, Primer.Gray500)
    }
    val colorSpec = spring<Color>(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
    val bgColor by animateColorAsState(bg, colorSpec, label = "badge-bg")
    val fgColor by animateColorAsState(fg, colorSpec, label = "badge-fg")
    val dotColor by animateColorAsState(dot, colorSpec, label = "badge-dot")

    // 徽标也不要默认灰色涟漪：按压缩放一下即可（点击反馈走动画，不叠一层灰）
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "badge-press-scale",
    )

    Row(
        modifier = Modifier
            .clip(CircleShape)
            .scale(pressScale)
            .background(bgColor)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = a11y }
            .padding(start = 10.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(dotColor))
        Spacer(Modifier.width(6.dp))
        AnimatedContent(
            targetState = label,
            transitionSpec = {
                // 默认 slide 规格就是弹簧，和圆点 / 底色的弹性同一节奏
                (fadeIn(tween(160)) + slideInVertically { it / 2 }) togetherWith
                    (fadeOut(tween(100)) + slideOutVertically { -it / 2 })
            },
            label = "commit-mode-badge",
        ) { text ->
            Text(text, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = fgColor)
        }
        Icon(
            Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = fgColor,
            modifier = Modifier.size(14.dp),
        )
    }
}

/**
 * 三档选项 + **一条会滑动的高亮**。
 *
 * 为什么不是每行各自淡入淡出：快速连切时旧行的绿还没褪完、新行的绿已经开始进，
 * 两行同时半绿就闪。改成「只有一块高亮，从旧行滑到新行」，任何时刻屏幕上只有一块绿；
 * 位置与高度都从**实际测量**的坐标来，不假设三行等高（字体缩放 / 说明换行都安全）。
 */
@Composable
private fun CommitModeOptions(
    mode: CommitMode?,
    showLocalRepo: Boolean,
    onOpenLocalRepo: () -> Unit,
    onPick: (CommitMode) -> Unit,
) {
    val modes = CommitMode.entries
    val selectedIndex = modes.indexOf(mode)
    val bounds = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }
    val highlightTop = remember { Animatable(0f) }
    val highlightHeight = remember { Animatable(0f) }
    val highlightAlpha = remember { Animatable(1f) }
    var placed by remember { mutableStateOf(false) }

    // 进场：先落位、高度从 0 展开 + 淡入；之后每次换档用弹簧从旧行滑到新行。
    LaunchedEffect(selectedIndex, bounds[selectedIndex]) {
        if (selectedIndex < 0) {
            placed = false
            return@LaunchedEffect
        }
        val b = bounds[selectedIndex] ?: return@LaunchedEffect
        val spec = spring<Float>(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)
        if (!placed) {
            // 首次进场：位置直接对准，高度 0 → 行高、透明度 0 → 1
            highlightTop.snapTo(b.first)
            highlightHeight.snapTo(0f)
            highlightAlpha.snapTo(0f)
            placed = true
            coroutineScope {
                launch { highlightHeight.animateTo(b.second, spec) }
                launch { highlightAlpha.animateTo(1f, tween(180)) }
            }
        } else {
            coroutineScope {
                launch { highlightTop.animateTo(b.first, spec) }
                launch { highlightHeight.animateTo(b.second, spec) }
            }
        }
    }

    Box(Modifier.fillMaxWidth()) {
        if (placed && selectedIndex >= 0) {
            val density = LocalDensity.current
            Box(
                Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(0, highlightTop.value.roundToInt()) }
                    .height(with(density) { highlightHeight.value.toDp() })
                    .alpha(highlightAlpha.value)
                    .background(Primer.SuccessSurface),
            )
        }
        Column {
            modes.forEachIndexed { index, m ->
                ModeOptionRow(
                    // 卡片是整行，可以承载完整说明；状态位（设置页右侧值等）只用短名 m.labelRes
                    label = stringResource(m.titleRes),
                    desc = stringResource(m.descRes),
                    selected = mode == m,
                    bouncySelection = true,
                    // 底色由上面那块滑动高亮统一画；行里再画就会出现两行同时绿
                    drawSelectionBackground = false,
                    // 滑动高亮本身就是点击反馈，行里不再叠按压色
                    pressFeedback = false,
                    // 第三档行尾的「本地仓库」快捷入口（决策一 / 决策二）：条件是
                    // 「本地管理开着，或盘上还留着仓库」，纯函数在 CommitModeStore.kt
                    trailing = if (m == CommitMode.LOCAL_REPO && showLocalRepo) {
                        { LocalRepoShortcut(onClick = onOpenLocalRepo) }
                    } else {
                        null
                    },
                    modifier = Modifier.onGloballyPositioned { c ->
                        val b = c.positionInParent().y to c.size.height.toFloat()
                        if (bounds[index] != b) bounds[index] = b
                    },
                    onClick = { if (mode != m) onPick(m) },
                )
            }
        }
    }
}

/**
 * 第三档行尾的「本地仓库」快捷按钮（2026-11-27 决策一）。
 *
 * 为什么做成行尾胶囊、而不是再加一条设置行：它和「选档」是同一行上的两个动作 ——
 * 选档改的是**提交行为**，这个按钮去的是**仓库列表**。Compose 的嵌套 `clickable`
 * 会先消费事件，所以点按钮不会顺带把模式切到第三档。
 *
 * 视觉：白底 + 控件描边（`BorderControl`，可交互边界要 ≥3:1），压在选中行的
 * `SuccessSurface` 上仍然读得清；不用 `Modifier.alpha`（那是 graphicsLayer）。
 */
@Composable
private fun LocalRepoShortcut(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Primer.BackgroundPrimary)
            .border(1.dp, Primer.BorderControl, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .heightIn(min = 30.dp)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Folder,
            contentDescription = null,
            tint = Primer.TextSecondary,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            stringResource(R.string.nav_local_repo),
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = Primer.TextPrimary,
        )
    }
}

/**
 * 内容抽屉：原页首说明 + 逐模式的展开说明。
 *
 * 抽屉本身由 `ModalBottomSheet` 提供滑入 / 滑出；内容再叠一层 `revealEnter()`，
 * 让「打开」是两段动作（面板上来 → 内容展开），而不是整块内容瞬间贴上。
 * 每个模式都回答同一个问题：**改动去了哪、什么时候该用它**。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommitModeSheet(
    current: CommitMode?,
    proxyValue: String,
    onOpenGitProxy: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    // 首帧 false → true，触发内容进场动画；抽屉关闭时内容随 sheet 一起销毁，下次重来
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { revealed = true }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            // 说明正文两侧各留 20dp；下面的「网络」卡片按设置页的卡片边距整宽铺开，
            // 所以内边距放在这一层，而不是挂在整个滚动列上（卡片再被 20dp 推一下会太窄）
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text(
                    stringResource(R.string.commit_mode_drawer_title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Primer.TextPrimary,
                )
                Spacer(Modifier.height(6.dp))

                AnimatedVisibility(visible = revealed, enter = revealEnter()) {
                    Column {
                        // 原文保留在抽屉里（导语），没有丢 —— 只是不再常驻页面
                        Text(
                            stringResource(R.string.note_commit_mode_explain),
                            fontSize = 12.5.sp,
                            color = Primer.TextSecondary,
                            lineHeight = 19.sp,
                        )
                        Spacer(Modifier.height(14.dp))
                        CommitMode.entries.forEach { m ->
                            Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        stringResource(m.titleRes),
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Primer.TextPrimary,
                                    )
                                    if (m == current) {
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            stringResource(R.string.commit_mode_drawer_current),
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Primer.SuccessTextStrong,
                                        )
                                    }
                                }
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    stringResource(m.descRes),
                                    fontSize = 12.sp,
                                    color = Primer.TextSecondary,
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    stringResource(m.drawerBodyRes),
                                    fontSize = 12.sp,
                                    color = Primer.TextTertiary,
                                    lineHeight = 18.sp,
                                )
                            }
                        }
                        Text(
                            stringResource(R.string.commit_mode_drawer_footnote),
                            fontSize = 11.5.sp,
                            color = Primer.TextTertiary,
                            lineHeight = 17.sp,
                        )
                    }
                }
            }

            // 网络：Git 代理入口（2026-11-27 决策四 —— 从设置主页的「网络」组搬来）。
            // 与三档同一屏的理由：代理只作用于本地仓库的 clone / pull / push，
            // 放这里既不占主页一行，也不需要用户记得「代理在另一个二级页」。
            Spacer(Modifier.height(14.dp))
            SettingsSection(stringResource(R.string.label_network_group)) {
                NavRow(
                    icon = Icons.Filled.Public,
                    name = stringResource(R.string.nav_git_proxy),
                    sub = stringResource(R.string.note_proxy_scope_detail),
                    // 值列只显示 host:port，凭据不进列表（规范 §6.5）
                    value = proxyValue,
                    onClick = {
                        // 先收抽屉再跳页：跳页后本页会随组合销毁，抽屉不会残留
                        onDismiss()
                        onOpenGitProxy()
                    },
                    divider = false,
                )
            }
        }
    }
}

/** 进入页面的日志（保持与其它子页一致的行为）。 */
@Composable
private fun LaunchedEffectOnce() {
    androidx.compose.runtime.LaunchedEffect(Unit) { Logger.ui("进入提交模式页", "Compose") }
}
