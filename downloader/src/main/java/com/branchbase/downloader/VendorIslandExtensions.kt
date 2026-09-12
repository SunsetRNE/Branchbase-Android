package com.branchbase.downloader

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.app.NotificationCompat
import java.util.Locale

/**
 * 厂商官方 SDK 的**注入点**。
 *
 * `:downloader` 不引任何厂商 SDK（引了就等于把下载模块绑死在某一家的系统上）。
 * OPPO 这类「必须用官方 SDK + 必须申请白名单」的能力，由 `:app`（或未来的插件）
 * 从外面把 SDK 调用注进来：
 *
 * ```kotlin
 * DownloaderRuntime.registerIslandExtension(
 *     OppoLiveAlertExtension(attacher = { ctx, builder, state -> OPPOSDK.attach(...) }),
 * )
 * ```
 *
 * @return true = 已接管这条通知（内置兜底路径不再执行）；false = 交回内置路径。
 */
fun interface IslandAttacher {
    fun attach(
        context: Context,
        builder: NotificationCompat.Builder,
        state: DownloadNotificationState,
    ): Boolean
}

/**
 * 三家厂商的**内置**「上岛」实现。
 *
 * 共同约定：
 * - 只在本厂商设备上 `isAvailable`（判断结果缓存，`isAvailable` 会被每个进度 tick 调用）；
 * - 一律只改**同一条**通知（extras / style），不另发一条 —— 否则用户会在通知栏看到两条；
 * - 任何一步失败都静默（岛不显示是白名单问题，不是错误）。
 */
object VendorIslandExtensions {

    /**
     * 默认装配清单。App 把它塞进 [DownloaderConfig.islandExtensions] 即可；
     * 不想要某一家的行为，从这里删掉一行就行。
     */
    fun defaults(): List<DownloadIslandExtension> = listOf(
        GoogleLiveUpdateExtension(),
        XiaomiSuperIslandExtension(),
        OppoLiveAlertExtension(),
    )
}

// ───────────────────────── 谷歌：Android 16 Live Updates ─────────────────────────

/**
 * Android 16 的「实时更新」（promoted ongoing notification）。
 *
 * 官方做法是 `NotificationCompat.ProgressStyle` + `Builder.setRequestPromotedOngoing(true)`
 * —— 这两个 API 从 **androidx.core 1.17.0** 才有，而本模块当前依赖 1.10.1。
 * 所以这里用**反射**调用：
 * - 依赖哪天升上去，不需要改这个文件就自动生效（`isAvailable` 会开始返回 true）；
 * - 现在则静默跳过，通知退回普通形态（这正是预期行为，不是降级 bug）。
 *
 * 另外清单里必须声明 `POST_PROMOTED_NOTIFICATIONS`，否则 Android 16 上系统不会把通知提升为实时更新。
 */
class GoogleLiveUpdateExtension(
    var attacher: IslandAttacher? = null,
) : DownloadIslandExtension {

    override val id: String get() = ID
    override val vendor: IslandVendor get() = IslandVendor.GOOGLE

    private val progressStylePresent: Boolean by lazy {
        runCatching { Class.forName(PROGRESS_STYLE_CLASS) }.isSuccess
    }

    @Volatile
    private var cachedSupport: Boolean? = null

    override fun isAvailable(context: Context): Boolean = cachedSupport ?: (
        Build.VERSION.SDK_INT >= API_36 && progressStylePresent
        ).also { cachedSupport = it }

    override fun decorate(
        context: Context,
        builder: NotificationCompat.Builder,
        state: DownloadNotificationState,
    ) {
        val attached = attacher?.let {
            runCatching { it.attach(context, builder, state) }.getOrDefault(false)
        } ?: false
        if (attached) return
        runCatching { applyProgressStyle(builder, state) }
    }

    private fun applyProgressStyle(builder: NotificationCompat.Builder, state: DownloadNotificationState) {
        val styleClass = Class.forName(PROGRESS_STYLE_CLASS)
        val style = styleClass.getDeclaredConstructor().newInstance()
        val percent = state.percent
        if (percent != null) {
            styleClass.getMethod("setProgress", Int::class.javaPrimitiveType).invoke(style, percent)
        } else {
            styleClass.getMethod("setProgressIndeterminate", Boolean::class.javaPrimitiveType)
                .invoke(style, true)
        }
        val styleBase = Class.forName(STYLE_CLASS)
        builder.javaClass.getMethod("setStyle", styleBase).invoke(builder, style)
        builder.javaClass.getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType)
            .invoke(builder, true)
    }

    internal companion object {
        internal const val ID = "google.live_update"
        private const val API_36 = 36
        private const val PROGRESS_STYLE_CLASS = "androidx.core.app.NotificationCompat\$ProgressStyle"
        private const val STYLE_CLASS = "androidx.core.app.NotificationCompat\$Style"
    }
}

// ───────────────────────── 小米：超级岛 / 焦点通知 ─────────────────────────

/**
 * 小米超级岛（焦点通知）。
 *
 * 官方文档写明「按普通通知发送 + 在 extras 里加 `miui.focus.param`（JSON）」，是否上岛由系统决定：
 * - 支持岛且应用**拿到了焦点通知权限** → 以岛形态显示；
 * - 没权限 / 系统版本低 → 按普通通知显示（参数里的 `filterWhenNoPermission=false` 保证不会连普通通知都被吞掉）。
 *
 * 判定用官方文档给的 `Settings.System` 键 `notification_focus_protocol`：
 * 1/2 = 老焦点通知，3 = 超级岛。这里 ≥ 2 就挂参数 —— 参数本身对老版本无害。
 */
