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

// 七位 git 哈希（优先读注入环境变量，回退运行时计算，再回退 unknown）
fun gitShortHash(): String =
    System.getenv("BRANCHBASE_GIT_HASH") ?: try {
        ProcessBuilder("git", "rev-parse", "--short=7", "HEAD")
            .directory(rootProject.projectDir)
            .redirectErrorStream(true)
            .start()
            .inputStream.bufferedReader().readText().trim().ifBlank { "unknown" }
    } catch (e: Exception) { "unknown" }

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
    compileSdk = 35

    defaultConfig {
        applicationId = "com.branchbase"
        minSdk = 24
        targetSdk = 35
        versionCode = engineeringVersionCode
        versionName = standardVersion

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
        debug {
            signingConfig = signingConfigs.getByName("beta")
            // Beta 版：标准版本号尾部附加 "-Beta"
            versionNameSuffix = "-Beta"
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
        }
    }
    compileOptions {
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

// APK 输出文件名：Branchbase-工程版本号-年月日-时分-七位哈希[-debug].apk
// 例：Branchbase-1.0.3-20260902-2226-a1b2c3d-debug.apk / Branchbase-1.0.3-20260902-2226-a1b2c3d.apk
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val suffix = if (variant.buildType == "debug") "-debug" else ""
            (output as VariantOutputImpl).outputFileName.set("Branchbase-${standardVersion}${suffix}.apk")
        }
    }
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
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
