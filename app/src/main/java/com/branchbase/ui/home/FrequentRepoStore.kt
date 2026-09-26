package com.branchbase.ui.home

import android.content.Context
import com.branchbase.core.AccountStore
import com.branchbase.ui.settings.SettingsKeys
import org.json.JSONArray
import org.json.JSONObject

/**
 * 首页「常用仓库」的**数据源缓存键**（`/user/repos` 的原始 JSON，查询串见 [MY_REPOS_PATH]）。
 *
 * 首页与管理页读的是**同一份** JSON：首页进管理页时先用它渲染（L2 缓存，零网络），
 * 管理页刷新后回写，首页下次进前台直接看到新数据。
 * 键写在这里、不写两份字面量，就是为了不让两个页面各自漂移（`SettingsKeys` §8.1 同一条理由）。
 *
 * 为什么不再用 `starred_repos`：1.1.8 及以前这一栏取的是星标全集，那是**另一份响应**。
 * 换数据源就必须换键 —— 复用旧键会让升级后的第一帧先渲染一批已经不在候选集里的仓库，
 * 随后被网络结果覆盖，用户看到的是「闪一下又变」。
 */
internal const val MY_REPOS_CACHE_KEY = "my_repos"

/**
 * 「我能用的仓库」查询串：账户自己持有的 + 有权限/被协作的 + 所属组织与团队的。
 *
 * `affiliation` 显式写全三个值（GitHub 的默认值恰好就是这三个），因为这正是需求里的四类仓库：
 * `owner` = 账号自身持有；`collaborator` = 被邀请协作、对仓库拥有权限；
 * `organization_member` = 所属组织（含团队授权）里的仓库。显式写出来，默认值将来若变也不会漂。
 *
 * `sort=pushed&direction=desc` **必须留在服务端**：`per_page=100` 只取第一页，
 * 改成客户端排序就变成「先按仓库名取一页、再把这一页排序」—— 名字靠后的活跃仓库
 * 根本进不了这一页，首页那 5 个就会挑错人。按最近推送排序也更贴近「常用」的直觉。
 */
internal const val MY_REPOS_PATH =
    "/user/repos?per_page=100&affiliation=owner,collaborator,organization_member&sort=pushed&direction=desc"

/**
 * 首页**没置顶过**时用的数据源：星标（收藏）仓库，服务端按「最近星标」倒序。
 *
 * `sort=created&direction=desc` 与 [MY_REPOS_PATH] 同一条理由：`per_page=100` 只取第一页，
 * 排序必须由服务端做（客户端排序 = 先随便取一页再排）。而且这里显式写全，
 * 是因为「最近收藏的 5 个」这个语义**完全**依赖这个顺序，不能靠默认值。
 */
internal const val STARRED_RECENT_PATH =
    "/user/starred?per_page=100&sort=created&direction=desc"

/**
 * [STARRED_RECENT_PATH] 的缓存键 —— **刻意沿用 1.1.8 的旧键**。
 *
 * 1.1.8 及以前这一栏取的就是星标全集，用的就是这个键；1.1.9 一度把它让给 `my_repos`。
 * 现在「没置顶过时显示收藏仓库」又把星标请回来，于是直接捡回旧键：
 * 升级上来的用户本地已经有这份缓存，首帧就能渲染出和以前一样的 5 个仓库，而不是先闪一下空态。
 */
internal const val STARRED_RECENT_CACHE_KEY = "starred_repos"

/**
 * 「常用仓库」这一栏该向谁要数据（纯函数，便于单测 —— 见 `MyReposSourceTest`）。
 *
 * 规则只有一条，但它是用户明确要求的边界（原话）：
 * 「若设置常用仓库，则不显示收藏仓库，只显示常用仓库，除非取消所有常用仓库的选择」。
 * 也就是说：**置顶过 → 只显示常用仓库；没置顶过、或把置顶全取消 → 回到默认态，显示收藏仓库最近 5 个**。
 * 「清空置顶」因此不是空态，而是「还原默认」。
 */
