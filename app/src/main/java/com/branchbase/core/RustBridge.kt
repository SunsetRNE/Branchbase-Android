package com.branchbase.core

import com.branchbase.ui.log.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Rust 核心桥接层。
 *
 * 对应 Rust 侧 `core/src/bridge/jni.rs` 的 JNI 导出函数。
 * 约定：所有 native 函数返回 JSON 字符串，出错时返回空串（判空处理）。
 * 网络类操作会阻塞，需在 IO 线程调用。
 */
object RustBridge {

    init {
        System.loadLibrary("branchbase_core")
    }

    // ── native 声明（与 Rust JNI 函数一一对应） ──

    private external fun nativeCoreVersion(): String

    private external fun nativeGeneratePkce(): String

    private external fun nativeBuildAuthorizeUrl(
        clientId: String,
        redirectUri: String,
        host: String,
        scopes: String,
        challenge: String
    ): String

    private external fun nativeExchangeCode(
        clientId: String,
        clientSecret: String,
        redirectUri: String,
        host: String,
        code: String,
        verifier: String
    ): String

    private external fun nativeGetCurrentUser(host: String, token: String): String

    private external fun nativeGetMyRepos(host: String, token: String): String

    private external fun nativeGetStarredRepos(host: String, token: String): String

    private external fun nativeGetMyPackages(host: String, token: String): String

    private external fun nativeGetMyProjects(host: String, token: String): String

    private external fun nativeGetReceivedEvents(host: String, token: String, login: String): String

    private external fun nativeSearchRepositories(host: String, token: String, query: String, sort: String): String

    private external fun nativeSearchUsers(host: String, token: String, query: String): String

    private external fun nativeSearchIssues(host: String, token: String, query: String): String

    private external fun nativeSearchCode(host: String, token: String, query: String): String

    private external fun nativeSearchCommits(host: String, token: String, query: String): String

    private external fun nativeSearchTopics(host: String, token: String, query: String): String

    private external fun nativeValidateTwoFactor(code: String): String

    private external fun nativeRefreshToken(
        clientId: String,
        clientSecret: String,
        host: String,
        refreshToken: String
    ): String

    private external fun nativeResolveLink(
        url: String,
        host: String,
        owner: String,
        repo: String,
        branch: String,
        baseDir: String,
        currentUser: String
    ): String

    private external fun nativeReadmeHtml(host: String, token: String, owner: String, repo: String, branch: String): String

    private external fun nativeListBranches(host: String, token: String, owner: String, repo: String): String

    private external fun nativeGetRepoInfo(host: String, token: String, owner: String, repo: String): String

    private external fun nativeGetRepoLanguages(host: String, token: String, owner: String, repo: String): String

    private external fun nativeGetRepoContributors(host: String, token: String, owner: String, repo: String): String

    private external fun nativeGetJson(host: String, token: String, path: String): String

    private external fun nativeGraphQL(host: String, token: String, query: String, variables: String): String

    private external fun nativeContributionCalendar(host: String, token: String, login: String, from: String, to: String): String

    private external fun nativeMarkNotificationRead(host: String, token: String, threadId: String): String

    private external fun nativeMarkAllNotificationsRead(host: String, token: String): String

    private external fun nativeRenderMarkdown(host: String, token: String, text: String): String

    private external fun nativeGitClone(url: String, into: String, branch: String, token: String): String

    private external fun nativeGitPull(dir: String, token: String): String

    private external fun nativeGitCommit(dir: String, message: String, authorName: String, authorEmail: String): String

    private external fun nativeGitPush(dir: String, token: String, branch: String): String

    private external fun nativeLocalBranches(dir: String): String

    private external fun nativeCheckoutBranch(dir: String, name: String): String

    private external fun nativeCreateBranchLocal(dir: String, name: String, from: String): String

    private external fun nativeDeleteBranchLocal(dir: String, name: String): String

    private external fun nativeDiscardAllChanges(dir: String): String

