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

## 三、相关文件索引

| 文件 | 作用 |
|------|------|
| `version.properties` | 工程版本号 / 版本码（手动维护） |
| `app/build.gradle.kts` | 版本号标准化（优先读环境变量注入）+ APK 命名 + 签名配置 |
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
