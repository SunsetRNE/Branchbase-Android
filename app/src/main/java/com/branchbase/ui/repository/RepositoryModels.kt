package com.branchbase.ui.repository

import org.json.JSONArray
import org.json.JSONObject
import android.content.Context
import androidx.annotation.StringRes
import com.branchbase.R
import com.branchbase.ui.LocalizedText

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
    /**
     * 是否允许复刻（`allow_forking`）。
     *
     * 组织可以整体关闭复刻，此时网页版的复刻按钮是禁用态；判定见
     * [RepoRelationRules.forkDecision]。默认 `true`：字段缺失（老缓存）时不能把按钮误判成禁用。
     */
    val allowForking: Boolean = true,
    /** 本仓库自身是否是一个复刻（`fork`）。 */
    val isFork: Boolean = false,
    /** 复刻来源的 `owner/name`（`parent.full_name`），非复刻为 null。 */
    val parentFullName: String? = null,
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
        // 复刻能力：这三个字段一直就在响应里，此前被整段丢掉 —— 于是复刻按钮
        // 只能靠一次额外请求（或干脆不判）决定形态。默认 true / false 是「不误判禁用」。
        allowForking = if (o.has("allow_forking")) o.optBoolean("allow_forking", true) else true,
        isFork = o.optBoolean("fork", false),
        parentFullName = o.optJSONObject("parent")
            ?.optString("full_name")
            ?.takeIf { it.isNotBlank() },
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

/**
 * 解析**单体**发布接口 `GET /repos/{o}/{r}/releases/{id}`（返回对象，不是数组）。
 *
 * 复用 [parseReleases]：套一层方括号就成数组了，免得再抄一遍十个字段
 * （与 `parseWorkflowRun` 同一手法）。
 */
fun parseSingleRelease(json: String?): ReleaseItem? {
    if (json.isNullOrBlank() || json.startsWith("ERROR:")) return null
    return parseReleases("[$json]").firstOrNull()
}

/**
 * 挑出「需要回源单体接口补齐附件」的 release。
 *
 * ## 为什么需要这个
 *
 * 列表接口 `GET /repos/{o}/{r}/releases` 对**刚发布**的 release 会返回空 `assets` 数组，
 * 而同一时刻 `GET /releases/{id}` 已经是正确的。
 *
 * **它是什么**：2026-09-23 那次实测（每 4 分钟采一次、采了一小时）显示 —— **同一个 release、
 * 同一个端点，值在 `0` 与 `3` 之间反复横跳**（09:42 恢复 → 09:47 又变回 0 → 10:15 恢复 →
 * 10:20 又全部变 0 → 10:24 起稳定）。所以这**不是「按 release 年龄的缓存窗口」**，
 * 而是 GitHub 把请求分散到了数据不一致的多个副本上；约 2.5 小时后收敛。
 * 我最初按单点采样推出的「窗口 1~2 小时」是被这个假象骗了。
 *
 * 网页端也露了同一个馅：发布页标题旁的「Assets N」计数显示成「0 个上传附件 + 2 个源码包」，
 * 但**同一页的附件列表本身是完整的**（那份列表由另一个端点渲染）。
 * 顺带一个坑：`GET /releases/tags/{tag}` 也会低报 `assets`（健康的 release 它同样报 0），
 * 判断附件数**不能**用它。
 *
 * **App 只读列表接口**（见 `RepositoryListScreens` 的 releases 加载），于是「刚发完版想立刻装」
 * 这个最常见的动作会看到「没有附件」，只能去装上一版 —— 所以这里要回源补一次。
 *
 * ## 边界
 *
 * - 只挑 `assets` 为空的，正常 release 一次都不多请求；
 * - `id == 0` 的（解析异常）跳过，否则会拼出 `/releases/0`；
 * - 最多 [limit] 条 —— 列表是新→旧排的，所以天然只补最新的几条。
 *   真有仓库整仓都不传附件时，上限保证不会每个 release 都发一次请求。
 */
fun releasesNeedingAssetBackfill(items: List<ReleaseItem>, limit: Int = 3): List<ReleaseItem> =
    items.filter { it.id != 0L && it.assets.isEmpty() }.take(limit)

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