    private external fun nativeLatestReleaseSignature(host: String, token: String, owner: String, repo: String): String

    private external fun nativeRepoSignature(host: String, token: String, owner: String, repo: String): String

    private external fun nativePutContents(
        host: String,
        token: String,
        owner: String,
        repo: String,
        path: String,
        message: String,
        content: String,
        sha: String,
        branch: String
    ): String

    private external fun nativeCommitFiles(
        host: String,
        token: String,
        owner: String,
        repo: String,
        branch: String,
        message: String,
        filesJson: String
    ): String

    // ── 决策页面支持（对齐 docs/decision-pages-gap.md §6） ──

    private external fun nativeGitStatus(dir: String): String

    private external fun nativeGitResetSoft(dir: String): String

    private external fun nativeGitResetHardRemote(dir: String, branch: String): String

    private external fun nativeGitAmend(dir: String, message: String): String

    private external fun nativeGitRevert(dir: String, sha: String, message: String, authorName: String, authorEmail: String): String

    private external fun nativeGitPushSetUpstream(dir: String, remoteUrl: String, branch: String, token: String): String

    private external fun nativeScanSensitive(text: String): String

    private external fun nativeGitInitSsl(dir: String): String

    private external fun nativeSetGitProxy(dir: String, proxy: String): String

    private external fun nativeUpdateProfile(host: String, token: String, body: String): String

    // ── 协作与仓库管理（PR 一条龙 / 合并 / 仓库设置执行层） ──

    private external fun nativeGetRefSha(host: String, token: String, owner: String, repo: String, branch: String): String

    private external fun nativeCreateBranch(host: String, token: String, owner: String, repo: String, branch: String, sha: String): String

    private external fun nativeCreatePullRequest(host: String, token: String, owner: String, repo: String, title: String, body: String, head: String, base: String, draft: String): String

    private external fun nativeMergePullRequest(host: String, token: String, owner: String, repo: String, number: String, mergeMethod: String): String

    private external fun nativeMergeBranch(host: String, token: String, owner: String, repo: String, base: String, head: String, message: String): String

    private external fun nativeCompareBranches(host: String, token: String, owner: String, repo: String, base: String, head: String): String

    private external fun nativeUpdateRef(host: String, token: String, owner: String, repo: String, branch: String, sha: String, force: String): String

    private external fun nativeDeleteBranch(host: String, token: String, owner: String, repo: String, branch: String): String

    private external fun nativeUpdateDefaultBranch(host: String, token: String, owner: String, repo: String, branch: String): String

    private external fun nativeDeleteRepo(host: String, token: String, owner: String, repo: String): String

    // ── 高层 API（suspend，切 IO 线程） ──

    fun coreVersion(): String = nativeCoreVersion()

    data class Pkce(val verifier: String, val challenge: String)

