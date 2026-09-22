package com.branchbase.ui.log

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import android.view.Display
import com.branchbase.BuildConfig
import java.util.Locale
import kotlin.math.roundToInt

/**
 * **设备档案**：把「这台机器是什么状况」写进日志（开机一行行、不发 UI）。
 *
 * ## 为什么要有它（远程排查缺的从来不是「慢帧多少毫秒」，而是**设备上下文**）
 *
 * 别人的日志发过来时，能看到的只有慢帧分段和缓存命中 —— 但同样的 120ms 慢帧，
 * 在旗舰机上是我们写得重，在低端机上可能已经是极限；而下面这几项**会直接改变结论**：
 *
 * | 项 | 为什么它决定结论 |
 * |---|---|
 * | **刷新率** | 慢帧阈值（16ms 预算 / 32ms 慢帧）是**按 60Hz 写死**的；90/120Hz 机器上「没超 16ms」其实已经掉了 vsync |
 * | **动画缩放**（开发者选项） | 用户把动画调成 0.5x / 关闭时，动效验收与「动画不对劲」的报告全部失真 |
 * | **不保留活动** | 打开后每次离开页面都销毁重建 —— 直接对应「每次重进页面都要重建」这类反馈 |
 * | **省电模式** | 限频、限刷新率，性能类反馈的头号混淆项 |
 * | **内存 / 核数 / 堆上限** | 判断「该不该预取、缓存能放多少」的边界条件 |
 *
 * 因此这些值的采集与格式化都放在这里，**只在日志里出现**（设置页不加行，避免变成用户要维护的配置）。
 * 每次 `App 启动` 记一组，于是「用户导出日志」里天然带着当时的设备状态。
 *
 * ## 一行一条
 *
 * 日志是**行式**存储（`logs/<日期>/branchbase.log`，一行一条、按行解析），
 * 所以这里把档案拆成若干**单行**，而不是塞一个多行块 —— 多行会破坏「一行一条」的约定，
 * 也会让按行过滤的工具（含 `tools/perf/frame-baseline.py`）解析错乱。
 */
internal object DeviceProfile {

    /** tag：日志里按 `[设备]` 过滤即可看到全部档案行。 */
    const val TAG = "设备"

    /** 采集并写日志。主线程调用（`Display` / `ActivityManager` 都是快调用，不读盘不联网）。 */
    fun log(activity: Activity) {
        val fields = runCatching { collect(activity) }.getOrNull() ?: return
        // ⚠️ 用「本地」类目而不是「UI」：`FrameWatch` 给慢帧加的页面注脚取的是
        // **最近一条 UI 类日志**（`LogManager.lastUiMessage`），档案按 UI 记的话，
        // 启动那几帧的注脚会变成「机型 OnePlus PJD110…」——正是它注释里警告过的套娃。
        formatDeviceProfile(fields).forEach { Logger.local(it, TAG) }
    }

    private fun collect(activity: Activity): DeviceFields {
        val ctx = activity.applicationContext
        val res = ctx.resources
        val dm = res.displayMetrics
        val cfg = res.configuration

        val display = currentDisplay(activity)
        val hz = display?.refreshRate ?: 0f
        val modes = display?.supportedModes?.map { it.refreshRate }?.distinct()?.sorted().orEmpty()

        val resolver = ctx.contentResolver
        fun globalFloat(key: String, def: Float): Float =
            runCatching { Settings.Global.getFloat(resolver, key, def) }.getOrDefault(def)
        fun globalInt(key: String, def: Int): Int =
            runCatching { Settings.Global.getInt(resolver, key, def) }.getOrDefault(def)

        val mem = ActivityManager.MemoryInfo()
        runCatching {
            (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mem)
        }
        val power = runCatching {
            (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode
        }.getOrDefault(false)

        val data = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()

        return DeviceFields(
            brand = Build.BRAND.orEmpty(),
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            device = Build.DEVICE.orEmpty(),
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            buildDisplay = Build.DISPLAY.orEmpty().take(40),
            fingerprint = Build.FINGERPRINT.orEmpty().take(28),
            widthPx = dm.widthPixels,
            heightPx = dm.heightPixels,
            densityDpi = dm.densityDpi,
            widthDp = cfg.screenWidthDp,
            heightDp = cfg.screenHeightDp,
            fontScale = cfg.fontScale,
            darkMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES,
            refreshHz = hz,
            modeHz = modes,
            peakHz = runCatching { Settings.System.getFloat(resolver, "peak_refresh_rate", 0f) }
                .getOrDefault(0f),
            memTotalMb = mem.totalMem / (1024 * 1024),
            memAvailMb = mem.availMem / (1024 * 1024),
            lowMemory = mem.lowMemory,
            heapMaxMb = Runtime.getRuntime().maxMemory() / (1024 * 1024),
            cores = Runtime.getRuntime().availableProcessors(),
            abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            cpuMaxMhz = cpuMaxMhz(),
            storageFreeGb = (data?.availableBytes ?: 0L) / (1024L * 1024 * 1024),
            storageTotalGb = (data?.totalBytes ?: 0L) / (1024L * 1024 * 1024),
            locale = Locale.getDefault().toString(),
            timeZone = java.util.TimeZone.getDefault().id,
            animatorScale = globalFloat(Settings.Global.ANIMATOR_DURATION_SCALE, 1f),
            transitionScale = globalFloat(Settings.Global.TRANSITION_ANIMATION_SCALE, 1f),
            windowScale = globalFloat(Settings.Global.WINDOW_ANIMATION_SCALE, 1f),
            alwaysFinish = globalInt(Settings.Global.ALWAYS_FINISH_ACTIVITIES, 0) == 1,
            developmentEnabled = globalInt(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1,
            powerSave = power,
            appVersion = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE,
            debuggable = BuildConfig.DEBUG,
        )
    }

    /** 当前 Display：API 30 起 `Activity.display`，更低版本退回 `windowManager.defaultDisplay`。 */
    @Suppress("DEPRECATION")
    private fun currentDisplay(activity: Activity): Display? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display
        } else {
            activity.windowManager.defaultDisplay
        }
    }.getOrNull()