/**
 * 仓库文件列表的**展示顺序**（对齐 GitHub 网页版那一列，2026-09-26 用户拍板）。
 *
 * 三条规则，按优先级：
 * 1. **文件夹优先、文件其次**；
 * 2. 同类型内 **`.` 开头的排最前**（对齐性规则：`.github` / `.gitignore` 永远在最上面）；
 * 3. 其余按名字 **A→Z**（大小写敏感，见下）。
 *
 * 为什么名字用**大小写敏感的字节序**、而不是 `lowercase()` 再比：GitHub 的列表就是这么排的
 * （实测 `rust-lang/rust` 根目录 —— 目录 `.github` `LICENSES` `compiler` `library` `src` `tests`；
 * 文件 `.clang-format` … `AGENTS.md` … `INSTALL.md` … `README.md` … `yarn.lock`，大写在前）。
 * 而 **GitHub contents API 返回的是混着的纯名字序**（`.clang-format` `.github` `.gitignore`
 * `AGENTS.md` `LICENSES` `compiler` …，目录不提前）—— 所以要「对齐 GitHub」，渲染前必须自己排一次。
 *
 * `symlink` / `submodule` 归到**文件**侧：与点击行为一致（只有 `type == "dir"` 才会进目录）。
 */
fun sortFileTree(items: List<FileTreeItem>): List<FileTreeItem> = items.sortedWith(
    compareBy(
        { if (it.type == "dir") 0 else 1 },
        { if (it.name.startsWith(".")) 0 else 1 },
        { it.name },
    ),
)

/**
 * 解析 GET /repos/{o}/{r}/contents/{path} 数组（文件树）。
 *
 * 解析出来**就已经是** [sortFileTree] 的 GitHub 顺序 —— 排序只有这一个真源：
 * 缓存直出与回源两条路径都经过这里，渲染层不再各排一遍（否则两条路径会排出两种顺序）。
 */
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
}.getOrDefault(emptyList()).let(::sortFileTree)

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

/**
 * 上一层目录（纯函数，便于单测）：`"core/src"` → `"core"`，已经在根目录时 → `""`。
 *
 * 与 [joinPath] 是一对：进目录用它、退目录用这个，免得退回时各写一段 `substringBeforeLast`，
 * 少一处就多一条「退到 `/` 或空段」的线上 bug。
 */
fun parentPath(path: String): String =
    path.split("/").filter { it.isNotBlank() }.dropLast(1).joinToString("/")

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
    /**
     * 徽章的**资源 ID**（没有徽章时返回 null）：作者优先于协作者身份。
     *
     * 模型层不认识 Context，所以只给 ID。顺带修掉一个 i18n 缺口：
     * `Owner` / `Member` 原来是英文**字面量**，中文界面下也照样显示英文。
     */
    val badgeRes: Int?
        get() = when {
            isIssueAuthor -> R.string.badge_author
            authorAssociation.equals("OWNER", true) -> R.string.badge_owner
            authorAssociation.equals("MEMBER", true) -> R.string.badge_member
            authorAssociation.equals("COLLABORATOR", true) -> R.string.badge_collaborator
            authorAssociation.equals("CONTRIBUTOR", true) -> R.string.badge_contributor
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
        val text: LocalizedText,
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
    /**
     * `GET /pulls/{n}` 的 `merged`：是否已被合并。
     *
     * 带默认值是为了不影响只关心展示字段的构造点（手写详情的测试等）。
     */
    val merged: Boolean = false,
    /**
     * `GET /pulls/{n}` 的 `mergeable`（**三态**）：
     *
     * - `true`  GitHub 认为可以自动合并；
     * - `false` GitHub 明确判定不可自动合并（冲突 / 分支保护）；
     * - `null`  **未知** —— GitHub 还在后台计算，响应里就是 JSON `null`。
     *
     * `null` 绝不能落到 `false`：「还没算出来」与「不能合并」是两回事，
     * 前者不该给用户看一句凭空捏造的拒绝理由（[pullMergeEntry] 也不把它当 false）。
     */
    val mergeable: Boolean? = null,
    /**
     * `GET /pulls/{n}` 的 `head.repo.full_name`（**PR 的来源仓库**，`owner/name`）。
     *
     * 它决定「拉到本地解决」能不能成立：引擎的 `merge_branch` 只从本地仓库的 `origin` 拉，
     * 而复刻仓库里的 head 分支在 `origin` 上根本不存在 —— 那种情况入口要如实置灰，
     * 而不是让用户点下去才看到一句「找不到分支」。空串 = 响应里没带（缺键 / 老数据），
     * 那时**不预判**（按同仓库处理），与合并入口对 `mergeable == null` 的口径一致。
     */
    val headRepoFullName: String = "",
)

