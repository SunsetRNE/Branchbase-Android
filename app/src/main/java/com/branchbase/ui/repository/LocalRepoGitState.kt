package com.branchbase.ui.repository

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.branchbase.core.AccountStore
import com.branchbase.core.LocalRepos
import com.branchbase.core.RustBridge
import com.branchbase.ui.decision.DirtyFile
import com.branchbase.ui.decision.GitStatus
import com.branchbase.ui.decision.parseGitStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 本地仓库（`repos/{login}/{repo}`）的 git 状态快照。
 *
 * 供代码页 / 文件页的 Git 气泡面板展示「当前分支 / 改动数 / 领先落后」，
 * 决定哪些动作可用（不存在本地仓库时只显示服务端相关动作）。
 */
data class LocalRepoGitState(
    val exists: Boolean,
    val branch: String = "",
    val upstream: String = "",
    val ahead: Int = 0,
    val behind: Int = 0,
    /** 工作区变更文件（工作台「工作区」档要列清单；徽标只用它的长度）。 */
    val dirty: List<DirtyFile> = emptyList(),
    val hasUpstream: Boolean = false,
    val remoteUrl: String = "",
    /**
     * 仓库是不是**停在合并中**（`.git/MERGE_HEAD` 在）。
     *
     * 这是「合并到一半被杀」之后唯一的线索（`git-mode-design.md` §6.4 风险 ①）：
     * 面板据此把「合并分支」换成「继续 / 放弃」，而不是让用户对着一堆冲突标记猜发生了什么。
     */
    val merging: Boolean = false,
    /** 合并中**还剩几个**冲突文件（`merging = false` 时恒为 0）。 */
    val mergeConflicts: Int = 0,
    /**
     * 这份快照**读完了没有**（[rememberLocalRepoGitState] 的初值是 `loaded = false`）。
     *
     * 为什么必须与 [exists] 分开：`exists` 是「本地有没有这个副本」这个**事实**，
     * 而首帧的 `exists = false` 只是「还没读到」。两者混用的代价是两处真机上看得见的错：
     * ① 面板第一帧按「未拉取到本地」渲染，几十毫秒后再跳成真实内容；
     * ② 提交图档拿 `exists = false` 去择源 → 先按 REST 取一次（**一次多余的网络请求**），
     *    等快照到了再按本地取一次 —— 两张图前后闪一下。
     * 所以档内容的渲染与择源都以 `loaded` 为闸门（见 `GitPanelViewHost`）。
     */
    val loaded: Boolean = true,
) {
    /** 需要推送 / 需要拉取 / 分叉 —— 面板徽标与动作开关都用它。 */
    /** 改动文件数（徽标 / 摘要用它，免得两处各取一次长度）。 */
    val dirtyCount: Int get() = dirty.size

    val needsPush: Boolean get() = exists && ahead > 0
    val needsPull: Boolean get() = exists && behind > 0 && ahead == 0
    val diverged: Boolean get() = exists && ahead > 0 && behind > 0

    /** 一句话摘要（面板标题）。 */
    fun summary(): String = when {
        !exists -> "未拉取到本地"
        diverged -> "$branch · 分叉 ↑$ahead ↓$behind"
        ahead > 0 -> "$branch · 待推送 $ahead"
        behind > 0 -> "$branch · 待拉取 $behind"
        dirtyCount > 0 -> "$branch · 改动 $dirtyCount"
        else -> "$branch · 已同步"
    }
}

/** 本地仓库目录（按当前账号隔离）。 */
fun localRepoDir(context: Context, repo: String): String =
    LocalRepos.dirOf(context, AccountStore.currentLogin(context), repo)

/**
 * 本地 git 提交的**身份**（作者名 / 邮箱）。
 *
 * 存储键与「设置 → 本地仓库」那一页是同一份（`branchbase` 这个 SharedPreferences 里的
 * `commit.author.*`）—— 抽出来是为了**只有一处**知道键名：合并提交与普通提交用的是同一个身份，
 * 两处各写一份的话，改了设置里的称呼、合并提交却还用旧名字（而这事只有翻 git log 才看得出来）。
 */
