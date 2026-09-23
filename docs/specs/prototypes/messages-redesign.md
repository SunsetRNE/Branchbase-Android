<!-- 入库副本：正文与 design/messages-redesign/README.md 一致，未做删改，仅加本段头。 -->
> **来源**：`design/messages-redesign/README.md`（原型本体 `index.html` / `issue.html` / `app.js` / `style.css` 在 `/design/messages-redesign/` 下，按仓库约定**不入库**；DSH 侧边栏「原型预览」面板可直接打开）
> **为什么只把文档抽进来**：原型 HTML 是一次性草图，跟具体实现绑死、很快过期；
> 而这份文档里的**现状测绘、取舍论证与落地清单**是跨时间有效的设计结论，代码注释会引用它。
> **维护**：原型再改时请把这份副本一并更新，别让两边分叉；文中提到的文件名相对原始目录。

# 消息页重设计原型 · 说明与落实方案

> **快照说明（2026-09 补）**：本文是**设计当时的记录** —— 文中的「现状测绘」、「`文件:行号`」、
> 「依据：`xxx.kt`（N 行）」都不会随代码演进更新（只有少数几处加了「2026-09 追记」）。
> 判断当前行为请以代码与 `docs/specs/` 下的规格为准；落地清单里未落地的项只表示「当时计划过」。

> 目录：`design/messages-redesign/`（`/design/` 已在 `.gitignore` 中：原型草图不入库，但能被 DSH 原型预览面板扫到）
> 本文档的**入库副本**：`docs/specs/prototypes/messages-redesign.md`（改完这里请同步那份）
> 目标页面：**消息（通知收件箱）页** + **Issue 单消息页**

## 一、交付物与查看方式

| 文件 | 说明 |
| --- | --- |
| `index.html` | 消息页原型：右下角筛选/视图弹窗面板（含过往 Issue 列表）+ 卡片长按完整状态机 + 多选批量与撤销 |
| `list-v2.html` | **列表重绘对比稿（1.0.76 落地）**：可切「重绘前 / 重绘后」与「浅色 / 深色」，含折叠行展开 |
| `issue.html` | Issue 单消息页原型：对标 github.com issue 页的信息结构与交互细节 |
| `style.css` | 两页共用样式（设计令牌对齐 `ui/theme/Color.kt` 的 `Primer` 对象） |
| `app.js` | 消息页全部交互（过滤/排序/分组/多选/长按/手势/撤销/自动演示） |
| `issue.js` | Issue 页全部交互（Markdown 渲染/反应/任务清单/评论提交/关闭重开/输入器/弹层） |
| `smoke.js` · `theme-audit.js` | `list-v2.html` 的两个脚本级验收（渲染与交互 / 浅深两套对比度），见 §十 |

查看方式（任选其一）：

1. **DSH Web 侧边栏「原型预览」面板** → 选 `design/messages-redesign/index.html`（面板按「设备预设 → 手机 390×844」最贴近真机）；
2. 本地静态服务：`python3 -m http.server 8099 --directory design/messages-redesign`，浏览器打开 `http://127.0.0.1:8099/index.html`（两页互相有跳转链接）。

原型内的左下角 **ⓘ 设计说明 / ▶ 自动演示** 是原型工具（非产品 UI），说明文案与本文档一致。

---

## 一之二、列表重绘对比稿（`list-v2.html`，1.0.76 已落地）

`index.html` 演示的是**整页**（面板 / 长按状态机 / 多选批量）；`list-v2.html` 只聚焦
**列表本身该怎么画**，并带「重绘前 / 重绘后」对照 —— 落地时对着它改的，结论已进
[`screens-design.md` §3](../screens-design.md) 与 [`VERSION-NOTES.md` 1.0.76](../VERSION-NOTES.md)。

三处设计决策：

1. **折叠**「同一仓库 + 同一工作流 + 同一分支 + 同一天」的相邻 CI 通知（口径照抄动态页
   `collapsePushes`，含它的边界：只折相邻、不跨天、解析不出就不折）；
