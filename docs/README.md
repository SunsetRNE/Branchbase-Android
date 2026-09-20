# 设计文档（`/docs/`）· 收纳规则与索引

> **给后来者的第一页。** 这里回答三件事：**该往哪放、什么该入库、什么永远不入库。**
>
> 判断只需要问一句：**「这个结论，半年后还有效吗？」**
> 有效 → 写进 `docs/specs/`（入库）；只在这一次改动的这几天有用 → 那是**草图**，
> 留在 `/design/`，**永远不入库**。

---

## 一、走哪：一眼地图

```
/docs/                            ← 结论类文档，**全部入库**（这个目录不再整体忽略）
├── README.md                     ← 本文件：收纳规则 + 索引
└── specs/                        ← 「详细重点文档」都放这儿
    ├── html-parser-design.md     ← HTML 链接解析与跳转导航的设计契约（规则表 + 已知边界）
    ├── NAVIGATION-NOTES.md       ← 返回键与导航（反复踩坑后定下的两条硬规则）
    ├── BUILD-NOTES.md            ← 构建环境、AGP 9.0 API、版本号体系
    ├── settings-design.md        ← **设置页设计规范**（信息架构 / 6 种行型 / 控件选型 / 用语表 / 危险操作）
    ├── modules-design.md         ← **功能模块族**（沉浸式翻译 / 内建下载 / 图片查看器 / 编辑器 / 作业日志与运行轮询）
    ├── ui-design.md              ← **界面规格**（配色与弹层（单一真源）/ 深色主题 / 动效）
    ├── morph-design.md           ← **图标形变规格**（术语表 / 六步管线与失真点 / 六类成因 / 验收清单 / 极端组合台账）
    ├── screens-design.md         ← **页面重绘**（运行详情卡片流 / 发布三档性质 / 消息卡片流与多选）
    ├── reachability-design.md    ← **远端可达性判定**（三档口径 / 四种跃迁 / 跃迁时丢连接池并重探）
    ├── VERSION-NOTES.md          ← **版本变更记录**（逐版 versionName 说明 + versionCode 流水；新增一版先改这里）
    └── prototypes/               ← 原型**说明文档**的入库副本（原型本体不在这里）
        ├── log-redesign.md
        ├── messages-redesign.md
        ├── workflow-redesign.md
        ├── release-redesign.md
        ├── settings-redesign.md
        └── theme-neutral-preview.md

/design/<原型名>/                  ← 原型**草图本体**（HTML/CSS/JS）：**永久不入库**
README.md                          ← 项目门面 + 功能概览 + 文档指针，留在根目录别挪
```

> **从根 README 迁出的正文**（2026-09：`modules-design.md` / `ui-design.md` / `screens-design.md`）
> 同样属于结论类。头部都标了来源与「正文未删改」，区别只有三点：标题去 emoji、加**章节编号**；
> 空行规整（合并多余空行）；正文里的**仓库根相对链接改成相对本文件的链接**。迁出后 README 只留结论摘要 + 指向，
> 别把正文再抄回去 —— 两处各写一份，迟早分叉。

## 二、存什么 / 不存什么

| 判据 | ✅ 入库：放 `docs/specs/` | ❌ 不入库：留 `/design/` 或别写 |
|---|---|---|
| **时效** | 结论跨版本有效，半年后还会来查 | 跟本次实现绑死，改完就过期 |
| **形态** | 契约 / 规范 / 规则表 / 「为什么这么定」/ 踩过的坑 | 能跑的 HTML 草图、一次性配色对比页 |
| **谁会用** | 代码注释、README、后来改这块的人会指向它 | 只有当时写的人看 |
| **反例** | —— | 「调这个色值试试」的临时预览页 |

对号入座：