/** 详情页「合并」入口的三种形态：不露出 / 可点 / 置灰（附原因）。 */
enum class PullMergeEntry { Hidden, Enabled, Disabled }

/**
 * 「合并」入口的可见性规则（纯函数，便于单测钉住）。
 *
 * - 只有 `state == "open"` 且**未合并**的 PR 才有可合并的东西 → 其余 [Hidden]
 *   （已合并 / 已关闭的 PR 再点合并，服务端只会回一句错误）；
 * - `mergeable == false` 时**不藏、置灰**（[Disabled]）—— 用户需要知道为什么点不了；
 * - `mergeable == null`（GitHub 还在算）**放行**：算完之前不替 GitHub 下结论，
 *   真不可合并时合并页会把服务端错误原样回报；
 * - head / base 分支名为空同样置灰：合并页的副标题与「合并后删分支」都依赖它们。
 */
fun pullMergeEntry(
    state: String,
    merged: Boolean,
    mergeable: Boolean?,
    headRef: String,
    baseRef: String,
): PullMergeEntry = when {
    state != "open" || merged -> PullMergeEntry.Hidden
    mergeable == false || headRef.isBlank() || baseRef.isBlank() -> PullMergeEntry.Disabled
    else -> PullMergeEntry.Enabled
}

/**
 * 入口下方那句说明（入口露出时才有意义）：
 *
 * - `mergeable == false` → 拒绝原因（对应置灰态）；
 * - 分支信息缺失 → 为什么点不了；
 * - `mergeable == null` → 「还在算」的提醒（可点，但可能被拒）；
 * - 其余（可合并）→ `null`，不加文案。
 */
fun pullMergeHintRes(mergeable: Boolean?, headRef: String, baseRef: String): Int? = when {
    mergeable == false -> R.string.merge_hint_conflict
    headRef.isBlank() || baseRef.isBlank() -> R.string.merge_hint_missing_branch
    mergeable == null -> R.string.merge_hint_calculating
    else -> null
}

/** 详情页「拉到本地解决」入口的三种形态（与 [PullMergeEntry] 同一套路）。 */
enum class PullLocalResolveEntry { Hidden, Enabled, Disabled }

/**
 * 「拉到本地解决」入口的可见性规则（纯函数，便于单测钉住）。
 *
 * 它是 `mergeable == false`（PR 在 GitHub 侧冲突）时的**第二条路**：把这个 PR 的 head 分支
 * 合到本地仓库的当前分支上，在本地逐文件解决（阶段 5 的合并流程已经能把这条路走完）。
 *
 * - 只有 `mergeable == false` 才露出：能自动合并的 PR 走 GitHub 那条更直接，
 *   给第二条路只会让人以为「合并」按钮坏了；
 * - 本地没有这个仓库的副本 → 置灰 + 说明：合并要有本地仓库，先去「设置 → 本地仓库」拉一份；
 * - PR 的 head 在**复刻仓库**里（`headRepoFullName` 与当前仓库不同）→ 置灰 + 说明：
 *   本地副本的 `origin` 上没有那条分支（引擎只从 `origin` 拉），点下去只能得到一句「找不到分支」；
 * - `headRepoFullName` 为空（响应没带）**不预判** —— 与 `mergeable == null` 同一条口径。
 */
fun pullLocalResolveEntry(
    mergeable: Boolean?,
    localRepoExists: Boolean,
    headRepoFullName: String,
    headRef: String,
    ownerRepo: String,
): PullLocalResolveEntry = when {
    mergeable != false || headRef.isBlank() -> PullLocalResolveEntry.Hidden
    !localRepoExists -> PullLocalResolveEntry.Disabled
    isForkHead(headRepoFullName, ownerRepo) -> PullLocalResolveEntry.Disabled
    else -> PullLocalResolveEntry.Enabled
}

