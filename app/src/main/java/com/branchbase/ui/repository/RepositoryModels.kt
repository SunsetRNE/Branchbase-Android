package com.branchbase.ui.repository

import org.json.JSONArray
import org.json.JSONObject

/**
 * 仓库详情页数据模型与 JSON 解析（对齐 `core/src/html` 解析器的输出 + GitHub REST）。
 */

// ── 仓库信息（GET /repos/{owner}/{repo}） ──
data class RepoInfo(
    val fullName: String,
    val name: String,
    val description: String,
    val stars: Long,
    val forks: Long,
    val watchers: Long,
    val license: String?,          // spdx_id，无则 null（显示「无」）
    val defaultBranch: String,
    val ownerLogin: String,
    /** 当前登录用户是否有写权限（`permissions.push`）—— 决定编辑/新建入口是否显示。 */
    val canPush: Boolean = false,
    /** 当前登录用户是否至少有读权限（`permissions.pull`）。 */
    val canPull: Boolean = true,
    /** 是否私有仓库（判定关系时用来兜底「能不能读到」）。 */
    val isPrivate: Boolean = false,
    /** owner 类型：`User` / `Organization`。 */
    val ownerType: String? = null,
    /** 当前登录用户是否有 issue 分诊权限（`permissions.triage`）—— 决定关闭/重开按钮。 */
    val canTriage: Boolean = false,
)

// ── 链接跳转目标（对齐 matcher 输出） ──
data class Destination(
    val type: String,              // repo/blob/tree/issue/pull/commit/user/anchor/external/raw
    val owner: String? = null,
    val repo: String? = null,
    val branch: String? = null,
    val path: String? = null,
    val lines: String? = null,     // 行号锚点（如 "L12-L34"）
    val number: Long? = null,
    val sha: String? = null,
    val login: String? = null,
    val url: String = "",
    val isOwn: Boolean = false,
    val isExternal: Boolean = false,
)

// ── 语言 / 贡献者 ──
data class LanguageStat(
    val name: String,
    val bytes: Long,
    val percent: Double,           // 0..100
)

data class Contributor(
    val login: String,
    val avatarUrl: String?,
    val commits: Long,
)

// ── 分支（GET /repos/{o}/{r}/branches） ──
data class BranchItem(
    val name: String,
    val protected: Boolean = false,
)

// ── 解析函数 ──

fun parseRepoInfo(json: String): RepoInfo? = runCatching {
    val o = JSONObject(json)
    RepoInfo(
        fullName = o.optString("full_name"),
        name = o.optString("name"),
        description = o.optString("description").orEmpty(),
        stars = o.optLong("stargazers_count"),
        forks = o.optLong("forks_count"),
        watchers = o.optLong("subscribers_count"),
        license = o.optJSONObject("license")?.optString("spdx_id")?.takeIf { it.isNotBlank() },
        defaultBranch = o.optString("default_branch", "main"),
        ownerLogin = o.optJSONObject("owner")?.optString("login").orEmpty(),
        // permissions 只在带 token 请求时返回；缺失即视为无写权限（保守）
        canPush = o.optJSONObject("permissions")?.optBoolean("push", false) ?: false,
        canPull = o.optJSONObject("permissions")?.optBoolean("pull", true) ?: true,
        isPrivate = o.optBoolean("private", false),
        ownerType = o.optJSONObject("owner")?.optString("type")?.takeIf { it.isNotBlank() },
    )
}.getOrNull()

internal fun parseDestination(d: JSONObject): Destination =
    Destination(
        type = d.optString("type"),
        owner = d.optString("owner").takeIf { it.isNotBlank() },
        repo = d.optString("repo").takeIf { it.isNotBlank() },
        branch = d.optString("branch").takeIf { it.isNotBlank() },
        path = d.optString("path").takeIf { it.isNotBlank() },
        lines = d.optString("lines").takeIf { it.isNotBlank() },
        number = if (d.has("number")) d.optLong("number") else null,
        sha = d.optString("sha").takeIf { it.isNotBlank() },
        login = d.optString("login").takeIf { it.isNotBlank() },
        url = d.optString("url"),
        isOwn = d.optBoolean("is_own"),
        isExternal = d.optBoolean("is_external"),
    )

