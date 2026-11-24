# 界面语言（i18n）：体系、范式与后续

> 这份文档记录**已落地的体系**、**已确立的范式**、**刻意保留不译的**，以及**剩余工作怎么接着做**。
> 配套工具在 `tools/i18n/`，审计产物在 `i18n-audit/`（未跟踪）。
>
> **路径写法**：Kotlin 文件名省略 `app/src/main/java/com/branchbase/` 前缀 ——
> 文中 `ui/settings/AppLanguage.kt` 即 `app/src/main/java/com/branchbase/ui/settings/AppLanguage.kt`；
> 以 `app/`、`core/`、`tools/`、`downloader/` 等开头的则是仓库根相对路径。

## 一、现状

| 指标 | 数值 |
|---|---|
| 支持语言 | **2** —— 简体中文（默认，`zh-Hans`）+ 英文（`values-en/`） |
| `:app` 资源条目 | **1384**；`values-en` 1383 / 1383 · **覆盖率 100%** |
| `:downloader` | 27 / 27 · 100%（模块自带资源） |
| `:imageviewer` | 6 / 6 · 100%（模块自带资源） |
| 生产代码剩余硬编码中文 | **923** 条（`:app` 644 · `:core` Rust 243 · `:translate` 27 · `:downloader` 8 · `:joblogs` 1） |
| 其中日志与设备信息 | **206** —— 按约定保持中文，**不计入**待翻译量 |
| 单测 | `:app` + `:downloader` **835 条**全绿 |

三点容易读错的地方：

- `:app` 的两种语言条目数差 1（1384 vs 1383）不是漏译 —— `app_name` 是品牌名，标了 `translatable="false"`，
  按约定不出现在译文里。
- **`:imageviewer` 的生产代码硬编码已清零**。扫描器在该模块仍报 3 条，那是测试文件的**中文方法名**
  （反引号函数名），不是字面量，不参与翻译。
- 剩余 923 条里 `category=log` 的 206 条**不是欠账**：日志进导出的日志包，是排障产物，
  翻译它反而会让 `tools/perf/frame-baseline.py` 的正则失配。
- **2026-11-25 起（1.4.17）**：Kotlin 侧的引擎错误文案已收口到 `core/EngineError.kt`
  （解析 + 渲染边界，见 §14），那一批字面量不再算欠账；`:core` 的 243 条要等 Rust 换码。

## 二、语言体系（怎么加一种语言）

1. **语言清单不在 App 里**。`app/build.gradle.kts` 开着 `androidResources.generateLocaleConfig`，
   AGP 按 `app/src/main/res/values-*/` 目录生成 locale config，并挂到 `android:localeConfig`。
   APK 里生成物的实际路径是 `res/xml/_generated_res_locale_config.xml`（名字由 AGP 定，
   不要手写同名文件去覆盖它 —— manifest 里也不写这一条，避免手写清单与资源目录分家）。
   本包当前生成的内容是 `defaultLocale="zh-Hans"` 加两条 `locale`（`zh-Hans` / `en`）。
   默认那份**是哪种语言**由 `app/src/main/res/resources.properties` 的 `unqualifiedResLocale=zh-Hans`
   声明 —— 缺了它 `:app:extractDebugSupportedLocales` 会直接失败（它只能从目录名看出「有哪些语言」，
   看不出默认那份是什么语言）。
   **加一种语言 ＝ 加一个 `values-xx/` 目录**，语言页自动多一个选项，不改任何列表常量。

2. **语言存系统里，App 不存**。`ui/settings/AppLanguage.kt` 是 `LocaleManager`（API 33+）的薄封装 ——
   没有任何 prefs 键、没有 DataStore、没有内存里的语言状态，每次现读。
   收益不是省几行代码，而是**消灭一整类闪烁**：不存在「异步读出来才知道该用哪种语言」，
   也就不存在「首帧先渲染中文再跳英文」的窗口。

3. **切换不重建 Activity**。`AndroidManifest.xml` 里 `MainActivity` 声明了
   `configChanges="locale|layoutDirection"`：框架把新 Configuration 应用到 Activity 的 Resources，
   Compose 侧 `LocalConfiguration` 跟着更新、`stringResource` 自动取到新文案 ——
   导航记忆、各页取数状态、WebView 滚动位置都不会丢。

4. **语言页**：设置 → 外观 → 语言（`ui/settings/LanguageScreen.kt`）。
   仅 **API 33+ 且清单里至少两种语言**时出现 —— 低于 33 没有 `LocaleManager`，
   整行**不出现**而不是置灰（禁用行必须给「怎么才能开」的出路，而这里的出路是换台新手机，不属于设置页能代办的事）。
   语言行**名称用母语自称**（`English` 而不是「英语」）：用户在看不懂当前界面语言时也必须能认出自己那一行；
   **说明用当前界面语言的他称**（界面是中文时，`English` 那行写「英语」），两者相同时不给说明。
   第 2 条同时是**翻译完成度的门控**：`values-en/` 不存在时清单里只有默认语言，入口自动隐藏。

5. **CI 校验**：`tools/i18n/check-i18n.py --min-coverage 100` 跑在 `build-beta.yml` / `build-release.yml`
   的**环境准备之前**（只解析 XML，几毫秒）。

## 三、校验器查什么（`check-i18n.py`）

| 检查 | 为什么 |
|---|---|
| 结构与覆盖率 | 每种语言缺多少条，阈值 `--min-coverage`（CI 用 100：不允许发半成品翻译） |
| 占位符一致性 + **格式串完整性** | 只比占位符集合查不出 `%1%1$s` 这种写坏的串 |
| `translatable` 一致性 | 品牌名、格式串不许有译文 |
| **跨模块语言子集** | 库模块的 `values-xx/` 必须是 `:app` 的子集 —— `localeConfig` 按 `:app` 的 res 生成，只在库模块加语言会「通知是德文、界面是中文、语言列表里还没有德语」 |
| **复数不变量** | `<plurals>` 必须有 `other`，且各 quantity 的占位符必须一致 |

## 四、工具链

