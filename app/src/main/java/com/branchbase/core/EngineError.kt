package com.branchbase.core

import android.content.Context
import androidx.annotation.StringRes
import com.branchbase.R

/**
 * 引擎（Rust 核心）错误的**稳定码**与本地化边界。
 *
 * ## 为什么要有这一层
 *
 * 引擎的失败一直是以**拼好的文案**回到 Kotlin 的：`api` 层把 HTTP 状态与 GitHub 原话拼成
 * `HTTP 404: {"message":"Not Found"}`，`git` 层返回中文（「不是 git 仓库」），JNI 层再统一加
 * `ERROR:` 前缀。于是「这句话该怎么给用户看」散在几十个调用点里，而这个仓库已经**反复**
 * 从文本里反推分类：`friendlySearchError`（搜索）、`repoAccessHint`（仓库打不开）、
 * `repoRelationOf`、`classifyOAuthFailure` —— 每一次都重写一份正则，每一次都漏一种写法。
 *
 * 现在把「认码」收口到这一个文件：**解析是纯函数，渲染才需要 Context**。
 * 迁移分三步（见 `docs/specs/i18n-migration.md` §14）：
 *
 * 1. 引擎按 `ERROR:<CODE>[:<detail>]` 返回（Rust 侧逐步接：`api` → `git` → `translate`）；
 * 2. **迁移期双读**：还没换码的旧文案（`未知错误: HTTP 404 …`、`引擎不可用`）在这里一并识别，
 *    所以两边不同步的窗口里界面不会退化成一句英文；
 * 3. 未知码**原样透出**，不猜 —— 猜错比不翻更坏（§9「用文案猜语气」那次事故）。
 *
 * ## 码表（四层：传输 / git / auth / translate）
 *
 * | 码 | 含义 |
 * |---|---|
 * | `HTTP_<status>` | GitHub 返回了非 2xx（`detail` 是原响应体） |
 * | `NETWORK` | 请求没发出去 / 没回来（断网、代理、证书） |
 * | `ENGINE_UNAVAILABLE` | native 库缺失或 JNI 调用抛异常 |
 *
 * 其余三层的码在上面这张表的下面：`git`（19 + 7 个原因码，1.4.20/1.4.22）、
 * `auth`（5 个，1.4.24）、`translate`（7 个，1.4.24）。
 *
 * ## 状态：实验性（1.4.26 定稿）
 *
 * 码表与归类逻辑有单测与 CI 覆盖，但**没有做真机逐场景验收**（验收成本见
 * `docs/specs/i18n-migration.md` §16）。因此：
 *
 * - 影响面只有文案 —— 码选错不会崩、不会丢数据，原文始终在 [EngineError.Coded.detail] 与日志里；
 * - **回退单个码 = 从下面 [CODE_TABLE] 删掉它**（该码变成未知 → 界面回退成原文，不用动 Rust）；
 * - 解析器里的旧文案双读**永久保留**，它是这个实验的安全网。
 *
 * ## 边界
 *
 * - **detail 不上屏**：它保留原文（GitHub 的 `message`、libgit2 的英文错误），供日志与排障；
 *   把它拼进界面就等于把刚翻译掉的英文又贴回去。
 * - **不要在这里做业务判断**：`HTTP 404` 到底是「不存在」还是「私有仓库没权限」是
 *   `repoAccessHint` 的事，这里只回答「码是什么」。业务判断用 [engineErrorStatus]。
 */
sealed interface EngineError {
    /** 稳定码；`null` = 没有认出来（[Raw]）。 */
    val code: String?

    /** 引擎给的原文（不含 `ERROR:` 前缀），供日志与排障；可能为空。 */
    val detail: String?

    /** 认得出码的错误：渲染成一条本地化文案。 */
    data class Coded(
        override val code: String,
        @param:StringRes val res: Int,
        val args: List<Any> = emptyList(),
        override val detail: String? = null,
    ) : EngineError

