package com.branchbase.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.branchbase.cache.RepoPrefetcher
import com.branchbase.ui.log.Logger
import com.branchbase.ui.notification.NotificationPrefetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 远端可达性守望：盯住默认网络（含 VPN 的接入 / 断开），在**实质跃迁**发生时
 * 丢掉旧路径的结论并重新探测远端。
 *
 * ## 它修的是什么
 *
 * 「先没开 VPN 打开 App，再开 VPN，远端还是打不开」有两条独立成因，缺一不可：
 *
 * 1. **连接池绑在旧路径上**：Rust 侧 `shared_http()` 是进程级单例，池里的连接是在切换前的
 *    网络上握手完成的。VPN 挂上后默认网络换了，那些连接既不可用也不会立刻被丢弃 ——
 *    后续请求继续复用它们，一路超时（症状就是「换了网络也一直转圈」）。
 *    → 跃迁时调 [RustBridge.resetHttpClient] 让下一次请求重建连接。
 * 2. **启动那次的负面结论被记账**：账号健康检查只在 `MainActivity` 启动时跑一次；
 *    没有 VPN 时它判出 [AccountStatus.UNREACHABLE] 并写进本地账号状态，之后再没人复检。
 *    两个预取器也各有去重窗口（45s / 60s），失败那次同样占了窗口。
 *    → 跃迁时重跑 `AccountChecks.checkAll`，并清掉两个去重窗口。
 *
 * ## 判定口径
 *
 * 「取事实」在 [snapshotOf]，「下判断」在纯函数 [decideReprobe]（可单测）：
 * 只有**网络恢复 / VPN 接入 / VPN 断开 / 换了一张网**这四种跃迁才触发重探；
 * 同一网络的带宽、计费标记抖动一律忽略 —— 否则弱网下会反复重置连接池、反复打 `/user`。
 *
 * ## 为什么要防抖 + 前台复核
 *
 * - 防抖 [SETTLE_MS]：VPN 建链期间系统会连着下发数次回调（旧网 `onLost`、VPN `onAvailable`、
 *   能力补全），逐次处理会在「VPN 还没拿到 `VALIDATED`」时判成离线而漏掉那次 `VPN_UP`；
 * - 前台复核 [refresh]：后台期间回调理论上照常投递，但进程被冻结 / 回调丢失时不该
 *   一直带着旧结论 —— 回前台再算一次是最便宜的兜底。
 *
 * 只读网络状态，不申请任何新权限（`ACCESS_NETWORK_STATE` 已在 Manifest 声明）。
 */
object NetworkWatch {

    private const val TAG = "Reach"

    /**
     * 判定防抖窗口。
     *
     * 取 350ms：VPN 建链的几次回调通常落在几十毫秒内，等到窗口结束再判定就能一次看到
     * 「VPN + 已校验」的最终形态。
     */
    private const val SETTLE_MS = 350L

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 防抖用的 token（`removeCallbacksAndMessages(TOKEN)` 只撤自己的）。 */
    private val token = Any()

    @Volatile
    private var installed = false

    /** 上一次**已判定**的快照；只在主线程读写。 */
    private var lastEvaluated: NetworkSnapshot? = null

    /**
     * 安装守望。幂等，从 `BranchbaseApp.onCreate` 调一次即可
     * （必须在任何网络请求之前，基线才对得上）。
     */
    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val app = context.applicationContext
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm == null) {
                installed = true
                return
            }
            // 先落基线：进程刚起时的这张网不该被当成「变化」（否则启动瞬间白打一次探测）
            val baseline = snapshotOf(cm)
            lastEvaluated = baseline
            val registered = runCatching {
                cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = schedule(app, cm)

                    override fun onLost(network: Network) = schedule(app, cm)

                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities,
                    ) = schedule(app, cm)
                })
            }.isSuccess
            installed = true
            Logger.net(
                "远端可达性守望：基线 ${baseline.label}" +
                    if (registered) "" else "（注册默认网络回调失败，退回前台复核）",
                TAG,
            )
        }
    }

    /**
     * 立即复核一次（回前台 / 手动触发）。
     *
     * 与回调走同一条判定：如果这期间网络已经跃迁（后台错过回调），这里会补上那次重探；
     * 没跃迁则是一次无副作用的读。
     */
    fun refresh(context: Context) {
        val app = context.applicationContext
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        evaluate(app, cm)
    }

    /** 防抖：把窗口内的多次回调合并成一次判定（主线程，`lastEvaluated` 只有主线程碰）。 */
    private fun schedule(app: Context, cm: ConnectivityManager) {
        main.removeCallbacksAndMessages(token)
        main.postAtTime({ evaluate(app, cm) }, token, SystemClock.uptimeMillis() + SETTLE_MS)
    }

    private fun evaluate(app: Context, cm: ConnectivityManager) {
        val next = snapshotOf(cm)
        val previous = lastEvaluated
        val reason = decideReprobe(previous, next)
        lastEvaluated = next

        // 低噪：只有档位 / 网络句柄真的变了（或触发了重探）才写日志，
        // 带宽、计费标记这类同网抖动不刷屏。
        val meaningful = previous == null ||
            previous.kind != next.kind ||
            previous.networkHandle != next.networkHandle
        if (meaningful || reason != null) {
            Logger.net(
                "网络状态 ${previous?.label ?: "未知"} → ${next.label}" +
                    (reason?.let { "，${it.label}：重新探测远端" } ?: ""),
                TAG,
            )
        }
        if (reason == null) return

        // ① 丢掉旧路径上的连接池：见类注释成因 1
        RustBridge.resetHttpClient()
        // ② 清掉两个预取去重窗口：失败那次已经把窗口记账了，见类注释成因 2
        RepoPrefetcher.forgetDedupe()
        NotificationPrefetcher.forgetDedupe()
        // ③ 重新探账号：启动时那次「无法连接」的结论还留在账号状态里
        scope.launch {
            runCatching { AccountChecks.checkAll(app) }
                .onFailure { Logger.net("重探账号失败：${it.message}", TAG) }
        }
    }

    /** 把 `ConnectivityManager` 的原始事实读成判定用的快照。 */
    private fun snapshotOf(cm: ConnectivityManager): NetworkSnapshot {
        val network = cm.activeNetwork ?: return NetworkSnapshot.OFFLINE
        val caps = cm.getNetworkCapabilities(network) ?: return NetworkSnapshot.OFFLINE
        return NetworkSnapshot(
            hasDefaultNetwork = true,
            validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            networkHandle = network.networkHandle,
        )
    }
}