2. **行，而不是卡片盒**：浅色板里 `canvas` 与 `canvasSubtle` 都是纯白，卡片一直只靠那圈灰边撑着；
   未读只留竖条 + 粗标题 + 圆点（竖条仍是 overlay 绘制，不占布局宽度）；
3. **原因标签只在「需要你动手」时显示**（提到了你 / 请求你审查 / 分配给了你 / 安全警报）。

页面上刻意保留了「重绘前」基线（卡片盒 + 8 条重复的 CI 各占一行），用来对照观感。

---

## 二、① 分类设计舍弃 → 右下角弹窗面板

### 现状（代码定位）

| 位置 | 内容 |
| --- | --- |
| `ui/notification/NotificationScreen.kt:613-676` | `FilterRow`：**四等分格**（未读 / 全部 / 参与 / 类型），常驻列表上方，高 46dp |
| `NotificationScreen.kt:661-673` | 类型筛选是 `DropdownMenu`，与格子里的短名两套文案（见 `typeShortName`） |
| `ui/notification/NotificationModels.kt:239-259` | `NotifLayout`（平铺/按仓库/按主题/两级）+ `readNotifLayout/writeNotifLayout` 已持久化，**但没有 UI 入口** |
| `NotificationScreen.kt:505-519` | 多选态下筛选行让位给 `SelectionBar`（两者同为 46dp） |

### 新设计

- **删除常驻筛选行**，列表可视高度 +46dp；
- 右下角常驻 **FAB**，点开自 FAB 正上方展开的 **锚定弹窗面板**（右下角对齐 + 指向箭头），点遮罩 / 再点 FAB / Esc 关闭；
- FAB 徽标 = **生效中的筛选维度数**（0 时隐藏）。不用未读数：未读已经在底部 Tab 有红点，两处重复会误导；
- 面板单列承载全部维度：

| 区块 | 控件 | 备注 |
| --- | --- | --- |
| 分类 | 分段控件：未读 / 全部 / 参与 / 已完成 | 各自带计数，「已完成」= 本地归档（`BulkOp.DONE` 后的去处） |
| 类型 | 多选芯片（带计数）+「清除」 | 显示用 `typeShortName`，比对仍用原始 `subjectType` |
| 视图模式 | 单选卡：平铺 / 按仓库 / 按主题 / 两级 | 直接写 `writeNotifLayout`，与已有持久化打通 |
| 时间范围 | 芯片：今天 / 近 3 天 / 近 7 天 / 全部 | 客户端过滤，`updatedAt` 比较 |
| 排序 | 芯片：最新在前 / 最早在前 / 未读优先 | 服务端 `before` 游标分页下仅作用于已加载段 |
| **过往 Issue** | 搜索框 + 列表 + 行尾「恢复未读」 | 见下 |

**过往 Issue 列表**：把「已完成 / 已静音 / 历史处理过」的 issue-like thread 集中在一处（`Notification` 里 `subjectType ∈ {Issue, PullRequest}`）。
每行：状态徽章（Open/Closed）→ 标题 → `repo #number` → 相对时间；行尾「恢复未读」→ 本地插入一条未读通知 + Snackbar 可撤销。
数据来源建议复用搜索接口 `searchIssues("author:@me state:closed")` / `involves:@me`，缓存键走 `PageCache.TYPE_DETAIL`。

### 交互与状态

| 状态 | 表现 |
| --- | --- |
| 关闭 | FAB 蓝色；有生效维度时右上角黑底徽标数字 |
| 打开 | FAB 变深灰（`--gray-900`）；面板 `scale(0.86)→1` 自右下角弹出；背景遮罩 32% |
| 改动 | **即时生效**，无需「确定」，底部只留「重置 / 完成」 |
| 关闭 | 焦点回到 FAB；`Esc` / 点遮罩 / 点 FAB 三选一 |

---

## 三、② 卡片长按：把交互逻辑补全

现有实现（`NotificationScreen.kt:982-1112`）只有「长按 → 进入多选」一条，且右滑已读与多选共用开关。
新设计把长按扩成完整状态机：

