# 远端可达性判定（网络跃迁 → 丢连接池 → 重探）

> 这份文档存在的理由：「先没开 VPN 打开 App、之后再接入 VPN，远端还是打不开」这个症状
> 很容易被当成「网络问题」放过 —— 实际是**判定缺失**：网络已经换了，但连接池、账号结论、
> 预取去重窗口全都还停在切换前那条路径上。判定收在 `NetworkWatch` +
> 纯函数 `decideReprobe`（`app/src/main/java/com/branchbase/core/`），改这块之前先读完本页。

## 一、症状与两条独立成因

真机现场（OnePlus / Android 16，cellular）：App 先在没有 VPN 的网络下启动并浏览，
之后用户切到 VPN 客户端接入，再切回 Branchbase —— 远端资源（`api.github.com` 数据、
`raw.githubusercontent.com` 图片、网页会话端点）仍然拿不到，看起来像「网络一直没恢复」。

成因有两条，**各自都能单独造成这个症状**，所以两条都要修：

| # | 成因 | 为什么它会让「换了网络也不好」 |
|---|---|---|
| 1 | Rust 侧共享 HTTP 客户端（`core/src/api/client.rs` 的 `shared_http()`，进程级单例） | 连接池里的连接是在**切换前那张网**上握手完成的。默认网络换了以后它们既不可用也不会自己消失，后续请求继续复用 → 一路超时（由 15s 总超时兜底，用户看到的是一串卡顿而不是明确报错） |
| 2 | 启动那一次的负面结论被记账 | 账号健康检查只在 `MainActivity` 启动时跑一次：没有 VPN 时判出 `AccountStatus.UNREACHABLE` 并写进本地账号状态，之后无人复检；两个预取器（通知 45s / 仓库 60s）也把失败的那次记进了去重窗口 |

## 二、判定口径

### 2.1 三档（`NetworkKind`）

判定按固定优先级走（`NetworkSnapshot.kind`）：

| 档 | 条件 | 含义 |
|---|---|---|
| `OFFLINE` | 没有默认网络，**或**默认网络既不含 `TRANSPORT_VPN` 又没带 `NET_CAPABILITY_VALIDATED` | 出不了网（门户未登录 / 上游断了也长这样） |
| `VPN` | 默认网络带 `TRANSPORT_VPN`（**不看** `VALIDATED`） | 「已接入 VPN」 |
| `DIRECT` | 有出口，默认网络**不含** `TRANSPORT_VPN` | 「未接入 VPN」 |

两处口径是有意这么定的：

- **非 VPN 网络用 `VALIDATED`**，而不是「有没有网」：Android 的「已连接」只说明链路在，
  不代表能到互联网；
- **VPN 网络不看 `VALIDATED`**：真机 `dumpsys connectivity` 实测（OnePlus / Android 16，
  FlClash：`Transports: CELLULAR|VPN Capabilities: INTERNET&…&VALIDATED`），VPN 的
  `VALIDATED` 是从底层网络**继承**来的，刚建链的那一小段可能还没写上。若据此把「已接入 VPN」
  判成离线，最该抓的 `VPN_UP` 会被漏掉。VPN 本身就是一次有意的出口选择，先算在线；
  真能不能到 GitHub 由账号探测回答（见 §五）。

三档判定是 `NetworkSnapshot.kind` 的只读派生值，取事实与下判断不会各写一份口径。

### 2.2 四种跃迁才重探

| 跃迁 | 原因 | 为什么 |
|---|---|---|
| 离线 → 有出口 | `RESTORED` | 恢复联网：之前那次失败不算数 |
| 未接入 VPN → 接入 VPN | `VPN_UP` | **本机制存在的理由** |
| 接入 VPN → 断开 VPN | `VPN_DOWN` | 反向同理：池里的连接绑在 VPN 链路上 |
| 同档但换了网络句柄 | `SWITCHED` | Wi-Fi ↔ 移动数据、换 Wi-Fi，链接同样断了 |

**不触发**的形状（防折腾，同样重要）：

- 进程内第一次拿到网络事实（`previous == null`）—— 客户端本来就是新建的，没有陈旧结论可丢，
  此时重探只是白打一次 `/user`；
