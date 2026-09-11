package com.branchbase.ui.repository

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.branchbase.cache.PageCache
import com.branchbase.cache.SearchCacheDatabase
import com.branchbase.cache.SearchCacheManager
import com.branchbase.core.RustBridge
import com.branchbase.ui.theme.Primer
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Issue 单消息页（需求 ④，对标 github.com issue 页）。
 *
 * 与改造前的差别（改造前只有：返回栏 + 标题/状态/作者 + 标签 + 正文 + 评论列表）：
 * - **头部**：状态徽章三态（Open / Closed as completed / Closed as not planned）、带颜色的标签、
 *   指派者、里程碑、评论数；
 * - **时间线**：评论与事件（labeled / assigned / referenced / milestoned / closed / reopened / renamed…）
 *   按时间混排；拿不到 timeline 时自动降级为「只有评论」，页面结构不变；
 * - **评论卡**：作者/协作者徽章、反应（可加可撤）、⋮ 菜单（复制链接 / 复制 Markdown / 浏览器打开）、
 *   长评折叠；
 * - **正文**：原生 Markdown 渲染（粗斜体 / 行内代码 / 代码块 + 复制 / 列表 / 任务清单 / 引用 / @提及 / #编号），
 *   含 HTML 或图片的主帖退回 WebView 渲染（保真优先）；
 * - **筛选**：全部 / 仅评论 / 仅事件；
 * - **吸底输入器**：写 / 预览切换、Markdown 工具栏、随内容自增高、落笔即启用「评论」、
 *   「关闭并评论 ▾」区分「已完成 / 不计划实施」。
 *
 * 远端写操作全部走已有或本次补齐的桥接：
 * `createIssueComment` / `updateIssueState`（带 state_reason）/ `postJson` + `deleteJson`（反应）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IssueDetailScreen(
    sessionJson: String,
    owner: String,
    repo: String,
    number: Long,
    onBack: () -> Unit,
) {
    val (host, token, login) = sessionInfo(sessionJson)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var detail by remember { mutableStateOf<IssueDetail?>(null) }
    var entries by remember { mutableStateOf<List<TimelineEntry>>(emptyList()) }
    var bodyHtml by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var filter by remember { mutableStateOf(TlFilter.ALL) }
    var showAllOlder by remember { mutableStateOf(false) }

    // 输入器
    var draft by remember { mutableStateOf("") }
    var previewTab by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var closeMenu by remember { mutableStateOf(false) }
    // 正在编辑哪条评论（null = 新评论）。编辑态下主按钮变「保存修改」，并给一条显式的取消入口。
    var editing by remember { mutableStateOf<CommentItem?>(null) }
    // 待确认删除的评论
    var deleting by remember { mutableStateOf<CommentItem?>(null) }
    // 提交成功后滚到时间线末尾：新评论在最后，不滚过去用户会以为没发出去
    var pendingScrollToEnd by remember { mutableStateOf(false) }

    // 本地交互态（不进网络的状态放这里，避免为了一个勾/一个折叠重拉整页）
    val reactionsBusy = remember { mutableStateMapOf<String, Boolean>() }
    val expandedComments = remember { mutableStateMapOf<Long, Boolean>() }

    val issueUrl = "https://github.com/$owner/$repo/issues/$number"

    suspend fun applyDetail(json: String): Boolean {
        val d = runCatching { parseIssueDetail(json) }.getOrNull() ?: return false
        // 主帖反应同样要补「我点过的」（网络响应只给计数，不给「哪个是我」）
        detail = d.copy(
            reactions = d.reactions.map { r ->
                r.copy(mine = IssueReactionStore.isMine(context, "$owner/$repo#$number:issue:${r.content}"))
            },
        )
        // 主帖：含 HTML / 图片时退回 WebView（MarkdownBody 只覆盖评论常用语法）
        bodyHtml = if (d.body.isNotBlank() && needsRichRenderer(d.body)) markdownToHtml(host, token, d.body) else null
        return true
    }

    LaunchedEffect(owner, repo, number) {
        loading = true
        // 本页由列表页进入，自身没有手动刷新入口，因此 force 恒为 false（PageCache 默认值）
        val manager = SearchCacheManager(SearchCacheDatabase.getInstance(context).searchCacheDao())
        val detailKey = PageCache.issueKey(owner, repo, number)
        val timelineKey = PageCache.issueTimelineKey(owner, repo, number)
        val commentsKey = PageCache.issueCommentsKey(owner, repo, number)
        var appliedJson: String? = null

        // ① 先直出缓存（含过期数据）：返回上一层再进来立即有内容
        PageCache.cachedFirst(manager, detailKey, PageCache.TYPE_DETAIL)?.let { cached ->
            if (applyDetail(cached)) {
                appliedJson = cached
                loading = false
            }
        }
        PageCache.cachedFirst(manager, timelineKey, PageCache.TYPE_DETAIL)?.let { cached ->
            runCatching { parseIssueTimeline(cached, detail?.author.orEmpty()) }.getOrNull()
                ?.takeIf { it.isNotEmpty() }?.let { entries = hydrateMine(context, it, "$owner/$repo#$number") }
        }

        // ② 回源并写回：详情与时间线并行（原来是详情成功后再串行拉评论）
        coroutineScope {
            val detailJob = async {
                PageCache.refresh(manager, detailKey, PageCache.TYPE_DETAIL) {
                    RustBridge.getJson(host, token, "/repos/$owner/$repo/issues/$number")
                }
            }
            val timelineJob = async {
                PageCache.refresh(manager, timelineKey, PageCache.TYPE_DETAIL) {
                    RustBridge.getJsonAccept(
                        host,
                        token,
                        "/repos/$owner/$repo/issues/$number/timeline",
                        "application/vnd.github+json",
                    )
                }
            }
            val detailJson = detailJob.await()
            if (detailJson != null && detailJson != appliedJson) applyDetail(detailJson)

            val timelineJson = timelineJob.await()
            if (timelineJson != null) {
                val parsed = parseIssueTimeline(timelineJson, detail?.author.orEmpty())
                if (parsed.isNotEmpty()) entries = hydrateMine(context, parsed, "$owner/$repo#$number")
            }
            // ③ 时间线拿不到（权限 / 预览 media type 变化）→ 退回评论端点，至少不丢评论
            if (entries.isEmpty()) {
                PageCache.refresh(manager, commentsKey, PageCache.TYPE_DETAIL) {
                    RustBridge.getJson(host, token, "/repos/$owner/$repo/issues/$number/comments")
                }?.let { json ->
                    entries = hydrateMine(
                        context,
                        parseComments(json, detail?.author.orEmpty()).map { TimelineEntry.Comment(it) },
                        "$owner/$repo#$number",
                    )
                }
            }
        }
        loading = false
    }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    /** 本地更新某条评论（或主帖）的反应计数 */
    fun updateReaction(comment: CommentItem?, content: String, delta: Int, mine: Boolean) {
        if (comment == null) {
            detail = detail?.let { d -> d.copy(reactions = applyReactionDelta(d.reactions, content, delta, mine)) }
            return
        }
        entries = entries.map { e ->
            if (e is TimelineEntry.Comment && e.comment.id == comment.id) {
                TimelineEntry.Comment(
                    e.comment.copy(reactions = applyReactionDelta(e.comment.reactions, content, delta, mine)),
                )
            } else {
                e
            }
        }
    }

    /** 反应：POST 添加 / DELETE 撤销；反应 id 存在本地（GitHub 的计数对象不带「我是哪一个」）。 */
    fun toggleReaction(comment: CommentItem?, content: String) {
        val scopeKey = comment?.let { "c${it.id}" } ?: "issue"
        val key = "$owner/$repo#$number:$scopeKey:$content"
        if (reactionsBusy[key] == true) return
        reactionsBusy[key] = true
        scope.launch {
            val path = if (comment != null) {
                "/repos/$owner/$repo/issues/comments/${comment.id}/reactions"
            } else {
                "/repos/$owner/$repo/issues/$number/reactions"
            }
            val mine = IssueReactionStore.isMine(context, key)
            if (mine) {
                val id = IssueReactionStore.idOf(context, key)
                // 撤销反应必须带 reaction id，且评论反应与 issue 反应是两个端点
                val deletePath = when {
                    id == null -> null
                    comment != null -> "/repos/$owner/$repo/issues/comments/${comment.id}/reactions/$id"
                    else -> "/repos/$owner/$repo/issues/$number/reactions/$id"
                }
                val err = if (deletePath != null) RustBridge.deleteJson(host, token, deletePath)
                else "本地没有记录这条反应的 id（可能是在其它设备上添加的）"
                if (err == null) {
                    IssueReactionStore.remove(context, key)
                    updateReaction(comment, content, delta = -1, mine = false)
                } else {
                    toast("取消反应失败：$err")
                }
            } else {
                val json = RustBridge.postJson(host, token, path, "{\"content\":\"$content\"}")
                if (json != null && !json.startsWith("ERROR:")) {
                    val id = runCatching { org.json.JSONObject(json).optLong("id") }.getOrDefault(0L)
                    if (id > 0) IssueReactionStore.put(context, key, id)
                    updateReaction(comment, content, delta = 1, mine = true)
                } else {
                    toast("添加反应失败，请重试")
                }
            }
            reactionsBusy[key] = false
        }
    }

    /** 勾选任务清单 / 改错别字：GitHub 没有「只改一个勾」的接口，必须 PATCH 整段正文 */
    fun patchCommentBody(comment: CommentItem, newBody: String) {
        scope.launch {
            val err = RustBridge.updateIssueComment(host, token, owner, repo, comment.id, newBody)
            if (err == null) {
                entries = entries.map { e ->
                    if (e is TimelineEntry.Comment && e.comment.id == comment.id) {
                        TimelineEntry.Comment(comment.copy(body = newBody, isEdited = true))
                    } else {
                        e
                    }
                }
            } else {
                toast("更新评论失败：$err")
            }
        }
    }

    fun patchIssueBody(newBody: String) {
        scope.launch {
            val err = RustBridge.updateIssueBody(host, token, owner, repo, number, newBody)
            if (err == null) {
                detail = detail?.copy(body = newBody)
                bodyHtml = null
            } else {
                toast("更新正文失败：$err")
            }
        }
    }

    /** 提交评论（或保存编辑） / 关闭并评论 */
    fun submit(text: String, closeAs: String?) {
        if (submitting) return
        val target = editing
        val body = text.trim()
        if (body.isEmpty() && closeAs == null) return
        if (target != null && body.isEmpty()) {
            toast("正文不能为空")
            return
        }
        submitting = true
        scope.launch {
            if (target != null) {
                // 编辑态：先保存评论正文，再（可选）关闭
                val err = RustBridge.updateIssueComment(host, token, owner, repo, target.id, body)
                if (err == null) {
                    entries = entries.map { e ->
                        if (e is TimelineEntry.Comment && e.comment.id == target.id) {
                            TimelineEntry.Comment(target.copy(body = body, isEdited = true))
                        } else {
                            e
                        }
                    }
                    editing = null
                    draft = ""
                    toast("已保存修改")
                } else {
                    toast("保存失败：$err")
                    submitting = false
                    return@launch
                }
            } else if (body.isNotEmpty()) {
                val err = RustBridge.createIssueComment(host, token, owner, repo, number, body)
                if (err == null) {
                    val newComment = CommentItem(
                        id = System.currentTimeMillis(),
                        author = login,
                        avatarUrl = null,
                        body = body,
                        createdAt = java.time.Instant.now().toString(),
                        authorAssociation = null,
                        reactions = emptyList(),
                        isIssueAuthor = detail?.author == login,
                    )
                    entries = entries + TimelineEntry.Comment(newComment)
                    draft = ""
                    previewTab = false
                    pendingScrollToEnd = true
                } else {
                    toast("评论失败：$err")
                }
            }
            if (closeAs != null) {
                val err = RustBridge.updateIssueState(host, token, owner, repo, number, "closed", closeAs)
                if (err == null) {
                    detail = detail?.copy(state = "closed", stateReason = closeAs)
                    entries = entries + TimelineEntry.Event(
                        kind = "closed",
                        actor = login,
                        text = "$login 关闭了此 issue" + if (closeAs == "not_planned") "（不计划实施）" else "（已完成）",
                        createdAt = java.time.Instant.now().toString(),
                    )
                } else {
                    toast("关闭失败：$err")
                }
            }
            submitting = false
        }
    }

    fun reopen() {
        scope.launch {
            val err = RustBridge.updateIssueState(host, token, owner, repo, number, "open", "reopened")
            if (err == null) {
                detail = detail?.copy(state = "open", stateReason = "reopened")
                entries = entries + TimelineEntry.Event(
                    kind = "reopened",
                    actor = login,
                    text = "$login 重新打开了此 issue",
                    createdAt = java.time.Instant.now().toString(),
                )
            } else {
                toast("重新打开失败：$err")
            }
        }
    }

    val comments = remember(entries) { entries.filterIsInstance<TimelineEntry.Comment>() }
    val filtered = remember(entries, filter) {
        when (filter) {
            TlFilter.ALL -> entries
            TlFilter.COMMENT -> entries.filterIsInstance<TimelineEntry.Comment>()
            TlFilter.EVENT -> entries.filterIsInstance<TimelineEntry.Event>()
        }
    }
    // 「显示更早的评论」：timeline 首屏可能很长，默认只画最近 30 条，避免一屏一个巨型 Lazy 列表
    val visibleEntries = remember(filtered, showAllOlder) {
        if (showAllOlder || filtered.size <= OLDER_THRESHOLD) filtered
        else listOf(filtered.first()) + filtered.takeLast(OLDER_THRESHOLD - 1)
    }
    val hiddenCount = filtered.size - visibleEntries.size

    // 提交成功后滚到时间线末尾：新评论在最后，不滚过去用户会以为没发出去
    LaunchedEffect(pendingScrollToEnd, visibleEntries.size) {
        if (pendingScrollToEnd) {
            listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
            pendingScrollToEnd = false
        }
    }

    Column(
        Modifier.fillMaxSize().background(Primer.BackgroundPrimary).statusBarsPadding().navigationBarsPadding(),
    ) {
        IssueAppBar(
            number = number,
            repoFullName = "$owner/$repo",
            issueUrl = issueUrl,
            onBack = onBack,
        )

        when {
            loading && detail == null -> IssueLoading()
            detail == null -> IssueCenteredText("加载失败")
            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 12.dp),
                ) {
                    item(key = "head") { IssueHead(detail!!) }

                    // 主帖：与评论同构（网页版也是把正文作为时间线的第一张卡）
                    item(key = "body") {
                        val d = detail!!
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .border(1.dp, Primer.Blue500.copy(alpha = 0.35f), RoundedCornerShape(10.dp)),
                        ) {
                            CommentHeader(
                                author = d.author,
                                avatarUrl = d.authorAvatar,
                                badge = "作者",
                                createdAt = d.createdAt,
                                edited = false,
                            ) {}
                            if (d.body.isNotBlank()) {
                                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                                    val html = bodyHtml
                                    if (html != null) {
                                        // 含 HTML / 图片的主帖退回 WebView 渲染（保真优先），
                                        // 代价是任务清单不可点：富文本里定位到具体某个勾的代价远高于收益。
                                        ReadmeWebView(html, host, owner, repo, "main", login, token, onLinkClick = {})
                                    } else {
                                        MarkdownBody(
                                            source = d.body,
                                            onLinkClick = { openLink(context, it) },
                                            onCopyCode = { copyToClipboard(context, it, "代码已复制") },
                                            onToggleTask = if (d.author == login) {
                                                ({ task ->
                                                    patchIssueBody(toggleTaskLine(d.body, task.line, !task.checked))
                                                })
                                            } else {
                                                null
                                            },
                                        )
                                    }
                                }
                            }
                            ReactionRow(d.reactions, { content -> toggleReaction(null, content) }, ::emojiOf)
                        }
                    }

                    // 时间线筛选条
                    item(key = "filter") {
                        TimelineFilterRow(
                            filter = filter,
                            onChange = { filter = it },
                            commentCount = comments.size,
                            eventCount = entries.size - comments.size,
                        )
                    }

                    if (hiddenCount > 0) {
                        item(key = "older") {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .border(1.dp, Primer.Gray300, RoundedCornerShape(9.dp))
                                    .clickable { showAllOlder = true }
                                    .padding(vertical = 9.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "显示更早的 $hiddenCount 条",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Primer.Blue500,
                                )
                            }
                        }
                    }

                    itemsIndexed(visibleEntries, key = { _, e -> entryKey(e) }) { _, entry ->
                        when (entry) {
                            is TimelineEntry.Comment -> {
                                val expanded = expandedComments[entry.comment.id] == true
                                val mineComment = entry.comment.author == login
                                CommentCard(
                                    comment = entry.comment,
                                    expanded = expanded,
                                    onToggleExpand = { expandedComments[entry.comment.id] = !expanded },
                                    onToggleReaction = { content -> toggleReaction(entry.comment, content) },
                                    onCopyCode = { copyToClipboard(context, it, "代码已复制") },
                                    onLinkClick = { openLink(context, it) },
                                    onCopyLink = { copyToClipboard(context, "$issueUrl#issuecomment-${entry.comment.id}", "已复制评论链接") },
                                    onCopyMarkdown = { copyToClipboard(context, entry.comment.body, "已复制 Markdown 原文") },
                                    onOpenBrowser = { openLink(context, issueUrl) },
                                    canEdit = mineComment,
                                    onEdit = {
                                        editing = entry.comment
                                        draft = entry.comment.body
                                        previewTab = false
                                        pendingScrollToEnd = false
                                    },
                                    onDelete = { deleting = entry.comment },
                                    onToggleTask = { task ->
                                        patchCommentBody(
                                            entry.comment,
                                            toggleTaskLine(entry.comment.body, task.line, !task.checked),
                                        )
                                    },
                                    linkBusy = reactionsBusy.values.any { it },
                                )
                            }
                            is TimelineEntry.Event -> EventRow(entry)
                        }
                    }
                }

                CommentComposer(
                    draft = draft,
                    onDraft = { draft = it },
                    previewTab = previewTab,
                    onPreviewTab = { previewTab = it },
                    submitting = submitting,
                    issueClosed = detail?.isOpen == false,
                    closeMenu = closeMenu,
                    onCloseMenu = { closeMenu = it },
                    onComment = { submit(draft, null) },
                    onCommentAndClose = { reason -> submit(draft, reason) },
                    onReopen = { reopen() },
                    editingAuthor = editing?.author,
                    onCancelEdit = {
                        editing = null
                        draft = ""
                        previewTab = false
                    },
                )
            }
        }
    }

    // 删除确认（不可逆操作 → 二次确认）
    deleting?.let { target ->
        DeleteCommentDialog(
            commentId = target.id,
            onDismiss = { deleting = null },
            onConfirm = { id ->
                deleting = null
                scope.launch {
                    val err = RustBridge.deleteIssueComment(host, token, owner, repo, id)
                    if (err == null) {
                        entries = entries.filterNot { e -> e is TimelineEntry.Comment && e.comment.id == id }
                        if (editing?.id == id) {
                            editing = null
                            draft = ""
                        }
                        toast("评论已删除")
                    } else {
                        toast("删除失败：$err")
                    }
                }
            },
        )
    }
}

