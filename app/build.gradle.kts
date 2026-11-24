import com.android.build.api.variant.impl.VariantOutputImpl
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// 从 local.properties 读取 client secret（本地私有，不进 git）
val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.exists()) {
    localPropsFile.inputStream().use { localProps.load(it) }
}
val branchbaseClientSecret = localProps.getProperty("branchbase.client.secret") ?: ""
val githubRedirectUri = localProps.getProperty("github.redirect.uri") ?: "branchbase://oauth/callback"

// ── 版本号标准化 ──
// 工程版本号（version.properties，手动维护）
val versionProps = Properties()
val versionPropsFile = rootProject.file("version.properties")
if (versionPropsFile.exists()) {
    versionPropsFile.inputStream().use { versionProps.load(it) }
}
val engineeringVersion = versionProps.getProperty("versionName") ?: "1.0.0"
val engineeringVersionCode = (versionProps.getProperty("versionCode") ?: "1").toIntOrNull() ?: 1

// 构建时间（年月日-时分，固定 Asia/Shanghai 时区，避免本地与 CI 时区差异）
// 优先读取 tools/build/assemble.sh 注入的环境变量，保证跨阶段版本参数一致
fun buildTimestamp(): String =
    System.getenv("BRANCHBASE_BUILD_TIME") ?: LocalDateTime.now(ZoneId.of("Asia/Shanghai"))
        .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))

// 七位 git 哈希。
//
// 配置缓存兼容性：配置阶段**不能启动外部进程**（ProcessBuilder 会让
// `org.gradle.configuration-cache=true` 直接失败）。CI 与本地构建脚本
// （tools/build/assemble.sh）都会注入 BRANCHBASE_GIT_HASH；
// 直接从 IDE 构建时退化为 unknown —— 只影响 BuildConfig 的展示值。
fun gitShortHash(): String =
    System.getenv("BRANCHBASE_GIT_HASH")?.takeIf { it.isNotBlank() } ?: "unknown"

val buildTime = buildTimestamp()
val gitHash = gitShortHash()
// 标准版本号 = 工程版本号-年月日-时分-七位哈希
val standardVersion = "$engineeringVersion-$buildTime-$gitHash"

// ── Release 签名（从 CI Secrets 环境变量注入） ──
val releaseKeystoreBase64 = System.getenv("KEYSTORE_BASE64")
val releaseStorePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
val releaseKeyAlias = System.getenv("KEY_ALIAS") ?: ""
val releaseKeyPassword = System.getenv("KEY_PASSWORD") ?: ""

// 解码 base64 keystore 到临时文件（仅当 CI 提供 KEYSTORE_BASE64 时生效）
val releaseKeystoreFile: File? = releaseKeystoreBase64?.let { b64 ->
    val f = File(rootProject.layout.buildDirectory.get().asFile, "release.keystore")
    f.parentFile?.mkdirs()
    f.writeBytes(Base64.getDecoder().decode(b64))
    f
}