```bash
# 1) 看某个文件里还有哪些可抽的字面量（C = 在 @Composable 内，- = 不在）
python3 tools/i18n/extract.py --file <文件> --list

# 2) 往 tools/i18n/strings.tsv 追加翻译行（三列：原文<TAB>英文<TAB>资源名）
#    原文必须与源码**逐字一致**（含 \n 这类转义）

# 3) 空跑 → 落盘（同时写 values/ 与 values-en/ 的 strings.xml）
python3 tools/i18n/extract.py --file <文件>
python3 tools/i18n/extract.py --file <文件> --apply

# 4) 编译 → 按报错自动降级（非组合 lambda 里 context.getString、缺 Context 时补声明）→ 再编译
python3 tools/i18n/fix_composable.py

# 5) 校验
python3 tools/i18n/check-i18n.py
```

**`--list` 是最常用的入口**：它按文件列出候选，用序号编翻译表可以避免手抄中文出错。

**`extract.py` 只写 `:app` 的资源**（`VALUES` / `VALUES_EN` 两个常量写死）。库模块
（`:downloader` / `:imageviewer`）的资源要手工建 —— 见 §五 的「库模块资源命名」。

## 五、已确立的范式

### 5.1 路径 A′：模型带「资源 ID + 参数」

**症状**：数据类 / 枚举直接带中文 `String`，而文件里没有 Context。

```kotlin
// ① 纯标签：只带资源 ID
private enum class ProfileTab(@StringRes val labelRes: Int, val logLabel: String, …) {
    Repositories(R.string.profile_tab_repositories, "仓库", …),   // 日志专用中文名单独一个字段
}
enum class RepoPage(@param:StringRes val labelRes: Int, val logLabel: String) { … }

// ② 带参数的句子：ID + 参数一起带（纯 ID 表达不了参数）
data class TimelineText(@StringRes val res: Int, val args: List<Any> = emptyList())

// ③ 同名 @Composable 包装负责解析 —— 消费者一行都不用改
@Composable
fun stateLabelOf(state: String): String =
    stateLabelResOrNull(state)?.let { stringResource(it) } ?: state
```

要点：

- **`labelRes` 与 `logLabel` 是两个字段，不能合并**：界面文案跟语言走，日志按约定固定中文。
  `RepoPage` / `ProfileTab` 都是这个形状。
- **「未知值原样透出」用 `Int?` 表达**，这样它可单测（`assertNull(...)`）——
  见 `stateLabelResOrNull`，钉子 `StateLabelTest` 3 例。
- **`?.let { }` 是必需的**：`stringResource` 不能出现在普通 lambda 里，而 `let` 是 inline。
- **遇到拼接就整句成资源**：`"$actor 关闭了此 issue$suffix"` 改成两个资源；
  首页活动流的 `"$actor $verb $repoName"` 也改成了整句（六条 `home_activity_*`）——
  中文语序拼得出来，英文拼不出来。

### 5.2 `LocalizedText`：一段「待解析」的文案

`ui/LocalizedText.kt` 是模型层与渲染层之间的载体，三个能力：

| 能力 | 用法 | 为什么需要 |
|---|---|---|
| 资源 ID + 参数 | `LocalizedText(R.string.timeline_renamed, listOf(actor, from, to))` | 模型不认识 Context，但字段要显示 |
| 参数**可嵌套** `LocalizedText` | `LocalizedText(R.string.last_used_at, listOf(shortTime(iso)))` | 「把一个 helper 的结果拼进句子」在模型里很常见；嵌套后只解析一次，语序由资源的 `%1$s` 决定 |
| `raw` 原样透出 | `LocalizedText(raw = type.removeSuffix("Event"))` | 后端新增的类型不能被吞掉 |
| **复数**（`quantity`） | `LocalizedText.plural(R.plurals.relative_minutes, 5, listOf(5L))` | 中文只有 `other`，英文要分 `one` / `other`（`1 minute ago` / `2 minutes ago`） |

> ⚠️ **复数的构造只能走 `LocalizedText.plural(...)`**，不要直接传 `quantity`。
> `res` 的类型是 `Int`，编译器和 lint 都分不出它装的是 `<string>` 还是 `<plurals>`；
> 直接构造的话传错要到运行时才炸（`getQuantityString` 拿到 `<string>` 抛
> `Resources$NotFoundException`）。工厂入口的 `@PluralsRes` 是唯一的把关点。

渲染侧两种解析方式：

```kotlin
Text(entry.text.resolve(context))    // 已经拿着 Context
Text(shortTime(iso).resolve())       // @Composable 扩展（ui/LocalizedTextCompose.kt），省掉一行 context
```

`LocalizedText.kt` 本体**不依赖 Compose**（`resolve(context)` 只认 `android.content.Context`），
`@Composable` 的便利扩展单独放在 `LocalizedTextCompose.kt` —— 这样模型类型才能留在纯 JVM 单测里被断言
（`ActivityFeedTest` 的 `assertDetail` 断言 `res` + `args`）。

### 5.3 `Feedback`：语气由产生方给出，不从文案里猜

`ui/repository/Feedback.kt` 是 `data class Feedback(val text: String, val ok: Boolean)`。

**猜的写法有两种，都静默错色、都不崩**：

```kotlin
color = if (msg.contains("失败")) …   // 文案抽成资源、界面切英文后永不成立 → 失败被渲染成绿色
color = if (msg.startsWith("已")) …   // 同上；而且「默认分支已改为 …」首字是「默」，中文下就已经错色
```

判据必须来自产生方（它手上就有 `err == null` 这个事实）。

### 5.4 路径 C′：纯逻辑函数返回「枚举 / 资源 ID」，不返回文案

`LogExporter` / `SigningVerify` / `RustBridge` 这类纯函数返回值会显示给用户，但文件里没有 Context。
做法是先把「分类」抽成稳定枚举（照 `DownloadErrorCode` / `DownloadFailure` 抄），
分类逻辑留在纯函数里可测，**文案在调用方**（本来就是 composable）解析。

`downloader` 的 `DownloadFailure(code, args)` + `resolve(context)` 扩展是这条路线的样板：
映射表与参数顺序都收在模块内，App 侧只调 `resolve`。

### 5.5 库模块的资源要带模块前缀

`:downloader` 用 `downloader_`、`:imageviewer` 用 `imageviewer_`。

不是洁癖：**库模块的资源会与 `:app` 合并**，而 `action_close`、`label_image` 这类通用名
在 `:app` 里已经被占用 —— 同名会被 `:app` 覆盖，值一旦不同就是「改了库模块却不见效」。