/**
 * 删除评论的二次确认。
 *
 * 没有内联在调用处的原因：AlertDialog 必须是独立节点，
 * 且删除是**不可逆的远端操作** —— 入口（⋮ 菜单）与确认（弹窗）分开写，
 * 避免以后有人在菜单项里直接发请求。
 */
@Composable
private fun DeleteCommentDialog(
    commentId: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除这条评论？", fontSize = 15.sp, fontWeight = FontWeight.Bold) },
        text = {
            Text(
                "删除后无法恢复，评论会从 GitHub 上移除。",
                fontSize = 13.sp,
                color = Primer.TextSecondary,
            )
        },
        confirmButton = {
            Text(
                "删除",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Primer.Red500,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onConfirm(commentId) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        },
        dismissButton = {
            Text(
                "取消",
                fontSize = 13.sp,
                color = Primer.TextSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onDismiss() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        },
    )
}

@Composable
private fun IssueLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Primer.Blue500)
    }
}

@Composable
private fun IssueCenteredText(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 13.sp, color = Primer.TextTertiary)
    }
}

// ───────────────────────────────── 顶部栏 ─────────────────────────────────

@Composable
private fun IssueAppBar(
    number: Long,
    repoFullName: String,
    issueUrl: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(38.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Primer.IconPrimary)
        }
        Column(Modifier.weight(1f)) {
            Text("#$number", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary)
            Text(
                repoFullName,
                fontSize = 12.sp,
                color = Primer.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = { copyToClipboard(context, issueUrl, "已复制链接") }, modifier = Modifier.size(38.dp)) {
            Icon(Icons.Filled.ContentCopy, "复制链接", tint = Primer.IconPrimary, modifier = Modifier.size(18.dp))
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Filled.MoreVert, "更多", tint = Primer.IconPrimary)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("在浏览器打开", fontSize = 13.sp) },
                    onClick = { menu = false; openLink(context, issueUrl) },
                )
                DropdownMenuItem(
                    text = { Text("分享", fontSize = 13.sp) },
                    onClick = {
                        menu = false
                        runCatching {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, issueUrl)
                            }
                            context.startActivity(Intent.createChooser(send, "分享 issue"))
                        }
                    },
                )
            }
        }
    }
}

