plugins {
    // AGP 9 内置 Kotlin 支持：**不要**再显式应用 org.jetbrains.kotlin.android
    // （应用了会直接报「no longer required since AGP 9.0」）
    alias(libs.plugins.android.library)
}

/**
 * :downloader —— 内建下载模块。
 *
 * 设计约束（对齐 :editor / :translate 的做法：拆包速切、留痕可删）：
 * 1. **依赖方向单向**：`:app → :downloader`。模块内不引用任何 App 类型 ——
 *    凭据（Authorization）、通知小图标、User-Agent 都由 :app 在
 *    [com.branchbase.downloader.DownloaderRuntime.install] 时注入；
 * 2. **后台存活靠前台服务**（`DownloadService`，`foregroundServiceType="dataSync"`），
 *    通知进度与下载逻辑同源，退出应用后仍在跑；
 * 3. **通知权限与下载解耦**：POST_NOTIFICATIONS 被拒时下载照常进行（只是没有通知），
 *    因此权限申请（[com.branchbase.downloader.NotificationPermission]）不参与下载主流程；
 * 4. **可单测的都在纯函数里**：文件名净化 / 重定向解析 / 进度节流策略见 `src/test`。
 *
 * 权限与服务声明都写在**本模块的 AndroidManifest.xml** 里（清单会合并进 :app），
 * 这样「加/删这个模块」不需要再动 :app 的清单。
 */
android {
    namespace = "com.branchbase.downloader"
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
    implementation(libs.androidx.core.ktx)   // FileProvider / NotificationCompat / ContextCompat
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