## 六、术语约定（中文侧）

### `issue` → **讨论**

> 已与项目方确认：中文侧统一用「讨论」，英文侧保持 `issue`。**不要"顺手改回去"。**

改的是**资源值**，不动资源名（`*_issue*` 的名字保留，改名会连带改所有引用点）。
共 21 条，例如：`state_no_issues`「暂无讨论」/ `repo_page_issues`「讨论」/
`timeline_reopened`「%1$s 重新打开了此讨论」/ `activity_issue_action`「%1$s讨论 #%2$d」。

**刻意不改的两处**：

1. **`login_key_scopes_bullets` 里的 `Issues`** —— 那是 GitHub 细粒度 token 界面上真实存在的
   **权限名**，换成中文用户就找不到该勾哪一项。
2. **`type:issue`**（`SearchQueryTest` 钉的）—— 那是 **GitHub 搜索语法**，不是给人看的文案。

### 另一类：逻辑判别值照旧不动

`when (state)` 这类匹配的是 **GitHub API 返回值**（`open` / `closed` / `merged` / `draft`…），
与界面语言无关，永远保持英文原样 —— 换成中文会当场失效。

## 七、刻意保留不译的

| 内容 | 为什么 |
|---|---|
| `FrameWatch` 的 8 个帧阶段名（等待/输入/动画/布局/绘制/上传/下发/交换） | `tools/perf/frame-baseline.py` 把这份清单**写死在工具里**，翻译即打断性能报表 |
| `Logging` / `DeviceProfile` 的日志类别与设备信息 | 进的是导出的日志包，是排障产物 |
| `LogExporter.buildLogReport` 的 `report.md` 正文（约 32 条） | 与上一条同理：索引里引用的类别名（UI / 网络 / 本地）与锚点词（慢帧 / 启动）本身就是固定中文，翻译会让同一份包对不上锚点词典、grep 示例失效。**要跟界面语言走的只有 `Result.Failed` 的 9 条失败提示**（1.4.5 已资源化） |
| 缓存键常量（`PageCache.TYPE_*` 等） | 落库标识，改了旧行成孤儿 |
| `ProfileTab.logLabel` / `RepoPage.logLabel` / `SubPage.label` | **日志**专用的中文名，与界面文案是两个字段 |
| `TranslateEngine.classify` 里的中文匹配 | 匹配的是 **Rust 返回的错误文案**，等 Rust 侧结构化错误方案定了再动 |
| GitHub 权限名 / 搜索语法 / API 判别值 | 见 §六 |

## 八、三条纪律（抽取时一直守着）

1. **位置参数用 `%1$s`，不用裸 `%s`** —— 语序不同的语言里译者要能调换参数位置。
   `check-i18n.py` 会报裸占位符。
2. **带计数的文案用 `<plurals>`** —— 中文只有 `other`，英文要 `one` + `other`。
   调用点用 `pluralStringResource(R.plurals.x, n, n)`；模型层用 `LocalizedText.plural(...)`。
   注意 `count` 参数是 `Int`，`Long` 要 `.toInt()`。
3. **品牌名与格式串标 `translatable="false"`** —— 只放在默认语言里，翻译文件里不重复。

## 九、踩过的坑（都是真事）

| 坑 | 现象 | 教训 |
|---|---|---|
| TSV 里以 `#` 开头的字面量被当注释 | 表里写了、工具说没匹配上，**无报错** | 注释判定不能只看行首 `#`；改成「恰好三列即数据」 |
| 资源文件被算进「硬编码」 | 抽一条搬一条，净变化 0，指标永远不降 | 审计要排除 `res/values*/strings.xml` |
| `to_format` 对译文又跑一遍 | 写出 `%1%1$s`，运行时 `String.format` 抛异常 | 先转义字面 `%`，再做模板替换 |
| 校验器里 `for name, …` 遮蔽外层 `name` | `name == 'app'` 判断静默失灵，报出假的语言不一致 | 内层循环变量别用 `name` |
| 源码级测试钉字面量 | 每抽取一次假红一次 | 钉**语义分支 / 资源 ID / 资源值**，不钉会被搬走的字面量（`NotificationModelsTest` 已从 `assertEquals("5 分钟前", …)` 改成断言 `res` + `quantity` + `args`） |
| `remember { mutableStateOf(字符串) }` | 语言切换后停在旧语言 | 跨组合存活的状态应存资源 ID + 参数，渲染时才解析 |
| 用文案猜语气（`contains("失败")` / `startsWith("已")`） | 切英文后成功提示渲染成红色，**不崩不报错** | 语气由产生方给出，见 `Feedback` |
| 把 `issue` 一刀切改成中文 | GitHub 权限名 / 搜索语法当场失效 | 改词前先认专有名词，见 §六 |
| 注释里写 `res/values*/strings.xml` | 其中的 `*/` **提前结束块注释**，编译报「Expecting a top level declaration」 | KDoc 里别出现 `*/` |

