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
import com.branchbase.core.NetworkWatch
import com.branchbase.ui.auth.LoginFlow
import com.branchbase.ui.log.DeviceProfile
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

        // 日志管理器已在 BranchbaseApp.onCreate 里起过（那里才是启动路径的第一笔磁盘 IO）；
        // 这里留一次幂等兜底：万一 Application 的初始化被换掉，日志也不会整个丢掉。
        LogManager.init(applicationContext)
        Logger.ui("App 启动", "System")

        // 设备档案：机型 / 屏幕与刷新率 / 内存核数 / 动画缩放 / 不保留活动 / 省电模式 …
        // 别人发日志过来时，「同样的 120ms 慢帧」在旗舰机与低端机上结论完全不同 ——
        // 这几项直接决定怎么读那些数字（阈值按 60Hz 写死、动画被调小会让动效验收失真）。
        // 只进日志、不加设置页行；每次启动记一组。
        DeviceProfile.log(this)

        // 慢帧守望：把系统每帧下发的 FrameMetrics（与 `dumpsys gfxinfo framestats` 同源）
        // 里挑出的慢帧写进日志 —— 「切页那一下卡了多少毫秒、卡在哪一段」从此不用另开终端敲
        // dumpsys（那个 120 帧窗口 + dump 自身跑在主线程的两个硬伤见 FrameWatch 类注释）
        FrameWatch.install(this)
        // 默认值随编译通道（Beta 开、正式版关），用户可在「设置 → 关于与诊断 → 慢帧日志」改
        Logger.startupOnce("prefs-first-load", "启动 ▸ 首选项首次加载（整份 XML 在主线程解析）")
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
        // 这一行也是阶段标记：`gitInitSsl` 会首次触碰 RustBridge → 类初始化 → 加载 11MB .so，
        // 而上面的后台线程（AccountChecks / 头像预热）可能正拿着同一把类初始化锁。
        Logger.startupOnce("jni-and-cert", "启动 ▸ JNI 库与 git 证书（loadLibrary + 190KB CA）")
        val sslOk = RustBridge.gitInitSsl(cacheDir.absolutePath)
        Logger.ui(if (sslOk) "git TLS 证书初始化完成" else "git TLS 证书初始化失败", "SSL")

        // 解析 OAuth 深链回调
        handleDeepLink(intent)

        // 主题档位在启动时同步一次；之后由 ThemeRuntime 驱动（开关无需层层传参）
        ThemeRuntime.init(applicationContext)

        // 阶段标记：从这一行到首帧之间，跑的是「恢复会话 → 登记账号 → 首页首次组合 + 首帧取数」。
        // 真机数据（14 次启动）里，这一段与慢帧「等待」段的相关系数最高（r=0.957），
        // 所以它必须能出现在慢帧注脚里，否则下一轮还是只能猜。
        Logger.startupOnce("first-composition", "启动 ▸ 首次组合：恢复会话 / 登记账号 / 首页取数")
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

    /**
     * 回到前台补一次远端可达性复核。
     *
     * 正常路径由 `NetworkWatch` 的网络回调覆盖；这里兜的是「回调没投到（后台冻结 / 平台差异）」
     * 的情况 —— 否则用户从 VPN 客户端切回来时，App 还带着接入前的结论。
     */
    override fun onResume() {
        super.onResume()
        NetworkWatch.refresh(applicationContext)
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