    fun generatePkce(): Pkce? {
        val json = nativeGeneratePkce()
        if (json.isBlank()) return null
        // 简单解析（生产环境应引入 kotlinx.serialization）
        val verifier = Regex("\"verifier\":\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: return null
        val challenge = Regex("\"challenge\":\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: return null
        return Pkce(verifier, challenge)
    }

    suspend fun buildAuthorizeUrl(
        clientId: String,
        redirectUri: String,
        host: String,
        scopes: List<String>,
        challenge: String
    ): String? = withContext(Dispatchers.IO) {
        val url = nativeBuildAuthorizeUrl(clientId, redirectUri, host, scopes.joinToString(" "), challenge)
        url.ifBlank { null }
    }

    suspend fun exchangeCode(
        clientId: String,
        clientSecret: String,
        redirectUri: String,
        host: String,
        code: String,
        verifier: String
    ): String = withContext(Dispatchers.IO) {
        nativeExchangeCode(clientId, clientSecret, redirectUri, host, code, verifier)
    }

    suspend fun getCurrentUser(host: String, token: String): String? =
        withContext(Dispatchers.IO) {
            nativeGetCurrentUser(host, token).ifBlank { null }
        }

    suspend fun getMyRepos(host: String, token: String): String? =
        withContext(Dispatchers.IO) {
            nativeGetMyRepos(host, token).ifBlank { null }
        }

    suspend fun getStarredRepos(host: String, token: String): String? =
        withContext(Dispatchers.IO) {
            nativeGetStarredRepos(host, token).ifBlank { null }
        }

    suspend fun getMyPackages(host: String, token: String): String? =
        withContext(Dispatchers.IO) {
            nativeGetMyPackages(host, token).ifBlank { null }
        }

    suspend fun getMyProjects(host: String, token: String): String? =
        withContext(Dispatchers.IO) {
            nativeGetMyProjects(host, token).ifBlank { null }
        }

    suspend fun getReceivedEvents(host: String, token: String, login: String): String? =
        withContext(Dispatchers.IO) {
            nativeGetReceivedEvents(host, token, login).ifBlank { null }
        }

    suspend fun searchRepositories(host: String, token: String, query: String, sort: String = ""): String? =
        withContext(Dispatchers.IO) {
            nativeSearchRepositories(host, token, query, sort).ifBlank { null }
        }

    suspend fun searchUsers(host: String, token: String, query: String): String? =
        withContext(Dispatchers.IO) {
            nativeSearchUsers(host, token, query).ifBlank { null }
        }

    suspend fun searchIssues(host: String, token: String, query: String): String? =
        withContext(Dispatchers.IO) {
            nativeSearchIssues(host, token, query).ifBlank { null }
        }

    suspend fun searchCode(host: String, token: String, query: String): String? =
        withContext(Dispatchers.IO) {
            nativeSearchCode(host, token, query).ifBlank { null }
        }

    suspend fun searchCommits(host: String, token: String, query: String): String? =
        withContext(Dispatchers.IO) {
            nativeSearchCommits(host, token, query).ifBlank { null }
        }

    suspend fun searchTopics(host: String, token: String, query: String): String? =
        withContext(Dispatchers.IO) {
            nativeSearchTopics(host, token, query).ifBlank { null }
        }

    fun validateTwoFactor(code: String): Boolean = nativeValidateTwoFactor(code) == "1"

    suspend fun refreshToken(
        clientId: String,
        clientSecret: String,
        host: String,
        refreshToken: String
    ): String = withContext(Dispatchers.IO) {
        nativeRefreshToken(clientId, clientSecret, host, refreshToken)
    }

    /**
     * 解析单个链接为内部跳转目标（返回 Destination JSON）。
     * @param baseDir 当前文件所在目录（"" = 仓库根）
     */
    fun resolveLink(
        url: String,
        host: String,
        owner: String,
        repo: String,
        branch: String,
        baseDir: String = "",
        currentUser: String
    ): String = nativeResolveLink(url, host, owner, repo, branch, baseDir, currentUser)

    /**
     * 获取仓库 README 渲染 HTML（返回 HTML 字符串；无 README 时返回 "ERROR:..." 前缀）。
     * @param branch 目标分支（空串 = 默认分支）
     */
    suspend fun readmeHtml(host: String, token: String, owner: String, repo: String, branch: String = ""): String? =
        withContext(Dispatchers.IO) {
            nativeReadmeHtml(host, token, owner, repo, branch).ifBlank { null }
        }

    /** 获取仓库分支列表（返回 Branch 数组 JSON）。 */
    suspend fun listBranches(host: String, token: String, owner: String, repo: String): String? =
        withContext(Dispatchers.IO) {
            nativeListBranches(host, token, owner, repo).ifBlank { null }
        }

    /** 获取单个仓库信息（返回原始 JSON）。 */
    suspend fun getRepoInfo(host: String, token: String, owner: String, repo: String): String? =
        withContext(Dispatchers.IO) {
            nativeGetRepoInfo(host, token, owner, repo).ifBlank { null }
        }

    /** 获取仓库语言统计（返回 {语言:字节数} JSON）。 */
    suspend fun getRepoLanguages(host: String, token: String, owner: String, repo: String): String? =
        withContext(Dispatchers.IO) {
            nativeGetRepoLanguages(host, token, owner, repo).ifBlank { null }
        }

    /** 获取仓库贡献者（返回 JSON 数组）。 */
    suspend fun getRepoContributors(host: String, token: String, owner: String, repo: String): String? =
        withContext(Dispatchers.IO) {
            nativeGetRepoContributors(host, token, owner, repo).ifBlank { null }
        }

    /** 通用 GET（列表等任意路径，返回原始 JSON）。 */
    suspend fun getJson(host: String, token: String, path: String): String? =
        withContext(Dispatchers.IO) {
            nativeGetJson(host, token, path).ifBlank { null }
        }

    /**
     * 通用 GraphQL 查询（返回 `data` 部分 JSON；null = 失败）。
     *
     * 与 [getJson] 共用 ApiClient，差别只在端点与鉴权前缀（`bearer`）。
     * 失败时返回 `ERROR:` 开头的串（含 HTTP 状态码与响应体），可用
     * [com.branchbase.core.AccountStore.statusFromResponse] 之类的方式解析。
     */
    suspend fun graphQL(
        host: String,
        token: String,
        query: String,
        variablesJson: String = "",
    ): String? = withContext(Dispatchers.IO) {
        nativeGraphQL(host, token, query, variablesJson).ifBlank { null }
    }

    /**
     * 贡献日历（GraphQL `contributionsCollection.contributionCalendar`，52 周）。
     *
     * @param from ISO8601（如 `2025-09-08T00:00:00Z`），GitHub 要求跨度 ≤ 1 年
     */
    suspend fun contributionCalendar(
        host: String,
        token: String,
        login: String,
        from: String,
        to: String,
    ): String? = withContext(Dispatchers.IO) {
        nativeContributionCalendar(host, token, login, from, to).ifBlank { null }
    }

    /** 标记单条通知已读（PATCH /notifications/threads/{id}），返回是否成功。 */
    suspend fun markNotificationRead(host: String, token: String, threadId: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val result = nativeMarkNotificationRead(host, token, threadId)
                if (result.startsWith("ERROR:")) {
                    Logger.net("PATCH /notifications/threads/$threadId 失败：$result", "GitHubAPI")
                    false
                } else {
                    true
                }
            } catch (e: Throwable) {
                Logger.net("PATCH /notifications/threads/$threadId 异常：${e.message}", "GitHubAPI")
                false // native 符号缺失（.so 未重编译）时优雅降级
            }
        }

    /** 标记全部通知已读（PUT /notifications），返回是否成功。 */
    suspend fun markAllNotificationsRead(host: String, token: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val result = nativeMarkAllNotificationsRead(host, token)
                if (result.startsWith("ERROR:")) {
                    Logger.net("PUT /notifications 失败：$result", "GitHubAPI")
                    false
                } else {
                    true
                }
            } catch (e: Throwable) {
                Logger.net("PUT /notifications 异常：${e.message}", "GitHubAPI")
                false // native 符号缺失（.so 未重编译）时优雅降级
            }
        }

