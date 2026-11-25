package com.branchbase.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.branchbase.R
import com.branchbase.ui.log.Logger
import com.branchbase.ui.profile.ModeOptionRow
import com.branchbase.ui.profile.SubPageHeader
import com.branchbase.ui.theme.Primer
import com.branchbase.ui.theme.ThemeMode
import com.branchbase.ui.theme.ThemeRuntime

/**
 * 主题（设置 → 外观 → 主题）—— **外观类的唯一入口页**。
 *
 * ## 这一页为什么存在
 *
 * 2026-11 之前的主题是设置主页上的三档分段控件、悬浮玻璃导航栏是主页上的一个开关。
 * 两者都是「外观」，却分住在主页两行里；而整套 UI 设计风格（COUI / 多皮肤，见
 * `docs/specs/coui-adoption-design.md` §11 的 D1/D2）迟早也要一个落点。如果每次都往主页加一行，主页会重新长回「设置项容器」——
 * 那正是 1.4.38 压密要收掉的东西。
 *
 * 所以这一页是**外观类设置的唯一落点**：主题模式、导航栏形态，以及后续的 UI 风格 / 皮肤，
 * 全部在这里切换，**别处不再做同类开关**（规范 §十七）。主页只留一行入口 + 当前值。
 *
 * ## 为什么是单选列表而不是分段控件
 *
 * 分段控件靠「一屏内并排可见」表达互斥；一旦档位里出现需要解释的选项（跟随系统 = 跟的是什么？），
 * 分段控件就放不下说明。这一页用 [ModeOptionRow]：每档一行，说明给的是**增量信息**
 * （「跟随系统」那一行报当前系统是浅色还是深色），而不是复述档位名（规范 §6.2）。
 *
 * ## 悬浮玻璃导航栏为什么也在这里
 *
 * 它决定底栏长什么样 —— 是「外观」而不是「导航功能」。放在这里之后，用户改外观只需要进一个页面；
 * 也避免以后每加一种底栏形态就在主页多一个开关。
 */
@Composable
fun ThemeScreen(onBack: () -> Unit) {
    LaunchedEffect(Unit) { Logger.ui("进入主题设置页", "Compose") }
    val context = LocalContext.current
    val mode by ThemeRuntime.mode.collectAsState()
    // 系统当前的深浅：只用来给「跟随系统」那一行报增量信息
    val systemDark = isSystemInDarkTheme()
    // 底栏形态是外观的一种，跟主题同页；同样只在进入时读一次盘
    var glassNavigation by remember { mutableStateOf(glassNavigationEnabled(context)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Primer.BackgroundPrimary)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        SubPageHeader(stringResource(R.string.label_theme), onBack)

        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {

            // 主题模式：三档互斥。不写分组标签会和页头「主题」读成重复（规范 §3.2），
            // 所以这一组用「主题模式」——页头是对象，组名是维度。
            SettingsSection(stringResource(R.string.label_theme_mode_group)) {
                ThemeMode.entries.forEachIndexed { index, entry ->
                    ModeOptionRow(
                        label = stringResource(entry.labelRes),
                        desc = if (entry == ThemeMode.SYSTEM) {
                            // 增量信息：跟随系统到底跟成了什么。浅色 / 深色两档不写说明（§6.2 禁止复述名称）
                            stringResource(
                                R.string.theme_follow_system_desc,
                                stringResource(if (systemDark) R.string.theme_mode_dark else R.string.theme_mode_light),
                            )
                        } else {
                            null
                        },
                        selected = mode == entry,
                        // 分隔线画在**行的顶部**：首行不画（那是卡片上沿），其余行各画一条在自己头顶
                        divider = index != 0,
                        onClick = { ThemeRuntime.set(context, entry) },
                    )
                }
            }

            // 导航栏：底栏形态。后续若出现更多形态（例如贴边 / 隐藏），扩这里的分组，
            // 不要回到设置主页加开关。
            SettingsSection(stringResource(R.string.label_navigation_group)) {
                SwitchRow(
                    icon = Icons.Filled.Tune,
                    name = stringResource(R.string.label_glass_navigation),
                    // 说明**随开关状态换一句话**（2026-11 决策）：关时讲「现在是什么」，开时讲
                    // 「已经发生什么」。比一句静态解释更容易一眼确认当前形态。
                    //
                    // 两段都在 17 字以内（16 / 17），独占整行宽、12sp 下约 204dp —— 文本区约 272dp
                    // （360 − 12×2 分组 − 16×2 行内边距 − 32 图标缩进），余量足够；
                    // `subMaxLines = 1` 只用来兜住系统字号放大，不靠缩字号换空间。
                    //
                    // 语义：这一项与主题模式**正交** —— 它是全局的底栏形态，浅色 / 深色 / 跟随系统
                    // 任意主题下都生效（`GlassNavigationRuntime` 只有一份状态），不是「深色专用皮肤」。
                    sub = stringResource(
                        if (glassNavigation) R.string.note_glass_navigation_on
                        else R.string.note_glass_navigation_off,
                    ),
                    subFullWidth = true,
                    subMaxLines = 1,
                    checked = glassNavigation,
                    onCheckedChange = {
                        glassNavigation = it
                        GlassNavigationRuntime.set(context, it)
                    },
                    divider = false,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
