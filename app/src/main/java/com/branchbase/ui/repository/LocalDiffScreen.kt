package com.branchbase.ui.repository

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.R
import com.branchbase.core.RustBridge
import com.branchbase.ui.log.LogCategory
import com.branchbase.ui.log.Logger
import com.branchbase.ui.theme.Primer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * **本地 diff 页**（全屏）：把「工作区改了什么 / 这次提交改了什么」摊开看。
 *
 * 真源：[`git-mode-design.md`](../../../../../../docs/specs/git-mode-design.md) §3.2（改动清单点开看 diff）、
 * §11.6（1.0.99 拍板：**新开全屏页**，而不是给文件查看器加「本地工作树」来源）。
 *
 * ## 为什么是「新页」而不是复用文件查看器
 *
 * 1.0.96 一度把改动清单接成「点一行 → 打开那个文件」，写完才发现目的地错了：查看器读的是
 * `GET /repos/{o}/{r}/contents/{path}`（**远端**），而清单列的是本地改动 —— 点开看到的是
 * 没改过的那一份。给查看器加「本地工作树」来源当然也能修，但那意味着在一个已经背着
 * 编辑态 / 草稿 / 冲突检测 / 三种提交模式的页面上，再回答一遍「本地来源下这些还成不成立」
 * （缓存键、草稿基准 sha、提交后失效都会跟着变）；而用户点开脏文件想问的是
 * **「我改了什么」**，diff 一句话回答，原文得自己记得远端长什么样才比得出来。
 * 所以这里走一条独立的、**只读**的路：两个数据源（工作区 / 某个提交）输出同一套结构，
 * 共用这一页与同一份渲染。
 *
 * ## 两个入口
 *
 * | 入口 | 数据 | 标题 |
 * |---|---|---|
 * | 工作区档的改动清单（点一行） | `diff_worktree` | 工作区改动（+ 文件路径） |
 * | 提交图档的一条提交（点一行） | `diff_commit` | 提交 <短 sha> |
 *
 * ## 态
 *
 * 加载 / 失败可重试 / 没有改动 / 截断 / 单文件没有可显示的差异（二进制），
 * **每一种都有话说** —— 空白页面在这里是最坏的结果（用户分不清「没有改动」与「没读出来」）。
 */
@Composable
fun LocalDiffScreen(
    repoDir: String,
    onBack: () -> Unit,
    path: String? = null,
    commitSha: String? = null,
    modifier: Modifier = Modifier,
) {
    var view by remember(commitSha, repoDir) { mutableStateOf<LocalDiffView?>(null) }
    var loading by remember(commitSha, repoDir) { mutableStateOf(true) }
    var failed by remember(commitSha, repoDir) { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(repoDir, path, commitSha, reloadKey) {
        loading = true
        failed = false
        val json = withContext(Dispatchers.IO) {
            if (commitSha.isNullOrBlank()) RustBridge.gitDiffWorktree(repoDir)
            else RustBridge.gitDiffCommit(repoDir, commitSha)
        }
        val parsed = parseLocalDiff(json)
        view = parsed
        failed = parsed == null
        loading = false
        // 读失败**必须留一条**：页面上只有一句「读取失败」，事后分不清是引擎不可用、
        // 目录被删还是 sha 传错了（锚点与面板那一族一致）
        if (parsed == null) {
            Logger.warn(
                LogCategory.NETWORK, GIT_WORKBENCH_LOG_TAG,
                "本地 diff ▸ 读取失败（${commitSha ?: "工作区"}）",
            )
        } else {
            Logger.net(
                "本地 diff ▸ ${commitSha?.take(7) ?: "工作区"}：${parsed.files.size} 个文件" +
                    if (parsed.truncated) "（已截断）" else "",
                GIT_WORKBENCH_LOG_TAG,
            )
        }
    }

    val rows = remember(view, path) { view?.let { localDiffRows(it, onlyPath = path) }.orEmpty() }

    DetailScaffold(title = diffTitle(commitSha, path), onBack = onBack) {
        when {
            loading -> DetailLoading()
            failed -> DetailErrorRetry(onRetry = { reloadKey++ })
            view == null || view!!.isEmpty || rows.isEmpty() -> DetailEmptyText(
                stringResource(
                    if (path.isNullOrBlank()) R.string.state_local_diff_empty
                    else R.string.state_local_diff_file_empty,
                ),
            )
            else -> DiffBody(rows = rows, truncated = view!!.truncated, modifier = modifier)
        }
    }
}

/** 标题：工作区改动（带文件名）/ 提交 <短 sha>。 */
@Composable
private fun diffTitle(commitSha: String?, path: String?): String = when {
    !commitSha.isNullOrBlank() -> stringResource(R.string.label_local_diff_commit, commitSha.take(7))
    !path.isNullOrBlank() -> stringResource(R.string.label_local_diff_file, path)
    else -> stringResource(R.string.label_local_diff_worktree)
}

@Composable
private fun DiffBody(rows: List<LocalDiffRow>, truncated: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        if (truncated) {
            // 引擎按 200 KB 截断：**不许悄悄少画几行**（那会让人以为「改动就这么点」）
            Text(
                stringResource(R.string.note_local_diff_truncated),
                fontSize = 11.5.sp,
                color = Primer.WarningText,
                lineHeight = 16.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Primer.WarningSurface)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(
                items = rows,
                key = { row ->
                    when (row) {
                        is LocalDiffRow.Header -> "h:${row.file.path}"
                        is LocalDiffRow.NoPatch -> "n:${row.file.path}"
                        // 行号必须**带上文件身份**：两个文件各自的 hunk 头可能逐字相同
                        // （都是 `@@ -1,2 +1,2 @@`），只用行号做 key 会撞 key 直接崩
                        is LocalDiffRow.Line -> {
                            val line = row.line
                            "l:${row.filePath}:${line.kind}:${line.oldLine}:${line.newLine}"
                        }
                    }
                },
            ) { row ->
                when (row) {
                    is LocalDiffRow.Header -> FileHeader(row.file)
                    is LocalDiffRow.NoPatch -> NoPatchNote()
                    is LocalDiffRow.Line -> DiffLineRow(row.line)
                }
            }
        }
    }
}

/** 一个文件的抬头：路径 + 状态字母 + `+n −m`。 */
@Composable
private fun FileHeader(file: LocalDiffFile) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Primer.Gray100)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            file.status,
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = Primer.Orange500,
            modifier = Modifier.width(14.dp),
        )
        Text(
            file.path,
            fontSize = 11.5.sp,
            fontFamily = FontFamily.Monospace,
            color = Primer.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text("+${file.additions}", fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = Primer.Green500)
        Spacer(Modifier.width(6.dp))
        Text("−${file.deletions}", fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = Primer.Red500)
    }
}

/** 二进制 / 只有模式变化的文件：如实说明，而不是画一行假的行号。 */
@Composable
private fun NoPatchNote() {
    Text(
        stringResource(R.string.note_local_diff_no_patch),
        fontSize = 11.5.sp,
        color = Primer.TextTertiary,
        lineHeight = 16.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