    /** 将 markdown 渲染为 HTML（POST /markdown）。 */
    suspend fun renderMarkdown(host: String, token: String, text: String): String? =
        withContext(Dispatchers.IO) {
            nativeRenderMarkdown(host, token, text).ifBlank { null }
        }

    /** 浅 clone 仓库到本地目录（返回是否成功）。 */
    suspend fun gitClone(url: String, into: String, branch: String = "", token: String = ""): Boolean =
        withContext(Dispatchers.IO) {
            !nativeGitClone(url, into, branch, token).startsWith("ERROR:")
        }

    /** clone 三态（决策页/反馈用）：null=成功，其他=具体失败原因（透出 ERROR: 后文本）。 */
    suspend fun gitCloneDetailed(url: String, into: String, branch: String = "", token: String = ""): String? =
        withContext(Dispatchers.IO) {
            try {
                val r = nativeGitClone(url, into, branch, token)
                if (r.isBlank()) null else r.removePrefix("ERROR:").take(120)
            } catch (e: Throwable) {
                "引擎不可用"
            }
        }

    /** pull（fetch + fast-forward）本地仓库（返回是否成功）。 */
    suspend fun gitPull(dir: String, token: String = ""): Boolean =
        withContext(Dispatchers.IO) {
            !nativeGitPull(dir, token).startsWith("ERROR:")
        }

