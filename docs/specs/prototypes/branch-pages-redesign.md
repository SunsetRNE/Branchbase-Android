# 分支管理 / 分支对比 · 浮层卡 + 三语义图标 · 设计说明

> 原型：[`design/branch-pages-redesign/index.html`](../../../design/branch-pages-redesign/index.html)
> 状态：**已落地（1.4.97 / versionCode 357）**

## 一、怎么打开

侧边栏「原型预览」打开。四屏 = **分支管理 / 分支对比 × 浅色 / 深色**；
可点 `⋯` 看写操作、点文件头展开差异、点提交卡展开、点对比入口跳到对应主题的对比屏。

## 二、两页原来长什么样

- **分支管理**（`BranchManageScreen.kt`）：白底扁平行；默认 / 受保护只有小文字标签；
  每行右侧同排「对比 / 设为默认 / 删除」三枚文字动作；对比入口是一块 `Gray150` 灰底；新建是整行描边按钮。
- **分支对比**（`BranchCompareScreen.kt`）：两个描边下拉字段；摘要是一块 `Gray150` 三行文字；
  提交与文件差异都是无卡列表；diff 行号列 30 + 30dp、字号 11.5sp。

问题不在功能，在**扫读成本**：状态与动作混在同一行字里，长分支名被三枚动作挤到只剩一截；
对比页 101 个文件时，文件之间没有卡面分隔，摘要、提交、文件三层也读不出层次。

## 三、这版的三条决策

### 1. 内容改用浮层卡

统一走 `cardSurface()`（白面 / `#161B22` + 4dp 阴影 + 12dp 圆角）：

| 位置 | 落地 |
|---|---|
| 分支管理 · 对比入口 | 48dp 卡，`TintChip(ACCENT)` + 说明 + 箭头 |
| 分支管理 · 每条分支 | 38dp 卡，卡距 4dp；写操作收进 `⋯` |
| 分支管理 · 新建 | 38dp 卡，替代整行描边按钮 |
| 分支对比 · 选择器 | 一张 48dp 卡（标签 / 值 / 互换 / 值） |
| 分支对比 · 摘要 | 58dp 卡 |
| 分支对比 · 提交 | 一张可折叠卡 |
| 分支对比 · 每个文件 | 一张卡：36dp 文件头 + 可展开 diff |

### 2. 压缩内容密度

- 分支行 **38dp**（原 38dp 无卡，但三个动作同排）；卡距 4dp；行尾只留「对比」+ `⋯`。
- 长分支名 `weight(1f, fill = false)`：单行省略，**短名时默认 / 受保护标签紧跟名字**，不会被推到行尾。
- 对比选择器合成一张卡；标签 9sp 收进字段上方。
- 文件头 **36dp 起**：状态标签带 9dp 图标，名称与目录两行，`+N -M` 行尾。
- diff 走 `DiffLineRow(compact = true)`：行号列 30 → **25dp**、前缀列 12 → **10dp**、
  代码 11.5 → **10.5sp**、hunk 左右边距 16 → **10dp**；仍与本地 diff 页共用同一份渲染。

### 3. 三语义图标

全部走 `TintRole`，不新造色板；彩色只留给需要一眼分类的地方：

| 语义 | 角色 | 出现处 |
|---|---|---|
| 默认分支 | `ACCENT` | 行首图标牌 + 「默认」标签 |
| 受保护 | `WARNING` | 行首图标牌 + 锁图标标签 |
| 普通分支 | `NEUTRAL_SUBTLE` | 行首图标牌 |
| 领先 | `SUCCESS` | 摘要统计 |
| 落后 | `DANGER` | 摘要统计 |
| 文件数 | `ACCENT` | 摘要统计 |
| 分叉 / 重命名 | `DONE` | 摘要标签 / 文件状态 |
| 新增 / 删除 | `SUCCESS` / `DANGER` | 文件状态 |
| 修改 / 未知 | `NEUTRAL_SUBTLE` | 文件状态 |

### 4. 选择器的底色与分支抽屉（第二轮）

