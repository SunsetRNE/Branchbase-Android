package com.branchbase.ui.repository

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.branchbase.core.AccountStore
import com.branchbase.core.LocalRepos
import com.branchbase.core.RustBridge
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
    val dirtyCount: Int = 0,
    val hasUpstream: Boolean = false,
    val remoteUrl: String = "",
) {
    /** 需要推送 / 需要拉取 / 分叉 —— 面板徽标与动作开关都用它。 */
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
            dirtyCount = status?.dirty?.size ?: 0,
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
