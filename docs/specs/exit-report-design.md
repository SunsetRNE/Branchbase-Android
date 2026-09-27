# 异常退出上报（「上一程是怎么结束的」）

> 落地版本：**1.1.12 / versionCode 218**。
> 代码：`app/src/main/java/com/branchbase/ui/log/ExitReport.kt`；
> 钉子：`app/src/test/java/com/branchbase/ui/log/ExitReportTest.kt`；
> 日志锚点：`异常退出`（登记在 `Logging.kt` 的 `LOG_ANCHORS`，导出包的 `report.md` 会带上它）。

## 一、起因：一次什么都没留下的闪退

2026-09-27 早上，用户反馈「莫名其妙的闪退」并导出了日志包（`branchbase-logs-20260927-091250.zip`）。
那份包**干干净净**：39 条日志、`ERROR 0`、`WARN 0`，索引里连一个可疑词都没有。但原始日志里那段空白
是铁证：

```
09:12:24.179 [UI] [Compose] INFO 切换到「发布」
09:12:24.205 [网络] [缓存] DEBUG L1 直出（含过期）repo-list:releases:SunsetRNE/Branchbase-Android
09:12:28.890 [UI] [启动] INFO 启动 ▸ 日志初始化（建目录 / 清历史）
```

最后一条之后隔了 **4.685 秒**才是下一次「启动 ▸ 日志初始化」—— 也就是说进程在这 4.7 秒里**直接没了**，
而它一句话都没留下。为什么留不下：

| 死法 | 为什么日志里没有 |
|---|---|
| 被 `SIGKILL`（LMK 回收 / 系统杀） | 信号直接终止进程，Java 层根本没有机会执行任何代码 |
| 未捕获异常 | 能写，但这个 App **从未安装** `Thread.setDefaultUncaughtExceptionHandler`，异常只能进 logcat |
| native 崩溃（Rust/JNI） | 同上，且我们连 native 信号处理器都没装 |
| ANR | 主线程卡住时打不出日志；系统那份 traces 我们从来没去读过 |

再加上取证通道的现实：**容器里拿不到 logcat / tombstone**（设备 shell 通道未授权），
用户也不可能为了一个偶发闪退去抓 bugreport。于是「事后问系统」成了唯一可行的路。

## 二、为什么是 `ApplicationExitInfo`

| 方案 | 判定 |
|---|---|
| 装 `Thread.setDefaultUncaughtExceptionHandler` | 只能覆盖「Java 未捕获异常」；**管不了**被杀 / native；而且它是「下次才生效」，对已经发生过的那次毫无办法 |
| 常驻读 logcat（`Runtime.exec("logcat")`） | Android 10+ 起 App 读不到别的进程的日志，自己的崩溃行还常常在 `DEBUG` 缓冲里被冲掉；还有常驻进程的代价 |
| 让用户抓 bugreport | 用户成本极高（开发者选项 → 完整错误报告），偶发问题根本等不到 |
| 第三方崩溃平台 | 要把栈送出设备，涉及隐私与网络依赖；本项目的日志哲学是「一切都进本地 `branchbase.log`，用户自己导出」 |
| **`ActivityManager.getHistoricalProcessExitReasons(pkg, 0, n)`（API 30+）** | 系统在**进程死亡时**就写好了原因、时间、优先级、内存占用与 trace（Java 崩溃栈 / ANR traces / API 31+ 的 native tombstone），是**全局环形缓冲**里的记录 —— 不需要我们当时活着，所以**可以回溯** |

选最后一档。它顺手解决了最要紧的一条：**用户装上这一版之后如果再复现，栈就会自己进日志。**

## 三、设计

### 3.1 抄出来的是什么（`ExitFacts`）

`ApplicationExitInfo` 是 final 类、没有公开构造函数，单测里造不出来，所以先把要用的字段抄成一个
纯数据类，之后的「挑哪些上报」「正文长什么样」全部在纯函数里完成：

| 字段 | 来源 | 说明 |
|---|---|---|
| `timestampMs` | `getTimestamp()` | 退出时刻（毫秒） |
| `reason` | `getReason()` | 原因码，见 3.2 |
| `importance` | `getImportance()` | 退出时的进程优先级 —— 「在什么处境下被杀的」 |
| `pid` / `processName` | `getPid()` / `getProcessName()` | 多进程时能分清是哪个进程 |
| `description` | `getDescription()` | 崩溃时是异常摘要（如 `java.lang.IllegalStateException: …`） |
| `pssKb` / `rssKb` | `getPss()` / `getRss()` | 单位是 **kB**（AOSP 注释原话「in kB」），打印时才换算 MB |
| `trace` | `getTraceInputStream()` | 见 3.5 |

### 3.2 原因码 → 人话