/** 解析 GET /repos/{o}/{r}/languages 的 {语言:字节数}，计算占比并降序 */
fun parseLanguages(json: String): List<LanguageStat> = runCatching {
    val o = JSONObject(json)
    val total = o.keys().asSequence().sumOf { o.optLong(it) }
    if (total <= 0) return@runCatching emptyList()
    o.keys().asSequence()
        .map { name ->
            val bytes = o.optLong(name)
            LanguageStat(name, bytes, bytes.toDouble() / total * 100.0)
        }
        .sortedByDescending { it.bytes }
        .toList()
}.getOrDefault(emptyList())

/** 解析 GET /repos/{o}/{r}/contributors 的 JSON 数组 */
fun parseContributors(json: String): List<Contributor> = runCatching {
    val arr = JSONArray(json)
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        Contributor(
            login = o.optString("login"),
            avatarUrl = o.optString("avatar_url").takeIf { it.isNotBlank() },
            commits = o.optLong("contributions"),
        )
    }
}.getOrDefault(emptyList())

/** 解析 GET /repos/{o}/{r}/branches 的 JSON 数组 */
fun parseBranches(json: String): List<BranchItem> = runCatching {
    val arr = JSONArray(json)
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        BranchItem(
            name = o.optString("name"),
            protected = o.optBoolean("protected"),
        )
    }
}.getOrDefault(emptyList())

// ── 列表模型（Issue/PR/提交/发布/工作流/文件树/用户/复刻） ──

data class IssueItem(
    val number: Long,
    val title: String,
    val state: String,       // open / closed
    val author: String,
    val createdAt: String,
)

data class PullItem(
    val number: Long,
    val title: String,
    val state: String,       // open / closed
    val author: String,
    val createdAt: String,
)

data class CommitItem(
    val sha: String,
    val message: String,
    val author: String,
    val date: String,
)

/** 发布附件（可下载）。 */
data class ReleaseAsset(
    val id: Long,
    val name: String,
    val size: Long,
    val downloadUrl: String,
    val downloadCount: Long,
)

data class ReleaseItem(
    val id: Long = 0,
    val tag: String,
    val name: String,
    val createdAt: String,
    val body: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val author: String = "",
    val assets: List<ReleaseAsset> = emptyList(),
)

data class WorkflowItem(
    val id: Long,
    val name: String,
    val state: String,
    /** 工作流文件路径（如 `.github/workflows/ci.yml`）—— 读 YAML 判断能否手动触发 */
    val path: String = "",
    val htmlUrl: String = "",
    val badgeUrl: String = "",
)

data class FileTreeItem(
    val name: String,
    val type: String,        // "file" / "dir"
    val size: Long,
)

data class UserItem(
    val login: String,
    val avatarUrl: String?,
)

data class ForkItem(
    val fullName: String,
    val description: String,
    val language: String?,
    val stars: Long,
)

/** 解析 GET /repos/{o}/{r}/issues?state=… 数组 */
fun parseIssues(json: String): List<IssueItem> = runCatching {
    val arr = JSONArray(json)
    (0 until arr.length()).mapNotNull { i ->
        val o = arr.getJSONObject(i)
        // GitHub 的 /issues 端点会同时返回 PR（PR 底层也是 issue），
        // 过滤掉含 pull_request 字段的条目，避免 issue 页串台显示 PR 内容。
        if (o.optJSONObject("pull_request") != null) return@mapNotNull null
        IssueItem(
            number = o.optLong("number"),
            title = o.optString("title"),
            state = o.optString("state"),
            author = o.optJSONObject("user")?.optString("login").orEmpty(),
            createdAt = o.optString("created_at"),
        )
    }
}.getOrDefault(emptyList())

/** 解析 GET /repos/{o}/{r}/pulls?state=… 数组 */
fun parsePulls(json: String): List<PullItem> = runCatching {
    val arr = JSONArray(json)
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        PullItem(
            number = o.optLong("number"),
            title = o.optString("title"),
            state = o.optString("state"),
            author = o.optJSONObject("user")?.optString("login").orEmpty(),
            createdAt = o.optString("created_at"),
        )
    }
}.getOrDefault(emptyList())

