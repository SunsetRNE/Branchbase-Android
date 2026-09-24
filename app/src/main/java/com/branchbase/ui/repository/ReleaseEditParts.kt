package com.branchbase.ui.repository

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.R
import com.branchbase.core.AttachmentStatus
import com.branchbase.core.DraftAttachment
import com.branchbase.downloader.DownloadPaths
import com.branchbase.ui.theme.ElementMotion
import com.branchbase.ui.theme.Primer
import com.branchbase.ui.theme.iconTap
import java.io.File

/**
 * 发布编辑页的零件（第二轮设计）：类型分段 + 合并的标签/标题/分支行 + 附件行 + 分组头。
 *
 * 页面骨架只有一句话：**左侧定宽窄槽 + 右侧内容**。表单标签、附件图标、行号槽都落在同一条
 * 竖直轴上，槽宽恒定 → 内容左边界永不跳（`docs/specs/screens-design.md` §3 的既有约束）。
 *
 * 这一版把上一版的三个「一块一块」压掉了：标签与标题并成一行、目标分支变成行尾 chip、
 * 「设为最新发布」从一张卡片变成类型行里的小开关 —— 详见
 * `design/release-redesign/README.md`「三、设计 ②」。
 */

/** 发布的三档性质。**互斥**，对应 GitHub 的 draft / prerelease / latest 三种归属。 */
internal enum class ReleaseType(val label: String, val hint: String) {
    STABLE("正式发布", "所有人可见，并占据仓库的「最新发布」"),
    PRERELEASE("预发布", "所有人可见，但不会被标为「最新发布」"),
    DRAFT("草稿", "仅自己和有写权限的人可见，尚未公开"),
}

/**
 * 版本类型：轨道式三段 + （仅正式发布时）「设为最新」小开关。
 *
 * 用整块 `Gray150` 做轨道、选中项是一个**滑动**的白胶囊，而不是三个各自描边的按钮 ——
 * 描边按钮并排会得到一列竖线，看着像三个独立控件，读不出「三选一」。
 */
@Composable
internal fun ReleaseTypeRow(
    type: ReleaseType,
    onType: (ReleaseType) -> Unit,
    latest: Boolean,
    onLatest: (Boolean) -> Unit,
) {
    val entries = ReleaseType.entries
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(9.dp))
                .background(Primer.Gray150)
                .padding(2.dp),
        ) {
            val gap = 2.dp
            val itemWidth = (maxWidth - gap * (entries.size - 1)) / entries.size
            val index = entries.indexOf(type).coerceAtLeast(0)
            val offset by animateDpAsState(
                targetValue = (itemWidth + gap) * index,
                animationSpec = tween(ElementMotion.COLOR_MS),
                label = "release-type-pill",
            )

            // 指示器先画、按钮后画：按钮的点击区域盖在胶囊上
            Box(
                Modifier
                    .offset(x = offset)
                    .width(itemWidth)
                    .height(28.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Primer.BackgroundPrimary),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                entries.forEach { entry ->
                    val selected = entry == type
                    Box(
                        Modifier
                            .weight(1f)
                            .height(28.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .clickable { onType(entry) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            entry.label,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) Primer.TextPrimary else Primer.TextSecondary,
                        )
                    }
                }
            }
        }

        // 草稿 / 预发布不占 Latest（官方限制），所以这个开关只在该有意义时出现
        if (type == ReleaseType.STABLE) {
            Spacer(Modifier.width(8.dp))
            Row(
                Modifier.clickable { onLatest(!latest) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.action_set_as_latest), fontSize = 10.5.sp, color = Primer.TextTertiary)
                Spacer(Modifier.width(5.dp))
                MiniSwitch(latest)
            }
        }
    }
}