internal object FrequentRepoSource {

    /** @param pinned `null` 或空列表 = 没置顶过 → 收藏仓库；否则 → 我能用的仓库。 */
    fun pathFor(pinned: List<String>?): String =
        if (pinned.isNullOrEmpty()) STARRED_RECENT_PATH else MY_REPOS_PATH

    /**
     * 与 [pathFor] 一一对应的缓存键。
     *
     * 两个数据源**必须**有各自的键：共用的话，切到另一个源时会先拿星标 JSON 当候选集渲染，
     * 或者反过来把「我能用的仓库」画进默认态 —— 都是「闪一下又变」，且顺序来源都不对。
     */
    fun cacheKeyFor(pinned: List<String>?): String =
        if (pinned.isNullOrEmpty()) STARRED_RECENT_CACHE_KEY else MY_REPOS_CACHE_KEY
}

/**
 * 算出「常用仓库」该写进哪个账号桶。
 *
 * 首页与管理页必须用**这一个**函数算桶名。两边各抄一遍兜底链的话，将来只改一边
 * （比如「先看账号表再看 session」），用户就会撞上最难查的一类 bug：
 * 管理页里明明勾好了，回首页一个都不显示 —— 数据在，只是落在另一个桶里。
 *
 * 兜底链与首页原本的 `login` 解析一致：`session.user.login` → 账号表的当前账号 → 空串。
 * 空串是「认不出账号」时的桶（不是错误），两侧算出来一样，行为仍然自洽。
 */
internal fun frequentRepoBucket(context: Context, sessionJson: String): String =
    runCatching { JSONObject(sessionJson).getJSONObject("user").optString("login") }
        .getOrNull()?.takeIf { it.isNotBlank() && it != "null" }
        ?: AccountStore.currentLogin(context).takeIf { it.isNotBlank() }
        ?: ""

/**
 * 首页「常用仓库」**置顶选择**的本地存储（需求：长按首页标题 → 列表页勾选 → 置顶到首页）。
 *
 * ## 三条硬性约束
 * 1. **只在本地**：GitHub 没有「把仓库置顶到 App 首页」这种概念；数据源 `/user/repos`
 *    自带的顺序只是「最近推送」，不是「我最常用」。用户要的是「我自己排的序」，
 *    这份顺序只能我们自己记。
 * 2. **随账号隔离**：一台设备可以登多个账号（`docs/specs/features-design.md` §1），
 *    「常用」天然是每账号各一份，所以值是一个 `{"<login>": [...]}` 的对象。
 * 3. **更新/重装不丢**：走 `branchbase` prefs，而备份规则（`res/xml/backup_rules.xml`
 *    与 `data_extraction_rules.xml`）只排除 `repo_credentials.xml`，所以这份选择
 *    参与云备份与换机迁移，App 更新更是不受影响。
 *
 * ## 为什么「缺键」和「空数组」在存储上分开、在显示上同义
 * - **缺键**（该账号不在对象里）= 用户从没自定义过；
 * - **空数组** = 用户进管理页把置顶全部取消。
 *
 * 两者首页都显示「收藏（星标）仓库最近 5 个」—— 这就是需求里的默认态
 * （用户原话：「若设置常用仓库，则不显示收藏仓库，只显示常用仓库，除非取消所有常用仓库的选择」）。
 * 所以「清空置顶」不是空态，而是**还原默认**，规则见 [FrequentRepoSource]。
 *
 * 存储上仍然分开，是因为管理页要能区分「一个都没勾」和「从没进过这一页」
 * （前者进页面时计数是 0/100，后者是 0/100 但清除按钮不出现），而且将来若要给
 * 「清空过」换个行为，这一位信息是现成的。
 */
object FrequentRepoStore {