fun gitAuthorName(context: Context): String =
    context.getSharedPreferences("branchbase", Context.MODE_PRIVATE)
        .getString("commit.author.name", "")?.takeIf { it.isNotBlank() } ?: "Branchbase"

fun gitAuthorEmail(context: Context): String =
    context.getSharedPreferences("branchbase", Context.MODE_PRIVATE)
        .getString("commit.author.email", "")?.takeIf { it.isNotBlank() }
        ?: "branchbase@users.noreply.github.com"

/**
 * 本地仓库是不是**浅克隆**（`.git/shallow` 存在）。
 *
 * 这个判定现在有真实用途：提交图的择源（[graphSourceOf]）。clone 用的是 `depth(1)`，
 * 浅克隆里本地只有 HEAD 一条提交 —— 拿它当提交图来源就是把「一屏 100 条」换成「1 条」。
 *
 * 为什么读文件而不是问引擎：libgit2 有 `is_shallow`，但为此多开一条 JNI（还要重建 `.so`）
 * 只为读一个「文件在不在」不值当；`git` 自己也是靠这个文件认定浅克隆的，
 * 加深（`fetch_deepen`）成功时 libgit2 会把它删掉（`repository.c` 的
 * `git_repository__shallow_roots_write`：roots 为空就 remove），所以这个判定会跟着翻。
 *
 * 纯 IO、无副作用：**调用方在 IO 上读**（提交图那一档在取数那一趟里读，
 * 不在组合期读 —— 组合期读文件是主线程的一次 stat）。
 */
fun isShallowClone(repoDir: String): Boolean =
    repoDir.isNotBlank() && File(repoDir, ".git/shallow").exists()

/** 读取本地仓库 git 状态；目录或 `.git` 不存在时返回 `exists=false`。 */
suspend fun loadLocalRepoGitState(context: Context, repo: String): LocalRepoGitState =
    withContext(Dispatchers.IO) {
        val dir = File(localRepoDir(context, repo))
        // 「读完了，没有本地副本」也是**读完了**：loaded 与 exists 是两件事（见字段说明）
        if (!File(dir, ".git").exists()) {
            return@withContext LocalRepoGitState(exists = false, loaded = true)
        }
        val status: GitStatus? = RustBridge.gitStatus(dir.absolutePath)?.let { parseGitStatus(it) }
        // 合并中才去读第二个接口：正常状态下这一条不产生任何额外调用，
        // 而合并中不读的话，面板就只能说「合并中」、说不出「还剩几个」
        val merging = status?.merging ?: false
        val mergeConflicts = if (merging) {
            unresolvedCount(parseMergeState(RustBridge.gitMergeState(dir.absolutePath)))
        } else {
            0
        }
        LocalRepoGitState(
            exists = true,
            branch = status?.branch.orEmpty(),
            ahead = status?.ahead ?: 0,
            behind = status?.behind ?: 0,
            dirty = status?.dirty.orEmpty(),
            hasUpstream = status?.hasUpstream ?: false,
            remoteUrl = status?.remoteUrl.orEmpty(),
            merging = merging,
            mergeConflicts = mergeConflicts,
        )
    }

/** Compose 版：`tick` 变化即重新读取（提交/推送后自增即可刷新徽标）。 */
@Composable
fun rememberLocalRepoGitState(repo: String, tick: Int = 0): LocalRepoGitState {
    val context = androidx.compose.ui.platform.LocalContext.current
    // 初值 `loaded = false` + `exists = false`：这一帧只说明「还没读到」，不说明「没有本地仓库」。
    // 用它去渲染档内容或择源，就会先画错一次再改（面板跳一下 / 提交图白取一次 REST）
    val state by produceState(LocalRepoGitState(exists = false, loaded = false), repo, tick) {
        value = loadLocalRepoGitState(context, repo)
    }
    return state
}
