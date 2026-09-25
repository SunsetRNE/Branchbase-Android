package com.branchbase.ui.repository

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.R
import com.branchbase.ui.log.LogCategory
import com.branchbase.ui.log.Logger
import com.branchbase.ui.theme.PlaceholderSwap
import com.branchbase.ui.theme.Primer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Git 工作台 —— **「引用树」档**（[GitPanelKind.Refs]，阶段 2）。
 *
 * 一屏回答「这个仓库里有哪些引用、我现在在哪、哪些只在远端」：
 * **本地分支**（HEAD 置顶 · 上游 · 领先落后）+ **远端跟踪引用**。数据来自本地仓库
 * （`local_branches` / `remote_branches`），离线可读、与「工作区」档同源。
 *
 * ## 这一档为什么是只读的
 *
 * 切换 / 新建 / 删除分支是**有后果的动作**（脏工作区切分支要撤销改动、删分支会丢提交），
 * 按 §6.1 的三档划分它们走决策页，落点在既有的「分支管理」「本地分支同步」。所以这里
 * **一个可点分支行都不放**：点了没反应的行比没有这一档更坏，而把危险动作直接搬进
 * 268dp 的浮层里则是把「误触」变成默认路径。面板底部只给两个真能用的出口（刷新 / 同步）。
 *
 * ## tags：阶段 3 起走本地 `list_tags`
 *
 * 一开始这一区是占位（引擎没有这个接口）。当时**没有**用 REST 的 `/tags` 顶替 ——
 * 那份给不了 annotated 的 tagger / 时间 / 说明（D-f 的口径），两套混用迟早要拆两遍。
 * 现在本地接口落地了：annotated tag 显示说明首行 + 打 tag 的人；
 * **轻量 tag 只有名字与提交** —— 不画「未知作者」这种编出来的字段。
 *
 * ## 只读，但**不是死胡同**
 *
 * 分支的切 / 建 / 删落既有页面（D-j），所以这一档必须把路指清楚：给了 [onOpenBranches] 的宿主
 * 就会多一枚「分支管理」胶囊。没有出口的只读列表会让人以为「App 里根本改不了分支」。
 *
 * @param repoDir 本地仓库目录（`localRepoDir(context, repo)`）
 * @param localRepoExists 本地仓库在不在。false 时**不去读**（引擎只会报错），直接如实说明
 * @param refreshTick 外部刷新计数（工作区档同一份来源：宿主自增，本档跟着重读）
 * @param onOpenBranches 去分支管理；null = 这个宿主没有这个出口（不画那枚胶囊）
 */
