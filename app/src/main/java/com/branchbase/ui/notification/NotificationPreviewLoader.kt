package com.branchbase.ui.notification

import android.content.Context
import com.branchbase.cache.PageCache
import com.branchbase.cache.SearchCacheDatabase
import com.branchbase.cache.SearchCacheManager
import com.branchbase.core.RustBridge
import com.branchbase.ui.log.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 通知内容预览加载器（`subject.latest_comment_url` → 评论正文）。
 *
 * ## 为什么需要它
 *
 * 通知列表目前每行只有三样东西：**issue/PR 标题 + 仓库 #编号 + reason（触发动因）**。
 * 用户看不出「到底谁说了什么」——「提到了你」后面跟的是一句评审意见还是一个「+1」，
 * 必须点进详情页才知道。而 GitHub 的通知对象里已经带了
 * `subject.latest_comment_url`（该 thread 最新一条评论的 REST API URL），
 * 拉一次 `GET /repos/{owner}/{repo}/issues/comments/{id}` 就能拿到评论正文与作者头像，
 * 于是列表行可以在标题下面直接补一行「作者：正文」，不点进去也能判断值不值得看。
 *
 * ## 为什么要限流与缓存
 *
 * 预览是**每条通知一次额外请求**：一屏 50 条通知就是 50 次 HTTP，而 GitHub 的 REST
 * 二级速率限制对突发并发极其敏感（超过建议并发会直接 `403 secondary rate limit`），
 * 一旦被限流，**整个页面的通知列表也跟着拉不到**。所以这里做了两件事：
 *
 * - **并发上限**（[prefetchPreviews] 的 `maxConcurrent`，默认 2）：把突发摊平成串行小流量；
 * - **缓存**（复用 [SearchCacheManager] + [PageCache.TYPE_NOTIFICATION]，TTL 2 分钟）：
 *   上下滚动、退出再进、`LazyColumn` 重组都不会重复请求同一条评论。
 *
 * 预览是**纯增强信息**：任何一条失败都只影响它自己不显示，绝不影响列表、绝不抛异常
 * （[prefetchPreviews] 整体 `runCatching` 兜底，单条失败静默跳过）。
 */

/** 通知内容预览（来自 subject.latest_comment_url 指向的评论）。 */
data class NotificationPreview(
    val threadId: String,     // 对应 Notification.id
    val author: String,       // 评论作者 login（为空则空串）
    val avatarUrl: String,    // 作者头像（可为空串）
    val body: String,         // 评论正文（已把 markdown 折成纯文本，见下）
    val createdAt: String,    // ISO8601（可为空串）
)

/** 评论 path 的严格形态：`/repos/{owner}/{repo}/(issues|pulls)?/?comments/{数字}` */
private val COMMENT_PATH = Regex("^/repos/[^/]+/[^/]+/(?:(?:issues|pulls)/)?comments/\\d+$")

/**
 * 从完整 API URL 提取 path（复用同包 [extractPathFromUrl]）。解析失败返回 null。
 *
 * **签名说明**：入参是 URL 字符串而不是 `Notification` —— 当前 `NotificationModels.kt` 里
 * 还没有 `subject.latest_comment_url` 字段（该字段由模型侧补充），本文件若按 `Notification`
 * 取字段会编译不过。调用方在模型侧字段落地后传 `n.latestCommentUrl` 即可，
 * 那边加一个 `val latestCommentUrl: String?` 本文件不需要改动。
 *
 * 校验比 [extractPathFromUrl] 严一档（那个是安全警报详情的通用入口，这里是预览专用）：
 * 1. URL 必须含 `/repos/`，取其后的 path，并丢掉 `?`/`#` 之后的查询串与锚点；
 * 2. path 必须匹配 [COMMENT_PATH]，否则返回 null —— `latest_comment_url` 有时会退化成
 *    issue/PR 本体（`/repos/o/r/issues/42`）或指向 commit/discussion，
 *    那些都不该被当成评论去解析。
 *
 * @return 可直接喂给 `RustBridge.getJson(host, token, path)` 的 path；非评论 URL 则 null。
 */
fun latestCommentPath(commentUrl: String): String? {
    val path = extractPathFromUrl(commentUrl)
        ?.substringBefore('?')
        ?.substringBefore('#')
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: return null
    return if (COMMENT_PATH.matches(path)) path else null
}

