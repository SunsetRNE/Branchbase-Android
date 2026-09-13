package com.branchbase.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.branchbase.downloader.DownloadPaths
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 发布附件的**暂存区**：用户从系统文件选择器导入的安装包 / 任意文件，以及「编辑中」的表单草稿。
 *
 * ## 为什么要有它（而不是只留一个 `content://` Uri）
 *
 * 选择器给的 Uri 是**临时凭据**：进程被杀、设备重启、或用户切出去一会儿，它就失效了。
 * 所以拿到 Uri 后**立刻复制**到 App 私有目录，之后所有动作（预览大小、上传、清理）都只认文件，
 * 不再碰 Uri —— 这才是「导入的文件不会丢」的落点。
 *
 * ## 目录选择：与 `:downloader` 同一套约定（`DownloadPaths.kt:10-28`）
 *
 * - 附件本体：`getExternalFilesDir(null)/release-uploads/{owner}/{repo}/{tag}/{文件名}`
 * - 表单草稿：`filesDir/release-drafts/{owner}-{repo}.json`（清单，小且频繁改）
 *
 * 文件与清单**分开存**是故意的：文件大、改动少，清单小、每次敲字都可能要落盘；
 * 分开放，「重进页面」时两边各恢复各的，也允许清单引用不在暂存区里的文件（`downloads/` 里的引用）。
 *
 * ## 为什么不用 cacheDir
 *
 * 系统在低存储时会直接清 `cacheDir`。用户「导入 APK → 切出去查个东西 → 回来」就可能发现文件没了 ——
 * 那正是这个功能最不能被接受的一种失败。外部私有目录只有卸载才会清（且不需要任何存储权限）。
 *
 * ## 生命周期
 *
 * | 时机 | 动作 |
 * |------|------|
 * | 导入 | 复制到 `<name>.part`，成功后 rename 成 `<name>`（半截文件不会被当成可用附件） |
 * | 发布成功 | 删除本次上传的文件（[clear]） |
 * | 用户点「移除」 | 立刻删该文件（[remove]） |
 * | 草稿未发布 | 保留 7 天，之后由 [pruneExpired] 回收 |
 *
 * 清理按「文件最后修改时间」而不是创建时间：`File` 拿不到可靠的创建时间，
 * 而附件在导入后不会再被写，两者的语义在这里等价。
 */
object ReleaseAttachmentStore {

    internal const val BASE = "release-uploads"

    /** 半成品后缀：复制中途被杀，目录里留下的是 `.part`，不会被列出来。 */
    internal const val PART_SUFFIX = ".part"

    /** 草稿未发布时的保留时长。 */
    internal const val TTL_MS = 7L * 24 * 60 * 60 * 1000

    /** 暂存区根目录（`getExternalFilesDir(null)` 在极少数机型上可能为 null，回退 filesDir）。 */
    fun root(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, BASE).apply { mkdirs() }

    /** 某条发布的附件目录。 */
    fun dir(context: Context, owner: String, repo: String, tag: String): File =
        File(root(context), relativeDir(owner, repo, tag)).apply { mkdirs() }

    /**
     * 三级目录名（纯函数，可单测）。
     *
     * 三段都要过 [DownloadPaths.sanitize]：tag 里完全可能有 `/`（如 `release/1.2`），
     * 直接当路径用会写到子目录里、`..` 还能跑出暂存区。
     */
    internal fun relativeDir(owner: String, repo: String, tag: String): String =
        listOf(owner, repo, tag.ifBlank { UNTAGGED }).joinToString("/") { DownloadPaths.sanitize(it) }

    internal const val UNTAGGED = "_untagged"

    /** 已落盘的附件（不含 `.part`）。 */
    fun list(context: Context, owner: String, repo: String, tag: String): List<File> =
        dir(context, owner, repo, tag).listFiles()
            ?.filter { it.isFile && !it.name.endsWith(PART_SUFFIX) }
            ?: emptyList()