`exitReasonLabel(reason: Int)` 是一张纯表，数值对着 `platforms/android-35/android.jar` 的
`javap -constants android.app.ApplicationExitInfo` 核过（**故意不引 `REASON_*` 常量**：单测只需要
「这个数字是什么意思」，引常量会把 android.jar 拖进纯 JVM 单测）：

`0` 未知 · `1` 自己退出 · `2` 被信号杀死 · `3` 系统低内存回收（LMK） · `4` Java/Kotlin 未捕获异常 ·
`5` native 崩溃 · `6` ANR · `7` 初始化失败 · `8` 运行时权限变更 · `9` 系统判定占用过高 ·
`10` 用户从最近任务划掉 · `11` 被强制停止 · `12` 依赖被卸载或停用 · `13` 其它 · `14` 被冻结 ·
`15` 包状态变化 · `16` 包被更新。表外的数字落成「未知原因码 N」（不许崩、也不许装作认识）。

### 3.3 什么算「异常」（`isAbnormalExit`）

**排掉**：`1` 自己退出、`8` 权限变更（用户授权时系统会杀进程再拉起，属正常）、`10` 用户划掉、
`11` 被强制停止、`12` 依赖被卸载、`14` 被冻结、`15`/`16` 包状态变化与装新包。
**保留**：`4`/`5`（闪退）、`6`（ANR）、`3`（被 LMK 杀）、`2`（被信号杀）、`9`（占用过高）、
`7`（初始化失败）、`13`（其它 —— 系统归不了类的那一类往往最可疑）、`0`（未知，宁可多报）。

### 3.4 上报哪几条（`pickExitFacts`）

**不是「只报最新一条」。** 用户的真实动作链是「装上新包 → 打开 App」，而**装新包本身**会把旧进程
以 `REASON_PACKAGE_UPDATED`（16）结束掉 —— 它才是最新一条。只报最新一条的实现在这里就会把真正的
闪退丢掉，而且丢得毫无声息（看起来「上报功能在工作」）。

所以：**报所有「比上次报过的时间戳新」的异常退出，上限 [EXIT_REPORT_LIMIT] = 3 条**（取最近的 3 条，
再按时间正序输出，读日志的人从旧看到新）。

「报到哪一刻了」记在一份**独立的** prefs 里（`log_runtime` / `exit_reported_upto`）：

- 它是**运行时状态**，不是设置项 —— 塞进 `branchbase` 命名空间会污染
  「设置键只在 `SettingsKeys.kt` 里声明」这条规矩（`SettingsSpecTest` 在钉它）；
- 标记推到**「现在」**而不是「最后一条记录的时间」：这次启动之前的所有退出都已处理，之后的退出必然
  带更大的时间戳（那属于下一个进程），不会被这次的上报吞掉。

### 3.5 栈：文本、tombstone、截断

`getTraceInputStream()` 给的**不是同一种东西**：

- **文本**（Java 崩溃栈 / ANR traces）：没有 NUL 字节，直接按 UTF-8 读；
- **native tombstone**：API 31+ 起 `REASON_CRASH_NATIVE` 给的是 **protobuf**（AOSP 文档指明 schema 在
  `debuggerd/proto/tombstone.proto`）。直接当文本写进日志就是一片乱码，所以
  `decodeExitTrace` 检测到 NUL 就改走 `printableRuns`，只抽 ASCII 可打印串
  （`signal 11 (SIGSEGV)`、崩溃地址、`backtrace:` 后面那串符号本来就在可打印段里）；
- **系统可能压根没留**（trace 在另一个全局环形缓冲里，会被别家的崩溃覆盖）：那就在正文里明说
  「系统没有留下栈」，而不是让读者以为「这次没崩溃」。

三道截断（一次崩溃不值得撑爆导出包、也不该把「最近 1000 条」的内存缓冲挤掉一大截）：
读系统文件 **64KB**、栈最多 **60 行**、最多 **8000 字**；被丢掉的量在末尾交代
（`…（还有 40 行没记：只留前 60 行 / 8000 字）`）。第一行就超长（没有换行的巨行）时宁可截半行，
也不留一条空白。

### 3.6 与日志格式契约的接口：多行正文逐行补表头

日志格式的契约是「**一行一条**，每行都以 `HH:mm:ss.SSS [类] [tag] 级别` 开头」——
导出包 `report.md` 的读法说明、用户 `grep 慢帧` / `grep [网络]`，全建立在这上面。
而退出上报的正文天生多行（原因一行、进程与内存一行、栈 N 行）。

于是新增 `Logging.kt` 的 `logFileLines(e: LogEntry): List<String>`：**逐行补表头**，
并且把原先**三处各拼一遍**的行格式收敛成一处真源：