// ───────────────────────────────── 头部 ─────────────────────────────────

private data class StatePill(val label: String, val color: Color)

private fun statePillOf(d: IssueDetail): StatePill = when {
    d.isOpen -> StatePill("Open", Primer.Green500)
    d.stateReason == "not_planned" -> StatePill("Closed as not planned", Primer.Gray600)
    d.stateReason == "completed" -> StatePill("Closed as completed", Primer.Purple500)
    else -> StatePill("Closed", Primer.Red500)
}

@Composable
private fun IssueHead(d: IssueDetail) {
    val pill = statePillOf(d)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(pill.color)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (d.isOpen) Icons.Filled.ErrorOutline else Icons.Filled.Check,
                    null,
                    tint = Color.White,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(pill.label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "${d.author} 创建于 ${shortTime(d.createdAt)} · ${d.commentsCount} 条评论",
                fontSize = 12.sp,
                color = Primer.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(10.dp))
        Text(d.title, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary, lineHeight = 26.sp)

        if (d.labels.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                d.labels.take(6).forEach { label ->
                    Text(
                        label.name,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = label.onColor,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(label.color)
                            .padding(horizontal = 9.dp, vertical = 2.dp),
                    )
                }
            }
        }

        if (d.assignees.isNotEmpty() || d.milestone != null) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (d.assignees.isNotEmpty()) {
                    Icon(Icons.Filled.Person, null, tint = Primer.IconSecondary, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    d.assignees.take(3).forEach { a ->
                        Text(
                            "@$a",
                            fontSize = 12.sp,
                            color = Primer.TextSecondary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
                if (d.milestone != null) {
                    Icon(Icons.Filled.Tag, null, tint = Primer.IconSecondary, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(d.milestone, fontSize = 12.sp, color = Primer.TextSecondary)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Primer.Gray150))
    }
}

// ───────────────────────────────── 时间线筛选 ─────────────────────────────────

private enum class TlFilter(val label: String) { ALL("全部"), COMMENT("仅评论"), EVENT("仅事件") }

@Composable
private fun TimelineFilterRow(
    filter: TlFilter,
    onChange: (TlFilter) -> Unit,
    commentCount: Int,
    eventCount: Int,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TlFilter.entries.forEach { f ->
            val selected = f == filter
            val count = if (f == TlFilter.EVENT) eventCount else commentCount
            Text(
                "${f.label} $count",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) Color.White else Primer.TextSecondary,
                modifier = Modifier
                    .padding(end = 6.dp)
                    .clip(CircleShape)
                    .background(if (selected) Primer.Gray900 else Primer.Gray150)
                    .clickable { onChange(f) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

// ───────────────────────────────── 评论卡 ─────────────────────────────────

@Composable
private fun CommentShell(
    author: String,
    avatarUrl: String?,
    badge: String?,
    createdAt: String,
    text: String,
    reactions: List<ReactionSummary>,
    onCopyCode: (String) -> Unit,
    onLinkClick: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, Primer.Gray200, RoundedCornerShape(10.dp)),
    ) {
        CommentHeader(author, avatarUrl, badge, createdAt, edited = false) {}
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            MarkdownBody(text, onLinkClick = onLinkClick, onCopyCode = onCopyCode)
        }
        if (reactions.isNotEmpty()) {
            ReactionRow(reactions, {}, ::emojiOf)
        }
    }
}

@Composable
private fun CommentHeader(
    author: String,
    avatarUrl: String?,
    badge: String?,
    createdAt: String,
    edited: Boolean,
    trailing: @Composable () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Primer.Gray100)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AuthorAvatar(author, avatarUrl, 20)
        Spacer(Modifier.width(8.dp))
        Text(author, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary)
        if (badge != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                badge,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                color = if (badge == "作者") Primer.Blue500 else Primer.TextSecondary,
                modifier = Modifier
                    .clip(CircleShape)
                    .border(
                        1.dp,
                        if (badge == "作者") Primer.Blue500.copy(alpha = 0.5f) else Primer.Gray200,
                        CircleShape,
                    )
                    .background(if (badge == "作者") Primer.Blue500.copy(alpha = 0.08f) else Color.Transparent)
                    .padding(horizontal = 7.dp, vertical = 1.dp),
            )
        }
        Spacer(Modifier.width(6.dp))
        Text("评论于 ${shortTime(createdAt)}", fontSize = 11.5.sp, color = Primer.TextTertiary)
        if (edited) {
            Spacer(Modifier.width(4.dp))
            Text("· 已编辑", fontSize = 11.sp, color = Primer.TextTertiary)
        }
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun CommentCard(
    comment: CommentItem,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onToggleReaction: (String) -> Unit,
    onCopyCode: (String) -> Unit,
    onLinkClick: (String) -> Unit,
    onCopyLink: () -> Unit,
    onCopyMarkdown: () -> Unit,
    onOpenBrowser: () -> Unit,
    canEdit: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleTask: ((MdBlock.Task) -> Unit)?,
    linkBusy: Boolean,
) {
    var menu by remember { mutableStateOf(false) }
    // 长评折叠：超过 [COLLAPSE_CHARS] 先折起来，避免一条贴满日志的评论把时间线顶走
    val collapsible = comment.body.length > COLLAPSE_CHARS
    val shown = if (collapsible && !expanded) comment.body.take(COLLAPSE_CHARS) + "\n\n…" else comment.body

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(
                1.dp,
                if (comment.badge == "作者") Primer.Blue500.copy(alpha = 0.35f) else Primer.Gray200,
                RoundedCornerShape(10.dp),
            ),
    ) {
        CommentHeader(comment.author, comment.avatarUrl, comment.badge, comment.createdAt, comment.isEdited) {
            Box {
                Icon(
                    Icons.Filled.MoreVert,
                    "评论操作",
                    tint = Primer.IconSecondary,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { menu = true },
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("复制评论链接", fontSize = 13.sp) },
                        onClick = { menu = false; onCopyLink() },
                    )
                    DropdownMenuItem(
                        text = { Text("复制 Markdown 原文", fontSize = 13.sp) },
                        onClick = { menu = false; onCopyMarkdown() },
                    )
                    DropdownMenuItem(
                        text = { Text("在浏览器打开", fontSize = 13.sp) },
                        onClick = { menu = false; onOpenBrowser() },
                    )
                    if (canEdit) {
                        DropdownMenuItem(
                            text = { Text("编辑评论", fontSize = 13.sp) },
                            onClick = { menu = false; onEdit() },
                        )
                        DropdownMenuItem(
                            text = { Text("删除评论", fontSize = 13.sp, color = Primer.Red500) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }
        }

        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            MarkdownBody(
                source = shown,
                onLinkClick = onLinkClick,
                onCopyCode = onCopyCode,
                // 只有自己的评论能改（服务端也会拒绝别人的），折叠态下不给勾选入口
                onToggleTask = if (canEdit && !collapsible) onToggleTask else null,
            )
            if (collapsible) {
                Spacer(Modifier.height(4.dp))
                Text(
                    if (expanded) "收起" else "展开全文",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Primer.Blue500,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onToggleExpand() },
                )
            }
        }

        ReactionRow(comment.reactions, { content -> if (!linkBusy) onToggleReaction(content) }, ::emojiOf)
    }
}

