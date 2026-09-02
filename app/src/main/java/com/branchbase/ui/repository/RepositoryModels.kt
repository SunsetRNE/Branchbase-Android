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
)

// ── README Block / Inline / Destination（对齐 parseHtml 输出） ──
data class ReadmeBlock(
    val type: String,              // heading/paragraph/code/list_item/table/image/blockquote/hr/details
    val level: Int = 0,            // heading 级别 1~6
    val depth: Int = 0,            // 列表嵌套层级
    val ordered: Boolean = false,
    val index: Long = 0,           // 有序列表序号
    val lang: String? = null,      // 代码块语言
    val text: String? = null,      // 代码块文本
    val src: String? = null,       // 图片地址（已解析为绝对地址）
    val alt: String? = null,       // 图片说明
    val href: String? = null,      // 图片被链接包裹时的跳转地址（徽章）
    val dest: Destination? = null, // 图片链接解析后的跳转目标
    val checked: Boolean? = null,  // 任务列表项勾选状态（null = 非任务项）
    val summary: List<ReadmeInline> = emptyList(), // 折叠块标题行内内容
    val children: List<ReadmeBlock> = emptyList(), // 折叠块展开后的子块
    val inline: List<ReadmeInline> = emptyList(),
    val rows: List<List<List<ReadmeInline>>> = emptyList(), // 表格：每格是行内树
)

data class ReadmeInline(
    val kind: String,              // text/link/code/bold/italic/strike/image
    val value: String,             // 叶子节点（text/code/image 的 alt）文本；容器节点为空
    val href: String? = null,
    val dest: Destination? = null,
    val src: String? = null,       // 图片节点（kind=image）的地址
    val children: List<ReadmeInline> = emptyList(), // 容器节点的嵌套子节点
)

data class Destination(
    val type: String,              // repo/blob/tree/issue/pull/commit/user/anchor/external/raw
    val owner: String? = null,
    val repo: String? = null,
    val branch: String? = null,
    val path: String? = null,
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
    )
}.getOrNull()

/** 解析 parseHtml 返回的 `{"blocks":[...]}` */
fun parseReadmeBlocks(json: String): List<ReadmeBlock> = runCatching {
    val arr = JSONObject(json).optJSONArray("blocks") ?: return@runCatching emptyList()
    (0 until arr.length()).map { i -> parseBlock(arr.getJSONObject(i)) }
}.getOrDefault(emptyList())

private fun parseBlock(o: JSONObject): ReadmeBlock {
    val inlineArr = o.optJSONArray("inline")
    val inline = (0 until (inlineArr?.length() ?: 0)).map { i ->
        parseInline(inlineArr!!.getJSONObject(i))
    }
    val summaryArr = o.optJSONArray("summary")
    val summary = (0 until (summaryArr?.length() ?: 0)).map { i ->
        parseInline(summaryArr!!.getJSONObject(i))
    }
    val childrenArr = o.optJSONArray("children")
    val children = (0 until (childrenArr?.length() ?: 0)).map { i ->
        parseBlock(childrenArr!!.getJSONObject(i))
    }
    val rowsArr = o.optJSONArray("rows")
    val rows = (0 until (rowsArr?.length() ?: 0)).map { i ->
        val r = rowsArr!!.getJSONArray(i)
        (0 until r.length()).map { j ->
            val cell = r.getJSONArray(j)
            (0 until cell.length()).map { k -> parseInline(cell.getJSONObject(k)) }
        }
    }
    return ReadmeBlock(
        type = o.optString("type"),
        level = o.optInt("level"),
        depth = o.optInt("depth"),
        ordered = o.optBoolean("ordered"),
        index = o.optLong("index"),
        lang = o.optString("lang").takeIf { it.isNotBlank() },
        text = o.optString("text").takeIf { it.isNotBlank() },
        src = o.optString("src").takeIf { it.isNotBlank() },
        alt = o.optString("alt").takeIf { it.isNotBlank() },
        href = o.optString("href").takeIf { it.isNotBlank() },
        dest = o.optJSONObject("dest")?.let { parseDestination(it) },
        checked = if (o.has("checked")) o.optBoolean("checked") else null,
        summary = summary,
        children = children,
        inline = inline,
        rows = rows,
    )
}