    /** 认不出码的错误：**原样**透出（迁移期的中文文案、未知码、GitHub 原话都在这一档）。 */
    data class Raw(val text: String) : EngineError {
        override val code: String? get() = null
        override val detail: String get() = text
    }
}

/**
 * 引擎错误里出现过的稳定码（新增要同时改 [CODE_TABLE] 与资源）。
 *
 * 码由引擎侧定义（`core/src/lib.rs` 的 `CoreError::coded` 与 `core/src/git/mod.rs` 的
 * `classify_git_error`），这里只是同名的常量 —— 字符串必须**逐字一致**。
 */
internal object EngineCodes {
    const val ENGINE_UNAVAILABLE = "ENGINE_UNAVAILABLE"
    const val NETWORK = "NETWORK"
    const val HTTP = "HTTP"

    // ── git 层（1.4.20 起：只给「用户能据此行动」的情况配码） ──
    const val NFF = "NFF"
    const val AUTH_FAILED = "AUTH_FAILED"
    const val LOCKED = "LOCKED"
    const val NOT_GIT_REPO = "NOT_GIT_REPO"
    const val REPO_OPEN_FAILED = "REPO_OPEN_FAILED"
    const val CLONE_TARGET_NOT_EMPTY = "CLONE_TARGET_NOT_EMPTY"
    const val DIRTY_WORKTREE = "DIRTY_WORKTREE"
    const val CHECKOUT_BLOCKED = "CHECKOUT_BLOCKED"
    const val BRANCH_EXISTS = "BRANCH_EXISTS"
    const val BRANCH_NOT_FOUND = "BRANCH_NOT_FOUND"
    const val CURRENT_BRANCH = "CURRENT_BRANCH"
    const val MERGE_IN_PROGRESS = "MERGE_IN_PROGRESS"
    const val NO_MERGE_IN_PROGRESS = "NO_MERGE_IN_PROGRESS"
    const val CONFLICTS_REMAIN = "CONFLICTS_REMAIN"
    const val NO_PARENT_COMMIT = "NO_PARENT_COMMIT"
    const val REMOTE_REF_MISSING = "REMOTE_REF_MISSING"
    const val SHALLOW_CLONE = "SHALLOW_CLONE"
    const val NO_COMMON_ANCESTOR = "NO_COMMON_ANCESTOR"
    const val NOT_IN_CONFLICT = "NOT_IN_CONFLICT"

    // ── git 内部失败的原因码（1.4.22 / §15：不归通用码，按原因给行动） ──
    const val SIGNATURE_MISSING = "SIGNATURE_MISSING"
    const val UNMERGED_INDEX = "UNMERGED_INDEX"
    const val REMOTE_MISSING = "REMOTE_MISSING"
    const val EMPTY_REPO = "EMPTY_REPO"
    const val REPO_WRITE_FAILED = "REPO_WRITE_FAILED"
    const val REPO_CORRUPT = "REPO_CORRUPT"
    const val CANCELLED = "CANCELLED"

    // ── auth 层（1.4.24）：OAuth 交换 / 拉取用户 / 续期的失败原因 ──
    const val AUTH_CLIENT_CREDENTIALS = "AUTH_CLIENT_CREDENTIALS"
    const val AUTH_BAD_CODE = "AUTH_BAD_CODE"
    const val AUTH_REDIRECT_MISMATCH = "AUTH_REDIRECT_MISMATCH"
    const val AUTH_GITHUB_ERROR = "AUTH_GITHUB_ERROR"
    const val AUTH_REFRESH_FAILED = "AUTH_REFRESH_FAILED"
}