    /**
     * 把系统选择器给的 `content://` 复制进暂存区。
     *
     * 目标文件已存在且非空时直接复用（同一次编辑里重复选同一个文件不该覆盖、也不该报错）。
     */
    fun stage(context: Context, owner: String, repo: String, tag: String, uri: Uri): Result<File> = runCatching {
        val target = File(dir(context, owner, repo, tag), displayName(context, uri))
        if (target.isFile && target.length() > 0L) return@runCatching target

        val part = File(target.parentFile, target.name + PART_SUFFIX)
        val input = context.contentResolver.openInputStream(uri) ?: error("无法读取所选文件")
        input.use { source ->
            part.outputStream().use { out -> source.copyTo(out) }
        }
        if (!part.renameTo(target)) {
            part.delete()
            error("写入暂存目录失败")
        }
        target
    }

    /**
     * 选择器里的显示名。
     *
     * 不可信（可能为空、可能带路径），一律过 `sanitize`；空名兜底成 `attachment`，
     * 否则会 `File(dir, "")` 拿到目录本身。
     */
    internal fun displayName(context: Context, uri: Uri): String {
        val queried = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()
        val raw = queried?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment.orEmpty()
        return DownloadPaths.sanitize(raw).ifBlank { "attachment" }
    }

    /** 删除单个附件（用户点「移除」）。 */
    fun remove(file: File): Boolean = file.isFile && file.delete()

    /**
     * 清掉某条发布的所有暂存文件（发布成功后调用）。
     *
     * 只删文件与空目录，不动其他 tag 的目录 —— 同一仓库可能同时在编辑多条发布。
     */
    fun clear(context: Context, owner: String, repo: String, tag: String): Int {
        val dir = dir(context, owner, repo, tag)
        var removed = 0
        dir.listFiles()?.forEach { if (it.isFile && it.delete()) removed++ }
        dir.delete() // 空了才删得掉
        return removed
    }

    /**
     * 回收超过 [TTL_MS] 没动过的暂存文件（含 `.part` 残骸），并清掉随之空掉的目录。
     *
     * 只按最后修改时间判断：附件导入后不会再被写，所以「最后修改」就是「导入时间」。
     * 纯 `File` 操作 + 注入时间，便于单测。
     */
    fun pruneExpired(context: Context, now: Long = System.currentTimeMillis()): Int =
        prune(root(context), now, TTL_MS)

    internal fun prune(root: File, now: Long, ttl: Long): Int {
        if (!root.isDirectory) return 0
        var removed = 0
        root.walkBottomUp().forEach { file ->
            when {
                file.isFile && isExpired(file.lastModified(), now, ttl) -> if (file.delete()) removed++
                file.isDirectory && file != root -> file.delete() // 空的才删得掉，非空会返回 false
            }
        }
        return removed
    }

    internal fun isExpired(lastModified: Long, now: Long, ttl: Long): Boolean =
        lastModified > 0L && now - lastModified > ttl

    /** 上传时的 Content-Type：GitHub 只把它当元数据，但装到浏览器里点下载时需要它是对的。 */
    internal fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "apk" -> "application/vnd.android.package-archive"
        "zip" -> "application/zip"
        "txt", "md", "log" -> "text/plain"
        "json" -> "application/json"
        "aab" -> "application/octet-stream"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        else -> "application/octet-stream"
    }
}

/**
 * 附件在「待发布」这条流水线上的状态。
 *
 * `UPLOADING` **不落盘**：重启后回到 [READY] 重来 —— 上传是一次网络动作，
 * 记一个「上次传到一半」的中间态没有意义（远端有没有收下也无从判断）。
 */
enum class AttachmentStatus {
    READY, UPLOADING, DONE, FAILED;

    /** JSON 里的写法：小写下划线没必要，直接小写枚举名。 */
    val wire: String get() = name.lowercase()

    companion object {
        fun fromWire(raw: String): AttachmentStatus =
            entries.firstOrNull { it.wire == raw } ?: READY
    }
}

