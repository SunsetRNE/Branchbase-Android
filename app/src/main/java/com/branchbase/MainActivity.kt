package com.branchbase

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.branchbase.core.RustBridge
import com.branchbase.ui.auth.LoginFlow
import com.branchbase.ui.log.LogManager
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

        // 清理超期短任务记录（后台，不阻塞启动）
        Thread {
            kotlinx.coroutines.runBlocking { com.branchbase.ui.task.TaskStore.prune(applicationContext) }
        }.start()

        // 初始化 git 引擎 TLS 证书信任（主线程同步：仅写文件 + 设环境变量，
        // 不触碰 libgit2；避免后台线程竞态与冷启动期 native 调用）
        val sslOk = RustBridge.gitInitSsl(cacheDir.absolutePath)
        Logger.ui(if (sslOk) "git TLS 证书初始化完成" else "git TLS 证书初始化失败", "SSL")

        // 解析 OAuth 深链回调
        handleDeepLink(intent)

        setContent {
            BranchbaseTheme {
                LoginFlow()
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