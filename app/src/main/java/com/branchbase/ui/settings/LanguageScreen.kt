package com.branchbase.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.branchbase.R
import com.branchbase.ui.log.Logger
import com.branchbase.ui.profile.ModeOptionRow
import com.branchbase.ui.profile.SubPageHeader
import com.branchbase.ui.theme.Primer

/**
 * 语言（设置 → 外观 → 语言）—— 二级单选页。
 *
 * ## 这一页存在的条件
 *
 * 只有 [languagePickerAvailable] 为真时才可能进来：API 33+ 且清单里至少两种语言。
 * 条件不满足时设置页**整行不出现**（不是置灰）—— 规范 §3.2.1 对「仓库凭据」用的是同一条道理：
 * 禁用行必须给「怎么才能开」的出路，而这里的出路是换一台新系统的手机，设置页代办不了。
 *
 * ## 选项从哪来
 *
 * 不写死任何语言列表：清单来自 `android:localeConfig`（AGP 按 `values-*` 目录生成，
 * 见 `app/build.gradle.kts`），语言名称来自 `Locale.getDisplayName`。
 * 所以**加一种语言 = 加一个 `values-xx/` 目录**，这一页不用改一行代码。
 *
 * ## 为什么切换不重建 Activity
 *
 * `MainActivity` 声明了 `configChanges="locale|layoutDirection"`，框架把新 Configuration
 * 应用到 Activity 的 Resources，Compose 的 `LocalConfiguration` 跟着更新 ——
 * 整棵树在同一帧里用新语言重组。不会白闪，也不会丢掉导航记忆、各页取数状态、WebView 滚动位置。
 */
@Composable
fun LanguageScreen(onBack: () -> Unit) {
    LaunchedEffect(Unit) { Logger.ui("进入语言设置页", "Compose") }
    val context = LocalContext.current

    // 当前**界面**语言：用来给每种语言算「用你现在看得懂的话怎么说」（见 languageOptionDesc）。
    // 它跟着配置走，所以切换语言后这个值会变，说明文案会跟着换成新语言 —— 这正是想要的。
    val uiLocale = LocalConfiguration.current.locales[0]

    // 清单与当前值只在进入页面时读一次：两者都是系统状态，本页不订阅任何变化源
    val supported = remember { supportedAppLanguages(context) }
    var selected by remember { mutableStateOf(currentAppLanguageTag(context)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Primer.BackgroundPrimary)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        SubPageHeader(stringResource(R.string.settings_language), onBack)

        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {

            // 不写分组标签：整页只有这一组，标签会和页头重复（规范 §3.2 禁止分组名与行名重复）
            SettingsSection {
                ModeOptionRow(
                    label = stringResource(R.string.language_follow_system),
                    // 说明给的是「系统现在是什么语言」这个**增量信息**，不是复述名称
                    desc = stringResource(
                        R.string.language_follow_system_desc,
                        systemLanguage(context).getDisplayName(uiLocale),
                    ),
                    selected = selected == LANGUAGE_FOLLOW_SYSTEM,
                    divider = supported.isNotEmpty(),
                    onClick = {
                        if (setAppLanguage(context, LANGUAGE_FOLLOW_SYSTEM)) {
                            selected = LANGUAGE_FOLLOW_SYSTEM
                        }
                    },
                )

                supported.forEachIndexed { index, locale ->
                    val tag = locale.toLanguageTag()
                    ModeOptionRow(
                        // 名称用该语言的**母语自称**（English / 中文）：界面已经是看不懂的语言时，
                        // 用户也得能认出自己那一行
                        label = languageOptionName(locale),
                        // 说明用当前界面语言的**他称**；两者相同时为 null（规范 §6.2 禁止复述名称）
                        desc = languageOptionDesc(locale, uiLocale),
                        selected = selected == tag,
                        divider = index != supported.lastIndex,
                        onClick = { if (setAppLanguage(context, tag)) selected = tag },
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
