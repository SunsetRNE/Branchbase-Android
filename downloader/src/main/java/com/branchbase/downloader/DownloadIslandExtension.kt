package com.branchbase.downloader

import android.content.Context
import androidx.core.app.NotificationCompat
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 「上岛」扩展点：把下载进度同时投到厂商的**灵动岛 / 实时活动**上。
 *
 * ## 为什么是「扩展点」而不是「内置三家实现」
 *
 * 三家厂商的能力都建立在**自家系统**上，而且都要白名单：
 *
 * | 厂商 | 官方形态 | 落地方式 | 没白名单时 |
 * |------|---------|---------|-----------|
 * | 谷歌 | Android 16 Live Updates（promoted ongoing） | `NotificationCompat.ProgressStyle` + `setRequestPromotedOngoing`（androidx.core ≥ 1.17） | 普通通知 |
 * | 小米 | 超级岛 / 焦点通知 | 通知 extras 里塞 `miui.focus.param`（JSON） | 普通通知 |
 * | OPPO | ColorOS 实况通知（流体云） | 无公开三方文档，需在开放平台申请后接官方 SDK | 普通通知 |
 *
 * 所以模块只做两件事：
 * 1. **留接口**（本文件）：通知构建过程中按顺序回调已注册的扩展，扩展可以往同一条通知上
 *    叠加 extras / style；
 * 2. **给默认实现**（[VendorIslandExtensions]）：能纯 extras 做到的（小米）直接做，
 *    需要新依赖的（谷歌）用反射做「依赖到位即生效」，需要厂商 SDK 的（OPPO）留注入点。
 *
 * 三条硬约束：
 * - **扩展永远不能影响下载**：注册表对每次派发都 `runCatching`，厂商 SDK 抛异常只丢这一帧；
 * - **`isAvailable` 会被高频调用**（每个进度 tick），实现必须自己缓存，别在里面做 IO；
 * - **失败要静默**：岛没显示出来是预期内的（白名单），不能弹 Toast、更不能写错误日志刷屏。
 */
interface DownloadIslandExtension {

    /** 稳定且唯一（重复 id 会覆盖旧的）。 */
    val id: String

    val vendor: IslandVendor get() = IslandVendor.CUSTOM

    /** 当前设备/系统是否具备条件（厂商判断、系统版本、权限）。实现方负责缓存。 */
    fun isAvailable(context: Context): Boolean = true

    /** 在同一条下载通知上叠加厂商需要的 extras / style（在 `build()` 之前调用）。 */
    fun decorate(context: Context, builder: NotificationCompat.Builder, state: DownloadNotificationState) {}

    /** 任务结束（成功或失败）：用于收掉厂商侧的独立卡片。 */
    fun onFinished(context: Context, state: DownloadNotificationState, ok: Boolean) {}

    /** 任务被取消 / 从列表移除：关闭对应卡片。 */
    fun onRemoved(context: Context, taskId: String) {}
}

/** 扩展归属（仅用于展示与排查，不参与逻辑分支）。 */
enum class IslandVendor { GOOGLE, OPPO, XIAOMI, CUSTOM }

/**
 * 「上岛」扩展的注册表。
 *
 * 用 `CopyOnWriteArrayList`：注册发生在 App 启动（单线程），派发在下载线程，
 * 读多写极少，读写不互相阻塞。
 *
 * 注意：**注册表是进程内的**。厂商扩展通常由 `Application.onCreate` 一次性装配
 * （见 [DownloaderConfig.islandExtensions]），第三方或未来的官方 SDK 也可以运行时注册。
 */
object DownloadIslandExtensions {

    private val installed = CopyOnWriteArrayList<DownloadIslandExtension>()

    /** 注册（同 id 覆盖，返回 true = 新增）。 */
    fun install(extension: DownloadIslandExtension): Boolean {
        uninstall(extension.id)
        return installed.add(extension)
    }

    fun uninstall(id: String): Boolean = installed.removeAll { it.id == id }

    fun all(): List<DownloadIslandExtension> = installed.toList()

    fun clear() = installed.clear()

    /** 整体替换（`install` 时用配置里的清单覆盖）。 */
    internal fun replaceAll(extensions: List<DownloadIslandExtension>) {
        clear()
        extensions.forEach { install(it) }
    }

    /** 通知构建前：让每个可用扩展往 Builder 里加东西。 */
    internal fun decorate(
        context: Context,
        builder: NotificationCompat.Builder,
        state: DownloadNotificationState,
    ) {
        for (extension in installed) {
            if (!available(context, extension)) continue
            runCatching { extension.decorate(context, builder, state) }
        }
    }

    internal fun finished(context: Context, state: DownloadNotificationState, ok: Boolean) {
        for (extension in installed) {
            if (!available(context, extension)) continue
            runCatching { extension.onFinished(context, state, ok) }
        }
    }

    internal fun removed(context: Context, taskId: String) {
        for (extension in installed) {
            runCatching { extension.onRemoved(context, taskId) }
        }
    }

    /** `isAvailable` 由外部实现，必须当成不可信代码调用（抛异常 = 不可用）。 */
    private fun available(context: Context, extension: DownloadIslandExtension): Boolean =
        runCatching { extension.isAvailable(context) }.getOrDefault(false)
}