/** 反应行：已有反应 + 一个「＋」（展开全部可选 emoji）。 */
@Composable
private fun ReactionRow(
    reactions: List<ReactionSummary>,
    onToggle: (String) -> Unit,
    emoji: (String) -> String,
) {
    var picker by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            reactions.forEach { r ->
                Text(
                    "${emoji(r.content)} ${r.count}",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (r.mine) Primer.Blue600 else Primer.TextSecondary,
                    modifier = Modifier
                        .clip(CircleShape)
                        .border(1.dp, if (r.mine) Primer.Blue500 else Primer.Gray200, CircleShape)
                        .background(if (r.mine) Primer.Blue500.copy(alpha = 0.10f) else Color.Transparent)
                        .clickable { onToggle(r.content) }
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                )
            }
            Text(
                "＋",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Primer.TextSecondary,
                modifier = Modifier
                    .clip(CircleShape)
                    .border(1.dp, Primer.Gray200, CircleShape)
                    .clickable { picker = true }
                    .padding(horizontal = 9.dp, vertical = 3.dp),
            )
        }
        DropdownMenu(expanded = picker, onDismissRequest = { picker = false }) {
            REACTION_ORDER.forEach { (content, e) ->
                DropdownMenuItem(
                    text = { Text("$e  添加反应", fontSize = 13.sp) },
                    onClick = { picker = false; onToggle(content) },
                )
            }
        }
    }
}