## 十、验收清单

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :downloader:testDebugUnitTest --offline
python3 tools/i18n/check-i18n.py                                # i18n 校验
# APK 内确认（应看到 localeConfig 与 configChanges=0x2004，以及 plurals 资源）
aapt2 dump xmltree --file AndroidManifest.xml <apk> | grep -E 'configChanges|localeConfig'
# 抽查译文确实分侧（默认列是中文、en 列是英文）
aapt2 dump resources <apk> | grep -A 2 'string/repo_page_issues'
```

## 十一、剩余工作

按「能不能自动抽」分三档：

| 档 | 量 | 说明 |
|---|---|---|
| ✅ 作用域允许、可自动抽取 | **0** | 已抽干净 —— `extract.py` 全量空跑「可替换 0」 |
| ❌ 需接口改造 | **717** | 不在 `@Composable` 内、作用域也拿不到 `Context`；走 §5.1 A′ 或 §5.4 C′ |
| ⛔ 按约定不译 | **206** | 日志与设备信息，见 §七 |

> 两个数字口径不同，别混：
> **923** 是扫描器统计的**全部**中文字面量（表内 + 表外）；
> `extract.py` 只对**翻译表里已有的条目**做判定，它那次空跑报的是「跳过 86 处」
> ＝ 84 处不在 `@Composable` 函数体内 ＋ 2 处日志文案。表外那 ~500 条它根本不会看见。

改造的入口按「一条链能带出多少」排序：

1. **`:core`（Rust）243 条** —— 最大的一块，集中在 `core/src/git/mod.rs` 与 `core/src/translate/deepseek.rs`。
   **方案已定并落地了 Kotlin 侧**（§14：`ERROR:<CODE>[:detail]` + 解析/渲染边界，1.4.17）；
   剩下的是 Rust 自己逐模块换码，Kotlin 侧不用再改。
2. ~~`RustBridge` 的 `"引擎不可用"`~~ —— **1.4.17 已改稳定码** `ERROR:ENGINE_UNAVAILABLE`，
   上屏文案由 `engineErrorText` 在提示边界解析。
3. ~~`LogExporter`(41) / `SigningVerify`(14)~~ —— 分别在 1.4.5 / 1.4.3 处理（C′）。
4. **其余作用域受限项** —— 逐个走 A′/C′（含 `RepoAccessHint` 一类「结论型」中文，1.4.17 只做了仓库访问那 4 条）。

> **补翻译表不是这些条目的出路**：`extract.py` 的两个跳过条件是「表里没有」与「作用域不行」，
> 前者靠补表解决，后者**补表一条也推不动**。动手前先跑一次
> `python3 tools/i18n/extract.py --file <文件>` 空跑，看它报的是哪一种。

---

## 十二、2026-09-24 追记：英文模式下仍然漏出来的那几处

起因是一张**英文界面**的真机截图：设置页里 Theme / Language / Commit mode 都已英文，
只有下面这些还是中文。它们有一个共同点 —— **都不在 `@Composable` 函数体里**，
所以 `extract.py` 一条也抽不到（它只动组合体内的字面量），只能按 §5.1 路径 A′ 手工改。

| 漏出来的位置 | 当时的写法 | 现在 |
|---|---|---|
| 外观 → 主题三档 | `ThemeMode.SYSTEM("system", "跟随系统")` | `@StringRes labelRes`（`theme_mode_*`），调用方 `stringResource` |
| 代码与提交 → 提交模式（状态位 / 卡片标题 / 说明） | `CommitMode.SINGLE_FILE("单个文件", …)` | `labelRes / titleRes / descRes` + `logLabel`（日志专用中文） |
| 通知 → 状态与说明 | `SystemNotificationState.label = "已开启"` | `labelRes / hintRes / actionRes`（`@get:StringRes`） |
| 通知 → 本地仓库那一行的按钮 | `DisabledNavRow(fixLabel = "去设置")` 参数默认值 | 参数改 `String?`，`null` → `action_open_settings` |
| 底部导航（含侧边 / 玻璃两套变体） | `NavDestination.Home("首页")` | `@StringRes labelRes`（`nav_home` / 复用 `nav_messages`） |
| 危险确认卡标题 | `DangerConfirmCard(title = "二次确认 · 不可恢复")` 参数默认值（8 个调用点全不传） | `String?` + `confirm_default_title` |
| 应用图标无障碍名 | `AppIcon(contentDescription = "应用图标")` 参数默认值（4 个调用点全不传） | `String?` + `label_app_icon` |
| 双击返回退出 | `const val EXIT_CONFIRM_HINT = "再按一次返回退出应用"` | `context.getString(hint_press_back_again)`（Toast 里现取，不缓存） |
| 账号状态 / 认证方式 / 发布变体 / 任务状态与筛选 / 时间线筛选 / 发版三档 / Watch 说明 / 复刻被拒原因 | 各枚举的中文构造参数、`forkErrorText` 的中文返回值 | 全部 `@StringRes`；**未知值原样透出**改用 `LocalizedText(raw = …)`（`forkErrorText`） |

规矩没变，两处必须记住：

- **`labelRes` 与 `logLabel` 是两个字段**：界面跟语言走，日志按约定固定中文（§7）。
  `CommitMode` / `AccountStatus` 这次都补了 `logLabel`，它们的日志调用点用后者。
- **参数默认值里的中文字面量是最隐蔽的一类**：调用点不传就必然上屏，
  而 `extract.py` 看不见、编译器也不报。默认值改成 `null` + 函数体内 `stringResource`。

### 钉子：`I18nUiTextTest`

新测试 `app/src/test/java/com/branchbase/ui/I18nUiTextTest.kt` 钉三件事：

1. **已资源化的界面文件里不许再有中文**（注释先剔除；`logLabel = "…"` 是唯一例外）。
   清单是一个一个加的文件名 —— 加进去就等于承诺「这个文件的用户可见文案已经全部资源化」；
2. `CommitMode` 的三档里不许再出现写死的中文（状态位 / 卡片 / 说明三处共用同一份）；
3. `values-en/strings.xml` 的**条目值**里不许有中文（注释除外），
   且这一批新键在中英两边都存在、值不同（漏译 / 复制中文后忘改）。

### 还剩下什么（怎么查）

这一轮清的是「真机截图里已经露出来的 + 底部导航 + 设置树可达的页面」。**剩余量仍然很大**，
口径见 §11；按类别看的当前快照（`python3 i18n-audit/scan_hardcoded_cjk.py`）：

- `category=ui` **75** 条、`category=error` **36** 条 —— 这两类才是「用户会看见的」；
- 其余是 `test`(1166) / `design`(2789) / `log`(206) / `tooling`(308)，不算欠账。

下一批的入口（按「一处改动覆盖多少调用点」排）：

1. **`LoginScreens.kt` 的 OAuth / PAT 引导页 19 条** + `LoginViewModel` 的错误提示 7 条 —— 登录是首次启动路径；
2. **搜索**（`SearchQuery` / `SearchScreen` / `SearchViewModel` 共 ~40 条）；
3. **`SigningVerify.verifyCopy`** —— **1.4.3 已按 §5.4 C′ 改完**：`VerifyCopy` 现在只带
   `LocalizedText`（chip / detail 各一条），指纹作为嵌套参数；`fingerprintShort` 读不到时
   也只返回资源。同类小项（`LocalRepoGitState.summary`、`CompareFile.statusLabel`、
   `SyncState.labelRes`）同批处理；
4. **`LocalBranchSyncModels` / `RepoActions` / `GitProxy` 校验提示** 等纯逻辑里的提示。

> 动手前照旧先空跑一次：`python3 tools/i18n/extract.py --file <文件>` ——
> 它报「表里没有」就补 `tools/i18n/strings.tsv`，报「作用域不行」就得按 A′/C′ 改结构。

## 十三、2026-11-24 第二批：搜索 / 决策页 / 仓库列表

这一批清的是 `review/progress-2026-11-24.md` 列出的剩余界面文件。按类别重扫后
（日志、`logLabel`、测试方法名都不算欠账）真正要改的是四块：

| 位置 | 处理 |
|---|---|
| `ui/search/SearchScreen.kt` | 排序四档、高级筛选十一项、仓库 meta → 资源；`SearchItem.meta` 改 `LocalizedText?` |
| `ui/decision/CollabScreens.kt` | 分支名 / 提交信息校验、合并状态、仓库统计 → `LocalizedText` + 五个 `<plurals>` |
| `ui/decision/SyncDecisionScreens.kt` | 四个「选项被拦下」的预检、失败文案、revert 标题 → `LocalizedText` |
| `ui/repository/RepositoryListScreens.kt`、`RepoActions.kt` | 列表失败文案（嵌套参数）、写入失败提示（产生方解析） |

### 三个这一批长出来的范式

1. **显示名与稳定 key 必须分开（不只是 i18n 洁癖）。**
   搜索页的 `advancedFilters` 原来是 `"所有者" to "user:/org:"`，而 `advancedValues`
   和 `expandedFilter` 都拿这个**中文字面量当 key**。显示名换成资源后，`name` 不能再兼任 key：
   切语言后已填的筛选值会对不上，查询串也会跟着显示文案变。
   现在 key 是 ASCII（`owner` / `stars` / `created`…），显示名走 `advancedFilterLabelRes(key)`。
   **判据：只要一个字面量既上屏又当标识符用，它就不能被翻译。**

2. **返回给用户看的字符串不能再按语言拼。**
   `failureMessage` 原来是 `"${action}失败：$reason"` —— 中文拼得出来，英文是
   `"%1$s failed: %2$s"`，语序不同。这类一律**整句成资源**，参数用 `%1$s`。
   同理，`"GitHub 已把$subject 列表…"` 改成 `error_people_list_restricted` + 把主体作为
   **嵌套 `LocalizedText`** 传进去：主体本身也是资源（星标者 / 复刻列表 / 关注者）。

3. **日志里不许解析界面文案。**
   `SyncDecisionScreens` 的日志原来拿预检原因去 `getString` —— 那会跟着界面语言变成英文，
   而日志按 §7 固定中文。现在日志只记**布尔**（「被拦下 / 可用」）：事实仍然留痕
   （配合同一行的 `hasRemoteRef` / `dirty` / `merging` 已足够定位），但不再混入可翻译文案。
   由此 `state_discard_blocked` 等 7 条日志专用资源失去引用，一并删除。

### 为什么这些文件没进 `I18nUiTextTest.localizedFiles`

那个清单是**「这个文件里彻底不出现中文」的承诺**（唯一例外是含 `logLabel` 的行）。
本批的文件都还剩日志正文 / `logLabel` / `mergeStateLogLabel`，按 §7 保留 ——
为了让它们进白名单而给日志加例外，会让钉子本身失真。
这一批的回归保护放在单测里：`CollabDecisionRulesTest` / `SyncDecisionPrecheckTest` /
`RepoViewerRelationTest` 断言**资源 ID + 参数**，函数一旦退回中文 `String` 就编译不过或断言失败。

> 口径仍以 §11 的分档为准；`717 / 923` 是 2026-11-24 第一批之前的快照，
> 本批之后请以 `tools/i18n/` 的扫描（`category=ui` / `error`）为准，不要引用旧数字。

## 十四、Rust 结构化错误：稳定码与本地化边界

> **状态（2026-11-25 / 1.4.26）**：整套稳定码体系按**实验性**定稿（见 §16：未做真机验收，
> 双读永久保留、回退路径写在那一节）。以下是它的实现现状：
>
> Kotlin 侧两步已落地（解析/渲染边界 + 全部提示点接线），
> Rust 侧从 **`api` 层开始换码**（`HTTP_<status>` / `NETWORK`，1.4.19）；`git` / `auth` / `translate`
> 还是中文文案，靠解析器的双读兜着。下面先写「现在是怎么工作的」，再写「剩下两块怎么接着做」。

### 14.1 现在怎么工作

引擎的失败仍然以 `String` 回到 Kotlin，但**含义被收进了稳定码**：

```text
ERROR:<CODE>[:<detail>]
```

- `CODE` 是全大写 ASCII 的稳定标识（`HTTP_404` / `NETWORK` / `ENGINE_UNAVAILABLE` …）；
- `detail` 是原文（GitHub 的 `message`、libgit2 的英文错误、旧的中文文案），**只进日志与排障，不上屏**；
- 未知 `CODE` **原样透出**，不猜 —— 猜错比不翻更坏（§9「用文案猜语气」）。

Kotlin 侧只有三个入口（`app/src/main/java/com/branchbase/core/EngineError.kt`）：

| 入口 | 用途 |
|---|---|
| `parseEngineError(raw)` | 纯函数：`Coded(code, res, args, detail)` / `Raw(text)`；`null` = 本来没有错误 |
| `engineErrorText(context, raw)` | 提示边界渲染成当前界面语言；`Context.engineErrorOrRaw(raw)` 是「一定有值」的版本 |
| `engineErrorStatus(raw)` | 只回答「HTTP 状态码是多少」，业务判断仍归调用方 |

**迁移期双读**（这是这一版能安全落地的关键）：解析器同时认

- 新码：`ERROR:HTTP_404:…`（也认 `RustBridge.err` 剥过前缀后的 `HTTP_404:…`）；
- 旧文案：`未知错误: HTTP 404 Not Found` / `HTTP 403 Forbidden: …`；
- 旧哨兵：`引擎不可用`（它与新码 `ENGINE_UNAVAILABLE` 落到同一条）。

所以「Rust 还没换码」的窗口里界面不会退回英文原话；Rust 换码后也不需要再改调用方。

### 14.2 提示边界接在哪

一遍接线覆盖所有来源（调用方不需要判断现在是哪一代引擎）：

| 边界 | 位置 |
|---|---|
| 三个 toast 出口 | 仓库页 / issue 页 / 消息页各自的局部 `toast()` —— 全部动作反馈都从这里出 |
| 列表失败态 | `RepositoryListScreens.ListError`（`message` 上屏时解析，日志与 `onAccessDenied` 仍拿原文） |
| 任务与反馈行 | `Feedback(...)` 的失败原因、`TaskStore.fail(...)` 的原因、`failureMessage(action, reason)` |
| 决策 / 冲突页 | PR 一条龙、同步回退、分支同步、合并冲突（占我方的 / 手工解决 / 提交合并 / 放弃） |
| 发布 / 工作流 | 删除 release、触发工作流（后者的 422 提示同时改判 `engineErrorStatus`） |

**状态码解析只留一处**：`friendlySearchError`、`repoAccessHint`、`peopleListErrorText`、
`AccountStore.statusFromResponse`、`RepoActions`（复刻关系 / 仓库是否存在）、`BranchSyncScreen`
都改用 `engineErrorStatus`。这不是洁癖 —— 引擎换成 `HTTP_401` 之后，原来那些
`contains("401")` / `Regex("HTTP (\d{3})")` 会**静默判错**：账号健康检查会把「令牌失效」当成
「无法连接」，用户拿到的出路是错的。

### 14.3 为什么不能直接翻译 Rust 文案

- Rust 里很多错误串同时进**日志**（§7 固定中文）与**界面**，两边口径不同；
- 同一句 `ERROR:` 里混着 GitHub 原话（如 422 的 `Validation Failed`），翻译会吞掉可行动信息；
- `tools/perf` 与若干测试按中文锚点匹配 Rust 输出，翻文案会连带打断报表。

所以 Rust 侧**不改现有文案**，只加码：`detail` 保留原文，日志锚点不动。

### 14.4 剩下两块（下一版起，按序）

1. ~~**`api` 层换码**~~ —— **1.4.19 已完成**：`CoreError::Coded { code, message }`，
   `api/client.rs`（12 处）+ `api/web.rs`（2 处）→ `HTTP_<status>`；`reqwest` 传输失败 → `NETWORK`；
   JNI 输出 `ERROR:<CODE>:<message>`（换行压平）。`message` 仍是旧文案，日志锚点不动。
   Kotlin 侧零改动（双读已经在了）。
2. **`git` 层换码**（最大一块，243 条中文）—— **1.4.20 做了第一批**：
   - 已定的码（19 个）：`NFF` / `AUTH_FAILED` / `LOCKED` / `NOT_GIT_REPO` / `REPO_OPEN_FAILED` /
     `CLONE_TARGET_NOT_EMPTY` / `DIRTY_WORKTREE` / `CHECKOUT_BLOCKED` / `BRANCH_EXISTS` /
     `BRANCH_NOT_FOUND` / `CURRENT_BRANCH` / `MERGE_IN_PROGRESS` / `NO_MERGE_IN_PROGRESS` /
     `CONFLICTS_REMAIN` / `NO_PARENT_COMMIT` / `REMOTE_REF_MISSING` / `SHALLOW_CLONE` /
     `NO_COMMON_ANCESTOR` / `NOT_IN_CONFLICT`；
   - 认法：一条 `classify_git_error`（libgit2 英文 → 认证 / 锁 / 不是仓库 / 检出被挡）＋每条
     `pub fn` 在**出错那一行**贴码（`git_error(e, "CODE", "旧文案前缀")`）；认不出的仍按旧中文透出；
   - **`nff:` 前缀保留在 message 里**（日志锚点），但判定改看码 `ERROR:NFF` —— 顺手修掉了
     「`startsWith("ERROR:nff")` 从来没匹配上」的老 bug（见 `local-git-engine-design.md` §4.0）；
   - 剩下的是**内部失败**（读索引 / 写树 / 签名 / revwalk …）：**不归通用码**，
     按原因补成 7 个场景码 —— 方案与组合规则见 **§15**（1.4.21 定稿，未实施）；
3. ~~**`auth` / `translate` 换码**~~ —— **1.4.24 已完成**：
   - OAuth 五种原因码（`AUTH_CLIENT_CREDENTIALS` / `AUTH_BAD_CODE` / `AUTH_REDIRECT_MISMATCH` /
     `AUTH_GITHUB_ERROR` / `AUTH_REFRESH_FAILED`）＋拉取用户的 `AUTH_FAILED`；
     `classifyOAuthFailure` 码优先、正文兜底；
   - 翻译七种类别码（`TRANSLATE_AUTH` / `_QUOTA` / `_NETWORK` / `_UNSUPPORTED` / `_EMPTY` /
     `_QUERY_LIMIT` / `_UNKNOWN`）；`:translate` 的 `classify` 码优先，`RustTranslateEngine`
     把码从**上屏文案**里剥掉。

~~收尾：删掉解析器里的旧文案双读分支~~ —— **取消**（1.4.26）。它现在是这套**实验性**体系的
安全网：码认错 / 未映射 / 旧 `.so` 混用时，旧文案照常工作。见 §16。

（`RustBridge` 里已无引用的中文兜底可以删，但 `EngineError` 的双读分支保留。）

---

## 十五、git 内部失败：语义、场景与组合（1.4.22 已实施）

> 1.4.20 的 19 个码只覆盖「前置条件 / 用户能行动」的场景。写路径里还有约 93 处 `map_err`
> （读索引、写树、构造签名、revwalk、找对象…）仍是 libgit2 英文原文套一句中文前缀。
> 这一节回答「它们要不要归一个通用码」—— 结论是**不归通用码**，而是把语义拆成三个维度、
> 补齐 7 个「有出路」的场景，并给出多条原因同时命中时的取舍规则。
>
> **状态：1.4.22 已实施**（7 个码 + 三条前置修复 + 归类规则；剩「不该发生」类仍保留原文）。

### 15.1 三个维度，别混成一个码

| 维度 | 内容 | 谁提供 | 进码吗 |
|---|---|---|---|
| **场景** | 用户当时在做什么（推送 / 拉取 / 提交 / 切换 / 合并…） | 调用方的文案（「推送 %s 失败：」） | ❌ 不进 |
| **对象** | 本地仓库的哪一块：索引 / 引用 / 对象库 / 工作区 / 签名配置 / 合并状态 / 远端 | Rust 侧（日志与句子主语） | ❌ 不进（见下） |
| **原因** | 为什么失败、用户能做什么 | Rust 侧的归类 | ✅ **码只编码这个** |

为什么对象不进码：`INDEX` / `TREE` / `REF` / `ODB` 对应的**用户行动完全一样**（腾空间 / 查权限 / 重新拉取），
把它们做成码只会得到一堆「同义不同名」的文案。对象留在日志里，用于事后定位：

```
Logger.warn(LOCAL_TASK, tag, "推送 main 失败 ← [REPO_WRITE_FAILED/INDEX] failed to write index")
```

### 15.2 场景目录（已有的 19 个 + 这一批补 7 个）

先列**新增的 7 个**（它们今天都藏在一句内部失败里）：

| 码 | 典型 libgit2 原文 | 用户看到的 | 出路 | 今天藏在哪句里 |
|---|---|---|---|---|
| `SIGNATURE_MISSING` | `config value 'user.name' was not found` | 还没填提交人姓名或邮箱 | 设置 → 代码与提交 → 提交身份 | `构造作者签名失败: …` |
| `UNMERGED_INDEX` | `cannot commit because there are unmerged entries` / `cannot create a tree from a not fully merged index` | 还有没解决的冲突文件 | 先解决冲突再提交 | `写树失败: …` |
| `REMOTE_MISSING` | `remote 'origin' does not exist` / `config value 'remote.origin.url' was not found` | 这个本地仓库还没配远端地址 | 设置 → 本地仓库 → 远端地址 | `找不到 origin: …` |
| `EMPTY_REPO` | `reference 'refs/heads/…' not found`（unborn）/ `the HEAD reference is not a symbolic ref` | 本地仓库还没有任何提交 | 先提交一次 | `读取 HEAD 失败: …` |
| `REPO_WRITE_FAILED` | `failed to write index` / `failed to write tree` / `Permission denied` / `No space left on device` | 本地仓库写不进去：磁盘满了或没有写入权限 | 腾空间 / 查权限后重试 | `写索引失败:` / `写树失败:` |
| `REPO_CORRUPT` | `object not found - no match for id (…)` / `corrupt` / `failed to parse` | 本地仓库的数据缺失或损坏 | 重新拉取（浅克隆先加深历史） | `revwalk …` / `找对象失败: …` |
| `CANCELLED` | 用户按了取消 | 已取消 | 无（正常） | `clone 已取消` |

已有 19 个（1.4.20）继续有效：`NFF` / `AUTH_FAILED` / `LOCKED` / `NOT_GIT_REPO` / `REPO_OPEN_FAILED` /
`CLONE_TARGET_NOT_EMPTY` / `DIRTY_WORKTREE` / `CHECKOUT_BLOCKED` / `BRANCH_EXISTS` / `BRANCH_NOT_FOUND` /
`CURRENT_BRANCH` / `MERGE_IN_PROGRESS` / `NO_MERGE_IN_PROGRESS` / `CONFLICTS_REMAIN` /
`NO_PARENT_COMMIT` / `REMOTE_REF_MISSING` / `SHALLOW_CLONE` / `NO_COMMON_ANCESTOR` / `NOT_IN_CONFLICT`。

### 15.3 组合规则（一个失败只出一个码）

**① 自愈优先。** 能自动修的（浅边界、残留锁、半成品目录）先修再报；`heal_missing_objects` /
`clear_stale_locks` 是既有实践，新场景也遵守这条 —— 用户看到的应该是「修好了 / 修不了」，
而不是「引擎内部曾经出过错」。

**② 多个原因同时命中时，取优先级最高的一条。** libgit2 的一句话里可能同时出现
`authentication` 与 `403`，或者 `lock` 与 `write`。顺序（越靠前越「用户马上能判断」）：

```
CANCELLED
  → AUTH_FAILED
  → LOCKED
  → REMOTE_MISSING
  → NOT_FOUND（分支 / 引用）
  → EMPTY_REPO
  → MERGE_IN_PROGRESS / UNMERGED_INDEX / CONFLICTS_REMAIN
  → DIRTY_WORKTREE / CHECKOUT_BLOCKED
  → SHALLOW_CLONE / NO_COMMON_ANCESTOR
  → SIGNATURE_MISSING
  → REPO_CORRUPT
  → REPO_WRITE_FAILED
  → REPO_OPEN_FAILED
  → （无码：保留原文）