/**
 * 把评论正文折成一行可预览的纯文本：去掉多余空白与换行、去 markdown 标记、
 * 截断到 [maxChars]（默认 140），超出加「…」。纯函数、不碰 Android API，可 JVM 单测。
 *
 * **处理顺序**（顺序很重要，写反了规则之间会互相破坏语法）：
 * ```
 * ① HTML 注释 <!-- ... -->：整段丢弃（非贪婪 + DOT_MATCHES_ALL，
 *    否则两个注释之间的正文会被一起吃掉）
 * ② HTML 标签：只去标签、保留标签内文字 → <sub>补充</sub> 变成「补充」
 * ③ 图片 ![alt](url)：整段丢弃（预览里画不出图，留 url 只是噪音）
 * ④ 链接 [text](url)：只留 text
 * ⑤ 代码围栏 ```（含 ```lang）与行内反引号：先围栏、后行内，
 *    否则围栏会被行内规则拆散
 * ⑥ 强调标记 **粗** / __粗__ / *斜* / _斜_：先长后短，
 *    `**` 必须在 `*` 之前，否则 `**粗**` 会剩下一堆孤立的 `*`
 * ⑦ 行首标记（全部锚定行首，避免误伤正文中间的 `-`/`#`）：
 *    `#` 标题、`>` 引用、`-`/`*`/`+` 列表、`1.` 有序列表、`---` 分隔线
 * ⑧ 折行与收敛：所有换行和连续空白（含制表符/全角空格）折成单个空格，首尾 trim
 * ⑨ 截断：超长时最多留下 `maxChars` 个**码点**（不是 UTF-16 char），
 *    这样 emoji 这类四字节字符不会被拦腰切断；真的截掉了才补「…」
 * ```
 *
 * 极端参数：`maxChars <= 0` 返回空串；`maxChars` 小到 1 且首个字符是代理对时，
 * 取其一半会产出乱码，这时宁可返回空串也不返回半个字符。
 */
fun previewText(markdown: String, maxChars: Int = 140): String {
    var t = markdown
    // ① HTML 注释：整段丢弃
    t = t.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), " ")
    // ② HTML 标签：去标签、留文字
    t = t.replace(Regex("</?[A-Za-z][^>]*>"), "")
    // ③ 图片：整段丢弃
    t = t.replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), " ")
    // ④ 链接：只留可见文字
    t = t.replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
    // ⑤ 代码围栏 + 行内反引号
    t = t.replace(Regex("```[A-Za-z0-9_+-]*"), " ")
    t = t.replace("`", "")
    // ⑥ 强调标记（先长后短）
    t = t.replace(Regex("\\*\\*(.+?)\\*\\*", RegexOption.DOT_MATCHES_ALL), "$1")
    t = t.replace(Regex("__(.+?)__", RegexOption.DOT_MATCHES_ALL), "$1")
    t = t.replace(Regex("\\*(.+?)\\*", RegexOption.DOT_MATCHES_ALL), "$1")
    t = t.replace(Regex("_(.+?)_", RegexOption.DOT_MATCHES_ALL), "$1")
    // ⑦ 行首标记
    t = t.replace(Regex("^\\s*#{1,6}\\s+", setOf(RegexOption.MULTILINE)), "")
    t = t.replace(Regex("^\\s*>\\s?", setOf(RegexOption.MULTILINE)), "")
    t = t.replace(Regex("^\\s*[-*+]\\s+", setOf(RegexOption.MULTILINE)), "")
    t = t.replace(Regex("^\\s*\\d+[.)]\\s+", setOf(RegexOption.MULTILINE)), "")
    t = t.replace(Regex("^\\s*([-*_])\\1{2,}\\s*$", setOf(RegexOption.MULTILINE)), " ")
    // ⑧ 折行 + 收敛空白
    t = t.replace(Regex("\\s+"), " ").trim()

    // ⑨ 截断：以**码点**为单位（不是 UTF-16 char），这样 emoji 这类四字节字符不会被拦腰切断
    if (maxChars <= 0) return ""
    if (t.codePointCount(0, t.length) <= maxChars) return t // 没超长就不截断、也不加「…」
    // offsetByCodePoints 落在码点边界上，因此不会有孤立代理字符
    val cut = t.offsetByCodePoints(0, maxChars)
    val head = t.substring(0, cut).trimEnd()
    return if (head.isEmpty()) "" else head + "…" // 全被 trim 掉时不返回孤零零的「…」
}

/**
 * 解析 `GET /repos/{o}/{r}/issues/comments/{id}`（或 pulls/comments、comments）响应。
 * 失败返回 null。
 *
 * 两条使用约束：
 * - **只能喂原始评论 JSON**，不要把 [previewText] 处理过的纯文本再喂进来。
 *   [prefetchPreviews] 因此往缓存里写的是**原始响应**而不是预览结果，就是为了让本函数可复用。
 * - 正文为空（空串 / 全是空白 / 只有一张图而图已被 [previewText] 丢弃）时返回 null：
 *   「有预览对象但内容为空」和「没有预览」对 UI 是一回事，调用方少一个判空分支。
 */
fun parsePreview(threadId: String, json: String?): NotificationPreview? = runCatching {
    if (json.isNullOrBlank()) return@runCatching null
    if (json.startsWith("ERROR:")) return@runCatching null
    val o = JSONObject(json)
    val user = o.optJSONObject("user")
    val body = previewText(o.str("body"))
    if (body.isBlank()) return@runCatching null
    NotificationPreview(
        threadId = threadId,
        author = user?.str("login").orEmpty(),
        avatarUrl = user?.str("avatar_url").orEmpty(),
        body = body,
        createdAt = o.str("created_at"),
    )
}.getOrNull()

/**
 * 安全取字符串：字段缺失或值为 JSON `null` 时返回空串。
 *
 * 必须用 `isNull` 而不是比较 `optString(...) != "null"` —— `optString` 遇到 JSON `null`
 * 会返回**字符串** `"null"`，那种写法在「正文真的就是 null 值」时会把 `"null"` 当成正文渲染出来。
 */