// ───────────────────────────────── 事件行 ─────────────────────────────────

@Composable
private fun EventRow(entry: TimelineEntry.Event) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(Primer.Gray150),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                when (entry.kind) {
                    "closed" -> Icons.Filled.Check
                    "reopened" -> Icons.Filled.Refresh
                    "referenced", "cross-referenced" -> Icons.Filled.Sync
                    "milestoned", "demilestoned" -> Icons.Filled.Tag
                    "labeled", "unlabeled" -> Icons.Filled.Tag
                    "assigned", "unassigned" -> Icons.Filled.Person
                    else -> Icons.Filled.Schedule
                },
                null,
                tint = Primer.IconSecondary,
                modifier = Modifier.size(12.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            entry.text,
            fontSize = 12.5.sp,
            color = Primer.TextSecondary,
            lineHeight = 18.sp,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(shortTime(entry.createdAt), fontSize = 11.sp, color = Primer.TextTertiary)
    }
}

// ───────────────────────────────── 吸底输入器 ─────────────────────────────────

@Composable
private fun CommentComposer(
    draft: String,
    onDraft: (String) -> Unit,
    previewTab: Boolean,
    onPreviewTab: (Boolean) -> Unit,
    submitting: Boolean,
    issueClosed: Boolean,
    closeMenu: Boolean,
    onCloseMenu: (Boolean) -> Unit,
    onComment: () -> Unit,
    onCommentAndClose: (String) -> Unit,
    onReopen: () -> Unit,
    /** 非 null = 正在编辑该用户的评论（主按钮变「保存修改」，并提供取消入口） */
    editingAuthor: String?,
    onCancelEdit: () -> Unit,
) {
    val editing = editingAuthor != null
    Column(
        Modifier
            .fillMaxWidth()
            .background(Primer.BackgroundPrimary)
            .border(1.dp, Primer.Gray150)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        // 编辑态提示：不显式说明的话，用户会以为自己在发新评论
        if (editing) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Primer.Blue500.copy(alpha = 0.08f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "正在编辑 @$editingAuthor 的评论",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Primer.Blue600,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "取消编辑",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Primer.Blue500,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onCancelEdit() }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }

        // 写 / 预览
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Primer.Gray150)
                    .padding(2.dp),
            ) {
                ComposerTab("写", !previewTab) { onPreviewTab(false) }
                ComposerTab("预览", previewTab) { onPreviewTab(true) }
            }
            Spacer(Modifier.weight(1f))
            if (issueClosed) {
                Text(
                    "重新打开",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Primer.Blue500,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onReopen() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))

        if (previewTab) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp, max = 220.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .border(1.dp, Primer.Gray200, RoundedCornerShape(9.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp),
            ) {
                if (draft.isBlank()) {
                    Text("还没有内容，切回「写」开始输入。", fontSize = 12.5.sp, color = Primer.TextTertiary)
                } else {
                    MarkdownBody(draft)
                }
            }
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp, max = 160.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .border(1.dp, Primer.Gray200, RoundedCornerShape(9.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp),
            ) {
                if (draft.isEmpty()) {
                    Text("留下评论（支持 Markdown）", fontSize = 13.sp, color = Primer.TextTertiary)
                }
                BasicTextField(
                    value = draft,
                    onValueChange = onDraft,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = Primer.TextPrimary),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Primer.Blue500),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        // Markdown 工具栏
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            MdTool("B", "加粗") { onDraft(wrap(draft, "**", "**")) }
            MdTool("I", "斜体") { onDraft(wrap(draft, "*", "*")) }
            MdTool("</>", "代码") { onDraft(wrap(draft, "`", "`")) }
            MdTool("≡", "列表") { onDraft("$draft\n- ") }
            MdTool("☑", "任务") { onDraft("$draft\n- [ ] ") }
            MdTool("❝", "引用") { onDraft("$draft\n> ") }
            MdTool("@", "提及") { onDraft("$draft@") }
            Spacer(Modifier.weight(1f))
            Text("Markdown 已支持", fontSize = 11.sp, color = Primer.TextTertiary)
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .border(1.dp, Primer.Gray200, RoundedCornerShape(9.dp))
                        .clickable(enabled = !issueClosed && !editing) { onCloseMenu(true) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        when {
                            editing -> "编辑中"
                            issueClosed -> "已关闭"
                            else -> "关闭并评论 ▾"
                        },
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (issueClosed || editing) Primer.TextTertiary else Primer.TextPrimary,
                    )
                }
                DropdownMenu(expanded = closeMenu, onDismissRequest = { onCloseMenu(false) }) {
                    DropdownMenuItem(
                        text = { Text("关闭并评论（已完成）", fontSize = 13.sp) },
                        onClick = { onCloseMenu(false); onCommentAndClose("completed") },
                    )
                    DropdownMenuItem(
                        text = { Text("关闭并评论（不计划实施）", fontSize = 13.sp) },
                        onClick = { onCloseMenu(false); onCommentAndClose("not_planned") },
                    )
                    DropdownMenuItem(
                        text = { Text("只提交评论，不关闭", fontSize = 13.sp) },
                        onClick = { onCloseMenu(false); onComment() },
                    )
                }
            }
            Row(
                Modifier
                    .weight(1f)
                    .height(34.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (draft.isBlank() || submitting) Primer.Gray300 else Primer.Blue500)
                    .clickable(enabled = draft.isNotBlank() && !submitting) { onComment() },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (submitting) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                } else {
                    Icon(Icons.Filled.Send, null, tint = Color.White, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    if (editing) "保存修改" else "评论",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
        }
    }
}

