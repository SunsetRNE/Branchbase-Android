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
        if (!File(dir, ".git").exists()) return@withContext LocalRepoGitState(exists = false)
        val status: GitStatus? = RustBridge.gitStatus(dir.absolutePath)?.let { parseGitStatus(it) }
        LocalRepoGitState(
            exists = true,
            branch = status?.branch.orEmpty(),
            ahead = status?.ahead ?: 0,
            behind = status?.behind ?: 0,
            dirty = status?.dirty.orEmpty(),
            hasUpstream = status?.hasUpstream ?: false,
            remoteUrl = status?.remoteUrl.orEmpty(),
        )
    }

/** Compose 版：`tick` 变化即重新读取（提交/推送后自增即可刷新徽标）。 */
@Composable
fun rememberLocalRepoGitState(repo: String, tick: Int = 0): LocalRepoGitState {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state by produceState(LocalRepoGitState(exists = false), repo, tick) {
        value = loadLocalRepoGitState(context, repo)
    }
    return state
}