private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key).orEmpty()

/**
 * 批量预取预览（带缓存与并发限制）。
 *
 * @param threadIds 只处理这些 thread（调用方已按「未读 + 类型 Issue/PullRequest + 前 N 条」筛过）
 * @param commentUrlOf 把 threadId 映射到 `subject.latest_comment_url`，**调用方必须提供**：
 *        `threadIds` 里只有通知 id，而拉预览需要的是 URL，两者之间的桥梁只有模型侧知道。
 *        模型侧 `latestCommentUrl` 字段落地后，调用点写
 *        `{ id -> byId[id]?.latestCommentUrl }` 即可。
 *        默认值返回 null（一条都解析不出 URL 时整批跳过），
 *        这种情况会打一条告警日志，不会静默失败、也不会抛异常。
 * @param maxConcurrent 并发上限，默认 2（避免触发二级速率限制）
 * @param onEach 每成功一条回调一次（**主线程** `Dispatchers.Main` 调用，调用方据此刷新 UI）；
 *        缓存命中与网络回源都算成功，失败/空正文不回调。
 *        **刻意放在参数表最后**：调用方用尾随 lambda 写法时，`{ }` 绑定到的是最后一个参数 ——
 *        若它后面还有 `maxConcurrent: Int`，尾随 lambda 会静默绑到 `maxConcurrent` 上并报
 *        「No value passed for parameter 'onEach'」，所以这里的顺序不是随意的。
 *
 * 执行流程（每条 thread 独立，互不阻塞）：
 * 1. 缓存命中（未过期）→ 直接 [parsePreview] + [onEach]，**不发请求、不占并发许可**；
 * 2. 未命中 → `Semaphore` 取许可（超出 [maxConcurrent] 的排队等待）→ 回源
 *    [PageCache.refresh]（成功后写回缓存；TTL 2 分钟，过期自动回源，失败不覆盖旧缓存）；
 * 3. 单条失败（null / `ERROR:` 前缀 / 解析失败 / 空正文 / URL 非评论）静默跳过，不影响其它条目；
 * 4. 本函数从不抛异常（整体 `runCatching` 兜底）。
 *
 * 注意：本函数**挂起到全部子任务结束**（`coroutineScope` 语义），`onEach` 在过程中逐条回调；
 * 需要「边取边渲染」就在调用点 `launch` 它，不要阻塞 UI 线程。
 */
suspend fun prefetchPreviews(
    context: android.content.Context,
    host: String,
    token: String,
    threadIds: List<String>,
    commentUrlOf: (String) -> String? = { null },
    maxConcurrent: Int = 2,
    onEach: (NotificationPreview) -> Unit,
) {
    val ids = threadIds.distinct()
    if (ids.isEmpty()) return
    runCatching {
        // maxConcurrent <= 0 会造出「永不发许可」的信号量，整批请求被挂死 —— 兜到 1
        val semaphore = Semaphore(if (maxConcurrent < 1) 1 else maxConcurrent)
        val manager = SearchCacheManager(SearchCacheDatabase.getInstance(context).searchCacheDao())

        // threadId → 评论 URL。解析不出 URL 的条目直接不参与（不是错误：该 thread 本来就没有评论）
        val targets = ids.mapNotNull { id ->
            commentUrlOf(id)?.takeIf { it.isNotBlank() }?.let { id to it }
        }
        if (targets.isEmpty()) {
            // 一条都没解析出 URL ⇒ 多半是没传 commentUrlOf（模型侧字段尚未接入）
            Logger.net("通知预览未发起：${ids.size} 条 thread 都没解析出评论 URL", "GitHubAPI")
            return@runCatching
        }

        coroutineScope {
            for ((threadId, commentUrl) in targets) {
                launch {
                    runCatching {
                        val path = latestCommentPath(commentUrl) ?: return@runCatching
                        val key = PageCache.notificationKey("preview:$commentUrl")

                        // ① 先查缓存：命中就直接用，既不发请求也不占并发许可
                        val cached = withContext(Dispatchers.IO) {
                            manager.get(key, PageCache.TYPE_NOTIFICATION)
                        }
                        // ② 未命中才走限流回源（refresh 内部写回缓存，失败返回 null 且不覆盖旧值）
                        val json = cached ?: semaphore.withPermit {
                            PageCache.refresh(manager, key, PageCache.TYPE_NOTIFICATION) {
                                RustBridge.getJson(host, token, path)
                            }
                        } ?: return@runCatching

                        if (json.startsWith("ERROR:")) return@runCatching
                        val preview = parsePreview(threadId, json) ?: return@runCatching
                        withContext(Dispatchers.Main) { onEach(preview) }
                    }.onFailure {
                        // 单条失败静默跳过：预览是增强信息，不该影响其它条目
                        Logger.net("通知预览单条失败（$threadId）：${it.message}", "GitHubAPI")
                    }
                }
            }
        }
    }.onFailure { Logger.net("通知预览预取整体失败：${it.message}", "GitHubAPI") }
}
