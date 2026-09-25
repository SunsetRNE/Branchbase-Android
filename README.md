# Branchbase

> GitHub 第三方 Android 客户端，对标官方 GitHub App 的信息架构与交互体验。

业务核心（OAuth 认证、GitHub API、Git 操作、HTML 解析）由 **Rust** 实现，经 JNI 暴露给 **Jetpack Compose** 界面层。

- **仓库**：<https://github.com/SunsetRNE/Branchbase-Android>
- **平台范围**：**只做 Android 手机 / 平板**（`minSdk 24`，即 Android 7.0+）；桌面端、Web 端、iOS 均不在计划内。
- **开发交流**：QQ 群「Branchbase开发交流」· 群号 **790735040**
  （[群链接](https://qun.qq.com/universal-share/share?ac=1&authKey=pvJE5SaHMaTeBU%2BNqt2VJBfeAGY0eLg%2BGHUTz2TDjsxe0aeS3L32m6Cg0NEnMCmg&busi_data=eyJncm91cENvZGUiOiI3OTA3MzUwNDAiLCJ0b2tlbiI6ImN4bGZ6WnBiUHVRZ0JOWFlmTVloV1R6S1o3NnIyV212RExzeTdtdUs4TVdqamVaNjR0V2xDRGJvNkI1N1RvV3ciLCJ1aW4iOiIxNTM5MDA3NDYwIn0%3D&data=U0Umjl8BD3SwVhcezyilAhDNcA1MUL3HSJAq3dI4hCfZywecQmAbK4yqvgp-3FkggT-Sq4hqJivTho3p4TrhEQ&svctype=4&tempid=h5_group_info)
  里的 `authKey` 会过期，过期后按**群号搜索**加入即可）

## 📥 下载与安装

| 通道 | 从哪拿 | 说明 |
|---|---|---|
| **正式版** | [Releases](https://github.com/SunsetRNE/Branchbase-Android/releases) | 手动触发的发布流水线产出，附签名校验文件与源码包 |
| **Beta 测试版** | 同上，标着 **Pre-release** 的那些 | push 到 `main` 自动构建 |

- 产物形如 `Branchbase-<工程版本>-<年月日-时分>-<七位哈希>.apk`（调试包多一个 `-debug` 后缀）。
- 安装后在欢迎页二选一登录：**OAuth 授权**（新设备首次要过一次口令 / 设备验证）或**访问密钥 PAT**。
- **自己编译的人注意**：OAuth 的 `client_secret` 是**编译期注入**的（`local.properties` → `BuildConfig`，CI 走仓库 secret），
  仓库里没有也不该有 —— 自己出包需要**自己的 GitHub OAuth App**；只用 PAT 登录则不需要。

## 🚀 快速上手

- **登录**：欢迎页两个入口 —— OAuth（授权码 + PKCE）或 PAT（`GET /user` 先校验再落库，可一键跳网页端并预填所需权限）。
- **看代码**：仓库列表 → 详情 → README / 语言统计 / 贡献者；正文里的链接按站内路由跳转，站外的交给浏览器。
- **提交**：三种模式（单文件 / 多文件 / 本地仓库）—— 代码页与文件页的 **Git 悬浮球只在「本地仓库（Git）」模式出现**
  （另外两种直接调远端 API、本地没有工作树）。
- **同步分支**：仓库页底部 `⋮` 气泡 → 分支同步（服务端合并，入口只在有写权限时出现）。
- **下载附件**：发布页的附件走应用内下载（前台服务 + 通知进度 + 断点续传），完成后可直接安装 / 打开 / 分享。
- **翻译**：设置 → 沉浸式翻译 → 打开总开关，正文页出现悬浮球（原文 + 译文对照）。
  判定引擎会跳过「本来就是目标文字」的段落（含译文与原文一致的情形），中英混排只翻需要翻的片段
  （按「片段 → 译文」对照展示；可在设置里切成整段翻 / 混排不翻）。
- **导出日志**：设置 → 日志 → 导出（打包 zip 到 `Download/Branchbase`）—— 提 Issue 时带上它最有用。
- **返回键**：已登录时顶层「再按一次退出」，页面内逐层关闭自己（与左上角返回箭头**同一条路径**）。

## ✨ 功能一览

| 功能 | 一句话 | 细节 |
|---|---|---|
| 登录与账号 | OAuth + PAT 两种方式；多账号管理与状态检查 | [`features-design.md`](docs/specs/features-design.md) §1 |
| 个人主页 | 概览 / 仓库 / 动态 + 星标 · 软件包 · 项目 · 设置 | §2 |
| 仓库浏览 | 列表 / 详情 / README 渲染 / 语言 / 贡献者 / 分支同步 | §3 |
| 搜索 | 七类搜索；结果可点进站内；**按账号隔离**缓存 | §4 |
| 提交与本地仓库 | 三种提交模式；libgit2 浅 clone / pull / commit / push | §5、[`local-git-engine-design.md`](docs/specs/local-git-engine-design.md) |
| 决策页面 | 分叉 / 撤销 / 上游 / 回退 / 删除警告 / 敏感信息 … | [`decision-pages-design.md`](docs/specs/decision-pages-design.md) |
| 沉浸式翻译（`:translate`） | 正文页对照翻译；判定引擎跳过已一致的段落、只翻混排里需要翻的片段 | [`modules-design.md`](docs/specs/modules-design.md) §1 |
| 内建下载（`:downloader`） | 前台服务保活 + 通知进度 + 断点续传 | §2 |
| 图片查看器（`:imageviewer`） | 点图放大：缩放 / 拖动 / 下拉关闭 | §3 |
| 代码编辑器（`:editor`） | 文件页**编辑态**：等宽 + 行号 + 无边框 | §4 |
| 作业日志（`:joblogs`） | 分组切段 + 两段式缓存 | §5 |

## 🧱 技术栈与结构

| 层 | 技术 |
|----|------|
| UI | Kotlin + Jetpack Compose + Material 3 |
| 业务核心 | Rust（`branchbase-core`，JNI 桥接） |
| Git | libgit2（clone / pull / commit / push） |
| 网络 | reqwest（rustls-tls） |
| 异步 | tokio |
| 序列化 | serde / serde_json |

| 目录 | 是什么 |
|---|---|
| `app/` | Android 应用层（Compose UI + `core/RustBridge`） |
| `core/` | Rust 业务核心（cdylib → `libbranchbase_core.so`）：`models` / `auth` / `api` / `bridge` / `git` / `translate` / `html` / `workflow` |
| `translate/` `downloader/` `imageviewer/` `editor/` `joblogs/` | 五个**独立模块**（可整体换掉，依赖方向一律 `:app → :<module>`） |
| `docs/` | 设计文档（**结论类，入库**）：收纳规则 + `specs/` |
| `design/` | 原型草图（HTML/CSS/JS，**永久不入库**，DSH 侧边栏「原型预览」可打开） |
| `tools/` | 环境 / 构建 / 帧率取数脚本（`tools/env`、`tools/build`、`tools/perf`） |
| `.github/workflows/` | CI/CD：Beta / 正式版 / core 编译检查 三条流水线 |
| `version.properties` | 工程版本号（手动维护；逐版说明见 `docs/specs/VERSION-NOTES.md`） |

## 🔧 构建（开发者）

环境：JDK 17+ · Android SDK（compileSdk 35）· Rust 工具链。

```bash
./setup_android_env.sh            # ARM64 环境一键准备（判定 → 准备 → 持久化，脚本解耦）
./gradlew assembleDebug           # APK（版本号尾部附加 -Beta）
cd core && cargo build --release  # 生成 libbranchbase_core.so
```

单测：`./gradlew :app:testDebugUnitTest :translate:testDebugUnitTest` · `cd core && cargo test`。

> 容器 / proot 环境先 `source tools/env/env.rc`（其中导出了 UTF-8 locale —— JVM 的文件名编码取自 locale，
> 非 UTF-8 时**中文命名的测试方法会编译失败**，见 [`BUILD-NOTES.md`](docs/specs/BUILD-NOTES.md)）。
> CI 三步骤流水线、AGP 9 的坑、Rust ↔ Kotlin 的 JNI 契约、版本号命名体系都在那份笔记里。

## 📚 文档

**结论类文档在 `docs/`（入库）；原型草图在 `design/`（永久不入库）。**
唯一的**文档索引与收纳规则**在 [`docs/README.md`](docs/README.md) —— 新增文档只登记那一处，别在别处再列一份。

- 想**用**这个 App → 本文件 + [`features-design.md`](docs/specs/features-design.md)；
- 想**改**某块实现 → 先读 [`docs/README.md`](docs/README.md)（按角色 / 主题找）；
- 想**动设计** → `design/<原型名>/` 是可点草图，它的说明与落实方案入库在 [`docs/specs/prototypes/`](docs/specs/prototypes/)；
- 想查**某一版改了什么、为什么** → [`docs/specs/VERSION-NOTES.md`](docs/specs/VERSION-NOTES.md)。

## 💬 反馈与交流

- **Bug / 功能建议**：[提 Issue](https://github.com/SunsetRNE/Branchbase-Android/issues)
  （附上机型、系统版本、复现步骤与截图；**设置 → 日志 → 导出**的 zip 最有用）。
- **开发交流**：QQ 群「Branchbase开发交流」· 群号 ［**790735040**］（https://qun.qq.com/universal-share/share?ac=1&authKey=yo4wme1K282KJ5pjKPoEOH%2BwvUnqXBXZmrmk0UM7EqCR5mNXx2wfobua8yRHfqt6&busi_data=eyJncm91cENvZGUiOiI3OTA3MzUwNDAiLCJ0b2tlbiI6Im5hclpJZTQ5TlhQNEtZUTEycmpRY2xKY0hYb1lES04vUXVGaEpQT3ovdk9teERSVXF4WEVEZys0UzhoVDBmQS8iLCJ1aW4iOiIxNTM5MDA3NDYwIn0%3D&data=jFk2wCMkvso0aQvYHaqQR7Pb8PeAQ1cHA6-i8NUi68a4xWwqeK7-kYtQlO1m8bgfKPsRptrjvWFmSkEW86ybBQ&svctype=4&tempid=h5_group_info）。
- **平台范围**：只做 Android 手机 / 平板，其他端（桌面 / Web / iOS）不做。

## 📄 许可证

本项目以 **MIT** 发布 —— 全文见 [`LICENSE`](LICENSE)（`Copyright (c) 2026 SunsetRNE`）。
你可以自由使用、修改、分发（含商用），只需保留版权与许可声明。

**第三方内容单独声明**：非本项目自研的内容（vendored 文件与构建依赖）**允许**随仓库分发，
但**必须**在 [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) 里单独声明，并写清来源于哪个仓库。
其中两处**不是 MIT**，看代码或许可证前先读那两条：

| 组件 | 许可证 | 上游 |
|---|---|---|
| **Sora Editor**（`:editor` 模块封装的代码编辑器） | LGPL-2.1 | [Rosemoe/sora-editor](https://github.com/Rosemoe/sora-editor) |
| **libgit2**（clone / pull / push 的 C 库，随 `git2` crate vendored 编译） | GPL-2.0（附 LINKING EXCEPTION） | [libgit2/libgit2](https://github.com/libgit2/libgit2) |

两者的当前用法（未修改 + 动态链接）、合规依据与「换用法时要重新判什么」都写在
[`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) §2.1；新增第三方内容时按那份文档 §三 的
维护规则同步加行。

> 关于「Branchbase」这个名字与图标：名称与图标是本项目的标识，**不在 MIT 授权范围内**
> （MIT 覆盖的是代码与随仓库分发的文档）；fork 后请换成你自己的名称与图标，
> 以免与官方构建混淆 —— 官方 Beta/正式包带签名校验（设置 → 关于 可核对）。

