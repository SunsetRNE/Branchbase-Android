# Branchbase

> GitHub 第三方 Android 客户端，对标官方 GitHub App 的信息架构与交互体验。

Branchbase 是一个基于 **Jetpack Compose** + **Rust** 的现代化 GitHub 客户端。业务核心（OAuth 认证、GitHub API、Git 操作、HTML 解析）由 Rust 实现，通过 JNI 桥接暴露给 Android Compose 层调用。

## ✨ 技术栈

| 层 | 技术 |
|----|------|
| UI | Kotlin + Jetpack Compose + Material 3 |
| 业务核心 | Rust（`branchbase-core`，JNI 桥接） |
| Git | libgit2（clone / pull / commit / push） |
| 网络 | reqwest（rustls-tls） |
| 异步 | tokio |
| 序列化 | serde / serde_json |

## 📁 项目结构

```
Branchbase/
├── app/                 # Android 应用层（Compose UI）
│   └── src/main/java/com/branchbase/
│       ├── core/        #   RustBridge（JNI 桥接）
│       └── ui/          #   auth / home / profile / repository / navigation / theme
├── core/                # Rust 业务核心（cdylib → libbranchbase_core.so）
│   └── src/
│       ├── models/      #   数据模型（User / Repository / Token ...）
│       ├── auth/        #   OAuth（PKCE）+ token 管理 + 2FA
│       ├── api/         #   GitHub API 客户端（REST / GraphQL）
│       ├── bridge/      #   JNI 导出函数
│       ├── git/         #   libgit2 封装（clone/pull/commit/push）
│       ├── translate/   #   翻译后端（MyMemory 匿名 / DeepSeek 或任意 OpenAI 兼容服务，自带 Key）
│       └── html/        #   README 渲染 HTML 解析 + 链接跳转
├── editor/              # 代码编辑器独立模块（封装 Sora Editor，换库/移除只动这里）
├── translate/           # 沉浸式翻译独立模块（设置/分片/占位符保护/缓存/调度/页面脚本）
│   ├── src/main/java/com/branchbase/translate/   #   纯逻辑 + Android 适配 + WebView 桥
│   └── src/main/assets/translate/                #   页面脚本（01-core ~ 04-boot）与译文 CSS
├── tools/               # 环境与构建脚本（tools/env、tools/build）
├── .github/workflows/   # CI/CD（Beta / Release）
├── version.properties   # 工程版本号配置（手动维护）
└── setup_android_env.sh # ARM64 AAPT2 替换脚本
```

> `docs/`（设计文档 wireframe / 规范）、`design/`（原型草图）、`re-workspace/`（逆向资源）、
> `tools/aapt2/`（官方二进制）、`.kotlin/` 等目录已加入 `.gitignore`，**不入库**。
> 因此代码注释里不再引用这些文件路径 —— 规格与结论直接写在注释与本文档中；
> 迁移过程中丢失的文档请以代码与本文档为准。

## 🚀 功能特性

- **OAuth 登录**：授权码 + PKCE，`client_secret` 编译期注入（`local.properties` → `BuildConfig`）
- **个人主页**：概览 / 仓库 / 动态 + 气泡导航，星标 / 软件包 / 项目 / 设置子页面
- **仓库浏览**：列表 / 详情 / README 渲染 / 语言统计 / 贡献者
- **代码搜索**：仓库 / 用户 / issue / 代码 / 提交 / 主题多类型搜索
- **提交模式**：单文件 / 多文件 / 本地仓库（对齐 GitHub 官方行为）
- **本地仓库**：libgit2 浅 clone / pull（fast-forward）/ commit / push
- **沉浸式翻译**：正文页原文 + 译文对照（独立 `:translate` 模块，见下文）
- **关于页**：版本号标准化展示（工程版本 / 标准版本 / 构建时间 / 七位哈希）

## 🌐 沉浸式翻译（模块化实现）

在**正文页**（自述文件 README、Issue / PR 主帖、发布说明 —— 即 WebView 渲染的那些页面）把每个段落
翻成目标语言、插在原文下方，形成「原文 + 译文」对照；页内右下角的「译」按钮可随时开关，
长按在「对照 / 仅译文」之间切换，整页可见文本 ≤ 5000 字符时一次翻完，更长则按视口滚动逐段翻译。