- 新态仍是 `OFFLINE` —— 没得探，等恢复（典型是 VPN 建链中途：旧网已断、VPN 还没拿到 `VALIDATED`）；
- 同一个网络的带宽 / 计费标记抖动（句柄没变）—— 否则弱网下会反复重置连接池、反复打 `/user`。

判定是纯函数 `decideReprobe(previous, next)`，单测 `ReachabilityPolicyTest` 逐条钉住
（15 条，含「没开 VPN 打开 App 之后再接入 VPN 必须重探」与「VPN 刚建链还没继承到 VALIDATED
也算已接入」两条）。

### 2.3 VPN 优先于「换网」

VPN 挂上时默认网络句柄也会变，两个条件会同时成立。原因必须先看 `vpn` 位：
把那次记成 `SWITCHED` 会把唯一的线索埋掉 —— 排障时「到底走没走 VPN」正是第一个要看的东西。

## 三、跃迁时做的三件事（顺序固定）

1. **丢共享连接池**：`RustBridge.resetHttpClient()` → Rust `reset_http_client()`，
   一次丢掉**两份**进程级客户端（JSON/网页用的共享客户端 + 上传专用客户端，见
   `core/src/api/client.rs` 的 `SHARED_HTTP` / `UPLOAD_HTTP` 两个槽）。
   在途请求各自持有 `reqwest::Client` 的引用计数，不会被掐断；丢掉的只是「以后新建请求要用的那一份」。
2. **清两个预取去重窗口**：`RepoPrefetcher.forgetDedupe()` / `NotificationPrefetcher.forgetDedupe()`。
   窗口是在**发起预取时**记的，失败那次同样占着它 —— 不清就会出现「梯子连上了，页面还是空的」。
   `inFlight` 标志不动：那些是真的还在跑，清掉会让同一仓库并发两份。
3. **重探账号**：`AccountChecks.checkAll()`（IO 线程）。启动时那次 `UNREACHABLE` 就是这样被覆盖掉的。

## 四、安装时机与防抖

| 时机 | 位置 | 理由 |
|---|---|---|
| 安装（一次） | `BranchbaseApp.onCreate` 最前面 | 必须早于任何网络请求，否则基线落在旧网络上、`VPN_UP` 会被漏掉 |
| 网络回调 | `ConnectivityManager.registerDefaultNetworkCallback` 的 `onAvailable` / `onLost` / `onCapabilitiesChanged` | 默认网络（含 VPN）的一切变化都从这里出 |
| 回前台复核 | `MainActivity.onResume` → `NetworkWatch.refresh()` | 兜「回调没投到」（后台冻结 / 平台差异）：从 VPN 客户端切回来时不该还带着旧结论 |

- **防抖 350ms**：VPN 建链期间系统会连着下发数次回调（旧网 `onLost`、VPN `onAvailable`、
  能力补全）。逐次处理会在「VPN 还没拿到 `VALIDATED`」时把它判成离线，从而漏掉那次 `VPN_UP`；
  等窗口结束再判定，看到的是一次成型的最终状态。
- **只读**：只用 `ACCESS_NETWORK_STATE`（`AndroidManifest.xml` 早已声明，普通权限、无运行时弹窗），
  不申请任何新权限。
- **日志低噪**：只有档位 / 网络句柄真的变了（或触发了重探）才写一条 `[网络] [Reach]`，
  同网抖动不刷屏。

## 五、已知边界

1. **判定不了「这张网能不能到 GitHub」**。中国大陆的直连网络是 `VALIDATED` 的（能上国内站点），
   GitHub 却被阻断 —— 本地没有任何系统事实能表达这件事。所以 `DIRECT` 不等于「远端可达」，
   真正的可达性仍由账号探测（`GET /user`）给出；本机制保证的是**换了路径就重新判定**，而不是替判定下结论。
2. **只跟随默认网络**。分应用代理 / 只在某些 UID 上生效的 VPN 不会改变默认网络，
   `TRANSPORT_VPN` 也就不会出现 —— 这类工具走「VPN 未接入」档，属于预期行为。
3. **去重窗口被清空 = 可能多打一次请求**。跃迁是低频事件（用户手动开关 VPN / 换网），
   代价可接受；不这么做则会出现「窗口内拒绝重试」的假故障。
4. **libgit2（clone / pull / push）不经过共享连接池**。它的连接是每次操作现建的，
   VPN 切换后天然可用，因此不在本机制的处理范围内。
