package com.branchbase.core

import android.content.Context
import java.io.File

/**
 * 本地仓库目录布局（按账号隔离）。
 *
 * - v1（旧）：`repos/{仓库名}` 平铺
 * - v2（现）：`repos/{login}/{仓库名}` —— 每个账号只能看到自己的目录
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

    fun base(context: Context): File = File(context.getExternalFilesDir(null), BASE)

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
