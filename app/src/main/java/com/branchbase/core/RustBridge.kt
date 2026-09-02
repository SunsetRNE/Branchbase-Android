package com.branchbase.core

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

    private external fun nativeParseHtml(
        html: String,
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

    private external fun nativeMarkNotificationRead(host: String, token: String, threadId: String): String

    private external fun nativeMarkAllNotificationsRead(host: String, token: String): String

    private external fun nativeRenderMarkdown(host: String, token: String, text: String): String

    private external fun nativeGitClone(url: String, into: String, branch: String, token: String): String

    private external fun nativeGitPull(dir: String, token: String): String

    private external fun nativeGitCommit(dir: String, message: String, authorName: String, authorEmail: String): String

    private external fun nativeGitPush(dir: String, token: String, branch: String): String

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
     * 解析 README 渲染 HTML 为块级树（返回 `{"blocks":[...]}` JSON，链接已解析 dest）。
     */
    suspend fun parseHtml(
        html: String,
        host: String,
        owner: String,
        repo: String,
        branch: String,
        baseDir: String = "",
        currentUser: String
    ): String? = withContext(Dispatchers.IO) {
        nativeParseHtml(html, host, owner, repo, branch, baseDir, currentUser).ifBlank { null }
    }

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

    /** 标记单条通知已读（PATCH /notifications/threads/{id}），返回是否成功。 */
    suspend fun markNotificationRead(host: String, token: String, threadId: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                !nativeMarkNotificationRead(host, token, threadId).startsWith("ERROR:")
            } catch (e: Throwable) {
                false // native 符号缺失（.so 未重编译）时优雅降级
            }
        }

    /** 标记全部通知已读（PUT /notifications），返回是否成功。 */
    suspend fun markAllNotificationsRead(host: String, token: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                !nativeMarkAllNotificationsRead(host, token).startsWith("ERROR:")
            } catch (e: Throwable) {
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
}