    /**
     * 读某账号的置顶列表（**保序**）。
     *
     * @return `null` = 从未自定义过；空列表 = 明确清空。首页显示上两者同义（都回落收藏仓库，
     *   见 [FrequentRepoSource]），但存储上保留这一位区别（见类注释）。
     */
    fun selection(context: Context, login: String): List<String>? {
        val raw = SettingsKeys.prefs(context).getString(SettingsKeys.HOME_FREQUENT_REPOS, null) ?: return null
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val arr = obj.optJSONArray(login) ?: return null
        return (0 until arr.length()).mapNotNull { i ->
            arr.optString(i, "").takeIf { it.isNotBlank() }
        }
    }

    /**
     * 写某账号的置顶列表（覆盖式；`fullNames` 的**数组顺序就是首页显示顺序**）。
     *
     * 只改这一个账号的桶，其它账号原样保留 —— 否则在 A 账号里点一下会清掉 B 账号的选择。
     * 用 `apply()`：用户点一下就写，主线程不做同步盘 I/O；进程内存里的值立即可见，
     * 返回首页那一次重组就能读到。
     */
    fun save(context: Context, login: String, fullNames: List<String>) {
        val prefs = SettingsKeys.prefs(context)
        val obj = runCatching {
            JSONObject(prefs.getString(SettingsKeys.HOME_FREQUENT_REPOS, null) ?: "{}")
        }.getOrNull() ?: JSONObject()
        obj.put(login, JSONArray(fullNames))
        prefs.edit().putString(SettingsKeys.HOME_FREQUENT_REPOS, obj.toString()).apply()
    }
}

/**
 * 首页「常用仓库」的**显示规则**（纯函数，便于单测；见 `FrequentRepoRulesTest`）。
 *
 * 把规则从 Compose 里抽出来，是因为这几条全是**用户能感知的边界**：
 * 首页最多几个、顺序听谁的、没勾过时怎么办、勾过的仓库被取消星标后怎么办。
 */
object FrequentRepoRules {

    /** 首页「常用仓库」最多显示几个。 */
    const val HOME_LIMIT = 5

    /**
     * 算出首页实际要渲染哪些仓库。
     *
     * - `selection` 为 `null` 或**空**（没置顶过 / 把置顶全取消）→ 回落该数据源自己的顺序取前 [limit] 个：
     *   默认态是「收藏仓库最近星标的 5 个」，与 1.1.8 及以前一致。
     * - `selection` 非空 → **按用户点选的先后**排（后选的在后面），并且
     *   ① 只保留仍在 [all] 里的（仓库不再出现在候选集里 —— 被移出协作者、组织权限变更、
     *   仓库转移 —— 首页自动不显示，不留死条目）；
     *   ② 超过 [limit] 的截断（正常情况下管理页就不让选第 6 个，这里只是兜底：
     *   老版本写进来的、或以后放宽上限时留下的数据）。
     */
    fun <T> visibleOnHome(
        all: List<T>,
        selection: List<String>?,
        limit: Int = HOME_LIMIT,
        key: (T) -> String,
    ): List<T> {
        if (selection.isNullOrEmpty()) return all.take(limit)
        val byName = all.associateBy(key)
        return selection.mapNotNull { byName[it] }.take(limit)
    }

    /**
     * 还能不能再勾一个。
     *
     * 已经勾上的永远可以再点（那是取消），所以 `fullName in selection` 直接放行 ——
     * 否则「选满 5 个之后连取消都点不动」。
     */
    fun canPin(selection: List<String>, fullName: String, limit: Int = HOME_LIMIT): Boolean =
        fullName in selection || selection.size < limit

    /**
     * 点一下列表条目：没勾上就**追加到末尾**（末尾 = 首页更靠后），勾上了就取消。
     *
     * 不重排已有条目：用户按 A→B→C 点出来的顺序就是 A、B、C，
     * 中途取消 B 之后 A、C 的相对顺序不变（重新点 B 只会把它排到末尾）。
     */
    fun toggle(selection: List<String>, fullName: String): List<String> =
        if (fullName in selection) selection - fullName else selection + fullName
}