```

**③ 不做「A+B」组合码。** 需要两个信息时用句子组合：**码文案（原因 + 出路）＋ 调用方前缀（场景）**。
例：「**提交**失败」＋「还没填提交人姓名或邮箱，去设置里补一下」。这已经是一句完整的话，
再多一层组合只会得到「提交失败：签名缺失：请填写」这种同义反复。

**④ 回退链：具体原因 → 对象族兜底 → 原文。**

```
认得出具体原因（如 SIGNATURE_MISSING） → 用它
只认得出「写不进本地仓库」             → REPO_WRITE_FAILED
什么都认不出（或消息是纯英文技术串）   → 保留原文（CoreError::Other），不猜
```

**⑤ 参数化只在必要时用。** 像 `DIRTY_WORKTREE` 的「先提交或放弃这些改动」在合并 / 拉取 / 切换
三个场景下都成立，所以**保持中性文案**，不引入「动作」参数。若将来某个码的行动必须随场景变，
再给 `engineErrorText` 加一个可选的 `action: LocalizedText?`，由调用方传
（`LocalizedText(R.string.action_merge)`），而不是在码里塞自由文本。

### 15.4 渲染与日志

- **码文案自足**：不看对象、不看场景也能读通；对象只进日志。
- **detail 不上屏**（与 §14 一致）—— 但**必须落日志**。所以本方案有前置修复（§15.5）。
- **唯一例外**：`REPO_CORRUPT` 可以在文案里带一个**短参数**（缺失对象的 7 位 sha 或冲突文件名），
  因为「哪几个对象坏了」决定了用户是「重新拉取」还是「只补一个对象」；仍然不贴英文整句。
- 日志格式建议统一成：`<场景>失败 ← [<CODE>/<OBJECT>] <libgit2 原文>`，
  这样 grep 一个码就能捞出所有同类事故。

### 15.5 前置修复（1.4.22 已完成）

1. ~~**`gitCommit` / `gitRevert` 把错误丢了**~~ —— 1.4.22 已改成 `GitWriteResult(sha, error)`，
   失败必带原因；三个调用点（仓库列表提交 / 工作台提交 / 文件页提交）与 revert 都把它上屏 + 落日志；
2. ~~**有几个失败路径不落日志**~~ —— 1.4.22 已补：`switchAndPull` / `trackRemote` /
   `SyncDecisionScreens` 的 amend / reset / revert；
3. **clone 的锁文件分支保持原文**（它带现场取证），不归类 —— 1.4.20 定下，1.4.22 未变。

### 15.6 场景 × 操作矩阵（节选）

| 操作 | 正常会遇到的码 | 异常（说明引擎/仓库状态坏了） |
|---|---|---|
| 推送 | `NFF`、`AUTH_FAILED`、`LOCKED`、`REMOTE_MISSING` | `REPO_CORRUPT`、`REPO_WRITE_FAILED` |
| 拉取 | `NFF`、`AUTH_FAILED`、`DIRTY_WORKTREE`、`SHALLOW_CLONE` | `REPO_CORRUPT` |
| 提交 | `SIGNATURE_MISSING`、`UNMERGED_INDEX`、`DIRTY_WORKTREE` | `REPO_WRITE_FAILED`、`EMPTY_REPO` |
| 切换 / 建分支 | `CHECKOUT_BLOCKED`、`BRANCH_EXISTS`、`BRANCH_NOT_FOUND`、`DIRTY_WORKTREE` | `REPO_OPEN_FAILED` |
| 合并 / 冲突 | `MERGE_IN_PROGRESS`、`NO_COMMON_ANCESTOR`、`SHALLOW_CLONE`、`CONFLICTS_REMAIN`、`NOT_IN_CONFLICT` | `REPO_CORRUPT` |
| 撤销 / amend | `NO_PARENT_COMMIT`、`REMOTE_REF_MISSING`、`DIRTY_WORKTREE` | `REPO_WRITE_FAILED` |

「异常」那一列不是给用户看的分类，而是**排障提示**：同一列反复出现，说明那台设备上的仓库状态
或磁盘有问题，日志里的 `[CODE/OBJECT]` 就是入口。

### 15.7 成本与验收

- Rust：约 50 处 `map_err` 贴码（其中大半是同一条规则），`classify_git_error` 增加 5–7 条匹配；
- Kotlin：7 条资源 × 中英 + `CODE_TABLE` 7 行；无需改解析器（码的形状没变）；
- 测试：Rust 归因 5 例（签名缺失 / 未合并索引 / 远端缺失 / 空仓库 / 写失败）+ Kotlin 码表 1 例；
- 验收：真机上制造四个场景 —— 新装未填提交身份、浅克隆上合并、断网推送、只读目录里提交 ——
  确认文案与出路都对。

---

## 十六、稳定码体系的实验性状态（1.4.26 定稿）

### 16.1 为什么定成「实验性」

1.4.17–1.4.24 把「引擎错误的稳定码 + 本地化边界」做完了：传输层 / git / auth / translate 四层都有码，
单测覆盖解析、映射、归类与优先级（`:app` 1161 条 + `:translate` 129 条）。

**但没有做真机逐场景验收。** 验收要「制造一个本地 git 故障 → 看界面那句话对不对」，
而当前 DSHA 的屏幕识别速率太慢（读屏一轮要等，四个场景 × 多次往返），成本高于收益。
所以不宣称「已验收」，按实验性处理。

**影响面只有文案**：码选错不会崩、不会丢数据 —— 最坏情况是用户看到一句不对路的行动建议，
而日志里仍有 libgit2 的原文（`detail` 从头到尾都在）。

### 16.2 已知风险与各自的兜底

| 风险 | 表现 | 兜底 |
|---|---|---|
| 误分类（码选错） | 界面给了不对的行动（把「缺对象」说成「磁盘满」） | `detail` 与日志里有原文；删掉这个码即回退成原文（§16.3 第 1 条） |
| 未映射的新码 | 界面显示 `CODE:原文` | 解析器对未知码就是原样透出（`EngineError.Raw`） |
| 旧 `.so` + 新 APK | 只有旧文案 | 双读保留，照常工作 |
| 文案措辞不满意 | 读起来别扭 / 不够准确 | 只改资源，不动逻辑 |

### 16.3 回退路径（代价从低到高）

1. **单个码错**：从 `EngineError.kt` 的 `CODE_TABLE` 删掉它 → 该码变成未知码 → 界面回退成原文。
   发一版 Kotlin 即可，**不用动 Rust**；
2. **整层错**：把 `engineErrorText` 改成返回 `detail ?: raw`（一处），或让那一层停发码
   （Rust 侧把 `CoreError::coded` 改回 `Other`）；
3. **双读（旧文案）永久保留**：它是这个实验的安全网 —— 原计划的「收尾删除」已取消。

### 16.4 未验收清单（将来条件允许时再补）

| 场景 | 期望的码 |
|---|---|
| 新装、未填提交身份就提交 | `SIGNATURE_MISSING` |
| 浅克隆上合并 | `SHALLOW_CLONE` |
| 断网推送 | 传输失败 → `NETWORK`（或保留原文，取决于失败形态） |
| 只读目录里提交 | `REPO_WRITE_FAILED` |
| OAuth 三种错误（需改 OAuth App 配置才能复现） | `AUTH_CLIENT_CREDENTIALS` / `AUTH_BAD_CODE` / `AUTH_REDIRECT_MISMATCH` |
| 翻译额度耗尽（需要真把额度用光） | `TRANSLATE_QUOTA` |

### 16.5 结论

- **保留、默认开启**，但按实验性对待：不因它改别的设计，也不承诺文案的最终措辞；
- 任何一次真机反馈落到某个码上 → 先按 §16.2/§16.3 修文案或删码，再考虑补验收；
- 码表本身是「可删的」：`CODE_TABLE` + `EngineCodes` + `classify_*` 三处，删一个码不影响其它层。
