package com.branchbase.ui.profile

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.ui.graphics.vector.ImageVector
import com.branchbase.R
import com.branchbase.ui.theme.TintRole

/**
 * 仓库「语言」筛选的**大类**。
 *
 * ## 为什么要分大类
 *
 * 1.4.88 之前筛选条是 `全部 / Kotlin / Shell / Python / C++` 五个平铺胶囊，一次占掉一整行高度；
 * 而这五个只是**语言**这一个维度，页面上却和搜索框平级。现在筛选收进抽屉，抽屉里先用一枚
 * 分段控件选「大类」，再在下面选具体语言 —— 语言多起来（这个账号的仓库里就有 Java / Kotlin /
 * Shell / Python / C++）时不会把抽屉堆成一张语言清单，且「应用 / 脚本 / 底层」这三档本身就
 * 回答了「我想找哪一类项目」。
 *
 * ## 颜色为什么正好是「语义三色」
 *
 * 三档各占一个语义角色，与首页区块图标同一套（`docs/specs/prototypes/home-tint-chips.md` 拍板）：
 * 应用 = [TintRole.ACCENT] 蓝、脚本 = [TintRole.WARNING] 橙、底层 = [TintRole.DONE] 紫。
 * 颜色在这里承担的是**分类编码**（三档一眼分得开），不是装饰 —— 所以不换主题、不跟选中态漂移：
 * 选中态由文字色与 `✓` 表达，图标始终说「我属于哪一类」。
 *
 * 数量刻意只有三档：四档以上在 360dp 宽的分段控件里每段只剩 ~66dp，中文两字还行，
 * 英文（Apps / Scripts / Systems）就要截断了；而超过三档也就读不成「目录」了。
 */
internal enum class RepoLangClass(
    @StringRes val labelRes: Int,
    val role: TintRole,
    val icon: ImageVector,
    val languages: List<String>,
) {
    /** 应用与 JVM：客户端 / 服务端应用代码。 */
    APP(
        R.string.label_lang_class_app,
        TintRole.ACCENT,
        Icons.Filled.Code,
        listOf("Kotlin", "Java", "Swift", "Dart", "TypeScript", "JavaScript"),
    ),

    /** 脚本与数据：自动化、胶水层、数据处理。 */
    SCRIPT(
        R.string.label_lang_class_script,
        TintRole.WARNING,
        Icons.Filled.Terminal,
        listOf("Shell", "Python", "Ruby", "Lua", "PHP", "Perl"),
    ),

    /** 系统与底层：原生、系统工具、命令行程序。 */
    SYSTEM(
        R.string.label_lang_class_system,
        TintRole.DONE,
        Icons.Filled.Memory,
        listOf("C++", "C", "Rust", "Go", "Zig", "Assembly"),
    ),
}

/**
 * 旧筛选条固定列出的四种语言：只在**一个语言都读不到**时兜底。
 *
 * `RepoItem.language` 来自 GitHub 的 `language` 字段，它是可空的（仓库没有可识别的主语言时是 null）。
 * 如果列表里恰好一条都没有语言，抽屉就是一张空面板 —— 所以这时退回旧筛选条的四个，
 * 至少把「筛选这件事」摆出来，而不是让用户以为抽屉坏了。有数据时**不**列它们：
 * 一个 0 条的筛选项目标就是「点了没结果」，那是噪声。
 */
private val LegacyRepoLanguages = listOf("Kotlin", "Shell", "Python", "C++")

/** 语言属于哪个大类；不在三档里的语言返回 null（抽屉里按「其他」处理，仍然可筛）。 */
internal fun langClassOf(language: String): RepoLangClass? =
    RepoLangClass.entries.firstOrNull { language in it.languages }

/** 每种语言各有多少仓库（`null` 语言不计）。抽屉里的行尾数字就是它。 */
internal fun repoLanguageCounts(repos: List<RepoItem>): Map<String, Int> =
    repos.mapNotNull { it.language }.groupingBy { it }.eachCount()

/**
 * 抽屉里要列出的语言，按「大类顺序 → 大类内声明顺序」排，末尾接**未归类**的语言（按字母序）。
 *
 * 顺序不按数量排：数量会随仓库变，排序跟着变的话，同一个语言每次打开抽屉都在换位置，
 * 用户在抽屉里建立的肌肉记忆就没了。语言是**稳定集合**，顺序也该稳定。
 *
 * 未知语言（Dart / Vue / HTML…）排在末尾而不是丢掉：丢掉的话这些仓库永远筛不出来。
 */
internal fun filterableLanguages(repos: List<RepoItem>): List<String> {
    val present = repoLanguageCounts(repos).keys
    if (present.isEmpty()) return LegacyRepoLanguages
    val classified = RepoLangClass.entries.flatMap { cls -> cls.languages.filter { it in present } }
    val unclassified = present.filter { langClassOf(it) == null }.sorted()
    return classified + unclassified
}

/**
 * 仓库列表的**本地**过滤：关键词（名称 / 全名 / 描述，大小写不敏感）+ 语言，两个条件同时生效。
 *
 * 为什么是纯函数、且放在 UI 包而不是解析层：这一页的数据是 `/user/repos` 一次取回的
 * 30~100 条，过滤是**展示问题**（搜索框改一个字就要重算），所以不联网、不缓存、不动 `repos` 本身 ——
 * 清掉关键词必须能原样回到完整列表。抽成纯函数是为了让 [RepoFilterTest] 逐条钉住
 * 「空关键词 = 不筛」「大小写」「描述也算」「两个条件叠加」这四条，而不需要跑 UI。
 */
internal fun filterRepos(repos: List<RepoItem>, query: String, language: String?): List<RepoItem> {
    val keyword = query.trim()
    if (keyword.isEmpty() && language == null) return repos
    return repos.filter { repo ->
        (language == null || repo.language == language) &&
            (
                keyword.isEmpty() ||
                    repo.name.contains(keyword, ignoreCase = true) ||
                    repo.fullName.contains(keyword, ignoreCase = true) ||
                    repo.description?.contains(keyword, ignoreCase = true) == true
                )
    }
}