/**
 * 解析引擎错误串（纯函数，JVM 可直接单测）。
 *
 * 接受的写法（按优先级）：
 *
 * | 输入 | 结果 |
 * |---|---|
 * | `ERROR:HTTP_404:{"message":"Not Found"}` | [EngineError.Coded] `HTTP_404` |
 * | `HTTP_404: {"message":…}`（`RustBridge.err` 去掉前缀后） | 同上 |
 * | `未知错误: HTTP 404 Not Found`（还没换码的旧文案） | 同上（双读） |
 * | `ERROR:ENGINE_UNAVAILABLE` / `引擎不可用`（旧哨兵） | [EngineError.Coded] `ENGINE_UNAVAILABLE` |
 * | `ERROR:某个没见过的码:…` | [EngineError.Raw]（原样，不猜） |
 * | 其它任何文本 | [EngineError.Raw] |
 * | `null` / 空串 | `null`（**没有错误**，与「空错误消息」是两件事） |
 *
 * 为什么「未知码」不折成通用文案：新码意味着引擎那边有了这层还不知道的语义，
 * 硬套一句「请求失败」会把可行动信息（比如「分叉了」）吞掉。
 */
fun parseEngineError(raw: String?): EngineError? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    // `ERROR:` 前缀只在开头剥一次：`detail` 里可能还带着 `ERROR:` 字样（GitHub 原话）
    val body = if (text.startsWith(PREFIX)) text.removePrefix(PREFIX).trim() else text
    if (body.isEmpty()) return null

    // ① 旧哨兵（迁移期双读）。`引擎不可用` 是 RustBridge 在 .so 缺失 / JNI 抛异常时的原话。
    if (body == LEGACY_ENGINE_UNAVAILABLE) {
        return EngineError.Coded(
            code = EngineCodes.ENGINE_UNAVAILABLE,
            res = R.string.engine_error_unavailable,
            detail = body,
        )
    }

    // ② 稳定码：`CODE` 或 `CODE:detail`。只认「全大写 + 下划线」的形状，别把普通英文句子当码。
    CODED.matchEntire(body)?.let { m ->
        val code = m.groupValues[1]
        val detail = m.groupValues[2].takeIf { it.isNotBlank() }
        codedFor(code, detail)?.let { return it }
        // 认得出「这是码」但码表里没有：原样透出（含 detail），不猜
        return EngineError.Raw(body)
    }

    // ③ 迁移期双读：旧文案里的 `HTTP 404` / `HTTP 404 Not Found`。
    //    先去引擎自己那层中文前缀（`未知错误: ` / `认证失败: `，见 core/src/lib.rs 的 thiserror 定义），
    //    再**从头**认 `HTTP <status>`：既不会把正文里出现的数字当状态码，也不会把
    //    `repoAccessHint` 那种以「HTTP 404：」开头的说明句误判成引擎错误（它用的是全角冒号，
    //    这里只认 ASCII 的空白/冒号）。
    val unwrapped = stripLegacyWrapper(body)
    LEGACY_HTTP.find(unwrapped)?.let { m ->
        val status = m.groupValues[1].toIntOrNull()
        if (status != null) return codedFor("${EngineCodes.HTTP}_$status", body)
    }

    return EngineError.Raw(body)
}

/**
 * 把引擎错误串渲染成**当前界面语言**的一句话（`null` = 本来就没有错误）。
 *
 * 调用点在「提示边界」：toast、页内错误行、任务失败原因。**一次接线覆盖所有来源** ——
 * 同一个函数既认新码也认旧文案，所以不需要在调用处判断现在是哪一代引擎。
 */
fun engineErrorText(context: Context, raw: String?): String? = when (val e = parseEngineError(raw)) {
    null -> null
    is EngineError.Coded -> context.getString(e.res, *e.args.toTypedArray())
    is EngineError.Raw -> e.text
}

/**
 * [engineErrorText] 的「一定有值」版本：`null` / 空串渲染成空串。
 *
 * 给「错误原因只可能在这个分支里非空」的调用点用（例如 `err != null` 之后拼进
 * `getString(R.string.error_x_failed, …)`）：那里不需要再写一次 `?:`，
 * 也不会把字面量 `"null"` 拼进界面。
 */
internal fun Context.engineErrorOrRaw(raw: String?): String = engineErrorText(this, raw).orEmpty()