/** 解析 GET /repos/{o}/{r}/commits 数组 */
fun parseCommits(json: String): List<CommitItem> = runCatching {
    val arr = JSONArray(json)
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        val commit = o.optJSONObject("commit") ?: JSONObject()
        val author = commit.optJSONObject("author")?.optString("name")
            ?: o.optJSONObject("author")?.optString("login").orEmpty()
        CommitItem(
            sha = o.optString("sha").take(7),
            message = commit.optString("message"),
            author = author,
            date = commit.optJSONObject("author")?.optString("date").orEmpty(),
        )
    }
}.getOrDefault(emptyList())

/** 解析 GET /repos/{o}/{r}/releases 数组 */
fun parseReleases(json: String): List<ReleaseItem> = runCatching {
    val arr = JSONArray(json)
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        val assetArr = o.optJSONArray("assets")
        val assets = if (assetArr == null) emptyList() else (0 until assetArr.length()).map { j ->
            val a = assetArr.getJSONObject(j)
            ReleaseAsset(
                id = a.optLong("id"),
                name = a.optString("name"),
                size = a.optLong("size"),
                downloadUrl = a.optString("browser_download_url"),
                downloadCount = a.optLong("download_count"),
            )
        }
        ReleaseItem(
            id = o.optLong("id"),
            tag = o.optString("tag_name"),
            name = o.optString("name").takeIf { it.isNotBlank() } ?: o.optString("tag_name"),
            // 草稿没有 published_at，退回 created_at
            createdAt = o.optString("published_at").takeIf { it.isNotBlank() } ?: o.optString("created_at"),
            body = o.optString("body").orEmpty(),
            draft = o.optBoolean("draft", false),
            prerelease = o.optBoolean("prerelease", false),
            author = o.optJSONObject("author")?.optString("login").orEmpty(),
            assets = assets,
        )
    }
}.getOrDefault(emptyList())

/** 解析 GET /repos/{o}/{r}/actions/workflows 的 {total_count, workflows:[…]} */
fun parseWorkflows(json: String): List<WorkflowItem> = runCatching {
    val arr = JSONObject(json).optJSONArray("workflows") ?: return@runCatching emptyList()
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        WorkflowItem(
            id = o.optLong("id"),
            name = o.optString("name"),
            state = o.optString("state"),
            path = o.optString("path"),
            htmlUrl = o.optString("html_url"),
            badgeUrl = o.optString("badge_url"),
        )
    }
}.getOrDefault(emptyList())

/** 解析 GET /repos/{o}/{r}/contents/{path} 数组（文件树） */
fun parseFileTree(json: String): List<FileTreeItem> = runCatching {
    val arr = JSONArray(json)
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        FileTreeItem(
            name = o.optString("name"),
            type = o.optString("type"),
            size = o.optLong("size"),
        )
    }
}.getOrDefault(emptyList())

/** 解析 stargazers / subscribers 数组（用户列表） */
fun parseUsers(json: String): List<UserItem> = runCatching {
    val arr = JSONArray(json)
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        UserItem(
            login = o.optString("login"),
            avatarUrl = o.optString("avatar_url").takeIf { it.isNotBlank() },
        )
    }
}.getOrDefault(emptyList())

/** 解析 forks 数组（复刻仓库列表） */
fun parseForks(json: String): List<ForkItem> = runCatching {
    val arr = JSONArray(json)
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        ForkItem(
            fullName = o.optString("full_name"),
            description = o.optString("description").orEmpty(),
            language = o.optString("language").takeIf { it.isNotBlank() },
            stars = o.optLong("stargazers_count"),
        )
    }
}.getOrDefault(emptyList())

/** 解析 GET /repos/{o}/{r}/contents/{path}（文件）的 {content: base64}，解码为文本 */
fun parseFileContent(json: String): String = runCatching {
    val o = JSONObject(json)
    val content = o.optString("content")
    if (o.optString("encoding") == "base64" && content.isNotBlank()) {
        String(android.util.Base64.decode(content.replace("\n", ""), android.util.Base64.DEFAULT), Charsets.UTF_8)
    } else content
}.getOrDefault("")