class XiaomiSuperIslandExtension(
    var attacher: IslandAttacher? = null,
) : DownloadIslandExtension {

    override val id: String get() = ID
    override val vendor: IslandVendor get() = IslandVendor.XIAOMI

    @Volatile
    private var cachedSupport: Boolean? = null

    override fun isAvailable(context: Context): Boolean = cachedSupport ?: (
        MANUFACTURERS.any { Build.MANUFACTURER.equals(it, ignoreCase = true) } &&
            XiaomiFocusProtocol.version(context) >= 2
        ).also { cachedSupport = it }

    override fun decorate(
        context: Context,
        builder: NotificationCompat.Builder,
        state: DownloadNotificationState,
    ) {
        val attached = attacher?.let {
            runCatching { it.attach(context, builder, state) }.getOrDefault(false)
        } ?: false
        if (attached) return
        val extras = Bundle().apply {
            putString(XiaomiIslandPayload.EXTRA_PARAM, XiaomiIslandPayload.build(state))
        }
        builder.addExtras(extras)
    }

    internal companion object {
        internal const val ID = "xiaomi.super_island"
        private val MANUFACTURERS = listOf("xiaomi", "redmi", "poco")
    }
}

/** 小米焦点通知的版本查询（官方文档：`notification_focus_protocol`，0 = 不支持）。 */
internal object XiaomiFocusProtocol {

    private const val KEY = "notification_focus_protocol"

    fun version(context: Context): Int =
        runCatching { Settings.System.getInt(context.contentResolver, KEY, 0) }.getOrDefault(0)
}

/**
 * 小米 `miui.focus.param` 的 JSON 构造（**纯函数，可单测**）。
 *
 * 只带「状态栏文案 + 焦点通知标题/内容」这几个稳定字段：
 * - 不带图片（`miui.focus.pics`）—— 没有需要展示的素材，带空 Bundle 反而多余；
 * - 大岛 / 小岛的分区模板字段随系统版本变化，字段名以《小米超级岛模板库》为准，
 *   这里不硬编码模板结构，缺失时系统会退化成普通焦点通知。
 */
internal object XiaomiIslandPayload {

    /** 岛参数（字符串，JSON）。 */
    const val EXTRA_PARAM = "miui.focus.param"

    /** 岛图片（Bundle<Icon>）：不传图片时无需携带，保留常量只为对照官方文档。 */
    const val EXTRA_PICS = "miui.focus.pics"

    /** 运营场景（仅用于厂商侧统计，不要写业务数据）。 */
    private const val BUSINESS = "file_download"

    fun build(state: DownloadNotificationState): String {
        val ticker = state.progressText
        return buildString {
            append("{\"param_v2\":{")
            append("\"protocol\":1")
            append(",\"business\":\"").append(BUSINESS).append('"')
            append(",\"updatable\":").append(state.isActive)
            append(",\"enableFloat\":false")
            append(",\"islandFirstFloat\":false")
            // false = 没拿到焦点通知权限时按普通通知显示（绝不能让通知消失）
            append(",\"filterWhenNoPermission\":false")
            append(",\"ticker\":\"").append(escape(ticker)).append('"')
            append(",\"param_island\":{\"islandProperty\":1}")
            append(",\"baseInfo\":{")
            append("\"title\":\"").append(escape(state.title)).append('"')
            append(",\"content\":\"").append(escape(state.sizeText)).append('"')
            append("}}")
            append('}')
        }
    }

    /** JSON 字符串转义（标题来自网络，必须转义，否则会拼出非法 JSON 让整条参数被丢弃）。 */
    internal fun escape(raw: String): String {
        val out = StringBuilder(raw.length + 8)
        for (ch in raw) {
            when (ch) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch < ' ') {
                    out.append("\\u%04x".format(Locale.ROOT, ch.code))
                } else {
                    out.append(ch)
                }
            }
        }
        return out.toString()
    }
}

// ───────────────────────── OPPO：ColorOS 实况通知 ─────────────────────────

/**
 * ColorOS「实况通知 / 流体云」。
 *
 * 现状（不要假装能做到）：OPPO **没有公开**三方接入文档，能力开放走开放平台申请，
 * 拿到白名单后才给 SDK。所以这里做两件事：
 * 1. 在 ColorOS 设备上把通知规范成「常驻 + 进度 + 不重复提醒」的形态 —— 这是实况通知最基础的载体，
 *    白名单批下来后系统即可识别；
 * 2. 留 [attacher] 注入点：官方 SDK 到位后由 `:app` 接进来，不需要改下载模块。
 *
 * **不写任何私有的 `oplus.*` extras**：字段名没有公开来源，猜错既没用还可能干扰系统。
 */
class OppoLiveAlertExtension(
    var attacher: IslandAttacher? = null,
) : DownloadIslandExtension {

    override val id: String get() = ID
    override val vendor: IslandVendor get() = IslandVendor.OPPO

    @Volatile
    private var cachedSupport: Boolean? = null

    override fun isAvailable(context: Context): Boolean = cachedSupport
        ?: MANUFACTURERS.any { Build.MANUFACTURER.equals(it, ignoreCase = true) }
            .also { cachedSupport = it }

    override fun decorate(
        context: Context,
        builder: NotificationCompat.Builder,
        state: DownloadNotificationState,
    ) {
        val attached = attacher?.let {
            runCatching { it.attach(context, builder, state) }.getOrDefault(false)
        } ?: false
        if (attached) return
        // 实况通知的基线形态：常驻、只响一次、归类进度
        builder.setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_PROGRESS)
    }

    internal companion object {
        internal const val ID = "oppo.live_alert"
        private val MANUFACTURERS = listOf("oppo", "oneplus", "realme")
    }
}