| 手势 | 普通态 | 多选态 |
| --- | --- | --- |
| 轻点 | 本地标已读 → 远端 PATCH → 深链接跳转（`resolveTarget`） | 切换该行选中 |
| 右滑 > 72dp | 标记已读、原地复位（`SwipeToReadRow` 的 `reset()` 语义不变） | **禁用**（避免批量选择时误触） |
| 长按 420ms | 触觉 + 卡片浮起 → **快捷动作面板** | 从锚点到该行 **区间选中** |
| 长按后拖动 | 位移 > 10dp 取消（不进菜单） | **刷选**：按住跨行划过即选中/取消 |
| 点分组头 | 展开 / 收起 | 整组选中 / 取消（半选显示横杠 `mixed`） |

**快捷动作面板**（长按唤出，底部弹层）：标记为已读/未读 · 标记完成 · 静音该会话 · 复制链接 · 在浏览器打开 · 分享 ／ **多选**。
面板底部带一行说明，把「长按 → 动作」「多选中长按 → 区间」「按住划过 → 刷选」三条规则直接讲给用户。

**多选态 UI 换位**：顶部栏换成 **CAB**（✕ / 已选 N 共 M / 全选），底部换成 **操作条**（已读 · 完成 · 静音 · 更多）。
现有实现把 `SelectionBar` 放在原筛选行位置；筛选行删除后，拇指可达的底部位置更合理，且进入多选时列表原地不动。
「更多」弹层承载：复制全部链接 · 在浏览器打开 · 全部标为未读 · 分享所选。

**必补的收尾逻辑**：

1. **撤销**：批量执行后 Snackbar「已处理 N 条 · 撤销」（5s 倒计时条），回滚本地状态并重新写回远端；
2. **执行中**：操作条显示进度转圈、四个动作禁用；退出多选不中断后台请求（沿用 `scope.launch`）；
3. **4 条退出路径**：✕ / 返回键 / 点列表空白 / 批量成功后自动退出；
4. **无重复触发**：多选态下长按不再重复「进入多选」，改为区间选择；
5. **可访问性**：每个纯图标按钮都有 `contentDescription`，长按统一 `performHapticFeedback(LongPress)`。

---

## 四、③ 预渲染 / 预加载提前到首页加载渲染阶段

### 问题

`HomeScreen.kt:167-171` 为了算未读数请求了 `/notifications?per_page=100`，**只用来数长度**；
`NotificationScreen.kt:198-236` 进入页面时再请求一次 `/notifications?per_page=50&all=true`。
同一份数据两次请求，且消息页首帧是骨架屏。

### 目标时序

```
首页渲染 0ms ──┬── /notifications?per_page=50&all=true   → PageCache(TYPE_NOTIFICATION, TTL 2min)
               ├── 解析成 List<Notification> 快照（内存）           ← 「预渲染」
               └── 前 8 条未读 Issue/PR 的 latest_comment_url 预览（并发 2，失败静默）
用户点「消息」Tab ── 0 次网络 + 0 帧骨架：首帧直接渲染快照，后台静默回源
```

### 落地清单（4 处）

1. **`cache/PrefetchPolicy.kt`**：`PrefetchPlan` 增加 `notifications: Boolean`；
   `PrefetchReason.AppStart` 场景返回该项，取值仍是 `userOptIn && !metered`（对齐 `RepoPrefetcher.enabled/metered`）。
2. **`ui/notification/NotificationPrefetcher.kt`（新）**：结构与 `cache/RepoPrefetcher.kt` 一致（`SupervisorJob` + 去重窗口 + fire-and-forget），
   写缓存必须复用 `PageCache.notificationKey(path)`，`path` 与页面 `listPath()` **完全一致**。
   ⚠️ 建议把 `NotificationScreen.kt:185-189` 的 `listPath()` 抽成顶层函数（如 `notifListPath(participating, before)`）供两侧共用 ——
   键少一个 `&all=true` 就会永远不命中，这类 bug 在 README 里已经出现过一次（`PreloadStore.readmeKey` 的分支问题）。
3. **`ui/home/HomeScreen.kt:160-195`**：在现有 `coroutineScope { 5 路并行 }` 里追加一次 AppStart 预取；
   未读计数直接复用同一份响应（`JSONArray(json).length()`），**顺带消掉首页那次重复请求**。