@Composable
fun GitRefsPanel(
    repoDir: String,
    localRepoExists: Boolean,
    refreshTick: Int,
    onRefresh: () -> Unit,
    onOpenSync: () -> Unit,
    onOpenBranches: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var view by remember(repoDir) { mutableStateOf<GitRefsView?>(null) }
    var failed by remember(repoDir) { mutableStateOf(false) }
    var loading by remember(repoDir) { mutableStateOf(localRepoExists) }
    var reloadKey by remember(repoDir) { mutableIntStateOf(0) }

    LaunchedEffect(repoDir, localRepoExists, refreshTick, reloadKey) {
        if (!localRepoExists) {
            view = null
            failed = false
            loading = false
            return@LaunchedEffect
        }
        loading = true
        failed = false
        val loaded = withContext(Dispatchers.IO) { loadGitRefsView(repoDir) }
        view = loaded
        // null = 读不到（仓库不存在 / 引擎不可用）：**不折成空列表**，
        // 「读失败」与「真的一个引用都没有」在界面上是两条不同的文案
        failed = loaded == null
        loading = false
        // 一处动作一条：本档的「取数结果」就是它的动作。日志里只放仓库名（不带完整路径 ——
        // 路径里含账号登录名，没必要进日志包）
        val name = repoDir.substringAfterLast('/')
        if (loaded == null) {
            Logger.warn(LogCategory.LOCAL_TASK, GIT_WORKBENCH_LOG_TAG, "引用树 ▸ 读取失败：$name（仓库不存在或引擎不可用）")
        } else {
            Logger.local(
                "引用树 ▸ $name：本地 ${loaded.locals.size} · 远端 ${loaded.remotes.size}（只在远端 ${loaded.remoteOnly}）",
                GIT_WORKBENCH_LOG_TAG,
            )
        }
    }

    Column(modifier.fillMaxWidth()) {
        if (!localRepoExists) {
            Text(
                stringResource(R.string.state_local_repo_missing),
                fontSize = 12.sp,
                color = Primer.TextTertiary,
                modifier = Modifier.padding(vertical = 10.dp),
            )
            RefFooter(onRefresh, onOpenSync, onOpenBranches, syncEnabled = false)
            return@Column
        }

        if (loading) {
            PlaceholderSwap(loading = true, skeleton = { RefsSkeleton() }) { }
            return@Column
        }

        if (failed) {
            Text(
                stringResource(R.string.error_refs_load_failed),
                fontSize = 11.5.sp,
                color = Primer.DangerText,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            TextButton(onClick = { reloadKey++ }) {
                Text(stringResource(R.string.action_retry), color = Primer.Blue500, fontSize = 12.sp)
            }
            return@Column
        }

        val refs = view
        if (refs == null || refs.isEmpty) {
            Text(
                stringResource(R.string.state_refs_empty),
                fontSize = 12.sp,
                color = Primer.TextTertiary,
                modifier = Modifier.padding(vertical = 10.dp),
            )
            RefFooter(onRefresh, onOpenSync, onOpenBranches, syncEnabled = true)
            return@Column
        }

        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 240.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            item(key = "local-header") {
                RefSectionHeader(
                    stringResource(R.string.label_local_branches),
                    refs.locals.size,
                )
            }
            items(refs.locals, key = { "local:${it.name}" }) { LocalRefRow(it) }

            item(key = "remote-header") {
                RefSectionHeader(
                    stringResource(R.string.label_refs_remote_branches),
                    refs.remotes.size,
                    note = refs.remoteOnly.takeIf { it > 0 }
                        ?.let { stringResource(R.string.label_refs_remote_only_count, it) },
                )
            }
            if (refs.remotes.isEmpty()) {
                // 没 fetch 过的仓库就是这样。**如实说明 + 给出口**（同步页做的那次 fetch），
                // 而不是画一个空区让人以为「远端没有分支」
                item(key = "remote-empty") {
                    Text(
                        stringResource(R.string.state_refs_remote_empty),
                        fontSize = 10.5.sp,
                        color = Primer.TextTertiary,
                        lineHeight = 14.sp,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            } else {
                items(refs.remotes, key = { "remote:${it.name}" }) { RemoteRefRow(it) }
            }

            item(key = "tags-header") {
                RefSectionHeader(stringResource(R.string.label_tag), refs.tags.size)
            }
            if (refs.tags.isEmpty()) {
                item(key = "tags-empty") {
                    // 没有 tag 是**正常状态**（新仓库就是这样），说清楚而不是画一个空区
                    Text(
                        stringResource(R.string.state_refs_tags_empty),
                        fontSize = 10.5.sp,
                        color = Primer.TextTertiary,
                        lineHeight = 14.sp,
                        modifier = Modifier.padding(vertical = 3.dp),
                    )
                }
            } else {
                items(refs.tags, key = { "tag:${it.name}" }) { TagRow(it) }
            }
        }

        RefFooter(onRefresh, onOpenSync, onOpenBranches, syncEnabled = true)
    }
}

/** 分区标题：名字 + 条数（条数是**如实**的，不写「若干」）；[note] 用于「N 个只在远端」这类补充。 */
@Composable
private fun RefSectionHeader(title: String, count: Int?, note: String? = null) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (count == null) title else "$title · $count",
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = Primer.TextSecondary,
        )
        if (note != null) {
            Spacer(Modifier.width(6.dp))
            Text(note, fontSize = 9.5.sp, color = Primer.TextTertiary)
        }
    }
}