- 「HTML 链接怎么分类、§3 的规则表是什么」→ **入库**，`specs/html-parser-design.md`。
- 「返回键为什么必须走 `PageBackHandler`」→ **入库**，`specs/NAVIGATION-NOTES.md`。
- 「消息页重做长什么样」的**可点原型** → **不入库**，留在 `design/messages-redesign/index.html`。
- 同一件事的**说明与落实方案**（现状测绘 / 取舍论证 / 落地清单）→ **入库副本**，
  `specs/prototypes/messages-redesign.md`。

### 为什么「原型不入库、但它的说明文档要抽一份进来」

这两个东西的寿命不一样：

- **原型 HTML/CSS/JS** 是一次性草图 —— 拿来看观感、比方案，落地后就没人再打开；
  它跟具体实现绑死，留着还会被误当成「现状」。
- **说明文档里的现状测绘、取舍论证、落地清单**是**结论** —— 代码注释会直接引用它
  （例如 `NotificationScreen.kt` 就引用了 `design/messages-redesign` 的状态机描述）。

所以：**草图留在 `/design/`（本地可见、DSH 侧边栏「原型预览」面板能扫到），
文档抽一份进 `specs/prototypes/`。** 原型再改时请把副本一并更新，别让两边分叉；
副本头部都标了来源与「正文未做删改」。

## 三、黑名单现状（`.gitignore`）

```gitignore
# 原型设计草图（HTML 草图，永久不入库）
/design/
```

**`/docs/` 不在黑名单里了。** 历史上来过一版「`/docs/` 也整体忽略」的写法，代价是
`core/src/html/mod.rs` 长年引用一份 `docs/html-parser-design.md`，而那份文档**从未进过版本库**、
丢失后无从找回 —— 注释指向一个不存在的文件，比没有注释更坏。**结论类文档必须入库**，
否则引用它的人迟早踩空。

`/design/` 的忽略保留，但请注意它是**唯一**的草图落点：新原型请建在
`/design/<原型名>/`，不要另起顶层目录，也不要往 `/docs/` 里放草图。

## 四、新增一份文档

1. **判定**：过一遍 §二的四条判据。有一条落在右边 → 别写进 `/docs/`。
2. **放哪**：`docs/specs/` 根下平铺（不建子目录，除非像 `prototypes/` 那样成组）。
3. **命名**：
   - 设计契约 / 规格 → `<主题>-design.md`（kebab-case，如 `html-parser-design.md`）；
   - 踩坑型笔记 → `<主题>-NOTES.md`（沿用 `NAVIGATION-NOTES.md` / `BUILD-NOTES.md` 的历史写法）；
   - 原型说明副本 → `prototypes/<原型名>.md`，且**必须**带上来源头（见 §二）。
4. **被代码引用时**：注释里写**仓库相对路径**（如 `docs/specs/html-parser-design.md §3`）。
   引用到**章节号**的，重排章节时必须回头改注释 —— `matcher.rs` 钉着 §3、
   `ReadmeWebView.kt` 钉着 §5.1，就是既有的两个锚点。
5. **顺手做**：在 §五 的索引里加一行，并同步 `README.md` 的「📚 文档导航」表与项目结构块
   （两处都别写反）。

## 五、索引