@Composable
private fun ComposerTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.SemiBold,
        color = if (selected) Primer.TextPrimary else Primer.TextSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Primer.BackgroundPrimary else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 3.dp),
    )
}

@Composable
private fun MdTool(label: String, desc: String, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = Primer.IconPrimary,
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 5.dp),
    )
}

private fun wrap(text: String, prefix: String, suffix: String): String = "$prefix$text$suffix"

/**
 * 把「我点过的反应」从本地登记表补回模型。
 *
 * 网络响应只能给计数，给不了「哪个是我」；本地登记表（[IssueReactionStore]）在
 * 添加反应时记下了 reaction id，这里按同一个键把 `mine` 标回来，
 * 否则每次重新进页面，自己点过的反应都会显示成未选中。
 */
private fun hydrateMine(
    context: Context,
    entries: List<TimelineEntry>,
    keyPrefix: String,
): List<TimelineEntry> = entries.map { e ->
    if (e !is TimelineEntry.Comment) return@map e
    val updated = e.comment.reactions.map { r ->
        r.copy(mine = IssueReactionStore.isMine(context, "$keyPrefix:c${e.comment.id}:${r.content}"))
    }
    TimelineEntry.Comment(e.comment.copy(reactions = updated))
}

// ───────────────────────────────── 工具 ─────────────────────────────────

