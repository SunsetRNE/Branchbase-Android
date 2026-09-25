package com.branchbase.ui.repository

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.branchbase.R
import com.branchbase.core.CloneProgress
import com.branchbase.core.RustBridge
import com.branchbase.ui.log.Logger
import com.branchbase.ui.task.TaskKind
import com.branchbase.ui.task.TaskStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「加深历史」（`fetch_deepen`）的运行器 —— 面板里那枚出口背后的**长任务**。
 *
 * ## 为什么抽成一份、两个宿主各挂一次
 *
 * 代码页与文件页共用 Git 面板，两边的「加深」出口都是同一件事。写两遍的代价不是多几行，
 * 而是**两套轮询节奏 / 两套终态处理**：一边失败停弹窗、另一边只留一句反馈，
 * 用户看到的行为就会随「从哪个页面点」而不同（本仓库为这类不一致付过代价：Git 球门控那次）。
 *
 * ## 与 clone 的关系
 *
 * 进度走的是**同一个引擎快照**（`core/src/git/progress.rs`，libgit2 在 fetch 回调里更新、
 * 这里每 200ms 轮询一次）—— §10 要求「不许出现第二种转圈」，所以这里连弹窗都复用
 * [CloneProgressDialog]，只换标题。取消也走同一条 `gitCloneCancel`。
 *
 * ## 三个终态各自的样子
 *
 * | 结果 | 界面 | 任务中心 |
 * |---|---|---|
 * | 成功 | 弹窗消失，宿主 `refreshTick++` → 提交图**换回本地来源** | SUCCESS |
 * | 用户取消 | 弹窗消失，不留红字（取消不是失败） | CANCELED |
 * | 失败 | 弹窗**停在原地**把引擎原文摊开，给「重试」 | FAILED（detail = 原因） |
 *
 * ## 作用域这条边界（与 clone 那条一样）
 *
 * 运行器跑在**宿主的组合作用域**里（`rememberCoroutineScope`）。用户在加深途中离开页面时
 * 协程会被取消，而引擎那侧**已经在跑**（JNI 是阻塞调用，取消不了）——所以收尾那一段走
 * `NonCancellable`，至少保证任务记录不会停在 RUNNING（那是在撒谎）。
 * 要彻底做对得把长任务搬到应用级作用域，那是独立的一件事，已登记进设计稿 §11。
 */
