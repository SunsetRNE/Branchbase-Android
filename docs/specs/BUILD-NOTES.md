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

下面这段（以及本节其余示例）是**格式示例** —— 示例值 `1.0.3` / `103`，**不是当前值**；
当前值一律以 `version.properties` 为准（截至 1.0.75 为 `1.0.75` / `177`），版本号逐版变化、不属于本节的契约。

```properties
versionName=1.0.3   # 工程版本号（semver：主.次.修订）
versionCode=103      # 工程版本码（整数，每次发布递增）
```

> **逐版变更说明在 [`VERSION-NOTES.md`](VERSION-NOTES.md)**（每个 `versionName` / `versionCode` 的来龙去脉都在那里）。
> `version.properties` 本体只留格式契约 + 3 个写法样板，**不再堆变更记录**；新增一版时先去那份文档加条目。
> 文件里除 `versionName=` / `versionCode=` 两行外都是 `#` 注释 —— `app/build.gradle.kts` 用
> `Properties.load()`、`tools/build/assemble.sh` 用 `grep '^versionName='`，两者都只看键值行。

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
Branchbase-{标准版本号}[-debug|-perfBeta].apk
```

| 类型 | 产物名 |
|------|--------|
| Debug（Beta） | `Branchbase-1.0.3-20260902-2226-a1b2c3d-debug.apk` |
| perfBeta（性能测试版：release 的性能特征 + beta 签名） | `Branchbase-1.0.3-20260902-2226-a1b2c3d-perfBeta.apk` |
| Release | `Branchbase-1.0.3-20260902-2226-a1b2c3d.apk` |

> 文件名后缀**只由 `variant.buildType` 决定**，与 `versionNameSuffix` 无关：`debug` 与 `perfBeta`
> 的 `versionName` 都带 `-Beta`（`app/build.gradle.kts:128`、`:159`），但产物名里分别是 `-debug` /
> `-perfBeta`，release 不带后缀。`perfBeta` 是「非 debuggable + 入库 beta 钥匙、可覆盖安装」那一档，
> 定位见 `app/build.gradle.kts:142-155`。

实现（`app/build.gradle.kts`）：

```kotlin
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val suffix = when (variant.buildType) {
                "debug" -> "-debug"
                "perfBeta" -> "-perfBeta"
                else -> ""
            }
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
| 只想打 UI 包、不想编 Rust | `tools/build/fetch-core.sh`：按 HEAD 的七位哈希找同名 Beta APK、解出 `.so` 并校验 AArch64 + JNI 符号；找不到时退到 `origin/beta`，但**先算 `core/` 与 `RustBridge.kt` 的差异**，对不上就拒绝写入（`--from-beta` 可显式接受风险，`--commit <hash>` 可指定已发布版本，显式指定时**不会**退回别的版本） |

> 本地验证顺序建议：`cargo test --lib`（纯逻辑）→ `cargo test --test deepseek_http`
> （回环服务验证真实 HTTP 请求形状，不联网、不需要 Key）→ 重建 `.so` → `assembleDebug`。

> **缺整个 `.so` 与「符号对不上」是两回事**（2026-11-24 的真机现场）：单个 native 调用找不到符号，
> 会被 `RustBridge` 的 `runCatching` 兜成「引擎不可用」（功能哑、不崩）；而 `RustBridge` 的
> **类初始化**里那行 `System.loadLibrary("branchbase_core")` 没有被兜 —— 缺文件时在
> `MainActivity.onCreate`（`setContent` **之前**）直接抛 `UnsatisfiedLinkError`，**启动即闪退**，
> 连应用内的日志页都起不来（现场只能靠 `adb logcat -b crash`）。而 `assembleDebug` 当时是**全绿**的：
> 构建成功不等于产物可运行。
>
> 所以 `app/build.gradle.kts` 加了两道**互补**的护栏：
>
> 1. **硬保证**：把 `app/src/main/jniLibs/arm64-v8a/libbranchbase_core.so` 声明成打包任务
>    （`packageDebug` / `packageRelease` / `packagePerfBeta`）的**输入文件** —— 缺文件时 Gradle
>    在执行前的输入校验就会失败，configuration cache 命中时也拦得住；
> 2. **可操作的提示**：配置期检查「本次调用是否会产出 APK」，是的话直接抛出中文错误，
>    给出两条恢复路径（取上一个 Beta 的产物 / `tools/build/build-core.sh` 重编），
>    并写明「拿旧 `.so` 顶替只会把崩点推到更晚」。
>
> ⚠️ **别改回 `doFirst {}`**：Kotlin DSL 脚本里的任务动作会被 configuration cache 序列化，
> 抓到的脚本对象不可序列化，Gradle 9 直接报 `cannot serialize Gradle script object references`（实测）。
> 编译与单测**不受影响** —— 改 UI 不该被迫先编 18 分钟 Rust，但产出一个装不上的 APK 必须当场拦下。

