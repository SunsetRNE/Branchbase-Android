package com.branchbase.ui.profile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.R
import com.branchbase.ui.log.Logger
import com.branchbase.ui.theme.Primer
import com.branchbase.ui.theme.revealEnter

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
 * 动效：徽标标签切换走 `AnimatedContent`（淡入 + 纵向滑动），单选圆点在选中时做一次轻弹，
 * 抽屉内容进场走统一的 `revealEnter()`（纵向展开 + 淡入）。
 */
@Composable
fun CommitModeScreen(onBack: () -> Unit) {
    LaunchedEffectOnce()

    val context = LocalContext.current
    var mode by remember { mutableStateOf(commitMode(context)) }
    var drawerOpen by remember { mutableStateOf(false) }

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

        CommitMode.entries.forEach { m ->
            ModeOptionRow(
                // 卡片是整行，可以承载完整说明；状态位（设置页右侧值等）只用短名 m.labelRes
                label = stringResource(m.titleRes),
                desc = stringResource(m.descRes),
                selected = mode == m,
                bouncySelection = true,
                onClick = {
                    if (mode != m) {
                        mode = m
                        saveCommitMode(context, m)
                        Logger.ui("提交模式改为 ${m.logLabel}", "Compose")
                    }
                },
            )
        }

        Spacer(Modifier.height(16.dp))
    }

    if (drawerOpen) {
        CommitModeSheet(
            current = mode,
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

    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(bgColor)
            .clickable(onClick = onClick)
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
 * 内容抽屉：原页首说明 + 逐模式的展开说明。
 *
 * 抽屉本身由 `ModalBottomSheet` 提供滑入 / 滑出；内容再叠一层 `revealEnter()`，
 * 让「打开」是两段动作（面板上来 → 内容展开），而不是整块内容瞬间贴上。
 * 每个模式都回答同一个问题：**改动去了哪、什么时候该用它**。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommitModeSheet(current: CommitMode?, onDismiss: () -> Unit) {
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
                .padding(horizontal = 20.dp)
                .padding(bottom = 12.dp),
        ) {
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
    }
}

/** 进入页面的日志（保持与其它子页一致的行为）。 */
@Composable
private fun LaunchedEffectOnce() {
    androidx.compose.runtime.LaunchedEffect(Unit) { Logger.ui("进入提交模式页", "Compose") }
}