/** 拼接文件树路径 */
fun joinPath(parent: String, name: String): String = if (parent.isEmpty()) name else "$parent/$name"

/** URL 编码路径（逐段编码，保留 '/' 分隔；空格编码为 `%20` 而非 `+`，路径里 `+` 是字面加号） */
fun encodePath(path: String): String = path.split("/").joinToString("/") {
    java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")
}

/** URL 编码 ref/branch 参数（`/` → `%2F`，空格 → `%20`），供 `?ref=`/`?sha=`/`?base=`/`?branch=` 使用 */
fun encodeRef(ref: String): String =
    java.net.URLEncoder.encode(ref, "UTF-8").replace("+", "%20")

// ── 详情模型（Issue/PR/评论/文件变更） ──

/** 标签（名字 + 颜色）。颜色是 6 位十六进制（GitHub 给的是不带 `#` 的 RGB）。 */
data class LabelChip(val name: String, val colorHex: String) {
    /** 按亮度决定文字用黑还是白（网页版同款算法），否则浅色标签上的白字会看不见。 */
    val onColor: androidx.compose.ui.graphics.Color
        get() {
            val hex = colorHex.removePrefix("#")
            if (hex.length < 6) return androidx.compose.ui.graphics.Color.White
            val r = hex.substring(0, 2).toIntOrNull(16) ?: 0
            val g = hex.substring(2, 4).toIntOrNull(16) ?: 0
            val b = hex.substring(4, 6).toIntOrNull(16) ?: 0
            val lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
            return if (lum > 0.62) androidx.compose.ui.graphics.Color(0xFF24292F) else androidx.compose.ui.graphics.Color.White
        }

    val color: androidx.compose.ui.graphics.Color
        get() = runCatching {
            androidx.compose.ui.graphics.Color(0xFF000000L or (colorHex.removePrefix("#").toLong(16) and 0xFFFFFFL))
        }.getOrDefault(androidx.compose.ui.graphics.Color(0xFF6A6D7C))
}

/**
 * Issue 详情。
 *
 * 相比最初版本补齐了「网页版有、移动端此前没有」的元信息：
 * 状态原因（completed / not_planned）、标签颜色、指派者、里程碑、评论数。
 * 这些字段 REST 的 `/issues/{n}` 本来就返回，属于解析侧白丢的信息。
 */
data class IssueDetail(
    val number: Long,
    val title: String,
    val state: String,              // open / closed
    val stateReason: String?,       // completed / not_planned / reopened / null
    val body: String,
    val author: String,
    val authorAvatar: String?,
    val createdAt: String,
    val labels: List<LabelChip>,
    val assignees: List<String>,
    val milestone: String?,
    val commentsCount: Int,
    /** 主帖自身的反应（GitHub 的 issue 对象同样带 reactions 计数）。 */
    val reactions: List<ReactionSummary> = emptyList(),
) {
    val isOpen: Boolean get() = state.equals("open", ignoreCase = true)
}

/** 一条反应（emoji + 计数 + 我是否已选）。 */
data class ReactionSummary(
    val content: String,   // GitHub 的 content 值：+1 / -1 / laugh / hooray / confused / heart / rocket / eyes
    val emoji: String,
    val count: Int,
    val mine: Boolean,
)

/** 反应 content → emoji（顺序即界面展示顺序）。 */
val REACTION_ORDER: List<Pair<String, String>> = listOf(
    "+1" to "👍", "-1" to "👎", "laugh" to "😄", "hooray" to "🎉",
    "confused" to "😕", "heart" to "❤️", "rocket" to "🚀", "eyes" to "👀",
)

fun emojiOf(content: String): String = REACTION_ORDER.firstOrNull { it.first == content }?.second ?: "👍"