private const val COLLAPSE_CHARS = 900
private const val OLDER_THRESHOLD = 30

/** 时间线条目 → LazyColumn 的稳定 key。 */
/** 反应列表 +1/-1 的公共实现（主帖与评论共用，避免两处各写一份导致「撤销后计数不为 0 仍显示」）。 */
private fun applyReactionDelta(
    list: List<ReactionSummary>,
    content: String,
    delta: Int,
    mine: Boolean,
): List<ReactionSummary> {
    val out = list.toMutableList()
    val idx = out.indexOfFirst { it.content == content }
    if (idx >= 0) {
        val old = out[idx]
        val count = (old.count + delta).coerceAtLeast(0)
        if (count == 0) out.removeAt(idx) else out[idx] = old.copy(count = count, mine = mine)
    } else if (delta > 0) {
        out += ReactionSummary(content, emojiOf(content), 1, mine)
    }
    // 固定顺序：+1 / -1 / laugh / hooray / confused / heart / rocket / eyes
    return out.sortedBy { r -> REACTION_ORDER.indexOfFirst { it.first == r.content } }
}

private fun entryKey(e: TimelineEntry): String = when (e) {
    is TimelineEntry.Comment -> "c${e.comment.id}"
    is TimelineEntry.Event -> "e${e.kind}-${e.createdAt}-${e.text.hashCode()}"
}

/** 正文是否必须用 WebView 渲染（HTML 标签 / 图片，MarkdownBody 不覆盖这两种）。 */
private fun needsRichRenderer(body: String): Boolean =
    body.contains(Regex("</?[A-Za-z][^>]*>")) || body.contains("![")

@Composable
private fun AuthorAvatar(login: String, url: String?, size: Int) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(Primer.Blue500),
        contentAlignment = Alignment.Center,
    ) {
        if (!url.isNullOrBlank()) {
            AsyncImage(model = url, contentDescription = login, modifier = Modifier.size(size.dp).clip(CircleShape))
        } else {
            Text(
                login.take(1).uppercase().ifBlank { "?" },
                color = Color.White,
                fontSize = (size * 0.5).sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private fun copyToClipboard(context: Context, text: String, okMessage: String) {
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("GitHub", text))
        Toast.makeText(context, okMessage, Toast.LENGTH_SHORT).show()
    }.onFailure { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
}

private fun openLink(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        .onFailure { Toast.makeText(context, "没有可用的浏览器", Toast.LENGTH_SHORT).show() }
}
