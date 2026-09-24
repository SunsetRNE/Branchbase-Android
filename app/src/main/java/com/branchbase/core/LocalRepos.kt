package com.branchbase.core

import android.content.Context
import java.io.File

/**
 * 本地仓库目录布局（按账号隔离）。
 *
 * - v1（旧）：`repos/{仓库名}` 平铺
 * - v2（现）：`repos/{login}/{仓库名}` —— 每个账号只能看到自己的目录
 * - v3（现，1.0.91）：根目录从**外部存储**换到**内部存储**（见 [base] 的说明）
 *
 * 删除账号只删账号记录，目录**保留**：重新添加同 login 的账号后自动重新可见，
 * 其他账号始终看不到（目录名就是隔离键，与账号 id 无关）。
 *
 * 未登录时归入 `_guest`，登录后可继续使用。
 */
object LocalRepos {

    private const val BASE = "repos"
    private const val GUEST = "_guest"
    private const val LAYOUT_MARK = ".layout-v2"
    private const val INTERNAL_MARK = ".layout-v3"

    /**
     * 仓库根目录：**内部存储**（`noBackupFilesDir`）。刻意不是 `getExternalFilesDir`。
     *
     * ## 为什么从外部存储搬走（1.0.91 的兼容处理）
     *
     * 1.0.89 / 1.0.90 的真机日志（OnePlus PJD110 / Android 16）里，clone 稳定失败在：
     *
     * ```
     * failed to lock file '…/Android/data/com.branchbase/files/repos/<login>/<repo>/.git/HEAD.lock' for writing
     * ```
     *
     * 一次 clone 会把 `HEAD` 写**两次** —— `git_repository_init` 建仓库时写一次（unborn HEAD），
     * 收尾 `git_repository_set_head` 再写一次 —— 第二次撞上了第一次留下的 `HEAD.lock`。
     * 同一台机器上，ext4（内部存储）与 `/sdcard/Download`（**同一个 FUSE、另一棵策略子树**）
     * 用同一份 libgit2 代码都能克隆成功，只有「App 私有的外部存储目录」这棵子树必现失败：
     * 也就是说，**问题在这个 FUSE 子树对 git 锁文件语义的兼容性**，不在 libgit2 的用法。
     *
     * 而 git 的每一次写都是「建 `<path>.lock` → 写完 rename」：这条路不兼容，
     * 坏掉的就不只是 clone —— commit / pull / push 迟早会坏在 `.git/index.lock`、`.git/refs/…lock` 上。
     * 所以把仓库整体换到**内部存储（/data 分区，ext4）**，让锁语义回到正常文件系统上。
     *
     * ## 代价（写在这里，免得以后被当成 bug）
     *
     * 仓库不再落在用户能用文件管理器翻到的目录里。但在 Android 11+ 上 `Android/data/`
     * 本来就不对文件管理器与 MTP 开放，这条「路径可被桌面端访问」的承诺在真机上早就是空的；
     * App 自己的文件页仍然是看仓库内容的入口。
     *
     * ## 为什么是 `noBackupFilesDir` 而不是 `filesDir`
     *
     * 仓库可能有几百 MB，而 `filesDir` 会进云备份 / 设备迁移（见 `res/xml/backup_rules.xml`）。
     * 外部存储时代它不参与备份，换过来不该顺手把这条语义改掉。
     */
    fun base(context: Context): File = File(context.noBackupFilesDir, BASE)

