# 主线程卡顿守望（HangWatch）

> 关联：`docs/specs/exit-report-design.md`（异常退出上报）、`app/src/main/java/com/branchbase/ui/log/HangWatch.kt`。
> 版本：1.1.13 / versionCode 219。

## 一、起因：慢帧日志在「卡死」面前是瞎的

2026-09-27 两次真机闪退（`SunsetRNE/Branchbase-Android` 与 `SunsetRNE/oppo_oplus_realme_sm8650`）
形态完全一致：切到「发布」Tab 之后，进程在约 5 秒后静默消失，日志包里 `ERROR 0` / `WARN 0`。

第二次的原始日志尾部长这样：

```
10:29:44.266 [UI] [Compose] INFO 切换到「发布」
10:29:44.311 [网络] [缓存] DEBUG 无缓存可直出 repo-list:releases:...(type=仓库列表)
10:29:46.912 [UI] [帧] INFO 慢帧 105.6ms（等待 5.7 / … / 绘制 61.5* / …） · 页面「切换到「发布」」
10:29:51.857 [UI] [启动] INFO 启动 ▸ 日志初始化（建目录 / 清历史）
```

注意 **46.912 那一帧还画出来了**（105.6ms，就是「加载中」那屏），之后 **4.945 秒**里一条日志都没有，
下一条已经是新进程的启动。

> 这 4.945 秒有**两种解释**，而当时的证据分不出来：
> ① 主线程卡住 —— 那一帧永远画不完（FrameWatch 看不见）；② 进程在 46.912 之后**立刻**就死了，
> 这 4.9 秒只是用户发现闪退、重新点开 App 的时间（人看到闪退再点图标，四秒多很正常）。
> 两次事件分别是 4.945s 与 4.685s，两个数字都站得住。
> **正因为分不出来，才要两条通道一起上**：卡住 → 本文件（`HangWatch`，≥2s 就留下主线程栈）；
> 已经死掉 → 异常退出上报（原因码 + 系统 trace，`exit-report-design.md`）。

为什么之前查不动？因为 `FrameWatch` 的数据源是 `Window.addOnFrameMetricsAvailableListener` ——
**一帧画完了才有回调**。若是①，卡住的那一帧永远画不完，于是在慢帧日志里「等于不存在」：
既没有一条巨大的慢帧，也没有任何归属信息。

## 二、它做什么

一个后台守护线程（`bb-main-watch`）每秒往主线程 `Handler` 投一枚探针，然后带
`BLOCK_MS = 2000ms` 超时等回音：

| 情况 | 行为 |
|---|---|
| 正常 | 毫秒级返回，零输出（代价 = 每秒一次空投递） |
| 主线程 2s 没回 | 判定卡住：写下 `主线程卡住 ≥Nms` + **当时页面** + **主线程调用栈** |
| 之后恢复 | 补一条 `主线程恢复：约卡了 Nms 后继续`，把上一条的「≥」补成真实时长 |

关键是**写日志的线程不是主线程**：`LogManager.log` 只入队，落盘由 `FileAppender` 自己的线程做
（`Logging.kt`）。所以**即使主线程一直卡到被系统杀掉，这几行也已经写进磁盘**，
下一次导出就能看到「卡在哪一行」—— 这正是那 4.9 秒空白里缺的东西。

## 三、为什么不用别的办法

| 候选 | 为什么不够 |
|---|---|
| `FrameWatch` 加长阈值 | 数据源决定它只能看到**已完成**的帧，卡死的那一帧永远没有回调 |
| `dumpsys gfxinfo` / `dumpsys activity anr` | 要 adb；dump 自身跑在被测进程主线程上；用户手机根本没连电脑 |
| `Choreographer.postFrameCallback` 自测 | 挂在主线程上 —— 主线程卡住时它自己也卡住，测不出「卡了多久」 |
| 系统 ANR traces（`/data/anr/traces.txt`） | 要 root / 要 adb，且只有真 ANR 才有 |
| `ExitReport`（`ApplicationExitInfo`） | 互补而非替代：它事后给**结论**（原因码 + 系统 trace），拿不到「卡在我们哪一行」；且 API 30+ |

## 四、纯函数层（可在纯 JVM 单测里钉）