/** 一条评论。 */
data class CommentItem(
    val id: Long,
    val author: String,
    val avatarUrl: String?,
    val body: String,
    val createdAt: String,
    /** OWNER / MEMBER / COLLABORATOR / CONTRIBUTOR / NONE，用于「作者 / 协作者」徽章 */
    val authorAssociation: String?,
    val reactions: List<ReactionSummary>,
    val isIssueAuthor: Boolean,
    /** 编辑过的评论网页版会标「已编辑」；GitHub 用 updated_at != created_at 表达。 */
    val isEdited: Boolean = false,
) {
    /** 徽章文案（没有徽章时返回 null）：作者优先于协作者身份。 */
    val badge: String?
        get() = when {
            isIssueAuthor -> "作者"
            authorAssociation.equals("OWNER", true) -> "Owner"
            authorAssociation.equals("MEMBER", true) -> "Member"
            authorAssociation.equals("COLLABORATOR", true) -> "Collaborator"
            authorAssociation.equals("CONTRIBUTOR", true) -> "Contributor"
            else -> null
        }
}

/**
 * 时间线条目：评论与事件按时间混排。
 *
 * GitHub 的 `/issues/{n}/timeline` 把「评论」也作为一种 event（`commented`）返回，
 * 因此时间线是单一数据源；拿不到时间线时（网络/权限）回退到 `/comments` +
 * 「无事件」的降级形态，页面结构不变。
 */
sealed interface TimelineEntry {
    val createdAt: String

    data class Comment(val comment: CommentItem) : TimelineEntry {
        override val createdAt: String get() = comment.createdAt
    }

    data class Event(
        val kind: String,
        val actor: String,
        val text: String,
        override val createdAt: String,
    ) : TimelineEntry
}

data class PullDetail(
    val number: Long,
    val title: String,
    val state: String,
    val body: String,
    val author: String,
    val createdAt: String,
    val baseRef: String,
    val headRef: String,
)

data class PullFile(
    val filename: String,
    val status: String,
    val additions: Long,
    val deletions: Long,
)

/** 解析 GET /repos/{o}/{r}/issues/{n} */
fun parseIssueDetail(json: String): IssueDetail? = runCatching {
    val o = JSONObject(json)
    val labels = o.optJSONArray("labels")?.let { arr ->
        (0 until arr.length()).map { i ->
            val l = arr.getJSONObject(i)
            LabelChip(l.optString("name"), l.optString("color", "6A6D7C"))
        }
    } ?: emptyList()
    val assignees = o.optJSONArray("assignees")?.let { arr ->
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.optString("login")?.takeIf { it.isNotBlank() } }
    } ?: emptyList()
    IssueDetail(
        number = o.optLong("number"),
        title = o.optString("title"),
        state = o.optString("state"),
        stateReason = o.optString("state_reason").takeIf { it.isNotBlank() && it != "null" },
        body = o.optString("body").orEmpty(),
        author = o.optJSONObject("user")?.optString("login").orEmpty(),
        authorAvatar = o.optJSONObject("user")?.optString("avatar_url")?.takeIf { it.isNotBlank() },
        createdAt = o.optString("created_at"),
        labels = labels,
        assignees = assignees,
        milestone = o.optJSONObject("milestone")?.optString("title")?.takeIf { it.isNotBlank() && it != "null" },
        commentsCount = o.optInt("comments", 0),
        reactions = parseReactions(o.optJSONObject("reactions")),
    )
}.getOrNull()

/** 解析 reactions 子对象（缺失 / 全 0 时返回空列表，界面据此整行不渲染）。 */
fun parseReactions(o: JSONObject?, mine: Map<String, Boolean> = emptyMap()): List<ReactionSummary> {
    val r = o ?: return emptyList()
    return REACTION_ORDER.mapNotNull { (content, emoji) ->
        val count = r.optInt(content, 0)
        if (count <= 0) null else ReactionSummary(content, emoji, count, mine[content] == true)
    }
}

