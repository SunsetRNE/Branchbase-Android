# 液态玻璃导航栏：三层结构与运动物理（移植版）

> 上游：[Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)（Apache-2.0），
> 移植固定在 commit `65ab177`（tag `2.0.1`）。
> 相关文件：`ui/navigation/GlassBar.kt`（栏）、`ui/navigation/LiquidGlassDrag.kt`（物理）、
> `ui/navigation/Backdrop.kt`（参数真源 + 采样安全阀）、`ui/navigation/NavigationShell.kt`（录制与下发）、
> 钉子：`app/src/test/java/com/branchbase/ui/navigation/GlassBackdropTest.kt`（11 条，源码级）。

## 一、这一版取代了什么

上一版（1.2.3 早期）的选中态是**两档模糊**：未选中 `blur(24dp)`、选中 `blur(2dp) + lens(...)`。
它能成立，但有两个天生的弱点：

1. 两档半径的差别在浅色背景下几乎看不出来（都是糊的白），选中项要靠再叠一层实心色才看得出来 ——
   而那层实心色正好把采进来的背景盖死，「玻璃」和「半透明塑料」就没区别了；
2. 运动只有一个位移弹簧：滑块只会**滑过去**，不会形变。液态感一半来自形变，这里缺一半。

现在改成上游 catalog 的结构：**选中态不再靠模糊档位，改靠「有没有颜色」**；运动交给四个弹簧。

## 二、三层结构（顺序刚性，换一换就不成立）

| 层 | 节点（`GlassBar.kt`） | 作用 |
|---|---|---|
| ① 容器 | 可见 `Row` + `drawBackdrop(vibrancy → blur → lens)` | 整条栏的玻璃：增色、磨砂、边缘折射；按住时横向拉伸 |
| ② 染色录制层 | `alpha(0f)` + `layerBackdrop(tabsBackdrop)` + `ColorFilter.tint(Primer.Blue500)` | 把「染成强调色的内容」录进**第二个**图层；屏幕上看不见 |
| ③ 滑块 | `drawBackdrop(rememberCombinedBackdrop(页面, ②))` | 采样「页面 × 染色层」的合体 → 滑块里透出**强调色图标 + 边缘色散** |

为什么非得是三层：

- ①②③ 的绘制顺序决定了观感。①先画、③后画，所以 ③ 的采样结果**盖住** ① 的图标 ——
  「滑块里是彩色图标、滑块外是灰图标」正是这么来的，不是靠给图标换 tint（那会连滑块外一起换）；
- ②`alpha(0f)` 是关键：录制发生在 `alpha` 图层**内层**，录到的是不透明的内容，屏幕上却什么都不显示。
  删掉这一层，滑块采到空图层，看起来就是一块灰玻璃；删掉 `ColorFilter.tint(`，滑块里透出的和屏幕上一模一样，等于白录；
- ③ 必须采 `rememberCombinedBackdrop(page, tabsBackdrop)`：只采页面的话，滑块里没有颜色来源。

## 三、运动：四个弹簧（`LiquidGlassDrag.kt`）

| 弹簧 | 管什么 | 少了会怎样 |
|---|---|---|
| `valueAnimation` | 滑块停在哪个槽位 | 点一下硬切过去 |
| `pressProgressAnimation` | 按下进度（折射 / 高光 / 阴影 / 图标放大都乘它） | 按下去栏面纹丝不动 |
| `scaleX/YAnimation` | 按下拉伸（X 阻尼 0.6、Y 阻尼 0.7） | 形变各向同性，像贴纸 |
| `velocityAnimation` | 拖动速度（X 吃三倍于 Y 的压扁量） | 甩动时不会先压扁再归位 |

配套两件事：

- **拖动识别器不走 `touchSlop`**：上游自己写了 `inspectDragGestures`，按下即开始形变。
  用框架的 `detectDragGestures` 会有半拍延迟；
- **指针高光与手势分离**：`InteractiveHighlight.modifier` 画在**表面**上（栏 / 录制层），
  `gestureModifier` 挂在**滑块**上。两件都挂在滑块上的话，滑块一动高光就跟着自己跑，位置全错。

## 四、参数只有一处真源

全部在 `Backdrop.kt` 的 `GlassBackdrop`：容器 64dp / 滑块 56dp / 内边距 4dp、
容器 `blur 8dp` + `lens(24dp, 24dp)`、滑块 `lens(10dp × press, 14dp × press, chromaticAberration)`、
按压拉伸 `78/56` 与 `16dp`、图标放大 `1.2`、面板位移 `4dp`、内阴影 `8dp`、高光/薄层的四个 alpha。
钉子单测同时钉「声明」与「读取」（`GlassBackdropTest` 第八条）—— 只钉一边挡不住「定义了没人用」。

