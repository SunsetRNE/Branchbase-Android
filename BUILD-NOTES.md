# Branchbase 构建与版本笔记

> 记录 AGP 9.0 API 变更与当前的命名体系，供后续修改时参考，避免再次翻找。

---

## 一、AGP 9.0 API 变更（APK 重命名）

AGP 9.0 **移除了旧版 `VariantOutput.outputFileName`**（以及旧版 `applicationVariants` 的该属性），改由内部实现类 `VariantOutputImpl` 提供。

### 旧版写法（AGP 7.x 及更早，已废弃/移除）

```kotlin
android {
    applicationVariants.all { variant ->
        variant.outputs.all { output ->
            output.outputFileName = "xxx.apk"   // ❌ AGP 9.0 已不可用
        }
    }
}
```

### 新版写法（AGP 8.0+ / 9.0，当前采用）

```kotlin
import com.android.build.api.variant.impl.VariantOutputImpl

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            // 必须强转为内部实现类 VariantOutputImpl，接口 VariantOutput 上已无 outputFileName
            (output as VariantOutputImpl).outputFileName.set("xxx.apk")
        }
    }
}
```

### 关键点备忘

| 项 | 说明 |
|----|------|
| `androidComponents.onVariants { variant -> }` | AGP 8.0+ 推荐的变体 API（替代旧 `applicationVariants`） |
| `variant.outputs` | `List<VariantOutput>` |
| `VariantOutput`（接口） | 仅有 `versionCode` / `versionName` / `enabled`，**无 `outputFileName`** |
| `VariantOutputImpl`（内部实现） | 包名 `com.android.build.api.variant.impl.VariantOutputImpl`，有 `outputFileName: Property<String>` |
| 赋值方式 | `.outputFileName.set(...)`（Property 类型，用 `set` 而非 `=`） |
| `variant.buildType` | buildType 名称（`"debug"` / `"release"`） |
| `variant.name` | 变体名（`"debug"` / `"release"`） |

> 说明：`VariantOutputImpl` 是 AGP 内部实现类（非公开 API），官方未在文档明示，故此处专门记录，防止后续升级 AGP 时再次踩坑。

---

## 二、当前命名体系

### 1. 版本号（`version.properties`，手动维护）

```properties
versionName=1.0.3   # 工程版本号（semver：主.次.修订）
versionCode=103      # 工程版本码（整数，每次发布递增）
```

### 2. 标准版本号（构建时自动生成）

在 `app/build.gradle.kts` 中计算，注入 `BuildConfig`：

```
标准版本号 = 工程版本号-年月日-时分-七位哈希
示例：1.0.3-20260902-2226-a1b2c3d
```

| BuildConfig 字段 | 含义 | 示例 |
|------------------|------|------|
| `ENGINEERING_VERSION` | 工程版本号 | `1.0.3` |
| `STANDARD_VERSION` | 标准版本号 | `1.0.3-20260902-2226-a1b2c3d` |
| `BUILD_TIME` | 构建时间（年月日-时分，Asia/Shanghai） | `20260902-2226` |
| `GIT_HASH` | 七位 git 哈希 | `a1b2c3d` |

- 时间格式：`yyyyMMdd-HHmm`（固定 Asia/Shanghai 时区，避免本地与 CI 差异）
- 哈希：`git rev-parse --short=7 HEAD`（失败回退 `unknown`）

### 3. Android `versionName` / `versionCode`

```kotlin
defaultConfig {
    versionCode = engineeringVersionCode   // 103
    versionName = standardVersion          // 1.0.3-20260902-2226-a1b2c3d
}
buildTypes {
    debug { versionNameSuffix = "-Beta" }  // debug 的 versionName = standardVersion + "-Beta"
}
```

### 4. APK 产物命名

```
Branchbase-{标准版本号}[-debug].apk
```

| 类型 | 产物名 |
|------|--------|
| Debug（Beta） | `Branchbase-1.0.3-20260902-2226-a1b2c3d-debug.apk` |
| Release | `Branchbase-1.0.3-20260902-2226-a1b2c3d.apk` |

实现（`app/build.gradle.kts`）：

```kotlin
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val suffix = if (variant.buildType == "debug") "-debug" else ""
            (output as VariantOutputImpl).outputFileName.set("Branchbase-${standardVersion}${suffix}.apk")
        }
    }
}
```

> 目的：让每次构建的 APK 名唯一（含时间戳 + 哈希），避免下载器出现 `app-debug (27).apk` 这种无法区分代际的问题。

---

## 三、AGP 9 / Kotlin 2.3 新建模块的三个坑（:`translate` 落地时踩到）

### 1. Kotlin 插件不用（也不能）自己加

AGP 9.0 起**内置 Kotlin 支持**：library 模块只 apply `com.android.library`，`src/main/java` 下的 `.kt`
就会被编译。显式加 `alias(libs.plugins.kotlin.android)` 会直接失败：

```
The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support since AGP 9.0.
```

只有需要 Compose 的模块才额外 apply `org.jetbrains.kotlin.plugin.compose`（`:app` / `:editor` 即如此；
纯 Kotlin 的 `:translate` 只 apply `android.library`）。