/** 把 timeline / comments 里的一条「评论对象」解析为 [CommentItem]。 */
fun parseCommentObject(o: JSONObject, issueAuthor: String = ""): CommentItem? {
    val body = o.optString("body").orEmpty()
    val id = o.optLong("id")
    if (id == 0L && body.isBlank()) return null
    val login = o.optJSONObject("user")?.optString("login").orEmpty()
    return CommentItem(
        id = id,
        author = login,
        avatarUrl = o.optJSONObject("user")?.optString("avatar_url")?.takeIf { it.isNotBlank() },
        body = body,
        createdAt = o.optString("created_at"),
        authorAssociation = o.optString("author_association").takeIf { it.isNotBlank() && it != "null" },
        reactions = parseReactions(o.optJSONObject("reactions")),
        isIssueAuthor = issueAuthor.isNotBlank() && login == issueAuthor,
        isEdited = o.optString("updated_at").let { u -> u.isNotBlank() && u != o.optString("created_at") },
    )
}

/** 解析 GET /repos/{o}/{r}/issues/{n}/comments 数组（无事件信息的降级路径） */
fun parseComments(json: String, issueAuthor: String = ""): List<CommentItem> = runCatching {
    val arr = JSONArray(json)
    (0 until arr.length()).mapNotNull { i -> parseCommentObject(arr.getJSONObject(i), issueAuthor) }
}.getOrDefault(emptyList())

/**
 * 解析 GET /repos/{o}/{r}/issues/{n}/timeline（`Accept: application/vnd.github+json`）。
 *
 * 只保留**移动端能讲清楚**的事件类型，未知事件一律忽略 —— 宁可少一条事件，
 * 也不要出现「某事件」这种没有信息量的占位行。事件文案在解析期就拼好，
 * 界面层只负责画，避免渲染逻辑里塞满 when 分支。
 */
fun parseIssueTimeline(json: String, issueAuthor: String = ""): List<TimelineEntry> = runCatching {
    val arr = JSONArray(json)
    (0 until arr.length()).mapNotNull { i ->
        val o = arr.getJSONObject(i)
        val event = o.optString("event")
        val actor = o.optJSONObject("actor")?.optString("login")
            ?: o.optJSONObject("user")?.optString("login").orEmpty()
        val at = o.optString("created_at")

        if (event == "commented") {
            return@mapNotNull parseCommentObject(o, issueAuthor)?.let { TimelineEntry.Comment(it) }
        }

        val text: String? = when (event) {
            "labeled" -> o.optJSONObject("label")?.optString("name")
                ?.let { "$actor 添加了标签「$it」" }
            "unlabeled" -> o.optJSONObject("label")?.optString("name")
                ?.let { "$actor 移除了标签「$it」" }
            "assigned" -> o.optJSONObject("assignee")?.optString("login")
                ?.let { "$actor 指派给 @$it" }
            "unassigned" -> o.optJSONObject("assignee")?.optString("login")
                ?.let { "$actor 取消了 @$it 的指派" }
            "closed" -> {
                val reason = o.optString("state_reason")
                val suffix = if (reason == "not_planned") "（不计划实施）" else "（已完成）"
                "$actor 关闭了此 issue$suffix"
            }
            "reopened" -> "$actor 重新打开了此 issue"
            "milestoned" -> o.optJSONObject("milestone")?.optString("title")
                ?.let { "$actor 加入里程碑「$it」" }
            "demilestoned" -> o.optJSONObject("milestone")?.optString("title")
                ?.let { "$actor 移除了里程碑「$it」" }
            "renamed" -> o.optJSONObject("rename")?.let { r ->
                val from = r.optString("from")
                val to = r.optString("to")
                "$actor 把标题从「$from」改为「$to」"
            }
            "referenced" -> {
                val sha = o.optString("commit_id").take(7)
                if (sha.isBlank()) "$actor 引用了此 issue" else "$actor 在提交 $sha 中引用了此 issue"
            }
            "cross-referenced" -> {
                val src = o.optJSONObject("source")?.optJSONObject("issue")
                val num = src?.optLong("number") ?: 0L
                val title = src?.optString("title").orEmpty()
                if (num > 0) "$actor 在 #$num $title 中引用了此 issue" else "$actor 引用了此 issue"
            }
            else -> null
        }
        text?.let { TimelineEntry.Event(event, actor, it, at) }
    }
}.getOrDefault(emptyList())