4. **`ui/notification/NotificationScreen.kt`**：新增内存快照直出 —— 首帧同步渲染快照（不进入 `LoadState.Loading`），随后照旧 `PageCache.refresh` 回源。
   ⚠️ 快照必须存**原始 `updatedAt`**、渲染时再算相对时间：现有 `parseNotifications` 在解析期就把 `fmtTime` 算成字符串（`NotificationModels.kt:113-130`），
   直接快照会把「3 分钟前」冻结成旧值。建议 `Notification` 增 `updatedAtMs: Long`，`relativeTime` 改为渲染期派生。

### 预渲染 ≠ 提前请求

内存快照（`object NotifSnapshot { var items: List<Notification>; var previews: Map<String, NotificationPreview> }`）让消息页**首帧无骨架**；
`PageCache` 负责跨进程重启的持久层，两者叠加才是「预渲染 + 预加载」。
预览预取沿用 `NotificationPreviewLoader.prefetchPreviews(maxConcurrent = 2)`，首页阶段只取前 8 条，且**只在非计费网络**。

---

## 五、④ Issue 单消息页（对标网页版）

对比现状 `ui/repository/RepositoryDetailScreens.kt:60-145`（返回栏 + 标题/状态/作者 + 标签 + 正文 + 评论列表），本次原型补齐：

- 状态徽章三态：Open（绿）/ Closed as completed（紫）/ Closed as not planned（灰），点击切换并写入时间线事件，支持撤销；
- 头部信息：标签胶囊（label 色 + 按亮度选黑/白字）、指派者头像、里程碑、评论数；
- 时间线：评论卡与事件行（labeled / assigned / referenced / milestone / closed / reopened）按时间混排；
- 评论卡：作者徽章（作者 / Collaborator）、已编辑标记、⋮ 菜单（复制链接 / 引用回复 / 复制 Markdown / 在浏览器打开）、长按同一菜单；
- 正文 Markdown：标题、列表、**任务清单可勾选**、引用、行内代码、**代码块带复制按钮**、`@提及` 与 `#编号` 高亮、链接；
- 长评折叠：> 190px 渐隐 + 「展开全文 / 收起」；
- 反应：👍 🎉 🚀 👀 计数，点按 +1 / 再点取消，`＋` 唤起表情选择；
- 筛选：全部 / 仅评论 / 仅事件（粘顶）；分页：「显示更早的 N 条评论」插入时间线中段；
- 吸底输入器：写/预览切换、Markdown 工具栏（B / I / 代码 / 链接 / 列表 / 任务 / 引用 / @）、随内容自增高、
  落笔即启用「评论」、滚动时收成一行「留下评论…」、点按展开；
- 滚动行为：应用栏描边、回到顶部悬浮按钮、输入器收起/展开。

### 数据缺口（需要扩展 Rust 侧解析）

| UI 元素 | 现有模型（`RepositoryModels.kt:369-433`） | 需补字段 |
| --- | --- | --- |
| 状态语义 | `IssueDetail.state` | `state_reason`（completed / not_planned / reopened） |
| 指派 / 里程碑 | 无 | `assignees[]`、`milestone.title` |
| 标签颜色 | `labels: List<String>` | `labels[].color`（前端按亮度决定文字色） |
| 时间线事件 | 无 | `GET /repos/{o}/{r}/issues/{n}/timeline` |
| 反应 | 无 | `reactions` + `POST /reactions` |
| 评论分页 | 一次拉全量 | `page`/`Link`，配合 `PageCache.issueCommentsKey` 分页键 |

---

## 六、令牌映射（原型 CSS 变量 → `ui/theme/Color.kt`）