    /** 某账号的仓库根目录（顺带完成一次性布局迁移）。 */
    fun rootFor(context: Context, login: String): File {
        val base = base(context)
        val dir = File(base, login.ifBlank { GUEST })
        migrateFlatLayout(base, dir)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 该账号是否已有本地仓库（用于设置页显示数量）。 */
    fun count(context: Context, login: String): Int =
        File(base(context), login.ifBlank { GUEST })
            .listFiles()?.count { it.isDirectory } ?: 0

    /** 该账号的仓库目录名列表。 */
    fun listNames(context: Context, login: String): List<String> =
        rootFor(context, login).listFiles()
            ?.filter { it.isDirectory }?.map { it.name }?.sorted()
            ?: emptyList()

    /** 某仓库的绝对路径。 */
    fun dirOf(context: Context, login: String, repo: String): String =
        File(rootFor(context, login), repo).absolutePath

    /** 一次性迁移的结果（启动日志用；[alreadyDone] = 这一版之前就搬过了）。 */
    data class MigrationResult(val moved: Int, val failed: Int, val alreadyDone: Boolean)

    /**
     * 把「外部存储时代」的仓库搬进内部存储（幂等，失败保留原目录、下次启动再试）。
     *
     * 为什么要搬：见 [base] 的说明（FUSE 子树的锁文件不兼容）。
     * 为什么不在切目录时直接丢掉旧的：用户可能已经拉过几个仓库、里面还有未推送的提交 ——
     * 换存储位置是**兼容处理**，不是清理。
     *
     * 每次只处理一层条目（账号目录，或 v2 迁移之前的平铺仓库目录）：
     * 平铺仓库搬过去之后由 [migrateFlatLayout] 收进账号目录，两条迁移各管一段，互不干扰。
     * 同名目标已存在时**不覆盖**（内部目录里已有的那棵树优先）。
     * 全部搬完才写标记；有失败就不写 —— 下一次启动继续搬剩下的。
     */
    fun migrateFromExternal(context: Context): MigrationResult {
        val to = base(context)
        val mark = File(to, INTERNAL_MARK)
        if (mark.exists()) return MigrationResult(0, 0, true)
        to.mkdirs()

        val from = context.getExternalFilesDir(null)?.let { File(it, BASE) }
        var moved = 0
        var failed = 0
        if (from != null && from.exists() && !samePath(from, to)) {
            from.listFiles()?.forEach { src ->
                // 布局标记属于旧根目录，跟着搬会误导新根目录的迁移判断
                if (src.name.startsWith(".layout-")) return@forEach
                val dst = File(to, src.name)
                if (dst.exists()) return@forEach
                if (moveTree(src, dst)) moved++ else failed++
            }
        }
        if (failed == 0) runCatching { mark.writeText("v3") }
        return MigrationResult(moved, failed, false)
    }

    private fun samePath(a: File, b: File): Boolean =
        runCatching { a.canonicalPath == b.canonicalPath }.getOrDefault(false)

    /**
     * 一次性把旧平铺布局搬进当前账号目录。
     *
     * 只搬「含 `.git` 子目录」的目录 —— 旧布局的仓库一定带 `.git`，
     * 而账号目录本身不会有，因此不会把别的账号目录误搬进来。
     * 标记文件保证只执行一次。
     */
    private fun migrateFlatLayout(base: File, target: File) {
        val mark = File(base, LAYOUT_MARK)
        if (!base.exists()) {
            base.mkdirs()
            runCatching { mark.writeText("v2") }
            return
        }
        if (mark.exists()) return

        base.listFiles()?.forEach { f ->
            if (!f.isDirectory || f.name == target.name || f.name == GUEST) return@forEach
            if (!File(f, ".git").exists()) return@forEach
            val dest = File(target, f.name)
            if (!dest.exists()) {
                if (!target.exists()) target.mkdirs()
                runCatching { f.renameTo(dest) }
            }
        }
        runCatching { mark.writeText("v2") }
    }
}

/**
 * 把一个文件 / 目录树搬到 [dst]：先试 `renameTo`（同一文件系统，瞬时完成），
 * 跨文件系统时退化成递归复制 + 删源。成功返回 `true`。
 *
 * 纯文件操作、不碰 Android API —— 单测直接喂临时目录（见 `LocalReposMigrationTest`）。
 */
internal fun moveTree(src: File, dst: File): Boolean {
    if (src.renameTo(dst)) return true
    if (!copyTree(src, dst)) return false
    return src.deleteRecursively()
}

/** 递归复制（`dst` 已存在时合并进去，不删除 `dst` 里多出来的东西）。 */
internal fun copyTree(src: File, dst: File): Boolean {
    if (src.isDirectory) {
        if (!dst.exists() && !dst.mkdirs()) return false
        src.listFiles()?.forEach { child ->
            if (!copyTree(child, File(dst, child.name))) return false
        }
        return true
    }
    return runCatching {
        dst.parentFile?.mkdirs()
        src.inputStream().use { input -> dst.outputStream().use { output -> input.copyTo(output) } }
        true
    }.getOrDefault(false)
}
