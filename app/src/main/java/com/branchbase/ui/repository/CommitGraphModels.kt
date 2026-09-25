package com.branchbase.ui.repository

import org.json.JSONArray
import org.json.JSONObject

/**
 * 提交图的数据模型与**泳道布局**（纯逻辑，可 JVM 单测）。
 *
 * 真源与取舍见 [`git-mode-design.md`](../../../../../../docs/specs/git-mode-design.md)
 * §4.1（数据与分页）/ §4.2（泳道布局）：
 * 布局**预先算好**（每行的泳道与线段），绘制期只按行画自己那几条线 ——
 * 一屏百行时这是「能滑动」与「滑动掉帧」的分界。
 */

/** 引用标注的种类（HEAD / 本地分支 / 远端分支 / tag / 上游）。 */
enum class RefKind { HEAD, LOCAL_BRANCH, REMOTE_BRANCH, TAG, UPSTREAM }

/** 挂在某个提交上的一条引用。 */
data class CommitRef(val name: String, val kind: RefKind)

/** 图上的一条提交（已解析 `parents` 与引用标注）。 */
data class GraphCommit(
    val fullSha: String,
    val parents: List<String>,
    val subject: String,
    val author: String,
    val date: String,
    val refs: List<CommitRef> = emptyList(),
    /**
     * 这条提交**还没推送到上游**（`git log @{u}..HEAD` 里的一条）。
     *
     * 只有**本地来源**答得出来（[parseLocalGraphCommits] 读引擎的 `unpushed`）；REST 那份响应里
     * 根本没有「本地推没推」这件事，所以 [parseGraphCommits] 一律留 false ——
     * 这不是「都推过了」，是「这一屏回答不了」，UI 也因此不在 REST 来源下画这个标记。
     */
    val unpushed: Boolean = false,
) {
    /** 短 sha（列表左侧那一列）。 */
    val shortSha: String get() = fullSha.take(7)
}

/** 图上的一条边（本行要画的线段）。 */
data class GraphEdge(
    val fromLane: Int,
    val toLane: Int,
    /** 起点是不是本行的节点（false = 只是「穿过」本行）。 */
    val fromNode: Boolean,
    /** 终点是不是本行的节点（合并的第二父会指向更下方的某一行的节点之外的位置）。 */
    val toNode: Boolean,
    /** 父提交不在已加载窗口里 —— 画成终止符，**不许画成断头线**。 */
    val dangling: Boolean = false,
)

/** 一条提交在图上占的一行（几何已预计算）。 */
data class GraphCommitRow(
    val commit: GraphCommit,
    /** 节点所在泳道。 */
    val lane: Int,
    /** 本行泳道总数（决定 gutter 宽度上限）。 */
    val laneCount: Int,
    val edges: List<GraphEdge>,
    /** 该提交的父不在已加载窗口里（分页截断 / 更早历史未加载）。 */
    val dangling: Boolean,
)

/**
 * 图上的一行：**未提交的工作区虚节点**，或一条真实提交。
 *
 * 虚节点是产品拍板「画」（方案 A）：它在 HEAD 之上，没有 sha、不是 git 对象 ——
 * 所以**所有以 sha 为键的交互都不适用于它**（详情 / 复制 / 对比 / 回滚），
 * 点它只能进「工作区」档。
 */
sealed interface GraphRow {
    val laneCount: Int

    data class WorkingTree(val dirtyCount: Int) : GraphRow {
        override val laneCount: Int get() = 1
    }

    data class Commit(val row: GraphCommitRow) : GraphRow {
        override val laneCount: Int get() = row.laneCount
    }
}

/**
 * 解析 `GET /repos/{o}/{r}/commits`（**保留 `parents`**）。
 *
 * 为什么另起一个解析函数而不是改 `parseCommits`：那个的 `CommitItem` 只服务「提交列表」，
 * 改成带 `parents` 会牵动列表页与它们的单测；图是另一件事，**各解析各的**（版本树设计稿 §2 的口径）。
 *
 * 解析失败返回空列表 —— 与 `parseCommits` 一致：调用方按「空列表 = 没有提交 / 解析不出来」渲染，
 * 不在这里抛。
 *
 * `unpushed` 一律留 false：REST 那份响应里没有「本地推没推」这件事（那是**本地**才知道的事实），
 * 所以这一来源下不画未推送标记 —— 见 [GraphCommit.unpushed]。
 */
fun parseGraphCommits(json: String?): List<GraphCommit> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val commit = o.optJSONObject("commit") ?: JSONObject()
            val sha = o.optString("sha")
            if (sha.isBlank()) return@mapNotNull null
            val parents = commit.optJSONArray("parents")?.let { ps ->
                (0 until ps.length()).mapNotNull { k ->
                    ps.optJSONObject(k)?.optString("sha")?.takeIf { it.isNotBlank() }
                }
            }.orEmpty()
            GraphCommit(
                fullSha = sha,
                parents = parents,
                subject = commit.optString("message").lineSequence().firstOrNull().orEmpty(),
                author = commit.optJSONObject("author")?.optString("name")
                    ?: o.optJSONObject("author")?.optString("login").orEmpty(),
                date = commit.optJSONObject("author")?.optString("date").orEmpty(),
            )
        }
    }.getOrDefault(emptyList())
}

