package com.branchbase.ui.log

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.branchbase.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 日志导出：把当前日志打成 **zip** 落到公共下载目录 `Download/Branchbase/`，
 * 并给出一个可分享的 `content://` Uri（供系统分享窗口使用）。
 *
 * ## 落盘方式按系统分两条（这不是偷懒，是必需）
 *
 * | 系统 | 方式 | 权限 | 目录被删后 |
 * |---|---|---|---|
 * | API 29+ | `MediaStore.Downloads` + `RELATIVE_PATH=Download/Branchbase` | **不需要** | 下次写入自动重建 |
 * | API ≤ 28 | `Environment.getExternalStoragePublicDirectory(DOWNLOADS)/Branchbase` + `mkdirs()` | 需要 `WRITE_EXTERNAL_STORAGE`（运行时申请） | 下次写入重建（`mkdirs`） |
 *
 * 走 MediaStore 还有两个附带好处：写入期间用 `IS_PENDING` 标记，别的应用不会读到半截文件；
 * 拿到的 `content://` Uri 可以直接丢给分享窗口 —— 而 API ≤ 28 的真文件必须过
 * `FileProvider`（`file://` 从 API 24 起抛 `FileUriExposedException`）。
 *
 * ## 失败要说清是哪一种失败
 *
 * 调用方拿到的 [Result.Failed] 带 `needsStoragePermission`：权限问题要引导用户去授权
 * （设置页），其它问题（磁盘满、系统拒绝）只该如实说明原因 —— 两者混成一句
 * 「导出失败」会让用户去改一个本来没问题的开关。
 */
object LogExporter {

    /** 导出目录名（公共下载目录下）。 */
    const val DIR_NAME = "Branchbase"

    /** 压缩包里日志条目的文件名（与日志文件同名，便于对照）。 */
    const val LOG_ENTRY_NAME = "branchbase.log"

    /**
     * 压缩包里**索引/报告**条目的文件名。
     *
     * 一个包**两份文件**：`report.md`（给人看的概览：版本、条数、时间范围、怎么看、锚点词典）
     * 与 [LOG_ENTRY_NAME]（原始日志，全文检索用）。索引完全由同一批日志算出，不含额外信息。
     */
    const val REPORT_ENTRY_NAME = "report.md"

    const val MIME_ZIP = "application/zip"

    /** FileProvider authority（与 AndroidManifest 里的 `${applicationId}.logs.files` 一致）。 */
    private fun authority(context: Context) = "${context.packageName}.logs.files"

    /** 导出结果。 */
    sealed interface Result {
        /** @param displayDir 给用户看的位置描述（成功提示/日志用） */
        data class Ok(
            val uri: Uri,
            val fileName: String,
            val displayDir: String,
            val bytes: Long,
        ) : Result

        /** @param needsStoragePermission 只有它该引导用户去授权；其它失败只如实说明原因 */
        data class Failed(
            val message: String,
            val needsStoragePermission: Boolean,
        ) : Result
    }

    /**
     * 导出（IO 线程由内部切好）。
     *
     * 调用前不必自己 `LogManager.flush()` —— 这里会先把写盘队列刷下去，
     * 否则导出的包会少最后几行（写盘是异步的，见 `FileAppender`）。
     */
    suspend fun export(context: Context): Result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        LogManager.flush()
        val text = currentLogText()
        if (text.isBlank()) return@withContext Result.Failed("还没有日志内容可导出", false)

