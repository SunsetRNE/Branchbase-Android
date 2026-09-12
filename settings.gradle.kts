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
// 选型与版本只在 gradle/libs.versions.toml 维护，关于页也留了痕迹（EditorModuleInfo）。
include(":editor")

// 沉浸式翻译独立模块：
// 「设置 / 分片 / 占位符保护 / 缓存 / 调度重试 / 页面脚本」都收进这一个模块，
// 翻译后端以接口注入（:app 提供 Rust 实现），换服务商或整体移除只动它。
include(":translate")

// 内建下载模块：
// 「下载引擎（断点 / 进度 / 重定向鉴权）/ 前台服务 / 通知进度 / 通知权限 / 安装与打开文件」
// 收进这一个模块；凭据、通知小图标由 :app 注入，模块内不引用任何 App 类型。
// 移除步骤：删本目录 + 本行 include + :app 的依赖与 DownloaderRuntime.install 一行。
include(":downloader")

// 图片查看器独立模块（正文页点图放大查看）：
// 只认「URL + 可选请求头 + 关闭回调」，不认识 GitHub / Token；
// 正文渲染器（ReadmeWebView）用它弹一个全屏可缩放查看器。
// 移除步骤：删本目录 + 本行 include + :app 的依赖 + ReadmeWebView 里那一处调用。
include(":imageviewer")