    /** 本地 git commit（暂存 + 提交，返回 commit sha 或 null）。 */
    suspend fun gitCommit(dir: String, message: String, authorName: String, authorEmail: String): String? =
        withContext(Dispatchers.IO) {
            nativeGitCommit(dir, message, authorName, authorEmail).ifBlank { null }
        }

    /** 本地 git push（推送到 origin，返回是否成功）。 */
    suspend fun gitPush(dir: String, token: String = "", branch: String = "main"): Boolean =
        withContext(Dispatchers.IO) {
            !nativeGitPush(dir, token, branch).startsWith("ERROR:")
        }

    /** pull 三态（决策页用）：null=成功、"nff"=本地与远端分叉、其他=失败原因。 */
    suspend fun gitPullDetailed(dir: String, token: String = ""): String? = withContext(Dispatchers.IO) {
        try {
            val r = nativeGitPull(dir, token)
            when {
                r.isBlank() -> null
                r.startsWith("ERROR:nff") -> "nff"
                else -> r.removePrefix("ERROR:").take(300)
            }
        } catch (e: Throwable) {
            "引擎不可用"
        }
    }

    /** push 三态（决策页用）：null=成功、"nff"=远端领先被拒、其他=失败原因。 */
    suspend fun gitPushDetailed(dir: String, token: String = "", branch: String = "main"): String? =
        withContext(Dispatchers.IO) {
            try {
                val r = nativeGitPush(dir, token, branch)
                when {
                    r.isBlank() -> null
                    r.startsWith("ERROR:nff") -> "nff"
                    else -> r.removePrefix("ERROR:").take(300)
                }
            } catch (e: Throwable) {
                "引擎不可用"
            }
        }

    // ── 本地分支管理（列表 / 切换 / 新建 / 删除） ──

    /** 本地分支列表（JSON 数组：name / isHead / upstream / ahead / behind）。 */
    suspend fun localBranches(dir: String): String? = withContext(Dispatchers.IO) {
        runCatching { nativeLocalBranches(dir).takeIf { it.isNotBlank() && !it.startsWith("ERROR:") } }.getOrNull()
    }