| 用处 | 原先 | 现在 |
|---|---|---|
| 写盘（`FileAppender`） | `private fun line(e)` 自己拼 | `logFileLines(entry).forEach { out.append(it) }` |
| 界面点行复制 / RAW 搜索 / 全量复制（`LogScreen.logLine`） | 自己拼（注释里就在抱怨「三处各拼一遍」） | `logFileLines(e).joinToString("\n")` |
| 导出兜底（`LogExporter.currentLogText`，文件缺失时） | 调 `logLine` | 同上（自动一致） |

「点行复制的内容 = `branchbase.log` 的行格式」这条验收项（`prototypes/log-redesign.md` §八）
从此是**逐字节一致**，多行栈粘到别处也不会分不清哪行属于哪条日志。

### 3.7 插在哪

`BranchbaseApp.onCreate` 里 `LogManager.init(this)` **之后**（写盘线程已就绪，是启动路径上唯一一个
稳定早于业务代码的插入点），并配一条 `启动 ▸ 异常退出上报（读系统留的退出记录）` 的阶段标记 ——
这条标记**每次都打**，于是「这次没有异常退出」和「上报功能没跑」在日志里能分开。

两条细节：

- 类别用 **`本地`**（`LogCategory.LOCAL_TASK`）、级别 **`ERROR`**：级别给 ERROR 是为了让导出的
  `report.md` 统计出「ERROR 1」，用户一眼看出这次导出里有事故；类别不给 `UI` 是因为
  `LogManager.lastUiMessage` 拿它当慢帧的「页面」注脚，一条异常退出混进去会让慢帧的归因走偏。
- **同步**读（一次 binder + 至多 64KB 文件），与 `DeviceProfile.log` / `FrameWatch.install` 一样在
  启动路径上：相比「崩溃后什么都拿不到」，这点代价换得值。

## 四、验证

`ExitReportTest`（24 例，纯 JVM）：

- 原因码：4 = 未捕获异常 / 0~16 都有不重复的人话且不落兜底 / 99 与 -1 不崩且带数字；
- 判定：`1,8,10,11,12,14,15,16` 不算异常、`0,2,3,4,5,6,7,9,13` 算；
- 挑选：只挑比 `reportedUpto` 新的、按时间正序、**装新包杀掉的旧进程不会挤掉真闪退**、
  上限取最近 3 条、全报过就一条不报；
- 栈：没栈时正文说明「系统没留」、超 60 行截断并交代丢 40 行、按字符数截断、
  空输入不留空行、第一行超长时截半行、文本栈原样读、tombstone 的 protobuf 抽成可打印串且
  不含 NUL、太短的可打印碎片丢掉；
- 正文：多行且带齐日期/时间/原因/进程/pid/优先级/内存/描述；
- 行格式：**正文几行、文件里就几行，且每行都带表头**，`logLine` 与 `logFileLines` 逐字节一致；
- 接线（源码级）：锚点常量与 `LOG_ANCHORS` 登记、`EXIT_PREFS` 是独立 prefs 且代码里不碰
  `SettingsKeys`（断言前先剥注释，否则「把坑写在注释里」会变成假红）、标记读完必须推进、
  API 30 守卫、写盘走 `logFileLines` 且旧的单行拼法已删、`ExitWatch.report(this)` 在
  `LogManager.init(this)` 之后。

全量：`119 suites / 1067 tests / 0 failures / 0 errors`；`check-i18n.py --min-coverage 100` 仍 100%
（本版**没有**新增字符串键：正文里的中文都进日志，不进资源表 —— 日志本来就是中文的）。

**真机没能验证**：容器里装不上包（桥没有安装端点、设备 shell 通道未授权），也读不到 logcat /
tombstone，所以在容器内只能把纯函数与接线钉死；真正「拿到闪退栈」的那一步只能靠用户复现。

## 五、已知边界（写在前面，免得下次误判）

1. **卸载重装会清掉历史**：`ApplicationExitInfo` 属于「这个包的历史」，卸载（或清数据）之后系统那份
   记录也没了。所以出事后应当**先导出日志包**再看要不要重装。装同一签名的新版本不算卸载，历史保留。
2. **记录是全局环形缓冲**：崩溃记录可能被**别的 App** 的新崩溃挤掉（AOSP 文档明写），拿不到属正常，
   不是我们读错了。
3. **tombstone 只留可打印串**：要看完整的 native tombstone（完整内存映射、寄存器）得上 bugreport，
   本版不做。
4. **不做 ANR traces 的分段解析**：ANR 的 trace 是几万行的 `traces.txt`，本版只截前 60 行。
5. **不进 `report.md`**：目前只进 `branchbase.log`（`report.md` 的锚点表里登记了 `异常退出` 这个词，
   收到日志的人照着 grep 就能找到那一行）。