    /** CPU 最高频率（kHz → MHz）。读不到就返回 null（部分 ROM 不给读）。 */
    private fun cpuMaxMhz(): Long? = runCatching {
        java.io.File("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq")
            .takeIf { it.canRead() }
            ?.readText()?.trim()?.toLongOrNull()
            ?.let { it / 1000 }
    }.getOrNull()
}

/** 采集到的原始值（与格式化分开：格式化是纯函数，能单测）。 */
internal data class DeviceFields(
    val brand: String,
    val manufacturer: String,
    val model: String,
    val device: String,
    val androidRelease: String,
    val sdkInt: Int,
    val buildDisplay: String,
    val fingerprint: String,
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val widthDp: Int,
    val heightDp: Int,
    val fontScale: Float,
    val darkMode: Boolean,
    val refreshHz: Float,
    val modeHz: List<Float>,
    val peakHz: Float,
    val memTotalMb: Long,
    val memAvailMb: Long,
    val lowMemory: Boolean,
    val heapMaxMb: Long,
    val cores: Int,
    val abi: String,
    val cpuMaxMhz: Long?,
    val storageFreeGb: Long,
    val storageTotalGb: Long,
    val locale: String,
    val timeZone: String,
    val animatorScale: Float,
    val transitionScale: Float,
    val windowScale: Float,
    val alwaysFinish: Boolean,
    val developmentEnabled: Boolean,
    val powerSave: Boolean,
    val appVersion: String,
    val appVersionCode: Int,
    val debuggable: Boolean,
)

/**
 * 把设备档案格式化成**若干单行**（纯函数，便于单测）。
 *
 * 拆成 5 行的取舍：机型/屏幕/性能/系统开关/App 各自成行，日志里按 `[设备]` 过滤后一眼能扫，
 * 也不会因为某一项读不到就整块消失。**每行都不含换行**（行式日志的硬约定）。
 */
internal fun formatDeviceProfile(f: DeviceFields): List<String> {
    val hz = f.refreshHz.takeIf { it > 0f }?.let { "${one(it)}Hz" } ?: "?"
    val modes = f.modeHz.takeIf { it.isNotEmpty() }?.joinToString("/") { one(it) } ?: "?"
    val peak = f.peakHz.takeIf { it > 0f }?.let { one(it) } ?: "?"
    // 慢帧阈值按 60Hz 写死（见 FrameWatch）：非 60Hz 机器上读数需要换算，这里直接把提示写进档案行
    val hzHint = when {
        f.refreshHz > 61f -> "（>60Hz：慢帧阈值按 60Hz 记，读数需换算）"
        f.refreshHz in 1f..59f -> "（<60Hz：面板本身跑不满 60）"
        else -> ""
    }

    return listOf(
        "机型 ${f.brand} ${f.model}（${f.device}，${f.manufacturer}）· Android ${f.androidRelease}" +
            "（SDK ${f.sdkInt}）· 构建 ${f.buildDisplay} · 指纹 ${f.fingerprint}",
        "屏幕 ${f.widthPx}x${f.heightPx}px @${f.densityDpi}dpi（${f.widthDp}x${f.heightDp}dp，" +
            "字体 ${one(f.fontScale)}x，${if (f.darkMode) "深色" else "浅色"}）· " +
            "刷新率 $hz（支持 $modes，峰值 $peak）$hzHint",
        "性能 内存 ${gb(f.memTotalMb)}GB（可用 ${gb(f.memAvailMb)}GB，" +
            "${if (f.lowMemory) "**低内存**" else "低内存否"}）· 堆上限 ${f.heapMaxMb}MB · " +
            "CPU ${f.cores} 核 ${f.abi}${f.cpuMaxMhz?.let { "（最高 ${it}MHz）" } ?: ""} · " +
            "存储可用 ${f.storageFreeGb}/${f.storageTotalGb}GB",
        "系统开关 语言 ${f.locale} · 时区 ${f.timeZone} · " +
            "动画缩放 ${one(f.animatorScale)}/${one(f.transitionScale)}/${one(f.windowScale)}" +
            if (f.animatorScale < 0.99f || f.transitionScale < 0.99f || f.windowScale < 0.99f) {
                "（**被调小或关闭：动效验收会失真**）"
            } else {
                ""
            } +
            " · 不保留活动 ${if (f.alwaysFinish) "**开**" else "关"}" +
            " · 开发者选项 ${if (f.developmentEnabled) "开" else "关"}" +
            " · 省电模式 ${if (f.powerSave) "**开**" else "关"}",
        "App ${f.appVersion}（versionCode ${f.appVersionCode}）· " +
            "debuggable=${f.debuggable}${if (f.debuggable) "（**debug 包：性能不代表正式版**）" else ""}",
    )
}

/** 一位小数；整数就不带小数点（`60` 而不是 `60.0`）。 */
private fun one(v: Float): String =
    if (v == v.roundToInt().toFloat()) v.roundToInt().toString() else String.format(Locale.US, "%.1f", v)

private fun gb(mb: Long): String = String.format(Locale.US, "%.1f", mb / 1024.0)