| 文档 | 一句话 | 谁在引用 |
|---|---|---|
| [`specs/html-parser-design.md`](specs/html-parser-design.md) | 链接如何归一化成跳转目标：§3 规则表、§5.1 末尾斜杠判目录、§6 已知边界 | `core/src/html/mod.rs`、`matcher.rs`、`ReadmeWebView.kt` |
| [`specs/NAVIGATION-NOTES.md`](specs/NAVIGATION-NOTES.md) | 返回键只有一条链路：`PageBackHandler` + 两个切换器都下发 `LocalPageActive` | `README.md` 功能特性、`ui/navigation/` 各页 |
| [`specs/BUILD-NOTES.md`](specs/BUILD-NOTES.md) | AGP 9.0 的 `VariantOutputImpl` 坑 + 版本号体系 + JNI 签名与 locale | `README.md` 构建一节 |
| [`specs/VERSION-NOTES.md`](specs/VERSION-NOTES.md) | **版本变更记录**：§二 `versionName` 逐版说明（每版改了什么、为什么这么改）/ §三 `versionCode` 流水 + 新增一版的写法约定 | `version.properties`（只留 3 个样板并指向它）、`BUILD-NOTES.md` §二 |
| [`specs/modules-design.md`](specs/modules-design.md) | **功能模块族**：§1 沉浸式翻译 / §2 内建下载 / §3 图片查看器 / §4 代码编辑器 / §5 作业日志 / §6 运行中的工作流轮询，各带「已知边界」 | `README.md` 功能模块、`ui/repository/JobLogWiring.kt`、`RunPollPolicy.kt` |
| [`specs/ui-design.md`](specs/ui-design.md) | **界面规格**：§1 配色与弹层（单一真源）/ §2 深色主题（色板 + 角色、对比度、已知取舍）/ §3 动效（页面级 + 元素级） | `ui/theme/*`、`ui/navigation/PageTransitions.kt`、`README.md` 界面规格 |
| [`specs/screens-design.md`](specs/screens-design.md) | **页面重绘**：§1 运行详情卡片流 / §2 发布（三档性质 + 三个页面）/ §3 消息卡片流与多选 | `ReleaseNotesEditor.kt`、`ReleaseEditParts.kt`、`NotificationScreen.kt`、`WorkflowRunDetailScreen.kt` |
| [`specs/reachability-design.md`](specs/reachability-design.md) | **远端可达性判定**：三档（离线 / 直连 / VPN）、四种跃迁才重探、跃迁时三件事（丢连接池 / 清去重窗口 / 重探账号），含已知边界 | `core/NetworkWatch.kt`、`core/ReachabilityPolicy.kt`、`RustBridge.resetHttpClient` |
| [`specs/prototypes/release-redesign.md`](specs/prototypes/release-redesign.md) | 发布三页重绘原型：三档性质 / 垂直预算 694→360dp / 生成说明不覆盖手写行 | `specs/screens-design.md` §2、`ReleaseScreens.kt` |
| [`specs/prototypes/messages-redesign.md`](specs/prototypes/messages-redesign.md) | 消息页重做：右下弹窗面板 / 长按状态机 / 预渲染 / Issue 单消息页 | `NotificationScreen.kt` |
| [`specs/prototypes/workflow-redesign.md`](specs/prototypes/workflow-redesign.md) | Run 详情卡片流 + 作业日志页合并 | `WorkflowRunDetailScreen.kt`、`JobLogScreen.kt` |
| [`specs/prototypes/theme-neutral-preview.md`](specs/prototypes/theme-neutral-preview.md) | 中性色「去灰」三个方案的取值与结论 | `ThemePalette.kt` |
| [`specs/settings-design.md`](specs/settings-design.md) | **设置页设计规范**：两级 IA、6 种行型的封闭集合、控件选型决策树、用语表、危险操作规格、`SettingsSpecTest` 钉子 | `ui/profile/SubPageScreens.kt`、`CommitModeScreen.kt`、`TranslateSettingsScreen.kt`、`AccountsScreen.kt` |
| [`specs/prototypes/log-redesign.md`](specs/prototypes/log-redesign.md) | 日志页重设计原型：chrome 196→110dp、单行网格、9 个写死色值→主题角色 | `docs/specs/settings-design.md`（同族参考） |
| [`specs/prototypes/settings-redesign.md`](specs/prototypes/settings-redesign.md) | 设置页重设计原型：8 套行组件 → 6 种行型、主题去循环化、禁用行给出路、3 个对比度修正 | `docs/specs/settings-design.md` |

---

> **维护这条规则本身**：如果你发现某份该在 `specs/` 里的文档又变成「注释在引用、文件不存在」，
> 先把文档补回来（信息从代码与 commit message 里捞），再改 `.gitignore` —— 顺序反了会继续丢。
