package com.branchbase.ui.decision

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.ui.theme.iconTap
import com.branchbase.ui.theme.Primer

/**
 * 决策页面通用组件（决策页四要素 + 事实与决策分离）。
 */

/** 选项标签（推荐/危险） */
enum class OptionTag { RECOMMENDED, DANGER, NONE }

/**
 * 决策选项行：圆形单选 + 标题 + 描述 + 标签。
 * 选中的推荐项绿色高亮、危险项红色高亮（对齐设计原型 .opt.sel / .opt.sel.warn）。
 */
@Composable
fun DecisionOptionRow(
    title: String,
    desc: String,
    selected: Boolean,
    tag: OptionTag = OptionTag.NONE,
    onSelect: () -> Unit,
) {
    val bg = when {
        selected && tag == OptionTag.DANGER -> Primer.DangerSurfaceSoft
        selected -> Primer.SuccessSurface
        else -> Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable { onSelect() }
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 1.dp)
                .size(18.dp)
                .clip(CircleShape)
                .border(
                    width = 2.dp,
                    color = when {
                        selected && tag == OptionTag.DANGER -> Primer.Red500
                        selected -> Primer.Green500
                        else -> Primer.Border
                    },
                    shape = CircleShape,
                )
                .then(
                    if (selected) Modifier.background(if (tag == OptionTag.DANGER) Primer.Red500 else Primer.Green500)
                    else Modifier
                ),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
                when (tag) {
                    OptionTag.RECOMMENDED -> OptionTagChip("推荐", Primer.Green500, Color(0xEAF9F0))
                    OptionTag.DANGER -> OptionTagChip("危险", Primer.Red500, Primer.DangerSurface)
                    OptionTag.NONE -> Unit
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(desc, fontSize = 12.sp, color = Primer.TextTertiary, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun OptionTagChip(text: String, fg: Color, bg: Color) {
    Text(
        text,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = fg,
        modifier = Modifier
            .padding(start = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/**
 * 危险操作二次确认卡（勾选确认，对齐原型 .confirm）。
 * 仅当用户勾选后，外部按钮才可启用。
 */
@Composable
fun DangerConfirmCard(
    title: String = "二次确认 · 不可恢复",
    description: String,
    confirmLabel: String,
    confirmed: Boolean,
    onToggle: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Primer.DangerSurfaceSoft)
            .border(1.dp, Primer.Red500, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Text(title, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = Primer.Red500)
        Spacer(Modifier.height(4.dp))
        Text(description, fontSize = 12.sp, color = Primer.TextTertiary, lineHeight = 17.sp)
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.clickable { onToggle() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(18.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .border(2.dp, if (confirmed) Primer.Red500 else Primer.Border, RoundedCornerShape(4.dp))
                    .then(if (confirmed) Modifier.background(Primer.Red500) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                if (confirmed) Text("✓", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(8.dp))
            Text(confirmLabel, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
        }
    }
}

/** 事实区卡片：标题 + 内容（只读事实展示，对齐 §3.4）。 */
@Composable
fun FactCard(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, Primer.Border, RoundedCornerShape(8.dp)),
    ) {
        Text(
            title,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Primer.TextSecondary,
            modifier = Modifier
                .fillMaxWidth()
                .background(Primer.Gray150)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
        content()
    }
}

/** 事实区单行（等宽路径 + 右值）。 */
@Composable
fun FactRow(path: String, right: String = "", rightColor: Color = Primer.TextTertiary, mono: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            path,
            fontSize = 12.sp,
            color = Primer.TextSecondary,
            fontFamily = if (mono) FontFamily.Monospace else null,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (right.isNotBlank()) {
            Text(right, fontSize = 11.sp, color = rightColor)
        }
    }
}

/** 说明文字（浅灰提示）。 */
@Composable
fun DecisionNote(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        color = Primer.TextTertiary,
        lineHeight = 18.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

/**
 * 决策页通用壳：状态栏留白 + 头部（返回/标题/副标题）+ 滚动内容 + 底部按钮区。
 * 决策按钮固定底部（事实与决策分离），内容区可滚动。
 */
@Composable
fun DecisionScreenShell(
    title: String,
    subtitle: String = "",
    onBack: () -> Unit,
    bottom: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Primer.BackgroundPrimary)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // 头部
        Row(
            Modifier
                .fillMaxWidth()
                .background(Primer.BackgroundPrimary)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                "返回",
                tint = Primer.IconPrimary,
                modifier = Modifier.size(24.dp).iconTap { onBack() },
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
                if (subtitle.isNotBlank()) {
                    Text(subtitle, fontSize = 11.sp, color = Primer.TextTertiary)
                }
            }
        }
        // 滚动内容
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            content()
        }
        // 底部按钮区
        Row(
            Modifier
                .fillMaxWidth()
                .background(Primer.BackgroundPrimary)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            bottom()
        }
    }
}