---

## 五、相关文件索引

| 文件 | 作用 |
|------|------|
| `version.properties` | 工程版本号 / 版本码（手动维护） |
| `app/build.gradle.kts` | 版本号标准化（优先读环境变量注入）+ APK 命名 + 签名配置 |
| `translate/build.gradle.kts` | 沉浸式翻译模块（纯 Kotlin library，无 Compose；模块边界与移除步骤写在文件头注释） |
| `joblogs/build.gradle.kts` | 作业日志模块（纯逻辑，无 Compose / 无 Android API；取数单飞合并 + 切段 + 缓存） |
| `core/src/translate/` | 翻译后端（`mod.rs` 选后端 + `mymemory.rs` / `deepseek.rs`），改这里要重建 `.so` |
| `core/tests/deepseek_http.rs` | 回环服务验证 DeepSeek 请求形状的集成测试（不联网、不需要 Key） |
| `gradle/libs.versions.toml` | AGP / Kotlin / Compose 等依赖版本 |
| `gradle/wrapper/gradle-wrapper.properties` | Gradle 9.1.0（distributionUrl 固定腾讯云镜像） |
| `gradle.properties` | 构建开关（build cache / 配置缓存 / proot 下关闭 aapt2 daemon） |
| `setup_android_env.sh` | 本地环境一键入口（委托 tools/env/ 三脚本，无镜像测速） |
| `tools/env/env-detect.sh` | 环境判定（纯判定，JSON 输出，零副作用） |
| `tools/env/env-prepare.sh` | 环境准备（预存资产 + 固定镜像，缺失才下载） |
| `tools/env/env-prepare-arm64-patches.sh` | ARM64(proot) 补充准备（lld / cargo-ndk / 自包含 aarch64 aapt2 + 全局 Gradle 绑定；非 aarch64 自动跳过，幂等） |
| `tools/env/env-persist.sh` | 环境持久化（~/.bashrc 追加一行 source env.rc） |
| `tools/env/env.rc` | 环境变量（进仓库） |
| `tools/env/mirrors.conf` | 固定镜像源 + cargo-ndk 锁定版本 |
| `tools/gradle/` | Gradle 发行版 zip 预存目录（工作流检索·预存·命中，zip 不入库） |
| `.gitignore` | 不入库的构建资产（`/tools/aapt2/`、`/tools/gradle/*.zip`） |
| `tools/build/build-core.sh` | 编译 Rust `.so`（本地 clang / CI cargo-ndk 自动切换） |
| `tools/build/assemble.sh` | gradlew assemble，注入关键版本参数 |
| `tools/build/warmup-aapt2.sh` | ARM64 AAPT2 替换后预热 |
| `.github/actions/setup-branchbase-env/` | 远程 CI 环境准备 composite action |
| `.github/workflows/build-beta.yml` | Beta 三步骤流水线（prepare→build→publish） |
| `.github/workflows/build-release.yml` | 正式版三步骤流水线 |
| `.github/workflows/ci.yml` | 每次 push/PR 的编译与单测（Android 四渠道编译 + JVM 单测 + Rust check/test/clippy；原 `build-core.yml` 已并入） |

---

## 六、编译流水线三步骤与环境脚本解耦（为什么这么定）

> 本节承接 `setup_android_env.sh:4` 提到的 `docs/build-optimization-proposal.md`：那份文档**从未进过
> 版本库**（`git log --all -- docs/build-optimization-proposal.md` 0 条），而它要讲的事实已散在
> README 构建 / CI 章节与各脚本头部，这里只补「为什么」，不重复那些清单。