android {
    namespace = "com.branchbase"
    compileSdk = 36

    /**
     * 语言资源目录即语言清单：`values-en/`、`values-ja/` … 存在哪些目录，
     * AGP 就自动生成包含哪些 locale 的 `locales_config.xml`，并把 `android:localeConfig`
     * 指过去（manifest 里不写这一条，避免手写清单与资源目录分家）。
     *
     * 为什么用它 —— 把「加一种语言」的成本压到**加一个目录**：
     * - 运行时 `LocaleConfig(context).supportedLocales` 读它，语言页据此渲染（不必维护语言列表）；
     * - 系统设置里的「应用语言」页也读它（API 33+），所以自带一份由系统绘制的入口；
     * - 清单与资源目录由构建保证一致，不会出现「列了某种语言却没有对应文案」。
     *
     * 注意：只有 API 33+ 认这份清单。低于 33 的系统忽略它，App 侧的语言入口也整行不出现
     * （见 `ui/settings/LanguageScreen.kt` 的显示条件）。
     */
    androidResources {
        generateLocaleConfig = true
    }

    defaultConfig {
        applicationId = "com.branchbase"
        minSdk = 24
        targetSdk = 35
        versionCode = engineeringVersionCode
        versionName = standardVersion
        // 渠道标签（不同包名的渠道并排安装时，桌面/最近任务里能区分）。默认 = 正式名。
        manifestPlaceholders["appLabel"] = "Branchbase"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // 注入 client secret（来自 local.properties，不硬编码）
        buildConfigField("String", "BRANCHBASE_CLIENT_SECRET", "\"$branchbaseClientSecret\"")
        // 注入 redirect_uri（来自 local.properties，便于调试时调整）
        buildConfigField("String", "GITHUB_REDIRECT_URI", "\"$githubRedirectUri\"")
        // 注入版本号（工程版本号 + 标准版本号 + 构建时间 + 七位哈希）
        buildConfigField("String", "ENGINEERING_VERSION", "\"$engineeringVersion\"")
        buildConfigField("String", "STANDARD_VERSION", "\"$standardVersion\"")
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
        buildConfigField("String", "GIT_HASH", "\"$gitHash\"")
    }

    signingConfigs {
        // 固定 debug 签名（入库），确保本地与 CI 的 Beta 签名一致，可覆盖安装
        create("beta") {
            storeFile = rootProject.file("app/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Release 签名（从 CI Secrets 注入，仅在提供 KEYSTORE_BASE64 时生效）
        if (releaseKeystoreFile != null) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        /**
         * 慢帧日志（`FrameWatch`）的**编译通道默认值**。
         *
         * 需求：正式编译（`build-release.yml` → `assembleRelease`）**默认关闭**，
         * Beta（`build-beta.yml` → `assembleDebug` / `assemblePerfBeta`）**默认开启**。
         *
         * 为什么按通道分：慢帧日志是**诊断仪表**，会给日志文件持续增量，正式版用户不该
         * 无缘无故多出一份常年写入的文件；而 Beta 的全部意义就是拿来测。运行时开关
         * （设置 → 关于与诊断 → 慢帧日志）永远保留，所以正式版用户可以临时打开来抓一次。
         */
        debug {
            signingConfig = signingConfigs.getByName("beta")
            // 全面测试矩阵（2026-11-24 起）：五个渠道**各自独立的 applicationId**，
            // 同一台设备可并排安装、数据与登录态互相隔离。Beta 不再占用正式版的包名。
            applicationIdSuffix = ".beta"
            manifestPlaceholders["appLabel"] = "Branchbase beta"
            // Beta 版：标准版本号尾部附加 "-Beta"
            versionNameSuffix = "-Beta"
            buildConfigField("boolean", "FRAME_WATCH_DEFAULT", "true")
        }
        release {
            if (releaseKeystoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("boolean", "FRAME_WATCH_DEFAULT", "false")
        }
        /**
         * 性能测试版：**release 的性能特征 + beta 的签名**。
         *
         * 为什么需要它 —— 在此之前想评一次动效/滚动性能只能二选一：
         * - beta（`assembleDebug` + 入库的 beta 钥匙）：能覆盖安装，但 `isDebuggable=true`
         *   → ART 几乎不做 AOT、也拿不到依赖自带的 baseline profile，**测出来的不是真实性能**；
         * - 正式版（`assembleRelease` + CI secrets 里的 release 钥匙）：性能真实，
         *   但签名与 beta 不同 → **必须卸载才能装**，登录态与缓存全清，首轮都是冷缓存，
         *   同样没法做「改前 / 改后」对比。
         *
         * 这个变体取两者交集：`initWith(release)` 拿到 `isDebuggable=false`（ART 才愿意 AOT、
         * AGP 才会把 baseline profile 合进 APK），签名却沿用入库的固定 beta 钥匙 —— 覆盖安装即可。
         */
        /**
         * **全面测试矩阵：五个渠道、五个独立包名**（2026-11-24 起）。
         *
         * | 渠道 | applicationId | 签名 | 用途 |
         * |---|---|---|---|
         * | 正式版 | `com.branchbase` | 正式钥匙 | 用户实际安装的稳定版 |
         * | Beta | `com.branchbase.beta` | beta 钥匙 | 预发布，push main 自动出 |
         * | Perf | `com.branchbase.perf` | beta 钥匙 | 性能诊断（非 debuggable + beta 签名） |
         * | Dev | `com.branchbase.dev` | beta 钥匙 | 自己用的开发包 |
         * | Test | `com.branchbase.test` | beta 钥匙 | 给别人试的测试包 |
         *
         * 全部**可以并排安装**：`applicationIdSuffix` 不同 → 数据/登录态/通知互相隔离，
         * 不必为了试一个渠道先卸载另一个。正式版保留 `com.branchbase`，是为了不让
         * 已经装了稳定版的用户「换成一个新应用」。
         *
         * - 标签：`Branchbase beta` / `Branchbase perf` / `Branchbase dev` / `Branchbase test`
         *   （manifest 的 `${appLabel}` 占位符）；
         * - 依赖的 library 模块只有 debug/release 两套变体 → matchingFallbacks 指回 debug/release
         *   （dev/test 本质是 debug 的可区分副本）。
         */
        create("dev") {
            initWith(getByName("debug"))
            // initWith 会把 debug 的签名与 FRAME_WATCH_DEFAULT 一起复制过来，这里只改渠道差异
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            manifestPlaceholders["appLabel"] = "Branchbase dev"
            matchingFallbacks += listOf("debug")
        }
        /**
         * 对外是 **test 渠道**（包名 `com.branchbase.test`、标签 `Branchbase test`、APK `-test`），
         * 但内部 build type 只能叫 `trial`：AGP **保留 `test` 前缀**给测试组件
         * （`BuildType names cannot start with 'test'`，实测会直接构建失败）。
         * 所以"渠道名"由 `applicationIdSuffix` / 标签 / APK 后缀表达，build type 名只是实现细节。
         */
        create("trial") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".test"
            versionNameSuffix = "-test"
            manifestPlaceholders["appLabel"] = "Branchbase test"
            matchingFallbacks += listOf("debug")
        }

        create("perfBeta") {
            initWith(getByName("release"))
            // ⚠️ 必须在 initWith 之后覆盖：release 的 signingConfig 与 buildConfigField 会被一起复制过来
            signingConfig = signingConfigs.getByName("beta")
            versionNameSuffix = "-Beta"
            isMinifyEnabled = false
            // 性能测试版属于 Beta 通道：慢帧日志默认开启（见 buildTypes 顶部的说明）
            buildConfigField("boolean", "FRAME_WATCH_DEFAULT", "true")
            // 独立包名：性能包与 Beta 包并排安装，才能在同一台机器上做「改前 / 改后」对比
            applicationIdSuffix = ".perf"
            manifestPlaceholders["appLabel"] = "Branchbase perf"
            // 依赖的 library 模块只有 debug/release 两套变体，这里指回去
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        // Sora Editor 的 language-textmate 要求 app 启用 core library desugaring
        // （AAR 元数据检查会拦下来，见 :app:checkDebugAarMetadata）
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// APK 输出文件名：Branchbase-工程版本号-年月日-时分-七位哈希[-debug|-perfBeta].apk
// 例：Branchbase-1.0.3-20260902-2226-a1b2c3d-debug.apk / Branchbase-1.0.3-20260902-2226-a1b2c3d.apk
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            // 各个变体要在 dist/ 里能分辨：release 不带后缀，其余带自己的变体名
            val suffix = when (variant.buildType) {
                "debug" -> "-debug"
                "perfBeta" -> "-perfBeta"
                "dev" -> "-dev"
                // 渠道对外叫 test（内部 build type 是 trial，见上面的注释）
                "trial" -> "-test"
                else -> ""
            }
            (output as VariantOutputImpl).outputFileName.set("Branchbase-${standardVersion}${suffix}.apk")
        }
    }
}

/**
 * 打包护栏：arm64 的 Rust 核心（`libbranchbase_core.so`）必须在场。
 *
 * ## 为什么需要它（2026-11-24 的真实现场）
 *
 * `main` 分支按约定**不留 `.so`**（见 `docs/specs/BUILD-NOTES.md` §四：CI 交叉编译后把产物
 * force-push 到 `beta` 分支，main 只放源码）。于是从干净克隆直接 `assembleDebug` 会产出一个
 * **能装、能启动、但一进 `MainActivity` 就崩**的 APK：
 *
 * ```
 * Caused by: java.lang.UnsatisfiedLinkError: dlopen failed: library "libbranchbase_core.so" not found
 *     at com.branchbase.core.RustBridge.<clinit>(RustBridge.kt:39)
 *     at com.branchbase.MainActivity.onCreate(MainActivity.kt:118)
 * ```
 *
 * 因为 `RustBridge` 的类初始化第一句就是 `System.loadLibrary("branchbase_core")`，
 * 而 `MainActivity.onCreate` 在 `setContent` **之前**就碰它 —— `Application` 都走不完，
 * 应用内的日志页自然也起不来（现场只能靠 `adb logcat -b crash`）。
 * 那天 `assembleDebug` 是**全绿**的：构建成功不等于产物可运行，这道检查补的就是这个空档。
 *
 * ## 两道互补的机制（都不能用 `doFirst`）
 *
 * 1. **硬保证**：把 `.so` 声明成打包任务的**输入文件**。缺文件时 Gradle 在任务执行前自己就会
 *    失败 —— 这一层不受 configuration cache 影响（配置期被跳过时，输入快照照样会校验）；
 * 2. **可操作的提示**：配置期检查「这次是不是要打 APK」，是的话直接抛一条中文错误，
 *    给出两条恢复路径。
 *
 * ⚠️ **不要改回 `doFirst {}`**：Kotlin DSL 脚本里的任务动作会被 configuration cache 序列化，
 * `doFirst` 抓到的脚本对象不可序列化，Gradle 9 会直接报
 * `cannot serialize Gradle script object references`（实测）。上面这两道机制都不进任务动作，
 * 所以与 configuration cache 兼容。
 *
 * ## 为什么只拦打包
 *
 * 改 Kotlin / UI、跑单测都不该被迫先编一遍 Rust（`compileDebugKotlin` / `testDebugUnitTest`
 * 不受影响）；但**产出一个装不上的 APK** 必须当场拦下 —— 那才是会流到用户手机上的东西。
 */
val coreSoFile = layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libbranchbase_core.so").asFile
val apkPackagingTaskNames = setOf("packageDebug", "packageRelease", "packagePerfBeta")

// ① 硬保证：`.so` 是打包任务的输入。缺文件 ⇒ Gradle 的执行前校验必然失败。
tasks.configureEach {
    if (name in apkPackagingTaskNames) {
        inputs.file(coreSoFile).withPropertyName("branchbaseCoreSo")
    }
}

// ② 可操作的提示：只在「本次调用会产出一个 APK」时检查（配置期，不进任务动作）。
// 读文件存在性会被 configuration cache 记进指纹：文件被删/放回都会让配置正确失效。
fun requestsApkPackaging(taskPath: String): Boolean {
    val name = taskPath.substringAfterLast(':')
    if (name.contains("AndroidTest") || name.contains("UnitTest")) return false
    // 「会产出 APK/AAB」的入口：assemble* / bundle* / install* / build。
    // AndroidTest / UnitTest 变体不需要这个 .so（测试 APK 不打包应用的原生库），前面已排除。
    return name == "build" ||
        name.startsWith("assemble") || name.startsWith("bundle") || name.startsWith("install") ||
        name in apkPackagingTaskNames
}

if (gradle.startParameter.taskNames.any(::requestsApkPackaging) &&
    (!coreSoFile.exists() || coreSoFile.length() == 0L)
) {
    throw GradleException(
        """
        |缺少 Rust 核心（或文件是 0 字节）：${coreSoFile.path}
        |
        |没有它的 APK 一启动就崩（RustBridge.<clinit> 的 System.loadLibrary）：
        |  UnsatisfiedLinkError: dlopen failed: library "libbranchbase_core.so" not found
        |
        |恢复（二选一）：
        |  1) 只改了 Kotlin / UI —— 一条命令取同 commit 的产物（秒级）：
        |       tools/build/fetch-core.sh
        |     它会按 HEAD 的哈希找同名 Beta 包并解出 .so；找不到时退到 origin/beta 那份，
        |     但会先算 core/ 与 RustBridge.kt 的差异 —— 对不上就拒绝写入（不悄悄换版本）。
        |  2) 改了 core/ 或 RustBridge.kt —— 必须重编：
        |       tools/build/build-core.sh        （本地需要 NDK sysroot，ARM64 约 18 分钟）
        |
        |.so 必须与 Kotlin 侧的 JNI 声明同代；拿旧 .so 顶替只会把崩点推到更晚。
        """.trimMargin(),
    )
}

// Force use of ARM64 binaries for AAPT2 in Proot environment
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "com.android.tools.build" && requested.name == "aapt2") {
            useTarget("com.android.tools.build:aapt2:${'$'}{requested.version}:linux-aarch64")
        }
    }
}

