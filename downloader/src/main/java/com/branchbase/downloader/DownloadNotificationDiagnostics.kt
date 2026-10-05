package com.branchbase.downloader

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** 只记录状态变化；进度刷新不刷屏，系统查询至多每两秒一次。 */
internal class DownloadNotificationDiagnostics(
    context: Context,
    private val log: (String) -> Unit,
    scope: CoroutineScope,
) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val compatManager = NotificationManagerCompat.from(context)
    private val changes = NotificationDiagnosticChanges()
    private var lastQueryMs: Long? = null

    private sealed interface Event {
        data class Published(val notification: Notification) : Event
        data class Failed(val error: Throwable) : Event
        data object Removed : Event
    }

    private val events = Channel<Event>(32, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        scope.launch {
            for (event in events) {
                when (event) {
                    is Event.Published -> inspect(event.notification)
                    is Event.Failed -> emit("publish", "下载通知提交失败：${event.error.javaClass.simpleName}；本次未确认发布成功")
                    Event.Removed -> reset()
                }
            }
        }
    }

    fun published(notification: Notification) {
        events.trySend(Event.Published(notification))
    }

    fun failed(error: Throwable) {
        events.trySend(Event.Failed(error))
    }

    fun removed() {
        events.trySend(Event.Removed)
    }

    fun close() {
        events.close()
    }

    private fun inspect(notification: Notification) {
        emit("publish", "下载通知已提交前台服务；提交成功不代表已显示流体云")
        val now = SystemClock.elapsedRealtime()
        if (lastQueryMs?.let { now - it < 2_000L } == true) return
        lastQueryMs = now
        runCatching {
            val enabled = manager?.areNotificationsEnabled() == true
            val importance = if (Build.VERSION.SDK_INT >= 26) {
                manager?.getNotificationChannel(DownloadNotifications.CHANNEL_ID)?.importance
            } else null
            emit("channel", "通知权限=${yesNo(enabled)}，下载渠道重要度=${importance ?: "未知"}")
            if (Build.VERSION.SDK_INT < 36) {
                emit("eligibility", "Android API=${Build.VERSION.SDK_INT}，不支持 Android 16 通用实时更新，使用普通下载通知")
                return@runCatching
            }
            val requested = notification.extras.getBoolean(NotificationCompat.EXTRA_REQUEST_PROMOTED_ONGOING)
            val allowed = compatManager.canPostPromotedNotifications()
            val eligible = NotificationCompat.hasPromotableCharacteristics(notification)
            emit("eligibility", "实时更新：已请求提升=${yesNo(requested)}，系统允许提升=${yesNo(allowed)}，通知符合提升条件=${yesNo(eligible)}")
            if (!allowed) {
                emit("guidance", "系统未允许实时更新，请检查应用通知设置中的实时活动/流体云开关；普通通知与下载继续")
            } else {
                changes.clear("guidance")
            }
            val active = manager?.activeNotifications?.firstOrNull {
                it.id == DownloadNotifications.FOREGROUND_ID && it.tag == null
            }
            val result = when {
                active == null -> "系统活动通知中暂未查到下载通知（可能尚未处理提交）"
                active.notification.flags and Notification.FLAG_PROMOTED_ONGOING != 0 ->
                    "系统返回已提升标志；流体云最终显示形态由系统决定"
                else -> "系统当前未返回提升标志；不等同于永久拒绝，后续进度刷新会继续检查"
            }
            emit("system", result)
            changes.clear("queryFailure")
        }.onFailure {
            emit("queryFailure", "下载通知诊断查询失败：${it.javaClass.simpleName}；不影响下载")
        }
    }

    private fun reset() {
        emit("publish", "下载前台通知已撤下，实时更新结束")
        lastQueryMs = null
        changes.clear("system")
        changes.clear("eligibility")
    }

    private fun emit(key: String, message: String) {
        if (changes.changed(key, message)) runCatching { log(message) }
    }

    private fun yesNo(value: Boolean): String = if (value) "是" else "否"
}

/** 不持有任务或网络信息，只保留有限的诊断状态。 */
internal class NotificationDiagnosticChanges {
    private val states = mutableMapOf<String, String>()

    fun changed(key: String, message: String): Boolean = states.put(key, message) != message

    fun clear(key: String) {
        states.remove(key)
    }
}
