package com.branchbase.ui.notification

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.branchbase.downloader.NotificationPermission
import com.branchbase.ui.theme.Primer

/**
 * 系统通知权限（API 33+ 的 `POST_NOTIFICATIONS`）与通知总开关的 Compose 侧状态。
 *
 * 权限判定本身在 `:downloader` 模块里（[NotificationPermission]，下载通知与通知板块共用同一份），
 * 这里只补三样 UI 需要的东西：
 * 1. **授权结果回流**：`rememberLauncherForActivityResult` 拿到用户选择后立刻刷新状态；
 * 2. **从系统设置页返回时刷新**：用户可能在系统设置里开了/关了通知，不重新读就会显示旧状态；
 * 3. **两种「没通知」分开表达**（见 [label] / [hint]）：
 *    - 权限没给 → 还能弹系统授权框；
 *    - 权限给了但通知被关 → 只能去系统设置，再点「申请」是不会有反应的。
 */
class SystemNotificationState(
    /** 权限与总开关都放行 = 真的能弹出通知。 */
    val granted: Boolean,
    /** 还能弹系统授权框（false = 只能去设置页）。 */
    val canRequest: Boolean,
    val request: () -> Unit,
    val openSettings: () -> Unit,
) {
    val label: String get() = if (granted) "已开启" else "未开启"

    val hint: String
        get() = when {
            granted -> "系统通知已开启：下载进度、下载完成提醒会出现在通知栏。"
            canRequest -> "还没有授予通知权限。开启后，下载进度与完成提醒才会显示在通知栏（不影响下载本身）。"
            else -> "通知已在系统里被关闭（权限可能仍是授予状态）。请到系统设置里重新打开。"
        }
}

/** 观察系统通知状态；返回的 [SystemNotificationState.request] 会自动在「申请」与「去设置」之间选择。 */
@Composable
fun rememberSystemNotificationState(): SystemNotificationState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(NotificationPermission.isGranted(context) && NotificationPermission.notificationsEnabled(context))
    }

    fun refresh() {
        granted = NotificationPermission.isGranted(context) && NotificationPermission.notificationsEnabled(context)
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    // 从系统设置页返回（ON_RESUME）时重新读一次，否则用户开了通知回来还是「未开启」
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return SystemNotificationState(
        granted = granted,
        canRequest = NotificationPermission.canRequest(context),
        request = {
            if (NotificationPermission.canRequest(context)) {
                runCatching { launcher.launch(NotificationPermission.PERMISSION) }
            } else {
                runCatching { context.startActivity(NotificationPermission.settingsIntent(context)) }
            }
        },
        openSettings = { runCatching { context.startActivity(NotificationPermission.settingsIntent(context)) } },
    )
}

/** 通知页顶部的可关闭横幅（只在真的收不到通知时出现）。 */
@Composable
fun NotificationPermissionBanner(
    state: SystemNotificationState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Primer.InfoSurfaceSoft)
            .border(1.dp, Primer.Blue500.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Notifications, "系统通知", tint = Primer.Blue500, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("通知未开启", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
            Text(
                "开启后下载进度与完成提醒才会出现在通知栏（不影响下载本身）。",
                fontSize = 11.5.sp,
                color = Primer.TextTertiary,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            if (state.canRequest) "开启" else "去设置",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Primer.Blue500,
            modifier = Modifier.clickable { state.request() },
        )
        Spacer(Modifier.width(12.dp))
        Text(
            "不再提示",
            fontSize = 11.5.sp,
            color = Primer.TextTertiary,
            modifier = Modifier.clickable { onDismiss() },
        )
    }
}

// ── 横幅的「不再提示」（只影响通知页横幅，不影响通知设置页里的入口） ──

private const val PREFS = "branchbase"
private const val KEY_BANNER_DISMISSED = "notif_permission_banner_dismissed"

fun isNotificationBannerDismissed(context: Context): Boolean =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_BANNER_DISMISSED, false)

fun dismissNotificationBanner(context: Context) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putBoolean(KEY_BANNER_DISMISSED, true).apply()
}
