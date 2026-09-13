plugins {
    // AGP 9 内置 Kotlin 支持：**不要**再显式应用 org.jetbrains.kotlin.android
    // （应用了会直接报「no longer required since AGP 9.0」）
    alias(libs.plugins.android.library)
}

/**
 * :joblogs —— GitHub Actions **作业日志**独立模块。
 *
 * 为什么独立成模块：
 * 1. 以前「取作业日志」这件事在 :app 里写了两遍 —— 运行详情页（懒加载 + 页内内存缓存）
 *    与 Job 详情页（PageCache 磁盘缓存），日志地址、失败判定、分段逻辑各一份；
 *    两份实现的差异（一个按步骤分段、一个整段直出）只是渲染需要，取数过程完全同源；
 * 2. 依赖方向单向：`:app → :joblogs`。模块只认「jobId → 日志原文」这一个来源，
 *    不认识 GitHub、不认识 Token、不认识 RustBridge、不认识 Room / PageCache
 *    （日志来源与缓存都由 :app 注入）；
 * 3. 移除步骤：删本目录 + settings.gradle.kts 的 include + :app 的依赖，
 *    再把 `JobLogWiring.kt` 与两处 store 调用删掉即可（没有清单、权限、资源要清理）。
 *
 * 模块内只有纯逻辑（无 Android API、无 Compose）：分段解析、内存缓存（按字符数淘汰）、
 * 同一个 jobId 的**在飞请求合并**。并发用协程的结构化并发表达，不引入裸线程 ——
 * 日志的可感开销是「下载整份日志 + 逐行切段」，前者是网络等待、后者已经在
 * [kotlinx.coroutines.Dispatchers.Default] 上，加线程数不会更快。
 */
android {
    namespace = "com.branchbase.joblogs"
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
    // 在飞请求合并 / 切段下沉到 Default 调度器都要用（:translate 同款做法：不靠传递依赖）
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
