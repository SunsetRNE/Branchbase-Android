package com.branchbase.ui.repository

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.branchbase.R
import com.branchbase.core.RustBridge
import com.branchbase.ui.log.LogCategory
import com.branchbase.ui.log.Logger
import com.branchbase.ui.task.TaskKind
import com.branchbase.ui.task.TaskStore
import com.branchbase.ui.theme.Primer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 工作区档的四个**写出口**在宿主侧的「后果弹窗」与运行器（规格见 `docs/specs/git-mode-design.md` §3.6）。
 *
 * ## 为什么出口不直接落页面，要先过一层弹窗
 *
 * 从前这四个动作住在「设置 → 本地仓库」列表的**行内文字**上（一行五六个蓝色小字）：
 * 误触的代价是一笔提交 / 一次 reset / 一个上游 —— 而它们长得和「更新」一模一样。
 * 1.1.3 把它们收进工作区档，同时定下一条（§3.6 / D-l）：**有后果的动作，点下去先看见后果**。
 * 于是每次点击都是「出口胶囊 → 后果弹窗 → （需要选择或输入时才）决策页」。
 *
 * ## 为什么弹窗里不**执行**动作
 *
 * 提交要写 message、撤销要选软/硬、上游要填地址、回退要选保留 `.git` 还是删仓库 ——
 * 每一种都要用户给参数或做选择。弹窗只负责「说清这一步会让什么变、然后把人送到那个页面」；
 * 真正动手的地方只有两个：决策页（用户在那里按下确认）和 [runLocalRepoCommit]（提交这一笔）。
 *
 * ## 撤销 ⟷ 回退 Git 化不共用文案
 *
 * 两枚胶囊挨着，两个动作都「往回退」，但退的不是同一样东西：一个是**最近一笔提交**
 * （改动留在工作区，历史少一笔），一个是**整个仓库退回未纳管**（历史与远端都不动）。
 * 文案一旦共用，用户只能靠试 —— 所以 [gitOutletNoteRes] 里两条各写各的。
 */
internal enum class GitOutlet {
    /** 提交：工作区的改动 → 一笔提交（决策页写 message，必要时先补提交身份）。 */
    Commit,

    /** 撤销：撤销最近一次提交（未推送 amend/soft/hard、已推送 revert），改动不凭空消失。 */
    Undo,

    /** 上游：给当前分支设一个上游（远端地址 + 推送）。 */
    Upstream,

    /** 回退 Git 化：把本地仓库退回未纳管（保留 `.git` / 移除 `.git` / 删除整个仓库）。 */
    Rollback,
}

/** 与 [GitOutlet] 一一对应的文案（胶囊标签 = 弹窗标题，同一处真源）。 */
@StringRes
internal fun gitOutletLabelRes(outlet: GitOutlet): Int = when (outlet) {
    GitOutlet.Commit -> R.string.action_commit
    GitOutlet.Undo -> R.string.action_undo
    GitOutlet.Upstream -> R.string.nav_upstream
    GitOutlet.Rollback -> R.string.nav_revert_gitify
}

/** 弹窗正文：说清「点下去会让什么变」（两枚近义胶囊各写各的，见类注释）。 */
@StringRes
internal fun gitOutletNoteRes(outlet: GitOutlet): Int = when (outlet) {
    GitOutlet.Commit -> R.string.note_outlet_commit
    GitOutlet.Undo -> R.string.note_outlet_undo
    GitOutlet.Upstream -> R.string.note_outlet_upstream
    GitOutlet.Rollback -> R.string.note_outlet_rollback
}

/**
 * 四个出口的**待确认**状态（两个宿主各持一份，与 [MergeFlowState] 同一条口径）。
 *
 * 只装「哪一枚刚被点了」这一件事：页面路由不在这里 —— 决策页由各宿主自己的
 * `RepoRoute` / `FilePage` 表达（路由状态有两份的话，「返回时先关哪个」会有两种答案）。
 */
@Stable
internal class GitOutletFlowState {
    var pending by mutableStateOf<GitOutlet?>(null)

    /** 取消 / 确认之后都要清掉，否则下一次点开还是上一次那一枚。 */
    fun settle() {
        pending = null
    }
}

/** 两个宿主各持一份（`remember` 在页面级，跨重组保留）。 */
@Composable
internal fun rememberGitOutletFlowState(): GitOutletFlowState = remember { GitOutletFlowState() }

/**
 * 后果弹窗（§3.6 的第二层）。
 *
 * 只有两个按钮：继续（去决策页）/ 取消。**没有第三枚「就这样吧」** ——
 * 隔着屏幕猜用户想要哪种形态，正是弹窗这一层要避免的事。
 *
 * 1.1.5 起「上游」这一枚多带一句**探测出来的状态**（[upstream]）：点进去要做什么，
 * 取决于这个仓库在复刻网络里的位置（有上游 / 上游已归档 / 上游已删除 / 上游已私有化…），
 * 而那是客观事实、不是用户的选择 —— 摆在「后果」这一层，比进了页面才知道更早一步。
 * 自持仓库没有上游这一说，此时只显示状态句（那枚胶囊本来也不会画，见
 * [UpstreamRelation.showsEntry]）。
 */
