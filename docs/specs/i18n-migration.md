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
   按 §5.4 需先定「结构化错误」方案（Rust 侧返回稳定错误码，Kotlin 侧映射资源）。
2. **`RustBridge` 的 `"引擎不可用"` 8 处** —— 与上一条同源，返回值类型是 `String?`，要先定结构化错误。
3. **`LogExporter`(41) / `SigningVerify`(14)** —— §5.4 路径 C′，先枚举化分类。
4. **其余作用域受限项** —— 逐个走 A′/C′。

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
3. **`SigningVerify.verifyCopy` 的 14 条**：它是纯函数返回整句文案，要走 §5.4 C′（先枚举化分类，
   文案在调用方解析）—— 这次只把它的 `variant.label` 换成传参（`variantLabel: String`），
   整段文案仍是中文；
4. **`LocalBranchSyncModels` / `RepoActions` / `GitProxy` 校验提示** 等纯逻辑里的提示。

> 动手前照旧先空跑一次：`python3 tools/i18n/extract.py --file <文件>` ——
> 它报「表里没有」就补 `tools/i18n/strings.tsv`，报「作用域不行」就得按 A′/C′ 改结构。
