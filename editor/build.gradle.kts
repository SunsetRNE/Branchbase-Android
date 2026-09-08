plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

/**
 * :editor —— 代码编辑器独立模块。
 *
 * 设计约束（对齐「不盲加第三方、拆包速切、留痕可删」的要求）：
 * 1. 第三方编辑器库**只在这里**声明依赖，主应用不直接依赖它；
 * 2. 对外只暴露一个 Compose 组件（见 BranchbaseCodeEditor.kt），换库时改这一个模块；
 * 3. 移除时：删本模块 + settings.gradle.kts 的 include + 关于页那一行痕迹即可。
 */
android {
    namespace = "com.branchbase.editor"
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

    // Sora Editor（可编辑 + TextMate 语法高亮 + 行号）
    implementation(libs.sora.editor)
    implementation(libs.sora.editor.textmate)
}