**1. 两条运行面，一套契约。** 本地 proot（aarch64）与远程 CI（x86_64）的差异集中在两处：`.so` 怎么编
（本地走「系统 clang + NDK sysroot」`tools/build/build-core.sh:4-5`，CI 走 cargo-ndk `:6`，模式判定在 `:20-26`）、
环境怎么备（本地 `setup_android_env.sh:1`，CI `.github/actions/setup-branchbase-env/action.yml:2`）。
其余必须完全一致：版本真源只有 `version.properties`（`app/build.gradle.kts:26-32`）、标准版本号只由
`tools/build/assemble.sh:26-33` 算一次、产物名只由 `app/build.gradle.kts:185-198` 决定 —— 两个 workflow
调的是同一个 `assemble.sh`（`build-beta.yml:59`、`build-release.yml:51`）；运行面差异本身只收在两处判定里
（`build-core.sh:20-26` 的模式判定、`env-prepare-arm64-patches.sh:40-43` 的架构守卫）。

**2. 为什么删掉 ping 与分段测速。** 重构前的 `setup_android_env.sh` 有 636 行（`8c10ae7` 一次删掉 626 行，
提交后只剩 35 行）：`select_download_url()` 先给每个镜像**各下 512KB** 比速度（`curl --range 0-524287`），
全失败再退回 `ping -c 1 -W 2`。删它的三条理由 —— 探测本身要付流量与时间（下完即丢到 `/dev/null`）；
ping 通 ≠ HTTP 能下（ICMP 被禁或走 CDN 的镜像上两者没有稳定关系）；选路不确定 = 构建不可复现
（同一份代码两次准备可能走不同镜像，失败时归因不到具体哪条路）。现在镜像地址是仓库里的常量
（`tools/env/mirrors.conf:5-9`、`:11-15`），主镜像失败只退兜底、不再做任何探测
（`tools/env/env-prepare.sh:107-110`、`:79-80`），本地预存资产命中时完全不联网（`:99-105`）。

**3. 三脚本的边界、幂等与退出码。** 边界各管一段：只判定（`tools/env/env-detect.sh:2`「零副作用，不下载、
不写文件」）→ 只准备（`tools/env/env-prepare.sh:4`「与编译解耦，不含任何编译预热」）→ 只持久化
（`tools/env/env-persist.sh:2-4`），入口按序调（`setup_android_env.sh:24-36`）、② 可跳过（`:25-29`）。退出码就是契约：
判定用 0 / 1 / 2 表达三态（`env-detect.sh:5`、`:64-65`），入口只做**二元决策** —— 0 跳过准备，1 与 2 都去准备
（`setup_android_env.sh:25-29`）；JSON 明细只给人看，不参与分支（`env-detect.sh:50-62`）。幂等是「无条件
调用」的前提：准备命中即跳过（`env-prepare.sh:99-105`）、持久化按 marker 去重（`env-persist.sh:16-26`）。

**4. Gradle zip 为什么预存、为什么不入库。** `env-prepare.sh:99-114` 的优先级是「`tools/gradle/` 预存 →
本机 `$GRADLE_ROOT` 已有 → 固定镜像下载并**回存**」：回存让下一次与 CI 的 `actions/cache`
（`action.yml:93-101`）能命中，省掉重下 130MB；不入库是因为体积 —— `tools/gradle/.gitkeep:1-3` 写明 zip
约 130MB、走 git 会把仓库和克隆一起拖垮，故 `.gitignore:44` 只忽略 `/tools/gradle/*.zip`、留 `.gitkeep` 占位。

**5. ARM64 补充准备与 AAPT2 预热为什么各自剥离。** ARM64 补丁不能并进 `env-prepare.sh`：入口对它是
**无条件调用**的（`setup_android_env.sh:32-33`，「环境已就绪」只跳过 ②），所以「非 aarch64 直接退出 0」
这道守卫得它自己扛 —— `env-prepare-arm64-patches.sh:34-43` 写明：否则会在 x86_64 上装一堆用不上的包，
还把 aarch64 的 aapt2 写进全局 `~/.gradle/gradle.properties`；它的性质仍是幂等补缺（`:18`）。预热则是
「编译动作」不是「环境动作」：要真跑一次 `:app:processDebugResources`、失败只提示不影响后续
（`tools/build/warmup-aapt2.sh:18-21`），留在 setup 里会让「环境就绪」变成「必须先编译一次」，
而 `env-detect.sh` 的 7 项判定里根本没有它。同一支 aapt2 两边配置还相反：本地 `gradle.properties:31`
关掉 daemon（proot 起不来），CI 由 `assemble.sh:58-62` 显式打开。

