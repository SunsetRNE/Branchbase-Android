package com.branchbase

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.branchbase.ui.translate.LocalTranslateBubbleHost
import com.branchbase.ui.translate.TranslateBubble
import com.branchbase.ui.translate.TranslateBubbleHost
import com.branchbase.ui.theme.ThemeRuntime
import com.branchbase.core.RustBridge
import com.branchbase.ui.auth.LoginFlow
import com.branchbase.ui.log.FrameWatch
import com.branchbase.ui.log.LogManager
import com.branchbase.ui.settings.frameWatchEnabled
import com.branchbase.ui.log.Logger
import com.branchbase.ui.theme.BranchbaseTheme

/**
 * 应用入口 Activity。
 *
 * 同时负责接收 OAuth 深链回调（`branchbase://oauth/callback?code=...&state=...`），
 * 将授权码转交给登录流程。
 */
class MainActivity : ComponentActivity() {

    companion object {
        /** 深链回调携带的授权码（供 LoginFlow 读取一次） */
        @Volatile
        var pendingAuthCode: String? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初始化日志管理器（FileAppender 持久化到 branchbase.log）
        LogManager.init(applicationContext)
        Logger.ui("App 启动", "System")

        // 慢帧守望：把系统每帧下发的 FrameMetrics（与 `dumpsys gfxinfo framestats` 同源）
        // 里挑出的慢帧写进日志 —— 「切页那一下卡了多少毫秒、卡在哪一段」从此不用另开终端敲
        // dumpsys（那个 120 帧窗口 + dump 自身跑在主线程的两个硬伤见 FrameWatch 类注释）
        FrameWatch.install(this)
        // 默认值随编译通道（Beta 开、正式版关），用户可在「设置 → 关于与诊断 → 慢帧日志」改
        FrameWatch.setEnabled(frameWatchEnabled(applicationContext))

        // 清理超期短任务记录（后台，不阻塞启动）
        Thread {
            kotlinx.coroutines.runBlocking { com.branchbase.ui.task.TaskStore.prune(applicationContext) }
        }.start()

        // 账号健康检查（启动后跑一次；之后由用户在「设置 → 账号管理」手动检查）
        Thread {
            kotlinx.coroutines.runBlocking { com.branchbase.core.AccountChecks.checkAll(applicationContext) }
        }.start()

        // 当前账号头像预热：本地没有才拉（老用户升级后首次启动补齐，之后渲染零网络）。
        // 用 `avatarUrl`（快照缺失时回落会话里的 user.avatar_url）而不是裸 `avatar` ——
        // 老版本迁移来的账号只有会话、没有快照，用裸 avatar 会永远跳过预热。
        Thread {
            val ctx = applicationContext
            com.branchbase.core.AccountStore.current(ctx)?.let { acc ->
                val url = acc.avatarUrl
                if (url != null && !com.branchbase.core.AvatarCache.has(ctx, acc.login)) {
                    kotlinx.coroutines.runBlocking {
                        com.branchbase.core.AvatarCache.refresh(ctx, acc.login, url)
                    }
                }
            }
        }.start()

        // 初始化 git 引擎 TLS 证书信任（主线程同步：仅写文件 + 设环境变量，
        // 不触碰 libgit2；避免后台线程竞态与冷启动期 native 调用）
        val sslOk = RustBridge.gitInitSsl(cacheDir.absolutePath)
        Logger.ui(if (sslOk) "git TLS 证书初始化完成" else "git TLS 证书初始化失败", "SSL")

        // 解析 OAuth 深链回调
        handleDeepLink(intent)

        // 主题档位在启动时同步一次；之后由 ThemeRuntime 驱动（开关无需层层传参）
        ThemeRuntime.init(applicationContext)

        setContent {
            val themeMode by ThemeRuntime.mode.collectAsState()
            BranchbaseTheme(mode = themeMode) {
                // 沉浸式翻译的悬浮球 / 工具面板是**原生覆盖层**（见 ui/translate/TranslateBubble.kt）：
                // 会话在这里建一次，正文页（ReadmeWebView）通过 CompositionLocal 绑上来，
                // 控件画在最上层 —— 位置锚定窗口，不受正文 WebView 高度影响。
                val translateHost = remember { TranslateBubbleHost() }
                CompositionLocalProvider(LocalTranslateBubbleHost provides translateHost) {
                    Box(Modifier.fillMaxSize()) {
                        LoginFlow()
                        TranslateBubble(translateHost)
                    }
                }
            }
        }
    }

    /** 解析 branchbase://oauth/callback 深链，提取 code */
    private fun handleDeepLink(intent: android.content.Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "branchbase" && uri.host == "oauth" && uri.path == "/callback") {
            pendingAuthCode = uri.getQueryParameter("code")
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }
}