private fun parseDestination(d: JSONObject): Destination =
    Destination(
        type = d.optString("type"),
        owner = d.optString("owner").takeIf { it.isNotBlank() },
        repo = d.optString("repo").takeIf { it.isNotBlank() },
        branch = d.optString("branch").takeIf { it.isNotBlank() },
        path = d.optString("path").takeIf { it.isNotBlank() },
        number = if (d.has("number")) d.optLong("number") else null,
        sha = d.optString("sha").takeIf { it.isNotBlank() },
        login = d.optString("login").takeIf { it.isNotBlank() },
        url = d.optString("url"),
        isOwn = d.optBoolean("is_own"),
        isExternal = d.optBoolean("is_external"),
    )

private fun parseInline(o: JSONObject): ReadmeInline {
    val destObj = o.optJSONObject("dest")
    val dest = destObj?.let { parseDestination(it) }
    val childrenArr = o.optJSONArray("c")
    val children = (0 until (childrenArr?.length() ?: 0)).map { i ->
        parseInline(childrenArr!!.getJSONObject(i))
    }
    return ReadmeInline(
        kind = o.optString("t"),
        value = o.optString("v"),
        href = o.optString("href").takeIf { it.isNotBlank() },
        dest = dest,
        src = o.optString("src").takeIf { it.isNotBlank() },
        children = children,
    )
}

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

data class ReleaseItem(
    val tag: String,
    val name: String,
    val createdAt: String,
)

data class WorkflowItem(
    val id: Long,
    val name: String,
    val state: String,
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
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
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
        ReleaseItem(
            tag = o.optString("tag_name"),
            name = o.optString("name").takeIf { it.isNotBlank() } ?: o.optString("tag_name"),
            createdAt = o.optString("created_at"),
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

/** URL 编码路径（逐段编码，保留 '/' 分隔） */
fun encodePath(path: String): String = path.split("/").joinToString("/") {
    java.net.URLEncoder.encode(it, "UTF-8")
}

/** URL 编码 ref/branch 参数（`/` → `%2F`，空格 → `%20`），供 `?ref=`/`?sha=`/`?base=`/`?branch=` 使用 */
fun encodeRef(ref: String): String =
    java.net.URLEncoder.encode(ref, "UTF-8").replace("+", "%20")

// ── 详情模型（Issue/PR/评论/文件变更） ──

data class IssueDetail(
    val number: Long,
    val title: String,
    val state: String,
    val body: String,
    val author: String,
    val createdAt: String,
    val labels: List<String>,
)

data class CommentItem(
    val author: String,
    val avatarUrl: String?,
    val body: String,
    val createdAt: String,
)

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
        (0 until arr.length()).map { i -> arr.getJSONObject(i).optString("name") }
    } ?: emptyList()
    IssueDetail(
        number = o.optLong("number"),
        title = o.optString("title"),
        state = o.optString("state"),
        body = o.optString("body").orEmpty(),
        author = o.optJSONObject("user")?.optString("login").orEmpty(),
        createdAt = o.optString("created_at"),
        labels = labels,
    )
}.getOrNull()

/** 解析 GET /repos/{o}/{r}/issues/{n}/comments 数组 */
fun parseComments(json: String): List<CommentItem> = runCatching {
    val arr = JSONArray(json)
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        CommentItem(
            author = o.optJSONObject("user")?.optString("login").orEmpty(),
            avatarUrl = o.optJSONObject("user")?.optString("avatar_url")?.takeIf { it.isNotBlank() },
            body = o.optString("body").orEmpty(),
            createdAt = o.optString("created_at"),
        )
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
)

data class RunJob(
    val id: Long,
    val name: String,
    val status: String,
    val conclusion: String?,
)

data class JobStep(
    val number: Long,
    val name: String,
    val status: String,
    val conclusion: String?,
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
        )
    }
}.getOrDefault(emptyList())