**6. 版本参数注入与产物命名的衔接。** `assemble.sh:26-33` 算出唯一的 `standardVersion`（`TZ=Asia/Shanghai`），
`:35-40` 导出、`:43-51` 写 `$GITHUB_OUTPUT`。Gradle 侧只消费其中两个 —— `BRANCHBASE_BUILD_TIME`
（`app/build.gradle.kts:36-37`）与 `BRANCHBASE_GIT_HASH`（`:46-47`）；**必须由脚本注入**是因为配置阶段
不能起外部进程（`gradle.properties:17` 开着配置缓存），`git rev-parse` 会直接打挂它，IDE 直连构建只能
退化成 `unknown`（`app/build.gradle.kts:41-45`）；工程版本号 / 版本码始终直接读文件（`:26-32`）。
这个 `standardVersion` 同时进 `BuildConfig`（`:89-92`）与 APK 文件名（`:185-198`）；publish 阶段
**不重算**版本号，直接用 version / prepare job 的 output（`build-beta.yml` 的 `version` job、`build-release.yml` 的 `prepare` job）—— 所以 tag、APK 名、
发布页里的版本信息永远同源，不会出现「三个地方三个版本」。

---

## 七、流水线节点化：五段 + 矩阵合并打包（2026-11-24）

原先发版是一条 `build` 腿里的 `assembleX` **黑盒**：Kotlin 编译、资源合并、dex、签名、出 APK 全在一起，
失败时只能从一行 `FAILURE:` 猜是哪一段。现在拆成五段（beta 与 release 同一形状）：

| 段 | 做什么 | 交接物 |
|---|---|---|
| `version` / `prepare` | i18n 门禁 + **钉住**版本参数（时间戳 / 短哈希 / standardVersion） | job outputs |
| `core` | Rust 核心 `.so`（`core/**` 未改则命中缓存） | artifact |
| `compile` | 矩阵：`compileXKotlin` + `mergeXResources` + `mergeXJniLibFolders` | Gradle 构建缓存 |
| `package` | 矩阵：`assembleX`（dex / 资源链接 / 签名 / 出 APK） | APK + `signature*.txt` |
| `publish` | 汇总产物 / 发布页 / 代码变更整合 | Release |

**为什么这么拆：**

1. **定位**：编译错与打包错的 annotation 标题分别是 `compile::` / `package::`，不必再猜。
2. **签名材料范围最小化**：release 的 keystore 只在 `package(release)` 注入 ——
   `compile` 节点完全不接触签名材料。`app/build.gradle.kts` 的 release signingConfig 本就是
   「提供 `KEYSTORE_BASE64` 才创建」，所以 `compileReleaseKotlin` 不带 secret 也能跑。
3. **每段单独限时**：compile / package 各自 30 分钟，不会出现「整个 build 40 分钟超时、卡在哪不知道」。
4. **矩阵更完整**：CI 补上 `compileReleaseKotlin`（此前 release 变体只有发正式版时才第一次被编译）。

**三个必须知道的实现约束：**

- **跨 job 的交接靠 Gradle 构建缓存**（`setup-gradle` 在 job 结束时落盘，`package` 按同一批 task 输入复用）。
  ⚠️ 多个矩阵腿写同一个缓存键时是「先写者赢」，所以别的腿**可能拿不到缓存而重编** ——
  这是**性能**问题、不是正确性问题；要彻底共享得换远程构建缓存后端（S3/GCS），暂未做。
- `mergeXJniLibFolders` 依赖 `.so`，所以 **compile 节点也必须先下载 core 产物**，不能只放在 package。
- 任务名必须真实存在。改任务列表前先 dry-run 一次：
  `./gradlew --dry-run :app:compileXKotlin :app:mergeXResources :app:mergeXJniLibFolders`
  （release 变体同理，`:app:mergeReleaseResources` 等）。

**怎么验证这条流水线**：「测试性发版」—— push 到 `main` 触发 `build-beta.yml`，看五个 job 的
依赖关系与 annotation 标题是否符合预期；正式版再手动 `workflow_dispatch` 跑一次 `build-release.yml`。
