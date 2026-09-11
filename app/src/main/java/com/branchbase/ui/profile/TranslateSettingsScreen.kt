package com.branchbase.ui.profile

import android.widget.Toast
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.ui.log.Logger
import com.branchbase.ui.repository.TranslateConfig
import com.branchbase.ui.repository.TranslateSettings
import com.branchbase.ui.repository.Translator
import com.branchbase.ui.theme.Primer

/**
 * 「沉浸式翻译」控制面板（设置 → 沉浸式翻译）。
 *
 * ## 这个功能是什么
 *
 * 在**正文页**（自述文件 README / Issue / PR / 发布说明，即 WebView 渲染的那些页面）里，
 * 把每个段落翻成目标语言并插在原文下面：原文在上、译文在下，可随时切回只看原文。
 * 页内右下角还有一枚「译」按钮，随手可开（不依赖本页设置）。
 *
 * ## 面板上的四项为什么是这四项
 *
 * - **自动翻译**：默认关。开了就是「一进正文页就发请求」，费额度也拖首屏；
 *   默认交给页内按钮手动触发，这里只做「要不要自动化」的开关；
 * - **目标语言**：中英两向（源语言由目标反推），覆盖「读英文仓库」与「给英文使用者看中文文档」；
 * - **显示方式**：对照 / 仅译文。两者共用同一份译文缓存，切换不重翻；
 * - **清空译文缓存**：译文按段落缓存在内存里（进程内），出问题时可以一键从干净状态重来。
 */
@Composable
fun TranslateSettingsScreen(onBack: () -> Unit) {
    LaunchedEffect(Unit) { Logger.ui("进入沉浸式翻译设置页", "Compose") }
    val context = LocalContext.current
    var config by remember { mutableStateOf(TranslateSettings.read(context)) }
    var cached by remember { mutableStateOf(Translator.cachedCount()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Primer.BackgroundPrimary)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        SubPageHeader("沉浸式翻译", onBack)

        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {

            // ── 自动翻译开关 ──
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("自动翻译正文", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "开启后进入自述文件等正文页会自动开始翻译；关闭时在页面右下角点「译」按钮手动触发。",
                        fontSize = 12.sp,
                        color = Primer.TextTertiary,
                        lineHeight = 17.sp,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = config.enabled,
                    onCheckedChange = {
                        config = config.copy(enabled = it)
                        TranslateSettings.setEnabled(context, it)
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = Primer.Blue500),
                )
            }

            SectionTitle("目标语言")
            ModeOptionRow(
                label = "中文（简体）",
                desc = "把英文原文翻成中文对照显示（默认，读英文仓库用）",
                selected = config.target == TranslateConfig.ZH,
            ) {
                config = config.copy(target = TranslateConfig.ZH)
                TranslateSettings.setTarget(context, TranslateConfig.ZH)
            }
            ModeOptionRow(
                label = "English",
                desc = "把中文原文翻成英文对照显示（给英文读者看中文文档）",
                selected = config.target == TranslateConfig.EN,
            ) {
                config = config.copy(target = TranslateConfig.EN)
                TranslateSettings.setTarget(context, TranslateConfig.EN)
            }

            SectionTitle("显示方式")
            ModeOptionRow(
                label = "原文 + 译文对照",
                desc = "译文插在每段原文下方，原文保持不动（推荐）",
                selected = config.dual,
            ) {
                config = config.copy(dual = true)
                TranslateSettings.setDual(context, true)
            }
            ModeOptionRow(
                label = "仅显示译文",
                desc = "隐藏原文，只留译文（小屏快速浏览用，可随时切回对照）",
                selected = !config.dual,
            ) {
                config = config.copy(dual = false)
                TranslateSettings.setDual(context, false)
            }

            SectionTitle("译文缓存")
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        Translator.clearCache()
                        cached = Translator.cachedCount()
                        Toast.makeText(context, "已清空译文缓存", Toast.LENGTH_SHORT).show()
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("清空译文缓存", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "已缓存 $cached 段。译文缓存在内存中，用于避免同一段落重复翻译。",
                        fontSize = 12.sp,
                        color = Primer.TextTertiary,
                    )
                }
                Text("清空", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Primer.Blue500)
            }

            SectionTitle("说明")
            Text(
                "• 生效范围：自述文件（README）、Issue、Pull Request、发布说明等正文页；" +
                    "页内右下角的「译」按钮可随时开关，与本页设置相互独立。\n" +
                    "• 翻译服务：默认使用 MyMemory 的公开接口（无需配置，匿名额度约 5000 词/天）；" +
                    "额度用尽时会提示「无法完成翻译」，不会插入错误内容。\n" +
                    "• 隐私：只把**正文段落文本**发往翻译服务，不包含 token、仓库私有信息或账号数据。",
                fontSize = 12.sp,
                color = Primer.TextTertiary,
                lineHeight = 19.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 分组标题（与设置页其它分组同款样式）。 */
@Composable
private fun SectionTitle(title: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
    ) {
        Text(
            title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Primer.TextTertiary,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Primer.Gray100)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