/** 34×20 的小开关（「设为最新」用）。 */
@Composable
private fun MiniSwitch(checked: Boolean) {
    Box(
        Modifier
            .width(34.dp)
            .height(20.dp)
            .clip(CircleShape)
            .background(if (checked) Primer.Blue500 else Primer.Gray300),
        contentAlignment = Alignment.CenterStart,
    ) {
        val shift by animateDpAsState(
            targetValue = if (checked) 16.dp else 2.dp,
            animationSpec = tween(ElementMotion.COLOR_MS),
            label = "mini-switch",
        )
        Box(
            Modifier
                .offset(x = shift)
                .size(16.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

/**
 * 一行装下「标签 + 标题 + 目标分支」。
 *
 * 上一版是三个各自「标签在上、底线在下」的字段（每个 54dp），光标签行就吃掉 3×17dp。
 * 这里把它们并成一行：标签是一个带 tag 图标的等宽 chip（它本身就长得像输入框），标题跟着它，
 * 目标分支是行尾只读 chip（编辑已有发布时也不能改，GitHub 的 PATCH 不接受 target_commitish）。
 */
@Composable
internal fun ReleaseTagTitleRow(
    tag: String,
    onTag: (String) -> Unit,
    title: String,
    onTitle: (String) -> Unit,
    branch: String,
) {
    var focused by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(40.dp).onFocusChanged { focused = it.isFocused },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Primer.Gray150)
                    .padding(horizontal = 7.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Sell,
                    contentDescription = stringResource(R.string.label_tag),
                    tint = Primer.IconSecondary,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(5.dp))
                BasicTextField(
                    value = tag,
                    onValueChange = onTag,
                    singleLine = true,
                    textStyle = TextStyle(
                        fontSize = 12.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Primer.TextPrimary,
                    ),
                    cursorBrush = SolidColor(Primer.Blue500),
                    modifier = Modifier.width(68.dp),
                    decorationBox = { inner ->
                        Box {
                            if (tag.isEmpty()) {
                                Text(
                                    "v1.0.13",
                                    fontSize = 12.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Primer.TextTertiary,
                                )
                            }
                            inner()
                        }
                    },
                )
            }

            Spacer(Modifier.width(8.dp))

            BasicTextField(
                value = title,
                onValueChange = onTitle,
                singleLine = true,
                textStyle = TextStyle(fontSize = 13.5.sp, color = Primer.TextPrimary),
                cursorBrush = SolidColor(Primer.Blue500),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box {
                        if (title.isEmpty()) {
                            Text(stringResource(R.string.label_title_optional_tag), fontSize = 13.5.sp, color = Primer.TextTertiary)
                        }
                        inner()
                    }
                },
            )

            if (branch.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Row(
                    Modifier.widthIn(max = 76.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.AccountTree,
                        contentDescription = stringResource(R.string.label_target_branch_name),
                        tint = Primer.IconSecondary,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        branch,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Primer.TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (focused) 1.5.dp else 1.dp)
                .background(if (focused) Primer.Blue500 else Primer.Gray200),
        )
    }
}

/** 分组头：标题 +（计数）+ 右侧动作。省掉原来每个分组单独一行的统计。 */
@Composable
internal fun ReleaseGroupHeader(
    title: String,
    counter: String? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextTertiary)
        if (!counter.isNullOrBlank()) {
            Text("  ·  $counter", fontSize = 11.sp, color = Primer.TextTertiary)
        }
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

/** 分组头右侧的文字动作（导入 / 生成说明 / 预览 / 全部保留 …）。 */
@Composable
internal fun ReleaseHeaderAction(
    label: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val color = when {
        !enabled -> Primer.TextTertiary
        danger -> Primer.DangerText
        else -> Primer.Blue500
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(label, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

/**
 * 顶栏主操作：实心胶囊。文字不跟状态走（草稿态写「存为草稿」），
 * 否则用户点下去才知道会发生什么。
 */
@Composable
internal fun ReleasePrimaryAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (enabled) Primer.Blue500 else Primer.Gray300)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 13.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
        )
    }
}

/** 附件行的图标槽（24dp，按类型给底与前景色）。 */
@Composable
private fun AttachmentIcon(name: String) {
    val kind = name.substringAfterLast('.', "").lowercase()
    val (icon, bg, fg) = when (kind) {
        "apk" -> Triple(Icons.Filled.Android, Primer.SuccessSurface, Primer.SuccessTextStrong)
        "zip" -> Triple(Icons.Filled.Archive, Primer.WarningSurface, Primer.WarningTextStrong)
        "txt", "md", "log", "json" -> Triple(Icons.Filled.Description, Primer.InfoSurface, Primer.AccentText)
        else -> Triple(Icons.AutoMirrored.Filled.InsertDriveFile, Primer.Gray150, Primer.IconSecondary)
    }
    Box(
        Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
    }
}

/**
 * 一行附件：图标槽 + 文件名（等宽）+「大小 · 状态」+ 一个主动作 + ✕ 移除。
 *
 * 任何时刻**只有一个主动作**（上传 / 取消 / 重试），移除是图标而不是第二个文字按钮 ——
 * 与详情页 `AssetRow` 的既有约定一致（`:744-755`）。
 */