/**
 * 解析本地 `log_graph` 的输出（**扁平** native JSON：`[{sha, parents, subject, author, date, unpushed}]`）。
 *
 * 与 [parseGraphCommits] 分开是**故意的**：本地那份不是 GitHub 的嵌套响应体
 * （`{sha, commit:{message, author:{…}, parents:[{sha}]}}`），把两者塞进一个函数只会
 * 让「谁在撒谎」变得难查 —— 字段口径不同就各解析各的，与 `parseCommits` / `parseGraphCommits`
 * 分开的理由是同一条。
 *
 * 容错口径与其它解析一致：解析不出来返回空列表、不抛。但**空数组是有意义的另一件事**
 * （仓库里真的还没有提交），调用方按「还没有提交」渲染，不是「读取失败」。
 *
 * `unpushed` 缺省 false：老的 `.so` 里没有这个键（原生库与 Kotlin 是两份产物），
 * 缺了就按「不知道」退化 —— 不许把缺失读成「都推过了」，也不许整档报错。
 */
fun parseLocalGraphCommits(json: String?): List<GraphCommit> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val sha = o.optString("sha")
            if (sha.isBlank()) return@mapNotNull null
            val parents = o.optJSONArray("parents")?.let { ps ->
                (0 until ps.length()).mapNotNull { k ->
                    ps.optString(k).takeIf { it.isNotBlank() }
                }
            }.orEmpty()
            GraphCommit(
                fullSha = sha,
                parents = parents,
                subject = o.optString("subject"),
                author = o.optString("author"),
                date = o.optString("date"),
                unpushed = o.optBoolean("unpushed", false),
            )
        }
    }.getOrDefault(emptyList())
}

/**
 * 已加载窗口里**未推送**的条数（提交图脚注用）。
 *
 * 为什么是「窗口里」而不是「一共」：这一档是分页的，没加载到的那部分不在手上 ——
 * 而脚注必须只说手上这份，不能拿 [GraphCommit.unpushed] 的条数冒充「全部待推送 N 条」
 * （工作区档那个 `ahead` 才是全量，两处口径见 `git-mode-design.md` §4.1）。
 */
internal fun unpushedCount(commits: List<GraphCommit>): Int = commits.count { it.unpushed }

/**
 * 提交图的**数据来源**。
 *
 * 阶段 3' 起本地仓库能自己走出提交图（`log_graph`，离线、不消耗 API 限额、还能看见
 * **还没推送的本地提交**）—— 但本地仓库是 `depth(1)` 浅克隆，只有 HEAD 一条提交，
 * 直接拿它当来源就是把「100 条」换成「1 条」。所以来源不是「有没有本地仓库」，
 * 而是「本地仓库**值不值得**当来源」：见 [graphSourceOf]。
 */
enum class GraphSource { LOCAL, REST }

/**
 * 择源规则（纯函数）：**本地仓库存在且不是浅克隆** → 本地；否则 REST。
 *
 * 为什么浅克隆必须排除：clone 用的是 `depth(1)`，而 `pull` / `fetch` 不会撤销浅边界
 * （git 自己也要 `--unshallow`），本地历史上只有 HEAD 一条 —— 那时走本地来源等于让用户
 * 从「一屏 100 条」退回「1 条」。浅克隆的出路是面板里的「拉到本地（加深）」
 * （`fetch_deepen`，阶段 4），加深成功后 `refreshTick` 一变，这个判定自然翻成本地来源。
 */
internal fun graphSourceOf(localRepoExists: Boolean, localShallow: Boolean): GraphSource =
    if (localRepoExists && !localShallow) GraphSource.LOCAL else GraphSource.REST

/** 一页取数请求。**两种来源的分页键不一样**，所以这里是 sealed 而不是一个 Int。 */
internal sealed interface GraphPage {
    /** 本地：按 `skip` 续取（引擎自己走 revwalk，第 N 页就是跳过前 N 条）。 */
    data class Local(val skip: Int) : GraphPage

    /** REST：按**窗口内最老的 sha** 续取（GitHub 的 `sha` 参数不支持 offset）。 */
    data class Rest(val sha: String?) : GraphPage
}

/**
 * 下一页取数键：本地给 `skip = 已加载条数`，REST 给最老那条 sha（首页为 null）。
 *
 * 抽成纯函数是因为这两条在真机上只表现成「点了『加载更早』没反应 / 重复同一页」——
 * 前者看着像网络慢，后者会让人以为仓库历史就这么点。
 */
internal fun nextGraphPage(source: GraphSource, loaded: List<GraphCommit>): GraphPage = when (source) {
    GraphSource.LOCAL -> GraphPage.Local(skip = loaded.size)
    GraphSource.REST -> GraphPage.Rest(sha = loaded.lastOrNull()?.fullSha)
}