## 五、降级（两条，都不许编译报错式地静默失效）

1. **没有采样层**（开关关着 / 栏被单用 / API < 31）：不录制、不画滑块，退回主题色实心胶囊 +
   选中项实心圆。判据是 `item.selected && !sampled`。
2. **指针高光的画法降级**：上游 `InteractiveHighlight` 用 `com.kyant.backdrop.RuntimeShader` 写 AGSL；
   本项目锁定的 `backdrop` **1.0.6 没有导出 `RuntimeShader`**（`javap` 实测公开面只有
   `RuntimeShaderCache` / `ShadersKt`），故改用 `Brush.radialGradient` + `BlendMode.Plus`：
   位置、半径、颜色、加色混合、按下同步一致，只有衰减曲线由 `smoothstep` 变为线性。
   降级理由写在 `LiquidGlassDrag.kt` 文件头，并被钉子单测第十一条钉住。

## 六、验证与回滚

```bash
source tools/env/env.rc
./gradlew :app:testDebugUnitTest    # 期望 BUILD SUCCESSFUL，1121 tests / 0 failures
./gradlew :app:assembleDebug        # 期望产出 debug APK
```

- 界面验证：装新 APK → 设置页打开「悬浮玻璃导航栏」→ 看三处底部栏（主界面 / 仓库页 / 个人页）：
  滑块里应透出强调色图标；按住滑块整体拉伸、松手回弹；拖动可跨项切换。
  未装机时的确定性判据只有钉子单测与编译。
- 回滚：改动只落在 4 个文件 —— `ui/navigation/GlassBar.kt`、`ui/navigation/Backdrop.kt`、
  `ui/navigation/LiquidGlassDrag.kt`（新增）、`app/src/test/.../GlassBackdropTest.kt`。
  把这 4 个文件恢复到基线 `afd8b66` 的版本即可（新增文件删除）；本次改动的完整补丁另存为
  工作区根的 `liquid-glass-port.patch`（1469 行，含新增文件）。
  三处调用点（`GlassNavigationBar` / `GlassRepoBar` / `ProfileBubbleNavigationBar`）**一行都没动** ——
  `GlassBar(items, showLabels, trailing)` 的签名与语义保持不变，这是这次移植能整块回退的前提。

## 七、已知边界

- 栏是**整宽**的（上游形态）：外壳 `NavigationShell` 的横向 12dp 留白仍在，栏内 4dp 内边距，
  槽位等权。仓库页 6 个槽 + ⋮ 手柄时，每格约 `(宽 - 24 - 8) / 7`；手柄是**额外一格**，
  不是滑块目标（滑块只在真正的导航项之间移动）。
- `drawBackdrop` 的每一项都要显式传参（上游不给默认值）；容器 / 录制层 / 滑块共 3 处调用点，
  钉子单测按数量钉住（少一处就少一层，且不会编译报错）。
- 低端设备上这是一条每帧多画两遍内容 + 两处 AGSL 的链；`NavigationShell` 的
  「栏不可见不录」「WebView 在屏不录」两条门控照旧生效（钉子第一、二、十条）。

## 八、安装态实测缺陷与修复（2026-10-08，虚拟屏）

设备：`LENOVO TB321FU` / Android 16（SDK 36），安装包 `com.branchbase`（uid=10306）。
观察通道：DSHA 虚拟屏（`/app/vscreen/create → launch?package=com.branchbase → see`，
1008×1792，`generation` + `frameSeq` 双令牌才能输入）。复现记录见工作区 `glassfix-evidence/`。

**缺陷 1：静止态选中滑块是一块纯蓝方块，里面没有图标。**
录制层被写成「降级样式」（`sampled = false`）：单元格画实心 `Primer.Blue500` 底 + **白色**图标；
而这一层紧接着被外层 `ColorFilter.tint(accentColor)` 整片染色 —— 白图标变强调色，
压在强调色底上，等于消失；滑块采到这块内容，于是静止时是一块纯色，看不出选中项是什么。
修：录制层改传 `sampled = true`（只录图标本身，颜色交给外层 tint）。

**缺陷 2：通知外部的判据读的是快照。**
`LaunchedEffect(drag)` 启动那一刻的 `selectedSlot`：第 0 → 第 1 项时 `1 != 0` 成立、
切换成功；再点回第 0 项时守卫算的是 `0 != 0`、不成立，`onClick` 被吞。
修：判据改读 `rememberUpdatedState(selectedSlot).value`。