| CSS 变量 | 值 | `Primer` |
| --- | --- | --- |
| `--blue-500` | `#0969DA` | `Blue500` |
| `--blue-600` | `#005CC5` | `Blue600` |
| `--green-500` | `#28A745` | `Green500` |
| `--red-500` | `#D73A49` | `Red500` |
| `--orange-500` | `#F66A0A` | `Orange500` |
| `--purple-500` | `#6F42C1` | `Purple500` |
| `--gray-100/150/200/300/500/600/700/900` | `#F7F7F9` … | `Gray100` … `Gray900` |
| `--fg` / `--fg-muted` / `--fg-subtle` | — | `TextPrimary` / `TextSecondary` / `TextTertiary` |
| `--border` / `--border-strong` | — | `Gray200` / `Border` |
| `--green-web` `#1A7F37`、`--purple-web` `#8250DF`、`--red-web` `#CF222E`、`--gray-1000` `#050505` | — | 网页版 issue 状态色，建议补进 `Primer` 作为 `StateOpen/StateClosed/StateDanger` |

---

## 七、现有实现代码索引（改造时按图索骥）

| 关注点 | 位置 |
| --- | --- |
| 底部导航 2 Tab（Home / 消息）与未读徽标 | `ui/navigation/NavDestination.kt:14-20`、`ui/main/MainScreen.kt:104-142` |
| 通知 → 落地页路由 | `ui/main/MainScreen.kt:146-153`（`toDeepLink`）、`ui/notification/NotificationModels.kt:191-198`（`resolveTarget`） |
| 列表加载 / 缓存直出 / 分页 | `ui/notification/NotificationScreen.kt:185-272` |
| 内容预览预取（限流 + 缓存） | `NotificationScreen.kt:275-310`、`ui/notification/NotificationPreviewLoader.kt:203-257` |
| 已读 / 全部已读 / 回滚 | `NotificationScreen.kt:316-344`、`457-479` |
| 多选与批量（串行 + 间隔 + 回滚） | `NotificationScreen.kt:346-433` |
| 筛选行 / 多选条 / 行 / 分组 / 骨架 | `NotificationScreen.kt:613-676`、`750-796`、`982-1112`、`1157-1218`、`1266-1290` |
| 显示模式持久化 | `NotificationModels.kt:236-259` |
| 通知缓存键与 TTL | `cache/PageCache.kt:62-63`、`cache/SearchCacheManager.kt:49-50` |
| 预加载策略与执行器 | `cache/PrefetchPolicy.kt`、`cache/RepoPrefetcher.kt`、`ui/home/HomeScreen.kt:160-195` |
| Issue 详情页与模型 | `ui/repository/RepositoryDetailScreens.kt:60-145,249-319`、`ui/repository/RepositoryModels.kt:369-433` |
| 深链接入口（`RepoDeepLink.issueNumber`） | `ui/repository/RepositoryScreen.kt:86-94,115,376` |

---

## 八、原型自身的两个坑（已修，留档）

| 坑 | 现象 | 原因与对策 |
| --- | --- | --- |
| 顶层 `var history` | 页面完全不动：列表空白、点 FAB 只有 CSS 按下反馈 | `window.history` 是**只读访问器**，经典脚本的顶层 `var history = [...]` 在求值阶段直接抛 `TypeError: Cannot set property history of #<Window>`，**整个脚本停止执行**。已改名 `pastIssues`。同类别名还有 `location / navigator / top / self / parent / frames / length / document`，写原型脚本时一律避开 |
| 遮罩竞态 | 长按弹出的动作面板「闪一下就消失」 | 长按计时器在手指未抬起时就打开面板，遮罩随之变为可点；手势收尾的兼容性 `click` 落在遮罩上把它关掉。现在遮罩关闭动作在打开后 400ms 内忽略（`state.sheetOpenedAt` / `state.panelOpenedAt`） |

**自诊断**：页面内置两条不依赖调试器的信号 —— `#bootFlag`（纯 CSS 延迟 1.5s 显示，脚本正常会被移除，仍在即说明 JS 没执行）与 `#jsError`（`window.onerror` / `unhandledrejection` 把错误原文显示在顶部）。上面那个 `history` 的 bug 就是靠它一眼定位的。

**测试桩**：`vm.runInThisContext` 执行 + 把 `history/location/navigator/top/self/parent/frames/length` 定义成只读访问器，能复现「顶层 var 撞 Window 只读属性」这类只在真实浏览器出现的致命错误（用 `new Function` 的桩会漏掉，因为 var 退化成函数局部变量）。

