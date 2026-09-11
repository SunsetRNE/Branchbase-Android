plugins {
    // AGP 9 内置 Kotlin 支持：**不要**再显式应用 org.jetbrains.kotlin.android
    // （应用了会直接报「no longer required since AGP 9.0」）
    alias(libs.plugins.android.library)
}

/**
 * :translate —— 沉浸式翻译独立模块。
 *
 * 设计约束（对齐 :editor 的做法：拆包速切、留痕可删）：
 * 1. **依赖方向单向**：`:app → :translate`。模块内不引用任何 App 内类型，
 *    翻译后端由 :app 通过 [com.branchbase.translate.TranslateEngine] 注入；
 * 2. **换服务商只改一处**：现在后端是 Rust 侧的 MyMemory（`core/src/translate.rs`），
 *    要换成 DeepL / 大模型，只需要 :app 换一个 `TranslateEngine` 实现；
 * 3. **移除步骤**：删本模块目录 + settings.gradle.kts 的 include + :app 的依赖与
 *    `TranslateRuntime.install` 一行即可（页面脚本/CSS 随模块一起被删掉）。
 *
 * 模块内容分三层：
 * - 纯逻辑（无 Android 依赖，可 JVM 单测）：语言模型 / 文本策略 / 分片 / 占位符保护 /
 *   LRU 与磁盘缓存 / 重试调度器 / 门面 Translator；
 * - Android 适配：SharedPreferences 设置、assets 页面脚本装载、WebView JS 桥；
 * - 页面侧资产：`src/main/assets/translate/` 下的 CSS 与四个脚本，由 TranslatePage 按序拼接。
 */
android {
    namespace = "com.branchbase.translate"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    // JVM 单测里 android.jar 的 org.json 是空壳，注入真实实现（与 :app 同一约定）
    testImplementation(libs.org.json)
}
