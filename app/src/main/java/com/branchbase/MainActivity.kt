package com.branchbase

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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