pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://repo1.maven.org/maven2")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://repo.huaweicloud.com/repository/gradle-plugin/")
        maven("https://repo.huaweicloud.com/repository/maven/")
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://repo.huaweicloud.com/repository/maven/")
    }
}

rootProject.name = "Branchbase"
include(":app")
// 代码编辑器独立模块（封装 Sora Editor）：
// 换库 / 升级 / 移除只动这个模块，主应用只依赖它的公开 API。
// 选型与版本见 docs/third-party-components.md §3，关于页也留了痕迹。
include(":editor")
 