@Composable
internal fun GitOutletDialog(
    outlet: GitOutlet,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    upstream: UpstreamRelation? = null,
) {
    val baseNote = stringResource(gitOutletNoteRes(outlet))
    // 只有「上游…」这一出口带状态句（其余三枚传进来的 upstream 一律忽略）
    val upstreamRel = upstream?.takeIf { outlet == GitOutlet.Upstream }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(gitOutletLabelRes(outlet)),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Primer.TextPrimary,
            )
        },
        text = {
            Text(
                when {
                    // 还没探测出结果（老路径 / 离线）：只留基础说明，不说不知道的话
                    upstreamRel == null -> baseNote
                    // 自持仓库：那句「给当前分支设一个上游」不成立，只留状态句
                    upstreamRel.state == UpstreamState.SELF_OWNED -> upstreamNoteText(upstreamRel)
                    else -> "$baseNote\n\n${upstreamNoteText(upstreamRel)}"
                },
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = Primer.TextSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.action_outlet_continue), color = Primer.Blue500)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * 提交身份填过没有（未填就先去身份页 —— 与设置列表 / 文件页那条路同一条判据）。
 *
 * 判据是「prefs 里有没有」，不是「引擎给不给默认值」：libgit2 会用任意签名把提交写下去，
 * 写完再发现作者是 `Branchbase` 就晚了（那笔提交已经在历史里）。
 */
internal fun commitIdentityReady(context: Context): Boolean {
    val prefs = context.getSharedPreferences("branchbase", Context.MODE_PRIVATE)
    return prefs.getString("commit.author.name", null) != null &&
        prefs.getString("commit.author.email", null) != null
}

/**
 * 跑一次本地提交（两个宿主共用；这是工作区档「提交」出口的**唯一**落点）。
 *
 * 与设置列表那一版同口径：任务中心记一条 `TaskKind.COMMIT`、引擎原话失败时原样反馈、
 * 成功后回调 [onChanged] 让徽标 / 工作区档重读（不然「提交完了工作区还写着 3 个改动」）。
 *
 * 注意引擎口径：`commit_repo` 先 `add_all(".")` 再 `update_all(".")`，
 * 也就是**把工作区的全部改动一起提交** —— 所以提交页对本地仓库不提供按文件勾选（见 `StageCommitScreen`）。
 */
internal fun CoroutineScope.runLocalRepoCommit(
    context: Context,
    repoDir: String,
    repoName: String,
    message: String,
    onFeedback: (String, Boolean) -> Unit,
    onChanged: () -> Unit,
    // 身份页刚填好、但用户选了「仅本次使用」时，这两个值不会落盘 —— 那就**这一次**用它。
    // 不传（null）时与设置列表那一版同一条口径：读 prefs，缺了回落到默认署名
    authorName: String? = null,
    authorEmail: String? = null,
) {
    launch {
        val prefs = context.getSharedPreferences("branchbase", Context.MODE_PRIVATE)
        val taskId = TaskStore.start(
            context,
            TaskKind.COMMIT,
            context.getString(R.string.action_commit_local, repoName),
        )
        Logger.local("$GIT_WORKBENCH_LOG_TAG ▸ $repoName：提交（本地仓库）", GIT_WORKBENCH_LOG_TAG)
        val sha = RustBridge.gitCommit(
            dir = repoDir,
            message = message,
            authorName = authorName?.takeIf { it.isNotBlank() }
                ?: prefs.getString("commit.author.name", null)
                ?: gitAuthorName(context),
            authorEmail = authorEmail?.takeIf { it.isNotBlank() }
                ?: prefs.getString("commit.author.email", null)
                ?: gitAuthorEmail(context),
        )
        if (sha != null) {
            TaskStore.success(context, taskId, context.getString(R.string.toast_committed, sha))
            Logger.local("$GIT_WORKBENCH_LOG_TAG ▸ $repoName：提交 ${sha.take(7)}", GIT_WORKBENCH_LOG_TAG)
            onFeedback(context.getString(R.string.state_committed_local_sha, sha), true)
            onChanged()
        } else {
            TaskStore.fail(context, taskId, context.getString(R.string.error_commit_failed_engine))
            Logger.warn(
                LogCategory.LOCAL_TASK,
                GIT_WORKBENCH_LOG_TAG,
                "$GIT_WORKBENCH_LOG_TAG ▸ $repoName：提交失败（引擎未返回 sha）",
            )
            onFeedback(context.getString(R.string.error_commit_failed_engine), false)
        }
    }
}