### 2. Kotlin 块注释是**可嵌套**的：注释里别出现 `/*`

在 KDoc 里写 `` `assets/translate/*` `` 会开始一个**嵌套注释**，把后面的 `android { }` 整段吞掉。
报错完全指不到真正原因：

```
Android Gradle Plugin: project ':translate' does not specify `compileSdk` in build.gradle.kts
```

同一坑还会以 `Syntax error: ... Expecting a top level declaration`（文件末尾）的形式出现。
规则：注释文本里不要出现 `/*`（写目录就写 `translate/`）。

### 3. 中文命名的测试方法 + lambda 需要 UTF-8 locale

形如 `` fun `磁盘缓存跨实例命中`() { runBlocking { … } } `` 的测试会生成
`TranslateCacheTest$磁盘缓存跨实例命中$1.class`。若 JVM 的 `sun.jnu.encoding` 不是 UTF-8
（proot / 精简容器里 locale 常是 POSIX），写盘直接失败：

```
java.nio.file.InvalidPathException: Malformed input or input contains unmappable characters
→ Internal compiler error
```

`-Dsun.jnu.encoding=UTF-8` **无法覆盖**（该属性由 JVM 启动时的 locale 决定），
所以 `tools/env/env.rc` 里导出了 `LANG/LC_ALL=C.UTF-8`；已有的 Gradle 守护进程要用
`./gradlew --stop` 重启一次才会生效。

---

## 四、Rust ↔ Kotlin 的 JNI 契约（改了就要重建 `.so`）

JNI 导出函数是**按名字 + 形参个数**硬匹配的：`RustBridge.nativeTranslate(text, from, to, optionsJson)`
对应 `Java_com_branchbase_core_RustBridge_nativeTranslate`。两边不同步时的表现是
`UnsatisfiedLinkError`（被 `RustBridge` 的 `runCatching` 兜住，表现为「翻译服务无响应」，
**不会崩**），但功能是哑的，所以：

| 场景 | 要做的事 |
|------|---------|
| 给某个 JNI 函数加/改形参（如接入 DeepSeek 时 `nativeTranslate` 由 3 参改 4 参） | 改完必须重建 `.so`：`tools/build/build-core.sh --mode=local`（ARM64 本机约 18 分钟） |
| 只是加字段（不想动签名，也不想让旧 `.so` 报错） | 优先**传 JSON 参数**（如 `optionsJson`），Rust 侧 `serde` 解析、字段缺失走默认值 |
| CI | `build-core.sh --mode=ci` 用 cargo-ndk 交叉编译，产物提交到 `beta` 分支；`main` 不留 `.so` |

> 本地验证顺序建议：`cargo test --lib`（纯逻辑）→ `cargo test --test deepseek_http`
> （回环服务验证真实 HTTP 请求形状，不联网、不需要 Key）→ 重建 `.so` → `assembleDebug`。

---

## 五、相关文件索引

| 文件 | 作用 |
|------|------|
| `version.properties` | 工程版本号 / 版本码（手动维护） |
| `app/build.gradle.kts` | 版本号标准化（优先读环境变量注入）+ APK 命名 + 签名配置 |
| `translate/build.gradle.kts` | 沉浸式翻译模块（纯 Kotlin library，无 Compose；模块边界与移除步骤写在文件头注释） |
| `core/src/translate/` | 翻译后端（`mod.rs` 选后端 + `mymemory.rs` / `deepseek.rs`），改这里要重建 `.so` |
| `core/tests/deepseek_http.rs` | 回环服务验证 DeepSeek 请求形状的集成测试（不联网、不需要 Key） |
| `gradle/libs.versions.toml` | AGP / Kotlin / Compose 等依赖版本 |
| `gradle/wrapper/gradle-wrapper.properties` | Gradle 9.1.0（distributionUrl 固定腾讯云镜像） |
| `setup_android_env.sh` | 本地环境一键入口（委托 tools/env/ 三脚本，无镜像测速） |
| `tools/env/env-detect.sh` | 环境判定（纯判定，JSON 输出，零副作用） |
| `tools/env/env-prepare.sh` | 环境准备（预存资产 + 固定镜像，缺失才下载） |
| `tools/env/env-persist.sh` | 环境持久化（~/.bashrc 追加一行 source env.rc） |
| `tools/env/env.rc` | 环境变量（进仓库） |
| `tools/env/mirrors.conf` | 固定镜像源 + cargo-ndk 锁定版本 |
| `tools/gradle/` | Gradle 发行版 zip 预存目录（工作流检索·预存·命中，zip 不入库） |
| `tools/build/build-core.sh` | 编译 Rust `.so`（本地 clang / CI cargo-ndk 自动切换） |
| `tools/build/assemble.sh` | gradlew assemble，注入关键版本参数 |
| `tools/build/warmup-aapt2.sh` | ARM64 AAPT2 替换后预热 |
| `.github/actions/setup-branchbase-env/` | 远程 CI 环境准备 composite action |
| `.github/workflows/build-beta.yml` | Beta 三步骤流水线（prepare→build→publish） |
| `.github/workflows/build-release.yml` | 正式版三步骤流水线 |
| `.github/workflows/build-core.yml` | Rust core 编译检查 + 单元测试 |
