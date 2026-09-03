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
│       └── html/        #   README 渲染 HTML 解析 + 链接跳转
├── docs/                # 设计文档（wireframe / 规范）
├── .github/workflows/   # CI/CD（Beta / Release）
├── version.properties   # 工程版本号配置（手动维护）
└── setup_android_env.sh # ARM64 AAPT2 替换脚本
```

> `design/`（原型草图）、`re-workspace/`（逆向资源）、`tools/aapt2/`（官方二进制）、`.kotlin/` 等目录已加入 `.gitignore`，不入库。

## 🚀 功能特性

- **OAuth 登录**：授权码 + PKCE，`client_secret` 编译期注入（`local.properties` → `BuildConfig`）
- **个人主页**：概览 / 仓库 / 动态 + 气泡导航，星标 / 软件包 / 项目 / 设置子页面
- **仓库浏览**：列表 / 详情 / README 渲染 / 语言统计 / 贡献者
- **代码搜索**：仓库 / 用户 / issue / 代码 / 提交 / 主题多类型搜索
- **提交模式**：单文件 / 多文件 / 本地仓库（对齐 GitHub 官方行为）
- **本地仓库**：libgit2 浅 clone / pull（fast-forward）/ commit / push
- **关于页**：版本号标准化展示（工程版本 / 标准版本 / 构建时间 / 七位哈希）

## 🔧 构建

### 环境要求
- JDK 17+
- Android SDK（compileSdk 35）
- Rust 工具链（编译 `core/`）

### ARM64 环境一键准备（判定 → 准备 → 持久化，三脚本解耦）
```bash
chmod +x ./setup_android_env.sh
./setup_android_env.sh   # 委托 tools/env/ 下的三脚本，无镜像测速，预存资产 + 固定镜像
```

环境脚本（`tools/env/`）：
- `env-detect.sh`：纯判定（java/sdk/gradle/rust/aapt2），输出 JSON，零副作用
- `env-prepare.sh`：缺失才下载（预存资产优先 + 固定镜像）
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

### Rust 核心
```bash
cd core
cargo build --release       # 生成 libbranchbase_core.so
```

## 🔖 版本号规范

采用「工程版本号 + 标准版本号」双轨制，详见 `docs/versioning.md`：

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