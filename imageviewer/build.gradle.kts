plugins {
    // AGP 9 内置 Kotlin 支持 + Compose 编译器插件（与 :editor 同款组合，不需要再写 buildFeatures.compose）
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

/**
 * :imageviewer —— 全屏图片查看器独立模块（可缩放 / 可平移 / 下拉关闭）。
 *
 * 为什么独立成模块：
 * 1. 正文页（README / issue 主帖）里的图片以前只有两条路 —— 外开浏览器（移动端既不能双指缩放、
 *    又要重新登录），或者干脆放大不了。查看器属于「可替换的能力」，不该混在正文渲染器里；
 * 2. 依赖方向单向：`:app → :imageviewer`。模块只认「URL + 可选请求头 + 关闭回调」，
 *    不认识 GitHub、不认识仓库、不认识 Token（工作由 :app 注入）；
 * 3. 移除步骤：删本目录 + settings.gradle.kts 的 include + :app 的依赖，
 *    再把 `ReadmeWebView` 里那一处 `ImageViewerDialog` 调用删掉即可。
 *
 * 对外只有一个 Compose 组件（[com.branchbase.imageviewer.ImageViewerDialog]），
 * 手势/边界的浮点运算收在可单测的 [com.branchbase.imageviewer.ImageViewerMath] 里。
 */
android {
    namespace = "com.branchbase.imageviewer"
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
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.material3)
    // 只用 Icons.Filled.Close（图标核心包，不引 extended 的几 MB）
    implementation(libs.androidx.material.icons.core)
    // 图片加载复用主应用配置的 Coil 单例（内存/磁盘缓存、SVG 支持都在 :app 里声明）
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
}
