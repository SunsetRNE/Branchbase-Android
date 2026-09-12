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
 *
 * 版本号只有**一个真源**：`gradle/libs.versions.toml` 的 `soraEditor`。
 * 这里把它注入 BuildConfig，`EditorModuleInfo.VERSION` 直接读注入值 ——
 * 手写常量一旦忘记同步，编译与测试都不会报错，但关于页会开始显示错误的版本号。
 */
android {
    namespace = "com.branchbase.editor"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        // 把目录里的上游版本注入成 BuildConfig，供 EditorModuleInfo 展示（真源见文件头注释）
        buildConfigField("String", "SORA_VERSION", "\"${libs.versions.soraEditor.get()}\"")
    }

    buildFeatures {
        buildConfig = true
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

    // 配色的「浅色深黑 / 深色亮白」契约靠纯数据单测钉住（EditorPaletteTest）
    testImplementation(libs.junit)
}
