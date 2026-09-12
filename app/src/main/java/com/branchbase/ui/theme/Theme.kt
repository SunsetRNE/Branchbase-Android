package com.branchbase.ui.theme

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 主题模式：跟随系统 / 强制浅色 / 强制深色。
 *
 * 不做成二元的「深色开关」是有原因的：需要**强制浅色**的用户和需要深色的一样多
 * （深色在强光下看不清、部分用户对深色的对比度更敏感），所以给三档、允许显式锁定。
 */
enum class ThemeMode(val storageKey: String, val label: String) {
    SYSTEM("system", "跟随系统"),
    LIGHT("light", "浅色"),
    DARK("dark", "深色"),
    ;

    /** 供「太阳图标」开关循环切换：跟随系统 → 浅色 → 深色 → 跟随系统。 */
    fun next(): ThemeMode = when (this) {
        SYSTEM -> LIGHT
        LIGHT -> DARK
        DARK -> SYSTEM
    }

    /** 该档位在 UI 上是否呈现为「深色」（SYSTEM 由系统决定，交给调用方判断）。 */
    fun resolveDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        val default: ThemeMode = SYSTEM

        /** 容错解析（老配置 / 手改过的值一律回落到跟随系统）。 */
        fun fromKey(key: String?): ThemeMode =
            entries.firstOrNull { it.storageKey == key } ?: default
    }
}

/** 当前生效的主题模式（设置页展示「当前档位」用）。 */
val LocalThemeMode = staticCompositionLocalOf { ThemeMode.default }

/** 当前是否深色：给 WebView 这类「Compose 之外」的渲染用（注入深色 CSS）。 */
val LocalIsDarkTheme = staticCompositionLocalOf { false }

/**
 * 应用主题（GitHub Primer，浅色 + 深色）。
 *
 * ## 深色是怎么做到的（改造前后）
 *
 * 改造前这里只有一份 `lightColorScheme`，而 `Primer` 是写死的静态色值 ——
 * 没有深色模式可言（`isSystemInDarkTheme()` 全项目 0 处使用）。现在：
 *
 * 1. [LightPrimerPalette] / [DarkPrimerPalette] 按 [mode] 选择，经 [LocalPrimerPalette] 下发；
 *    `Primer.XXX` 全部读它，于是 **1254 处调用点一行未改**就跟着主题走；
 * 2. M3 的 `ColorScheme` 由**同一份色板**生成：对话框 / 菜单 / 手板 / 底部导航指示器
 *    这些组件读的是 M3 角色（`surfaceContainer*` / `secondaryContainer`），
 *    只改 `Primer` 会在深色下漏出浅色块；
 * 3. 系统状态栏图标明暗 + 窗口底色一并跟随，否则深色页面顶部会压一条白条。
 */
@Composable
fun BranchbaseTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = mode.resolveDark(isSystemInDarkTheme())
    val palette = if (dark) DarkPrimerPalette else LightPrimerPalette
    val colorScheme = if (dark) DarkM3Scheme else LightM3Scheme

    // 状态栏 / 导航栏图标与窗口底色（Compose 管不到的部分）
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(palette.canvas.toArgb()),
            )
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    CompositionLocalProvider(
        LocalPrimerPalette provides palette,
        LocalThemeMode provides mode,
        LocalIsDarkTheme provides dark,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content,
        )
    }
}

// ───────────────────────── M3 角色映射 ─────────────────────────

/**
 * 把色板映射到 M3 角色。
 *
 * 关键点是**容器角色**（漏一个就会在深色下冒出一块浅色）：
 * `DropdownMenu` 读 `surfaceContainer`、`AlertDialog` 读 `surfaceContainerHigh`、
 * `ModalBottomSheet` 读 `surfaceContainerLow`、`NavigationBarItem` 指示器读 `secondaryContainer`。
 */
private fun primerScheme(p: PrimerPalette, dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = p.accent,
        onPrimary = Color.White,
        primaryContainer = p.accent.copy(alpha = 0.18f),
        onPrimaryContainer = if (dark) p.textPrimary else p.accentStrong,
        inversePrimary = p.link,
        secondaryContainer = p.accent.copy(alpha = 0.18f),
        onSecondaryContainer = p.link,
        secondary = p.success,
        background = p.canvas,
        onBackground = p.textPrimary,
        surface = p.canvas,
        onSurface = p.textPrimary,
        surfaceBright = p.canvasSubtle,
        surfaceDim = p.canvas,
        surfaceContainerLowest = p.canvas,
        surfaceContainerLow = p.canvasSubtle,
        surfaceContainer = p.canvasSubtle,
        surfaceContainerHigh = p.canvasSubtle,
        surfaceContainerHighest = p.neutralFillStrong,
        surfaceVariant = p.neutralBorder,
        onSurfaceVariant = p.textSecondary,
        surfaceTint = p.accent,
        outline = p.border,
        outlineVariant = p.neutralBorder,
        scrim = Color.Black,
        inverseSurface = p.inverseSurface,
        inverseOnSurface = p.inverseOnSurface,
        error = p.danger,
        onError = Color.White,
        errorContainer = p.dangerSubtle,
        onErrorContainer = if (dark) p.textPrimary else p.danger,
    )
}

// 色板是常量：M3 映射只算一次，避免每次重组都 copy 一份 ColorScheme
private val LightM3Scheme: ColorScheme = primerScheme(LightPrimerPalette, dark = false)
private val DarkM3Scheme: ColorScheme = primerScheme(DarkPrimerPalette, dark = true)

/**
 * 主题模式的读写（与全局其它设置同用 `branchbase` 这份 prefs）。
 *
 * 放在 theme 包是因为**消费方就在这一层**（[BranchbaseTheme]）；设置页只是写的一方。
 * 加进程内缓存的理由同翻译设置：它会被高频读取（每次重组都要判断档位）。
 */
object ThemeSettings {

    private const val PREFS = "branchbase"
    private const val KEY_MODE = "ui.theme.mode"

    @Volatile
    private var cached: ThemeMode? = null

    fun read(context: Context): ThemeMode =
        cached ?: ThemeMode.fromKey(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, null),
        ).also { cached = it }

    fun write(context: Context, mode: ThemeMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODE, mode.storageKey).apply()
        cached = mode
    }
}