设计对齐网页版[沉浸式翻译](https://github.com/immersive-translate/immersive-translate)
（可读源码的开源旧版：[old-immersive-translate](https://github.com/immersive-translate/old-immersive-translate)）：
沿用「独立译文容器 + 视口优先 + 持久缓存 + 请求限流 + 占位符保护」的思路，落成 Android 侧的分层实现。

### 模块划分（`:translate`）

依赖方向单向：`:app → :translate`，模块内不引用任何 App 类型；翻译**后端由 App 注入**
（`RustTranslateEngine` → `core/src/translate.rs`，默认 MyMemory 匿名接口）。

| 文件 | 职责 |
|------|------|
| `TranslateLanguages.kt` | 语言模型（中英两向）+ 页面判定参数 `PageRules`（含注入 JS 的 JSON） |
| `TranslateProvider.kt` | 可选后端：MyMemory（免费）/ DeepSeek（自带 API Key，OpenAI 兼容） |
| `TranslateTextPolicy.kt` | 「这一段值不值得翻」的权威判定（纯函数，可单测） |
| `TextSegmenter.kt` | 长文本分片：段落 → 句末 → 空格 → 硬切（后端 500 字符硬上限） |
| `PlaceholderGuard.kt` | 占位符保护：URL / `@提及` / `#编号` / 邮箱 / 模板变量 / 提交 SHA |
| `TranslateCache.kt` | 进程内 LRU + 磁盘追加日志缓存（跨进程复用） |
| `TranslateEngine.kt` | 后端接口 + 失败分类（`QUOTA` / `NETWORK` / `UNSUPPORTED` / `UNKNOWN`） |
| `TranslateScheduler.kt` | 全局串行闸门 + 指数退避重试 + 两级熔断 |
| `Translator.kt` | 门面：判定 → 缓存 → 保护 → 分片 → 调度 → 还原 → 回写缓存 |
| `TranslateConfig.kt` | 用户设置与读写（自动翻译 / 目标语言 / 显示方式 / 样式 / 本地缓存 / 保护） |
| `TranslatePage.kt` | 页面资产装载：`assets/translate/*` 的 CSS 与四个脚本按序拼接 |
| `TranslateBridge.kt` | WebView JS 桥（`request` / `retry` / `state`，异步回调 + 状态回推） |
| `TranslateRuntime.kt` | 装配点：`Application.onCreate` 里 `install(this, RustTranslateEngine())` |

页面脚本同样按职责拆分（`translate/src/main/assets/translate/`）：
`01-core.js`（配置 / 状态机 / 批量队列）、`02-dom.js`（段落收集 / 跳过 / 插入 / 视口）、
`03-ui.js`（浮动按钮与状态）、`04-boot.js`（启动与策略）、`translate.css`（译文样式）。

界面侧：设置页（设置 → 沉浸式翻译）负责所有开关；正文页由 `ReadmeWebView` 拼装页面资产，
不参与任何翻译逻辑。

### 翻译服务：可以自带 DeepSeek API Key

默认用 **MyMemory**（公开匿名接口，零配置，额度约 5000 词/天，本质是句子库匹配）。
想要更好的长句 / 术语一致性时，在**设置 → 沉浸式翻译 → 翻译服务**里切到 **DeepSeek** 并填入自己的 Key：

- 走 OpenAI 兼容的 `POST /chat/completions`（`Authorization: Bearer <key>`），
  **模型名与接入地址都可改**——官方模型名会随版本调整，接口兼容又意味着可以接中转 / 自建网关；
- 请求体只带 `model` / `messages` / `stream:false`：**不传 temperature**，
  官方对「翻译推荐温度」的说法变过，与其钉死一个可能被弃用的参数，不如用服务端默认；
- 系统提示词里唯一承担职责的一条是「`⟦n⟧` 占位符必须原样保留」（配合下面的占位符保护），
  另外要求「只输出译文、不加引号/前缀」，Rust 侧还会兜底清理模型爱加的外壳（`译文：`、包裹引号）；
- Key 只存在 App 私有 SharedPreferences、**绝不注入网页**（有单测钉住这条边界），设置页里可以
  「保存并测试连接」，失败原因会区分「Key 无效 / 余额不足 / 网络不可达」；
- 实现落在 `core/src/translate/`（`mod.rs` 选后端 + 2 个 provider），Kotlin 侧只是换一个后端选项，
  分片、缓存、串行、熔断全部复用同一套。

### 关键设计决策

1. **译文是原文的兄弟节点，原文一个字都不改** —— 网页版旧实现把译文写回原文本节点、再靠隐藏副本
   实现双语，导致「切回原文 / 切换对照」都依赖额外状态并互相打架；独立容器天然幂等（有容器=已翻译）。
2. **判定规则只有一个真源** —— 阈值与跳过正则定义在 Kotlin 的 `PageRules`，随设置注入
   `window.__bbTranslate.rules`，JS 只使用不定义；原生侧仍做权威判定，防止脚本版本漂移。
3. **占位符保护** —— 待译文本里的 URL / `@提及` / `#编号` / 提交 SHA 等先换成 `⟦n⟧`，
   译后还原；任一占位符丢失即判失败，并**不加保护地重翻一次**（对齐网页版「还原失败就重译该段」）。
4. **三种熔断** —— 额度用尽（余额/限流）与 API Key 无效都立刻停发请求（继续发只是浪费额度、
   或被 401 刷屏），二者状态分开，页面分别提示「稍后再试」与「去设置检查 Key」；
   连续 3 次其它失败也暂停。状态回推页面，按钮变成「可重试」，而不是「点了没反应」。
5. **缓存两层** —— 内存 LRU（512 条）+ 磁盘追加日志（2000 条，`filesDir/translate/cache.tsv`），
   键含源/目标语言；磁盘坏行跳过而不是让缓存整体失效。
6. **短页一次翻完、长页视口优先** —— 候选文本 ≤ 5000 字符直接全翻；超过则按 `IntersectionObserver`
   滚动逐段，避免一次几百个请求烧光额度、卡住首屏。
7. **译文样式只改一个根属性** —— `body[data-bb-style]`（卡片 / 下划线 / 淡灰），切换不重翻、不重建 WebView。
8. **JS 只做 DOM** —— 网络、缓存、串行与重试全在 Kotlin，桥上一次只传一批（≤3 段）文本，
   避免把整页内容或配置在 JS ↔ Native 之间来回搬。[#3262](https://github.com/immersive-translate/immersive-translate/issues/3262)
   的 OOM 正是「大对象过桥」造成的。

### 已知边界

- 生效范围是 **WebView 渲染的正文页**；评论区由 Compose 原生渲染（`MarkdownBody`），暂不在范围内；
- 后端支持 MyMemory 与「任意 OpenAI 兼容服务」（DeepSeek 官方 / 中转 / 自建）两种态；
  再加一家（DeepL 等）只需在 `core/src/translate/` 加一个 provider + 设置页加一项；
- 术语库 / 自定义提示词 / 悬停看原文等网页版高级能力未实现
  （`TranslateEngine` 的 options JSON 与提示词构建是预留的落点，
  `translate/build.gradle.kts` 里写了移除步骤）。

参考：[主仓库](https://github.com/immersive-translate/immersive-translate) ·
[开源旧版源码](https://github.com/immersive-translate/old-immersive-translate) ·
[官网文档](https://immersivetranslate.com/docs/usage/)

## 🎞 页面动效（统一规格）

此前全项目**没有任何页面切换动效**：Tab 切换、首页进个人页/搜索/仓库、仓库页里十几个全屏子页、
个人页的设置子页、任务列表进详情、登录流程各状态、开 PR 三步向导……状态一变内容直接换掉，
观感是「点一下，画面硬切」。

现在动效只有**一处真源**（`app/src/main/java/com/branchbase/ui/navigation/PageTransitions.kt`），
页面只声明「我在第几层」（`PageLevel.depth`），方向由层级差决定：

| 场景 | 动效 | 为什么 |
|------|------|--------|
| 有层级的推进 / 返回（`PageSwitcher`） | 水平滑动 1/4 屏 + 淡入淡出 | 子页有前后关系：前进从右进、返回向右出 |
| 同级切换（`TabSwitcher`，含仓库页切 Tab） | 淡入淡出 + 轻微上浮 | 同级没有前后关系，横向滑动会暗示错误的层级 |

约定：
- **页面状态要收敛成一条路由**（枚举 / data class），交给 `PageSwitcher` 渲染 ——
  它同时承担「渲染哪个页面」和「动画期间把旧状态原样交给退场内容」两件事。
  用 `if (x != null) { 页面(); return }` 的老写法做不到后者：状态一清空，
  退场中的页面会先变成空白、再淡出（看起来像闪烁）；
- **路由要把页面数据带上**（如 `RepoRoute.Issue(number)`），不要在页面里现读可变状态；
- 返回键**按当前路由分派一个**（不要每个页面各挂一个 `BackHandler`）：
  动画期间新旧两个页面会同时存在，两个 BackHandler 会抢同一个返回事件。

## 🔧 构建

### 环境要求
- JDK 17+
- Android SDK（compileSdk 35）
- Rust 工具链（编译 `core/`）

### ARM64 环境一键准备（判定 → 准备 → 持久化，脚本解耦）
```bash
chmod +x ./setup_android_env.sh
./setup_android_env.sh   # 委托 tools/env/ 下的三脚本，无镜像测速，预存资产 + 固定镜像
```

环境脚本（`tools/env/`）：
- `env-detect.sh`：纯判定（java/sdk/gradle/rust/aapt2），输出 JSON，零副作用
- `env-prepare.sh`：缺失才下载（预存资产优先 + 固定镜像）
- `env-prepare-arm64-patches.sh`：ARM64(proot) 补充准备（lld / cargo-ndk / 自包含 aarch64 aapt2 并绑定到全局 `~/.gradle/gradle.properties` / env.rc 的 cargo PATH）；**非 aarch64 自动跳过**
- `env-persist.sh`：仅在 `~/.bashrc` 追加一行 `source tools/env/env.rc`

编译脚本（`tools/build/`）：
- `build-core.sh`：编译 Rust `.so`（本地 clang / CI cargo-ndk 自动切换）
- `assemble.sh`：`gradlew assemble`，注入关键版本参数
- `warmup-aapt2.sh`：ARM64 AAPT2 替换后预热（可选，首次编译前）

### Android APK
```bash
./gradlew assembleDebug     # Debug（版本号尾部附加 -Beta）
./gradlew assembleRelease   # Release（需配置签名密钥）
```

### 单元测试
```bash
./gradlew :app:testDebugUnitTest :translate:testDebugUnitTest   # 业务层 + 沉浸式翻译模块
cd core && cargo test                                            # Rust 核心
```
> 容器/proot 环境先 `source tools/env/env.rc`（其中导出了 `LANG/LC_ALL=C.UTF-8`：
> JVM 的文件名编码取自 locale，非 UTF-8 时中文命名的测试方法会编译失败，见 `BUILD-NOTES.md`）。

### Rust 核心
```bash
cd core
cargo build --release       # 生成 libbranchbase_core.so
```

## 🔖 版本号规范

采用「工程版本号 + 标准版本号」双轨制（原设计文档未入库，规则以本节为准）：

```
标准版本号 = 工程版本号-年月日-时分-七位哈希
示例：1.0.1-20260901-1342-a1b2c3d
```

- 工程版本号：`version.properties` 手动维护（semver）
- 标准版本号：构建时注入 `BuildConfig`（时间 + `git rev-parse --short=7`）

## 🤖 CI/CD（三步骤流水线）

发行版编译（beta/release）统一为「环境准备 → 执行编译 → 发布」三步骤：

| 步骤 | job | 职责 |
|------|-----|------|
| ① 环境准备/检索资产/判定命中 | `prepare` | 复用 `.github/actions/setup-branchbase-env`（Java/Rust/NDK/cargo-ndk@4.1.2/Gradle 缓存） |
| ② 执行编译/注入版本参数 | `build` | `build-core.sh` 编 `.so` + `assemble.sh` 打包，注入版本参数并上传产物 |
| ③ 收集/类型判定发布/代码整合 | `publish` | 判定 beta/release 类型，发布 Release + 整合代码变更 |

- `build-beta.yml`：push 到 `main` 触发，三步骤构建 Debug APK 发布为 pre-release，`.so` 提交到 `beta` 分支
- `build-release.yml`：`workflow_dispatch` 手动触发，三步骤构建 Release APK 发布为正式版
- `build-core.yml`：Rust core 编译检查 + 单元测试（push/PR 触发，非发行编译）

## 📄 许可证

（待补充）