| 函数 | 职责 |
|---|---|
| `shouldReport(elapsedMs, nowMs, lastAtMs, lastElapsedMs): Boolean` | 限流：距上次 ≥ `MIN_GAP_MS = 10s`，**或者这次测到的下界比上次更长**（卡得更久不能被吞掉，否则日志会留下「只卡了 2 秒」的假象） |
| `hangMessage(elapsedMs, page, stack, maxLines = 40): String` | 卡住正文：`主线程卡住 ≥Nms（…「≥」是下界）· 页面「…」` + `主线程栈 N 层：` + 各帧；栈为空时给一句解释而不是留白；超 40 层末尾交代「余下 K 层没记」 |
| `recoveredMessage(stuckMs, page): String` | 恢复正文，把下界补成真实时长 |
| `trimStack(frames, maxLines): List<String>` | 截栈，`maxLines = 0` 不留 |
| `mainThreadStack(): List<String>` | 从守望线程取 `Looper.getMainLooper().thread.stackTrace`（不在单测里覆盖） |

多行正文由 `logFileLines`（1.1.12 引入）**逐行补表头**，所以文件里每一层栈都带
`HH:mm:ss.SSS [本地] [主线程] WARN ` —— `grep '\[主线程\]'` 能捞到整段现场。

## 五、接线与取舍

- 挂在 `MainActivity` 启动路径里 `FrameWatch.install(this)` 之后（同一段，先有帧数据再有卡死现场）；
- 类目用 `本地` / 级别 `WARN`：**不能进 UI 类**，否则它会成为 `LogManager.lastUiMessage` 的「页面」
  注脚，把慢帧归因带偏（`FrameWatch` 用「排除自己 tag」的办法躲开同一个坑，这里干脆换个类目）；
- **不设开关**（不像慢帧日志那样跟 `SettingsKeys.FRAME_WATCH`）：它只在已经出问题时才输出，
  属于诊断地基而不是可选项 —— 出事那天用户并不会先想起去设置里打开它；
- 锚点表登记 `主线程`（`LOG_ANCHORS`），导出包的 `report.md` 里因此有这个词可 grep。

## 六、验证

`app/src/test/java/com/branchbase/ui/log/HangWatchTest.kt` 13 例：

- 正文：要素齐（时长 / 页面 / 层数 / 各帧）· 必须写明「≥ 是下界」· 没页面就不写页面段 ·
  栈取不到时给解释（正文恰好两行）· 100 层截成 40 + 交代余下 60 · `trimStack` 边界（空 / 正好等于 / 超一 / 上限 0）；
- 限流：1999ms 不报、2000ms 报 · 10 秒内不重复 · **更久的即使 10 秒内也报** · 超间隔可再报；
- 恢复：补真实时长 + 页面（无页面时不写）；
- 行格式：卡住现场进日志后，正文几行文件里就几行、**每行都带 `[本地] [主线程] WARN `**；
- 接线（源码级）：tag 常量 / 守护线程名 / `isDaemon` / `handler.post { latch.countDown() }` /
  `latch.await(BLOCK_MS, …)` / `Looper…thread.stackTrace` / `lastUiMessage(excludeTag = …)` /
  `LogLevel.WARN, MAIN_THREAD_LOG_TAG` / 不出现 `SettingsKeys` / 锚点表含 `主线程` /
  `HangWatch.install()` 在 `FrameWatch.install(this)` 之后。

## 七、已知边界（诚实写下来，免得日志被误读）

1. **「≥Nms」是下界**：探针只能证明「到这为止还没回」，真实时长靠恢复那条补；
2. **卡在 native 里看不到内部帧**：栈里只有 `nativeXxx` 这一层（Rust core 内部帧属系统视角之外），
   那一段要靠 `ExitReport` 的 tombstone 可打印串补；
3. **探针本身会被排队**：主线程卡住期间每秒投一枚，恢复时这些空跑一起执行（无副作用，就是几次空 lambda）；
4. **抽不出「卡了多久才被杀」**：如果进程没恢复过，就只有下界 —— 恰好这种情形下
   `ExitReport` 会给出原因码（ANR / CRASH_NATIVE / LMK），两条通道合起来才完整；
5. **不是性能工具**：正常时零输出，不做采样统计（那是 `FrameWatch` 小结的活）。
