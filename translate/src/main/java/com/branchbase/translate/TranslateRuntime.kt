package com.branchbase.translate

import android.content.Context
import java.io.File

/**
 * 进程内运行时（模块的装配点）。
 *
 * ## 为什么要有这一层
 *
 * [Translator] 需要三样东西：后端引擎、缓存（含磁盘文件）、设置。
 * 这三样里只有引擎来自 :app（Rust），其余都能由 Context 推出来。
 * 于是 :app 的 `Application.onCreate` 只需要一行：
 *
 * ```
 * TranslateRuntime.install(this, RustTranslateEngine())
 * ```
 *
 * 装配点收在这里的另一个好处是**降级安全**：未 install 时 [translator] 返回一个
 * 「无引擎」实例，所有翻译请求安静地失败（返回 null → 页面不插入任何东西），
 * 而不是抛 NPE 把正文页带崩。
 *
 * ## 缓存路径
 *
 * `filesDir/translate/cache.tsv`。放 `filesDir` 而不是 `cacheDir`：
 * 系统清理缓存目录时不打招呼，用户会发现「昨天翻过的今天又要重翻」；
 * 而这份文件很小（2000 条约 400KB），值得留在 files 下。
 */
object TranslateRuntime {

    /** 磁盘缓存条目上限（约 400KB）。 */
    private const val DISK_ENTRIES = 2000

    /** 内存缓存条目上限：一屏 README 大约 40~80 段，512 条足够覆盖几页内容。 */
    private const val MEMORY_ENTRIES = 512

    @Volatile
    private var instance: Translator? = null

    private val fallback: Translator by lazy { Translator(TranslateEngine.NONE) }

    /** 是否已经装配（关于页/日志用）。 */
    val installed: Boolean get() = instance != null

    /**
     * 装配运行时。重复调用会用新引擎重建实例（幂等，便于测试与热切换）。
     *
     * @param engine :app 提供的后端（Rust 实现），或测试用的假实现
     * @param diskCache 自定义磁盘缓存（单测传临时文件；默认用 `filesDir/translate/cache.tsv`）
     */
    fun install(
        context: Context,
        engine: TranslateEngine,
        diskCache: TranslateDiskCache? = null,
    ): Translator {
        val app = context.applicationContext
        val disk = diskCache ?: TranslateDiskCache(File(app.filesDir, "translate/cache.tsv"), DISK_ENTRIES)
        val cache = TranslateCache(MEMORY_ENTRIES) {
            // 开关每次读取：设置页关掉「本地缓存」后立刻停止落盘，无需重新 install
            if (TranslateSettings.read(app).persist) disk else null
        }
        val translator = Translator(
            engine = engine,
            cache = cache,
            protectTokens = { TranslateSettings.read(app).protect },
        )
        instance = translator
        return translator
    }

    /** 当前实例（未装配时返回降级实例）。 */
    val translator: Translator get() = instance ?: fallback
}