    /** 切换本地分支（safe checkout）。null = 成功；其他 = 失败原因（含冲突提示）。 */
    suspend fun checkoutBranch(dir: String, name: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val r = nativeCheckoutBranch(dir, name)
            if (r.isBlank()) null else r.removePrefix("ERROR:").take(300)
        }.getOrElse { "引擎不可用" }
    }

    /** 新建本地分支并切换过去（from 为空 = 当前 HEAD）。null = 成功。 */
    suspend fun createBranchLocal(dir: String, name: String, from: String = ""): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val r = nativeCreateBranchLocal(dir, name, from)
                if (r.isBlank()) null else r.removePrefix("ERROR:").take(300)
            }.getOrElse { "引擎不可用" }
        }

    /** 删除本地分支（当前分支会被拒绝）。null = 成功。 */
    suspend fun deleteBranchLocal(dir: String, name: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val r = nativeDeleteBranchLocal(dir, name)
            if (r.isBlank()) null else r.removePrefix("ERROR:").take(300)
        }.getOrElse { "引擎不可用" }
    }

    /** 撤销工作区所有改动（恢复已跟踪文件 + 删除未跟踪文件）。null = 成功。 */
    suspend fun discardAllChanges(dir: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val r = nativeDiscardAllChanges(dir)
            if (r.isBlank()) null else r.removePrefix("ERROR:").take(300)
        }.getOrElse { "引擎不可用" }
    }

    /** 拉取 latest release 的 signature.txt 校验文件内容（返回文本或 null）。 */
    suspend fun latestReleaseSignature(host: String, token: String, owner: String, repo: String): String? =
        withContext(Dispatchers.IO) {
            try {
                nativeLatestReleaseSignature(host, token, owner, repo).ifBlank { null }
            } catch (e: Throwable) {
                null  // native 符号缺失（.so 未重编译）时优雅降级，不崩溃
            }
        }

    /** 拉取仓库 verify/signature.txt 校验文件内容（返回文本或 null）。 */
    suspend fun repoSignature(host: String, token: String, owner: String, repo: String): String? =
        withContext(Dispatchers.IO) {
            try {
                nativeRepoSignature(host, token, owner, repo).ifBlank { null }
            } catch (e: Throwable) {
                null  // native 符号缺失（.so 未重编译）时优雅降级，不崩溃
            }
        }

    /** 更新/新建单文件（PUT /contents，返回响应 JSON 或 null）。 */
    suspend fun putContents(
        host: String,
        token: String,
        owner: String,
        repo: String,
        path: String,
        message: String,
        content: String,
        sha: String = "",
        branch: String = ""
    ): String? = withContext(Dispatchers.IO) {
        nativePutContents(host, token, owner, repo, path, message, content, sha, branch).ifBlank { null }
    }

    /**
     * 批量提交多个文件（Git Data API），只产生一个 commit。
     *
     * 与 [putContents] 的差别：后者每个文件一个 commit，本方法把多个文件改动
     * 合成一次提交（blobs → tree → commit → 移动 ref）。
     *
     * @param files `(仓库内相对路径, 新内容)`
     * @return 新 commit sha；失败返回 `ERROR:` 开头的串
     */
    suspend fun commitFiles(
        host: String,
        token: String,
        owner: String,
        repo: String,
        branch: String,
        message: String,
        files: List<Pair<String, String>>,
    ): String? = withContext(Dispatchers.IO) {
        val json = org.json.JSONArray().apply {
            files.forEach { (path, content) ->
                put(org.json.JSONObject().put("path", path).put("content", content))
            }
        }.toString()
        nativeCommitFiles(host, token, owner, repo, branch, message, json).ifBlank { null }
    }

    // ── 决策页面支持（对齐 docs/decision-pages-gap.md §6；native 符号缺失时优雅降级） ──

    /** 仓库状态（JSON：branch/ahead/behind/hasUpstream/remoteUrl/dirty/unpushed）。 */
    suspend fun gitStatus(dir: String): String? = withContext(Dispatchers.IO) {
        try {
            nativeGitStatus(dir).takeIf { it.isNotBlank() && !it.startsWith("ERROR:") }
        } catch (e: Throwable) {
            null // .so 未重编译时优雅降级
        }
    }

    /** 撤销最近一次提交保留改动（reset --soft HEAD~1）。 */
    suspend fun gitResetSoft(dir: String): Boolean = withContext(Dispatchers.IO) {
        try {
            !nativeGitResetSoft(dir).startsWith("ERROR:")
        } catch (e: Throwable) {
            false
        }
    }

    /** 放弃本地提交：reset --hard origin/{branch}（危险，UI 需二次确认）。 */
    suspend fun gitResetHardRemote(dir: String, branch: String): Boolean = withContext(Dispatchers.IO) {
        try {
            !nativeGitResetHardRemote(dir, branch).startsWith("ERROR:")
        } catch (e: Throwable) {
            false
        }
    }

    /** 修改最近一次提交信息（amend）。 */
    suspend fun gitAmend(dir: String, message: String): Boolean = withContext(Dispatchers.IO) {
        try {
            !nativeGitAmend(dir, message).startsWith("ERROR:")
        } catch (e: Throwable) {
            false
        }
    }

    /** 对已推送提交创建 revert 提交（返回新 sha 或 null）。 */
    suspend fun gitRevert(dir: String, sha: String, message: String, authorName: String, authorEmail: String): String? =
        withContext(Dispatchers.IO) {
            try {
                nativeGitRevert(dir, sha, message, authorName, authorEmail).takeIf { it.isNotBlank() && !it.startsWith("ERROR:") }
            } catch (e: Throwable) {
                null
            }
        }

    /** 首次 push：确保 origin + 推送 + 设置上游。返回 null=成功、"nff"=远端领先被拒、其他=失败原因。 */
    suspend fun gitPushSetUpstream(dir: String, remoteUrl: String, branch: String, token: String = ""): String? =
        withContext(Dispatchers.IO) {
            try {
                val r = nativeGitPushSetUpstream(dir, remoteUrl, branch, token)
                when {
                    r.isBlank() -> null
                    r.startsWith("ERROR:nff") -> "nff"
                    else -> r.removePrefix("ERROR:").take(300)
                }
            } catch (e: Throwable) {
                "引擎不可用"
            }
        }

    /** 敏感信息本地扫描（返回 JSON 数组文本或 null）。 */
    fun scanSensitive(text: String): String? = try {
        nativeScanSensitive(text).takeIf { it.isNotBlank() && !it.startsWith("ERROR:") }
    } catch (e: Throwable) {
        null
    }

    /**
     * 初始化 git 引擎 TLS 证书信任（App 启动时调用一次，主线程安全：仅文件写 + 环境变量）。
     * 把内置 Mozilla CA bundle 写入 {dir}/branchbase-cacert.pem，写入
     * {dir}/branchbase-gitconfig（[http] sslCAInfo）并设 GIT_CONFIG_GLOBAL 环境变量；
     * clone/pull/push 建立 HTTPS 连接时由 libgit2 读取。不触碰 libgit2 API。
     */
    fun gitInitSsl(dir: String): Boolean = try {
        !nativeGitInitSsl(dir).startsWith("ERROR:")
    } catch (e: Throwable) {
        false // .so 未重编译时优雅降级
    }

    // ── 协作与仓库管理（对齐 docs/decision-pages-gap.md §8.4 执行层） ──
    // 统一约定：返回 null = 成功；其他 = 失败原因（透出 ERROR: 后文本，截断 160 字符）

    private fun err(r: String): String? =
        if (r.isBlank()) null else r.removePrefix("ERROR:").take(160)

    /** 读取分支 ref 的 sha（失败返回 null）。 */
    suspend fun getRefSha(host: String, token: String, owner: String, repo: String, branch: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val r = nativeGetRefSha(host, token, owner, repo, branch)
                r.takeIf { it.isNotBlank() && !it.startsWith("ERROR:") }
            } catch (e: Throwable) {
                null
            }
        }

    /** 创建分支（null = 成功）。 */
    suspend fun createBranch(host: String, token: String, owner: String, repo: String, branch: String, sha: String): String? =
        withContext(Dispatchers.IO) {
            try {
                err(nativeCreateBranch(host, token, owner, repo, branch, sha))
            } catch (e: Throwable) {
                "引擎不可用"
            }
        }

    /** 创建 PR（null = 成功）。 */
    suspend fun createPullRequest(
        host: String, token: String, owner: String, repo: String,
        title: String, body: String, head: String, base: String, draft: Boolean,
    ): String? = withContext(Dispatchers.IO) {
        try {
            err(nativeCreatePullRequest(host, token, owner, repo, title, body, head, base, if (draft) "1" else "0"))
        } catch (e: Throwable) {
            "引擎不可用"
        }
    }

    /** 合并 PR（null = 成功）。mergeMethod: merge / squash / rebase */
    suspend fun mergePullRequest(
        host: String, token: String, owner: String, repo: String, number: Int, mergeMethod: String,
    ): String? = withContext(Dispatchers.IO) {
        try {
            err(nativeMergePullRequest(host, token, owner, repo, number.toString(), mergeMethod))
        } catch (e: Throwable) {
            "引擎不可用"
        }
    }

    // ── 分支同步（服务端合并，无需本地 clone） ──

    /**
     * 服务端合并分支：把 [head]（源）合并进 [base]（目标）。
     *
     * @return null = 合并成功；"uptodate" = 已是最新（GitHub 204）；其他 = 失败原因（含冲突）
     */
    suspend fun mergeBranch(
        host: String, token: String, owner: String, repo: String,
        base: String, head: String, message: String = "",
    ): String? = withContext(Dispatchers.IO) {
        try {
            val r = nativeMergeBranch(host, token, owner, repo, base, head, message)
            when {
                r.startsWith("ERROR:") -> r.removePrefix("ERROR:").take(300)
                r.isBlank() -> "uptodate"
                else -> null
            }
        } catch (e: Throwable) {
            "引擎不可用"
        }
    }

    /** 比较两个分支（返回原始 JSON，含 ahead_by / behind_by / status）。 */
    suspend fun compareBranches(
        host: String, token: String, owner: String, repo: String, base: String, head: String,
    ): String? = withContext(Dispatchers.IO) {
        try {
            nativeCompareBranches(host, token, owner, repo, base, head)
                .takeIf { it.isNotBlank() && !it.startsWith("ERROR:") }
        } catch (e: Throwable) {
            null
        }
    }

    /** 更新分支引用（force=true 为「覆盖」模式，会丢目标分支独有提交）。null = 成功。 */
    suspend fun updateRef(
        host: String, token: String, owner: String, repo: String, branch: String, sha: String, force: Boolean,
    ): String? = withContext(Dispatchers.IO) {
        try {
            err(nativeUpdateRef(host, token, owner, repo, branch, sha, if (force) "true" else "false"))
        } catch (e: Throwable) {
            "引擎不可用"
        }
    }

    /** 删除远端分支（null = 成功）。 */
    suspend fun deleteBranch(host: String, token: String, owner: String, repo: String, branch: String): String? =
        withContext(Dispatchers.IO) {
            try {
                err(nativeDeleteBranch(host, token, owner, repo, branch))
            } catch (e: Throwable) {
                "引擎不可用"
            }
        }

    /** 修改仓库默认分支（null = 成功）。 */
    suspend fun updateDefaultBranch(host: String, token: String, owner: String, repo: String, branch: String): String? =
        withContext(Dispatchers.IO) {
            try {
                err(nativeUpdateDefaultBranch(host, token, owner, repo, branch))
            } catch (e: Throwable) {
                "引擎不可用"
            }
        }

    /**
     * 设置 libgit2 HTTP 代理（写 gitconfig 的 [http] proxy，重启后保留）。
     * 形如 `http://127.0.0.1:7890` / `socks5://127.0.0.1:1080`；空串清除。
     */
    fun setGitProxy(dir: String, proxy: String): Boolean = try {
        !nativeSetGitProxy(dir, proxy).startsWith("ERROR:")
    } catch (e: Throwable) {
        false
    }

    /** 更新当前用户资料（PATCH /user，body 为 JSON；null = 成功）。 */
    suspend fun updateProfile(host: String, token: String, body: String): String? =
        withContext(Dispatchers.IO) {
            try {
                err(nativeUpdateProfile(host, token, body))
            } catch (e: Throwable) {
                "引擎不可用"
            }
        }

    /** 删除仓库（null = 成功）。 */
    suspend fun deleteRepo(host: String, token: String, owner: String, repo: String): String? =
        withContext(Dispatchers.IO) {
            try {
                err(nativeDeleteRepo(host, token, owner, repo))
            } catch (e: Throwable) {
                "引擎不可用"
            }
        }
}