/**
 * git 三态的渲染版本：`"nff"`（[GIT_DIVERGED]）渲染成「远端有新的提交（分叉）…」。
 *
 * 有的调用点（本地分支同步页的推送 / 设置上游）拿到的就是 `"nff"` 这个**哨兵**而不是错误串 ——
 * 直接把哨兵拼进提示会显示成「推送 main 失败：nff」。决策页的入口用 [gitTriState] 自己判，
 * 不经过这里。
 */
internal fun Context.gitOutcomeText(raw: String?): String? =
    if (raw == GIT_DIVERGED) getString(R.string.engine_error_nff) else engineErrorText(this, raw)

/**
 * 引擎错误里的 HTTP 状态码（纯函数；`null` = 不是 HTTP 错误 / 认不出）。
 *
 * 这替代了 `AccountStore.HTTP_CODE`、`RepositoryListScreens` 里那几份手写正则：
 * 业务判断（404 = 不存在还是私有？403 = 权限还是限流？）仍然是调用方的事，但「状态码是多少」
 * 只该有一处解析。
 */
fun engineErrorStatus(raw: String?): Int? = when (val e = parseEngineError(raw)) {
    is EngineError.Coded -> e.args.firstOrNull() as? Int
        ?: e.code.removePrefix("${EngineCodes.HTTP}_").toIntOrNull()?.takeIf { e.code.startsWith("${EngineCodes.HTTP}_") }
    else -> null
}

// ── 解析实现（私有） ──

private const val PREFIX = "ERROR:"
private const val LEGACY_ENGINE_UNAVAILABLE = "引擎不可用"

/**
 * `CODE:detail` / `CODE`。
 *
 * 只认「至少 2 个字符、全大写、数字与下划线」：`HTTP_404`、`ENGINE_UNAVAILABLE` 命中；
 * `HTTP 404 Not Found`（有空格）、`failed to lock file`（小写）不会误判成码。
 * `detail` 允许为空（`HTTP_404:`）。
 */
private val CODED = Regex("^([A-Z][A-Z0-9_]{1,})(?::([\\s\\S]*))?$")

/**
 * 旧文案里的状态码：`HTTP 404` / `HTTP 404 Not Found` / `HTTP 403: …`（只认**串首**）。
 *
 * 状态码后面必须是 ASCII 空白或冒号：中文说明句里的「HTTP 404：」是全角冒号，不会命中。
 */
private val LEGACY_HTTP = Regex("^HTTP\\s+(\\d{3})(?=[\\s:])")

/**
 * 剥掉引擎旧文案自己那层中文前缀（`未知错误: ` / `认证失败: `）。
 *
 * 只剥这两个：它们是 `core/src/lib.rs` 里 `CoreError` 的 Display 前缀，也是唯一会把
 * `HTTP <status>` 包在中间的两种写法（`未知错误: HTTP 404 Not Found`）。
 */
private fun stripLegacyWrapper(body: String): String = when {
    body.startsWith("未知错误: ") -> body.removePrefix("未知错误: ")
    body.startsWith("认证失败: ") -> body.removePrefix("认证失败: ")
    else -> body
}

/**
 * 码 → 资源（两段：git 层查 [CODE_TABLE]，HTTP 按状态码分组）。
 *
 * HTTP 5xx 与未单列的 4xx 折成两条带参数的通用文案：每个状态码单列一条资源是维护负担，
 * 而用户可以行动的信息（重试 / 重新登录）按**类**区分。
 */