        val fileName = zipFileName(System.currentTimeMillis())
        // 一个包两份文件：索引在前（人先看到），原始日志在后
        val bytes = buildLogArchive(
            linkedMapOf(
                REPORT_ENTRY_NAME to buildLogReport(LogManager.all(), System.currentTimeMillis(), appInfo()),
                LOG_ENTRY_NAME to text,
            ),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeViaMediaStore(context, fileName, bytes)
        } else {
            writeViaLegacyDir(context, fileName, bytes)
        }
    }

    /** API ≤ 28 且未授权时为 true（调用方据此决定是否先申请权限）。 */
    fun needsStoragePermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED

    /** 分享意图（调用方用 `Intent.createChooser` 包一层再 startActivity）。 */
    fun shareIntent(context: Context, ok: Result.Ok): Intent = Intent(Intent.ACTION_SEND).apply {
        type = MIME_ZIP
        putExtra(Intent.EXTRA_STREAM, ok.uri)
        putExtra(Intent.EXTRA_SUBJECT, ok.fileName)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    // ── 内部：内容 ──

    /** 写进索引的版本信息（取自编译期注入的 BuildConfig）。 */
    private fun appInfo() = ExportAppInfo(
        engineeringVersion = BuildConfig.ENGINEERING_VERSION,
        standardVersion = BuildConfig.STANDARD_VERSION,
        buildTime = BuildConfig.BUILD_TIME,
        gitHash = BuildConfig.GIT_HASH,
        debug = BuildConfig.DEBUG,
    )

    /**
     * 当前日志文本：**文件优先，内存环形缓冲兜底**。
     *
     * 兜底这条不是多余：首次启动后立刻导出时文件可能还没落盘（写盘是异步的），
     * 没有它就会导出一个空包 —— 而「导出为空」比「导出失败」更难排查。
     */
    private fun currentLogText(): String {
        val fromFile = runCatching { LogManager.logFile()?.takeIf { it.isFile }?.readText() }.getOrNull()
        if (!fromFile.isNullOrBlank()) return fromFile
        return LogManager.all().asReversed().joinToString("\n") { logLine(it) }
    }

    // ── 内部：落盘 ──

    private fun writeViaMediaStore(context: Context, fileName: String, bytes: ByteArray): Result =
        runCatching {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, MIME_ZIP)
                // 目录不存在时 MediaStore 按这条相对路径创建；用户删掉后再导出也会重建
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$DIR_NAME")
                // 写完才「发布」：期间别的应用读不到半截 zip
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return Result.Failed("系统拒绝了写入（MediaStore 未返回 Uri）", false)

            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: return Result.Failed("打不开写入流（MediaStore）", false)

            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            Result.Ok(uri, fileName, "Download/$DIR_NAME", bytes.size.toLong())
        }.getOrElse { failureOf(it, context) }

    private fun writeViaLegacyDir(context: Context, fileName: String, bytes: ByteArray): Result =
        runCatching {
            if (needsStoragePermission(context)) {
                return Result.Failed("需要存储权限才能写入下载目录", true)
            }
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                DIR_NAME,
            )
            // 首次导出没有这个文件夹就创建；用户删掉之后再导出也会重建
            if (!dir.isDirectory && !dir.mkdirs()) {
                return Result.Failed("创建目录失败：${dir.absolutePath}", true)
            }
            val file = File(dir, fileName)
            file.writeBytes(bytes)
            val uri = FileProvider.getUriForFile(context, authority(context), file)
            Result.Ok(uri, fileName, dir.absolutePath, bytes.size.toLong())
        }.getOrElse { failureOf(it, context) }

    /** 把异常翻译成「用户能看懂 + 调用方能分派」的失败。 */
    private fun failureOf(t: Throwable, context: Context): Result.Failed {
        val needsPerm = needsStoragePermission(context) || t is SecurityException
        val message = when {
            t is SecurityException -> "没有存储权限，系统拒绝了写入。"
            t is java.io.IOException -> "写入失败：${t.message ?: "IO 错误"}（磁盘满或系统拒绝？）"
            else -> "导出失败：${t.message ?: t::class.java.simpleName}"
        }
        return Result.Failed(message, needsPerm)
    }
}

/** 压缩包文件名（纯函数，便于单测）：`branchbase-logs-20260922-204530.zip`。 */
internal fun zipFileName(nowMs: Long): String =
    "branchbase-logs-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(java.util.Date(nowMs)) + ".zip"

/**
 * 打包成 zip（纯函数，便于单测）：每个条目一份文本，UTF-8。
 *
 * 用内存里的 [ByteArrayOutputStream]：日志只有几百 KB，一次写完比边写边落盘简单得多，
 * 也避免「写了一半失败」在下载目录里留下半截文件。
 */