internal class LocalDeepenRunner internal constructor(
    private val scope: CoroutineScope,
    private val context: Context,
    private val repo: String,
    private val repoDir: String,
    private val token: String,
    private val onDone: () -> Unit,
) {

    /** 引擎给的进度快照（null = 还没拿到第一份，弹窗显示不确定态而不是假装 0%）。 */
    var progress by mutableStateOf<CloneProgress?>(null)
        private set

    var running by mutableStateOf(false)
        private set

    /** 已请求取消、等引擎在下一次回调里真的停（libgit2 没有立刻掐断的句柄）。 */
    var cancelling by mutableStateOf(false)
        private set

    /** 失败原因；非空 = 弹窗停在失败态。 */
    var error by mutableStateOf<String?>(null)
        private set

    /** 开始一次加深（已在跑时为 no-op：重复点击不该变成第二次全史下载）。 */
    fun start() {
        if (running) return
        running = true
        cancelling = false
        error = null
        progress = null
        scope.launch {
            val taskId = TaskStore.start(
                context,
                TaskKind.DEEPEN,
                context.getString(R.string.label_deepen_task_title, repo),
            )
            // 进度：fetch 是阻塞调用，只能从引擎快照里轮询（与 clone 同一条通道）。
            // IDLE / DONE / FAILED 不参与：它们属于上一次操作，读到只会让进度条刚打开就闪 100%
            val ticker = launch {
                while (isActive) {
                    val p = RustBridge.gitCloneProgress()?.takeIf {
                        it.phase != CloneProgress.Phase.IDLE &&
                            it.phase != CloneProgress.Phase.DONE &&
                            it.phase != CloneProgress.Phase.FAILED
                    }
                    if (p != null) {
                        progress = p
                        TaskStore.progress(context, taskId, p.percent ?: -1, clonePhaseText(context, p, cancelling))
                    }
                    delay(DEEPEN_POLL_MS)
                }
            }
            // depth = 0 = 全量（等价 git fetch --unshallow）：本地只有 depth(1) 那一条，
            // 增量加深对「离线看图 / 文件历史」没有意义，先给全量
            val failure = RustBridge.gitFetchDeepen(repoDir, depth = 0, token = token)
            ticker.cancel()
            // 收尾放进 NonCancellable：运行器的作用域是宿主的组合作用域，用户在加深途中离开页面
            // 会取消它 —— 而引擎那侧**已经在跑了**（JNI 是阻塞调用，取消不了），
            // 若收尾被一起取消，任务中心里就会留下一条永远 RUNNING 的记录（那是在撒谎）
            withContext(NonCancellable) {
                running = false
                progress = null
                when {
                    failure == null -> {
                        TaskStore.success(context, taskId, context.getString(R.string.toast_history_deepened))
                        // 只记仓库名、不记完整路径（路径含账号登录名，没必要进日志包）
                        Logger.remote("git 加深 $repo 历史完成", "libgit2")
                        onDone()
                    }
                    cancelling -> {
                        TaskStore.cancel(context, taskId)
                        Logger.remote("git 加深 $repo 历史已取消", "libgit2")
                    }
                    else -> {
                        TaskStore.fail(context, taskId, failure)
                        Logger.remote("git 加深 $repo 历史失败：$failure", "libgit2")
                        error = failure
                    }
                }
                cancelling = false
            }
        }
    }

    /** 请求取消（下一次进度回调里生效）。 */
    fun cancel() {
        if (!running || cancelling) return
        cancelling = true
        RustBridge.gitCloneCancel()
    }

    /** 关掉失败弹窗（不改任何状态，用户可以自己重试）。 */
    fun dismiss() {
        if (running) return
        error = null
    }

    /** 失败后重试。 */
    fun retry() {
        error = null
        start()
    }
}

/**
 * 记住一个加深运行器。
 *
 * [onDone] 走 `rememberUpdatedState`：它在宿主里通常写作 `{ refreshTick++ }`，
 * 而 `remember` 只在 repo / 目录变化时重建 —— 直接把旧 lambda 存进去，
 * 加深成功后刷新的就是**上一个**计数（表现是「加深完，图没换来源」）。
 */
@Composable
internal fun rememberLocalDeepenRunner(
    repo: String,
    repoDir: String,
    token: String,
    onDone: () -> Unit,
): LocalDeepenRunner {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val done by rememberUpdatedState(onDone)
    return remember(repo, repoDir, token) {
        LocalDeepenRunner(scope, context, repo, repoDir, token) { done() }
    }
}

/**
 * 加深任务的进度弹窗：跑着 / 失败时显示，其余时候什么都不画。
 *
 * 复用 clone 那只弹窗（同一套进度、同一种失败停留），只换标题 ——
 * 面板里点一下「加深历史」，弹出来的标题就必须是「加深历史」。
 */
@Composable
internal fun LocalDeepenDialog(runner: LocalDeepenRunner, repoFullName: String) {
    val state = when {
        runner.error != null -> CloneDialogState.Failed(runner.error!!)
        runner.running -> CloneDialogState.Running(runner.progress, runner.cancelling)
        else -> null
    } ?: return
    CloneProgressDialog(
        repoFullName = repoFullName,
        state = state,
        onCancel = runner::cancel,
        onRetry = runner::retry,
        onDismiss = runner::dismiss,
        title = stringResource(R.string.action_deepen_history),
    )
}

/** 轮询间隔：与 clone 那处一致（200ms 够跟手，又不至于把主线程叫醒得太勤）。 */
private const val DEEPEN_POLL_MS = 200L
