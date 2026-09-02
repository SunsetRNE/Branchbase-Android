package com.branchbase.ui.profile

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.ui.theme.Primer

/**
 * 提交模式统一封装（对齐 commit-mode-decision-tree.md）。
 *
 * - `commitMode` / `saveCommitMode`：统一读取/保存（消除各处重复 prefs 读取）。
 * - `CommitModePickerDialog`：可复用「提交模式」选择弹窗，供「提交时」（跳过后的延迟决定）使用。
 */

/** 读取当前提交模式（null = 未配置） */
internal fun commitMode(context: Context): CommitMode? =
    context.getSharedPreferences("branchbase", Context.MODE_PRIVATE)
        .getString(KEY_COMMIT_MODE, null)
        ?.let { name -> runCatching { CommitMode.valueOf(name) }.getOrNull() }

/** 保存提交模式（固化到本地配置） */
internal fun saveCommitMode(context: Context, mode: CommitMode) {
    context.getSharedPreferences("branchbase", Context.MODE_PRIVATE)
        .edit().putString(KEY_COMMIT_MODE, mode.name).apply()
}

/** 可复用「提交模式」选择弹窗（提交时用，跳过后的延迟决定） */
@Composable
internal fun CommitModePickerDialog(
    onDismiss: () -> Unit,
    onConfirm: (CommitMode) -> Unit,
) {
    var selected by remember { mutableStateOf<CommitMode?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择提交模式", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary) },
        text = {
            Column {
                CommitMode.entries.forEach { m ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (selected == m) Color(0xFFF0FFF4) else Color.Transparent)
                            .clickable { selected = m }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .border(2.dp, if (selected == m) Primer.Green500 else Primer.Border, CircleShape)
                                .background(if (selected == m) Primer.Green500 else Color.Transparent),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(m.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { selected?.let(onConfirm) }, enabled = selected != null) {
                Text("确定")
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
}