/** 解析 GET /repos/{o}/{r}/pulls/{n} */
fun parsePullDetail(json: String): PullDetail? = runCatching {
    val o = JSONObject(json)
    PullDetail(
        number = o.optLong("number"),
        title = o.optString("title"),
        state = o.optString("state"),
        body = o.optString("body").orEmpty(),
        author = o.optJSONObject("user")?.optString("login").orEmpty(),
        createdAt = o.optString("created_at"),
        baseRef = o.optJSONObject("base")?.optString("ref").orEmpty(),
        headRef = o.optJSONObject("head")?.optString("ref").orEmpty(),
    )
}.getOrNull()

/** 解析 GET /repos/{o}/{r}/pulls/{n}/files 数组 */
fun parsePullFiles(json: String): List<PullFile> = runCatching {
    val arr = JSONArray(json)
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        PullFile(
            filename = o.optString("filename"),
            status = o.optString("status"),
            additions = o.optLong("additions"),
            deletions = o.optLong("deletions"),
        )
    }
}.getOrDefault(emptyList())

data class CommitFile(
    val filename: String,
    val additions: Long,
    val deletions: Long,
    val patch: String,
)

data class CommitDetail(
    val sha: String,
    val message: String,
    val author: String,
    val date: String,
    val files: List<CommitFile>,
)

/** 解析 GET /repos/{o}/{r}/commits/{sha} */
fun parseCommitDetail(json: String): CommitDetail? = runCatching {
    val o = JSONObject(json)
    val commit = o.optJSONObject("commit") ?: JSONObject()
    val filesArr = o.optJSONArray("files") ?: JSONArray()
    val files = (0 until filesArr.length()).map { i ->
        val f = filesArr.getJSONObject(i)
        CommitFile(
            filename = f.optString("filename"),
            additions = f.optLong("additions"),
            deletions = f.optLong("deletions"),
            patch = f.optString("patch").orEmpty(),
        )
    }
    CommitDetail(
        sha = o.optString("sha").take(7),
        message = commit.optString("message"),
        author = o.optJSONObject("author")?.optString("login")
            ?: commit.optJSONObject("author")?.optString("name").orEmpty(),
        date = commit.optJSONObject("author")?.optString("date").orEmpty(),
        files = files,
    )
}.getOrNull()

// ── 工作流（Actions）模型 ──

data class WorkflowRun(
    val id: Long,
    val runNumber: Long,
    val name: String,
    val status: String,
    val conclusion: String?,
    val headBranch: String,
    val createdAt: String,
    val displayTitle: String = "",
    val event: String = "",
    val runAttempt: Int = 1,
    val headSha: String = "",
    val actor: String = "",
    val runStartedAt: String = "",
    val updatedAt: String = "",
    val htmlUrl: String = "",
    val path: String = "",
    val workflowId: Long = 0,
)

data class RunJob(
    val id: Long,
    val name: String,
    val status: String,
    val conclusion: String?,
    val startedAt: String = "",
    val completedAt: String = "",
    val runnerName: String = "",
    val htmlUrl: String = "",
    /** jobs 接口本身就返回 steps，运行详情页一次请求即可拿到全部步骤 */
    val steps: List<JobStep> = emptyList(),
)

data class JobStep(
    val number: Long,
    val name: String,
    val status: String,
    val conclusion: String?,
    val startedAt: String = "",
    val completedAt: String = "",
)

/** 解析 GET .../actions/workflows/{id}/runs 的 {workflow_runs:[…]} */
fun parseWorkflowRuns(json: String): List<WorkflowRun> = runCatching {
    val arr = JSONObject(json).optJSONArray("workflow_runs") ?: return@runCatching emptyList()
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        WorkflowRun(
            id = o.optLong("id"),
            runNumber = o.optLong("run_number"),
            name = o.optString("name"),
            status = o.optString("status"),
            conclusion = o.optString("conclusion").takeIf { it.isNotBlank() },
            headBranch = o.optString("head_branch"),
            createdAt = o.optString("created_at"),
            displayTitle = o.optString("display_title"),
            event = o.optString("event"),
            runAttempt = o.optInt("run_attempt", 1),
            headSha = o.optString("head_sha"),
            actor = o.optJSONObject("actor")?.optString("login").orEmpty(),
            runStartedAt = o.optString("run_started_at"),
            updatedAt = o.optString("updated_at"),
            htmlUrl = o.optString("html_url"),
            path = o.optString("path"),
            workflowId = o.optLong("workflow_id"),
        )
    }
}.getOrDefault(emptyList())