internal fun buildLogArchive(entries: Map<String, String>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        entries.forEach { (name, text) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(text.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}

// ── 索引（report.md） ──

/** 写进索引的 App 版本信息（在 `export()` 里由 `BuildConfig` 填，测试可直接构造）。 */
internal data class ExportAppInfo(
    val engineeringVersion: String,
    val standardVersion: String,
    val buildTime: String,
    val gitHash: String,
    val debug: Boolean,
)

private val reportTimeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
    timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
}
private val reportClockFmt = SimpleDateFormat("HH:mm:ss", Locale.US).apply {
    timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
}

/**
 * 压缩包里的**索引/报告**（纯函数，便于单测）。
 *
 * 它是「同一批日志的另一种呈现」，不是新数据源：所有数字都从 [entries] 现算，
 * 所以**索引与原始日志不可能互相矛盾** —— 有疑问以原始日志为准。
 *
 * 三件事各有用处：
 * 1. **概览**（版本 / 条数 / 时间范围 / 类别分布）—— 收到日志的人先看这个，判断「是不是这份日志」；
 * 2. **怎么读**（行格式 + 检索词）—— 不必先读代码；
 * 3. **锚点词典**（[LOG_ANCHORS]）—— 关键路径打了哪些 tag，`grep` 一个词就能看到整条链路。
 */
internal fun buildLogReport(
    entries: List<LogEntry>,
    nowMs: Long,
    app: ExportAppInfo,
): String {
    val sb = StringBuilder()
    val levels = entries.groupingBy { it.level }.eachCount()
    val categories = entries.groupingBy { it.category }.eachCount()
    val topTags = entries.groupingBy { it.tag }.eachCount().entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .take(8)

    sb.appendLine("# Branchbase 日志包 · 索引")
    sb.appendLine()
    sb.appendLine("> 这个压缩包里是**两份文件**：本索引（概览，给人看）与 `branchbase.log`（原始日志，全文检索用）。")
    sb.appendLine("> 索引里的数字都从同一批日志现算，不含额外信息 —— **有疑问以原始日志为准**。")
    sb.appendLine()

    sb.appendLine("## 1. 这份包从哪来")
    sb.appendLine()
    sb.appendLine("| 项 | 值 |")
    sb.appendLine("|---|---|")
    sb.appendLine("| 导出时间 | ${reportTimeFmt.format(java.util.Date(nowMs))}（北京时间） |")
    sb.appendLine("| 工程版本 | ${app.engineeringVersion} |")
    sb.appendLine("| 标准版本号 | ${app.standardVersion} |")
    sb.appendLine("| 构建时间 | ${app.buildTime} |")
    sb.appendLine("| 提交 | ${app.gitHash} |")
    sb.appendLine("| 通道 | ${if (app.debug) "Debug（Beta）" else "Release"} |")
    sb.appendLine()

    sb.appendLine("## 2. 日志概览")
    sb.appendLine()
    sb.appendLine("| 项 | 值 |")
    sb.appendLine("|---|---|")
    sb.appendLine("| 条数 | ${entries.size}（ERROR ${levels[LogLevel.ERROR] ?: 0} / WARN ${levels[LogLevel.WARN] ?: 0}） |")
    val range = if (entries.isEmpty()) {
        "（空）"
    } else {
        "${reportClockFmt.format(java.util.Date(entries.first().time))} → ${reportClockFmt.format(java.util.Date(entries.last().time))}"
    }
    sb.appendLine("| 时间范围 | $range |")
    sb.appendLine(
        "| 按类别 | " + LogCategory.entries.joinToString(" · ") { "${it.label} ${categories[it] ?: 0}" } + " |",
    )
    sb.appendLine(
        "| 出现最多的 tag | " +
            (if (topTags.isEmpty()) "（空）" else topTags.joinToString("、") { "${it.key}×${it.value}" }) + " |",
    )
    sb.appendLine()

    sb.appendLine("## 3. 包内文件")
    sb.appendLine()
    sb.appendLine("| 文件 | 是什么 |")
    sb.appendLine("|---|---|")
    sb.appendLine("| `${LogExporter.REPORT_ENTRY_NAME}` | 本文件：概览 + 怎么读 + 锚点词典 |")
    sb.appendLine("| `${LogExporter.LOG_ENTRY_NAME}` | 原始日志（UTF-8 文本，逐行） |")
    sb.appendLine()

    sb.appendLine("## 4. 怎么读")
    sb.appendLine()
    sb.appendLine("- 每行格式：`HH:mm:ss.SSS [类别] [tag] LEVEL 消息`，类别 ∈ UI / 网络 / 远端 / 本地。")
    sb.appendLine("- 常用检索：`ERROR`、`WARN`、`[网络]`、`慢帧`、`设备`、`启动`。")
    sb.appendLine("- 崩溃/卡顿类问题先搜 `慢帧`；接口类问题先搜 `[网络]`；本地 git 类问题先搜 `[本地]`。")
    sb.appendLine()

    val device = entries.firstOrNull { it.tag == DeviceProfile.TAG }?.message
    sb.appendLine("## 5. 设备档案")
    sb.appendLine()
    sb.appendLine("```")
    sb.appendLine(device ?: "（这份日志里没有设备档案：启动那条已被轮转覆盖，或本次是异常启动）")
    sb.appendLine("```")
    sb.appendLine()

    sb.appendLine("## 6. 关键路径锚点（直接 grep 这些词）")
    sb.appendLine()
    sb.appendLine("| tag | 记录什么 |")
    sb.appendLine("|---|---|")
    LOG_ANCHORS.forEach { (tag, what) -> sb.appendLine("| `$tag` | $what |") }
    sb.appendLine()
    sb.appendLine("> 锚点表是**契约**：改 tag 等于改契约（旧日志会对不上这张表）。表里的每个 tag 都有单测确认它真的被用过。")

    return sb.toString()
}