/**
 * 泳道布局（经典 swimlane）：**一条泳道 = 一个「还在等谁出现」的父提交**。
 *
 * 规则（`git-version-tree-design.md` §6）：
 * - 每条提交认领「正等它的那条泳道」，没有就开一条；
 * - **第一父继承当前泳道**（历史继续往下走），额外父各占一条（取最左的空位）；
 * - 一条泳道等到的 sha 一直没出现 → 它在下行**穿过**（不能断，否则线会莫名消失）；
 * - 父不在窗口里 → `dangling`（画终止符）；
 * - 纯函数、幂等：同输入同输出（列表增删才会重算）。
 */
object CommitGraphLayout {

    /**
     * @param commits 已按「新的在前」排好的提交（REST `/commits` 的顺序）
     * @param workingTreeDirty 工作区改动数；> 0 时在最上方插一行**虚节点**（HEAD 之上），null / 0 不插
     */
    fun layout(commits: List<GraphCommit>, workingTreeDirty: Int? = null): List<GraphRow> {
        val rows = mutableListOf<GraphRow>()
        if (workingTreeDirty != null && workingTreeDirty > 0) {
            rows += GraphRow.WorkingTree(workingTreeDirty)
        }
        if (commits.isEmpty()) return rows

        val shas = commits.map { it.fullSha }.toHashSet()
        // 每条泳道正在等的 sha（null = 空位，可被复用）
        val lanes = mutableListOf<String?>()

        commits.forEach { commit ->
            // 0) 先把上一行空出来的泳道**压实**：泳道是个列表，空位留在中间会让后面的节点
            //    永远往右偏（真机上看着就是「分支莫名其妙跳到右边」）。位移本身要画成斜线，
            //    否则那根线在行与行之间会断掉 —— 位移记在本行（线从上一行的旧位置斜进新位置）。
            val shifts = compact(lanes)

            // 1) 认领泳道：已经有泳道在等它（取最左），否则用最左空位 / 新开一条
            var lane = lanes.indexOfFirst { it == commit.fullSha }
            if (lane < 0) {
                lane = lanes.indexOfFirst { it == null }
                if (lane < 0) {
                    lanes += null
                    lane = lanes.lastIndex
                }
            }

            val edges = mutableListOf<GraphEdge>()
            shifts.forEach { (from, to) -> edges += GraphEdge(from, to, fromNode = false, toNode = false) }
            // 穿过本行的线：别的泳道在等别的提交
            lanes.indices.filter { it != lane && lanes[it] != null }.forEach { l ->
                edges += GraphEdge(fromLane = l, toLane = l, fromNode = false, toNode = false)
            }

            // 2) 父提交落位：第一父继承本泳道（除非已有泳道在等它，那就并过去）；额外父各占一条
            val parentLanes = mutableListOf<Int>()
            commit.parents.forEachIndexed { idx, parent ->
                val waiting = lanes.indexOfFirst { it == parent }
                when {
                    waiting >= 0 -> parentLanes += waiting
                    idx == 0 -> {
                        lanes[lane] = parent
                        parentLanes += lane
                    }
                    else -> {
                        var free = lanes.indexOfFirst { it == null }
                        if (free < 0) {
                            lanes += null
                            free = lanes.lastIndex
                        }
                        lanes[free] = parent
                        parentLanes += free
                    }
                }
            }

            val dangling = commit.parents.isNotEmpty() && commit.parents.none { it in shas }
            // 3) 本泳道的去向：根提交到此为止；没有父接在本泳道上（第一父并去了别处）也要释放
            if (commit.parents.isEmpty() || parentLanes.none { it == lane }) {
                lanes[lane] = null
            }
            parentLanes.forEachIndexed { idx, l ->
                edges += GraphEdge(
                    fromLane = lane,
                    toLane = l,
                    fromNode = true,
                    toNode = false,
                    dangling = dangling && idx == 0,
                )
            }

            while (lanes.isNotEmpty() && lanes.last() == null) lanes.removeAt(lanes.lastIndex)

            rows += GraphRow.Commit(
                GraphCommitRow(
                    commit = commit,
                    lane = lane,
                    laneCount = maxOf(lanes.size, lane + 1, 1),
                    edges = edges,
                    dangling = dangling,
                ),
            )
        }
        return rows
    }

    /**
     * 把泳道列表里的空位去掉（保序），返回**移动过的泳道**：`(旧下标, 新下标)`。
     *
     * 为什么要压实：泳道是「谁在等谁」的列表，空位留在中间只会让后面的线一直往右漂；
     * 又为什么要把位移返回出去：压实发生在两行之间，不把这个位移画成本行的斜线，
     * 那根线就会在行边界上断开（真机上表现为「线画到一半没了」）。
     */
    private fun compact(lanes: MutableList<String?>): List<Pair<Int, Int>> {
        val shifts = mutableListOf<Pair<Int, Int>>()
        var write = 0
        for (read in lanes.indices) {
            val sha = lanes[read] ?: continue
            if (read != write) shifts += read to write
            lanes[write] = sha
            write++
        }
        while (lanes.size > write) lanes.removeAt(lanes.lastIndex)
        return shifts
    }
}