**缺陷 3（真因，前两条修完仍然「切过去回不来」）：滑块停在上一个槽位不动，
并把那一格的点按吃掉了。**（2026-10-08 用户复现：主 Tab → 消息 Tab 成功，再点主 Tab 无反应。）

- 机制：`var currentSlot by remember(selectedSlot) { mutableIntStateOf(selectedSlot) }` ——
  把 `selectedSlot` 当成了 `remember` 的 key。外部选中项一变，**state 对象被换掉**，
  而 `LaunchedEffect(drag) { snapshotFlow { currentSlot } … }` 是长活的、观察的是**旧对象**；
  旧对象此后不再变化 ⇒ 流永不重发 ⇒ `animateToValue` 不被调用 ⇒ 滑块留在原槽位。
  滑块带 `pointerInput`（拖动），Compose 命中测试取最上层节点 ⇒ 那一格的点按被它吃掉。
- 设备证据：切到消息页后，点主 Tab 位置**应用日志里没有产生任何页面切换记录**
  （`files/logs/<日期>/branchbase.log` 只有 `进入消息页`，其后无记录）；同帧的栏像素统计显示
  蓝色仍集中在**左格**（左格 19px 蓝 = 强调色房子图标，右格仅内容透出的蓝点），
  即滑块没动。
- 修：`var currentSlot by remember { mutableIntStateOf(selectedSlot) }`（state 对象必须稳定）。
  同时保留「手势挂在滑块上」（与上游同构），但那意味着**滑块必须始终跟住真实选中项** ——
  这一条写进钉子第十二条。

三条都补进 `GlassBackdropTest` 第十二条（`1122 tests / 0 failures`，钉子 12/12）。
装机复验需用户侧安装：修复版已导出到设备 `Download/DSHA/branchbase-glassfix2.apk`
（md5 `562d9bdb041e0a9a5e4662071decff36`，与构建产物同哈希）——
设备命令通道对 `pm install` 返回 `POLICY_BLOCKED`，容器侧无法自行安装。

## 九、贴底浮动件必须让开悬浮栏（2026-10-08）

**现象**：消息页右下的筛选悬浮球（`NotifFilterFab`）被悬浮导航栏压住。
根因不是这个球写错了，而是**覆盖层契约**：悬浮形态下 `NavigationShell` 回传的内容内边距是
**0**（内容必须铺到栏下方，玻璃才有像素可采），于是页面里 `bottom = 18.dp` 那个位置正好落在栏后面。

**契约（三段，缺一段就会出现「有的让了、有的还挡着」）**：

| 段 | 谁做 | 落点 |
|---|---|---|
| ① 量出「栏占掉多高」 | 壳子 | 栏容器 `.onSizeChanged { barHeightPx = it.height }`；这个数含 `navigationBarsPadding()` + 上下 8dp 留白 + 栏高 64dp |
| ② 下发给页面 | 壳子 | `LocalFloatingBarReservedHeight`（CompositionLocal）；**占位形态恒为 0**，栏收起时也回落 0 |
| ③ 页面让位 | 页面 | 消息页 `val barReserved = LocalFloatingBarReservedHeight.current`，再给**所有**贴底件加：悬浮球 `18.dp + barReserved`、筛选面板 `78.dp + barReserved`、多选条 `padding(bottom = barReserved)`、撤销条 `barReserved + (inSelection ? 66.dp : 12.dp)` |

**为什么用「量」而不是推算**：栏自己带着 `navigationBarsPadding()`，手势条高度随机型 / 分屏 / 横屏变；
`FloatingBarReservedHeight`（栏高 + 上下留白 = 80dp）只作**首帧名义值**，量到之后以实测为准。
页面一律读 CompositionLocal，不许写死 80dp —— 写死的那份迟早与栏对不上。

钉子：`GlassBackdropTest` 第十三条（壳子声明 + 下发 / 页面读它 / 球·面板·多选条·撤销条一起让位）。
同时把原「悬浮形态不许出现 WindowInsets」的钉子改写成「内容内边距必须仍是 0，但让位高度必须量出来」
—— 原钉子防的是「把内容顶上去」，不是禁止测量。

修复版：设备 `Download/DSHA/branchbase-glassfix3.apk`，md5 `8cb0bd3b529481abaf09db33c9f22cf7`
（`1123 tests / 0 failures`，钉子 13/13）。