/**
 * 置灰的那一句理由（可点时也用它给一句「会发生什么」的说明）；null = 不加文案。
 *
 * 与 [pullLocalResolveEntry] 一一对应：**每一个 Disabled 都要说得出原因**，
 * 否则用户只能看到一枚点不动的按钮。
 */
fun pullLocalResolveHintRes(
    mergeable: Boolean?,
    localRepoExists: Boolean,
    headRepoFullName: String,
    headRef: String,
    ownerRepo: String,
): Int? = when {
    mergeable != false || headRef.isBlank() -> null
    !localRepoExists -> R.string.merge_local_hint_no_local
    isForkHead(headRepoFullName, ownerRepo) -> R.string.merge_local_hint_fork
    else -> R.string.merge_local_hint_ready
}

/**
 * PR 的 head 是不是**复刻仓库**里的分支。
 *
 * 只在两边都非空时才能判断：空串（响应没带 / 缺键）算「不知道」，不当作复刻 ——
 * 把「不知道」渲染成「复刻仓库」会凭空造出一句拒绝理由。
 */
private fun isForkHead(headRepoFullName: String, ownerRepo: String): Boolean =
    headRepoFullName.isNotBlank() && ownerRepo.isNotBlank() &&
        !headRepoFullName.equals(ownerRepo, ignoreCase = true)

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

        // 事件文案是**带参数的整句**，所以不能只给一个资源 ID —— 用 LocalizedText 把
        // 「ID + 参数」一起带出去，渲染时再按界面语言解析。
        // 顺带消灭了原来的拼接：`"$actor 关闭了此 issue$suffix"` 里的后缀
        // （（已完成）/（不计划实施））已经并进整句，英文语序下才拼得对。
        val text: LocalizedText? = when (event) {
            "labeled" -> o.optJSONObject("label")?.optString("name")
                ?.let { LocalizedText(R.string.timeline_label_added, listOf(actor, it)) }
            "unlabeled" -> o.optJSONObject("label")?.optString("name")
                ?.let { LocalizedText(R.string.timeline_label_removed, listOf(actor, it)) }
            "assigned" -> o.optJSONObject("assignee")?.optString("login")
                ?.let { LocalizedText(R.string.timeline_assigned, listOf(actor, it)) }
            "unassigned" -> o.optJSONObject("assignee")?.optString("login")
                ?.let { LocalizedText(R.string.timeline_unassigned, listOf(actor, it)) }
            "closed" -> {
                val res = if (o.optString("state_reason") == "not_planned") {
                    R.string.timeline_closed_not_planned
                } else {
                    R.string.timeline_closed_completed
                }
                LocalizedText(res, listOf(actor))
            }
            "reopened" -> LocalizedText(R.string.timeline_reopened, listOf(actor))
            "milestoned" -> o.optJSONObject("milestone")?.optString("title")
                ?.let { LocalizedText(R.string.timeline_milestoned, listOf(actor, it)) }
            "demilestoned" -> o.optJSONObject("milestone")?.optString("title")
                ?.let { LocalizedText(R.string.timeline_demilestoned, listOf(actor, it)) }
            "renamed" -> o.optJSONObject("rename")?.let { r ->
                LocalizedText(
                    R.string.timeline_renamed,
                    listOf(actor, r.optString("from"), r.optString("to")),
                )
            }
            "referenced" -> {
                val sha = o.optString("commit_id").take(7)
                if (sha.isBlank()) {
                    LocalizedText(R.string.timeline_referenced_issue, listOf(actor))
                } else {
                    LocalizedText(R.string.timeline_referenced_commit, listOf(actor, sha))
                }
            }
            "cross-referenced" -> {
                val src = o.optJSONObject("source")?.optJSONObject("issue")
                val num = src?.optLong("number") ?: 0L
                val title = src?.optString("title").orEmpty()
                if (num > 0) {
                    LocalizedText(R.string.timeline_cross_referenced, listOf(actor, num, title))
                } else {
                    LocalizedText(R.string.timeline_referenced_issue, listOf(actor))
                }
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
        merged = o.optBoolean("merged", false),
        // 缺键与 JSON null 都算「未知」：`optBoolean("mergeable")` 会把两者都吞成 false，
        // 而那正是「GitHub 还在计算」被渲染成「不可合并」的路径，所以这里必须先判 isNull。
        mergeable = if (o.isNull("mergeable")) null else o.optBoolean("mergeable"),
        // head.repo 在「来源仓库被删」时是 JSON null → 空串（不预判，见字段说明）
        headRepoFullName = o.optJSONObject("head")
            ?.optJSONObject("repo")
            ?.optString("full_name")
            .orEmpty(),
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
    /**
     * `head_commit.message` 的**首行**。
     *
     * 同一个响应里本来就带 `head_commit`，以前没解析 —— 于是详情页头部只能重复
     * `display_title`，用户看不出「这次跑的是哪个提交」。纯 Kotlin 侧解析，不动 JNI。
     */
    val headCommitMessage: String = "",
    /** `head_commit.author.name`（提交作者，与触发人 `actor` 不是一回事）。 */
    val headCommitAuthor: String = "",
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

/**
 * 取一个**可空**字符串字段：缺省、空串、以及字面量 `"null"` 一律返回 null（纯函数，便于单测）。
 *
 * ## 为什么必须有它（真机 bug 的根因）
 *
 * `org.json` 的 `optString` 在值是 JSON `null` 时返回的是**字符串 "null"**，不是 null。
 * GitHub 对**正在运行**的 workflow run / job / step 返回的正是 `"conclusion": null`，
 * 于是解析出来是 `"null"` —— 非空 ⇒ 判定层把它当成「有结论」⇒
 * **正在跑的工作流被算成失败**（进度条写「失败 1」、详情页的「只看失败」也能筛出它）。
 *
 * 同一个坑对 `started_at` / `completed_at` / `runner_name` 一样成立（排队中的 job 全为 null）。
 */
internal fun JSONObject.optNullableString(key: String): String? =
    optString(key).takeIf { it.isNotBlank() && it != "null" }

/** 同上，但字段本身不可空：伪值统一成空串（避免界面把 `"null"` 当内容显示出来）。 */
internal fun JSONObject.optText(key: String): String = optNullableString(key).orEmpty()

/** 解析 GET .../actions/workflows/{id}/runs 的 {workflow_runs:[…]} */
fun parseWorkflowRuns(json: String): List<WorkflowRun> = runCatching {
    val arr = JSONObject(json).optJSONArray("workflow_runs") ?: return@runCatching emptyList()
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        WorkflowRun(
            id = o.optLong("id"),
            runNumber = o.optLong("run_number"),
            name = o.optText("name"),
            status = o.optText("status"),
            conclusion = o.optNullableString("conclusion"),
            headBranch = o.optText("head_branch"),
            createdAt = o.optText("created_at"),
            displayTitle = o.optText("display_title"),
            event = o.optText("event"),
            runAttempt = o.optInt("run_attempt", 1),
            headSha = o.optText("head_sha"),
            actor = o.optJSONObject("actor")?.optNullableString("login").orEmpty(),
            runStartedAt = o.optText("run_started_at"),
            updatedAt = o.optText("updated_at"),
            htmlUrl = o.optText("html_url"),
            path = o.optText("path"),
            workflowId = o.optLong("workflow_id"),
            headCommitMessage = o.optJSONObject("head_commit")?.optText("message").orEmpty()
                .lineSequence().firstOrNull().orEmpty(),
            headCommitAuthor = o.optJSONObject("head_commit")
                ?.optJSONObject("author")?.optNullableString("name").orEmpty(),
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
            name = o.optText("name"),
            status = o.optText("status"),
            conclusion = o.optNullableString("conclusion"),
            startedAt = o.optText("started_at"),
            completedAt = o.optText("completed_at"),
            runnerName = o.optText("runner_name"),
            htmlUrl = o.optText("html_url"),
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
            name = o.optText("name"),
            status = o.optText("status"),
            conclusion = o.optNullableString("conclusion"),
            startedAt = o.optText("started_at"),
            completedAt = o.optText("completed_at"),
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
enum class RepoRelation(val labelRes: Int) {
    OWN(R.string.relation_own),
    COLLABORATOR(R.string.relation_collaborator),
    FOREIGN(R.string.relation_foreign),
    NOT_COLLABORATOR(R.string.relation_not_collaborator),
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
