# 第三方内容声明 · Third-Party Notices

本项目（Branchbase Android）自身以 **MIT** 发布 —— 见 [`LICENSE`](LICENSE)。

本文件声明**不是本项目自研**的内容。规矩只有一条：

> **非 MIT 的内容允许随仓库分发，但必须在本文件单独声明，并写清来源于哪个仓库。**

分两类列，因为「许可证义务跟着什么走」不一样：

| | 是什么 | 义务跟着谁走 | 在哪一节 |
|---|---|---|---|
| **vendored** | 文件本体就在仓库里（图标、CSS…） | 跟着**仓库**走 | §一 |
| **依赖** | 走 Gradle / Cargo 解析，源码不在仓库里 | 跟着**打进 APK 的二进制**走 | §二 |

> **为什么依赖也要列**：`:editor` 的 Sora Editor 是 **LGPL-2.1**、Rust 侧 vendored 编译的
> libgit2 是 **GPL-2.0（附链接例外）**——两者都会一起进 APK。只声明 vendored 文件、
> 漏掉这两条，等于声明了一半，而且漏掉的恰好是义务最重的那一半。

---

## 一、随仓库分发的第三方文件（vendored）

| 内容 | 许可证 | 上游仓库 | 在本仓库的位置 |
|---|---|---|---|
| `git-branch` 图标（启动图标前景） | MIT | [feathericons/feather](https://github.com/feathericons/feather) | `app/src/main/res/drawable/ic_launcher_foreground.xml`（文件头已注明来源） |
| GitHub Markdown 浅色样式（按 Primer 色板裁剪重写，非逐字拷贝） | MIT | [sindresorhus/github-markdown-css](https://github.com/sindresorhus/github-markdown-css) · [primer/primer](https://github.com/primer/primer) | `app/src/main/assets/github-markdown-light.css` |
| README 深色覆盖 CSS（`.markdown-body` / `.pl-*` 语法令牌等） | MIT | 同上 | `ui/repository/ReadmeWebView.kt` 的 `README_DARK_CSS` |
| Primer 设计令牌（GitHub 的色板与语义角色取值） | MIT | [primer/primer](https://github.com/primer/primer) | `ui/theme/ThemePalette.kt`、`ui/theme/Color.kt` |
| 通知小图标（下载箭头；形状对齐 Material 的 `file_download`） | Apache-2.0 | [google/material-design-icons](https://github.com/google/material-design-icons) | `app/src/main/res/drawable/ic_stat_download.xml` |

> 判断口径：**只放「能指出上游」的文件**。剩下的图标与图形都是本项目自绘
> （例如 `ic_launcher_background.xml` 就是一个纯色 rect），自绘的不进这张表 ——
> 表里每多一条无法核对的来源，这份声明的可信度就低一分。

## 二、构建依赖（会进 APK 的二进制）

### 2.1 非 MIT 的两条（重点）

| 组件 | 版本 | 许可证 | 上游仓库 | 引入位置 |
|---|---|---|---|---|
| **Sora Editor**（代码编辑器：TextMate 高亮 / 行号 / 折叠） | `0.23.6` | **LGPL-2.1** ⚠️ | [Rosemoe/sora-editor](https://github.com/Rosemoe/sora-editor) | `gradle/libs.versions.toml`（`soraEditor`）→ 只被 `:editor` 模块引用 |
| **libgit2**（clone / pull / commit / push 的 C 库，随 `git2` crate **vendored 编译**） | `1.7.2` | **GPL-2.0（附 LINKING EXCEPTION）** ⚠️ | [libgit2/libgit2](https://github.com/libgit2/libgit2) | `core/Cargo.toml`（`git2` 的 `vendored-libgit2` 特性） |

这两条当前用法为什么成立（合规姿势写下来，换用法时要重新判）：

- **Sora Editor（LGPL-2.1）**：以**未修改的库**形式动态链接（Gradle 依赖，不是把源码拷进仓库），
  因此本项目的 MIT 声明不受影响；只有**修改库本身**才会触发 LGPL 的传染条款。
  封装边界也刻意留好了：第三方编辑器库**只**在 `:editor` 模块声明，主应用不直接依赖它
  （见 `editor/build.gradle.kts` 的文件头），换库或删除时只动这一个模块 + 关于页那一行痕迹。
- **libgit2（GPL-2.0 + 链接例外）**：例外条款原文允许「link the compiled version of this library
  into combinations with other programs, and to distribute those combinations without any
  restriction coming from the use of this file」。本项目通过 `git2` crate 以 `.so` 方式链接、
  **未修改** libgit2 源码 —— 其余 GPL 限制（改库、单独分发库）本项目都不涉及。

### 2.2 其余依赖（均为宽松许可，非 MIT 的照样列）

| 组件 | 版本 | 许可证 | 上游仓库 |
|---|---|---|---|
| AndroidX：core-ktx / activity-compose / lifecycle / Room | `1.10.1` / `1.8.0` / `2.6.1` / `2.8.4` | Apache-2.0 | [androidx/androidx](https://github.com/androidx/androidx) |
| Jetpack Compose（BOM）、Material 3、Material Icons（core / extended） | `2026.01.01` | Apache-2.0 | 同上 |
| Coil（图片加载，含 SVG） | `2.7.0` | Apache-2.0 | [coil-kt/coil](https://github.com/coil-kt/coil) |
| kotlinx-coroutines | `1.9.0` | Apache-2.0 | [Kotlin/kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) |
| OpenSSL（随 `openssl` crate vendored 编译，供 HTTPS） | `3.6.3` | Apache-2.0 | [openssl/openssl](https://github.com/openssl/openssl) |
| Rust crates（`core/`，含 git2 / jni / serde / reqwest / tokio / rustls 等，共 **197** 个） | 见 `core/Cargo.lock` | **MIT 或 Apache-2.0**（多数为双许可，rustls 为 `Apache-2.0 OR ISC OR MIT`） | 各自仓库，见 [crates.io](https://crates.io) 与 `core/Cargo.lock` |
| JUnit 4 | `4.13.2` | EPL-1.0（**仅 JVM 单测**） | [junit-team/junit4](https://github.com/junit-team/junit4) |
| org.json（JVM 单测里替代 android.jar 的空壳实现） | `20240303` | Public Domain | [stleary/JSON-java](https://github.com/stleary/JSON-java) |
| Espresso / androidx.test（**仅 instrumented 测试**） | `3.5.1` / `1.1.5` | Apache-2.0 | [androidx/androidx](https://github.com/androidx/androidx) |

> Rust 那 197 个 crate 不逐个抄许可全文：它们**全部**是 MIT / Apache-2.0 系（含双许可），
> 且 `core/Cargo.lock` 是完整清单 —— 声明到「清单在哪、逐个什么许可」即可，
> 抄一遍反而会随依赖升级失真。**新增非宽松许可的 crate（GPL/AGPL/SSPL…）时必须回来单列一行。**

## 三、维护规则

1. **新增第三方内容 → 本文件必须同步加一行**：组件 / 版本 / 许可证 / 上游仓库 / 在本仓库的位置；
   **非 MIT 的一律单列**（不允许只写「见 Cargo.lock」这类含糊表述）。
2. **三处痕迹必须一致**：本文件 · `EditorModuleInfo`（`:editor`，关于页展示用）· 关于页那一行文案。
   升级 Sora Editor 时三处一起改 —— 版本号的真源是 `gradle/libs.versions.toml` 的 `soraEditor`。
3. **移除组件时删对应行**：`:editor` 的删除步骤写在 `editor/build.gradle.kts` 文件头
   （删模块 + `settings.gradle.kts` 的 include + 关于页那一行）。
4. **核对口径**（本节结论的取证方式，2026-09-24）：
   - Gradle 依赖的许可证：本地 Gradle 缓存里各 POM 的 `<licenses>` 段（例：`io.github.Rosemoe.sora-editor:editor` → `LGPL v2.1`）；
   - Rust crate 的许可证：crates.io registry 中各自 `Cargo.toml` 的 `license` 字段；
   - libgit2：`libgit2/COPYING`（GPL-2.0 + `LINKING EXCEPTION` 段）；
   - OpenSSL：`openssl-src` 内的 `LICENSE.txt`（Apache-2.0）；
   - 依赖清单真源：`gradle/libs.versions.toml`（Android 侧）与 `core/Cargo.lock`（Rust 侧）。

> 这条口径本身也是要求：**声明的每一行都要能指到证据**。指不到证据的，要么补证据，
> 要么按「可能有义务」先声明出来 —— 少声明一条的代价，比多声明一条大得多。