@Composable
internal fun ReleaseAttachmentRow(
    attachment: DraftAttachment,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AttachmentIcon(attachment.name)
            Spacer(Modifier.width(9.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    attachment.name,
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Primer.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(1.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (dot, label) = when (attachment.status) {
                        AttachmentStatus.UPLOADING -> Primer.Blue500 to stringResource(R.string.state_uploading)
                        AttachmentStatus.DONE -> Primer.Green500 to stringResource(R.string.state_uploaded)
                        AttachmentStatus.FAILED -> Primer.Red500 to (attachment.error ?: stringResource(R.string.error_upload_failed_short))
                        AttachmentStatus.READY -> Primer.Gray300 to
                            if (attachment.reference) stringResource(R.string.state_referenced_pending) else stringResource(R.string.state_pending_upload)
                    }
                    Box(Modifier.size(5.dp).clip(CircleShape).background(dot))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "${DownloadPaths.formatBytes(attachment.size)} · $label",
                        fontSize = 10.sp,
                        color = when (attachment.status) {
                            AttachmentStatus.DONE -> Primer.SuccessTextStrong
                            AttachmentStatus.FAILED -> Primer.DangerText
                            else -> Primer.TextTertiary
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.width(6.dp))

            // 上传只有「发布 / 保存」一个入口（资产必须先挂到 release 上，见 ReleaseEditScreen 的说明），
            // 所以这里只给「失败重试」与完成态，不再放一个会和两步流程打架的「上传」
            when (attachment.status) {
                AttachmentStatus.FAILED -> ReleaseHeaderAction(stringResource(R.string.action_retry), onClick = onRetry)
                AttachmentStatus.DONE -> Icon(
                    Icons.Filled.Check,
                    contentDescription = stringResource(R.string.state_uploaded),
                    tint = Primer.SuccessTextStrong,
                    modifier = Modifier.size(14.dp),
                )
                else -> Unit
            }

            Spacer(Modifier.width(2.dp))
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.action_remove),
                tint = Primer.IconSecondary,
                modifier = Modifier.size(24.dp).iconTap { onRemove() },
            )
        }

        // 上传中：2dp 的不确定进度条（Rust 侧目前是一次阻塞调用，拿不到百分比）
        if (attachment.status == AttachmentStatus.UPLOADING) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = Primer.Blue500,
                trackColor = Primer.Gray150,
            )
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** 空态：一个虚线槽 + 文案 + 动作，槽位与附件行对齐（看得出一条竖轴）。 */
@Composable
internal fun ReleaseAttachmentEmpty(onImport: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable { onImport() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, Primer.Gray300, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = Primer.IconSecondary, modifier = Modifier.size(13.dp))
        }
        Spacer(Modifier.width(9.dp))
        Text(stringResource(R.string.action_import_any_file), fontSize = 12.sp, color = Primer.TextTertiary, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.action_choose_file), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = Primer.Blue500)
    }
}

/** 行间发丝线（比大留白省高度，结构还更清楚）。 */
@Composable
internal fun ReleaseHairline() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Primer.Gray150))
}

/**
 * 状态条：校验失败 / 「草稿已自动保存」。
 *
 * 默认**不占高度**（[text] 为空时整行不渲染）：这一屏的每一 dp 都算过（见压缩预算），
 * 为一句偶尔出现的话常驻一行是不划算的。
 */
@Composable
internal fun ReleaseStatusNote(text: String?, bad: Boolean) {
    if (text.isNullOrBlank()) return
    Row(
        Modifier.fillMaxWidth().padding(top = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (bad) Icons.Filled.Info else Icons.Filled.Check,
            contentDescription = null,
            tint = if (bad) Primer.DangerText else Primer.SuccessTextStrong,
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            fontSize = 10.5.sp,
            color = if (bad) Primer.DangerText else Primer.TextTertiary,
        )
    }
}

/** 「存在哪」的说明动作（附件分组头右侧的 ⓘ）。 */
@Composable
internal fun ReleaseStoreInfoAction(onClick: () -> Unit) {
    Icon(
        Icons.Filled.Info,
        contentDescription = stringResource(R.string.faq_imported_files_location),
        tint = Primer.IconSecondary,
        modifier = Modifier.size(20.dp).iconTap { onClick() },
    )
}

/** 附件落盘文件：草稿里存的是绝对路径。 */
internal fun DraftAttachment.file(): File = File(path)