private val CODE_TABLE: Map<String, Int> = mapOf(
    EngineCodes.ENGINE_UNAVAILABLE to R.string.engine_error_unavailable,
    EngineCodes.NETWORK to R.string.engine_error_network,
    EngineCodes.NFF to R.string.engine_error_nff,
    EngineCodes.AUTH_FAILED to R.string.engine_error_auth_failed,
    EngineCodes.LOCKED to R.string.engine_error_locked,
    EngineCodes.NOT_GIT_REPO to R.string.engine_error_not_git_repo,
    EngineCodes.REPO_OPEN_FAILED to R.string.engine_error_repo_open_failed,
    EngineCodes.CLONE_TARGET_NOT_EMPTY to R.string.engine_error_clone_target_not_empty,
    EngineCodes.DIRTY_WORKTREE to R.string.engine_error_dirty_worktree,
    EngineCodes.CHECKOUT_BLOCKED to R.string.engine_error_checkout_blocked,
    EngineCodes.BRANCH_EXISTS to R.string.engine_error_branch_exists,
    EngineCodes.BRANCH_NOT_FOUND to R.string.engine_error_branch_not_found,
    EngineCodes.CURRENT_BRANCH to R.string.engine_error_current_branch,
    EngineCodes.MERGE_IN_PROGRESS to R.string.engine_error_merge_in_progress,
    EngineCodes.NO_MERGE_IN_PROGRESS to R.string.engine_error_no_merge_in_progress,
    EngineCodes.CONFLICTS_REMAIN to R.string.engine_error_conflicts_remain,
    EngineCodes.NO_PARENT_COMMIT to R.string.engine_error_no_parent_commit,
    EngineCodes.REMOTE_REF_MISSING to R.string.engine_error_remote_ref_missing,
    EngineCodes.SHALLOW_CLONE to R.string.engine_error_shallow_clone,
    EngineCodes.NO_COMMON_ANCESTOR to R.string.engine_error_no_common_ancestor,
    EngineCodes.NOT_IN_CONFLICT to R.string.engine_error_not_in_conflict,
    EngineCodes.SIGNATURE_MISSING to R.string.engine_error_signature_missing,
    EngineCodes.UNMERGED_INDEX to R.string.engine_error_unmerged_index,
    EngineCodes.REMOTE_MISSING to R.string.engine_error_remote_missing,
    EngineCodes.EMPTY_REPO to R.string.engine_error_empty_repo,
    EngineCodes.REPO_WRITE_FAILED to R.string.engine_error_repo_write_failed,
    EngineCodes.REPO_CORRUPT to R.string.engine_error_repo_corrupt,
    EngineCodes.CANCELLED to R.string.engine_error_cancelled,
    // 前三条复用登录页已有的文案（换码不换话）
    EngineCodes.AUTH_CLIENT_CREDENTIALS to R.string.error_oauth_client_credentials,
    EngineCodes.AUTH_BAD_CODE to R.string.error_oauth_bad_code,
    EngineCodes.AUTH_REDIRECT_MISMATCH to R.string.error_oauth_redirect_mismatch,
    EngineCodes.AUTH_GITHUB_ERROR to R.string.engine_error_auth_github,
    EngineCodes.AUTH_REFRESH_FAILED to R.string.engine_error_auth_refresh,
)

private fun codedFor(code: String, detail: String?): EngineError.Coded? {
    if (!code.startsWith("${EngineCodes.HTTP}_")) {
        val res = CODE_TABLE[code] ?: return null
        return EngineError.Coded(code, res, detail = detail)
    }
    val status = code.removePrefix("${EngineCodes.HTTP}_").toIntOrNull() ?: return null
    val res = when (status) {
        401 -> R.string.engine_error_http_401
        403 -> R.string.engine_error_http_403
        404 -> R.string.engine_error_http_404
        409 -> R.string.engine_error_http_409
        422 -> R.string.engine_error_http_422
        429 -> R.string.engine_error_http_429
        in 500..599 -> R.string.engine_error_http_server
        else -> R.string.engine_error_http
    }
    // 参数只有一个：状态码本身。两条带参数的文案都用 `%1$s`（语序不同的语言里译者能调换位置），
    // 单列的那几条不带参数 —— 所以这里统一传，`getString` 忽略未用到的参数。
    return EngineError.Coded(code, res, args = listOf(status), detail = detail)
}