- **底色**：两个选择器不再裸着，也不再等按下才出现一层灰 —— **整个字段（标签 + 值）**
  常驻 `ACCENT` 8% 底、按下 16%（`interactionSource` + `animateColorAsState`，`indication = null`）。
  中间的上下箭头圆钮换成文字「切换」按钮：常驻 `ACCENT` 12%、按下 20%。
- **分支选择**：两个按钮拉起**同一个**自绘 `CardDrawer`，抽屉内复用 `CardSegments`
  （「比较基准 / 对比分支」两段 + 已有的滑动胶囊动画）。默认分段由点的是哪个按钮决定，
  列表内容完全相同；切换分段只改变「正在改哪一侧」，选完按当前分段落 `base` / `head`。
  列表每行一张 10dp / 2dp 阴影的浮层卡（22dp 语义牌、选中蓝字 + 勾），
  行高约 42dp / 长名两行 52dp，按行数封顶 10 行后在抽屉内滚动。

### 5. 0 文件的出路（第二轮）

- 文案从「该对比没有返回文件差异（可能超过 300 个文件上限）」改为
  **「相关分支可能相差 300 个文件以上」**；
- 同一张提示卡右侧加入口，按 `localCommitMode` 切换：
  - 远端（单文件 / 多文件 / 未配置）→「提交模式」，跳 `CommitModeScreen`；
  - 本地仓库 →「本地仓库」，跳 `LocalRepoScreen`；
  - 走 `RepositoryScreen → MainScreen` 的 `onOpenProfileSubPage`，复用个人页的 `pendingSubPage`
    通道，不在这里重写两份设置。

新增共享件 `TintTag`（`app/src/main/java/com/branchbase/ui/TintTag.kt:47`）：
9sp / 同色 12% 底 / `TintRole.textColor()`；与 `TintChip` 同一条 12% 规则，不拿 `Red500` 当文字色。

## 四、落地位置

- `BranchManageScreen.kt:99` 页面；`:493` `BranchManageRow`；`:419` 对比入口；`:597` 新建分支卡。
- `BranchCompareScreen.kt:105` 页面；`:382` 摘要；`:464` 提交卡；`:538` 文件差异卡；
  `:657` 0 文件提示卡（`:685` 右侧入口）；`:710` 选择器；`:749` 切换按钮；`:784` 分支抽屉。
- `RepositoryScreen.kt:225` 的 `onOpenProfileSubPage`；`:939` 把 `mode == LOCAL_REPO` 传给对比页；
  `MainScreen.kt:206` 收起仓库页、打开个人页对应子页。
- `DiffLines.kt:30` 共享 diff 行，新增 `compact` 档（默认档行为不变）。
- 新增字符串：`label_branch_section` / `label_branch_remote` / `label_compare_ahead` /
  `label_compare_behind` / `label_compare_files_unit` / `label_compare_commits`（含 `values-en` 与
  `tools/i18n/strings.tsv`）。

## 五、刻意保留 / 已知边界

- **分支行整行不做点击**：主操作仍是「对比」文字，避免和「长按 / 点行看详情」抢手势；
- **长分支名单行省略**：不改成两行，33 字符的 `backup/pre-squash-…` 在 390dp 下正好卡边界；
- **diff 长行继续折行**：没有改成横向滚动；行号只标在首行（与现有 Compose 行为一致）；
- **摘要与提交仍是两张卡**：合卡还能省约 40dp，但会把两个层级挤在一起，暂不做；
- **提交卡内是一次性组合**：对比页的 `commits` 是 head 领先的提交（通常个位数），未再分页/虚拟化；
  若某次对比返回的提交极多，展开会变重 —— 当前接受，后续需要时再拆回懒列表；
- 写操作仍按 `canPush` 门控；受保护分支仍无删除入口；`.gitignore` 等数据/接口未改。

## 六、验证

- `:app:compileDebugKotlin` 通过；
- `SystemBarInsetsTest` / `GitWorkbenchWiringTest` 等 **188 项单测全绿**；
- 真机观感待装 1.4.97 debug 包后按四屏逐项对照（浅 / 深各一遍）。