---

## 九、验收清单（原型内可直接勾）

**消息页**

- [ ] 列表上方不再有筛选行；FAB 常驻右下角，徽标数字 = 生效维度数，0 时不显示
- [ ] 面板可切分类 / 类型（多选）/ 视图模式 / 时间 / 排序，改动立即反映到列表
- [ ] 「过往 Issue」可搜索，行尾「恢复未读」能把条目推回收件箱，且可撤销
- [ ] 长按卡片 → 触觉 + 卡片浮起 + 快捷动作面板（含「多选」入口）
- [ ] 多选态：点按切换、长按区间选择、按住划过刷选、分组头整组选择（含半选）
- [ ] 批量执行有进度，完成后 Snackbar 5 秒内可撤销
- [ ] 右滑已读在普通态可用、多选态禁用；下拉刷新与滚动分页正常
- [ ] 首帧无骨架屏（首页已预取的直出表现），顶部提示条 6 秒后自动收起

**Issue 页**

- [ ] 状态徽章 / 标签色 / 指派 / 里程碑完整
- [ ] 时间线评论与事件混排，筛选三态可用
- [ ] 任务清单可勾选、代码块可复制、长评可展开
- [ ] 反应可加可取消，长按或 ⋮ 打开评论动作面板
- [ ] 输入器：写/预览切换、工具栏插入、落笔启用「评论」、滚动收起
- [ ] 「关闭并评论」下拉三选项生效；关闭后可撤销重开

---

## 十、`list-v2.html` 的两个脚本级验收

浏览器里靠眼睛看，两件事看不出来：**脚本到底跑没跑**（顶层 `var` 撞 Window 只读属性会让整个脚本
静默停止，页面看着像没做）、**对比度到底够不够**（差 0.3 肉眼分不出，真机上才发现要返工）。
所以这两个原型各带一份 Node 脚本，不需要浏览器、不需要依赖：

```bash
node design/messages-redesign/smoke.js design/messages-redesign        # 渲染与交互
node design/messages-redesign/theme-audit.js design/messages-redesign  # 浅深两套的 WCAG 对比度
```

两份都以**退出码**表态（0 = 通过），可直接接进 CI。

**`smoke.js`（30 项）** —— 最小 DOM 桩 + `vm.runInContext` 真跑页面脚本，并且把
`history / location / navigator / top / self / parent / frames / length`
定义成**只读访问器**，专门复现「顶层 var 撞 Window」这类只在真实浏览器出现的致命错误。
断言覆盖：脚本不抛异常 → 渲染出 15 行 → 折叠行恰好 1 个且明细 8 条全在 →
原因标签的保留 / 抑制名单 → 展开收起点击真会变 → 版本与主题切换 → `#bootFlag` 被移除 →
**CSS 里每个十六进制色值都在 `ThemePalette` 的白名单里**（防止有人悄悄写死一个颜色）。

**`theme-audit.js`（33 项 × 2 套）** —— 从页面的令牌块里读值自己算 WCAG 对比度，
审计的是**真实叠放**（标签字压 12% 自色底、未读行标题压浅蓝底、徽标字压深底），
不是「随便挑两个令牌比一比」。阈值：文本 4.5 / 图形 3.0。

> **它抓到过真问题**：首轮 7 项不达标，根因是把**填充色当文字色**用 ——
> WARNING 2.64 / DANGER 3.85 / ACCENT 4.38，以及深色下 FAB 徽标白字只有 1.18。
> 这正是 [`ui-design.md`](../ui-design.md) 里「别拿 `Red500` 当文字用」那条禁令的同类错误，
> 落地时改成了 `TintRole.textColor()`（见 `ThemeContrastTest`）。
> 解析令牌时有个坑：`[data-theme="dark"]{` 在文件里出现**两次**（一次是 `:root` 块的结束标记），
> 用 `indexOf` 找第一次会切出一段空字符串、令牌全 undefined，审计**静默失效** ——
> 所以脚本里显式从 `:root` 之后再找，并且解析不出令牌时直接退出码 1。