dependencies {

    // 代码编辑器独立模块（封装 Sora Editor）—— 换库 / 升级 / 移除只动 :editor
    implementation(project(":editor"))

    // 沉浸式翻译独立模块（设置 / 分片 / 占位符保护 / 缓存 / 调度 / 页面脚本）
    // :app 只提供后端实现（RustTranslateEngine）并在 Application 里 install 一次
    implementation(project(":translate"))

    // 内建下载独立模块（下载引擎 / 前台服务 / 通知进度 / 通知权限 / 安装与打开文件）
    // :app 只注入凭据与小图标（DownloaderRuntime.install 一行）
    implementation(project(":downloader"))

    // 图片查看器独立模块（正文页点图放大：双指缩放 / 双击 / 下拉关闭）
    implementation(project(":imageviewer"))

    // 作业日志独立模块（取数单飞合并 / 分组切段 / 内存与磁盘缓存）
    // :app 只注入日志来源（RustBridge）与缓存适配（PageCache），见 JobLogWiring.kt
    implementation(project(":joblogs"))

    // core library desugaring：Sora Editor 的 language-textmate 要求
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    // 液态玻璃（backdrop 采样 + AGSL 折射）：只被 ui/navigation 的玻璃栏用；
    // 选型与版本理由见 gradle/libs.versions.toml 的 kyantBackdrop，来源登记在 THIRD-PARTY-NOTICES.md
    implementation(libs.kyant.backdrop)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    // JVM 单测里 android.jar 的 org.json 是空壳（方法抛 not mocked），补真实实现
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