/** 草稿里的一个附件（清单项）。`path` 是绝对路径：清单与文件在两个目录，必须写全。 */
data class DraftAttachment(
    val name: String,
    val size: Long,
    val path: String,
    val mime: String,
    val status: AttachmentStatus = AttachmentStatus.READY,
    val uploadedId: Long = 0L,
    val error: String? = null,
    /** true = 引用 `downloads/` 里已下载的文件，不占暂存区。 */
    val reference: Boolean = false,
)

/**
 * 发布编辑页的草稿（表单 + 附件清单）。
 *
 * 存在 `filesDir/release-drafts/{owner}-{repo}.json`：与附件本体分离、与账号无关
 * （同一台设备上换账号编辑同一个仓库，草稿仍按仓库归属）。
 */
data class ReleaseDraft(
    /** `existing?.id ?: 0`：0 表示「新建」。用来区分同一仓库下的两份编辑上下文。 */
    val releaseId: Long = 0L,
    val tag: String,
    val title: String,
    val body: String,
    val target: String,
    /** `stable` / `prerelease` / `draft`，与编辑页的三档性质同词汇（UI 侧映射）。 */
    val type: String,
    val latest: Boolean,
    val attachments: List<DraftAttachment>,
    val savedAt: Long,
)

object ReleaseDraftStore {

    private const val BASE = "release-drafts"

    fun dir(context: Context): File = File(context.filesDir, BASE).apply { mkdirs() }

    fun file(context: Context, owner: String, repo: String): File =
        File(dir(context), "${DownloadPaths.sanitize(owner)}-${DownloadPaths.sanitize(repo)}.json")

    fun load(context: Context, owner: String, repo: String): ReleaseDraft? {
        val file = file(context, owner, repo)
        if (!file.isFile) return null
        return runCatching { parse(file.readText()) }.getOrNull()
    }

    fun save(context: Context, owner: String, repo: String, draft: ReleaseDraft): Boolean = runCatching {
        file(context, owner, repo).writeText(toJson(draft))
    }.isSuccess

    fun clear(context: Context, owner: String, repo: String): Boolean =
        file(context, owner, repo).delete()

    internal fun toJson(draft: ReleaseDraft): String = JSONObject().apply {
        put("releaseId", draft.releaseId)
        put("tag", draft.tag)
        put("title", draft.title)
        put("body", draft.body)
        put("target", draft.target)
        put("type", draft.type)
        put("latest", draft.latest)
        put("savedAt", draft.savedAt)
        put("attachments", JSONArray().apply {
            draft.attachments.forEach { a ->
                put(JSONObject().apply {
                    put("name", a.name)
                    put("size", a.size)
                    put("path", a.path)
                    put("mime", a.mime)
                    put("status", if (a.status == AttachmentStatus.UPLOADING) AttachmentStatus.READY.wire else a.status.wire)
                    put("uploadedId", a.uploadedId)
                    put("reference", a.reference)
                    a.error?.let { put("error", it) }
                })
            }
        })
    }.toString()

    internal fun parse(json: String): ReleaseDraft {
        val o = JSONObject(json)
        val list = ArrayList<DraftAttachment>()
        val arr = o.optJSONArray("attachments") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            list += DraftAttachment(
                name = a.optString("name"),
                size = a.optLong("size"),
                path = a.optString("path"),
                mime = a.optString("mime").ifBlank { ReleaseAttachmentStore.mimeOf(a.optString("name")) },
                status = AttachmentStatus.fromWire(a.optString("status", "ready")),
                uploadedId = a.optLong("uploadedId"),
                error = a.optString("error").takeIf { it.isNotBlank() && it != "null" },
                reference = a.optBoolean("reference"),
            )
        }
        return ReleaseDraft(
            releaseId = o.optLong("releaseId"),
            tag = o.optString("tag"),
            title = o.optString("title"),
            body = o.optString("body"),
            target = o.optString("target"),
            type = o.optString("type", "stable"),
            latest = o.optBoolean("latest", true),
            attachments = list,
            savedAt = o.optLong("savedAt"),
        )
    }
}