/** 解析 GET .../actions/runs/{run_id}/jobs 的 {jobs:[…]} */
fun parseRunJobs(json: String): List<RunJob> = runCatching {
    val arr = JSONObject(json).optJSONArray("jobs") ?: return@runCatching emptyList()
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        RunJob(
            id = o.optLong("id"),
            name = o.optString("name"),
            status = o.optString("status"),
            conclusion = o.optString("conclusion").takeIf { it.isNotBlank() },
            startedAt = o.optString("started_at"),
            completedAt = o.optString("completed_at"),
            runnerName = o.optString("runner_name"),
            htmlUrl = o.optString("html_url"),
            steps = parseJobSteps(o.toString()),
        )
    }
}.getOrDefault(emptyList())

/** 解析 GET .../actions/jobs/{job_id} 的 {steps:[…]} */
fun parseJobSteps(json: String): List<JobStep> = runCatching {
    val arr = JSONObject(json).optJSONArray("steps") ?: return@runCatching emptyList()
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        JobStep(
            number = o.optLong("number"),
            name = o.optString("name"),
            status = o.optString("status"),
            conclusion = o.optString("conclusion").takeIf { it.isNotBlank() },
            startedAt = o.optString("started_at"),
            completedAt = o.optString("completed_at"),
        )
    }
}.getOrDefault(emptyList())

/**
 * 当前账号与某个仓库的关系。
 *
 * 判定依据全部来自 GitHub 返回的字段（`owner.login` / `permissions` / `private`），
 * 不猜、不额外发请求：
 *
 * | 关系 | 判定 |
 * |------|------|
 * | [OWN] | `owner.login` == 当前登录账号（**账号仓库**） |
 * | [COLLABORATOR] | 不是 owner，但 `permissions.push`（**账号协作仓库**：受邀协作 / 组织成员） |
 * | [FOREIGN] | 不是 owner 且没有写权限，但**能读到**（**非账号仓库**：别人的公开仓库） |
 * | [NOT_COLLABORATOR] | 既不是 owner、也没有协作权限（**非自身协作仓库**：通常是无权限的私有仓库） |
 *
 * 用途：决定「能做什么」（提交/推送入口）、列表分组与徽章文案。
 */
enum class RepoRelation(val label: String) {
    OWN("账号仓库"),
    COLLABORATOR("协作仓库"),
    FOREIGN("非账号仓库"),
    NOT_COLLABORATOR("非协作仓库"),
    ;

    /** 是否有写权限（能提交 / 推送）。 */
    val canWrite: Boolean get() = this == OWN || this == COLLABORATOR
}

/**
 * 判定当前账号与仓库的关系（**纯函数**，便于单测）。
 *
 * @param ownerLogin 仓库 owner 的 login
 * @param me 当前登录账号
 * @param canPush `permissions.push`
 * @param canPull `permissions.pull`；字段缺失时用 [isPrivate] 兜底判断「能不能读到」
 * @param isPrivate 仓库是否私有（`private`）
 */
fun repoRelationOf(
    ownerLogin: String?,
    me: String,
    canPush: Boolean = false,
    canPull: Boolean = false,
    isPrivate: Boolean = false,
): RepoRelation {
    if (ownerLogin.isNullOrBlank() || me.isBlank()) return RepoRelation.FOREIGN
    if (ownerLogin.equals(me, ignoreCase = true)) return RepoRelation.OWN
    if (canPush) return RepoRelation.COLLABORATOR
    // 能读到就算「非账号仓库」；私有且读权限都没给到 → 我们其实看不到它（权限缺失/被撤销）
    val readable = canPull || !isPrivate
    return if (readable) RepoRelation.FOREIGN else RepoRelation.NOT_COLLABORATOR
}