/** 本地分支行：`● 名字`（HEAD 加实心点）+ 上游 + 领先落后徽标。 */
@Composable
private fun LocalRefRow(row: GitRefRow) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(12.dp), contentAlignment = Alignment.Center) {
            if (row.isHead) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(Primer.Blue500))
            }
        }
        Text(
            row.name,
            fontSize = 11.5.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (row.isHead) FontWeight.SemiBold else FontWeight.Normal,
            color = if (row.isHead) Primer.TextPrimary else Primer.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (row.isHead) {
            RefChip("HEAD", Primer.Blue500)
        } else if (!row.tracked) {
            // 没配上游 ≠ 已同步：这枚小字就是为了不让 0/0 被读成「推完了」
            RefChip(stringResource(R.string.state_refs_untracked), Primer.TextTertiary)
        }
        row.badge?.let {
            Spacer(Modifier.width(4.dp))
            Text(it, fontSize = 10.sp, color = Primer.TextTertiary)
        }
    }
}

/**
 * tag 行：名字 + 「轻量」标记 + 说明首行。
 *
 * 轻量 tag 与 annotated tag 在界面上必须**一眼可分**：前者只有名字与提交（没有 tagger / 说明），
 * 后者多一条说明。**不给轻量 tag 编作者**（D-f），也不给这一行挂点击 ——
 * 这一档是清单，点开能去哪今天并不存在，画个可点的样子只会是假入口。
 */
@Composable
private fun TagRow(row: GitTagRow) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.name,
                fontSize = 11.5.sp,
                fontFamily = FontFamily.Monospace,
                color = Primer.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!row.annotated) {
                RefChip(stringResource(R.string.state_refs_tag_lightweight), Primer.TextTertiary)
            }
        }
        if (row.subject.isNotBlank()) {
            Text(
                row.subject,
                fontSize = 10.sp,
                color = Primer.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 远端跟踪引用行：`origin/名字`；本地没有对应分支时标「只在远端」。 */
@Composable
private fun RemoteRefRow(row: GitRemoteRefRow) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(12.dp))
        Text(
            "origin/${row.name}",
            fontSize = 11.5.sp,
            fontFamily = FontFamily.Monospace,
            color = Primer.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!row.hasLocal) {
            RefChip(stringResource(R.string.state_refs_remote_only), Primer.Orange500)
        }
        row.badge?.let {
            Spacer(Modifier.width(4.dp))
            Text(it, fontSize = 10.sp, color = Primer.TextTertiary)
        }
    }
}

@Composable
private fun RefChip(text: String, color: Color) {
    Text(
        text,
        fontSize = 9.sp,
        fontWeight = FontWeight.Medium,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Primer.Gray100)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

/**
 * 面板底部的出口：刷新（重读引用）/ 同步 / 分支管理（后两个都是**去既有页面**）。
 *
 * 走 [FlowRow] 而不是 `Row`：英文标签长得多（"Local branch sync" / "Branch management"），
 * 三枚挤一行会超出 268dp 的面板宽度**被裁掉**（`Row` 不换行也不报错）。
 * [onOpenBranches] 为 null 时那一枚不画（见参数说明）。
 */
@Composable
private fun RefFooter(
    onRefresh: () -> Unit,
    onOpenSync: () -> Unit,
    onOpenBranches: (() -> Unit)?,
    syncEnabled: Boolean,
) {
    Spacer(Modifier.height(6.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PanelChip(stringResource(R.string.action_refresh), enabled = true, onClick = onRefresh)
        PanelChip(stringResource(R.string.nav_local_branch_sync), enabled = syncEnabled, onClick = onOpenSync)
        onOpenBranches?.let {
            PanelChip(stringResource(R.string.nav_branch_manage), enabled = syncEnabled, onClick = it)
        }
    }
}

@Composable
private fun RefsSkeleton() {
    Column(Modifier.fillMaxWidth()) {
        repeat(4) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp)
                    .height(11.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Primer.Gray150),
            )
        }
    }
}
